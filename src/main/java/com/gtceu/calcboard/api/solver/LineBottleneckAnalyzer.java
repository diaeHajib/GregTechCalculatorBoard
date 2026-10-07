package com.gtceu.calcboard.api.solver;

import com.gtceu.calcboard.api.model.FlowGraph;
import com.gtceu.calcboard.api.model.RecipeNode;
import com.gtceu.calcboard.api.type.LineSolveMode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.WeakHashMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

/**
 * Answers one question: <em>if you build one more of some machine on this line, which one buys you the
 * most?</em> The answer is published as a single {@link RecipeNode#isBottleneck()} flag, and the
 * renderer outlines that card apart from every other state.
 *
 * <h2>Why this is measured, not inferred</h2>
 *
 * <p>Every per-node heuristic for "bottleneck" is wrong, and this board proves it:
 * <ul>
 *   <li>the machine at its own ceiling is not the answer - a saturated machine can be saturated
 *       because nothing downstream will take more, in which case expanding it gains exactly nothing;</li>
 *   <li>the most-utilised machine is not the answer either - two machines with identical efficiency
 *       and identical blocking ratio on the same line routinely have completely different gains;</li>
 *   <li>which port clips a machine says nothing about whether adding capacity to it helps.</li>
 * </ul>
 * The constraint is a property of the whole line, so it is measured: each machine's count is nudged
 * up by one, the line is re-solved, and the change in the line's output is recorded. The machine with
 * the largest gain is the bottleneck. That is also exactly what a player means by the word.
 *
 * <h2>Line output</h2>
 *
 * <p>The gain is the <em>median relative change across every product the line exports</em> - every
 * output port that leads nowhere, i.e. what the board actually ships. Median rather than mean so one
 * huge waste stream cannot dominate, and relative rather than absolute so a 6 000/min acid stream
 * cannot drown out a 4/min dust. On a line that is one coupled chain every product scales by the same
 * fraction, so the median is simply "how much the whole line improves". On a branched line a machine
 * that only helps one arm of the graph scores near zero, which is the honest reading of "improves
 * things around".
 *
 * <p>With machine targets, compare effective recipe cycles against each target's baseline nominal
 * capacity instead. Sorted throughput fractions are compared lexicographically, weakest first.
 * The baseline capacities stay fixed during trials, so adding a target's own capacity counts as
 * useful only when its actual work increases. Target selection never changes the operating solver.
 *
 * <h2>Behaviour over time</h2>
 *
 * <p>Nothing here is sticky: the sweep runs whenever the board is re-solved, so as soon as the
 * highlighted machine has been built a few times its own gain shrinks and the next best machine takes
 * the outline. That reproduces "expand the top one until it stops paying, then move to the next"
 * without any priority list to configure. It never declares the line finished - the player decides
 * when to stop.
 *
 * <h2>Cost</h2>
 *
 * <p>N trial solves plus baseline/restoration solves for N machines, on board re-solve only, never per
 * frame. Weak per-graph cache entries compare calculation content, including no-benefit results.
 * Layout and transient efficiencies do not invalidate the cache.
 */
public final class LineBottleneckAnalyzer {

    /** Ignore gains below this: float noise and rounding, not a real improvement. */
    private static final double GAIN_EPSILON = 1e-6;

    /**
     * Graphs currently being swept. Tracked per graph rather than with one global flag so that a
     * nested solve of a <em>different</em> graph - a module's sub-page, say - still gets its own sweep
     * instead of being silently skipped and left showing a stale highlight.
     */
    private static final Set<FlowGraph> SWEEPING = ConcurrentHashMap.newKeySet();

    private record CachedResult(CompoundTag content, String nodeId, double gain, Map<String, Double> gains) {}
    private record TargetCapacity(RecipeNode node, double nominalRate) {}
    private static final Map<FlowGraph, CachedResult> CACHE = Collections.synchronizedMap(new WeakHashMap<>());
    private static Map<String, Double> lastGains = Map.of();

    /**
     * @return node id to measured gain from the most recent sweep, for diagnostics and tests. Empty
     *         until a sweep has run; unchanged when a sweep is served from cache, which is correct
     *         because a cache hit means the content - and therefore the gains - are identical.
     */
    static Map<String, Double> lastGains() {
        return lastGains;
    }

    private LineBottleneckAnalyzer() {}

