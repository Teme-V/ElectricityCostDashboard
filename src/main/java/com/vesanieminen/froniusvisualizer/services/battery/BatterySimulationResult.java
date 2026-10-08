package com.vesanieminen.froniusvisualizer.services.battery;

/**
 * Committed dispatch of one simulation. Energies in kWh per period, costs in euros.
 *
 * @param charge         energy into the battery per period (AC side)
 * @param discharge      energy out of the battery per period (AC side)
 * @param gridImport     metered import per period
 * @param gridExport     metered export per period
 * @param soc            state of charge at the end of each period
 * @param cashCost       import cost - export revenue + wear cost per period, EUR
 * @param totalCashCost  sum of {@code cashCost}, EUR
 * @param terminalCredit value of the energy left in the battery at the end, EUR
 */
public record BatterySimulationResult(BatteryParameters params, PlanningHorizon horizon, double[] charge,
                                      double[] discharge, double[] gridImport, double[] gridExport, double[] soc,
                                      double[] cashCost, double totalCashCost, double terminalCredit) {

    /**
     * Net cost of the simulated period, EUR: cash cost minus the value of the energy left in the battery.
     */
    public double totalCost() {
        return totalCashCost - terminalCredit;
    }

    public double dischargedEnergy() {
        double sum = 0;
        for (double d : discharge) {
            sum += d;
        }
        return sum;
    }
}
