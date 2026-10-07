package com.gtceu.calcboard.api.solver;

import com.gtceu.calcboard.api.model.FlowGraph;
import com.gtceu.calcboard.api.model.IngredientStack;
import com.gtceu.calcboard.api.model.RecipeNode;
import com.gtceu.calcboard.api.property.NodeProperties;
import com.gtceu.calcboard.api.type.LineSolveMode;

import java.util.*;

/**
 * Computes node operating efficiencies using fixed-point iterative convergence
 * and precomputed self-sustaining cycle resource balances.
 */
public final class FixedPointEfficiencySolver {

    private FixedPointEfficiencySolver() {}

    /**
     * Iteration cap for the two-sided solve. The forward-only solve converges in a handful of
     * sweeps, but a throttle introduced at the tail of a long chain needs one sweep per link to
     * travel upstream, so the budget is an order of magnitude larger.
     */
    private static final int MAX_BLOCKING_ITERATIONS = 128;

    /** Efficiency change below which the solve is considered converged. */
    private static final double EFFICIENCY_EPSILON = 1e-4;
    private static final double CAPACITY_EPSILON = 1e-9;

    public record SelfSustainingResource(
            IngredientStack.Type type,
            net.minecraft.resources.ResourceLocation id
    ) {
        public boolean matches(IngredientStack stack) {
            if (stack == null) return false;
            return stack.getType() == type && Objects.equals(stack.getId(), id);
        }
    }

    public record SelfSustainingLoop(
            Set<String> nodeIds,
            SelfSustainingResource resource,
            double selfSufficiencyRatio,
            double externalFeedEfficiency
    ) {
        public boolean matches(RecipeNode node, IngredientStack stack) {
            return node != null && nodeIds.contains(node.getId()) && resource.matches(stack);
        }
    }

    public record ExternalFeedPort(
            String consumerNodeId,
            int inIdx,
            double nominalRate,
            List<FlowGraph.ConnectionEdge> inEdges
    ) {}

    public record PrecomputedLoopMeta(
            Set<String> scc,
            SelfSustainingResource resource,
            double selfSufficiencyRatio,
            List<ExternalFeedPort> externalFeedPorts
    ) {}

    /**
     * Analytical precomputed metadata for damped recirculation loops (ADR-044).
     * Solves infinite geometric series for steady-state supply: S_steady = S_ext / (1 - r).
     */
    public record PrecomputedDampedLoopMeta(
            Set<String> scc,
            SelfSustainingResource resource,
            double recirculationRatio,
            double nominalDemand,
            double nominalProduction,
            List<FlowGraph.ConnectionEdge> externalEdges
    ) {
        public double computeExternalSupply(FlowGraph graph, Map<String, Double> effMap, FlowEdgeAllocator.SolverContext context) {
            return computeIncomingSupply(graph, externalEdges, effMap, context);
        }

        public double computeSteadyStateSupply(FlowGraph graph, Map<String, Double> effMap, FlowEdgeAllocator.SolverContext context) {
            double sExt = computeExternalSupply(graph, effMap, context);
            if (recirculationRatio >= 1.0 - 1e-4) {
                return sExt;
            }
            return sExt / (1.0 - recirculationRatio);
        }

        public double computeSteadyStateEfficiency(FlowGraph graph, Map<String, Double> effMap, FlowEdgeAllocator.SolverContext context) {
            if (nominalDemand <= 1e-5) return 1.0;
            double sSteady = computeSteadyStateSupply(graph, effMap, context);
            return Math.min(1.0, sSteady / nominalDemand);
        }

        public boolean matches(RecipeNode node, IngredientStack stack) {
            return node != null && scc.contains(node.getId()) && resource.matches(stack);
        }
    }

    public static Map<String, Double> computeNodeEfficiencies(FlowGraph graph) {
        return computeNodeEfficiencies(graph, LineSolveMode.SUPPLY_ONLY);
    }

    /**
     * Computes node operating efficiencies under the requested constraint model.
     *
     * <p>{@link LineSolveMode#SUPPLY_ONLY} runs the historical forward-only fixed point and is
     * behaviourally identical to {@link #computeNodeEfficiencies(FlowGraph)}.
     *
     * <p>{@link LineSolveMode#SUPPLY_AND_DEMAND} runs the two-sided fixed point described in
     * {@link #computeNodeEfficienciesWithDemand(FlowGraph)}, which additionally throttles producers
     * whose outputs cannot be absorbed by downstream demand.
     */
    public static Map<String, Double> computeNodeEfficiencies(FlowGraph graph, LineSolveMode mode) {
        if (graph != null) graph.setSolvedMode(mode);
        if (mode != null && mode.isBlockingAware()) {
            return computeNodeEfficienciesWithDemand(graph);
        }
        return computeNodeEfficienciesSupplyOnly(graph);
    }

