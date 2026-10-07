package com.gtceu.calcboard.api.solver;

import com.gtceu.calcboard.api.model.FlowGraph;
import com.gtceu.calcboard.api.model.IngredientStack;
import com.gtceu.calcboard.api.model.RecipeNode;
import com.gtceu.calcboard.api.solver.linear.BoundedLinearOptimizer;
import com.gtceu.calcboard.api.type.FlowSplitMode;
import com.gtceu.calcboard.api.type.LineSolveMode;

import java.util.*;

/**
 * Sustained, expected-yield planning for a primed line with demand-pulled transfers and overflow
 * export. Connected inputs cannot import missing material; priming is not a continuing supply.
 */
public final class PrimedLineSolver {

    private PrimedLineSolver() {}

    public static Map<String, Double> solve(FlowGraph graph) {
        if (graph == null) return Map.of();
        graph.setSolvedMode(LineSolveMode.PRIMED);
        graph.cleanupInvalidConnections();
        for (RecipeNode node : graph.getNodes()) {
            node.setEfficiency(1.0);
            node.setBlockingInfo(1.0, null);
        }
        var context = FlowEdgeAllocator.SolverContext.create(graph);
        Model model = new Model();
        List<RecipeNode> nodes = graph.getNodes().stream()
                .sorted(Comparator.comparing(RecipeNode::getId)).toList();
        Map<FlowGraph.ConnectionEdge, Double> virtualDemands = new LinkedHashMap<>();
        nodes.forEach(node -> virtualDemands.putAll(FlowEdgeAllocator.virtualExportDemands(graph, node)));
        List<FlowGraph.ConnectionEdge> allEdges = new ArrayList<>(graph.getConnections());
        allEdges.addAll(virtualDemands.keySet());
        List<FlowGraph.ConnectionEdge> edges = allEdges.stream()
                .sorted(Comparator.comparing(FlowGraph.ConnectionEdge::fromNodeId)
                        .thenComparingInt(FlowGraph.ConnectionEdge::outputIndex)
                        .thenComparing(FlowGraph.ConnectionEdge::toNodeId)
                        .thenComparingInt(FlowGraph.ConnectionEdge::inputIndex)).toList();
        Map<IngredientStack, Double> scales = resourceScales(nodes, context);
        Map<String, Integer> efficiencies = new HashMap<>();
        Map<FlowGraph.ConnectionEdge, Integer> transfers = new HashMap<>();
        Map<FlowGraph.ConnectionEdge, Double> edgeScales = new HashMap<>();
        Map<String, Integer> drains = new HashMap<>();
        Map<String, Integer> exports = new HashMap<>();
        Map<Integer, Double> drainObjective = new HashMap<>();
        Map<Integer, Double> uptimeObjective = new HashMap<>();
        Map<Integer, Double> voidObjective = new HashMap<>();
        Map<Integer, Double> importObjective = new HashMap<>();
        Map<Integer, Double> exportObjective = new HashMap<>();
        Map<Integer, Double> transportObjective = new HashMap<>();
        double transferBound = nodes.stream().mapToDouble(node -> {
            if (node.isReroute()) {
                return node.getExternalSupplyRate() + node.getExternalDrainRate()
                        + node.getAllocatedInputRate() + node.getAllocatedExportRate();
            }
            double rate = 0.0;
            for (int port = 0; port < node.getInputs().size(); port++) rate += context.getInputRate(node, port);
            for (int port = 0; port < node.getOutputs().size(); port++) rate += context.getOutputRate(node, port);
            return rate;
        }).sum() + virtualDemands.values().stream()
                .filter(rate -> Double.isFinite(rate) && rate < Double.MAX_VALUE)
                .mapToDouble(Double::doubleValue).sum();

        for (RecipeNode node : nodes) {
            if (node.isReroute()) continue;
            int variable = model.variable();
            efficiencies.put(node.getId(), variable);
            model.limit(variable, 1.0);
            uptimeObjective.put(variable, 1.0);
        }
        for (FlowGraph.ConnectionEdge edge : edges) {
            int variable = model.variable();
            transfers.put(edge, variable);
            double scale = scale(scales, outputIngredient(graph.findNodeById(edge.fromNodeId()), edge.outputIndex()));
            edgeScales.put(edge, scale);
            double limit = edge.hasFixedLimit() ? Math.min(edge.fixedFlowLimit(), transferBound) : transferBound;
            RecipeNode consumer = graph.findNodeById(edge.toNodeId());
            if (virtualDemands.containsKey(edge)) {
                limit = Math.min(limit, virtualDemands.get(edge));
                exportObjective.put(variable, 1.0);
            }
            // A machine input equality already bounds each incoming transfer by its capacity.
            if (edge.hasFixedLimit() || consumer == null || consumer.isReroute()) {
                model.limit(variable, limit / scale);
            }
            transportObjective.put(variable, -1.0);
            if (consumer != null && consumer.isVoidSink()) voidObjective.put(variable, 1.0);
        }

        for (RecipeNode node : nodes) {
            if (!node.isReroute()) {
                int efficiency = efficiencies.get(node.getId());
                for (int port = 0; port < node.getInputs().size(); port++) {
                    List<FlowGraph.ConnectionEdge> incoming = context.edgeIndex().getInPortEdges(node.getId(), port);
                    if (incoming.isEmpty()) continue;
                    double scale = scale(scales, node.getInputs().get(port));
                    Map<Integer, Double> balance = terms(incoming, transfers, edgeScales, -1.0 / scale);
                    balance.put(efficiency, context.getInputRate(node, port) / scale);
                    model.equality(balance);
                }
                for (int port = 0; port < node.getOutputs().size(); port++) {
                    List<FlowGraph.ConnectionEdge> outgoing = context.edgeIndex().getOutPortEdges(node.getId(), port);
                    if (outgoing.isEmpty()) continue;
                    double scale = scale(scales, node.getOutputs().get(port));
                    Map<Integer, Double> balance = terms(outgoing, transfers, edgeScales, 1.0 / scale);
                    balance.put(efficiency, -context.getOutputRate(node, port) / scale);
                    model.constraint(balance, 0.0);
                }
                addCompoundLimit(node, nodes, efficiencies, model);
                continue;
            }
            if (node.isVoidSink()) continue;
            double scale = scale(scales, node.getRerouteIngredient());
            Map<Integer, Double> balance = terms(outgoing(context, node, 0, virtualDemands), transfers, edgeScales, 1.0 / scale);
            terms(context.getInEdges(node.getId()), transfers, edgeScales, -1.0 / scale)
                    .forEach((variable, coefficient) -> balance.merge(variable, coefficient, Double::sum));
            if (node.isFixedDrain() && node.getExternalDrainRate() > 0.0) {
                int variable = model.variable();
                drains.put(node.getId(), variable);
                model.limit(variable, node.getExternalDrainRate() / scale);
                balance.put(variable, 1.0);
                drainObjective.put(variable, 1.0);
            }
            if (node.getAllocatedExportRate() > 0.0
                    && virtualDemands.keySet().stream().noneMatch(edge -> edge.fromNodeId().equals(node.getId()))) {
                int variable = model.variable();
                exports.put(node.getId(), variable);
                model.limit(variable, node.getAllocatedExportRate() / scale);
                balance.put(variable, 1.0);
                drainObjective.put(variable, 1.0);
            }
            boolean freeSupply = node.isInfiniteSupply()
                    || (context.getInEdges(node.getId()).isEmpty()
                    && !node.isExternalSupply() && !node.isLinkedJunction() && !node.isFixedDrain());
            if (freeSupply) {
                int supplied = model.variable();
                balance.put(supplied, -1.0);
                importObjective.put(supplied, -1.0);
            }
            double supplied = !freeSupply && (node.isExternalSupply() || node.isLinkedJunction())
                    ? node.getExternalSupplyRate() : 0.0;
            model.constraint(balance, supplied / scale);
        }

        model.objective(drainObjective);
        addRoutingObjectives(graph, nodes, context, transfers, edgeScales, scales, virtualDemands, model);
        model.objective(uptimeObjective);
        model.objective(exportObjective);
        model.objective(importObjective);
        model.objective(voidObjective);
        addVoidRoutingObjectives(graph, nodes, context, transfers, edgeScales, scales, model);
        // Avoid artificial circulation in passive junction cycles and needless transport.
        model.objective(transportObjective);
        double[] solution = model.solve();
        Map<String, Double> result = new HashMap<>();
        Map<FlowGraph.ConnectionEdge, Double> flows = new HashMap<>();
        Map<String, Double> drainRates = new HashMap<>();
        Map<String, Double> exportRates = new HashMap<>();
        Map<FlowGraph.ConnectionEdge, Double> virtualFlows = new HashMap<>();
        for (RecipeNode node : nodes) {
            double efficiency = node.isReroute() ? 1.0 : Math.min(1.0, solution[efficiencies.get(node.getId())]);
            node.setEfficiency(efficiency);
            result.put(node.getId(), efficiency);
            double scale = scale(scales, node.getRerouteIngredient());
            if (drains.containsKey(node.getId())) drainRates.put(node.getId(), solution[drains.get(node.getId())] * scale);
            if (exports.containsKey(node.getId())) exportRates.put(node.getId(), solution[exports.get(node.getId())] * scale);
        }
        for (FlowGraph.ConnectionEdge edge : edges) {
            double rate = solution[transfers.get(edge)] * edgeScales.get(edge);
            if (virtualDemands.containsKey(edge)) {
                virtualFlows.put(edge, rate);
                exportRates.merge(edge.fromNodeId(), rate, Double::sum);
            } else {
                flows.put(edge, rate);
            }
        }
        graph.setPrimedFlows(flows, drainRates, exportRates, virtualFlows);
        graph.invalidatePortStatsCache();
        LineBottleneckAnalyzer.markBottlenecks(graph, LineSolveMode.PRIMED);
        return Map.copyOf(result);
    }

