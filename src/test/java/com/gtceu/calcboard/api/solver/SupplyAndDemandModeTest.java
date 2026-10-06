package com.gtceu.calcboard.api.solver;

import com.gtceu.calcboard.api.model.FlowGraph;
import com.gtceu.calcboard.api.model.IngredientStack;
import com.gtceu.calcboard.api.model.RecipeNode;
import com.gtceu.calcboard.api.model.role.NodeCalculationSnapshot;
import com.gtceu.calcboard.api.type.GTVoltageTier;
import com.gtceu.calcboard.api.type.LineSolveMode;
import com.gtceu.calcboard.api.type.SupplyMode;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers SUPPLY_AND_DEMAND mode: the backward (blocking) half of the fixed point.
 *
 * <p>Reference line used throughout: a machine that produces a resource with nothing to hold it back
 * if full speed, feeding a consumer, feeding a drain that only removes half the flow. In a real line
 * the drain's buffer fills, the consumer stalls, and the stall travels back up to the producer. The
 * historical SUPPLY_ONLY solve reports all three at 100%.
 */
public class SupplyAndDemandModeTest {

    /** 20 ticks per cycle == exactly 1 cycle/second, so rates in /s equal amounts per recipe. */
    private static final double CYCLE_TICKS = 20.0;

    private static final String POWDER_ID = "gtceu:raw_platinum_powder";
    private static final String POWDER_NAME = "Raw Platinum Powder";
    private static final String DUST_ID = "gtceu:platinum_dust";
    private static final String DUST_NAME = "Platinum Dust";

    private static RecipeNode machine(String name) {
        RecipeNode node = RecipeNode.create(name, CYCLE_TICKS, 20.0, GTVoltageTier.MV);
        node.setMachineCount(1.0);
        return node;
    }

    private static IngredientStack item(String id, String name, double amount) {
        return IngredientStack.item(ResourceLocation.tryParse(id), name, amount, 1.0);
    }

    /** A relay junction that deletes a fixed rate of whatever reaches it. */
    private static RecipeNode fixedDrain(String id, String name, double rate) {
        RecipeNode junction = RecipeNode.createReroute(0, 0);
        junction.setSupplyMode(SupplyMode.FIXED_DRAIN);
        junction.setExternalDrainRate(rate);
        junction.bindRerouteIngredient(item(id, name, 1.0));
        return junction;
    }

    /** A relay junction that accepts any amount. */
    private static RecipeNode voidSink(String id, String name) {
        RecipeNode junction = RecipeNode.createReroute(0, 0);
        junction.setSupplyMode(SupplyMode.VOID_SINK);
        junction.bindRerouteIngredient(item(id, name, 1.0));
        return junction;
    }

    /**
     * producer (1.0/s out) -&gt; consumer (1.0/s in, 1.0/s out) -&gt; drain (0.5/s). Expect every stage
     * throttled to the 0.5/s the line can actually get rid of.
     */
    private static FlowGraph halfCapacityLine(RecipeNode[] outNodes) {
        FlowGraph graph = new FlowGraph();

        RecipeNode producer = machine("Powder Producer");
        producer.addOutput(item(POWDER_ID, POWDER_NAME, 1.0));

        RecipeNode consumer = machine("Platinum Reactor");
        consumer.addInput(item(POWDER_ID, POWDER_NAME, 1.0));
        consumer.addOutput(item(DUST_ID, DUST_NAME, 1.0));

        RecipeNode drain = fixedDrain(DUST_ID, DUST_NAME, 0.5);

        graph.addNode(producer);
        graph.addNode(consumer);
        graph.addNode(drain);
        graph.addConnection(producer.getId(), 0, consumer.getId(), 0);
        graph.addConnection(consumer.getId(), 0, drain.getId(), 0);

        if (outNodes != null) {
            outNodes[0] = producer;
            outNodes[1] = consumer;
            outNodes[2] = drain;
        }
        return graph;
    }

    @Test
    @DisplayName("Blocking mode throttles a producer to what its drain accepts, and the throttle climbs upstream")
    public void blockedProducerIsThrottledToDownstreamAppetite() {
        RecipeNode[] nodes = new RecipeNode[3];
        FlowGraph graph = halfCapacityLine(nodes);
        RecipeNode producer = nodes[0];
        RecipeNode consumer = nodes[1];

        FixedPointEfficiencySolver.computeNodeEfficiencies(graph, LineSolveMode.SUPPLY_AND_DEMAND);

        Assertions.assertEquals(0.5, consumer.getEfficiency(), 0.01,
                "the consumer may only run at the rate the drain removes");
        Assertions.assertEquals(0.5, producer.getEfficiency(), 0.01,
                "blocking must propagate upstream: the producer's output has nowhere to go either");
    }

