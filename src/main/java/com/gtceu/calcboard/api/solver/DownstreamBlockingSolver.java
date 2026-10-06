package com.gtceu.calcboard.api.solver;

import com.gtceu.calcboard.api.model.FlowGraph;
import com.gtceu.calcboard.api.model.RecipeNode;
import com.gtceu.calcboard.api.util.NumberFormatUtil;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Computes the <em>output-side</em> (backpressure) constraint of a production line.
 *
 * <p>The rest of the solver stack answers one question: "does this machine get enough input?".
 * Nothing answers the opposite question: "can this machine get rid of its output?". In a real line
 * a machine whose outputs have nowhere to go stalls once its output buffer fills, and that stall
 * travels upstream: the stalled machine stops drawing its own inputs, so whatever feeds it stalls
 * too. This class supplies the missing half.
 *
 * <h2>Definitions</h2>
 * <ul>
 *   <li><b>Nominal production</b> of an output port: {@code node.getOutputSlotRate(index, false)},
 *       i.e. recipe amount x output chance x machine count, <em>before</em> any efficiency
 *       throttling. This is the ceiling the port could push out if nothing held it back.</li>
 *   <li><b>Accepted rate</b> of an output port: the effective appetite of everything reachable
 *       downstream, obtained from {@link AutoRatioFlowTraverser} with {@code effective = true} so
 *       that throttled consumers ask for proportionally less. Exports, fixed drains and relays with
 *       their own external supply are already netted out by that traversal.</li>
 *   <li><b>Acceptance ratio</b>: {@code accepted / nominal}, clamped to [0, 1]. A node's acceptance
 *       is the minimum ratio over its output ports. It is the largest efficiency the node may run at
 *       without its outputs backing up.</li>
 * </ul>
 *
 * <h2>Terminal kinds and deliberate policy</h2>
 * <p>A port is only treated as a real constraint when something downstream actually consumes it.
 * Two kinds of terminal are treated as <em>unbounded</em> (no backpressure) so that the mode stays
 * usable on real boards full of byproducts nobody bothers to wire:
 * <ul>
 *   <li>{@link SinkKind#VOID_SINK} - an explicit void sink absorbs everything, by definition.</li>
 *   <li>{@link SinkKind#NO_SINK} - nothing reachable consumes the output at all (unwired port, or a
 *       relay chain that dead-ends). This is <em>assumed to be vented</em> rather than treated as a
 *       stall, because read strictly ("unwired means it backs up") every existing board would read
 *       as completely stalled. The condition is reported through {@link PortAcceptance#sinkKind()}
 *       and {@link NodeAcceptance#hasUnwiredOutput()} so the UI can flag "nothing consumes this"
 *       instead of silently discarding it.</li>
 * </ul>
 * Ports that <em>do</em> reach consumers, exports or drains are constrained honestly - including
 * down to zero, which is the correct answer when a consumer's appetite has collapsed and is exactly
 * the stall the board previously could not show.
 *
 * <p>Stateless and side-effect free; computes from the graph's current node efficiencies.
 */
public final class DownstreamBlockingSolver {

    /** Production below this rate is treated as "this port produces nothing" and is skipped. */
    private static final double RATE_EPSILON = 1e-6;

    /** A ratio within this distance of 1.0 counts as unconstrained. */
    public static final double RATIO_EPSILON = 1e-4;

    /**
     * Nodes currently being analysed on this thread. The acceptance ceiling of a node is derived from
     * the ceilings of its consumers, so analysing a cyclic graph would otherwise recurse forever.
     */
    private static final ThreadLocal<Set<String>> ANALYSING = ThreadLocal.withInitial(HashSet::new);

    private DownstreamBlockingSolver() {}

    /**
     * What an output port ultimately leads to.
     */
    public enum SinkKind {
        /** Nothing reachable consumes this port's output (unwired, or a dead-ended relay chain). */
        NO_SINK,
        /** A void sink is reachable: accepts any amount, so the port is never constrained. */
        VOID_SINK,
        /** Reachable consumers, exports and/or fixed drains define a finite, measurable appetite. */
        CONSUMERS;

        /**
         * @return true when this terminal imposes no upper bound on the producing port.
         */
        public boolean isUnbounded() {
            return this != CONSUMERS;
        }
    }

    /**
     * Backpressure analysis of a single output port.
     *
     * @param outputIndex  port index on the producer
     * @param resourceName display name of the resource, for tooltips (may be empty)
     * @param nominalRate  nominal production rate before efficiency ({@code getOutputSlotRate(i, false)})
     * @param acceptedRate effective downstream appetite, only meaningful for {@link SinkKind#CONSUMERS}
     * @param sinkKind     what the port leads to
     * @param consumerCount number of consuming input ports reachable from this port
     */
    public record PortAcceptance(
            int outputIndex,
            String resourceName,
            double nominalRate,
            double acceptedRate,
            SinkKind sinkKind,
            int consumerCount
    ) {
        /**
         * @return the largest efficiency fraction this port tolerates, in [0, 1].
         */
        public double ratio() {
            if (sinkKind.isUnbounded() || nominalRate <= RATE_EPSILON) {
                return 1.0;
            }
            return Math.max(0.0, Math.min(1.0, acceptedRate / nominalRate));
        }

        /**
         * @return true when this port is the reason its producer cannot run at full speed.
         */
        public boolean isConstraining() {
            return ratio() < 1.0 - RATIO_EPSILON;
        }
    }

    /**
     * Backpressure analysis of a whole node: the tightest of its output ports.
     *
     * @param ratio             smallest acceptance ratio across output ports, in [0, 1]
     * @param bindingPort       the port producing that ratio, or null when unconstrained
     * @param hasUnwiredOutput  true when at least one producing port leads nowhere
     * @param hasVoidOutput     true when at least one producing port leads to a void sink
     */
    public record NodeAcceptance(
            double ratio,
            PortAcceptance bindingPort,
            boolean hasUnwiredOutput,
            boolean hasVoidOutput
    ) {
        public static final NodeAcceptance UNCONSTRAINED =
                new NodeAcceptance(1.0, null, false, false);

        /**
         * @return true when downstream demand throttles this node below full speed.
         */
        public boolean isConstraining() {
            return ratio < 1.0 - RATIO_EPSILON;
        }

        /**
         * @return display name of the resource backing up, or empty when none does.
         */
        public String bindingResourceName() {
            return bindingPort != null && bindingPort.resourceName() != null
                    ? bindingPort.resourceName()
                    : "";
        }

        /**
         * @return display name of the resource backing up, or null when nothing is constraining.
         *         Null (rather than empty) is the "no constraint recorded" state used by nodes solved
         *         without blocking awareness.
         */
        public String bindingResourceNameOrNull() {
            if (bindingPort == null || bindingPort.resourceName() == null || bindingPort.resourceName().isEmpty()) {
                return null;
            }
            return bindingPort.resourceName();
        }
    }

    /**
     * Computes the acceptance ratio of every machine/module node in the graph (junctions are relays
     * or sources and carry no production of their own, so they come back as unconstrained).
     *
     * @return node id to acceptance ratio in [0, 1]; nodes absent from the map are unconstrained
     */
    public static java.util.Map<String, Double> computeNodeAcceptanceRatios(FlowGraph graph) {
        java.util.Map<String, Double> ratios = new java.util.HashMap<>();
        if (graph == null) {
            return ratios;
        }
        for (RecipeNode node : graph.getNodes()) {
            ratios.put(node.getId(), acceptanceRatio(graph, node));
        }
        return ratios;
    }

    /**
     * Acceptance ratio of a node, i.e. the largest efficiency it may run at given the appetite of
     * whatever is wired to its outputs.
     *
     * @return a value in [0, 1]; 1.0 for junction nodes, nodes with no producing output ports, or
     *         ports that lead nowhere / to a void sink
     */
    public static double acceptanceRatio(FlowGraph graph, RecipeNode node) {
        if (graph == null || node == null || node.isReroute()) {
            return 1.0;
        }
        return analyzeNode(graph, node).ratio();
    }

    /**
     * Full backpressure analysis of a node's output ports.
     */
    public static NodeAcceptance analyzeNode(FlowGraph graph, RecipeNode node) {
        if (graph == null || node == null || node.isReroute()) {
            return NodeAcceptance.UNCONSTRAINED;
        }
        Set<String> inProgress = ANALYSING.get();
        if (!inProgress.add(node.getId())) {
            // This node is already being analysed further up the stack: the graph has a cycle. Report
            // it unconstrained - the same convention the topological recirculation guard uses - and let
            // the forward pass account for the loop, rather than recursing until the stack dies.
            return NodeAcceptance.UNCONSTRAINED;
        }
        try {
            return analyzeNodeUncycled(graph, node);
        } finally {
            inProgress.remove(node.getId());
        }
    }

    private static NodeAcceptance analyzeNodeUncycled(FlowGraph graph, RecipeNode node) {
        double ratio = 1.0;
        PortAcceptance binding = null;
        boolean unwired = false;
        boolean voided = false;
        boolean constrained = false;

        int outputCount = node.getOutputs().size();
        for (int i = 0; i < outputCount; i++) {
            double nominal = node.getOutputSlotRate(i, false);
            if (nominal <= RATE_EPSILON) {
                continue;
            }
            PortAcceptance port = analyzeOutputPort(graph, node, i);
            if (port.sinkKind() == SinkKind.NO_SINK) {
                unwired = true;
            } else if (port.sinkKind() == SinkKind.VOID_SINK) {
                voided = true;
            } else {
                constrained = true;
                if (port.ratio() < ratio) {
                    ratio = port.ratio();
                    binding = port;
                }
            }
        }

        if (!constrained) {
            return new NodeAcceptance(1.0, null, unwired, voided);
        }
        return new NodeAcceptance(ratio, binding, unwired, voided);
    }

    /**
     * Backpressure analysis of a single output port.
     */
    public static PortAcceptance analyzeOutputPort(FlowGraph graph, RecipeNode producer, int outputIndex) {
        if (graph == null || producer == null || outputIndex < 0) {
            return new PortAcceptance(outputIndex, "", 0.0, 0.0, SinkKind.NO_SINK, 0);
        }

        String resourceName = resourceName(producer, outputIndex);
        double nominal = producer.getOutputSlotRate(outputIndex, false);

        List<FlowGraph.ConnectionEdge> outgoing = edgesFrom(graph, producer.getId(), outputIndex);
        if (outgoing.isEmpty()) {
            return new PortAcceptance(outputIndex, resourceName, nominal, 0.0, SinkKind.NO_SINK, 0);
        }

        SinkKind kind = classifyDownstream(graph, outgoing);
        if (kind != SinkKind.CONSUMERS) {
            return new PortAcceptance(outputIndex, resourceName, nominal, 0.0, kind, 0);
        }

        // A port whose flow can find its way back to its own producer is part of a recirculating
        // loop. There the consumer's appetite is *self-referential*: the loop consumes whatever it
        // produces, so discounting its appetite by its own current throttle makes every sweep
        // multiply the producer's efficiency by another factor below one. The product of those
        // factors drives the entire branch to zero - the collapse this mode showed on real boards
        // (a producer capped at 1.5% of nominal ended up at 0.0002%). Nominal appetite is the
        // self-consistent answer inside a loop, and the supply-side loop solver already accounts
        // for the recirculation ratio, so nothing is double-counted.
        boolean recirculating = recirculatesToProducer(graph, producer, outgoing);
        double accepted = recirculating
                ? FlowBalanceMatrixSolver.calculateTotalConnectedPortDemand(graph, producer, outputIndex)
                : capacityAnchoredAppetite(graph, producer, outgoing);
        int consumers = countConsumingPorts(graph, outgoing);
        return new PortAcceptance(outputIndex, resourceName, nominal, accepted, SinkKind.CONSUMERS, consumers);
    }

    /**
     * Capacity-anchored appetite of a single edge, i.e. how much this consumer could take through this
     * edge if the line were running flat out. See {@link #capacityAnchoredAppetite} for why the current
     * throttle must not be used as the yardstick.
     *
     * <p>Also used by {@code FlowEdgeAllocator} as the weight it splits a producer's output by: the
     * distribution between consumers has exactly the same self-reference problem as the ceiling does,
     * so both must be measured from the same anchored figure.
     *
     * @return appetite in the port's own units, or 0.0 when the edge has no resolvable appetite
     */
    public static double edgeAppetite(FlowGraph graph, RecipeNode producer, FlowGraph.ConnectionEdge edge) {
        if (graph == null || producer == null || edge == null) return 0.0;
        RecipeNode consumer = graph.findNodeById(edge.toNodeId());
        if (consumer == null) return 0.0;
        double want = consumer.getInputSlotRate(edge.inputIndex(), false);
        if (want <= RATE_EPSILON) return 0.0;
        double discount = Math.min(1.0, analyzeNode(graph, consumer).ratio());
        discount = Math.min(discount, otherFeedCoverage(graph, consumer, producer));
        return want * Math.max(0.0, discount);
    }

    /**
     * How much this port's consumers could absorb if the line were running flat out: a ceiling that is
     * a property of the graph's hardware, not of the current throttles.
     *
     * <p>This must NOT be measured from the consumers' <em>current</em> appetite. A consumer's
     * efficiency is frequently explained by the very flow it receives from this producer, so using it
     * makes the ceiling self-referential. With one producer feeding two consumers that it also
     * throttles, the equation degenerates to {@code L <= L}: every efficiency satisfies it, and which
     * one gets reported is decided by the iteration path rather than by the line. A 30 B/min reactor
     * feeding consumers that could take 16.8 B/min reported 3.858 B/min that way.
     *
     * <p>So each consumer contributes its <em>nominal</em> appetite for the resource, discounted only
     * by constraints that do not depend on this producer: its own downstream acceptance ceiling, and
     * how well its other feeds are covered. Together those keep the cross-input cascade - a consumer
     * throttled by a different feed still pulls proportionally less from this one - without the
     * feedback loop.
     */
    private static double capacityAnchoredAppetite(
            FlowGraph graph,
            RecipeNode producer,
            List<FlowGraph.ConnectionEdge> outgoing
    ) {
        double total = 0.0;
        for (FlowGraph.ConnectionEdge edge : outgoing) {
            total += edgeAppetite(graph, producer, edge);
        }
        return total;
    }

    /**
     * @return the fraction of a consumer's requirement that its feeds <em>other than</em> this producer
     *         can structurally cover, in [0, 1]; 1.0 when every other port is unwired (free supply) or
     *         backed by unbounded capacity. Ports fed by the producer under test are skipped, so the
     *         result cannot depend on it.
     *
     * <p>Deliberately measured against producers' <em>nominal</em> output rather than the flow actually
     * allocated to the port. Using the allocated flow would be a second self-reference - a shortfall
     * anywhere upstream would be defined partly by this producer's own output - and because the terms
     * are combined with {@code min}, a single transient zero would propagate as a hard zero and pin the
     * whole branch at 0.0 instead of leaving it to the forward pass.
     */
    private static double otherFeedCoverage(FlowGraph graph, RecipeNode consumer, RecipeNode producer) {
        if (consumer.isReroute()) return 1.0;
        double coverage = 1.0;
        for (int port = 0; port < consumer.getInputs().size(); port++) {
            double required = consumer.getInputSlotRate(port, false);
            if (required <= RATE_EPSILON) continue;
            boolean wired = false;
            boolean fedByProducer = false;
            boolean unbounded = false;
            double capacity = 0.0;
            for (FlowGraph.ConnectionEdge in : graph.getConnections()) {
                if (!in.toNodeId().equals(consumer.getId()) || in.inputIndex() != port) continue;
                wired = true;
                if (in.fromNodeId().equals(producer.getId())) {
                    fedByProducer = true;
                    continue;
                }
                RecipeNode upstream = graph.findNodeById(in.fromNodeId());
                if (upstream == null) continue;
                if (upstream.isInfiniteSupply() || upstream.isBaseNode()) {
                    unbounded = true;
                    continue;
                }
                capacity += upstream.getOutputSlotRate(in.outputIndex(), false);
            }
            if (!wired || fedByProducer || unbounded) continue;
            coverage = Math.min(coverage, Math.max(0.0, Math.min(1.0, capacity / required)));
        }
        return coverage;
    }

    /**
     * @return true when any path leaving this port leads back to the producing node, i.e. the flow
     *         recirculates and the downstream appetite cannot be treated as an independent,
     *         one-way constraint
     */
    private static boolean recirculatesToProducer(
            FlowGraph graph,
            RecipeNode producer,
            List<FlowGraph.ConnectionEdge> outgoing
    ) {
        Deque<Hop> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        for (FlowGraph.ConnectionEdge edge : outgoing) {
            queue.add(new Hop(edge.toNodeId(), edge.inputIndex()));
        }
        while (!queue.isEmpty()) {
            Hop hop = queue.poll();
            if (hop.nodeId().equals(producer.getId())) {
                return true;
            }
            if (!visited.add(hop.nodeId() + ":" + hop.inputIndex())) {
                continue;
            }
            RecipeNode node = graph.findNodeById(hop.nodeId());
            if (node == null) {
                continue;
            }
            if (node.isReroute()) {
                // a relay that bleeds flow out of the graph (void sink, fixed drain, export) still
                // passes whatever its consumers take on to its own outputs
                for (FlowGraph.ConnectionEdge edge : graph.getConnections()) {
                    if (edge.fromNodeId().equals(node.getId())) {
                        queue.add(new Hop(edge.toNodeId(), edge.inputIndex()));
                    }
                }
                continue;
            }
            for (FlowGraph.ConnectionEdge edge : graph.getConnections()) {
                if (edge.fromNodeId().equals(node.getId())) {
                    queue.add(new Hop(edge.toNodeId(), edge.inputIndex()));
                }
            }
        }
        return false;
    }

    /**
     * Human-readable reason for a constrained node, used by tooltips.
     *
     * @return e.g. {@code "Sulfur Dioxide - consumers take 0.5/s of 1.0/s"}, or empty when the node
     *         is not constrained
     */
    public static String describeConstraint(FlowGraph graph, RecipeNode node) {
        NodeAcceptance acceptance = analyzeNode(graph, node);
        if (!acceptance.isConstraining() || acceptance.bindingPort() == null) {
            return "";
        }
        PortAcceptance port = acceptance.bindingPort();
        String name = port.resourceName() == null || port.resourceName().isEmpty()
                ? ("output #" + (port.outputIndex() + 1))
                : port.resourceName();
        return name + " - consumers take " + NumberFormatUtil.formatCompactNumber(port.acceptedRate())
                + "/s of " + NumberFormatUtil.formatCompactNumber(port.nominalRate()) + "/s";
    }

    /**
     * Walks every edge leaving a port, through relay junctions, and classifies what it reaches.
     *
     * @return {@link SinkKind#VOID_SINK} as soon as a void sink is reachable (accepts anything,
     *         regardless of what else is found), otherwise {@link SinkKind#CONSUMERS} when anything
     *         consumes the flow, otherwise {@link SinkKind#NO_SINK}
     */
    private static SinkKind classifyDownstream(FlowGraph graph, List<FlowGraph.ConnectionEdge> outgoing) {
        Deque<Hop> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        for (FlowGraph.ConnectionEdge edge : outgoing) {
            queue.add(new Hop(edge.toNodeId(), edge.inputIndex()));
        }

        boolean foundConsumer = false;

        while (!queue.isEmpty()) {
            Hop hop = queue.poll();
            if (!visited.add(hop.nodeId() + ":" + hop.inputIndex())) {
                continue;
            }
            RecipeNode node = graph.findNodeById(hop.nodeId());
            if (node == null) {
                continue;
            }
            if (node.isVoidSink()) {
                return SinkKind.VOID_SINK;
            }
            if (node.isReroute()) {
                if (reachesTerminalSink(node)) {
                    foundConsumer = true;
                }
                if (node.isInfiniteSupply()) {
                    continue;
                }
                for (FlowGraph.ConnectionEdge edge : graph.getConnections()) {
                    if (edge.fromNodeId().equals(node.getId()) && edge.outputIndex() == 0) {
                        queue.add(new Hop(edge.toNodeId(), edge.inputIndex()));
                    }
                }
                continue;
            }
            if (hop.inputIndex() >= 0 && hop.inputIndex() < node.getInputs().size()) {
                foundConsumer = true;
            }
        }

        return foundConsumer ? SinkKind.CONSUMERS : SinkKind.NO_SINK;
    }

    /**
     * @return true when a relay junction removes flow from the graph by itself (fixed drain or
     *         allocated export), making it a real sink even without downstream nodes
     */
    private static boolean reachesTerminalSink(RecipeNode junction) {
        if (junction.isFixedDrain() && junction.getExternalDrainRate() > RATE_EPSILON) {
            return true;
        }
        return junction.getAllocatedExportRate() > RATE_EPSILON;
    }

    private static int countConsumingPorts(FlowGraph graph, List<FlowGraph.ConnectionEdge> outgoing) {
        int count = 0;
        Set<String> visited = new HashSet<>();
        Deque<Hop> queue = new ArrayDeque<>(outgoing.stream()
                .map(edge -> new Hop(edge.toNodeId(), edge.inputIndex()))
                .toList());
        while (!queue.isEmpty()) {
            Hop hop = queue.poll();
            if (!visited.add(hop.nodeId() + ":" + hop.inputIndex())) {
                continue;
            }
            RecipeNode node = graph.findNodeById(hop.nodeId());
            if (node == null) {
                continue;
            }
            if (node.isReroute()) {
                if (reachesTerminalSink(node)) {
                    count++;
                }
                if (node.isInfiniteSupply()) {
                    continue;
                }
                for (FlowGraph.ConnectionEdge edge : graph.getConnections()) {
                    if (edge.fromNodeId().equals(node.getId()) && edge.outputIndex() == 0) {
                        queue.add(new Hop(edge.toNodeId(), edge.inputIndex()));
                    }
                }
                continue;
            }
            if (hop.inputIndex() >= 0 && hop.inputIndex() < node.getInputs().size()) {
                count++;
            }
        }
        return count;
    }

    private static List<FlowGraph.ConnectionEdge> edgesFrom(FlowGraph graph, String nodeId, int outputIndex) {
        List<FlowGraph.ConnectionEdge> edges = new ArrayList<>();
        for (FlowGraph.ConnectionEdge edge : graph.getConnections()) {
            if (edge.fromNodeId().equals(nodeId) && edge.outputIndex() == outputIndex) {
                edges.add(edge);
            }
        }
        return edges;
    }

    private static String resourceName(RecipeNode node, int outputIndex) {
        try {
            if (outputIndex >= 0 && outputIndex < node.getOutputs().size()) {
                String name = node.getOutputs().get(outputIndex).getDisplayName();
                return name != null ? name : "";
            }
        } catch (Throwable ignored) {
            // display names are cosmetic; never let them break a solve
        }
        return "";
    }

    private record Hop(String nodeId, int inputIndex) {}
}
