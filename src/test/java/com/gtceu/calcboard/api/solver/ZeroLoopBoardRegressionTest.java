package com.gtceu.calcboard.api.solver;

import com.gtceu.calcboard.api.model.FlowGraph;
import com.gtceu.calcboard.api.model.RecipeNode;
import com.gtceu.calcboard.api.storage.BlueprintCodec;
import com.gtceu.calcboard.api.type.LineSolveMode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZeroLoopBoardRegressionTest {

    private static FlowGraph load() throws Exception {
        try (var input = ZeroLoopBoardRegressionTest.class.getResourceAsStream("/boards/zero_loop.gtboard")) {
            assertNotNull(input);
            var blueprint = BlueprintCodec.importPackageFromString(
                    new String(input.readAllBytes(), StandardCharsets.UTF_8).trim());
            assertNotNull(blueprint);
            return blueprint.getGraph();
        }
    }

    @Test
    void recycledProductionLineDoesNotCollapse() throws Exception {
        FlowGraph graph = load();
        for (LineSolveMode mode : LineSolveMode.values()) {
            Map<String, Double> efficiencies = FixedPointEfficiencySolver.computeNodeEfficiencies(graph, mode);
            assertEquals(1.0, efficiencies.get("2d53b513-401c-4d8f-84f0-0a1e42702600"), 1e-6);
            assertEquals(0.25, efficiencies.get("aa5e68ad-d530-4106-8dd1-e20ff0669464"), 1e-6);
            assertEquals(0.004, efficiencies.get("dd87b9a3-2731-4072-a0b2-5ca15fdb50fa"), 1e-6);
            assertEquals(0.06, efficiencies.get("d79591c1-ba41-4109-93f8-e03db5766bc0"), 1e-6);
            assertEquals(0.8, efficiencies.get("c3f72b64-cc5a-4d83-bd9f-a50735b8d22a"), 1e-6);
            assertEquals(0.5, efficiencies.get("8e9c3940-1f9f-4ddb-8691-1b46537fffae"), 1e-6);
            assertEquals(0.125, efficiencies.get("b0e15b4c-3e61-421d-a0cf-43d0bc1e27b6"), 1e-6);
            for (var node : graph.getNodes()) {
                assertTrue(node.getEfficiency() > 1e-4, node.getName() + " collapsed in " + mode);
                for (int port = 0; port < node.getInputs().size(); port++) {
                    var stats = graph.getInputPortStats(node, port);
                    assertFalse(stats.isUnfedDampedLoop(), node.getName() + " falsely classified as decaying");
                    if (stats.isConnected()) {
                        assertTrue(stats.connectedRate() + 1e-6 >= node.getInputSlotRate(port, true),
                                node.getName() + " consumes more than its connected feed");
                    }
                }
            }
        }
    }

    @Test
    void repeatedAndReorderedSolvesPreserveTheNonzeroOperatingPoint() throws Exception {
        FlowGraph graph = load();
        FlowGraph reversed = new FlowGraph();
        for (int i = graph.getNodes().size() - 1; i >= 0; i--) {
            var node = graph.getNodes().get(i);
            var copy = node.copy(node.getId());
            copy.setEfficiency(0.0);
            reversed.addNode(copy);
        }
        for (int i = graph.getConnections().size() - 1; i >= 0; i--) {
            reversed.addConnection(graph.getConnections().get(i));
        }
        for (LineSolveMode mode : LineSolveMode.values()) {
            Map<String, Double> expected = FixedPointEfficiencySolver.computeNodeEfficiencies(graph, mode);
            for (int pass = 0; pass < 3; pass++) {
                Map<String, Double> actual = FixedPointEfficiencySolver.computeNodeEfficiencies(reversed, mode);
                reversed.invalidatePortStatsCache();
                for (var node : reversed.getNodes()) {
                    assertEquals(expected.get(node.getId()), actual.get(node.getId()), 1e-6);
                    for (int port = 0; port < node.getInputs().size(); port++) {
                        var stats = reversed.getInputPortStats(node, port);
                        if (stats.isConnected()) {
                            assertTrue(stats.connectedRate() + 1e-6 >= node.getInputSlotRate(port, true));
                        }
                    }
                }
            }
        }
    }

    @Test
    void passiveJunctionsDoNotChangeTheRecyclingBalance() throws Exception {
        FlowGraph graph = load();
        var edge = graph.getConnections().get(0);
        RecipeNode junction = RecipeNode.createReroute(0, 0);
        junction.bindRerouteIngredient(graph.findNodeById(edge.fromNodeId()).getOutputs().get(edge.outputIndex()));
        graph.addNode(junction);
        graph.removeConnection(edge);
        graph.addConnection(edge.fromNodeId(), edge.outputIndex(), junction.getId(), 0);
        graph.addConnection(junction.getId(), 0, edge.toNodeId(), edge.inputIndex());
        for (LineSolveMode mode : LineSolveMode.values()) {
            var efficiencies = FixedPointEfficiencySolver.computeNodeEfficiencies(graph, mode);
            assertEquals(1.0, efficiencies.get("2d53b513-401c-4d8f-84f0-0a1e42702600"), 1e-6);
            assertEquals(0.25, efficiencies.get("aa5e68ad-d530-4106-8dd1-e20ff0669464"), 1e-6);
            for (var node : graph.getNodes()) {
                assertTrue(node.getEfficiency() > 1e-4, node.getName() + " collapsed through a junction");
            }
        }
    }
}