    private static void addRoutingObjectives(
            FlowGraph graph, List<RecipeNode> nodes, FlowEdgeAllocator.SolverContext context,
            Map<FlowGraph.ConnectionEdge, Integer> transfers, Map<FlowGraph.ConnectionEdge, Double> edgeScales,
            Map<IngredientStack, Double> scales, Map<FlowGraph.ConnectionEdge, Double> virtualDemands, Model model) {
        Map<Integer, Map<Integer, Map<Integer, Double>>> levels = new TreeMap<>(Comparator.reverseOrder());
        for (RecipeNode producer : nodes) {
            int ports = producer.isReroute() ? 1 : producer.getOutputs().size();
            for (int port = 0; port < ports; port++) {
                var outgoing = outgoing(context, producer, port, virtualDemands);
                if (outgoing.size() < 2) continue;
                Map<Integer, List<FlowGraph.ConnectionEdge>> groups = new TreeMap<>(Comparator.reverseOrder());
                for (var edge : outgoing) {
                    RecipeNode consumer = graph.findNodeById(edge.toNodeId());
                    if (consumer == null || !consumer.isVoidSink()) {
                        groups.computeIfAbsent(edge.priority(), key -> new ArrayList<>()).add(edge);
                    }
                }
                for (var group : groups.entrySet()) {
                    Map<Integer, Double> weights = new LinkedHashMap<>();
                    Map<Integer, Double> backups = new LinkedHashMap<>();
                    double resourceScale = scale(scales, outputIngredient(producer, port));
                    FlowSplitMode splitMode = producer.isReroute() ? producer.getJunctionSplitMode() : FlowSplitMode.PROPORTIONAL;
                    for (var edge : group.getValue()) {
                        RecipeNode consumer = graph.findNodeById(edge.toNodeId());
                        double weight = switch (splitMode) {
                            case EQUAL -> 1.0;
                            case WEIGHTED -> edge.weight();
                            case PROPORTIONAL -> (virtualDemands.containsKey(edge)
                                    ? virtualDemands.get(edge)
                                    : nominalAppetite(graph, consumer, edge.inputIndex(), context,
                                    virtualDemands, new HashSet<>())) / resourceScale;
                        };
                        if (weight > 0.0) weights.put(transfers.get(edge), weight * resourceScale / edgeScales.get(edge));
                        else if (splitMode == FlowSplitMode.WEIGHTED) {
                            backups.put(transfers.get(edge), resourceScale / edgeScales.get(edge));
                        }
                    }
                    Map<Integer, Map<Integer, Double>> level = levels.computeIfAbsent(group.getKey(), key -> new TreeMap<>());
                    addShareObjectives(weights, level, 0, model);
                    addShareObjectives(backups, level, transfers.size(), model);
                }
            }
        }
        levels.values().forEach(level -> level.values().forEach(model::objective));
    }

