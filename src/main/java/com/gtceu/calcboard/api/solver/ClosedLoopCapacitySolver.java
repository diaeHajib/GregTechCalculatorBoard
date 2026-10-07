package com.gtceu.calcboard.api.solver;

import com.gtceu.calcboard.api.model.FlowGraph;
import com.gtceu.calcboard.api.model.RecipeNode;
import com.gtceu.calcboard.api.solver.linear.BoundedLinearOptimizer;
import com.gtceu.calcboard.api.type.FlowSplitMode;

import java.util.*;

/**
 * Finds a capacity-bounded operating point for isolated, unfed recycling components.
 * Port-level inequalities allow surplus production without inventing external feed.
 */
public final class ClosedLoopCapacitySolver {

    private ClosedLoopCapacitySolver() {}

    public record Capacity(Set<String> nodeIds, Map<String, Double> efficiencies,
                           Map<FlowGraph.ConnectionEdge, Double> flows) {}

    public static List<Capacity> solve(FlowGraph graph, FlowEdgeAllocator.SolverContext context) {
        List<Capacity> result = new ArrayList<>();
        for (Set<String> component : ProcessStabilityAnalyzer.findStronglyConnectedComponents(graph, context.edgeIndex())) {
            if (component.size() < 2 || !supportsComponent(graph, component, context)) continue;
            Capacity capacity = solveComponent(graph, component, context);
            if (capacity.efficiencies().values().stream().anyMatch(value -> value > 1e-6)) {
                result.add(capacity);
            }
        }
        return result;
    }

    private static boolean supportsComponent(
            FlowGraph graph, Set<String> component, FlowEdgeAllocator.SolverContext context) {
        for (String id : component) {
            RecipeNode node = graph.findNodeById(id);
            if (node == null || node.isCompoundNode() || node.isModule()) return false;
            if (node.isReroute() && (node.isExternalSupply() || node.isInfiniteSupply() || node.isLinkedJunction()
                    || node.isFixedDrain() || node.isVoidSink() || node.getAllocatedExportRate() > 0
                    || node.getJunctionSplitMode() != FlowSplitMode.PROPORTIONAL)) return false;
            if (node.getInputs().stream().anyMatch(stack -> stack.isStressUnit())) return false;
            for (FlowGraph.ConnectionEdge edge : context.getInEdges(id)) {
                if (!component.contains(edge.fromNodeId())) return false;
            }
            for (FlowGraph.ConnectionEdge edge : context.getOutEdges(id)) {
                if (!component.contains(edge.toNodeId()) || edge.priority() != 0
                        || Math.abs(edge.weight() - 1.0) > 1e-6) return false;
            }
        }
        return true;
    }

    private static Capacity solveComponent(
            FlowGraph graph, Set<String> component, FlowEdgeAllocator.SolverContext context) {
        List<RecipeNode> machines = component.stream().sorted().map(graph::findNodeById)
                .filter(node -> !node.isReroute()).toList();
        List<FlowGraph.ConnectionEdge> edges = graph.getConnections().stream()
                .filter(edge -> component.contains(edge.fromNodeId()))
                .sorted(Comparator.comparing(FlowGraph.ConnectionEdge::fromNodeId)
                        .thenComparingInt(FlowGraph.ConnectionEdge::outputIndex)
                        .thenComparing(FlowGraph.ConnectionEdge::toNodeId)
                        .thenComparingInt(FlowGraph.ConnectionEdge::inputIndex))
                .toList();
        int variables = machines.size() + edges.size();
        Map<FlowGraph.ConnectionEdge, Integer> edgeVariables = new HashMap<>();
        for (int i = 0; i < edges.size(); i++) edgeVariables.put(edges.get(i), machines.size() + i);
        List<double[]> constraints = new ArrayList<>();
        List<Double> bounds = new ArrayList<>();
        double[] objective = new double[variables];
        for (int i = 0; i < machines.size(); i++) {
            RecipeNode node = machines.get(i);
            objective[i] = 1.0;
            double[] limit = new double[variables];
            limit[i] = 1.0;
            constraints.add(limit);
            bounds.add(1.0);
            for (int port = 0; port < node.getInputs().size(); port++) {
                List<FlowGraph.ConnectionEdge> incoming = context.edgeIndex().getInPortEdges(node.getId(), port);
                if (incoming.isEmpty()) continue;
                double[] demand = new double[variables];
                demand[i] = context.getInputRate(node, port);
                for (FlowGraph.ConnectionEdge edge : incoming) demand[edgeVariables.get(edge)] = -1.0;
                constraints.add(demand);
                bounds.add(0.0);
            }
            for (int port = 0; port < node.getOutputs().size(); port++) {
                List<FlowGraph.ConnectionEdge> outgoing = context.edgeIndex().getOutPortEdges(node.getId(), port);
                if (outgoing.isEmpty()) continue;
                double[] production = new double[variables];
                production[i] = -context.getOutputRate(node, port);
                for (FlowGraph.ConnectionEdge edge : outgoing) production[edgeVariables.get(edge)] = 1.0;
                constraints.add(production);
                bounds.add(0.0);
            }
        }
        for (String id : component) {
            RecipeNode node = graph.findNodeById(id);
            if (!node.isReroute()) continue;
            double[] balance = new double[variables];
            for (FlowGraph.ConnectionEdge edge : context.getInEdges(id)) balance[edgeVariables.get(edge)] -= 1.0;
            for (FlowGraph.ConnectionEdge edge : context.getOutEdges(id)) balance[edgeVariables.get(edge)] += 1.0;
            constraints.add(balance);
            bounds.add(0.0);
        }
        for (FlowGraph.ConnectionEdge edge : edges) {
            if (!edge.hasFixedLimit()) continue;
            double[] limit = new double[variables];
            limit[edgeVariables.get(edge)] = 1.0;
            constraints.add(limit);
            bounds.add(edge.fixedFlowLimit());
        }
        double[] solution = BoundedLinearOptimizer.maximize(
                constraints.toArray(double[][]::new), bounds.stream().mapToDouble(Double::doubleValue).toArray(), objective);
        Map<String, Double> efficiencies = new HashMap<>();
        Map<FlowGraph.ConnectionEdge, Double> flows = new HashMap<>();
        for (int i = 0; i < machines.size(); i++) {
            efficiencies.put(machines.get(i).getId(), Math.min(1.0, solution[i]));
        }
        for (FlowGraph.ConnectionEdge edge : edges) flows.put(edge, solution[edgeVariables.get(edge)]);
        return new Capacity(Set.copyOf(component), Map.copyOf(efficiencies), Map.copyOf(flows));
    }
}
