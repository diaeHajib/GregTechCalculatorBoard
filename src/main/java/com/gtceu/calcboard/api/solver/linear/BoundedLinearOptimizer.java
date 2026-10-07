package com.gtceu.calcboard.api.solver.linear;

/**
 * Primal simplex for max(c*x), A*x <= b, x >= 0, with a feasible zero starting point.
 */
public final class BoundedLinearOptimizer {

    private static final double EPSILON = 1e-10;

    private BoundedLinearOptimizer() {}

    public static double[] maximize(double[][] coefficients, double[] bounds, double[] objective) {
        return maximizeLexicographic(coefficients, bounds, new double[][]{objective});
    }

    /**
     * Optimizes objectives in order; later objectives cannot sacrifice an earlier optimum.
     */
    public static double[] maximizeLexicographic(double[][] coefficients, double[] bounds, double[][] objectives) {
        if (objectives.length == 0) throw new IllegalArgumentException("At least one objective is required");
        int rows = coefficients.length;
        int variables = objectives[0].length;
        if (bounds.length != rows) throw new IllegalArgumentException("Mismatched constraint bounds");
        int columns = variables + rows;
        double[][] tableau = new double[rows + objectives.length][columns + 1];
        int[] basis = new int[rows];
        for (int row = 0; row < rows; row++) {
            if (coefficients[row].length != variables || !Double.isFinite(bounds[row]) || bounds[row] < 0) {
                throw new IllegalArgumentException("Constraints must admit a finite, feasible zero starting point");
            }
            double scale = Math.max(1.0, bounds[row]);
            for (double coefficient : coefficients[row]) {
                if (!Double.isFinite(coefficient)) throw new IllegalArgumentException("Non-finite coefficient");
                scale = Math.max(scale, Math.abs(coefficient));
            }
            for (int column = 0; column < variables; column++) {
                tableau[row][column] = coefficients[row][column] / scale;
            }
            tableau[row][variables + row] = 1.0;
            tableau[row][columns] = bounds[row] / scale;
            basis[row] = variables + row;
        }
        for (int level = 0; level < objectives.length; level++) {
            double[] objective = objectives[level];
            if (objective.length != variables) throw new IllegalArgumentException("Mismatched objectives");
            for (int column = 0; column < variables; column++) {
                if (!Double.isFinite(objective[column])) throw new IllegalArgumentException("Non-finite objective");
                tableau[rows + level][column] = -objective[column];
            }
        }

        boolean[] allowed = new boolean[columns];
        java.util.Arrays.fill(allowed, true);
        for (int level = 0; level < objectives.length; level++) {
            optimizeObjective(tableau, basis, rows + level, columns, allowed);
            // Later objectives may move only along the already optimal face. Freeze near-zero
            // reduced costs once, rather than repeatedly comparing noisy lexicographic vectors.
            for (int column = 0; column < columns; column++) {
                double cost = tableau[rows + level][column];
                if (Math.abs(cost) <= EPSILON) tableau[rows + level][column] = 0.0;
                else allowed[column] = false;
            }
        }
        double[] solution = new double[variables];
        for (int row = 0; row < rows; row++) {
            if (basis[row] < variables) {
                solution[basis[row]] = Math.max(0.0, tableau[row][columns]);
            }
        }
        return solution;
    }

    private static void optimizeObjective(double[][] tableau, int[] basis, int objectiveRow,
                                          int columns, boolean[] allowed) {
        int rows = basis.length;
        // Bland's entering/leaving rules avoid cycling on zero-bound conservation constraints.
        for (int iteration = 0; iteration < 10000; iteration++) {
            int entering = -1;
            for (int column = 0; column < columns; column++) {
                if (allowed[column] && tableau[objectiveRow][column] < -EPSILON) {
                    entering = column;
                    break;
                }
            }
            if (entering < 0) {
                return;
            }
            int leaving = -1;
            double bestRatio = Double.POSITIVE_INFINITY;
            for (int row = 0; row < rows; row++) {
                if (tableau[row][entering] <= EPSILON) continue;
                double ratio = tableau[row][columns] / tableau[row][entering];
                if (ratio < bestRatio - EPSILON
                        || (Math.abs(ratio - bestRatio) <= EPSILON
                        && (leaving < 0 || basis[row] < basis[leaving]))) {
                    bestRatio = ratio;
                    leaving = row;
                }
            }
            if (leaving < 0) throw new IllegalArgumentException("Unbounded objective");
            double pivot = tableau[leaving][entering];
            for (int column = 0; column <= columns; column++) tableau[leaving][column] /= pivot;
            for (int row = 0; row < tableau.length; row++) {
                if (row == leaving) continue;
                double factor = tableau[row][entering];
                if (factor == 0.0) continue;
                for (int column = 0; column <= columns; column++) {
                    tableau[row][column] -= factor * tableau[leaving][column];
                }
            }
            basis[leaving] = entering;
        }
        throw new IllegalStateException("Linear capacity optimization did not converge");
    }
}
