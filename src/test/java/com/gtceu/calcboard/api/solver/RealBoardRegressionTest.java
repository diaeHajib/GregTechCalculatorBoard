package com.gtceu.calcboard.api.solver;

import com.gtceu.calcboard.api.model.FlowGraph;
import com.gtceu.calcboard.api.model.RecipeNode;
import com.gtceu.calcboard.api.type.LineSolveMode;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests against pages taken verbatim from a real save (17-node platline page and the
 * 6-node hydrogen loop page).
 *
 * <p>Both boards exist because the bidirectional (SUPPLY_AND_DEMAND) solve has exactly one way to
 * go catastrophically wrong: whenever a consumer's appetite is discounted by its own current
 * throttle, a recirculating loop becomes self-referential and every sweep multiplies the producer's
 * efficiency by another factor below one. The hydrogen loop page reproduces that, and the platline
 * page pins the absolute rates so the demand pass cannot silently drift.
 */
public class RealBoardRegressionTest {

    private static final double EPS = 1e-6;

    private static FlowGraph load(String name) throws Exception {
        try (InputStream raw = RealBoardRegressionTest.class.getResourceAsStream("/boards/" + name + ".nbt")) {
            if (raw == null) throw new IllegalStateException("missing test resource /boards/" + name + ".nbt");
            CompoundTag root = NbtIo.read(new DataInputStream(new GZIPInputStream(raw)));
            return FlowGraph.deserializeNBT(root.getCompound("graph"));
        }
    }

    private static RecipeNode byName(FlowGraph graph, String name) {
        for (RecipeNode node : graph.getNodes()) {
            if (name.equals(node.getName())) return node;
        }
        throw new IllegalStateException("no node named " + name);
    }

    private static Map<String, Double> efficiencies(FlowGraph graph, LineSolveMode mode) {
        Map<String, Double> effs = FixedPointEfficiencySolver.computeNodeEfficiencies(graph, mode);
        Map<String, Double> byName = new LinkedHashMap<>();
        for (RecipeNode node : graph.getNodes()) {
            byName.put(node.getName(), effs.getOrDefault(node.getId(), 1.0));
        }
        return byName;
    }

    /**
     * The platline is a purely feed-forward chain, so the demand pass must reproduce the stoichiometric
     * answer exactly: the line is capped by what the centrifuge and the purified-chalcopyrite reactor
     * actually consume, and each upstream machine throttles to the fraction of its nominal output that
     * its consumer really takes.
     */
    @Test
    public void platlineDemandPassMatchesStoichiometry() throws Exception {
        FlowGraph graph = load("platline");
        Map<String, Double> supplyOnly = efficiencies(graph, LineSolveMode.SUPPLY_ONLY);
        Map<String, Double> twoSided = efficiencies(load("platline"), LineSolveMode.SUPPLY_AND_DEMAND);

        // anchors worked out by hand from the board's own recipe data
        assertEquals(0.00800000, twoSided.get("Large Chemical Reactor (Sulfur Dust)"), EPS);
        assertEquals(0.00240000, twoSided.get("Mixer (Nitric Acid)"), EPS);
        assertEquals(0.01500000, twoSided.get("Chemical Reactor (Purified Chalcopyrite Ore)"), EPS);
        assertEquals(0.00373333, twoSided.get("Large Chemical Reactor (Nitrogen Gas)"), 1e-5);
        assertEquals(0.00112000, twoSided.get("Large Chemical Reactor (Chlorine Gas)"), EPS);

        // machines that were already the line's real bottleneck must not move
        for (String pinned : new String[]{
                "Large Chemical Reactor (Inert Metal Mixture)",
                "Electrolyzer (Liquid Rhodium Sulfate)",
                "Large Chemical Reactor (Ruthenium Tetroxide Dust)",
                "Centrifuge (Platinum Group Sludge)"}) {
            assertEquals(supplyOnly.get(pinned), twoSided.get(pinned), EPS, pinned + " must be unaffected");
        }

        // and nothing may collapse
        for (Map.Entry<String, Double> e : twoSided.entrySet()) {
            assertTrue(e.getValue() > 1e-5, e.getKey() + " collapsed to " + e.getValue());
            assertTrue(e.getValue() <= supplyOnly.get(e.getKey()) + EPS,
                    e.getKey() + " ran faster than its supply permits");
        }
    }

    /**
     * A recirculating loop must solve to its greatest consistent operating point, not ratchet down to
     * zero. Without the recirculation guard these three values come out 2048x smaller (0.00024414 /
     * 0.00016728 / 0.00004521).
     */
    @Test
    public void recirculatingLoopMustNotRatchet() throws Exception {
        FlowGraph graph = load("hydrogen_loop");
        Map<String, Double> effs = efficiencies(graph, LineSolveMode.SUPPLY_AND_DEMAND);

        assertEquals(0.50000000, effs.get("Large Chemical Reactor (Carbon Dust)"), 1e-5);
        assertEquals(0.34259259, effs.get("Large Chemical Reactor (Water)"), 1e-5);
        assertEquals(0.09259259, effs.get("Electrolyzer (Carbon Dioxide)"), 1e-5);

        for (Map.Entry<String, Double> e : effs.entrySet()) {
            assertTrue(e.getValue() > 1e-4, e.getKey() + " ratcheted down to " + e.getValue());
        }
    }

