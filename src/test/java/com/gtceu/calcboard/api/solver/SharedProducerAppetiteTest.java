package com.gtceu.calcboard.api.solver;

import com.gtceu.calcboard.api.model.FlowGraph;
import com.gtceu.calcboard.api.model.RecipeNode;
import com.gtceu.calcboard.api.type.LineSolveMode;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard for the shared-producer appetite bug.
 *
 * <p>A single output port feeding two consumers used to have its acceptance ceiling measured from the
 * consumers' <em>current</em> appetite. When that appetite is itself caused by this producer's output,
 * the equation degenerates to {@code L <= L}: every efficiency satisfies it, and the number reported is
 * decided by the iteration path. On the board frozen below - a 30 B/min reactor feeding a consumer that
 * can take 7.2 and another that can take 9.6 - the solver reported 3.858 B/min at efficiency 0.12861
 * instead of 16.8 B/min at 0.56.
 *
 * <p>The fixture is a verbatim copy of a real save, so these are real recipe numbers rather than
 * invented ones. It is frozen in test resources and therefore does not follow later edits to the live
 * board.
 *
 * <p>The allocation must use the same anchored capacity as the producer's ceiling, independently of
 * the effective demand used by auto-ratio.
 */
public class SharedProducerAppetiteTest {

    private static final LineSolveMode MODE = LineSolveMode.SUPPLY_AND_DEMAND;
    private static final String LCR = "Large Chemical Reactor (Nitrogen Gas)";
    private static final String CR = "Chemical Reactor (Purified Chalcopyrite Ore)";
    private static final String MIXER = "Mixer (Nitric Acid)";

    private static FlowGraph loadFixture() throws Exception {
        try (InputStream raw = SharedProducerAppetiteTest.class
                .getResourceAsStream("/boards/platline_shared_feed.nbt")) {
            assertNotNull(raw, "missing test resource /boards/platline_shared_feed.nbt");
            CompoundTag root = NbtIo.read(new DataInputStream(new GZIPInputStream(raw)));
            return FlowGraph.deserializeNBT(root.getCompound("graph"));
        }
    }

    private static List<RecipeNode> named(FlowGraph graph, String name) {
        List<RecipeNode> out = new ArrayList<>();
        for (RecipeNode n : graph.getNodes()) {
            if (name.equals(n.getName())) out.add(n);
        }
        return out;
    }

    private static FlowGraph.ConnectionEdge edge(FlowGraph g, String from, String to, int inIdx) {
        for (FlowGraph.ConnectionEdge e : g.getConnections()) {
            if (e.fromNodeId().equals(from) && e.toNodeId().equals(to) && e.inputIndex() == inIdx) return e;
        }
        return null;
    }

    /**
     * One reactor shared by both consumers must be able to serve their combined appetite: the ceiling is
     * anchored to capacity, so it is the sum of what the consumers can take, not a function of whatever
     * share the solver happens to be handing out.
     */
    @Test
    public void sharedProducerCeilingIsAnchoredToConsumerCapacity() throws Exception {
        FlowGraph graph = loadFixture();
        RecipeNode cr = named(graph, CR).get(0);
        RecipeNode mixer = named(graph, MIXER).get(0);
        List<RecipeNode> lcrs = named(graph, LCR);

        RecipeNode shared = null;
        for (RecipeNode lcr : lcrs) {
            if (edge(graph, lcr.getId(), cr.getId(), 1) != null) shared = lcr;
        }
        assertNotNull(shared, "fixture no longer wires a nitric-acid reactor into the chemical reactor");

        // Give the same reactor the mixer as a second consumer - the reported failure case.
        if (edge(graph, shared.getId(), mixer.getId(), 0) == null) {
            graph.addConnection(shared.getId(), 0, mixer.getId(), 0);
        }
        FixedPointEfficiencySolver.computeNodeEfficiencies(graph, MODE);

        double efficiency = shared.getEfficiency();
        double producedPerMinute = shared.getOutputSlotRate(0, true) * 60.0 / 1000.0;

        assertEquals(0.56, efficiency, 1e-3,
                "shared producer ceiling should be (7.2 + 9.6) / 30; got " + efficiency);
        assertEquals(16.8, producedPerMinute, 0.05, "shared producer throughput");
        assertTrue(efficiency > 0.5, "shared producer collapsed below its anchored ceiling");

        double crShare = receivedPerMinute(graph, cr);
        double mixerShare = receivedPerMinute(graph, mixer);
        assertEquals(16.8, crShare + mixerShare, 0.05, "shares must still add up to the anchored total");
        assertEquals(7.2, crShare, 0.01, "chemical reactor receives its absolute capacity");
        assertEquals(9.6, mixerShare, 0.01, "mixer receives its absolute capacity");
    }

    /** Nitric acid received through the consumer's nitric-acid port, in B/min. */
    private static double receivedPerMinute(FlowGraph graph, RecipeNode consumer) {
        for (int i = 0; i < consumer.getInputs().size(); i++) {
            if (!"Nitric Acid".equals(consumer.getInputs().get(i).getDisplayName())) continue;
            return graph.getInputPortStats(consumer, i).connectedRate() * 60.0 / 1000.0;
        }
        throw new IllegalStateException(consumer.getName() + " has no nitric-acid port");
    }

    /**
     * The old failure shape: one producer, two consumers, everything scaled down by a common factor and
     * nothing at zero. Guards against a recurrence that would be easy to mistake for a plausible answer.
     */
    @Test
    public void sharedProducerDoesNotScaleTheWholeLineDown() throws Exception {
        FlowGraph graph = loadFixture();
        RecipeNode cr = named(graph, CR).get(0);
        RecipeNode mixer = named(graph, MIXER).get(0);
        RecipeNode shared = named(graph, LCR).get(0);
        if (edge(graph, shared.getId(), mixer.getId(), 0) == null) {
            graph.addConnection(shared.getId(), 0, mixer.getId(), 0);
        }
        FixedPointEfficiencySolver.computeNodeEfficiencies(graph, MODE);

        // 0.12861 was the degenerate value this board produced.
        assertTrue(shared.getEfficiency() > 0.3,
                "shared producer regressed to a path-dependent value: " + shared.getEfficiency());
        for (RecipeNode n : graph.getNodes()) {
            assertTrue(n.getEfficiency() > 1e-6, n.getName() + " collapsed to zero");
        }
    }

    /**
     * The highlight is whatever the sweep measured, so the flagged node must be the one with the largest
     * measured gain - not, as the superseded saturation rule held, merely a machine running at 100%.
     */
    @Test
    public void flaggedNodeIsTheLargestMeasuredGain() throws Exception {
        FlowGraph graph = loadFixture();
        FixedPointEfficiencySolver.computeNodeEfficiencies(graph, MODE);
        Map<String, Double> gains = LineBottleneckAnalyzer.lastGains();
        assertTrue(!gains.isEmpty(), "sweep recorded no gains");

        double best = 0.0;
        for (double gain : gains.values()) {
            if (Double.isFinite(gain)) best = Math.max(best, gain);
        }
        boolean flaggedAny = false;
        for (RecipeNode n : graph.getNodes()) {
            if (!n.isBottleneck()) continue;
            flaggedAny = true;
            assertEquals(best, gains.getOrDefault(n.getId(), 0.0), 1e-9,
                    n.getName() + " is flagged but is not the largest gain");
        }
        if (best > 1e-6) {
            assertTrue(flaggedAny, "a positive gain of " + best + " was measured but nothing is flagged");
        }
    }
}