    private static void addShareObjectives(Map<Integer, Double> weights,
                                           Map<Integer, Map<Integer, Double>> level, int offset, Model model) {
        for (int k = 1; k <= weights.size(); k++) {
            Map<Integer, Double> objective = level.computeIfAbsent(offset + k, key -> new HashMap<>());
            // k*t - sum(max(0, t-flow/weight)) is the sum of the k smallest shares.
            // Successive objectives implement water filling, including saturated branches.
            int threshold = model.variable();
            objective.put(threshold, (double) k);
            for (var weight : weights.entrySet()) {
                int shortfall = model.variable();
                objective.put(shortfall, -1.0);
                model.constraint(Map.of(threshold, 1.0, shortfall, -1.0,
                        weight.getKey(), -1.0 / weight.getValue()), 0.0);
            }
        }
    }

    private static void addVoidRoutingObjectives(
            FlowGraph graph, List<RecipeNode> nodes, FlowEdgeAllocator.SolverContext context,
            Map<FlowGraph.ConnectionEdge, Integer> transfers, Map<FlowGraph.ConnectionEdge, Double> edgeScales,
            Map<IngredientStack, Double> scales, Model model) {
        Map<Integer, Map<Integer, Double>> level = new TreeMap<>();
        for (RecipeNode producer : nodes) {
            int ports = producer.isReroute() ? 1 : producer.getOutputs().size();
            for (int port = 0; port < ports; port++) {
                var sinks = context.edgeIndex().getOutPortEdges(producer.getId(), port).stream()
                        .filter(edge -> graph.findNodeById(edge.toNodeId()).isVoidSink()).toList();
                if (sinks.size() < 2) continue;
                Map<Integer, Double> weights = new LinkedHashMap<>();
                Map<Integer, Double> backups = new LinkedHashMap<>();
                double scale = scale(scales, outputIngredient(producer, port));
                for (var edge : sinks) {
                    double weight = producer.isReroute() && producer.getJunctionSplitMode() == FlowSplitMode.WEIGHTED
                            ? edge.weight() : 1.0;
                    if (weight > 0.0) weights.put(transfers.get(edge), weight * scale / edgeScales.get(edge));
                    else backups.put(transfers.get(edge), scale / edgeScales.get(edge));
                }
                addShareObjectives(weights, level, 0, model);
                addShareObjectives(backups, level, transfers.size(), model);
            }
        }
        level.values().forEach(model::objective);
    }