    /**
     * Recomputes the bottleneck flag for the whole line. Clears the flag on every node first, so a
     * machine that stopped being the best buy is released immediately.
     *
     * @param graph graph that was just solved
     * @param mode  solve mode in force, used when re-solving each perturbed variant
     */
    public static void markBottlenecks(FlowGraph graph, LineSolveMode mode) {
        if (graph == null) return;

        if (!SWEEPING.add(graph)) {
            // Called from one of our own perturbation solves: the outer sweep owns the flags.
            return;
        }

        String bestId = null;
        double bestGain = 0.0;
        boolean restore = false;
        try {
            CompoundTag content = contentState(graph, mode);
            CachedResult cached = CACHE.get(graph);
            if (cached != null && cached.content().equals(content)) {
                lastGains = cached.gains();
                applyFlag(graph, cached.nodeId(), cached.gain());
                return;
            }

            // Settle the graph before measuring, so `base` reflects a solved line rather than whatever
            // efficiency state the caller happened to leave behind. Without this the answer depends on
            // call order instead of on the board's content alone.
            FixedPointEfficiencySolver.computeNodeEfficiencies(graph, mode);
            boolean targeted = !graph.getRecommendationTargetIds().isEmpty();
            Map<String, Double> base = targeted ? Map.of() : exportRates(graph);
            List<TargetCapacity> targets = new ArrayList<>();
            for (RecipeNode node : graph.getNodes()) {
                if (!node.isMachine() || !graph.getRecommendationTargetIds().contains(node.getId())) continue;
                double nominal = node.getNominalCyclesPerSecond();
                if (Double.isFinite(nominal) && nominal > 0.0) targets.add(new TargetCapacity(node, nominal));
            }
            double[] baselineTargets = targetThroughputs(targets);
            double[] bestTargets = baselineTargets;

            List<RecipeNode> machines = new ArrayList<>();
            for (RecipeNode node : graph.getNodes()) {
                if (node == null || node.isReroute() || !node.isMachine()) continue;
                if (node.getOutputs().isEmpty()) continue;
                machines.add(node);
            }

            Map<String, Double> gains = new LinkedHashMap<>();
            for (RecipeNode machine : machines) {
                double count = machine.getMachineCount();
                if (count <= 0.0) continue;
                restore = true;
                try {
                    machine.setMachineCount(count + 1.0);
                    FixedPointEfficiencySolver.computeNodeEfficiencies(graph, mode);
                    if (targeted) {
                        double[] after = targetThroughputs(targets);
                        double gain = firstTargetDifference(baselineTargets, after);
                        gains.put(machine.getId(), gain);
                        if (gain > GAIN_EPSILON && firstTargetDifference(bestTargets, after) > GAIN_EPSILON) {
                            bestTargets = after;
                            bestId = machine.getId();
                            bestGain = gain;
                        }
                    } else {
                        gains.put(machine.getId(), medianRelativeGain(base, exportRates(graph)));
                    }
                } finally {
                    machine.setMachineCount(count);
                }
            }

            if (!targeted) {
                bestGain = GAIN_EPSILON;
                for (Map.Entry<String, Double> entry : gains.entrySet()) {
                    if (Double.isFinite(entry.getValue()) && entry.getValue() > bestGain) {
                        bestGain = entry.getValue();
                        bestId = entry.getKey();
                    }
                }
            }
            if (bestId == null) bestGain = 0.0;
            Map<String, Double> measuredGains = Map.copyOf(gains);

            // Put the graph back the way we found it - efficiencies, blocking info and caches all
            // follow from this final solve, and the guard keeps it from sweeping again.
            FixedPointEfficiencySolver.computeNodeEfficiencies(graph, mode);
            restore = false;
            lastGains = measuredGains;
            CACHE.put(graph, new CachedResult(content, bestId, bestGain, measuredGains));
            applyFlag(graph, bestId, bestGain);
        } finally {
            try {
                if (restore) FixedPointEfficiencySolver.computeNodeEfficiencies(graph, mode);
            } finally {
                SWEEPING.remove(graph);
            }
        }
    }

    private static void applyFlag(FlowGraph graph, String nodeId, double gain) {
        for (RecipeNode node : graph.getNodes()) {
            if (node == null) continue;
            boolean hit = nodeId != null && nodeId.equals(node.getId());
            node.setBottleneck(hit);
            node.setBottleneckGain(hit ? gain : 0.0);
        }
    }

    private static double[] targetThroughputs(List<TargetCapacity> targets) {
        double[] rates = new double[targets.size()];
        for (int i = 0; i < targets.size(); i++) {
            TargetCapacity target = targets.get(i);
            rates[i] = target.node().getEffectiveCyclesPerSecond() / target.nominalRate();
            if (!Double.isFinite(rates[i])) {
                throw new IllegalStateException("Non-finite recommendation target throughput: " + target.node().getId());
            }
        }
        Arrays.sort(rates);
        return rates;
    }

    private static double firstTargetDifference(double[] before, double[] after) {
        for (int i = 0; i < before.length; i++) {
            double difference = after[i] - before[i];
            if (Math.abs(difference) > GAIN_EPSILON) return difference;
        }
        return 0.0;
    }

