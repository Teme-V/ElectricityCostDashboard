package com.vesanieminen.froniusvisualizer.services.battery;

import java.util.Arrays;

/**
 * Exact solver for the single-battery dispatch LP of one planning window.
 * <p>
 * The window problem is {@code min sum_t phi_t(u_t) - terminalValue * s_H} subject to
 * {@code s_t = s_(t-1) + u_t} and {@code socMin <= s_t <= socMax}, where {@code phi_t} is the convex piecewise linear
 * period cost of {@link StepModel}. It is solved by forward dynamic programming on the state of charge: the
 * cost-to-arrive {@code V_t(s)} stays convex piecewise linear and
 * {@code V_t = clip(V_(t-1) infimal-convolution phi_t, [socMin, socMax])}. The infimal convolution of two convex
 * piecewise linear functions merges their pieces in slope order, so each step costs O(pieces). The optimal path is
 * recovered backwards by splitting the merged pieces at the optimal end state. The result is the exact LP optimum
 * (no state discretisation), equal to what a general LP solver returns for the same model.
 * <p>
 * Long windows are handled with checkpoints every {@value #BLOCK} periods, so memory stays bounded by one block of
 * stored functions instead of the whole window.
 */
public final class StorageDispatchSolver {

    private static final int BLOCK = 256;
    private static final double TOL = 1e-12;

    private StorageDispatchSolver() {
    }

    /**
     * @param phi           period cost functions of the window, in time order
     * @param socInit       state of charge before the first period, kWh
     * @param socMin        lower bound for the state of charge, kWh
     * @param socMax        upper bound for the state of charge, kWh
     * @param terminalValue value of energy left in the battery at the end of the window, c/kWh (0 = none)
     * @return state of charge at the end of each period of the window, kWh
     */
    public static double[] solve(StepModel.Segments[] phi, double socInit, double socMin, double socMax, double terminalValue) {
        final int horizon = phi.length;
        final double[] soc = new double[horizon];
        if (horizon == 0) {
            return soc;
        }
        final Pwl start = Pwl.point(socInit);
        if (horizon <= BLOCK) {
            final Pwl[] before = new Pwl[horizon];
            Pwl v = start;
            for (int t = 0; t < horizon; t++) {
                before[t] = v;
                v = step(v, phi[t], socMin, socMax);
            }
            double s = v.argMin(terminalValue);
            for (int t = horizon - 1; t >= 0; t--) {
                soc[t] = s;
                s = before[t].split(phi[t], s);
            }
            return soc;
        }
        // pass 1: checkpoints of the state before every BLOCK-th period
        final int blocks = (horizon + BLOCK - 1) / BLOCK;
        final Pwl[] checkpoints = new Pwl[blocks];
        Pwl v = start;
        for (int t = 0; t < horizon; t++) {
            if (t % BLOCK == 0) {
                checkpoints[t / BLOCK] = v;
            }
            v = step(v, phi[t], socMin, socMax);
        }
        double s = v.argMin(terminalValue);
        // pass 2: recompute each block from its checkpoint and backtrack through it
        final Pwl[] before = new Pwl[BLOCK];
        for (int block = blocks - 1; block >= 0; block--) {
            final int from = block * BLOCK;
            final int to = Math.min(horizon, from + BLOCK);
            Pwl w = checkpoints[block];
            for (int t = from; t < to; t++) {
                before[t - from] = w;
                w = step(w, phi[t], socMin, socMax);
            }
            for (int t = to - 1; t >= from; t--) {
                soc[t] = s;
                s = before[t - from].split(phi[t], s);
            }
            checkpoints[block] = null;
        }
        return soc;
    }

    static Pwl step(Pwl v, StepModel.Segments phi, double socMin, double socMax) {
        return v.convolve(phi).clip(socMin, socMax);
    }

    /**
     * Convex piecewise linear function on {@code [start, start + sum(len)]} with non-decreasing slopes.
     * Function values are not needed for the dispatch and are not tracked.
     */
    static final class Pwl {
        final double start;
        final double[] len;
        final double[] slope;
        final int n;

        Pwl(double start, double[] len, double[] slope, int n) {
            this.start = start;
            this.len = len;
            this.slope = slope;
            this.n = n;
        }

        static Pwl point(double x) {
            return new Pwl(x, new double[0], new double[0], 0);
        }

        double end() {
            double e = start;
            for (int k = 0; k < n; k++) {
                e += len[k];
            }
            return e;
        }

        /**
         * Infimal convolution: pieces merged in slope order (ties: this function's piece first).
         */
        Pwl convolve(StepModel.Segments f) {
            final double[] fl = f.lengths();
            final double[] fs = f.slopes();
            final int m = fl.length;
            final double[] outLen = new double[n + m];
            final double[] outSlope = new double[n + m];
            int i = 0;
            int j = 0;
            int k = 0;
            while (i < n || j < m) {
                final boolean takeThis = j >= m || (i < n && slope[i] <= fs[j]);
                final double l = takeThis ? len[i] : fl[j];
                final double sl = takeThis ? slope[i++] : fs[j++];
                if (l <= TOL) {
                    continue;
                }
                if (k > 0 && Math.abs(outSlope[k - 1] - sl) <= TOL * Math.max(1, Math.abs(sl))) {
                    outLen[k - 1] += l;
                } else {
                    outLen[k] = l;
                    outSlope[k] = sl;
                    k++;
                }
            }
            return new Pwl(start + f.start(), outLen, outSlope, k);
        }

        /**
         * Restriction of the domain to {@code [lo, hi]}.
         */
        Pwl clip(double lo, double hi) {
            final double[] l = Arrays.copyOf(len, n);
            int first = 0;
            int last = n; // exclusive
            double newStart = start;
            if (newStart < lo) {
                double need = lo - newStart;
                while (first < last && l[first] <= need + TOL) {
                    need -= l[first];
                    first++;
                }
                if (first < last) {
                    l[first] -= Math.max(need, 0);
                }
                newStart = lo;
            }
            double total = 0;
            for (int k = first; k < last; k++) {
                total += l[k];
            }
            double excess = newStart + total - hi;
            if (excess > 0) {
                while (last > first && l[last - 1] <= excess + TOL) {
                    excess -= l[last - 1];
                    last--;
                }
                if (last > first) {
                    l[last - 1] -= Math.max(excess, 0);
                }
                newStart = Math.min(newStart, hi);
            }
            return new Pwl(newStart, Arrays.copyOfRange(l, first, last), Arrays.copyOfRange(slope, first, last), last - first);
        }

        /**
         * Minimiser of {@code this(x) - value * x} (leftmost on ties).
         */
        double argMin(double value) {
            double x = start;
            for (int k = 0; k < n && slope[k] < value; k++) {
                x += len[k];
            }
            return x;
        }

        /**
         * Given the state {@code target} reached after a period with cost {@code f}, returns the optimal state before
         * the period: the share of the merged pieces up to {@code target} that came from this function.
         */
        double split(StepModel.Segments f, double target) {
            final double[] fl = f.lengths();
            final double[] fs = f.slopes();
            final int m = fl.length;
            double remaining = target - (start + f.start());
            double taken = 0;
            int i = 0;
            int j = 0;
            while (remaining > TOL && (i < n || j < m)) {
                final boolean takeThis = j >= m || (i < n && slope[i] <= fs[j]);
                if (takeThis) {
                    final double t = Math.min(len[i++], remaining);
                    taken += t;
                    remaining -= t;
                } else {
                    remaining -= Math.min(fl[j++], remaining);
                }
            }
            return Math.min(Math.max(start + taken, start), end());
        }
    }
}
