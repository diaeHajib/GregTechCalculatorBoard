package com.gtceu.calcboard.api.type;

/**
 * Process-wide holder for the active {@link LineSolveMode}.
 *
 * <p>The solver layer ({@code api.solver}) must not depend on the storage layer
 * ({@code api.storage}), which is the direction the dependency already runs. This holder is the
 * narrow seam between them: {@code BoardSettings} publishes the mode of the board it just loaded or
 * is currently editing, and the summary aggregation reads it when it decides how to solve.
 *
 * <p>Defaults to {@link LineSolveMode#SUPPLY_ONLY} so that any code path which never touches board
 * settings - including unit tests - gets the historical behaviour.
 */
public final class LineSolveModeHolder {

    private static volatile LineSolveMode mode = LineSolveMode.SUPPLY_ONLY;

    private LineSolveModeHolder() {}

    /**
     * @return the active mode, never null
     */
    public static LineSolveMode get() {
        return mode;
    }

    /**
     * @param newMode mode to activate; null falls back to {@link LineSolveMode#SUPPLY_ONLY}
     */
    public static void set(LineSolveMode newMode) {
        mode = newMode != null ? newMode : LineSolveMode.SUPPLY_ONLY;
    }

    /**
     * @return true when the active mode must also constrain producers by downstream demand
     */
    public static boolean isBlockingAware() {
        return mode.isBlockingAware();
    }
}
