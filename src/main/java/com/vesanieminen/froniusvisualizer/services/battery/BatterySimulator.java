package com.vesanieminen.froniusvisualizer.services.battery;

import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Simulates battery dispatch over metered history with a given planning horizon.
 * <p>
 * Within each planning window the battery is dispatched optimally (perfect knowledge of consumption, production
 * and the prices visible to that window); the committed part of each window is executed and its final state of charge
 * carries over. The simulation starts from the minimum state of charge. Energy left in the battery at the very end is
 * credited at {@code efficiency * mean(sell)} so that results do not depend on an arbitrary end-of-period emptying.
 * Costs are reported at the true contract prices (no tie-breaking epsilons).
 */
public final class BatterySimulator {

    private BatterySimulator() {
    }

    public static BatterySimulationResult simulate(BatterySimulationInput input, BatteryParameters params,
                                                   PlanningHorizon horizon, ZoneId zone) {
        return simulate(input, params, horizon, zone, () -> false);
    }

    public static BatterySimulationResult simulate(BatterySimulationInput input, BatteryParameters params,
                                                   PlanningHorizon horizon, ZoneId zone, BooleanSupplier cancelled) {
        final int n = input.size();
        final StepModel[] models = new StepModel[n];
        final StepModel.Segments[] phi = new StepModel.Segments[n];
        for (int t = 0; t < n; t++) {
            models[t] = new StepModel(input.consumption()[t], input.production()[t], input.buy()[t], input.sell()[t],
                    params.energyLimit(input.hours()[t]), params);
            phi[t] = models[t].segments();
        }
        final double socMin = params.socMinKwh();
        final double socMax = params.socMaxKwh();
        final double terminalValue = params.oneWayEfficiency() * input.meanSell();

        final double[] charge = new double[n];
        final double[] discharge = new double[n];
        final double[] gridImport = new double[n];
        final double[] gridExport = new double[n];
        final double[] soc = new double[n];

        double state = socMin;
        final List<PlanningHorizon.Window> windows = PlanningHorizon.windows(horizon, input.time(), zone);
        for (PlanningHorizon.Window window : windows) {
            if (cancelled.getAsBoolean()) {
                throw new CancellationException();
            }
            final StepModel.Segments[] slice = java.util.Arrays.copyOfRange(phi, window.from(), window.to());
            final double[] path = StorageDispatchSolver.solve(slice, state, socMin, socMax, window.terminal() ? terminalValue : 0);
            for (int t = window.from(); t < window.commitTo(); t++) {
                final double next = Math.min(Math.max(path[t - window.from()], socMin), socMax);
                final double[] best = models[t].best(next - state);
                charge[t] = best[1];
                discharge[t] = best[2];
                gridImport[t] = models[t].gridImport(best[1], best[2]);
                gridExport[t] = models[t].gridExport(best[1], best[2]);
                soc[t] = next;
                state = next;
            }
        }

        final double[] cashCost = new double[n];
        double total = 0;
        for (int t = 0; t < n; t++) {
            cashCost[t] = (input.buy()[t] * gridImport[t] - input.sell()[t] * gridExport[t]
                    + params.cycleCostCentsPerKwh() * discharge[t]) / 100;
            total += cashCost[t];
        }
        final double terminalCredit = terminalValue * (state - socMin) / 100;
        return new BatterySimulationResult(params, horizon, charge, discharge, gridImport, gridExport, soc, cashCost,
                total, terminalCredit);
    }
}