    /**
     * Historical forward-only solve: a node is throttled purely by how much supply reaches its
     * input ports, and a producer is never slowed down by its own outputs backing up.
     */
    private static Map<String, Double> computeNodeEfficienciesSupplyOnly(FlowGraph graph) {
        Map<String, Double> effMap = new HashMap<>();
        if (graph == null) return effMap;
        graph.cleanupInvalidConnections();

        for (RecipeNode node : graph.getNodes()) {
            effMap.put(node.getId(), 1.0);
        }

        FlowEdgeAllocator.SolverContext context = FlowEdgeAllocator.SolverContext.create(graph);
        List<PrecomputedLoopMeta> loopMetas = precomputeLoopMetas(graph, context);
        List<PrecomputedDampedLoopMeta> dampedLoopMetas = precomputeDampedLoopMetas(graph, context);

        for (int iter = 0; iter < 10; iter++) {
            boolean changed = false;
            List<SelfSustainingLoop> loops = evaluateLoops(graph, loopMetas, effMap, context);

            for (RecipeNode consumer : graph.getNodes()) {
                double calculatedEff = computeConsumerEfficiency(graph, consumer, loops, dampedLoopMetas, effMap, context);
                double oldEff = effMap.get(consumer.getId());
                if (Math.abs(oldEff - calculatedEff) > 0.0001) {
                    effMap.put(consumer.getId(), calculatedEff);
                    changed = true;
                }
            }

            if (propagateCompoundBottlenecks(graph, effMap)) {
                changed = true;
            }

            if (!changed) break;
        }

        for (RecipeNode node : graph.getNodes()) {
            Double finalEff = effMap.get(node.getId());
            if (finalEff != null) {
                node.setEfficiency(finalEff);
            }
        }

        // Clear any blocking detail left over from a previous SUPPLY_AND_DEMAND solve, so switching
        // modes back cannot leave stale "blocked" annotations on the board.
        for (RecipeNode node : graph.getNodes()) {
            node.setBlockingInfo(1.0, null);
        }

        LineBottleneckAnalyzer.markBottlenecks(graph, LineSolveMode.SUPPLY_ONLY);

        graph.invalidatePortStatsCache();

        return effMap;
    }

    /**
     * Two-sided fixed point: starvation <em>and</em> blocking.
     *
     * <p>Each sweep runs the historical forward pass (a node is throttled by how much supply reaches
     * its inputs) and then a backward pass (a node is throttled by how much its downstream consumers
     * can absorb). Every update can only <em>lower</em> an efficiency, so the iteration descends
     * monotonically from "the whole line runs at full speed". The iteration budget and descending
     * updates prevent oscillation; the existing loop relaxation remains responsible for recirculation.
     *
     * <p>Hardware-derived acceptance and allocation weights are fixed within each descending pass.
     * Shared input reservations are then refined from delivered capacities until stable.
     * Supply still propagates through the forward iterations, independently of current appetite.
     */
    private static Map<String, Double> computeNodeEfficienciesWithDemand(FlowGraph graph) {
        Map<String, Double> effMap = new HashMap<>();
        if (graph == null) return effMap;
        graph.cleanupInvalidConnections();

        for (RecipeNode node : graph.getNodes()) {
            effMap.put(node.getId(), 1.0);
            node.setBlockingInfo(1.0, null);
            node.setEfficiency(1.0);
        }

        graph.invalidatePortStatsCache();
        DownstreamBlockingSolver.Analysis analysis = new DownstreamBlockingSolver.Analysis(graph);
        Map<FlowGraph.ConnectionEdge, Double> weights = Map.of();
        Map<String, Double> capacityEfficiencies = Map.of();
        boolean sharedInputs = hasSharedInputs(graph);
        FlowEdgeAllocator.SolverContext structuralContext = FlowEdgeAllocator.SolverContext.create(graph);
        List<PrecomputedLoopMeta> loopMetas = precomputeLoopMetas(graph, structuralContext);
        List<PrecomputedDampedLoopMeta> dampedLoopMetas = precomputeDampedLoopMetas(graph, structuralContext);
        for (int capacityIter = 0; capacityIter < MAX_BLOCKING_ITERATIONS; capacityIter++) {
            for (RecipeNode node : graph.getNodes()) {
                effMap.put(node.getId(), 1.0);
                node.setEfficiency(1.0);
            }
            analysis = analysis.withCapacityEfficiencies(capacityEfficiencies);
            weights = analysis.allocationWeights();
            FlowEdgeAllocator.SolverContext context = new FlowEdgeAllocator.SolverContext(
                    structuralContext.edgeIndex(), structuralContext.portRates(), weights);
            solveDemandEfficiencies(graph, effMap, analysis, context, loopMetas, dampedLoopMetas);
            if (!sharedInputs || capacitiesConverged(capacityEfficiencies, effMap)
                    || !hasUnusedSharedReservations(graph, effMap, context)) break;
            // A nominal reservation can strand capacity when co-producers depend on one another.
            // Reclaim it using their delivered shares, restarting the descending solve so a
            // producer can recover capacity instead of remaining pinned to its first reservation.
            if (capacityIter + 1 < MAX_BLOCKING_ITERATIONS) {
                capacityEfficiencies = Map.copyOf(effMap);
            }
        }

        for (RecipeNode node : graph.getNodes()) {
            node.setEfficiency(effMap.getOrDefault(node.getId(), 1.0));
            if (!node.isReroute()) {
                DownstreamBlockingSolver.NodeAcceptance acceptance = analysis.analyzeNode(node);
                node.setBlockingInfo(acceptance.ratio(), acceptance.bindingResourceNameOrNull());
            }
        }

        LineBottleneckAnalyzer.markBottlenecks(graph, LineSolveMode.SUPPLY_AND_DEMAND);
        graph.invalidatePortStatsCache();
        graph.setProductionCapacityEfficiencies(capacityEfficiencies);
        graph.setProductionAllocationWeights(weights);
        return effMap;
    }

