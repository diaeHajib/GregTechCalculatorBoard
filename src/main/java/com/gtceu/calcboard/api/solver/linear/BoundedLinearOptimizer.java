package com.gtceu.calcboard.api.solver.linear;

/**
 * Primal simplex for max(c*x), A*x <= b, x >= 0, with a feasible zero starting point.
 */
public final class BoundedLinearOptimizer {

    private static final double EPSILON = 1e-10;

    private BoundedLinearOptimizer() {}

    public static double[] maximize(double[][] coefficients, double[] bounds, double[] objective) {
        int rows = coefficients.length;
        int variables = objective.length;
        if (bounds.length != rows) throw new IllegalArgumentException("Mismatched constraint bounds");
        int columns = variables + rows;
        double[][] tableau = new double[rows + 1][columns + 1];
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
        for (int column = 0; column < variables; column++) {
            if (!Double.isFinite(objective[column])) throw new IllegalArgumentException("Non-finite objective");
            tableau[rows][column] = -objective[column];
        }

        // Bland's entering/leaving rules avoid cycling on zero-bound conservation constraints.
        for (int iteration = 0; iteration < 10000; iteration++) {
            int entering = -1;
            for (int column = 0; column < columns; column++) {
                if (tableau[rows][column] < -EPSILON) {
                    entering = column;
                    break;
                }
            }
            if (entering < 0) {
                double[] solution = new double[variables];
                for (int row = 0; row < rows; row++) {
                    if (basis[row] < variables) {
                        solution[basis[row]] = Math.max(0.0, tableau[row][columns]);
                    }
                }
                return solution;
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
            for (int row = 0; row <= rows; row++) {
                if (row == leaving) continue;
                double factor = tableau[row][entering];
                for (int column = 0; column <= columns; column++) {
                    tableau[row][column] -= factor * tableau[leaving][column];
                }
            }
            basis[leaving] = entering;
        }
        throw new IllegalStateException("Linear capacity optimization did not converge");
    }
}
