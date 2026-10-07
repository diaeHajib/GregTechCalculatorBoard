package com.gtceu.calcboard.api.solver;

import com.gtceu.calcboard.api.model.FlowGraph;
import com.gtceu.calcboard.api.model.IngredientStack;
import com.gtceu.calcboard.api.model.RecipeNode;
import com.gtceu.calcboard.api.storage.BlueprintCodec;
import com.gtceu.calcboard.api.storage.BoardSettings;
import com.gtceu.calcboard.api.storage.BoardPage;
import com.gtceu.calcboard.api.model.CrossPageExportTarget;
import com.gtceu.calcboard.api.type.FlowSplitMode;
import com.gtceu.calcboard.api.type.GTVoltageTier;
import com.gtceu.calcboard.api.type.LineSolveMode;
import com.gtceu.calcboard.api.type.LineSolveModeHolder;
import com.gtceu.calcboard.api.type.SupplyMode;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PrimedLineSolverTest {

    private static final LineSolveMode MODE = LineSolveMode.PRIMED;
    private static final String CENTRIFUGE = "6c31e409-92f1-4bf6-998e-5911ce4b2455";
    private static final String MIXER = "2d53b513-401c-4d8f-84f0-0a1e42702600";
    private static final String ALUMINIUM = "aa5e68ad-d530-4106-8dd1-e20ff0669464";

    private static IngredientStack material(String name, double amount) {
        return IngredientStack.item(ResourceLocation.tryParse("test:" + name), name, amount);
    }

    private static RecipeNode machine(String name) {
        return RecipeNode.create(name, 20.0, 20.0, GTVoltageTier.MV);
    }

    private static RecipeNode source(double rate) {
        RecipeNode node = machine("Source");
        node.addOutput(material("feed", rate));
        return node;
    }

    private static RecipeNode consumer(double rate) {
        RecipeNode node = machine("Consumer");
        node.addInput(material("feed", rate));
        node.addOutput(material("product", rate));
        return node;
    }

    private static FlowGraph graph(RecipeNode... nodes) {
        FlowGraph graph = new FlowGraph();
        for (RecipeNode node : nodes) graph.addNode(node);
        return graph;
    }

    private static FlowGraph load(String fixture) throws Exception {
        try (var input = PrimedLineSolverTest.class.getResourceAsStream("/boards/" + fixture + ".gtboard")) {
            assertNotNull(input);
            var blueprint = BlueprintCodec.importPackageFromString(
                    new String(input.readAllBytes(), StandardCharsets.UTF_8).trim());
            assertNotNull(blueprint);
            return blueprint.getGraph();
        }
    }

    private static void solve(FlowGraph graph) {
        FixedPointEfficiencySolver.computeNodeEfficiencies(graph, MODE);
    }

    private static double flow(FlowGraph graph, RecipeNode producer, RecipeNode consumer) {
        return graph.getConnections().stream()
                .filter(edge -> edge.fromNodeId().equals(producer.getId()) && edge.toNodeId().equals(consumer.getId()))
                .mapToDouble(edge -> FlowEdgeAllocator.getEdgeAllocatedFlow(graph, edge, null)).sum();
    }

    @Test
    void latestBoardHasANonzeroConservedPrimedOperatingPoint() throws Exception {
        FlowGraph graph = load("primed_bayer_loop");
        solve(graph);
        assertEquals(1.0, graph.findNodeById(MIXER).getEfficiency(), 1e-6);
        assertEquals(1.0, graph.findNodeById(CENTRIFUGE).getEfficiency(), 1e-6);
        assertEquals(0.96, graph.findNodeById(ALUMINIUM).getOutputSlotRate(0, true), 1e-6);
        assertConservation(graph);
        RecipeNode centrifuge = graph.findNodeById(CENTRIFUGE);
        var water = centrifuge.getOutputs().get(6);
        var summary = FlowSummaryAggregator.computeSummaryPreservingEfficiencies(graph);
        assertEquals(44.0, summary.rawInputs().get(water), 1e-6);
        assertEquals(60.0, centrifuge.isOutputPortVoided(6)
                ? summary.voidedOutputs().get(water) : summary.netOutputs().get(water), 1e-6);
    }

    @Test
    void originalCentrifugeIsNotCappedToTheHeatersAppetite() throws Exception {
        FlowGraph graph = load("zero_loop");
        solve(graph);
        RecipeNode centrifuge = graph.findNodeById(CENTRIFUGE);
        RecipeNode heater = graph.findNodeById("3b7f5a70-4477-4358-a5f2-470fa99e7c9c");
        assertEquals(1.0, centrifuge.getEfficiency(), 1e-6);
        assertEquals(heater.getInputSlotRate(0, true), flow(graph, centrifuge, heater), 1e-6);
        assertTrue(centrifuge.getOutputSlotRate(6, true) > flow(graph, centrifuge, heater));
        assertEquals(flow(graph, centrifuge, heater), graph.getOutputPortStats(centrifuge, 6).connectedRate(), 1e-6);
        assertConservation(graph);
    }

    @Test
    void aConnectedSlowConsumerWithdrawsOnlyItsNeed() {
        RecipeNode producer = source(100);
        RecipeNode consumer = consumer(4);
        FlowGraph graph = graph(producer, consumer);
        graph.addConnection(producer.getId(), 0, consumer.getId(), 0);
        solve(graph);
        assertEquals(1.0, producer.getEfficiency(), 1e-9);
        assertEquals(4.0, flow(graph, producer, consumer), 1e-9);
        graph.invalidatePortStatsCache();
        assertEquals(4.0, graph.getInputPortStats(consumer, 0).connectedRate(), 1e-9);
        assertEquals(4.0, graph.getOutputPortStats(producer, 0).connectedRate(), 1e-9);
        var summary = FlowSummaryAggregator.computeSummaryPreservingEfficiencies(graph);
        assertEquals(96.0, summary.netOutputs().get(material("feed", 1)), 1e-9);
    }

    @Test
    void anUnwiredImportIsNotCancelledByAnUnconnectedExportOfTheSameResource() {
        RecipeNode producer = source(100);
        RecipeNode consumer = consumer(4);
        FlowGraph graph = graph(producer, consumer);
        solve(graph);
        var summary = FlowSummaryAggregator.computeSummaryPreservingEfficiencies(graph);
        assertEquals(4.0, summary.rawInputs().get(material("feed", 1)), 1e-9);
        assertEquals(100.0, summary.netOutputs().get(material("feed", 1)), 1e-9);
    }

    @Test
    void aWireLimitStarvesTheConsumerWithoutBlockingTheProducer() {
        RecipeNode producer = source(100);
        RecipeNode consumer = consumer(10);
        FlowGraph graph = graph(producer, consumer);
        graph.addConnection(new FlowGraph.ConnectionEdge(producer.getId(), 0, consumer.getId(), 0, 3, 0, 1));
        solve(graph);
        assertEquals(1.0, producer.getEfficiency(), 1e-9);
        assertEquals(0.3, consumer.getEfficiency(), 1e-9);
        assertEquals(3.0, flow(graph, producer, consumer), 1e-9);
        assertFalse(graph.getInputPortStats(consumer, 0).isDemandThrottled());
    }

    @Test
    void genuinelyLossyUnfedLoopsCannotUsePrimingAsAPermanentSupply() {
        RecipeNode first = machine("First");
        first.addInput(material("a", 1));
        first.addOutput(material("b", 0.9));
        RecipeNode second = machine("Second");
        second.addInput(material("b", 1));
        second.addOutput(material("a", 1));
        FlowGraph graph = graph(first, second);
        graph.addConnection(first.getId(), 0, second.getId(), 0);
        graph.addConnection(second.getId(), 0, first.getId(), 0);
        solve(graph);
        assertEquals(0.0, first.getEfficiency(), 1e-9);
        assertEquals(0.0, second.getEfficiency(), 1e-9);
        assertConservation(graph);
    }

    @Test
    void equalSplitsRedistributeAfterASmallBranchSaturates() {
        RecipeNode producer = source(10);
        RecipeNode junction = RecipeNode.createReroute(0, 0);
        junction.bindRerouteIngredient(material("feed", 1));
        junction.setJunctionSplitMode(FlowSplitMode.EQUAL);
        RecipeNode small = consumer(1);
        RecipeNode medium = consumer(100);
        RecipeNode large = consumer(1000);
        FlowGraph graph = graph(producer, junction, small, medium, large);
        graph.addConnection(producer.getId(), 0, junction.getId(), 0);
        for (RecipeNode consumer : new RecipeNode[]{small, medium, large}) {
            graph.addConnection(junction.getId(), 0, consumer.getId(), 0);
        }
        solve(graph);
        assertEquals(1.0, flow(graph, junction, small), 1e-7);
        assertEquals(4.5, flow(graph, junction, medium), 1e-7);
        assertEquals(4.5, flow(graph, junction, large), 1e-7);
        assertConservation(graph);
    }

    @Test
    void priorityBranchesAreServedBeforeLowerPriorities() {
        RecipeNode producer = source(10);
        RecipeNode high = consumer(8);
        RecipeNode low = consumer(8);
        FlowGraph graph = graph(producer, high, low);
        graph.addConnection(new FlowGraph.ConnectionEdge(producer.getId(), 0, high.getId(), 0, 0, 10, 1));
        graph.addConnection(new FlowGraph.ConnectionEdge(producer.getId(), 0, low.getId(), 0, 0, 0, 1));
        solve(graph);
        assertEquals(8.0, flow(graph, producer, high), 1e-7);
        assertEquals(2.0, flow(graph, producer, low), 1e-7);
    }

    @Test
    void weightedJunctionsAllocateTheirConfiguredShares() {
        RecipeNode producer = source(10);
        RecipeNode junction = RecipeNode.createReroute(0, 0);
        junction.bindRerouteIngredient(material("feed", 1));
        junction.setJunctionSplitMode(FlowSplitMode.WEIGHTED);
        RecipeNode first = consumer(20);
        RecipeNode second = consumer(20);
        FlowGraph graph = graph(producer, junction, first, second);
        graph.addConnection(producer.getId(), 0, junction.getId(), 0);
        graph.addConnection(new FlowGraph.ConnectionEdge(junction.getId(), 0, first.getId(), 0, 0, 0, 1));
        graph.addConnection(new FlowGraph.ConnectionEdge(junction.getId(), 0, second.getId(), 0, 0, 0, 3));
        solve(graph);
        assertEquals(2.5, flow(graph, junction, first), 1e-7);
        assertEquals(7.5, flow(graph, junction, second), 1e-7);
    }

    @Test
    void zeroWeightBranchesAreServedOnlyAfterPositiveWeightBranchesSaturate() {
        RecipeNode producer = source(10);
        RecipeNode junction = RecipeNode.createReroute(0, 0);
        junction.bindRerouteIngredient(material("feed", 1));
        junction.setJunctionSplitMode(FlowSplitMode.WEIGHTED);
        RecipeNode primary = consumer(1);
        RecipeNode backup = consumer(20);
        FlowGraph graph = graph(producer, junction, primary, backup);
        graph.addConnection(producer.getId(), 0, junction.getId(), 0);
        graph.addConnection(new FlowGraph.ConnectionEdge(junction.getId(), 0, primary.getId(), 0, 0, 0, 1));
        graph.addConnection(new FlowGraph.ConnectionEdge(junction.getId(), 0, backup.getId(), 0, 0, 0, 0));
        solve(graph);
        assertEquals(1.0, flow(graph, junction, primary), 1e-7);
        assertEquals(9.0, flow(graph, junction, backup), 1e-7);
    }

    @Test
    void weightedVoidSinksSplitSurplusWithoutAffectingProduction() {
        RecipeNode producer = source(40);
        RecipeNode junction = RecipeNode.createReroute(0, 0);
        junction.bindRerouteIngredient(material("feed", 1));
        junction.setJunctionSplitMode(FlowSplitMode.WEIGHTED);
        RecipeNode first = RecipeNode.createReroute(0, 0);
        RecipeNode second = RecipeNode.createReroute(0, 0);
        for (RecipeNode sink : new RecipeNode[]{first, second}) {
            sink.bindRerouteIngredient(material("feed", 1));
            sink.setSupplyMode(SupplyMode.VOID_SINK);
        }
        FlowGraph graph = graph(producer, junction, first, second);
        graph.addConnection(producer.getId(), 0, junction.getId(), 0);
        graph.addConnection(new FlowGraph.ConnectionEdge(junction.getId(), 0, first.getId(), 0, 0, 0, 1));
        graph.addConnection(new FlowGraph.ConnectionEdge(junction.getId(), 0, second.getId(), 0, 0, 0, 3));
        solve(graph);
        assertEquals(1.0, producer.getEfficiency(), 1e-7);
        assertEquals(10.0, flow(graph, junction, first), 1e-7);
        assertEquals(30.0, flow(graph, junction, second), 1e-7);
    }

    @Test
    void aFixedDrainCannotConsumeMoreThanArrives() {
        RecipeNode producer = source(3);
        RecipeNode drain = RecipeNode.createReroute(0, 0);
        drain.bindRerouteIngredient(material("feed", 1));
        drain.setSupplyMode(SupplyMode.FIXED_DRAIN);
        drain.setExternalDrainRate(10);
        FlowGraph graph = graph(producer, drain);
        graph.addConnection(producer.getId(), 0, drain.getId(), 0);
        solve(graph);
        assertEquals(3.0, graph.getPrimedDrainRate(drain.getId()), 1e-9);
        var summary = FlowSummaryAggregator.computeSummaryPreservingEfficiencies(graph);
        assertEquals(3.0, summary.totalConsumption().get(material("feed", 1)), 1e-9);
        assertTrue(summary.rawInputs().isEmpty());
        assertTrue(summary.netOutputs().isEmpty());
    }

    @Test
    void declaredFixedSupplyIsBoundedAndItsUnusedMaterialIsExported() {
        RecipeNode supplied = RecipeNode.createReroute(0, 0);
        supplied.bindRerouteIngredient(material("feed", 1));
        supplied.setSupplyMode(SupplyMode.FIXED_RATE);
        supplied.setExternalSupplyRate(20);
        RecipeNode consumer = consumer(4);
        FlowGraph graph = graph(supplied, consumer);
        graph.addConnection(supplied.getId(), 0, consumer.getId(), 0);
        solve(graph);
        assertEquals(4.0, flow(graph, supplied, consumer), 1e-9);
        var summary = FlowSummaryAggregator.computeSummaryPreservingEfficiencies(graph);
        assertEquals(20.0, summary.rawInputs().get(material("feed", 1)), 1e-9);
        assertEquals(16.0, summary.netOutputs().get(material("feed", 1)), 1e-9);
    }

    @Test
    void infiniteSupplyDoesNotInventAnArbitraryExtraVoidingRate() {
        RecipeNode supplied = RecipeNode.createReroute(0, 0);
        supplied.bindRerouteIngredient(material("feed", 1));
        supplied.setSupplyMode(SupplyMode.INFINITE);
        RecipeNode consumer = consumer(4);
        RecipeNode sink = RecipeNode.createReroute(0, 0);
        sink.bindRerouteIngredient(material("feed", 1));
        sink.setSupplyMode(SupplyMode.VOID_SINK);
        FlowGraph graph = graph(supplied, consumer, sink);
        graph.addConnection(supplied.getId(), 0, consumer.getId(), 0);
        graph.addConnection(supplied.getId(), 0, sink.getId(), 0);
        solve(graph);
        assertEquals(4.0, flow(graph, supplied, consumer), 1e-9);
        assertEquals(0.0, flow(graph, supplied, sink), 1e-9);
        assertTrue(FlowSummaryAggregator.computeSummaryPreservingEfficiencies(graph).voidedOutputs().isEmpty());
    }

    @Test
    void linkedPageExportsBootstrapAndDoNotDuplicateTheirLocalSupply() {
        LineSolveMode original = LineSolveModeHolder.get();
        try {
            LineSolveModeHolder.set(MODE);
            RecipeNode producer = source(20);
            RecipeNode local = consumer(4);
            RecipeNode export = RecipeNode.createReroute(0, 0);
            export.bindRerouteIngredient(material("feed", 1));
            export.addExportTarget(new CrossPageExportTarget("primed_target", 0, 0));
            FlowGraph source = graph(producer, local, export);
            source.addConnection(producer.getId(), 0, local.getId(), 0);
            source.addConnection(producer.getId(), 0, export.getId(), 0);
            BoardPage sourcePage = new BoardPage("primed_source", "Source", source);
            RecipeNode linked = RecipeNode.createReroute(0, 0);
            linked.bindRerouteIngredient(material("feed", 1));
            linked.setSupplyMode(SupplyMode.LINKED_JUNCTION);
            linked.setLinkedSourcePageId(sourcePage.getId());
            linked.setLinkedSourceNodeId(export.getId());
            RecipeNode targetConsumer = consumer(10);
            targetConsumer.setEfficiency(0);
            FlowGraph target = graph(linked, targetConsumer);
            target.addConnection(linked.getId(), 0, targetConsumer.getId(), 0);
            BoardPage targetPage = new BoardPage("primed_target", "Target", target);
            for (int pass = 0; pass < 2; pass++) {
                var result = WorkspaceFlowCoordinator.coordinate(List.of(sourcePage, targetPage));
                assertEquals(10.0, result.getAllocatedRate(linked.getId()), 1e-7);
                assertEquals(10.0, source.getPrimedExportRate(export.getId()), 1e-7);
                assertEquals(4.0, flow(source, producer, local), 1e-7);
                assertEquals(1.0, targetConsumer.getEfficiency(), 1e-7);
                assertEquals(10.0, flow(target, linked, targetConsumer), 1e-7);
                assertConservation(source);
                assertConservation(target);
            }
        } finally {
            WorkspaceFlowCoordinator.coordinate(List.of());
            LineSolveModeHolder.set(original);
        }
    }

    @Test
    void explicitVoidDestinationsConsumeOnlySurplusAndAreNotExports() {
        RecipeNode producer = source(100);
        RecipeNode consumer = consumer(4);
        RecipeNode sink = RecipeNode.createReroute(0, 0);
        sink.bindRerouteIngredient(material("feed", 1));
        sink.setSupplyMode(SupplyMode.VOID_SINK);
        FlowGraph graph = graph(producer, consumer, sink);
        graph.addConnection(producer.getId(), 0, consumer.getId(), 0);
        graph.addConnection(producer.getId(), 0, sink.getId(), 0);
        producer.setOutputPortVoided(0, true);
        solve(graph);
        assertEquals(4.0, flow(graph, producer, consumer), 1e-7);
        assertEquals(96.0, flow(graph, producer, sink), 1e-7);
        var summary = FlowSummaryAggregator.computeSummaryPreservingEfficiencies(graph);
        assertEquals(96.0, summary.voidedOutputs().get(material("feed", 1)), 1e-7);
        assertFalse(summary.netOutputs().containsKey(material("feed", 1)));
    }

    @Test
    void refreshOrderingAndModeChangesDoNotLeaveStaleFlows() throws Exception {
        FlowGraph graph = load("primed_bayer_loop");
        solve(graph);
        Map<FlowGraph.ConnectionEdge, Double> expected = Map.copyOf(graph.getPrimedFlows());
        FlowGraph reversed = new FlowGraph();
        for (int i = graph.getNodes().size() - 1; i >= 0; i--) {
            RecipeNode node = graph.getNodes().get(i);
            RecipeNode copy = node.copy(node.getId());
            copy.setEfficiency(0.0);
            reversed.addNode(copy);
        }
        for (int i = graph.getConnections().size() - 1; i >= 0; i--) reversed.addConnection(graph.getConnections().get(i));
        solve(reversed);
        for (var entry : expected.entrySet()) assertEquals(entry.getValue(), reversed.getPrimedFlows().get(entry.getKey()), 1e-6);
        FixedPointEfficiencySolver.computeNodeEfficiencies(reversed, LineSolveMode.SUPPLY_AND_DEMAND);
        assertTrue(reversed.getPrimedFlows().isEmpty());
        solve(reversed);
        reversed.invalidatePortStatsCache();
        assertConservation(reversed);
        var subset = FlowSummaryAggregator.computeSubsetSummary(reversed, Set.of(CENTRIFUGE));
        assertFalse(subset.rawInputs().isEmpty());
        assertFalse(subset.netOutputs().isEmpty());
    }

    @Test
    void theNewModeRoundTripsThroughSettingsAndCyclesWithoutChangingLegacyDefaults() {
        LineSolveMode original = LineSolveModeHolder.get();
        try {
            BoardSettings settings = new BoardSettings();
            assertEquals(LineSolveMode.SUPPLY_ONLY, settings.getLineSolveMode());
            settings.setLineSolveMode(MODE);
            BoardSettings restored = new BoardSettings();
            var tag = new net.minecraft.nbt.CompoundTag();
            settings.serializeNBT(tag);
            restored.deserializeNBT(tag);
            assertEquals(MODE, restored.getLineSolveMode());
            assertEquals(MODE, LineSolveMode.SUPPLY_AND_DEMAND.next());
            assertEquals(LineSolveMode.SUPPLY_ONLY, MODE.next());
        } finally {
            LineSolveModeHolder.set(original);
        }
    }

    private static void assertConservation(FlowGraph graph) {
        var index = FlowEdgeAllocator.buildEdgeIndex(graph);
        for (RecipeNode node : graph.getNodes()) {
            assertTrue(Double.isFinite(node.getEfficiency()));
            if (node.isReroute()) continue;
            for (int port = 0; port < node.getInputs().size(); port++) {
                var incoming = index.getInPortEdges(node.getId(), port);
                if (incoming.isEmpty()) continue;
                double supplied = incoming.stream().mapToDouble(edge -> graph.getPrimedFlows().get(edge)).sum();
                assertEquals(node.getInputSlotRate(port, true), supplied, 1e-6, node.getName());
                assertFalse(graph.getInputPortStats(node, port).isUnfedDampedLoop());
            }
            for (int port = 0; port < node.getOutputs().size(); port++) {
                double delivered = index.getOutPortEdges(node.getId(), port).stream()
                        .mapToDouble(edge -> graph.getPrimedFlows().get(edge)).sum();
                assertTrue(delivered <= node.getOutputSlotRate(port, true) + 1e-6, node.getName());
            }
        }
        graph.getPrimedFlows().forEach((edge, rate) -> {
            assertTrue(Double.isFinite(rate) && rate >= 0.0);
            if (edge.hasFixedLimit()) assertTrue(rate <= edge.fixedFlowLimit() + 1e-6);
        });
    }
}
