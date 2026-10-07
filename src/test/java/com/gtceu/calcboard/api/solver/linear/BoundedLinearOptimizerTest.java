package com.gtceu.calcboard.api.solver.linear;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BoundedLinearOptimizerTest {

    @Test
    void conservationInequalitiesFindACapacityBoundedRecyclingRatio() {
        double[] result = BoundedLinearOptimizer.maximize(
                new double[][]{{1, 0}, {0, 1}, {2, -4}, {-2, 4}},
                new double[]{1, 1, 0, 0}, new double[]{1, 1});
        assertArrayEquals(new double[]{1, 0.5}, result, 1e-9);
    }

    @Test
    void genuinelyLossyUnfedCycleHasOnlyTheZeroOperatingPoint() {
        double[] result = BoundedLinearOptimizer.maximize(
                new double[][]{{1, 0}, {0, 1}, {1, -1}, {-0.9, 1}},
                new double[]{1, 1, 0, 0}, new double[]{1, 1});
        assertArrayEquals(new double[]{0, 0}, result, 1e-9);
    }

    @Test
    void surplusProductionDoesNotRequireAnImpossibleEquality() {
        double[] result = BoundedLinearOptimizer.maximize(
                new double[][]{{1, 0}, {0, 1}, {1, -2}},
                new double[]{1, 1, 0}, new double[]{1, 1});
        assertArrayEquals(new double[]{1, 1}, result, 1e-9);
    }

    @Test
    void unsupportedInputAndUnboundedObjectivesAreExplicitErrors() {
        assertThrows(IllegalArgumentException.class, () -> BoundedLinearOptimizer.maximize(
                new double[][]{{1}}, new double[]{-1}, new double[]{1}));
        assertThrows(IllegalArgumentException.class, () -> BoundedLinearOptimizer.maximize(
                new double[][]{{Double.NaN}}, new double[]{1}, new double[]{1}));
        assertThrows(IllegalArgumentException.class, () -> BoundedLinearOptimizer.maximize(
                new double[0][], new double[0], new double[]{1}));
    }
}
