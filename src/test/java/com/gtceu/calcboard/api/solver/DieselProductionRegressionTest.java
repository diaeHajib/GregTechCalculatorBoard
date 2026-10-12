package com.gtceu.calcboard.api.solver;

import com.gtceu.calcboard.api.model.FlowGraph;
import com.gtceu.calcboard.api.model.IngredientStack;
import com.gtceu.calcboard.api.model.NodeRateCalculator;
import com.gtceu.calcboard.api.model.RecipeNode;
import com.gtceu.calcboard.api.storage.BlueprintCodec;
import com.gtceu.calcboard.api.storage.BlueprintPackage;
import com.gtceu.calcboard.api.type.LineSolveMode;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DieselProductionRegressionTest {
    private static final double EPSILON = 1e-7;

    @Test
    void exactBoardImportsWithExpectedDieselTopologyAndBoundaries() throws Exception {
        BlueprintPackage pkg = importPackage();
        FlowGraph graph = pkg.getGraph();
        assertEquals("Page 3", pkg.getMetadata().getTitle());
        assertEquals(12, graph.getNodes().size());
        assertEquals(17, graph.getConnections().size());
        assertTrue(graph.getFrames().isEmpty());

        RecipeNode mixer = find(graph, "Mixer (Light Fuel)");
        assertEquals("gtceu:diesel", mixer.getOutputs().get(0).getId().toString());
        assertEquals(1, outgoing(graph, mixer, 0));
        RecipeNode generator = graph.getNodes().stream().filter(RecipeNode::isGenerator).findFirst().orElseThrow();
        assertEquals("gtceu:diesel", generator.getInputs().get(0).getId().toString());

        assertEquals(1, unwiredInputs(graph));
        RecipeNode rawCrusher = find(graph, "Ore Crushing (Raw Oilsands)");
        assertEquals("gtceu:raw_oilsands", rawCrusher.getInputs().get(0).getId().toString());
        assertEquals(0, incoming(graph, rawCrusher, 0));
        assertEquals(5, unwiredOutputs(graph));
    }

    @Test
    void fractionalAutoRatioHitsDieselTargetsAndMatchesStoichiometry() throws Exception {
        for (double target : new double[]{18.0, 180.0, 720.0}) {
            FlowGraph graph = scaleFromGenerator(target, false);
            assertBalancedTarget(graph, target);
            if (target >= 180.0) {
                assertAllFull(currentEfficiencies(graph));
            } else {
                assertMixedDemandAndFeedThrottling(graph);
            }
            assertAnalyticalFlows(graph, target);
            assertConservation(graph);
            assertStable(graph);
        }
    }

    @Test
    void integerAutoRatioRoundsGeneratorDemandAndRemainsConservative() throws Exception {
        FlowGraph exact = scaleFromGenerator(180.0, true);
        assertBalancedTarget(exact, 180.0);
        assertEquals(45.0, generator(exact).getMachineCount(), EPSILON);
        assertMixedDemandAndFeedThrottling(exact);

        FlowGraph rounded = scaleFromGenerator(181.0, true);
        assertBalancedTarget(rounded, 181.0);
        assertEquals(45.25, generator(rounded).getMachineCount(), EPSILON);
        assertMixedDemandAndFeedThrottling(rounded);
        assertConservation(rounded);

        FlowGraph wholeGenerator = scaleFromGenerator(184.0, true);
        assertBalancedTarget(wholeGenerator, 184.0);
        assertEquals(46.0, generator(wholeGenerator).getMachineCount(), EPSILON);
        assertMixedDemandAndFeedThrottling(wholeGenerator);
        assertConservation(wholeGenerator);
    }

    @Test
    void bothModesOrderAndRoundTripRemainStable() throws Exception {
        FlowGraph graph = scaleFromGenerator(180.0, false);
        Map<String, Double> demandEff = efficiencies(graph, LineSolveMode.SUPPLY_AND_DEMAND);
        Map<String, Double> demandFlows = edgeFlows(graph);
        assertAllFull(demandEff);

        Map<String, Double> repeated = efficiencies(graph, LineSolveMode.SUPPLY_AND_DEMAND);
        assertMapsEqual(demandEff, repeated, 1e-10);
        assertMapsEqual(demandFlows, edgeFlows(graph), 1e-10);

        FlowGraph reversed = reversed(scaleFromGenerator(180.0, false));
        assertMapsEqual(demandEff, efficiencies(reversed, LineSolveMode.SUPPLY_AND_DEMAND), 1e-8);
        assertMapsEqual(demandFlows, edgeFlows(reversed), 1e-8);

        FlowGraph supply = scaleFromGenerator(180.0, false);
        assertAllFull(efficiencies(supply, LineSolveMode.SUPPLY_ONLY));
        assertEquals(180.0, dieselRate(supply), EPSILON);

        String exported = BlueprintCodec.exportToString(graph, "Page 3 scaled", "", "", 0, 0, 1);
        FlowGraph restored = BlueprintCodec.importPackageFromString(exported).getGraph();
        for (RecipeNode node : graph.getNodes()) {
            RecipeNode copy = restored.findNodeById(node.getId());
            assertNotNull(copy);
            assertEquals(node.getMachineCount(), copy.getMachineCount(), 1e-12, node.getName());
            assertEquals(node.isBaseNode(), copy.isBaseNode(), node.getName());
        }
        assertBalancedTarget(restored, 180.0);
    }

    @Test
    void powerScalingAndBottleneckDiagnosticsAreConsistent() throws Exception {
        FlowGraph base = scaleFromGenerator(180.0, false);
        double powerPerDiesel = productionPower(base) / 180.0;
        assertEquals(3.011051587555556, powerPerDiesel, 1e-9);
        assertEquals(32.0, grossGeneration(base) / 180.0, 1e-9);

        double powerLimitedTarget = Math.floor((2048.0 / powerPerDiesel) * 10_000.0) / 10_000.0;
        FlowGraph limited = scaleFromGenerator(powerLimitedTarget, false);
        assertEquals(powerLimitedTarget, dieselRate(limited), 1e-4);
        assertTrue(productionPower(limited) <= 2048.0);
        assertEquals(2048.0, productionPower(limited), 2e-4);
        assertTrue(grossGeneration(limited) > productionPower(limited));

        String smallWinner = winner(scaleFromGenerator(18.0, false));
        String largeWinner = winner(limited);
        assertNull(smallWinner);
        assertNull(largeWinner);

        FlowGraph constrained = scaleFromGenerator(180.0, false);
        RecipeNode rawCrusher = find(constrained, "Ore Crushing (Raw Oilsands)");
        rawCrusher.setMachineCount(rawCrusher.getMachineCount() / 2.0);
        efficiencies(constrained, LineSolveMode.SUPPLY_AND_DEMAND);
        assertEquals(rawCrusher.getName(), winner(constrained));
    }

    @Test
    void duplicateResourceSlotsUseTheirOwnChances() {
        RecipeNode node = RecipeNode.create("Duplicate Dust Outputs", 196.0, 24.0,
                com.gtceu.calcboard.api.type.GTVoltageTier.LV);
        IngredientStack guaranteed = IngredientStack.item(
                net.minecraft.resources.ResourceLocation.tryParse("gtceu:oilsands_dust"),
                "Oilsands Dust", 1.0, 1.0);
        IngredientStack bonus = IngredientStack.item(
                net.minecraft.resources.ResourceLocation.tryParse("gtceu:oilsands_dust"),
                "Oilsands Dust", 1.0, (float) 0.1111);
        node.addOutput(guaranteed);
        node.addOutput(bonus);

        assertEquals(1.0 / 9.8, node.calculateSingleMachineOutputRate(guaranteed), 1e-12);
        assertEquals(((double) (float) 0.1111) / 9.8,
                node.calculateSingleMachineOutputRate(bonus), 1e-12);
    }

    private static FlowGraph scaleFromGenerator(double targetDiesel, boolean integerCounts) throws Exception {
        FlowGraph graph = importPackage().getGraph();
        RecipeNode generator = generator(graph);
        double dieselPerGenerator = generator.calculateSingleMachineInputRate(generator.getInputs().get(0));
        assertEquals(4.0, dieselPerGenerator, EPSILON);
        generator.setMachineCount(targetDiesel / dieselPerGenerator);
        generator.setBaseNode(true);
        AutoRatioResult result = graph.autoRatioFromAnchor(generator, integerCounts);
        assertFalse(result.hasDivergence(), result.divergentNodeIds().toString());
        assertFalse(result.clampedBySafetyLimit());
        efficiencies(graph, LineSolveMode.SUPPLY_AND_DEMAND);
        return graph;
    }

    private static void assertBalancedTarget(FlowGraph graph, double target) {
        assertEquals(target, dieselRate(graph), 2e-6);
        assertEquals(target, inputRate(generator(graph), 0), 2e-6);
    }

    private static void assertMixedDemandAndFeedThrottling(FlowGraph graph) {
        var snapshot = graph.captureSnapshot();
        var mixer = snapshot.getNodeSnapshot(find(graph, "Mixer (Light Fuel)").getId());
        var electrolyzer = snapshot.getNodeSnapshot(find(graph, "Electrolyzer (Hydrogen Sulfide)").getId());
        assertNotNull(mixer);
        assertNotNull(electrolyzer);
        assertTrue(mixer.isBlocked());
        assertFalse(mixer.isStarved());
        assertTrue(electrolyzer.isStarved());
        assertFalse(electrolyzer.isBlocked());
    }

    private static void assertAnalyticalFlows(FlowGraph graph, double diesel) {
        assertEquals(5.0 * diesel / 6.0,
                edgeFlow(graph, "Chemical Reactor (Sulfuric Light Fuel)", 1, "Mixer (Light Fuel)", 0), EPSILON);
        assertEquals(diesel / 6.0,
                edgeFlow(graph, "Chemical Reactor (Sulfuric Heavy Fuel)", 1, "Mixer (Light Fuel)", 1), EPSILON);
        assertEquals(10.0 * diesel / 9.0,
                edgeFlow(graph, "Centrifuge (Oilsands Dust)", 1, "Distillery (Raw Oil)", 0, 1), EPSILON);
        assertEquals(diesel / 15.0,
                edgeFlow(graph, "Centrifuge (Oilsands Dust)", 1, "Distillery (Heavy Oil)", 0, 0), EPSILON);
        assertEquals(13.0 * diesel / 144.0,
                inputRate(find(graph, "Electrolyzer (Hydrogen Sulfide)"), 0), EPSILON);
        assertEquals(13.0 * diesel / 72.0,
                outputRate(find(graph, "Electrolyzer (Hydrogen Sulfide)"), 1), EPSILON);
        assertEquals(103.0 * diesel / 90000.0,
                inputRate(findNth(graph, "Centrifuge (Oilsands Dust)", 0), 0)
                        + inputRate(findNth(graph, "Centrifuge (Oilsands Dust)", 1), 0), EPSILON);
        double oilsandsBonusChance = (double) (float) 0.1111;
        assertEquals((103.0 * diesel / 90000.0) / (2.0 * (1.0 + oilsandsBonusChance)),
                inputRate(find(graph, "Ore Crushing (Raw Oilsands)"), 0), EPSILON);
    }

    private static void assertConservation(FlowGraph graph) {
        for (RecipeNode producer : graph.getNodes()) {
            for (int port = 0; port < producer.getOutputs().size(); port++) {
                double allocated = 0.0;
                int edges = 0;
                for (FlowGraph.ConnectionEdge edge : graph.getConnections()) {
                    if (edge.fromNodeId().equals(producer.getId()) && edge.outputIndex() == port) {
                        allocated += FlowEdgeAllocator.getEdgeAllocatedFlow(graph, edge, null);
                        edges++;
                    }
                }
                if (edges > 0) {
                    assertTrue(allocated <= outputRate(producer, port) + 1e-7,
                            producer.getName() + "[" + port + "]");
                }
            }
        }
    }

    private static void assertStable(FlowGraph graph) {
        Map<String, Double> before = currentEfficiencies(graph);
        Map<String, Double> flows = edgeFlows(graph);
        assertMapsEqual(before, efficiencies(graph, LineSolveMode.SUPPLY_AND_DEMAND), 1e-10);
        assertMapsEqual(flows, edgeFlows(graph), 1e-10);
    }

    private static BlueprintPackage importPackage() throws Exception {
        try (InputStream stream = DieselProductionRegressionTest.class
                .getResourceAsStream("/boards/page3-diesel.gtboard")) {
            assertNotNull(stream);
            return BlueprintCodec.importPackageFromString(
                    new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static RecipeNode generator(FlowGraph graph) {
        return graph.getNodes().stream().filter(RecipeNode::isGenerator).findFirst().orElseThrow();
    }

    private static RecipeNode find(FlowGraph graph, String name) {
        return graph.getNodes().stream().filter(node -> node.getName().equals(name))
                .findFirst().orElseThrow();
    }

    private static RecipeNode findNth(FlowGraph graph, String name, int index) {
        return graph.getNodes().stream().filter(node -> node.getName().equals(name))
                .skip(index).findFirst().orElseThrow();
    }

    private static int incoming(FlowGraph graph, RecipeNode node, int port) {
        return (int) graph.getConnections().stream()
                .filter(edge -> edge.toNodeId().equals(node.getId()) && edge.inputIndex() == port).count();
    }

    private static int outgoing(FlowGraph graph, RecipeNode node, int port) {
        return (int) graph.getConnections().stream()
                .filter(edge -> edge.fromNodeId().equals(node.getId()) && edge.outputIndex() == port).count();
    }

    private static int unwiredInputs(FlowGraph graph) {
        int total = 0;
        for (RecipeNode node : graph.getNodes()) {
            for (int i = 0; i < node.getInputs().size(); i++) {
                if (incoming(graph, node, i) == 0) total++;
            }
        }
        return total;
    }

    private static int unwiredOutputs(FlowGraph graph) {
        int total = 0;
        for (RecipeNode node : graph.getNodes()) {
            for (int i = 0; i < node.getOutputs().size(); i++) {
                if (outgoing(graph, node, i) == 0) total++;
            }
        }
        return total;
    }

    private static double dieselRate(FlowGraph graph) {
        return outputRate(find(graph, "Mixer (Light Fuel)"), 0);
    }

    private static double inputRate(RecipeNode node, int port) {
        return NodeRateCalculator.getInputSlotRate(node, port, true);
    }

    private static double outputRate(RecipeNode node, int port) {
        return NodeRateCalculator.getOutputSlotRate(node, port, true);
    }

    private static double productionPower(FlowGraph graph) {
        return graph.getNodes().stream().filter(node -> !node.isGenerator())
                .mapToDouble(RecipeNode::getEffectiveTotalEUt).sum();
    }

    private static double grossGeneration(FlowGraph graph) {
        return graph.getNodes().stream().filter(RecipeNode::isGenerator)
                .mapToDouble(RecipeNode::getEffectiveTotalEUt).sum();
    }

    private static String winner(FlowGraph graph) {
        return graph.getNodes().stream().filter(RecipeNode::isBottleneck)
                .map(RecipeNode::getName).findFirst().orElse(null);
    }

    private static double edgeFlow(
            FlowGraph graph,
            String fromName,
            int output,
            String toName,
            int input
    ) {
        return edgeFlow(graph, fromName, output, toName, input, -1);
    }

    private static double edgeFlow(
            FlowGraph graph,
            String fromName,
            int output,
            String toName,
            int input,
            int fromOccurrence
    ) {
        ArrayList<RecipeNode> sources = new ArrayList<>(graph.getNodes().stream()
                .filter(node -> node.getName().equals(fromName)).toList());
        RecipeNode source = fromOccurrence >= 0 ? sources.get(fromOccurrence) : sources.get(0);
        RecipeNode target = find(graph, toName);
        return graph.getConnections().stream()
                .filter(edge -> edge.fromNodeId().equals(source.getId())
                        && edge.outputIndex() == output
                        && edge.toNodeId().equals(target.getId())
                        && edge.inputIndex() == input)
                .mapToDouble(edge -> FlowEdgeAllocator.getEdgeAllocatedFlow(graph, edge, null))
                .sum();
    }

    private static Map<String, Double> efficiencies(FlowGraph graph, LineSolveMode mode) {
        return FixedPointEfficiencySolver.computeNodeEfficiencies(graph, mode);
    }

    private static Map<String, Double> currentEfficiencies(FlowGraph graph) {
        Map<String, Double> result = new LinkedHashMap<>();
        for (RecipeNode node : graph.getNodes()) result.put(node.getId(), node.getEfficiency());
        return result;
    }

    private static Map<String, Double> edgeFlows(FlowGraph graph) {
        Map<String, Double> result = new LinkedHashMap<>();
        for (FlowGraph.ConnectionEdge edge : graph.getConnections()) {
            String key = edge.fromNodeId() + ":" + edge.outputIndex() + "->"
                    + edge.toNodeId() + ":" + edge.inputIndex();
            result.merge(key, FlowEdgeAllocator.getEdgeAllocatedFlow(graph, edge, null), Double::sum);
        }
        return result;
    }

    private static void assertAllFull(Map<String, Double> efficiencies) {
        efficiencies.forEach((id, efficiency) -> assertEquals(1.0, efficiency, 2e-7, id));
    }

    private static void assertMapsEqual(
            Map<String, Double> expected,
            Map<String, Double> actual,
            double tolerance
    ) {
        assertEquals(expected.keySet(), actual.keySet());
        expected.forEach((key, value) -> assertEquals(value, actual.get(key), tolerance, key));
    }

    private static FlowGraph reversed(FlowGraph source) {
        FlowGraph reversed = new FlowGraph();
        ArrayList<RecipeNode> nodes = new ArrayList<>(source.getNodes());
        Collections.reverse(nodes);
        for (RecipeNode node : nodes) reversed.addNode(node.copy(node.getId()));
        ArrayList<FlowGraph.ConnectionEdge> edges = new ArrayList<>(source.getConnections());
        Collections.reverse(edges);
        for (FlowGraph.ConnectionEdge edge : edges) reversed.addConnection(edge);
        return reversed;
    }
}