    /**
     * @return effective rate of every output port that leads nowhere, keyed by resource name - i.e.
     *         what the line ships, in per-minute terms
     */
    private static Map<String, Double> exportRates(FlowGraph graph) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (RecipeNode node : graph.getNodes()) {
            if (node == null) continue;
            for (int o = 0; o < node.getOutputs().size(); o++) {
                boolean wired = false;
                for (FlowGraph.ConnectionEdge edge : graph.getConnections()) {
                    if (edge.fromNodeId().equals(node.getId()) && edge.outputIndex() == o) {
                        wired = true;
                        break;
                    }
                }
                if (wired) continue;
                out.merge(node.getOutputs().get(o).getDisplayName(), node.getOutputSlotRate(o, true) * 60.0, Double::sum);
            }
        }
        return out;
    }

    private static double medianRelativeGain(Map<String, Double> base, Map<String, Double> after) {
        List<Double> ratios = new ArrayList<>();
        for (Map.Entry<String, Double> entry : base.entrySet()) {
            double before = entry.getValue();
            if (!(before > 0.0) || !Double.isFinite(before)) continue;
            double now = after.getOrDefault(entry.getKey(), 0.0);
            if (!Double.isFinite(now)) continue;
            ratios.add(now / before - 1.0);
        }
        if (ratios.isEmpty()) return 0.0;
        ratios.sort(Double::compare);
        int mid = ratios.size() / 2;
        return ratios.size() % 2 == 1 ? ratios.get(mid) : 0.5 * (ratios.get(mid - 1) + ratios.get(mid));
    }

    /**
     * Diagnostic hash of the calculation state. The cache itself compares the complete state, not
     * this hash, and uses graph identity. Serialized hardware/properties, nominal adapter rates,
     * subgraphs, junction allocations and full edge settings participate.
     *
     * <p>Card positions, zoom and pan are deliberately excluded: dragging a card is an edit that cannot
     * change the physics, and hashing the layout would re-run an N+1 solve sweep on every frame of a
     * drag. Layout changes therefore do not invalidate, by intent.
     *
     * <p>Package-private so the invalidation contract can be pinned by a test.
     */
    static long contentKey(FlowGraph graph, LineSolveMode mode) {
        return 31L * System.identityHashCode(graph) + contentState(graph, mode).hashCode();
    }

    private static CompoundTag contentState(FlowGraph graph, LineSolveMode mode) {
        CompoundTag state = graphContent(graph, Collections.newSetFromMap(new IdentityHashMap<>()));
        state.putString("solveMode", mode != null ? mode.name() : LineSolveMode.SUPPLY_ONLY.name());
        return state.copy();
    }

    private static CompoundTag graphContent(FlowGraph graph, Set<FlowGraph> visited) {
        CompoundTag state = new CompoundTag();
        if (!visited.add(graph)) return state;
        ListTag nodes = new ListTag();
        for (RecipeNode node : graph.getNodes()) {
            // Suppress the serializer's embedded graph expansion; traverse each graph once here.
            CompoundTag tag = node.serializeNBT(Collections.emptySet(), 16);
            tag.remove("roleData");
            for (String cosmetic : List.of("posX", "posY", "cardWidth", "cardHeight", "isFlipped",
                    "hiddenInputs", "hiddenOutputs")) tag.remove(cosmetic);
            tag.putDouble("allocatedInput", node.getAllocatedInputRate());
            tag.putDouble("allocatedExport", node.getAllocatedExportRate());
            ListTag exports = new ListTag();
            for (var entry : FlowEdgeAllocator.virtualExportDemands(graph, node).entrySet()) {
                CompoundTag export = entry.getKey().serializeNBT();
                export.putDouble("demand", entry.getValue());
                exports.add(export);
            }
            tag.put("virtualExports", exports);
            ListTag rates = new ListTag();
            for (int i = 0; i < node.getInputs().size(); i++) {
                rates.add(net.minecraft.nbt.DoubleTag.valueOf(node.getInputSlotRate(i, false)));
            }
            for (int i = 0; i < node.getOutputs().size(); i++) {
                rates.add(net.minecraft.nbt.DoubleTag.valueOf(node.getOutputSlotRate(i, false)));
            }
            tag.put("nominalRates", rates);
            if (node.getSubGraph() != null) tag.put("subGraph", graphContent(node.getSubGraph(), visited));
            nodes.add(tag);
        }
        state.put("nodes", nodes);
        ListTag edges = new ListTag();
        for (FlowGraph.ConnectionEdge edge : graph.getConnections()) edges.add(edge.serializeNBT());
        state.put("edges", edges);
        ListTag targets = new ListTag();
        graph.getRecommendationTargetIds().forEach(id -> targets.add(net.minecraft.nbt.StringTag.valueOf(id)));
        state.put("recommendationTargets", targets);
        return state;
    }

}
