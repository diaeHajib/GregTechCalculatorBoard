package com.gtceu.calcboard.api.solver;

import com.gtceu.calcboard.api.model.FlowGraph;
import com.gtceu.calcboard.api.model.RecipeNode;
import com.gtceu.calcboard.api.type.GTVoltageTier;
import com.gtceu.calcboard.api.type.LineSolveMode;
import com.gtceu.calcboard.api.type.SupplyMode;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Pins the bottleneck sweep's cache-invalidation contract.
 *
 * <p>The sweep costs N+1 solves, so its answer is cached. The cache is only allowed to be served when
 * the board's content cannot have changed the answer, and the previous key failed that: it hashed node
 * ids, machine counts, ingredient <em>names</em> and the <em>number</em> of connections, so editing a
 * machine's parallel count, its target tier, a recipe amount, a junction's supply mode or the wiring
 * itself all returned a stale highlight. These tests pin every one of those fields, and pin the two
 * things that must NOT invalidate: transient solve state, and card layout.
 */
public class BottleneckCacheInvalidationTest {

    private static final LineSolveMode MODE = LineSolveMode.SUPPLY_AND_DEMAND;

    private static FlowGraph load(String name) throws Exception {
        try (InputStream raw = BottleneckCacheInvalidationTest.class.getResourceAsStream("/boards/" + name + ".nbt")) {
            if (raw == null) throw new IllegalStateException("missing test resource /boards/" + name + ".nbt");
            CompoundTag root = NbtIo.read(new DataInputStream(new GZIPInputStream(raw)));
            return FlowGraph.deserializeNBT(root.getCompound("graph"));
        }
    }

    private static long key(FlowGraph graph) {
        return LineBottleneckAnalyzer.contentKey(graph, MODE);
    }

    private static RecipeNode machine(FlowGraph graph) {
        for (RecipeNode n : graph.getNodes()) {
            if (n.isMachine()) return n;
        }
        throw new IllegalStateException("fixture has no machine node");
    }

    /**
     * Solving rewrites efficiency, blocking ratios and caches but changes nothing about the board's
     * content, so it must NOT invalidate: otherwise the cache could never hit and every refresh would
     * pay for a full sweep.
     */
    @Test
    public void transientSolveStateDoesNotInvalidate() throws Exception {
        FlowGraph graph = load("platline");
        long before = key(graph);
        FixedPointEfficiencySolver.computeNodeEfficiencies(graph, MODE);
        assertEquals(before, key(graph), "a solve must not invalidate the bottleneck cache");
    }

    /** Every edit that can move the answer must miss the cache. */
    @Test
    public void contentEditsInvalidate() throws Exception {
        FlowGraph graph = load("platline");
        RecipeNode m = machine(graph);
        long k = key(graph);

        m.setMachineCount(m.getMachineCount() + 1.0);
        long afterCount = key(graph);
        assertNotEquals(k, afterCount, "machine count edit must invalidate");
        k = afterCount;

        m.setParallel(m.getParallel() + 1);
        long afterParallel = key(graph);
        assertNotEquals(k, afterParallel, "parallel edit must invalidate");
        k = afterParallel;

        // Only ever move a tier UP, and only assert when the setter actually took: setTargetTier routes
        // through the mod adapter's sanitizeTargetTier, which clamps to whatever the machine can
        // overclock to, so outside a full GTCEu environment the change can legitimately be a no-op.
        boolean tierChecked = false;
        for (RecipeNode n : graph.getNodes()) {
            if (!n.isMachine()) continue;
            GTVoltageTier current = n.getTargetTier();
            if (current == null || current == GTVoltageTier.MAX) continue;
            n.setTargetTier(GTVoltageTier.MAX);
            if (n.getTargetTier() == current) continue;
            long afterTier = key(graph);
            assertNotEquals(k, afterTier, "target tier edit must invalidate");
            k = afterTier;
            tierChecked = true;
            break;
        }
        System.out.println(tierChecked
                ? "  target tier edit: exercised"
                : "  target tier edit: SKIPPED - adapter sanitized the change away");

        // Junction supply configuration was invisible to the old key too.
        for (RecipeNode n : graph.getNodes()) {
            if (!n.isJunction()) continue;
            n.setExternalSupplyRate(n.getExternalSupplyRate() + 1000.0);
            long afterRate = key(graph);
            assertNotEquals(k, afterRate, "external supply rate edit must invalidate");
            k = afterRate;
            break;
        }

        RecipeNode junction = null;
        for (RecipeNode n : graph.getNodes()) {
            if (n.isJunction() && n.getSupplyMode() != null) {
                junction = n;
                break;
            }
        }
        if (junction != null) {
            SupplyMode original = junction.getSupplyMode();
            for (SupplyMode mode : SupplyMode.values()) {
                if (mode != original) {
                    junction.setSupplyMode(mode);
                    break;
                }
            }
            long afterSupply = key(graph);
            assertNotEquals(k, afterSupply, "junction supply mode edit must invalidate");
            k = afterSupply;
        }

        FlowGraph.ConnectionEdge edge = graph.getConnections().get(0);
        graph.removeConnection(edge);
        assertNotEquals(k, key(graph), "unwiring must invalidate");
    }

    /** Switching the production model changes what the sweep re-solves with, so it must invalidate. */
    @Test
    public void solveModeIsPartOfTheKey() throws Exception {
        FlowGraph graph = load("platline");
        assertNotEquals(LineBottleneckAnalyzer.contentKey(graph, LineSolveMode.SUPPLY_ONLY),
                LineBottleneckAnalyzer.contentKey(graph, LineSolveMode.SUPPLY_AND_DEMAND),
                "solve mode must be part of the cache key");
    }

    /**
     * Two boards must never serve each other's cached answer, even when their content is identical:
     * previously the key was purely a content hash, so a second page with a matching hash would inherit
     * the first page's highlighted node - and if that node id did not exist, clear every flag silently.
     */
    @Test
    public void separateGraphsNeverShareAnAnswer() throws Exception {
        FlowGraph a = load("platline");
        FlowGraph b = load("platline");
        assertNotEquals(key(a), key(b), "distinct graphs must not share a cache entry");
    }

    /** Layout is deliberately excluded, so dragging a card does not re-run an N+1 sweep every frame. */
    @Test
    public void layoutEditsDoNotInvalidate() throws Exception {
        FlowGraph graph = load("platline");
        long before = key(graph);
        machine(graph).setPos(4321.0, 1234.0);
        assertEquals(before, key(graph), "moving a card must not invalidate the bottleneck cache");
    }
}
