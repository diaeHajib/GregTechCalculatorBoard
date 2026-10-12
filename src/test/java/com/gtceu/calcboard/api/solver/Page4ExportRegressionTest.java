package com.gtceu.calcboard.api.solver;

import com.gtceu.calcboard.api.model.FlowGraph;
import com.gtceu.calcboard.api.model.RecipeNode;
import com.gtceu.calcboard.api.model.role.NodeCalculationSnapshot;
import com.gtceu.calcboard.api.storage.BlueprintCodec;
import com.gtceu.calcboard.api.storage.BlueprintPackage;
import com.gtceu.calcboard.api.type.LineSolveMode;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class Page4ExportRegressionTest {
    private static final double EPSILON = 1e-9;

    private static String suppliedExport() throws Exception {
        try (InputStream stream = Page4ExportRegressionTest.class.getResourceAsStream("/boards/page4.gtboard")) {
            assertNotNull(stream, "missing exact user-supplied Page 4 export");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static FlowGraph importGraph() throws Exception {
        String code = suppliedExport();
        assertTrue(code.startsWith("GTBOARD:Page 4:H4sI"));
        BlueprintPackage pkg = BlueprintCodec.importPackageFromString(code);
        assertNotNull(pkg, "production BlueprintCodec rejected Page 4");
        assertNotNull(pkg.getMetadata());
        assertEquals("Page 4", pkg.getMetadata().getTitle());
        assertNotNull(pkg.getGraph());
        return pkg.getGraph();
    }

    @Test
    void productionImportIsCompleteAndRoundTrips() throws Exception {
        FlowGraph graph = importGraph();
        assertEquals(10, graph.getNodes().size());
        assertEquals(10, graph.getConnections().size());
        assertTrue(graph.getNodes().stream().allMatch(node -> node != null && node.getId() != null));
        assertEquals(graph.getNodes().size(),
                graph.getNodes().stream().map(RecipeNode::getId).distinct().count());
        for (FlowGraph.ConnectionEdge edge : graph.getConnections()) {
            assertNotNull(graph.findNodeById(edge.fromNodeId()), "missing source " + edge);
            assertNotNull(graph.findNodeById(edge.toNodeId()), "missing target " + edge);
        }

        String roundTrip = BlueprintCodec.exportToString(graph, "Page 4", "", "", 0, 0, 1);
        FlowGraph reimported = BlueprintCodec.importFromString(roundTrip, new double[3]);
        assertNotNull(reimported);
        assertEquals(graph.getNodes().size(), reimported.getNodes().size());
        assertEquals(graph.getConnections().size(), reimported.getConnections().size());
    }

    @Test
    void supplyOnlyRetainsHistoricalFacadeBehavior() throws Exception {
        FlowGraph implicit = importGraph();
        FlowGraph explicit = importGraph();
        Map<String, Double> viaFacade = FixedPointEfficiencySolver.computeNodeEfficiencies(implicit);
        Map<String, Double> viaMode =
                FixedPointEfficiencySolver.computeNodeEfficiencies(explicit, LineSolveMode.SUPPLY_ONLY);
        assertEfficienciesEqual(viaFacade, viaMode, "implicit versus explicit SUPPLY_ONLY");
        assertFiniteGraph(implicit);
        for (RecipeNode node : implicit.getNodes()) {
            assertEquals(1.0, node.getBlockingRatio(), EPSILON, node.getName());
        }
    }

    @Test
    void demandSolveIsFiniteConservativeStableAndOrderIndependent() throws Exception {
        FlowGraph graph = importGraph();
        Map<String, Double> first =
                FixedPointEfficiencySolver.computeNodeEfficiencies(graph, LineSolveMode.SUPPLY_AND_DEMAND);
        assertFiniteGraph(graph);
        assertAllocationConservation(graph);
        Map<String, Double> firstFlows = edgeFlows(graph);

        Map<String, Double> again =
                FixedPointEfficiencySolver.computeNodeEfficiencies(graph, LineSolveMode.SUPPLY_AND_DEMAND);
        assertEfficienciesEqual(first, again, "repeat solve");
        assertRatesEqual(firstFlows, edgeFlows(graph), "repeat solve");

        FlowGraph reversed = reversed(importGraph());
        Map<String, Double> reversedResult =
                FixedPointEfficiencySolver.computeNodeEfficiencies(reversed, LineSolveMode.SUPPLY_AND_DEMAND);
        assertEfficienciesEqual(first, reversedResult, "reversed insertion order");
        assertRatesEqual(firstFlows, edgeFlows(reversed), "reversed insertion order");

        double min = 1.0;
        double max = 0.0;
        int throttled = 0;
        for (RecipeNode node : graph.getNodes()) {
            if (node.isReroute()) continue;
            min = Math.min(min, node.getEfficiency());
            max = Math.max(max, node.getEfficiency());
            if (node.getEfficiency() < 1.0 - 1e-6) throttled++;
        }
        System.out.printf("Page 4: nodes=%d edges=%d machines=%d throttled=%d efficiency=[%.9f, %.9f]%n",
                graph.getNodes().size(), graph.getConnections().size(),
                graph.getNodes().stream().filter(node -> !node.isReroute()).count(),
                throttled, min, max);
        for (RecipeNode node : graph.getNodes()) {
            if (node.isReroute()) continue;
            System.out.printf("  %s: efficiency=%.9f acceptance=%.9f%n",
                    node.getName(), node.getEfficiency(), node.getBlockingRatio());
        }
        for (FlowGraph.ConnectionEdge edge : graph.getConnections()) {
            RecipeNode source = graph.findNodeById(edge.fromNodeId());
            RecipeNode target = graph.findNodeById(edge.toNodeId());
            System.out.printf("  edge %s[%d] -> %s[%d]: %.9f%n",
                    source != null ? source.getName() : edge.fromNodeId(), edge.outputIndex(),
                    target != null ? target.getName() : edge.toNodeId(), edge.inputIndex(),
                    FlowEdgeAllocator.getEdgeAllocatedFlow(graph, edge, null));
        }
        printSharedOutputs(graph);
    }

    @Test
    void verdictsAndBottleneckTrialsRemainConsistentAndRestored() throws Exception {
        FlowGraph graph = importGraph();
        Map<String, Double> counts = new LinkedHashMap<>();
        for (RecipeNode node : graph.getNodes()) counts.put(node.getId(), node.getMachineCount());

        FixedPointEfficiencySolver.computeNodeEfficiencies(graph, LineSolveMode.SUPPLY_AND_DEMAND);
        Map<String, Boolean> bottlenecks = bottleneckFlags(graph);
        Map<String, Double> gains = LineBottleneckAnalyzer.lastGains();
        assertFalse(gains.isEmpty(), "bottleneck sweep recorded no candidates");

        int blocked = 0;
        int starved = 0;
        var snapshot = graph.captureSnapshot();
        for (RecipeNode node : graph.getNodes()) {
            assertEquals(counts.get(node.getId()), node.getMachineCount(), EPSILON,
                    node.getName() + " count leaked from a bottleneck trial");
            NodeCalculationSnapshot state = snapshot.getNodeSnapshot(node.getId());
            assertNotNull(state);
            assertFalse(state.isBlocked() && state.isStarved(), node.getName());
            if (state.isBlocked()) blocked++;
            if (state.isStarved()) starved++;
            for (int i = 0; i < node.getInputs().size(); i++) {
                var port = graph.getInputPortStats(node, i);
                if (port.isDemandThrottled()) {
                    assertFalse(port.isInputDeficit(),
                            node.getName() + " input " + i + " is both demand-throttled and deficient");
                }
            }
        }
        assertTrue(blocked + starved > 0, "fixture exercises no constrained-machine verdict");
        assertEquals(2, blocked);
        assertEquals(6, starved);
        assertEquals("Chemical Reactor (Raw Rubber Pulp)",
                graph.getNodes().stream().filter(RecipeNode::isBottleneck)
                        .map(RecipeNode::getName).findFirst().orElse(null));

        FixedPointEfficiencySolver.computeNodeEfficiencies(graph, LineSolveMode.SUPPLY_AND_DEMAND);
        assertEquals(bottlenecks, bottleneckFlags(graph), "cache hit changed bottleneck winner");
        for (RecipeNode node : graph.getNodes()) {
            assertEquals(counts.get(node.getId()), node.getMachineCount(), EPSILON,
                    node.getName() + " count changed on cached solve");
        }
        RecipeNode winner = graph.getNodes().stream().filter(RecipeNode::isBottleneck)
                .findFirst().orElse(null);
        System.out.printf("Page 4 verdicts: blocked=%d starved=%d bottleneck=%s gain=%.9f candidates=%d%n",
                blocked, starved,
                winner != null ? winner.getName() : "<none>",
                winner != null ? gains.getOrDefault(winner.getId(), 0.0) : 0.0,
                gains.size());
    }

    private static void assertFiniteGraph(FlowGraph graph) {
        for (RecipeNode node : graph.getNodes()) {
            assertTrue(Double.isFinite(node.getEfficiency()), node.getName() + " efficiency");
            assertTrue(node.getEfficiency() >= -EPSILON && node.getEfficiency() <= 1.0 + EPSILON,
                    node.getName() + " efficiency " + node.getEfficiency());
            for (int i = 0; i < node.getInputs().size(); i++) {
                var stats = graph.getInputPortStats(node, i);
                assertTrue(Double.isFinite(stats.connectedRate()), node.getName() + " input " + i);
                assertTrue(stats.connectedRate() >= -EPSILON, node.getName() + " input " + i);
            }
            for (int i = 0; i < node.getOutputs().size(); i++) {
                var stats = graph.getOutputPortStats(node, i);
                assertTrue(Double.isFinite(stats.connectedRate()), node.getName() + " output " + i);
                assertTrue(stats.connectedRate() >= -EPSILON, node.getName() + " output " + i);
            }
        }
    }

    private static void assertAllocationConservation(FlowGraph graph) {
        for (RecipeNode producer : graph.getNodes()) {
            for (int output = 0; output < producer.getOutputs().size(); output++) {
                final int port = output;
                List<FlowGraph.ConnectionEdge> edges = graph.getConnections().stream()
                        .filter(edge -> edge.fromNodeId().equals(producer.getId())
                                && edge.outputIndex() == port)
                        .toList();
                if (edges.isEmpty()) continue;
                double available = FlowEdgeAllocator.getEffectiveProducerOutputRate(graph, producer, output);
                double allocated = 0.0;
                for (FlowGraph.ConnectionEdge edge : edges) {
                    double flow = FlowEdgeAllocator.getEdgeAllocatedFlow(graph, edge, null);
                    assertTrue(Double.isFinite(flow) && flow >= -EPSILON, edge.toString());
                    allocated += flow;
                }
                assertTrue(allocated <= available + Math.max(1e-7, available * 1e-8),
                        producer.getName() + " output " + output + " allocates " + allocated
                                + " from " + available);
            }
        }
    }

    private static void printSharedOutputs(FlowGraph graph) {
        for (RecipeNode producer : graph.getNodes()) {
            for (int output = 0; output < producer.getOutputs().size(); output++) {
                final int port = output;
                List<FlowGraph.ConnectionEdge> edges = graph.getConnections().stream()
                        .filter(edge -> edge.fromNodeId().equals(producer.getId())
                                && edge.outputIndex() == port)
                        .toList();
                if (edges.size() < 2) continue;
                List<String> shares = new ArrayList<>();
                for (FlowGraph.ConnectionEdge edge : edges) {
                    RecipeNode target = graph.findNodeById(edge.toNodeId());
                    shares.add((target != null ? target.getName() : edge.toNodeId()) + "="
                            + FlowEdgeAllocator.getEdgeAllocatedFlow(graph, edge, null));
                }
                System.out.printf("Page 4 shared output: %s[%d] -> %s%n",
                        producer.getName(), output, String.join(", ", shares));
            }
        }
    }

    private static Map<String, Boolean> bottleneckFlags(FlowGraph graph) {
        Map<String, Boolean> result = new LinkedHashMap<>();
        for (RecipeNode node : graph.getNodes()) result.put(node.getId(), node.isBottleneck());
        return result;
    }

    private static Map<String, Double> edgeFlows(FlowGraph graph) {
        Map<String, Double> result = new LinkedHashMap<>();
        for (FlowGraph.ConnectionEdge edge : graph.getConnections()) {
            String key = edge.fromNodeId() + ":" + edge.outputIndex() + "->"
                    + edge.toNodeId() + ":" + edge.inputIndex();
            result.put(key, FlowEdgeAllocator.getEdgeAllocatedFlow(graph, edge, null));
        }
        return result;
    }

    private static FlowGraph reversed(FlowGraph original) {
        FlowGraph graph = new FlowGraph();
        List<RecipeNode> nodes = new ArrayList<>(original.getNodes());
        Collections.reverse(nodes);
        for (RecipeNode node : nodes) graph.addNode(node.copy(node.getId()));
        List<FlowGraph.ConnectionEdge> edges = new ArrayList<>(original.getConnections());
        Collections.reverse(edges);
        for (FlowGraph.ConnectionEdge edge : edges) graph.addConnection(edge);
        return graph;
    }

    private static void assertEfficienciesEqual(
            Map<String, Double> expected,
            Map<String, Double> actual,
            String context
    ) {
        assertEquals(expected.keySet(), actual.keySet(), context);
        for (Map.Entry<String, Double> entry : expected.entrySet()) {
            assertEquals(entry.getValue(), actual.get(entry.getKey()), EPSILON,
                    context + " node " + entry.getKey());
        }
    }

    private static void assertRatesEqual(
            Map<String, Double> expected,
            Map<String, Double> actual,
            String context
    ) {
        assertEquals(expected.keySet(), actual.keySet(), context);
        for (Map.Entry<String, Double> entry : expected.entrySet()) {
            assertEquals(entry.getValue(), actual.get(entry.getKey()), EPSILON,
                    context + " edge " + entry.getKey());
        }
    }
}