    private static boolean hasSharedInputs(FlowGraph graph) {
        Set<FlowGraph.PortKey> inputs = new HashSet<>();
        for (FlowGraph.ConnectionEdge edge : graph.getConnections()) {
            if (!inputs.add(new FlowGraph.PortKey(edge.toNodeId(), true, edge.inputIndex()))) return true;
        }
        return false;
    }

    private static boolean capacitiesConverged(Map<String, Double> previous, Map<String, Double> current) {
        if (previous.isEmpty()) return false;
        for (Map.Entry<String, Double> entry : current.entrySet()) {
            if (Math.abs(entry.getValue() - previous.getOrDefault(entry.getKey(), 1.0)) > CAPACITY_EPSILON) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasUnusedSharedReservations(
            FlowGraph graph,
            Map<String, Double> efficiencies,
            FlowEdgeAllocator.SolverContext context
    ) {
        for (List<FlowGraph.ConnectionEdge> incoming : context.edgeIndex().inPortEdges().values()) {
            if (incoming.size() < 2) continue;
            double reserved = 0.0;
            for (FlowGraph.ConnectionEdge edge : incoming) {
                reserved += context.allocationWeights().getOrDefault(edge, 0.0);
            }
            double supplied = computeIncomingSupply(graph, incoming, efficiencies, context);
            if (supplied < reserved - Math.max(1e-6, reserved * CAPACITY_EPSILON)) return true;
        }
        return false;
    }

    private static void solveDemandEfficiencies(
            FlowGraph graph,
            Map<String, Double> effMap,
            DownstreamBlockingSolver.Analysis analysis,
            FlowEdgeAllocator.SolverContext context,
            List<PrecomputedLoopMeta> loopMetas,
            List<PrecomputedDampedLoopMeta> dampedLoopMetas
    ) {
        for (int iter = 0; iter < MAX_BLOCKING_ITERATIONS; iter++) {
            boolean changed = false;
            List<SelfSustainingLoop> loops = evaluateLoops(graph, loopMetas, effMap, context);

            for (RecipeNode consumer : graph.getNodes()) {
                double supplyEff = computeConsumerEfficiency(graph, consumer, loops, dampedLoopMetas, effMap, context);
                double previous = effMap.getOrDefault(consumer.getId(), 1.0);
                double next = Math.min(previous, supplyEff);
                if (next < previous - EFFICIENCY_EPSILON) {
                    effMap.put(consumer.getId(), next);
                    changed = true;
                }
            }

            if (propagateCompoundBottlenecks(graph, effMap)) {
                changed = true;
            }

            // Publish for graph APIs that read efficiencies directly rather than through effMap.
            for (RecipeNode node : graph.getNodes()) {
                node.setEfficiency(effMap.getOrDefault(node.getId(), 1.0));
            }

            for (RecipeNode producer : graph.getNodes()) {
                if (producer.isReroute()) {
                    continue;
                }
                DownstreamBlockingSolver.NodeAcceptance acceptance =
                        analysis.analyzeNode(producer);
                producer.setBlockingInfo(acceptance.ratio(), acceptance.bindingResourceNameOrNull());
                double previous = effMap.getOrDefault(producer.getId(), 1.0);
                double next = Math.min(previous, acceptance.ratio());
                if (next < previous - EFFICIENCY_EPSILON) {
                    effMap.put(producer.getId(), next);
                    producer.setEfficiency(next);
                    changed = true;
                }
            }

            if (!changed) break;
        }
    }

    private static boolean propagateCompoundBottlenecks(FlowGraph graph, Map<String, Double> effMap) {
        boolean changed = false;
        for (RecipeNode node : graph.getNodes()) {
            if (!node.isCompoundNode() || node.getCompoundLayerIndex() <= 0) {
                continue;
            }
            String groupId = node.getCompoundGroupId();
            int myLayer = node.getCompoundLayerIndex();
            RecipeNode prevLayer = null;
            for (RecipeNode other : graph.getNodes()) {
                if (other.isCompoundNode() && groupId.equals(other.getCompoundGroupId()) && other.getCompoundLayerIndex() == myLayer - 1) {
                    prevLayer = other;
                    break;
                }
            }
            if (prevLayer != null) {
                double prevEff = effMap.getOrDefault(prevLayer.getId(), 1.0);
                double currentEff = effMap.getOrDefault(node.getId(), 1.0);
                if (prevEff < currentEff - 0.0001) {
                    effMap.put(node.getId(), prevEff);
                    node.setEfficiency(prevEff);
                    changed = true;
                }
            }
        }
        return changed;
    }

    private static List<SelfSustainingLoop> evaluateLoops(
            FlowGraph graph,
            List<PrecomputedLoopMeta> loopMetas,
            Map<String, Double> effMap,
            FlowEdgeAllocator.SolverContext context
    ) {
        List<SelfSustainingLoop> result = new ArrayList<>(loopMetas.size());
        for (PrecomputedLoopMeta meta : loopMetas) {
            double feedEff = computeLoopFeedEfficiency(graph, meta, effMap, context);
            result.add(new SelfSustainingLoop(meta.scc(), meta.resource(), meta.selfSufficiencyRatio(), feedEff));
        }
        return result;
    }

    private static double computeLoopFeedEfficiency(
            FlowGraph graph,
            PrecomputedLoopMeta meta,
            Map<String, Double> effMap,
            FlowEdgeAllocator.SolverContext context
    ) {
        if (meta.externalFeedPorts().isEmpty()) {
            return 1.0;
        }
        double minFeedEff = 1.0;
        for (ExternalFeedPort p : meta.externalFeedPorts()) {
            double supply = computeIncomingSupply(graph, p.inEdges(), effMap, context);
            double feedRatio = Math.max(0.0, Math.min(1.0, supply / p.nominalRate()));
            minFeedEff = Math.min(minFeedEff, feedRatio);
        }
        return minFeedEff;
    }

    private static double computeConsumerEfficiency(
            FlowGraph graph,
            RecipeNode consumer,
            List<SelfSustainingLoop> loops,
            List<PrecomputedDampedLoopMeta> dampedLoopMetas,
            Map<String, Double> effMap,
            FlowEdgeAllocator.SolverContext context
    ) {
        double minRatio = 1.0;
        boolean hasConnectedInput = false;

        for (int inIdx = 0; inIdx < consumer.getInputs().size(); inIdx++) {
            double portRatio = computePortRatio(graph, consumer, inIdx, loops, dampedLoopMetas, effMap, context);
            if (portRatio < 0.0) {
                continue;
            }
            hasConnectedInput = true;
            minRatio = Math.min(minRatio, portRatio);
        }

        return hasConnectedInput ? Math.max(0.0, Math.min(1.0, minRatio)) : 1.0;
    }

    private static double computePortRatio(
            FlowGraph graph,
            RecipeNode consumer,
            int inIdx,
            List<SelfSustainingLoop> loops,
            List<PrecomputedDampedLoopMeta> dampedLoopMetas,
            Map<String, Double> effMap,
            FlowEdgeAllocator.SolverContext context
    ) {
        FlowEdgeAllocator.CachedEdgeIndex edgeIndex = context != null ? context.edgeIndex() : null;
        List<FlowGraph.ConnectionEdge> inEdges = findIncomingEdges(graph, consumer.getId(), inIdx, edgeIndex);
        if (inEdges.isEmpty()) {
            return -1.0;
        }
        IngredientStack inStack = consumer.getInputs().get(inIdx);
        double nominalInRate = context != null ? context.getInputRate(consumer, inIdx) : consumer.getInputSlotRate(inIdx, false);
        if (nominalInRate <= 0.00001) {
            return -1.0;
        }

        PrecomputedDampedLoopMeta matchingDampedMeta = findMatchingDampedLoop(consumer, inStack, dampedLoopMetas);
        double portRatio;
        if (matchingDampedMeta != null) {
            double totalIncomingSupply = computeIncomingSupply(graph, inEdges, effMap, context);
            double actualRatio = totalIncomingSupply / nominalInRate;
            double sExt = matchingDampedMeta.computeExternalSupply(graph, effMap, context);
            double sSteady = matchingDampedMeta.recirculationRatio() < 1.0 - 1e-4
                    ? sExt / (1.0 - matchingDampedMeta.recirculationRatio())
                    : sExt;
            double steadyRatio = matchingDampedMeta.nominalDemand() > 1e-5
                    ? (sSteady / matchingDampedMeta.nominalDemand())
                    : 1.0;
            portRatio = Math.min(steadyRatio, actualRatio);
        } else {
            double totalIncomingSupply = computeIncomingSupply(graph, inEdges, effMap, context);
            portRatio = totalIncomingSupply / nominalInRate;
            portRatio = applyLoopRelaxation(consumer, inStack, portRatio, loops);
        }

        if (inStack.isStressUnit() && portRatio < 0.9999) {
            return 0.0;
        }
        return portRatio;
    }

    private static PrecomputedDampedLoopMeta findMatchingDampedLoop(
            RecipeNode consumer,
            IngredientStack inStack,
            List<PrecomputedDampedLoopMeta> dampedLoopMetas
    ) {
        if (dampedLoopMetas == null || dampedLoopMetas.isEmpty()) return null;
        for (PrecomputedDampedLoopMeta meta : dampedLoopMetas) {
            if (meta.matches(consumer, inStack)) {
                return meta;
            }
        }
        return null;
    }

    private static double applyLoopRelaxation(
            RecipeNode consumer,
            IngredientStack inStack,
            double baseRatio,
            List<SelfSustainingLoop> loops
    ) {
        double ratio = baseRatio;
        for (SelfSustainingLoop loop : loops) {
            if (loop.matches(consumer, inStack)) {
                double loopBound = Math.min(1.0, loop.selfSufficiencyRatio()) * loop.externalFeedEfficiency();
                ratio = Math.max(ratio, loopBound);
            }
        }
        return ratio;
    }

    public static List<FlowGraph.ConnectionEdge> findIncomingEdges(FlowGraph graph, String nodeId, int inputIndex, FlowEdgeAllocator.CachedEdgeIndex edgeIndex) {
        if (edgeIndex != null) {
            return edgeIndex.getInPortEdges(nodeId, inputIndex);
        }
        List<FlowGraph.ConnectionEdge> inEdges = new ArrayList<>();
        for (FlowGraph.ConnectionEdge edge : graph.getConnections()) {
            if (edge.toNodeId().equals(nodeId) && edge.inputIndex() == inputIndex) {
                inEdges.add(edge);
            }
        }
        return inEdges;
    }

    public static double computeIncomingSupply(
            FlowGraph graph,
            List<FlowGraph.ConnectionEdge> inEdges,
            Map<String, Double> effMap,
            FlowEdgeAllocator.SolverContext context
    ) {
        double totalIncomingSupply = 0.0;
        for (FlowGraph.ConnectionEdge edge : inEdges) {
            RecipeNode producer = graph.findNodeById(edge.fromNodeId());
            if (producer == null) continue;
            if (!producer.isReroute() && edge.outputIndex() >= producer.getOutputs().size()) continue;
            totalIncomingSupply += FlowEdgeAllocator.getEdgeAllocatedFlow(graph, edge, effMap, new HashSet<>(), context);
        }
        return totalIncomingSupply;
    }

    public static List<PrecomputedLoopMeta> precomputeLoopMetas(FlowGraph graph, FlowEdgeAllocator.SolverContext context) {
        List<PrecomputedLoopMeta> result = new ArrayList<>();
        if (graph == null || graph.getNodes().isEmpty() || graph.getConnections().isEmpty()) {
            return result;
        }

        FlowEdgeAllocator.CachedEdgeIndex edgeIndex = context != null ? context.edgeIndex() : FlowEdgeAllocator.buildEdgeIndex(graph);
        List<Set<String>> sccs = ProcessStabilityAnalyzer.findStronglyConnectedComponents(graph, edgeIndex);
        for (Set<String> scc : sccs) {
            if (scc.size() < 2 && !ProcessStabilityAnalyzer.hasSelfLoop(graph, scc, edgeIndex)) {
                continue;
            }
            Map<SelfSustainingResource, Double> prodTotals = new HashMap<>();
            Map<SelfSustainingResource, Double> demTotals = new HashMap<>();

            accumulateLoopResourceTotals(graph, scc, prodTotals, demTotals, context);
            if (isStrictlyDampedScc(prodTotals, demTotals)) {
                continue;
            }

            if (!areAllResourcesSelfSustaining(prodTotals, demTotals)) {
                continue;
            }

            for (Map.Entry<SelfSustainingResource, Double> entry : demTotals.entrySet()) {
                SelfSustainingResource res = entry.getKey();
                double dem = entry.getValue();
                double prod = prodTotals.getOrDefault(res, 0.0);
                if (dem > 0.0001 && prod >= dem - 0.001) {
                    double ratio = prod / dem;
                    List<ExternalFeedPort> extPorts = findExternalFeedPorts(graph, scc, res, context);
                    result.add(new PrecomputedLoopMeta(scc, res, ratio, extPorts));
                }
            }
        }
        return result;
    }

    private static boolean areAllResourcesSelfSustaining(
            Map<SelfSustainingResource, Double> prodTotals,
            Map<SelfSustainingResource, Double> demTotals
    ) {
        for (Map.Entry<SelfSustainingResource, Double> entry : demTotals.entrySet()) {
            double dem = entry.getValue();
            double prod = prodTotals.getOrDefault(entry.getKey(), 0.0);
            if (dem > 0.0001 && prod < dem - 0.001) {
                return false;
            }
        }
        return true;
    }

    private static double computeSccLoopGain(
            Map<SelfSustainingResource, Double> prodTotals,
            Map<SelfSustainingResource, Double> demTotals
    ) {
        if (demTotals.isEmpty()) return 1.0;
        double gain = 1.0;
        for (Map.Entry<SelfSustainingResource, Double> entry : demTotals.entrySet()) {
            double dem = entry.getValue();
            if (dem <= 0.0001) continue;
            double prod = prodTotals.getOrDefault(entry.getKey(), 0.0);
            gain *= (prod / dem);
        }
        return gain;
    }

    private static boolean isStrictlyDampedScc(
            Map<SelfSustainingResource, Double> prodTotals,
            Map<SelfSustainingResource, Double> demTotals
    ) {
        if (demTotals.isEmpty()) return false;
        double loopGain = computeSccLoopGain(prodTotals, demTotals);
        return (loopGain < 1.0 - 1e-4) && (loopGain > 1e-5);
    }

    public static List<PrecomputedDampedLoopMeta> precomputeDampedLoopMetas(FlowGraph graph, FlowEdgeAllocator.SolverContext context) {
        List<PrecomputedDampedLoopMeta> result = new ArrayList<>();
        if (graph == null || graph.getNodes().isEmpty() || graph.getConnections().isEmpty()) {
            return result;
        }

        FlowEdgeAllocator.CachedEdgeIndex edgeIndex = context != null ? context.edgeIndex() : FlowEdgeAllocator.buildEdgeIndex(graph);
        List<Set<String>> sccs = ProcessStabilityAnalyzer.findStronglyConnectedComponents(graph, edgeIndex);
        for (Set<String> scc : sccs) {
            if (scc.size() < 2 && !ProcessStabilityAnalyzer.hasSelfLoop(graph, scc, edgeIndex)) {
                continue;
            }
            collectDampedMetasForScc(graph, scc, edgeIndex, context, result);
        }
        return result;
    }

    private static Map<SelfSustainingResource, List<FlowGraph.ConnectionEdge>> findExternalEdgesMap(
            FlowGraph graph,
            Set<String> scc,
            Set<SelfSustainingResource> resources,
            FlowEdgeAllocator.CachedEdgeIndex edgeIndex
    ) {
        Map<SelfSustainingResource, List<FlowGraph.ConnectionEdge>> map = new HashMap<>();
        for (SelfSustainingResource res : resources) {
            map.put(res, findExternalEdgesForResource(graph, scc, res, edgeIndex));
        }
        return map;
    }

    private static void collectDampedMetasForScc(
            FlowGraph graph,
            Set<String> scc,
            FlowEdgeAllocator.CachedEdgeIndex edgeIndex,
            FlowEdgeAllocator.SolverContext context,
            List<PrecomputedDampedLoopMeta> result
    ) {
        Map<SelfSustainingResource, Double> prodTotals = new HashMap<>();
        Map<SelfSustainingResource, Double> demTotals = new HashMap<>();
        accumulateLoopResourceTotals(graph, scc, prodTotals, demTotals, context);

        if (!isStrictlyDampedScc(prodTotals, demTotals)) {
            return;
        }

        double loopGain = computeSccLoopGain(prodTotals, demTotals);
        Map<SelfSustainingResource, List<FlowGraph.ConnectionEdge>> extEdgesMap = findExternalEdgesMap(graph, scc, demTotals.keySet(), edgeIndex);
        long feedCount = extEdgesMap.values().stream().filter(edges -> !edges.isEmpty()).count();
        if (feedCount > 1) {
            return;
        }

        boolean hasAnyExternalFeed = feedCount > 0;

        for (Map.Entry<SelfSustainingResource, Double> entry : demTotals.entrySet()) {
            SelfSustainingResource res = entry.getKey();
            double dem = entry.getValue();
            if (dem <= 0.0001) continue;

            List<FlowGraph.ConnectionEdge> extEdges = extEdgesMap.getOrDefault(res, List.of());
            if (hasAnyExternalFeed && extEdges.isEmpty()) {
                continue;
            }

            double prod = prodTotals.getOrDefault(res, 0.0);
            result.add(new PrecomputedDampedLoopMeta(scc, res, loopGain, dem, prod, extEdges));
        }
    }

    public static List<FlowGraph.ConnectionEdge> findExternalEdgesForResource(
            FlowGraph graph,
            Set<String> scc,
            SelfSustainingResource res,
            FlowEdgeAllocator.CachedEdgeIndex edgeIndex
    ) {
        List<FlowGraph.ConnectionEdge> extEdges = new ArrayList<>();
        for (String nodeId : scc) {
            RecipeNode node = graph.findNodeById(nodeId);
            if (node == null) continue;
            if (node.isReroute()) {
                collectExternalEdgesForJunction(graph, node, scc, res, edgeIndex, extEdges);
                continue;
            }
            collectExternalEdgesForNode(graph, node, scc, res, edgeIndex, extEdges);
        }
        return extEdges;
    }

    private static void collectExternalEdgesForJunction(
            FlowGraph graph,
            RecipeNode junction,
            Set<String> scc,
            SelfSustainingResource res,
            FlowEdgeAllocator.CachedEdgeIndex edgeIndex,
            List<FlowGraph.ConnectionEdge> extEdges
    ) {
        IngredientStack rStack = junction.getRerouteIngredient();
        if (rStack == null && !junction.getInputs().isEmpty()) {
            rStack = junction.getInputs().get(0);
        }
        if (rStack == null || !res.matches(rStack)) {
            return;
        }
        List<FlowGraph.ConnectionEdge> inEdges = findIncomingEdges(graph, junction.getId(), 0, edgeIndex);
        for (FlowGraph.ConnectionEdge edge : inEdges) {
            if (!scc.contains(edge.fromNodeId())) {
                extEdges.add(edge);
            }
        }
    }

    private static void collectExternalEdgesForNode(
            FlowGraph graph,
            RecipeNode node,
            Set<String> scc,
            SelfSustainingResource res,
            FlowEdgeAllocator.CachedEdgeIndex edgeIndex,
            List<FlowGraph.ConnectionEdge> extEdges
    ) {
        for (int inIdx = 0; inIdx < node.getInputs().size(); inIdx++) {
            IngredientStack inStack = node.getInputs().get(inIdx);
            if (!res.matches(inStack)) continue;
            List<FlowGraph.ConnectionEdge> inEdges = findIncomingEdges(graph, node.getId(), inIdx, edgeIndex);
            for (FlowGraph.ConnectionEdge edge : inEdges) {
                if (!scc.contains(edge.fromNodeId())) {
                    extEdges.add(edge);
                }
            }
        }
    }

    public static PrecomputedDampedLoopMeta findDampedLoopMeta(FlowGraph graph, RecipeNode node, int inIdx) {
        if (graph == null || node == null || node.isReroute() || inIdx < 0 || inIdx >= node.getInputs().size()) {
            return null;
        }
        IngredientStack stack = node.getInputs().get(inIdx);
        List<PrecomputedDampedLoopMeta> metas = precomputeDampedLoopMetas(graph, null);
        for (PrecomputedDampedLoopMeta meta : metas) {
            if (meta.matches(node, stack)) {
                return meta;
            }
        }
        return null;
    }

    public static PrecomputedDampedLoopMeta findDampedLoopMetaForNode(FlowGraph graph, RecipeNode node) {
        if (graph == null || node == null || node.isReroute()) {
            return null;
        }
        List<PrecomputedDampedLoopMeta> metas = precomputeDampedLoopMetas(graph, null);
        for (PrecomputedDampedLoopMeta meta : metas) {
            if (meta.scc().contains(node.getId())) {
                return meta;
            }
        }
        return null;
    }

    private static List<ExternalFeedPort> findExternalFeedPorts(
            FlowGraph graph,
            Set<String> scc,
            SelfSustainingResource recirculatedRes,
            FlowEdgeAllocator.SolverContext context
    ) {
        List<ExternalFeedPort> extPorts = new ArrayList<>();
        FlowEdgeAllocator.CachedEdgeIndex edgeIndex = context != null ? context.edgeIndex() : null;
        for (String nodeId : scc) {
            RecipeNode node = graph.findNodeById(nodeId);
            if (node == null || node.isReroute()) continue;

            for (int inIdx = 0; inIdx < node.getInputs().size(); inIdx++) {
                IngredientStack inStack = node.getInputs().get(inIdx);
                if (recirculatedRes.matches(inStack)) {
                    continue;
                }
                double nomRate = context != null ? context.getInputRate(node, inIdx) : node.getInputSlotRate(inIdx, false);
                if (nomRate <= 0.0001) continue;

                List<FlowGraph.ConnectionEdge> inEdges = findIncomingEdges(graph, nodeId, inIdx, edgeIndex);
                List<FlowGraph.ConnectionEdge> externalEdges = filterExternalFeedEdges(inEdges, scc);
                if (!externalEdges.isEmpty()) {
                    extPorts.add(new ExternalFeedPort(nodeId, inIdx, nomRate, externalEdges));
                }
            }
        }
        return extPorts;
    }

    private static List<FlowGraph.ConnectionEdge> filterExternalFeedEdges(
            List<FlowGraph.ConnectionEdge> inEdges,
            Set<String> scc
    ) {
        List<FlowGraph.ConnectionEdge> externalEdges = new ArrayList<>();
        for (FlowGraph.ConnectionEdge edge : inEdges) {
            if (!scc.contains(edge.fromNodeId())) {
                externalEdges.add(edge);
            }
        }
        return externalEdges;
    }

    private static void accumulateLoopResourceTotals(
            FlowGraph graph,
            Set<String> scc,
            Map<SelfSustainingResource, Double> prodTotals,
            Map<SelfSustainingResource, Double> demTotals,
            FlowEdgeAllocator.SolverContext context
    ) {
        FlowEdgeAllocator.CachedEdgeIndex edgeIndex = context != null ? context.edgeIndex() : null;
        for (String nodeId : scc) {
            List<FlowGraph.ConnectionEdge> outEdges = edgeIndex != null
                    ? edgeIndex.getOutEdges(nodeId)
                    : graph.getConnections();
            for (FlowGraph.ConnectionEdge edge : outEdges) {
                if (!scc.contains(edge.toNodeId())) continue;
                RecipeNode producer = graph.findNodeById(edge.fromNodeId());
                if (producer != null && edge.outputIndex() < producer.getOutputs().size()) {
                    IngredientStack outStack = producer.getOutputs().get(edge.outputIndex());
                    SelfSustainingResource res = new SelfSustainingResource(outStack.getType(), outStack.getId());
                    prodTotals.putIfAbsent(res, 0.0);
                    demTotals.putIfAbsent(res, 0.0);
                }
            }
        }

        for (String nodeId : scc) {
            RecipeNode node = graph.findNodeById(nodeId);
            if (node == null || node.isReroute()) continue;
            accumulatePortTotals(node, prodTotals, demTotals, context);
        }
    }

    private static void accumulatePortTotals(
            RecipeNode node,
            Map<SelfSustainingResource, Double> prodTotals,
            Map<SelfSustainingResource, Double> demTotals,
            FlowEdgeAllocator.SolverContext context
    ) {
        for (int outIdx = 0; outIdx < node.getOutputs().size(); outIdx++) {
            IngredientStack out = node.getOutputs().get(outIdx);
            SelfSustainingResource res = new SelfSustainingResource(out.getType(), out.getId());
            if (prodTotals.containsKey(res)) {
                double rate = context != null ? context.getOutputRate(node, outIdx) : node.getOutputSlotRate(outIdx, false);
                prodTotals.put(res, prodTotals.get(res) + rate);
            }
        }
        for (int inIdx = 0; inIdx < node.getInputs().size(); inIdx++) {
            IngredientStack in = node.getInputs().get(inIdx);
            SelfSustainingResource res = new SelfSustainingResource(in.getType(), in.getId());
            if (demTotals.containsKey(res)) {
                double rate = context != null ? context.getInputRate(node, inIdx) : node.getInputSlotRate(inIdx, false);
                demTotals.put(res, demTotals.get(res) + rate);
            }
        }
    }
}
