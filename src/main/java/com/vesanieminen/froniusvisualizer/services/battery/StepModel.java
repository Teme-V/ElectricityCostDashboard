package com.vesanieminen.froniusvisualizer.services.battery;

import java.util.Arrays;

/**
 * Cost of one metering period as a function of the battery's state-of-charge change.
 * <p>
 * Decision variables of the period (all energies in kWh per period, prices in c/kWh):
 * charge {@code c} (AC side, into the battery) and discharge {@code d} (AC side, out of the battery), with
 * {@code 0 <= c <= P}, {@code 0 <= d <= Dmax}, {@code c + d <= P} (one converter, time-shared within the period).
 * The metered consumption {@code a} and production {@code b} of the period are given. Grid import {@code gi} and
 * export {@code ge} follow from the metering-point netting:
 * <pre>
 *   gi - ge = a - b + c - d,   gi >= max(0, a - d),   ge >= max(0, b - c)
 * </pre>
 * so that without a battery {@code gi = a} and {@code ge = b} exactly (the same split the price calculator bills),
 * and the battery never creates simultaneous import and export that the data did not already contain.
 * <p>
 * The period cost is
 * {@code F(c, d) = (buy + epsGrid) gi + (epsGrid - sellEff) ge + cycleCost d + epsBattery (c + d)}
 * with {@code sellEff = min(sell, buy)}. {@link #phi(double)} is the minimum of {@code F} over the line
 * {@code eta c - d / eta = u}, i.e. over all ways to change the state of charge by {@code u}. It is a convex
 * piecewise linear function whose breakpoints are the values of {@code u} at the vertices of the arrangement of
 * the constraint lines and the kink lines of {@code F}; {@link #segments} returns it in that exact form.
 */
public final class StepModel {

    // lines alpha * c + beta * d = gamma
    private final double[] alpha = new double[8];
    private final double[] beta = new double[8];
    private final double[] gamma = new double[8];
    private final int lineCount;

    private final double a;
    private final double b;
    private final double buy;
    private final double sellEff;
    private final double p;
    private final double dMax;
    private final double eta;
    private final double cycleCost;
    private final double epsGrid;
    private final double epsBattery;
    private final double tol;

    /**
     * @param consumption metered consumption of the period, kWh
     * @param production  metered production (export) of the period, kWh
     * @param buy         purchase price of the period, c/kWh
     * @param sell        sale price of the period, c/kWh (the objective uses {@code min(sell, buy)})
     * @param energyLimit converter limit for the period, kWh ({@code P * hours})
     * @param params      battery parameters
     */
    public StepModel(double consumption, double production, double buy, double sell, double energyLimit, BatteryParameters params) {
        this.a = Math.max(consumption, 0);
        this.b = Math.max(production, 0);
        this.buy = buy;
        this.sellEff = Math.min(sell, buy);
        this.p = Math.max(energyLimit, 0);
        this.dMax = params.allowBatteryExport() ? p : Math.min(p, a);
        this.eta = params.oneWayEfficiency();
        this.cycleCost = params.cycleCostCentsPerKwh();
        this.epsGrid = params.epsilonGrid();
        this.epsBattery = params.epsilonBattery();
        this.tol = 1e-12 * Math.max(1, Math.max(p, Math.max(a, b)));
        int i = 0;
        i = line(i, 1, 0, 0);        // c = 0
        i = line(i, 0, 1, 0);        // d = 0
        i = line(i, 1, 0, p);        // c = P
        i = line(i, 0, 1, dMax);     // d = Dmax
        i = line(i, 1, 1, p);        // c + d = P
        i = line(i, 0, 1, a);        // d = a   (kink of F)
        i = line(i, 1, 0, b);        // c = b   (kink of F)
        i = line(i, 1, -1, b - a);   // c - d = b - a (kink of F: netted import/export switch)
        this.lineCount = i;
    }

    private int line(int i, double al, double be, double ga) {
        alpha[i] = al;
        beta[i] = be;
        gamma[i] = ga;
        return i + 1;
    }

    /**
     * Grid import and export for given battery flows.
     */
    public double gridImport(double c, double d) {
        final double x = a - b + c - d;
        final double lowA = Math.max(0, a - d);
        final double lowB = Math.max(0, b - c);
        return x >= lowA - lowB ? x + lowB : lowA;
    }

