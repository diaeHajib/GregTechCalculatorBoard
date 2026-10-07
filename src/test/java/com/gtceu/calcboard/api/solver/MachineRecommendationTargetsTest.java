package com.gtceu.calcboard.api.solver;

import com.gtceu.calcboard.api.history.command.RecommendationTargetsCommand;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MachineRecommendationTargetsTest {
    private static final LineSolveMode MODE = LineSolveMode.PRIMED;
    private static final String ALLOY = "9350fbcf-19ba-45f0-a698-ddcdf041b218";
    private static final String SMELTING = "4994facb-c469-4164-ae9f-eb698bd91dee";
    private static final String SUPERCOMPUTER = "35d6c78e-e7bb-4707-9d28-ea858f7b173c";

    private static RecipeNode machine(String name) {
        return RecipeNode.create(name, 20, 20, GTVoltageTier.MV);
    }

    private static IngredientStack material(String name, double amount) {
        return IngredientStack.item(ResourceLocation.tryParse("test:" + name), name, amount);
    }

    private static RecipeNode source(String name, double supply) {
        RecipeNode node = machine(name + " source");
        node.addOutput(material(name, supply));
        return node;
    }

    private static RecipeNode target(String name, double demand) {
        RecipeNode node = machine(name + " target");
        node.addInput(material(name, demand));
        node.addOutput(material(name + "_product", demand));
        return node;
    }

    private static FlowGraph graph(RecipeNode... nodes) {
        FlowGraph graph = new FlowGraph();
        for (RecipeNode node : nodes) graph.addNode(node);
        return graph;
    }

    private static String winner(FlowGraph graph) {
        return graph.getNodes().stream().filter(RecipeNode::isBottleneck)
                .map(RecipeNode::getId).findFirst().orElse(null);
    }

    @Test
    void targetFindsItsUpstreamConstraintAndDoesNotRewardIdleExtraCapacity() {
        RecipeNode source = source("feed", 4);
        RecipeNode target = target("feed", 10);
        RecipeNode unrelated = source("waste", 1000000);
        FlowGraph graph = graph(source, target, unrelated);
        graph.addConnection(source.getId(), 0, target.getId(), 0);
        graph.setRecommendationTargetIds(Set.of(target.getId()));
        FixedPointEfficiencySolver.computeNodeEfficiencies(graph, MODE);
        assertEquals(source.getId(), winner(graph));
        assertEquals(0.4, LineBottleneckAnalyzer.lastGains().get(source.getId()), 1e-6);
        assertEquals(0, LineBottleneckAnalyzer.lastGains().get(target.getId()), 1e-6);
        assertEquals(0, LineBottleneckAnalyzer.lastGains().get(unrelated.getId()), 1e-6);
        assertEquals(0.4, target.getEfficiency(), 1e-6);
        assertEquals(1, target.getMachineCount());
    }

    @Test
    void weakestTargetWinsOverLargerCombinedImprovement() {
        RecipeNode weakSource = source("weak", 1);
        RecipeNode weak = target("weak", 10);
        RecipeNode strongSource = source("strong", 6);
        RecipeNode strong = target("strong", 10);
        FlowGraph graph = graph(weakSource, weak, strongSource, strong);
        graph.addConnection(weakSource.getId(), 0, weak.getId(), 0);
        graph.addConnection(strongSource.getId(), 0, strong.getId(), 0);
        graph.setRecommendationTargetIds(Set.of(weak.getId(), strong.getId()));
        FixedPointEfficiencySolver.computeNodeEfficiencies(graph, MODE);
        assertEquals(weakSource.getId(), winner(graph));
        assertTrue(LineBottleneckAnalyzer.lastGains().get(strongSource.getId())
                > LineBottleneckAnalyzer.lastGains().get(weakSource.getId()),
                "scalar diagnostic gains must not override weakest-first vector ranking");
    }

    @Test
    void tiedWeakestTargetsCanImproveOneAtATime() {
        RecipeNode firstSource = source("first", 1);
        RecipeNode first = target("first", 10);
        RecipeNode secondSource = source("second", 1);
        RecipeNode second = target("second", 10);
        FlowGraph graph = graph(firstSource, first, secondSource, second);
        graph.addConnection(firstSource.getId(), 0, first.getId(), 0);
        graph.addConnection(secondSource.getId(), 0, second.getId(), 0);
        graph.setRecommendationTargetIds(Set.of(first.getId(), second.getId()));
        FixedPointEfficiencySolver.computeNodeEfficiencies(graph, MODE);
        assertEquals(firstSource.getId(), winner(graph));
        assertEquals(0.1, firstSource.getBottleneckGain(), 1e-6);
    }

    @Test
    void expandingAFullyUtilizedTargetCountsActualAdditionalWork() {
        RecipeNode target = source("free_feed", 10);
        FlowGraph graph = graph(target);
        graph.setRecommendationTargetIds(Set.of(target.getId()));
        FixedPointEfficiencySolver.computeNodeEfficiencies(graph, MODE);
        assertEquals(target.getId(), winner(graph));
        assertEquals(1, target.getBottleneckGain(), 1e-6);
        assertEquals(1, target.getMachineCount());
    }

    @Test
    void zeroThroughputTargetStillParticipates() {
        RecipeNode source = source("feed", 1);
        RecipeNode target = target("feed", 1);
        RecipeNode drain = RecipeNode.createReroute(0, 0);
        drain.bindRerouteIngredient(material("feed", 1));
        drain.setSupplyMode(SupplyMode.FIXED_DRAIN);
        drain.setExternalDrainRate(1);
        FlowGraph graph = graph(source, target, drain);
        graph.addConnection(source.getId(), 0, target.getId(), 0);
        graph.addConnection(source.getId(), 0, drain.getId(), 0);
        graph.setRecommendationTargetIds(Set.of(target.getId()));
        FixedPointEfficiencySolver.computeNodeEfficiencies(graph, MODE);
        assertEquals(0, target.getEffectiveCyclesPerSecond(), 1e-6);
        assertEquals(source.getId(), winner(graph));
        assertEquals(1, source.getBottleneckGain(), 1e-6);
    }

    @Test
    void missingTargetsDoNotFallBackToUnrelatedExportsAndDeletionUndoKeepsTargets() {
        RecipeNode target = source("feed", 1);
        RecipeNode unrelated = source("waste", 10);
        FlowGraph graph = graph(target, unrelated);
        graph.setRecommendationTargetIds(Set.of(target.getId()));
        graph.removeNode(target);
        FixedPointEfficiencySolver.computeNodeEfficiencies(graph, MODE);
        assertNull(winner(graph));
        graph.addNode(target);
        FixedPointEfficiencySolver.computeNodeEfficiencies(graph, MODE);
        assertEquals(target.getId(), winner(graph));
    }

    @Test
    void targetsPersistCopyUndoAndInvalidateRecommendationCache() {
        RecipeNode first = source("first", 1);
        RecipeNode second = source("second", 1);
        FlowGraph graph = graph(first, second);
        long defaultKey = LineBottleneckAnalyzer.contentKey(graph, MODE);
        graph.setRecommendationTargetIds(Set.of(first.getId()));
        long firstKey = LineBottleneckAnalyzer.contentKey(graph, MODE);
        assertNotEquals(defaultKey, firstKey);
        FixedPointEfficiencySolver.computeNodeEfficiencies(graph, MODE);
        assertEquals(first.getId(), winner(graph));
        RecommendationTargetsCommand command = new RecommendationTargetsCommand(
                graph.getRecommendationTargetIds(), Set.of(second.getId()));
        command.redo(graph);
        assertNotEquals(firstKey, LineBottleneckAnalyzer.contentKey(graph, MODE));
        FixedPointEfficiencySolver.computeNodeEfficiencies(graph, MODE);
        assertEquals(second.getId(), winner(graph));
        command.undo(graph);
        assertEquals(firstKey, LineBottleneckAnalyzer.contentKey(graph, MODE));
        FixedPointEfficiencySolver.computeNodeEfficiencies(graph, MODE);
        assertEquals(first.getId(), winner(graph));
        Set<String> ids = graph.getRecommendationTargetIds();
        assertThrows(UnsupportedOperationException.class, () -> ids.add("other"));
        assertEquals(ids, graph.copy().getRecommendationTargetIds());
        assertEquals(ids, FlowGraph.deserializeNBT(graph.serializeNBT()).getRecommendationTargetIds());
        var exported = BlueprintCodec.importPackageFromString(BlueprintCodec.exportToString(graph, 0, 0, 1));
        assertNotNull(exported);
        assertEquals(ids, exported.getGraph().getRecommendationTargetIds());
        FlowGraph destination = new FlowGraph();
        destination.copyFrom(graph.copy());
        assertEquals(ids, destination.getRecommendationTargetIds());
        graph.setRecommendationTargetIds(Set.of());
        assertEquals(defaultKey, LineBottleneckAnalyzer.contentKey(graph, MODE));
        destination.clear();
        assertTrue(destination.getRecommendationTargetIds().isEmpty());
    }

    @Test
    void monifactoryPredictionMatrixBoardTargetsAlloyRatherThanSupercomputers() throws Exception {
        FlowGraph graph = loadPredictionBoard();
        FixedPointEfficiencySolver.computeNodeEfficiencies(graph, MODE);
        Map<String, Double> baselineRates = effectiveRates(graph);
        Map<FlowGraph.ConnectionEdge, Double> baselineFlows = graph.getPrimedFlows();
        graph.setRecommendationTargetIds(Set.of(ALLOY));
        FixedPointEfficiencySolver.computeNodeEfficiencies(graph, MODE);
        assertEquals(ALLOY, winner(graph), "Monifactory 0.14-b132 supplied recycling line");
        assertEquals(0.1, LineBottleneckAnalyzer.lastGains().get(ALLOY), 1e-6);
        assertEquals(0, LineBottleneckAnalyzer.lastGains().get(SUPERCOMPUTER), 1e-6);
        assertRatesEqual(baselineRates, graph);
        assertEquals(baselineFlows, graph.getPrimedFlows());
        RecipeNode alloy = graph.findNodeById(ALLOY);
        assertEquals(4.0, alloy.getOutputSlotRate(0, true), 1e-6);
        assertEquals(0.4, graph.findNodeById(SUPERCOMPUTER).getInputSlotRate(0, true), 1e-6);
        assertEquals(3.6, FlowSummaryAggregator.computeSummaryPreservingEfficiencies(graph)
                .netOutputs().get(alloy.getOutputs().get(0)), 1e-6);
        alloy.setMachineCount(alloy.getMachineCount() + 1);
        FixedPointEfficiencySolver.computeNodeEfficiencies(graph, MODE);
        assertEquals(4.4, alloy.getOutputSlotRate(0, true), 1e-6);
        assertEquals(4.0, FlowSummaryAggregator.computeSummaryPreservingEfficiencies(graph)
                .netOutputs().get(alloy.getOutputs().get(0)), 1e-6);
        assertEquals(SMELTING, winner(graph), "the next recommendation follows the new upstream limit");
    }

    @Test
    void targetSweepsPreserveOperatingRatesAndCountsInEveryProductionMode() throws Exception {
        for (LineSolveMode mode : LineSolveMode.values()) {
            FlowGraph graph = loadPredictionBoard();
            FixedPointEfficiencySolver.computeNodeEfficiencies(graph, mode);
            Map<String, Double> rates = effectiveRates(graph);
            Map<String, Double> counts = new LinkedHashMap<>();
            graph.getNodes().forEach(node -> counts.put(node.getId(), node.getMachineCount()));
            graph.setRecommendationTargetIds(Set.of(ALLOY, SUPERCOMPUTER));
            FixedPointEfficiencySolver.computeNodeEfficiencies(graph, mode);
            assertRatesEqual(rates, graph);
            graph.getNodes().forEach(node -> assertEquals(counts.get(node.getId()), node.getMachineCount()));
        }
    }

    private static FlowGraph loadPredictionBoard() throws Exception {
        try (var input = MachineRecommendationTargetsTest.class.getResourceAsStream("/boards/prediction_matrix_line.gtboard")) {
            assertNotNull(input);
            var blueprint = BlueprintCodec.importPackageFromString(
                    new String(input.readAllBytes(), StandardCharsets.UTF_8).trim());
            assertNotNull(blueprint);
            return blueprint.getGraph();
        }
    }

    private static Map<String, Double> effectiveRates(FlowGraph graph) {
        Map<String, Double> rates = new LinkedHashMap<>();
        graph.getNodes().forEach(node -> rates.put(node.getId(), node.getEffectiveCyclesPerSecond()));
        return rates;
    }

    private static void assertRatesEqual(Map<String, Double> expected, FlowGraph graph) {
        graph.getNodes().forEach(node ->
                assertEquals(expected.get(node.getId()), node.getEffectiveCyclesPerSecond(), 1e-6, node.getName()));
    }
}