    @Test
    @DisplayName("SUPPLY_ONLY mode is unchanged: the same line reports every machine at full speed")
    public void supplyOnlyModeIgnoresBlocking() {
        RecipeNode[] nodes = new RecipeNode[3];
        FlowGraph graph = halfCapacityLine(nodes);

        FixedPointEfficiencySolver.computeNodeEfficiencies(graph, LineSolveMode.SUPPLY_ONLY);

        Assertions.assertEquals(1.0, nodes[0].getEfficiency(), 0.0001);
        Assertions.assertEquals(1.0, nodes[1].getEfficiency(), 0.0001);
    }

    @Test
    @DisplayName("A consumer starved on one input throttles its other suppliers - the case a forward-only solve cannot see")
    public void starvedConsumerThrottlesItsSupplier() {
        FlowGraph graph = new FlowGraph();

        // Supplier: plenty of powder, nothing limiting it from the output side.
        RecipeNode supplier = machine("Powder Supplier");
        supplier.addOutput(item(POWDER_ID, POWDER_NAME, 1.0));

        // The consumer needs powder AND acid; acid arrives at only 0.3/s of the 1.0/s it wants.
        RecipeNode consumer = machine("Reactor");
        consumer.addInput(item(POWDER_ID, POWDER_NAME, 1.0));
        consumer.addInput(item("gtceu:hydrochloric_acid", "Hydrochloric Acid", 1.0));

        RecipeNode acidSupply = RecipeNode.createReroute(0, 0);
        acidSupply.setSupplyMode(SupplyMode.FIXED_RATE);
        acidSupply.setExternalSupplyRate(0.3);
        acidSupply.bindRerouteIngredient(item("gtceu:hydrochloric_acid", "Hydrochloric Acid", 1.0));

        graph.addNode(supplier);
        graph.addNode(consumer);
        graph.addNode(acidSupply);
        graph.addConnection(supplier.getId(), 0, consumer.getId(), 0);
        graph.addConnection(acidSupply.getId(), 0, consumer.getId(), 1);

        FixedPointEfficiencySolver.computeNodeEfficiencies(graph, LineSolveMode.SUPPLY_AND_DEMAND);

        Assertions.assertEquals(0.3, consumer.getEfficiency(), 0.01, "acid shortage caps the consumer");
        Assertions.assertEquals(0.3, supplier.getEfficiency(), 0.01,
                "the throttled consumer only absorbs 0.3/s, so its powder supplier must stall");
    }

    @Test
    @DisplayName("An output nothing consumes is treated as vented, and reported as such")
    public void unwiredOutputIsAssumedVentedButReported() {
        FlowGraph graph = new FlowGraph();
        RecipeNode producer = machine("Isolated Producer");
        producer.addOutput(item(POWDER_ID, POWDER_NAME, 1.0));
        graph.addNode(producer);

        DownstreamBlockingSolver.NodeAcceptance acceptance =
                DownstreamBlockingSolver.analyzeNode(graph, producer);

        Assertions.assertFalse(acceptance.isConstraining(),
                "an unwired output must not stall a board that simply does not model that byproduct");
        Assertions.assertTrue(acceptance.hasUnwiredOutput(),
                "but it must be reported so the discarded output is visible");
        Assertions.assertNull(acceptance.bindingPort());

        FixedPointEfficiencySolver.computeNodeEfficiencies(graph, LineSolveMode.SUPPLY_AND_DEMAND);
        Assertions.assertEquals(1.0, producer.getEfficiency(), 0.0001);
    }

    @Test
    @DisplayName("A void sink removes backpressure instead of creating it")
    public void voidSinkRemovesBackpressure() {
        FlowGraph graph = new FlowGraph();
        RecipeNode producer = machine("Producer");
        producer.addOutput(item(DUST_ID, DUST_NAME, 1.0));
        RecipeNode sink = voidSink(DUST_ID, DUST_NAME);
        graph.addNode(producer);
        graph.addNode(sink);
        graph.addConnection(producer.getId(), 0, sink.getId(), 0);

        DownstreamBlockingSolver.NodeAcceptance acceptance =
                DownstreamBlockingSolver.analyzeNode(graph, producer);
        Assertions.assertTrue(acceptance.hasVoidOutput());
        Assertions.assertFalse(acceptance.isConstraining());

        FixedPointEfficiencySolver.computeNodeEfficiencies(graph, LineSolveMode.SUPPLY_AND_DEMAND);
        Assertions.assertEquals(1.0, producer.getEfficiency(), 0.0001);
    }