    private static List<FlowGraph.ConnectionEdge> outgoing(
            FlowEdgeAllocator.SolverContext context, RecipeNode node, int port,
            Map<FlowGraph.ConnectionEdge, Double> virtualDemands) {
        List<FlowGraph.ConnectionEdge> result = new ArrayList<>(context.edgeIndex().getOutPortEdges(node.getId(), port));
        virtualDemands.keySet().stream()
                .filter(edge -> edge.fromNodeId().equals(node.getId()) && edge.outputIndex() == port)
                .forEach(result::add);
        return result;
    }

    private static double nominalAppetite(
            FlowGraph graph, RecipeNode node, int inputIndex, FlowEdgeAllocator.SolverContext context,
            Map<FlowGraph.ConnectionEdge, Double> virtualDemands, Set<String> visited) {
        if (node == null || node.isVoidSink()) return 0.0;
        if (!node.isReroute()) return context.getInputRate(node, inputIndex);
        if (!visited.add(node.getId())) return 0.0;
        try {
            double demand = node.isFixedDrain() ? node.getExternalDrainRate() : 0.0;
            for (var edge : outgoing(context, node, 0, virtualDemands)) {
                demand += virtualDemands.containsKey(edge) ? virtualDemands.get(edge)
                        : nominalAppetite(graph, graph.findNodeById(edge.toNodeId()), edge.inputIndex(),
                        context, virtualDemands, visited);
            }
            double supplied = node.isExternalSupply() || node.isLinkedJunction() ? node.getExternalSupplyRate() : 0.0;
            return Math.max(0.0, demand - supplied);
        } finally {
            visited.remove(node.getId());
        }
    }