    /**
     * A port that is short only because its own machine is throttled must never be shown as a
     * shortage. Regression guard for the bug where every throttled input measured against its
     * <em>nominal</em> draw was flagged red - including ports fed straight from an INFINITE junction,
     * which by definition can never run short.
     */
    @Test
    public void demandThrottledPortsAreNotReportedAsShortages() throws Exception {
        int throttled = 0;
        for (String board : new String[]{"platline", "hydrogen_loop"}) {
            FlowGraph graph = load(board);
            FixedPointEfficiencySolver.computeNodeEfficiencies(graph, LineSolveMode.SUPPLY_AND_DEMAND);
            for (RecipeNode node : graph.getNodes()) {
                if (node.isReroute()) continue;
                for (int i = 0; i < node.getInputs().size(); i++) {
                    var stats = graph.getInputPortStats(node, i);
                    if (stats == null || !stats.isConnected()) continue;
                    if (stats.isDemandThrottled()) {
                        throttled++;
                        assertFalse(stats.isInputDeficit(),
                                node.getName() + " in[" + i + "] is throttled by demand but still reported as a shortage");
                    }
                }
            }
        }
        assertTrue(throttled > 0, "no demand-throttled port exercised: this guard has gone vacuous");
    }

    // NOTE: the former bottleneckNodesAreSaturatedAndUnconstrained guard lived here. It encoded the
    // superseded saturation rule ("a flagged node is at its own ceiling"), which the measured-gain
    // sweep replaced, and it passed vacuously on these fixtures because nothing was ever flagged.
    // The invariant now lives in SharedProducerAppetiteTest.flaggedNodeIsTheLargestMeasuredGain.

    /**
     * The solve runs on every board refresh, so the bottleneck flag has to be republished from
     * scratch each time rather than accumulate stale hits.
     */
    @Test
    public void bottleneckFlagIsClearedOnResolve() throws Exception {
        FlowGraph graph = load("platline");
        FixedPointEfficiencySolver.computeNodeEfficiencies(graph, LineSolveMode.SUPPLY_AND_DEMAND);
        Map<String, Boolean> expected = new LinkedHashMap<>();
        for (RecipeNode node : graph.getNodes()) {
            expected.put(node.getId(), node.isBottleneck());
            node.setBottleneck(true);
        }
        FixedPointEfficiencySolver.computeNodeEfficiencies(graph, LineSolveMode.SUPPLY_AND_DEMAND);
        for (RecipeNode node : graph.getNodes()) {
            assertEquals(expected.get(node.getId()), node.isBottleneck(),
                    node.getName() + " must restore the measured winner, not a saturation heuristic");
        }
    }

    /**
     * The solve runs on every board refresh, so it has to be a fixed point of itself: feeding its own
     * output back in must not move any value. This is what keeps the in-game numbers from drifting
     * downwards frame after frame.
     */
    @Test
    public void repeatedSolvesAreStable() throws Exception {
        for (String board : new String[]{"platline", "hydrogen_loop", "chlorine", "platline_shared_feed"}) {
            FlowGraph graph = load(board);
            Map<String, Double> first = FixedPointEfficiencySolver.computeNodeEfficiencies(graph, LineSolveMode.SUPPLY_AND_DEMAND);
            for (int pass = 0; pass < 4; pass++) {
                Map<String, Double> again = FixedPointEfficiencySolver.computeNodeEfficiencies(graph, LineSolveMode.SUPPLY_AND_DEMAND);
                for (Map.Entry<String, Double> e : first.entrySet()) {
                    assertEquals(e.getValue(), again.get(e.getKey()), 1e-9,
                            board + ": " + e.getKey() + " drifted on re-solve");
                }
            }
        }
    }

    @Test
    void reversedInsertionOrderAndStaleEfficienciesDoNotCollapseBoards() throws Exception {
        for (String board : new String[]{"platline", "hydrogen_loop", "chlorine", "platline_shared_feed"}) {
            FlowGraph graph = load(board);
            FlowGraph reversed = new FlowGraph();
            for (int i = graph.getNodes().size() - 1; i >= 0; i--) {
                RecipeNode node = graph.getNodes().get(i).copy(graph.getNodes().get(i).getId());
                node.setEfficiency(0);
                reversed.addNode(node);
            }
            for (int i = graph.getConnections().size() - 1; i >= 0; i--) {
                reversed.addConnection(graph.getConnections().get(i));
            }
            Map<String, Double> expected = FixedPointEfficiencySolver.computeNodeEfficiencies(graph, LineSolveMode.SUPPLY_AND_DEMAND);
            Map<String, Double> actual = FixedPointEfficiencySolver.computeNodeEfficiencies(reversed, LineSolveMode.SUPPLY_AND_DEMAND);
            for (Map.Entry<String, Double> entry : expected.entrySet()) {
                assertEquals(entry.getValue(), actual.get(entry.getKey()), 1e-4, board + ": " + entry.getKey());
            }
        }
    }
}
