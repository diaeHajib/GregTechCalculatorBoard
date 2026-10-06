package com.gtceu.calcboard.api.solver;

import com.gtceu.calcboard.api.model.FlowGraph;
import com.gtceu.calcboard.api.model.IngredientStack;
import com.gtceu.calcboard.api.model.RecipeNode;
import com.gtceu.calcboard.api.type.LineSolveMode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Answers one question: <em>if you build one more of some machine on this line, which one buys you the
 * most?</em> The answer is published as a single {@link RecipeNode#isBottleneck()} flag, and the
 * renderer outlines that card apart from every other state.
 *
 * <h2>Why this is measured, not inferred</h2>
 *
 * <p>Every per-node heuristic for "bottleneck" is wrong, and this board proves it:
 * <ul>
 *   <li>the machine at its own ceiling is not the answer - a saturated machine can be saturated
 *       because nothing downstream will take more, in which case expanding it gains exactly nothing;</li>
 *   <li>the most-utilised machine is not the answer either - two machines with identical efficiency
 *       and identical blocking ratio on the same line routinely have completely different gains;</li>
 *   <li>which port clips a machine says nothing about whether adding capacity to it helps.</li>
 * </ul>
 * The constraint is a property of the whole line, so it is measured: each machine's count is nudged
 * up by one, the line is re-solved, and the change in the line's output is recorded. The machine with
 * the largest gain is the bottleneck. That is also exactly what a player means by the word.
 *
 * <h2>Line output</h2>
 *
 * <p>The gain is the <em>median relative change across every product the line exports</em> - every
 * output port that leads nowhere, i.e. what the board actually ships. Median rather than mean so one
 * huge waste stream cannot dominate, and relative rather than absolute so a 6 000/min acid stream
 * cannot drown out a 4/min dust. On a line that is one coupled chain every product scales by the same
 * fraction, so the median is simply "how much the whole line improves". On a branched line a machine
 * that only helps one arm of the graph scores near zero, which is the honest reading of "improves
 * things around".
 *
 * <h2>Behaviour over time</h2>
 *
 * <p>Nothing here is sticky: the sweep runs whenever the board is re-solved, so as soon as the
 * highlighted machine has been built a few times its own gain shrinks and the next best machine takes
 * the outline. That reproduces "expand the top one until it stops paying, then move to the next"
 * without any priority list to configure. It never declares the line finished - the player decides
 * when to stop.
 *
 * <h2>Cost</h2>
 *
 * <p>N+1 solves for N machines, on board re-solve only, never per frame. A single-entry cache keyed on
 * the board's full content keeps the steady state free; the key covers every field the solve reads, so
 * any edit that could change the answer misses the cache, and it is keyed on graph identity so two
 * pages can never serve each other's answer.
 */
public final class LineBottleneckAnalyzer {

    /** Ignore gains below this: float noise and rounding, not a real improvement. */
    private static final double GAIN_EPSILON = 1e-6;

    /**
     * Graphs currently being swept. Tracked per graph rather than with one global flag so that a
     * nested solve of a <em>different</em> graph - a module's sub-page, say - still gets its own sweep
     * instead of being silently skipped and left showing a stale highlight.
     */
    private static final Set<FlowGraph> SWEEPING = ConcurrentHashMap.newKeySet();

    private static long cachedKey = Long.MIN_VALUE;
    private static String cachedNodeId = null;
    private static double cachedGain = 0.0;
    private static Map<String, Double> lastGains = Map.of();

    /**
     * @return node id to measured gain from the most recent sweep, for diagnostics and tests. Empty
     *         until a sweep has run; unchanged when a sweep is served from cache, which is correct
     *         because a cache hit means the content - and therefore the gains - are identical.
     */
    static Map<String, Double> lastGains() {
        return lastGains;
    }

    private LineBottleneckAnalyzer() {}

    /**
     * Recomputes the bottleneck flag for the whole line. Clears the flag on every node first, so a
     * machine that stopped being the best buy is released immediately.
     *
     * @param graph graph that was just solved
     * @param mode  solve mode in force, used when re-solving each perturbed variant
     */
    public static void markBottlenecks(FlowGraph graph, LineSolveMode mode) {
        if (graph == null) return;

        if (!SWEEPING.add(graph)) {
            // Called from one of our own perturbation solves: the outer sweep owns the flags.
            return;
        }

        long key = contentKey(graph, mode);
        String bestId = null;
        double bestGain = 0.0;
        try {
            if (key == cachedKey && cachedNodeId != null) {
                applyFlag(graph, cachedNodeId, cachedGain);
                return;
            }

            // Settle the graph before measuring, so `base` reflects a solved line rather than whatever
            // efficiency state the caller happened to leave behind. Without this the answer depends on
            // call order instead of on the board's content alone.
            FixedPointEfficiencySolver.computeNodeEfficiencies(graph, mode);
            Map<String, Double> base = exportRates(graph);

            List<RecipeNode> machines = new ArrayList<>();
            for (RecipeNode node : graph.getNodes()) {
                if (node == null || node.isReroute() || !node.isMachine()) continue;
                if (node.getOutputs().isEmpty()) continue;
                machines.add(node);
            }

            Map<String, Double> gains = new LinkedHashMap<>();
            for (RecipeNode machine : machines) {
                double count = machine.getMachineCount();
                if (count <= 0.0) continue;
                machine.setMachineCount(count + 1.0);
                FixedPointEfficiencySolver.computeNodeEfficiencies(graph, mode);
                Map<String, Double> after = exportRates(graph);
                machine.setMachineCount(count);
                gains.put(machine.getId(), medianRelativeGain(base, after));
            }

            bestGain = GAIN_EPSILON;
            for (Map.Entry<String, Double> entry : gains.entrySet()) {
                if (Double.isFinite(entry.getValue()) && entry.getValue() > bestGain) {
                    bestGain = entry.getValue();
                    bestId = entry.getKey();
                }
            }
            if (bestId == null) bestGain = 0.0;
            lastGains = Map.copyOf(gains);

            // Put the graph back the way we found it - efficiencies, blocking info and caches all
            // follow from this final solve, and the guard keeps it from sweeping again.
            FixedPointEfficiencySolver.computeNodeEfficiencies(graph, mode);
        } finally {
            SWEEPING.remove(graph);
        }

        cachedKey = key;
        cachedNodeId = bestId;
        cachedGain = bestGain;
        applyFlag(graph, bestId, cachedGain);
    }

    private static void applyFlag(FlowGraph graph, String nodeId, double gain) {
        for (RecipeNode node : graph.getNodes()) {
            if (node == null) continue;
            boolean hit = nodeId != null && nodeId.equals(node.getId());
            node.setBottleneck(hit);
            node.setBottleneckGain(hit ? gain : 0.0);
        }
    }

    /**
     * @return effective rate of every output port that leads nowhere, keyed by resource name - i.e.
     *         what the line ships, in per-minute terms
     */
    private static Map<String, Double> exportRates(FlowGraph graph) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (RecipeNode node : graph.getNodes()) {
            if (node == null) continue;
            for (int o = 0; o < node.getOutputs().size(); o++) {
                boolean wired = false;
                for (FlowGraph.ConnectionEdge edge : graph.getConnections()) {
                    if (edge.fromNodeId().equals(node.getId()) && edge.outputIndex() == o) {
                        wired = true;
                        break;
                    }
                }
                if (wired) continue;
                out.merge(node.getOutputs().get(o).getDisplayName(), node.getOutputSlotRate(o, true) * 60.0, Double::sum);
            }
        }
        return out;
    }

    private static double medianRelativeGain(Map<String, Double> base, Map<String, Double> after) {
        List<Double> ratios = new ArrayList<>();
        for (Map.Entry<String, Double> entry : base.entrySet()) {
            double before = entry.getValue();
            if (!(before > 0.0) || !Double.isFinite(before)) continue;
            double now = after.getOrDefault(entry.getKey(), 0.0);
            if (!Double.isFinite(now)) continue;
            ratios.add(now / before - 1.0);
        }
        if (ratios.isEmpty()) return 0.0;
        ratios.sort(Double::compare);
        int mid = ratios.size() / 2;
        return ratios.size() % 2 == 1 ? ratios.get(mid) : 0.5 * (ratios.get(mid - 1) + ratios.get(mid));
    }

    /**
     * Content key of everything the sweep reads. Any edit that can change the answer changes the key,
     * so a stale entry can never be served: machine counts, parallels, target/recipe tier, overclock
     * mode, recipe timings, every ingredient with its amount, chance and boost, junction supply and
     * drain configuration, every node-kind flag, the <em>full</em> wiring (endpoints and port indices,
     * not just a connection count) and the solve mode. A graph's identity is mixed in as well, so two
     * pages can never serve each other's cached answer.
     *
     * <p>Card positions, zoom and pan are deliberately excluded: dragging a card is an edit that cannot
     * change the physics, and hashing the layout would re-run an N+1 solve sweep on every frame of a
     * drag. Layout changes therefore do not invalidate, by intent.
     *
     * <p>Package-private so the invalidation contract can be pinned by a test.
     */
    static long contentKey(FlowGraph graph, LineSolveMode mode) {
        long h = 1125899906842597L;
        h = h * 31 + System.identityHashCode(graph);
        h = h * 31 + (mode != null ? mode.ordinal() : -1);
        h = h * 31 + graph.getNodes().size();
        for (RecipeNode node : graph.getNodes()) {
            if (node == null) {
                h = h * 31 + 7;
                continue;
            }
            h = h * 31 + node.getId().hashCode();
            h = h * 31 + (node.isMachine() ? 1 : 0);
            h = h * 31 + (node.isModule() ? 1 : 0);
            h = h * 31 + (node.isReroute() ? 1 : 0);
            h = h * 31 + (node.isBaseNode() ? 1 : 0);
            h = h * 31 + (node.isGenerator() ? 1 : 0);
            h = h * 31 + (node.isFusion() ? 1 : 0);
            h = h * 31 + (node.isVoidSink() ? 1 : 0);
            h = h * 31 + (node.isExternalSupply() ? 1 : 0);
            h = h * 31 + (node.isFixedDrain() ? 1 : 0);
            h = h * 31 + (node.isInfiniteSupply() ? 1 : 0);
            h = h * 31 + (node.getSteamMode() != null ? node.getSteamMode().ordinal() + 1 : 0);
            h = h * 31 + Double.hashCode(node.getMachineCount());
            h = h * 31 + node.getParallel();
            h = h * 31 + node.getTotalParallel();
            h = h * 31 + (node.getTargetTier() != null ? node.getTargetTier().ordinal() : -1);
            h = h * 31 + (node.getRecipeTier() != null ? node.getRecipeTier().ordinal() : -1);
            h = h * 31 + (node.getOverclockMode() != null ? node.getOverclockMode().ordinal() : -1);
            h = h * 31 + Double.hashCode(node.getBaseDurationTicks());
            h = h * 31 + Double.hashCode(node.getBaseEUt());
            h = h * 31 + (node.getSupplyMode() != null ? node.getSupplyMode().ordinal() : -1);
            h = h * 31 + Double.hashCode(node.getExternalSupplyRate());
            h = h * 31 + Double.hashCode(node.getExternalDrainRate());
            h = h * 31 + node.getCompoundLayerIndex();
            h = h * 31 + (node.getCompoundGroupId() != null ? node.getCompoundGroupId().hashCode() : 0);
            List<IngredientStack> ins = node.getInputs();
            h = h * 31 + ins.size();
            for (IngredientStack in : ins) {
                h = mixIngredient(h, in);
            }
            List<IngredientStack> outs = node.getOutputs();
            h = h * 31 + outs.size();
            for (IngredientStack out : outs) {
                h = mixIngredient(h, out);
            }
        }
        h = h * 31 + graph.getConnections().size();
        for (FlowGraph.ConnectionEdge edge : graph.getConnections()) {
            h = h * 31 + (edge.fromNodeId() != null ? edge.fromNodeId().hashCode() : 0);
            h = h * 31 + edge.outputIndex();
            h = h * 31 + (edge.toNodeId() != null ? edge.toNodeId().hashCode() : 0);
            h = h * 31 + edge.inputIndex();
        }
        return h;
    }

    private static long mixIngredient(long h, IngredientStack stack) {
        if (stack == null) return h * 31 + 3;
        h = h * 31 + stack.getDisplayName().hashCode();
        h = h * 31 + Double.hashCode(stack.getAmount());
        h = h * 31 + Double.hashCode(stack.getChance());
        h = h * 31 + Double.hashCode(stack.getTierChanceBoost());
        h = h * 31 + (stack.isFluid() ? 1 : 0);
        h = h * 31 + (stack.isStressUnit() ? 1 : 0);
        return h;
    }
}
