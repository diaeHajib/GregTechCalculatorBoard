package com.gtceu.calcboard.api.solver;

import com.gtceu.calcboard.api.model.FlowGraph;
import com.gtceu.calcboard.api.model.IngredientStack;
import com.gtceu.calcboard.api.model.RecipeNode;
import com.gtceu.calcboard.api.type.GTVoltageTier;
import com.gtceu.calcboard.api.type.LineSolveMode;
import com.gtceu.calcboard.api.type.SupplyMode;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProductionAllocationTest {
    private static final LineSolveMode MODE = LineSolveMode.SUPPLY_AND_DEMAND;

    private static IngredientStack item(String id, double amount) {
        return IngredientStack.item(ResourceLocation.tryParse("test:" + id), id, amount, 1.0);
    }

    private static RecipeNode machine(FlowGraph graph, String name, double in, double out) {
        RecipeNode node = RecipeNode.create(name, 20.0, 20.0, GTVoltageTier.MV);
        if (in > 0) node.addInput(item("feed", in));
        if (out > 0) node.addOutput(item("feed", out));
        graph.addNode(node);
        return node;
    }

    private static RecipeNode relay(FlowGraph graph, SupplyMode mode, double rate) {
        RecipeNode node = RecipeNode.createReroute(0, 0);
        node.bindRerouteIngredient(item("feed", 1));
        node.setSupplyMode(mode);
        node.setExternalSupplyRate(rate);
        node.setExternalDrainRate(rate);
        graph.addNode(node);
        return node;
    }

    private static void connect(FlowGraph graph, RecipeNode from, RecipeNode to) {
        graph.addConnection(from.getId(), 0, to.getId(), 0);
    }

    private static double received(FlowGraph graph, RecipeNode node) {
        return graph.getInputPortStats(node, 0).connectedRate();
    }

    @Test
    void splitTracksCapacitiesNotPreviousEfficienciesAndSurvivesRelays() {
        for (boolean throughRelay : new boolean[]{false, true}) {
            FlowGraph graph = new FlowGraph();
            RecipeNode producer = machine(graph, "producer", 0, 30);
            RecipeNode a = machine(graph, "a", 7.2, 0);
            RecipeNode b = machine(graph, "b", 9.6, 0);
            RecipeNode source = producer;
            if (throughRelay) {
                source = relay(graph, SupplyMode.NONE, 0);
                connect(graph, producer, source);
            }
            connect(graph, source, a);
            connect(graph, source, b);
            a.setEfficiency(0.01);
            b.setEfficiency(0.9);
            for (int repeat = 0; repeat < 3; repeat++) {
                Map<String, Double> result = FixedPointEfficiencySolver.computeNodeEfficiencies(graph, MODE);
                assertEquals(0.56, producer.getEfficiency(), 1e-6);
                assertEquals(7.2, received(graph, a), 1e-6);
                assertEquals(9.6, received(graph, b), 1e-6);
                graph.invalidatePortStatsCache();
                assertEquals(7.2, received(graph, a), 1e-6, "display-cache refresh must retain solve mode");
                for (RecipeNode node : graph.getNodes()) {
                    assertEquals(result.get(node.getId()), node.getEfficiency(), 1e-9);
                }
            }
            FixedPointEfficiencySolver.computeNodeEfficiencies(graph, LineSolveMode.SUPPLY_ONLY);
            assertEquals(1, producer.getEfficiency(), 1e-9);
            assertTrue(graph.getProductionAllocationWeights().isEmpty());
        }
    }

    @Test
    void competingProducersShareCapacityWithoutDoubleCounting() {
        FlowGraph graph = new FlowGraph();
        RecipeNode small = machine(graph, "small", 0, 10);
        RecipeNode large = machine(graph, "large", 0, 30);
        RecipeNode consumer = machine(graph, "consumer", 20, 0);
        connect(graph, small, consumer);
        connect(graph, large, consumer);
        FixedPointEfficiencySolver.computeNodeEfficiencies(graph, MODE);
        assertEquals(0.5, small.getEfficiency(), 1e-6);
        assertEquals(0.5, large.getEfficiency(), 1e-6);
        assertEquals(20, received(graph, consumer), 1e-6);
        assertEquals(1, consumer.getEfficiency(), 1e-6);
    }

    @Test
    void relaySupplyOffsetsDemandAndDrainAddsDemand() {
        FlowGraph graph = new FlowGraph();
        RecipeNode producer = machine(graph, "producer", 0, 20);
        RecipeNode supply = relay(graph, SupplyMode.FIXED_RATE, 3);
        RecipeNode drain = relay(graph, SupplyMode.FIXED_DRAIN, 2);
        RecipeNode consumer = machine(graph, "consumer", 10, 0);
        connect(graph, producer, supply);
        connect(graph, supply, drain);
        connect(graph, drain, consumer);
        FixedPointEfficiencySolver.computeNodeEfficiencies(graph, MODE);
        assertEquals(9.0 / 20, producer.getEfficiency(), 1e-6);
        assertEquals(10, received(graph, consumer), 1e-6);
    }

    @Test
    void feedDeficitBelowAcceptanceCeilingIsStarvedNotBlocked() {
        for (double scale : new double[]{1, 0.001}) {
            assertFeedDeficit(false, scale);
            assertFeedDeficit(true, scale);
        }
    }

    private static void assertFeedDeficit(boolean module, double scale) {
        FlowGraph graph = new FlowGraph();
        RecipeNode source = relay(graph, SupplyMode.FIXED_RATE, 0.2 * scale);
        RecipeNode consumer = machine(graph, "consumer", 1, 1);
        if (module) consumer.setModule(true);
        RecipeNode drain = relay(graph, SupplyMode.FIXED_DRAIN, 0.5 * scale);
        connect(graph, source, consumer);
        connect(graph, consumer, drain);
        FixedPointEfficiencySolver.computeNodeEfficiencies(graph, MODE);
        assertEquals(0.2 * scale, consumer.getEfficiency(), 1e-9);
        assertEquals(0.5 * scale, consumer.getBlockingRatio(), 1e-9);
        var snapshot = graph.captureSnapshot().getNodeSnapshot(consumer.getId());
        assertTrue(snapshot.isStarved());
        assertFalse(snapshot.isBlocked());
        var port = graph.getInputPortStats(consumer, 0);
        assertFalse(port.isDemandThrottled());
        assertTrue(port.isInputDeficit());
    }

    @Test
    void allocationDoesNotReplaceScalingDemand() {
        FlowGraph graph = new FlowGraph();
        RecipeNode source = machine(graph, "source", 0, 1);
        RecipeNode a = machine(graph, "a", 1, 0);
        RecipeNode b = machine(graph, "b", 3, 0);
        connect(graph, source, a);
        connect(graph, source, b);
        FixedPointEfficiencySolver.computeNodeEfficiencies(graph, MODE);
        assertEquals(0.25, received(graph, a), 1e-6);
        assertEquals(0.75, received(graph, b), 1e-6);
        assertEquals(0.25, FlowEdgeAllocator.getConnectedConsumerDemand(graph, a, 0), 1e-6);
        assertEquals(0.75, FlowEdgeAllocator.getConnectedConsumerDemand(graph, b, 0), 1e-6);
    }

    @Test
    void savedBoardModeDrivesFacadeButDoesNotChangeAnotherSolvedGraph() {
        var original = com.gtceu.calcboard.api.type.LineSolveModeHolder.get();
        try {
            var settings = new com.gtceu.calcboard.api.storage.BoardSettings();
            settings.setLineSolveMode(MODE);
            var saved = new net.minecraft.nbt.CompoundTag();
            settings.serializeNBT(saved);
            settings.resetToDefault();
            settings.deserializeNBT(saved);
            assertEquals(MODE, settings.getLineSolveMode());
            FlowGraph graph = new FlowGraph();
            RecipeNode producer = machine(graph, "producer", 0, 30);
            RecipeNode a = machine(graph, "a", 7.2, 0);
            RecipeNode b = machine(graph, "b", 9.6, 0);
            connect(graph, producer, a);
            connect(graph, producer, b);
            FlowBalanceMatrixSolver.computeNodeEfficiencies(graph);
            assertEquals(0.56, producer.getEfficiency(), 1e-6);
            settings.resetToDefault();
            graph.invalidatePortStatsCache();
            assertEquals(7.2, received(graph, a), 1e-6);
            assertEquals(9.6, received(graph, b), 1e-6);
            FlowBalanceMatrixSolver.computeNodeEfficiencies(graph);
            assertEquals(1, producer.getEfficiency(), 1e-6);
        } finally {
            com.gtceu.calcboard.api.type.LineSolveModeHolder.set(original);
        }
    }

    @Test
    void autoRatioCountsAreIndependentOfProductionMode() {
        var original = com.gtceu.calcboard.api.type.LineSolveModeHolder.get();
        try {
            for (boolean integerCounts : new boolean[]{false, true}) {
                FlowGraph graph = new FlowGraph();
                RecipeNode source = machine(graph, "source", 0, 10);
                RecipeNode a = machine(graph, "a", 1, 0);
                RecipeNode b = machine(graph, "b", 3, 0);
                connect(graph, source, a);
                connect(graph, source, b);
                FlowGraph other = graph.copy();
                com.gtceu.calcboard.api.type.LineSolveModeHolder.set(LineSolveMode.SUPPLY_ONLY);
                graph.autoRatioFromAnchor(b, integerCounts);
                com.gtceu.calcboard.api.type.LineSolveModeHolder.set(MODE);
                FixedPointEfficiencySolver.computeNodeEfficiencies(other, MODE);
                other.autoRatioFromAnchor(other.findNodeById(b.getId()), integerCounts);
                for (RecipeNode node : graph.getNodes()) {
                    assertEquals(node.getMachineCount(), other.findNodeById(node.getId()).getMachineCount(),
                            1e-6, node.getName());
                }
            }
        } finally {
            com.gtceu.calcboard.api.type.LineSolveModeHolder.set(original);
        }
    }
}