    static double junctionImport(RecipeNode node, double incoming, double withdrawn) {
        if (!node.isInfiniteSupply() && (node.isExternalSupply() || node.isLinkedJunction())) {
            return node.getExternalSupplyRate();
        }
        return Math.max(0.0, withdrawn - incoming);
    }

    private static void addCompoundLimit(
            RecipeNode node, List<RecipeNode> nodes, Map<String, Integer> efficiencies, Model model) {
        if (!node.isCompoundNode() || node.getCompoundLayerIndex() <= 0) return;
        for (RecipeNode previous : nodes) {
            if (previous.isCompoundNode() && Objects.equals(node.getCompoundGroupId(), previous.getCompoundGroupId())
                    && previous.getCompoundLayerIndex() == node.getCompoundLayerIndex() - 1) {
                model.constraint(Map.of(efficiencies.get(node.getId()), 1.0,
                        efficiencies.get(previous.getId()), -1.0), 0.0);
                break;
            }
        }
    }

    private static Map<IngredientStack, Double> resourceScales(
            List<RecipeNode> nodes, FlowEdgeAllocator.SolverContext context) {
        Map<IngredientStack, Double> result = new HashMap<>();
        for (RecipeNode node : nodes) {
            if (node.isReroute()) {
                if (node.getRerouteIngredient() != null) {
                    double rate = Math.max(Math.max(node.getExternalSupplyRate(), node.getExternalDrainRate()),
                            Math.max(node.getAllocatedInputRate(), node.getAllocatedExportRate()));
                    result.merge(node.getRerouteIngredient(), Math.max(1.0, rate), Math::max);
                }
                continue;
            }
            for (int port = 0; port < node.getInputs().size(); port++) {
                result.merge(node.getInputs().get(port), Math.max(1.0, context.getInputRate(node, port)), Math::max);
            }
            for (int port = 0; port < node.getOutputs().size(); port++) {
                result.merge(node.getOutputs().get(port), Math.max(1.0, context.getOutputRate(node, port)), Math::max);
            }
        }
        return result;
    }

    private static IngredientStack outputIngredient(RecipeNode node, int port) {
        return node.isReroute() ? node.getRerouteIngredient() : node.getOutputs().get(port);
    }

    private static double scale(Map<IngredientStack, Double> scales, IngredientStack stack) {
        return scales.getOrDefault(stack, 1.0);
    }

    private static Map<Integer, Double> terms(
            List<FlowGraph.ConnectionEdge> edges, Map<FlowGraph.ConnectionEdge, Integer> variables,
            Map<FlowGraph.ConnectionEdge, Double> scales, double factor) {
        Map<Integer, Double> result = new HashMap<>();
        for (var edge : edges) result.merge(variables.get(edge), factor * scales.get(edge), Double::sum);
        return result;
    }

    private static final class Model {
        private int variables;
        private final List<Map<Integer, Double>> constraints = new ArrayList<>();
        private final List<Double> bounds = new ArrayList<>();
        private final List<Map<Integer, Double>> objectives = new ArrayList<>();

        int variable() {
            return variables++;
        }

        void limit(int variable, double bound) {
            constraint(Map.of(variable, 1.0), bound);
        }

        void constraint(Map<Integer, Double> coefficients, double bound) {
            constraints.add(coefficients);
            bounds.add(bound);
        }

        void equality(Map<Integer, Double> coefficients) {
            constraint(coefficients, 0.0);
            Map<Integer, Double> inverse = new HashMap<>();
            coefficients.forEach((variable, coefficient) -> inverse.put(variable, -coefficient));
            constraint(inverse, 0.0);
        }

        void objective(Map<Integer, Double> coefficients) {
            if (!coefficients.isEmpty()) objectives.add(coefficients);
        }

        double[] expand(Map<Integer, Double> coefficients) {
            double[] row = new double[variables];
            coefficients.forEach((variable, coefficient) -> row[variable] = coefficient);
            return row;
        }

        double[] solve() {
            if (objectives.isEmpty()) return new double[variables];
            return BoundedLinearOptimizer.maximizeLexicographic(
                    constraints.stream().map(this::expand).toArray(double[][]::new),
                    bounds.stream().mapToDouble(Double::doubleValue).toArray(),
                    objectives.stream().map(this::expand).toArray(double[][]::new));
        }
    }
}