    @Test
    @DisplayName("Blocking reaches every link of a long chain, not just the neighbour of the bottleneck")
    public void blockingPropagatesUpLongChain() {
        final int chainLength = 40;
        FlowGraph graph = new FlowGraph();
        RecipeNode previous = null;

        for (int i = 0; i < chainLength; i++) {
            RecipeNode stage = machine("Stage " + i);
            if (i > 0) {
                stage.addInput(item(DUST_ID, DUST_NAME, 1.0));
            }
            stage.addOutput(item(DUST_ID, DUST_NAME, 1.0));
            graph.addNode(stage);
            if (previous != null) {
                graph.addConnection(previous.getId(), 0, stage.getId(), 0);
            }
            previous = stage;
        }

        RecipeNode drain = fixedDrain(DUST_ID, DUST_NAME, 0.5);
        graph.addNode(drain);
        graph.addConnection(previous.getId(), 0, drain.getId(), 0);

        FixedPointEfficiencySolver.computeNodeEfficiencies(graph, LineSolveMode.SUPPLY_AND_DEMAND);

        for (RecipeNode stage : graph.getNodes()) {
            if (stage.isReroute()) {
                continue;
            }
            Assertions.assertEquals(0.5, stage.getEfficiency(), 0.01,
                    "every stage of a blocked chain must converge to the tail's throughput");
        }
    }

    @Test
    @DisplayName("A junction whose own supply covers all demand leaves no room, so the producer feeding it stalls")
    public void junctionWithOwnExternalSupplyLeavesNoRoomUpstream() {
        FlowGraph graph = new FlowGraph();

        RecipeNode producer = machine("Acid Producer");
        producer.addOutput(item("gtceu:hydrochloric_acid", "Hydrochloric Acid", 1.0));

        // The junction already supplies more acid than anything asks for.
        RecipeNode junction = RecipeNode.createReroute(0, 0);
        junction.setSupplyMode(SupplyMode.FIXED_RATE);
        junction.setExternalSupplyRate(5.0);
        junction.bindRerouteIngredient(item("gtceu:hydrochloric_acid", "Hydrochloric Acid", 1.0));

        RecipeNode consumer = machine("Consumer");
        consumer.addInput(item("gtceu:hydrochloric_acid", "Hydrochloric Acid", 1.0));

        graph.addNode(producer);
        graph.addNode(junction);
        graph.addNode(consumer);
        graph.addConnection(producer.getId(), 0, junction.getId(), 0);
        graph.addConnection(junction.getId(), 0, consumer.getId(), 0);

        FixedPointEfficiencySolver.computeNodeEfficiencies(graph, LineSolveMode.SUPPLY_AND_DEMAND);

        Assertions.assertEquals(1.0, consumer.getEfficiency(), 0.01, "the consumer is fully fed");
        Assertions.assertEquals(0.0, producer.getEfficiency(), 0.01,
                "documented sharp edge: the junction absorbs nothing further, so the producer has no "
                        + "outlet and stalls; void the output or stop feeding the junction if unintended");
    }

    @Test
    @DisplayName("Junction nodes carry no production of their own and are never reported as blocked")
    public void junctionsAreNeverReportedAsBlocked() {
        FlowGraph graph = halfCapacityLine(null);
        for (RecipeNode node : graph.getNodes()) {
            if (!node.isReroute()) {
                continue;
            }
            DownstreamBlockingSolver.NodeAcceptance acceptance =
                    DownstreamBlockingSolver.analyzeNode(graph, node);
            Assertions.assertEquals(1.0, acceptance.ratio(), 0.0001);
            Assertions.assertFalse(acceptance.isConstraining());
        }
    }

    @Test
    @DisplayName("Snapshots distinguish a blocked machine from a starved one and name the backing-up resource")
    public void snapshotsDistinguishBlockedFromStarved() {
        RecipeNode[] nodes = new RecipeNode[3];
        FlowGraph graph = halfCapacityLine(nodes);
        RecipeNode consumer = nodes[1];

        FixedPointEfficiencySolver.computeNodeEfficiencies(graph, LineSolveMode.SUPPLY_AND_DEMAND);
        graph.captureSnapshot();
        NodeCalculationSnapshot blocked = graph.getSnapshot().getNodeSnapshot(consumer.getId());

        Assertions.assertNotNull(blocked);
        Assertions.assertTrue(blocked.isBlocked(), "a demand-limited machine must report isBlocked");
        Assertions.assertFalse(blocked.isStarved(), "it is not starved: the problem is downstream");
        Assertions.assertEquals(0.5, blocked.blockingRatio(), 0.01);
        Assertions.assertEquals(DUST_NAME, blocked.blockingResource());
    }

    @Test
    @DisplayName("In SUPPLY_ONLY mode no machine is ever reported as blocked")
    public void supplyOnlyModeReportsNoBlocking() {
        RecipeNode[] nodes = new RecipeNode[3];
        FlowGraph graph = halfCapacityLine(nodes);
        RecipeNode consumer = nodes[1];

        FixedPointEfficiencySolver.computeNodeEfficiencies(graph, LineSolveMode.SUPPLY_ONLY);
        graph.captureSnapshot();
        NodeCalculationSnapshot snapshot = graph.getSnapshot().getNodeSnapshot(consumer.getId());

        Assertions.assertNotNull(snapshot);
        Assertions.assertFalse(snapshot.isBlocked());
        Assertions.assertFalse(snapshot.isStarved());
        Assertions.assertEquals(1.0, snapshot.blockingRatio(), 0.0001);
    }
}
