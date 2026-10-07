package com.gtceu.calcboard.api.solver;

import com.gtceu.calcboard.api.model.FlowGraph;
import com.gtceu.calcboard.api.model.IngredientStack;
import com.gtceu.calcboard.api.model.RecipeNode;
import com.gtceu.calcboard.api.storage.BlueprintCodec;
import com.gtceu.calcboard.api.type.GTVoltageTier;
import com.gtceu.calcboard.api.type.LineSolveMode;
import com.gtceu.calcboard.api.type.SupplyMode;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SaltwaterBoardRegressionTest {

    private static FlowGraph load() throws Exception {
        try (var input = SaltwaterBoardRegressionTest.class.getResourceAsStream("/boards/saltwater_loop.gtboard")) {
            assertNotNull(input);
            var blueprint = BlueprintCodec.importPackageFromString(
                    new String(input.readAllBytes(), StandardCharsets.UTF_8).trim());
            assertNotNull(blueprint);
            return blueprint.getGraph();
        }
    }

    @Test
    void coupledSaltwaterProducersFillElectrolyzerCapacity() throws Exception {
        FlowGraph graph = load();
        RecipeNode electrolyzer = graph.findNodeById("f3280782-b411-4f8a-8479-39deb1e81e92");
        RecipeNode epoxy = graph.findNodeById("3961b1ae-b85b-4181-8834-317d5fe0cb70");
        RecipeNode epichlorohydrin = graph.findNodeById("266c7209-5741-4cd7-b18f-055b868f4ef2");

        FixedPointEfficiencySolver.computeNodeEfficiencies(graph, LineSolveMode.SUPPLY_AND_DEMAND);

        double capacity = electrolyzer.getInputSlotRate(0, false);
        assertEquals(capacity / 2.0, epoxy.getOutputSlotRate(1, true), 1e-6);
        assertEquals(capacity / 2.0, epichlorohydrin.getOutputSlotRate(2, true), 1e-6);
        assertEquals(1.0, electrolyzer.getEfficiency(), 1e-6);
        assertEquals(capacity, graph.getInputPortStats(electrolyzer, 0).connectedRate(), 1e-6);
        assertEquals(capacity / 2.0, DownstreamBlockingSolver.analyzeOutputPort(graph, epoxy, 1).acceptedRate(), 1e-6);

        graph.invalidatePortStatsCache();
        assertEquals(capacity, graph.getInputPortStats(electrolyzer, 0).connectedRate(), 1e-6);
        assertEquals(capacity / 2.0, graph.getProductionAllocationWeights().get(
                new FlowGraph.ConnectionEdge(epoxy.getId(), 1, electrolyzer.getId(), 0)), 1e-6);
    }

    @Test
    void saltwaterRatesAreStableAcrossOrderRefreshAndModeChanges() throws Exception {
        FlowGraph graph = load();
        Map<String, Double> expected = FixedPointEfficiencySolver.computeNodeEfficiencies(
                graph, LineSolveMode.SUPPLY_AND_DEMAND);
        FlowGraph reversed = new FlowGraph();
        for (int i = graph.getNodes().size() - 1; i >= 0; i--) {
            RecipeNode node = graph.getNodes().get(i);
            RecipeNode copy = node.copy(node.getId());
            copy.setEfficiency(0.0);
            reversed.addNode(copy);
        }
        for (int i = graph.getConnections().size() - 1; i >= 0; i--) {
            reversed.addConnection(graph.getConnections().get(i));
        }
        for (int pass = 0; pass < 3; pass++) {
            Map<String, Double> actual = FixedPointEfficiencySolver.computeNodeEfficiencies(
                    reversed, LineSolveMode.SUPPLY_AND_DEMAND);
            for (var entry : expected.entrySet()) {
                assertEquals(entry.getValue(), actual.get(entry.getKey()), 1e-9);
            }
            FixedPointEfficiencySolver.computeNodeEfficiencies(reversed, LineSolveMode.SUPPLY_ONLY);
            assertEquals(Map.of(), reversed.getProductionCapacityEfficiencies());
        }
    }

    @Test
    void sharedSaltwaterReservationsAlsoRecoverThroughAMergingJunction() throws Exception {
        FlowGraph graph = load();
        RecipeNode electrolyzer = graph.findNodeById("f3280782-b411-4f8a-8479-39deb1e81e92");
        RecipeNode junction = RecipeNode.createReroute(0, 0);
        junction.bindRerouteIngredient(electrolyzer.getInputs().get(0));
        graph.addNode(junction);
        for (var edge : java.util.List.copyOf(graph.getConnections())) {
            if (!edge.toNodeId().equals(electrolyzer.getId())) continue;
            graph.removeConnection(edge);
            graph.addConnection(edge.fromNodeId(), edge.outputIndex(), junction.getId(), 0);
        }
        graph.addConnection(junction.getId(), 0, electrolyzer.getId(), 0);

        FixedPointEfficiencySolver.computeNodeEfficiencies(graph, LineSolveMode.SUPPLY_AND_DEMAND);

        assertEquals(1.0, electrolyzer.getEfficiency(), 1e-6);
        assertEquals(electrolyzer.getInputSlotRate(0, false),
                graph.getInputPortStats(electrolyzer, 0).connectedRate(), 1e-6);
    }

    @Test
    void anUnderfedCoProducerDoesNotStrandTheOtherProducersReservation() {
        FlowGraph graph = new FlowGraph();
        IngredientStack saltwater = IngredientStack.fluid(
                ResourceLocation.tryParse("gtceu:salt_water"), "Salt Water", 1.0, 1.0);
        IngredientStack water = IngredientStack.fluid(
                ResourceLocation.tryParse("minecraft:water"), "Water", 1.0, 1.0);
        RecipeNode weak = RecipeNode.create("Underfed Producer", 20, 30, GTVoltageTier.LV);
        weak.addInput(water);
        weak.addOutput(saltwater);
        RecipeNode strong = RecipeNode.create("Available Producer", 20, 30, GTVoltageTier.LV);
        strong.addOutput(saltwater);
        RecipeNode consumer = RecipeNode.create("Consumer", 20, 30, GTVoltageTier.LV);
        consumer.addInput(saltwater);
        RecipeNode feed = RecipeNode.createReroute(0, 0);
        feed.bindRerouteIngredient(water);
        feed.setSupplyMode(SupplyMode.FIXED_RATE);
        feed.setExternalSupplyRate(0.1);
        graph.addNode(weak);
        graph.addNode(strong);
        graph.addNode(consumer);
        graph.addNode(feed);
        graph.addConnection(feed.getId(), 0, weak.getId(), 0);
        graph.addConnection(weak.getId(), 0, consumer.getId(), 0);
        graph.addConnection(strong.getId(), 0, consumer.getId(), 0);

        FixedPointEfficiencySolver.computeNodeEfficiencies(graph, LineSolveMode.SUPPLY_AND_DEMAND);

        assertEquals(0.1, weak.getEfficiency(), 1e-6);
        assertEquals(0.9, strong.getEfficiency(), 1e-6);
        assertEquals(1.0, consumer.getEfficiency(), 1e-6);
    }
}
