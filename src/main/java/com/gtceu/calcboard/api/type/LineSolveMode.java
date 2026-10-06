package com.gtceu.calcboard.api.type;

/**
 * Selects how the flow solver constrains machine operation.
 *
 * <p>{@link #SUPPLY_ONLY} is the historical behaviour of the calculator: a node's efficiency is the
 * minimum over its input ports of {@code incomingSupply / nominalInputRate}. Machines are therefore
 * only ever throttled by a lack of feed. An output that nothing consumes never slows its producer
 * down, so a line whose tail is blocked still reports every machine as running at 100%.
 *
 * <p>{@link #SUPPLY_AND_DEMAND} additionally constrains every producer by the appetite of the
 * consumers actually wired to its output ports. A machine whose output has nowhere to go is
 * throttled to the rate its consumers can absorb, which then reduces its own demand for inputs and
 * so propagates upstream. This reproduces a real line, where a blocked machine stops and starves
 * whatever feeds it.
 *
 * <p>The mode is opt-in so that existing boards keep their historical numbers.
 */
public enum LineSolveMode {

    /**
     * Historical behaviour: starvation (forward propagation) only.
     */
    SUPPLY_ONLY("gui.gtcalcboard.solve_mode.supply_only", false),

    /**
     * Real-line behaviour: starvation and blocking (forward and backward propagation).
     */
    SUPPLY_AND_DEMAND("gui.gtcalcboard.solve_mode.supply_and_demand", true);

    private final String translationKey;
    private final boolean blockingAware;

    LineSolveMode(String translationKey, boolean blockingAware) {
        this.translationKey = translationKey;
        this.blockingAware = blockingAware;
    }

    public String getTranslationKey() {
        return translationKey;
    }

    /**
     * @return true when the solver must also constrain producers by downstream demand.
     */
    public boolean isBlockingAware() {
        return blockingAware;
    }

    /**
     * @return the next mode in cycling order, for a single-button settings control.
     */
    public LineSolveMode next() {
        LineSolveMode[] values = values();
        return values[(ordinal() + 1) % values.length];
    }

    /**
     * Tolerant lookup that never throws on an unknown or legacy name.
     */
    public static LineSolveMode byName(String name, LineSolveMode fallback) {
        if (name != null) {
            for (LineSolveMode mode : values()) {
                if (mode.name().equalsIgnoreCase(name)) {
                    return mode;
                }
            }
        }
        return fallback != null ? fallback : SUPPLY_ONLY;
    }
}