    public double gridExport(double c, double d) {
        final double x = a - b + c - d;
        final double lowA = Math.max(0, a - d);
        final double lowB = Math.max(0, b - c);
        return x >= lowA - lowB ? lowB : lowA - x;
    }

    /**
     * Objective value of the period (LP prices, including the tie-breaking epsilons).
     */
    public double cost(double c, double d) {
        final double gi = gridImport(c, d);
        final double ge = gridExport(c, d);
        return (buy + epsGrid) * gi + (epsGrid - sellEff) * ge + cycleCost * d + epsBattery * (c + d);
    }

    private boolean feasible(double c, double d) {
        return c >= -tol && d >= -tol && c <= p + tol && d <= dMax + tol && c + d <= p + tol;
    }

    /**
     * Minimal cost and its arg-min on the line {@code eta c - d / eta = u}. Returns {cost, c, d}; cost is
     * {@code +Infinity} when {@code u} is outside the feasible range.
     */
    public double[] best(double u) {
        double bestCost = Double.POSITIVE_INFINITY;
        double bestC = 0;
        double bestD = 0;
        for (int j = 0; j < lineCount; j++) {
            // eta c - d/eta = u ; alpha c + beta d = gamma
            final double det = eta * beta[j] + alpha[j] / eta;
            if (Math.abs(det) < 1e-15) {
                continue;
            }
            double c = (u * beta[j] + gamma[j] / eta) / det;
            double d = (eta * gamma[j] - alpha[j] * u) / det;
            if (!feasible(c, d)) {
                continue;
            }
            c = Math.min(Math.max(c, 0), p);
            d = Math.min(Math.max(d, 0), dMax);
            final double value = cost(c, d);
            if (value < bestCost - 1e-12 || (value <= bestCost + 1e-12 && c + d < bestC + bestD - 1e-12)) {
                bestCost = value;
                bestC = c;
                bestD = d;
            }
        }
        return new double[]{bestCost, bestC, bestD};
    }

    public double phi(double u) {
        return best(u)[0];
    }

    /**
     * The function {@code phi} as {start, lengths[], slopes[]}: {@code phi} is defined on
     * {@code [start, start + sum(lengths)]} and its slope on the k-th piece is {@code slopes[k]} (non-decreasing).
     */
    public Segments segments() {
        final double[] candidates = new double[lineCount * (lineCount - 1) / 2];
        int n = 0;
        for (int i = 0; i < lineCount; i++) {
            for (int j = i + 1; j < lineCount; j++) {
                final double det = alpha[i] * beta[j] - alpha[j] * beta[i];
                if (Math.abs(det) < 1e-15) {
                    continue;
                }
                final double c = (gamma[i] * beta[j] - gamma[j] * beta[i]) / det;
                final double d = (alpha[i] * gamma[j] - alpha[j] * gamma[i]) / det;
                if (feasible(c, d)) {
                    candidates[n++] = eta * Math.min(Math.max(c, 0), p) - Math.min(Math.max(d, 0), dMax) / eta;
                }
            }
        }
        Arrays.sort(candidates, 0, n);
        final double[] us = new double[n];
        int m = 0;
        for (int k = 0; k < n; k++) {
            if (m == 0 || candidates[k] - us[m - 1] > tol) {
                us[m++] = candidates[k];
            }
        }
        if (m == 0) {
            return new Segments(0, new double[0], new double[0]);
        }
        final double[] values = new double[m];
        for (int k = 0; k < m; k++) {
            values[k] = phi(us[k]);
        }
        final double[] lengths = new double[m - 1];
        final double[] slopes = new double[m - 1];
        for (int k = 0; k + 1 < m; k++) {
            lengths[k] = us[k + 1] - us[k];
            slopes[k] = (values[k + 1] - values[k]) / lengths[k];
        }
        // guard against rounding: a convex function has non-decreasing slopes
        for (int k = 1; k < slopes.length; k++) {
            if (slopes[k] < slopes[k - 1]) {
                slopes[k] = slopes[k - 1];
            }
        }
        return new Segments(us[0], lengths, slopes);
    }

    public record Segments(double start, double[] lengths, double[] slopes) {
    }
}
