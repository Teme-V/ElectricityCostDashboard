package com.vesanieminen.froniusvisualizer.services.battery;

/**
 * Battery and simulation parameters.
 *
 * @param capacityKwh          usable nameplate capacity, kWh (0 = no battery)
 * @param powerKw              converter power limit for charge + discharge, kW
 * @param roundTripEfficiency  round-trip efficiency (0..1], split evenly between charge and discharge
 * @param socMinFraction       lowest allowed state of charge as a fraction of the capacity; also the initial state
 * @param socMaxFraction       highest allowed state of charge as a fraction of the capacity
 * @param cycleCostCentsPerKwh wear cost per discharged kWh, c/kWh
 * @param allowBatteryExport   whether the battery may discharge to the grid (export beyond own consumption)
 * @param epsilonGrid          tiny tie-breaking cost on grid flows, c/kWh
 * @param epsilonBattery       tiny tie-breaking cost on battery flows, c/kWh
 */
public record BatteryParameters(double capacityKwh, double powerKw, double roundTripEfficiency, double socMinFraction,
                                double socMaxFraction, double cycleCostCentsPerKwh, boolean allowBatteryExport,
                                double epsilonGrid, double epsilonBattery) {

    public static final double DEFAULT_EPSILON = 1e-4;

    public static BatteryParameters of(double capacityKwh, double powerKw, double roundTripEfficiency, double socMinFraction,
                                       double socMaxFraction, double cycleCostCentsPerKwh, boolean allowBatteryExport) {
        return new BatteryParameters(capacityKwh, powerKw, roundTripEfficiency, socMinFraction, socMaxFraction,
                cycleCostCentsPerKwh, allowBatteryExport, DEFAULT_EPSILON, DEFAULT_EPSILON);
    }

    public BatteryParameters withCapacity(double capacity) {
        return new BatteryParameters(capacity, powerKw, roundTripEfficiency, socMinFraction, socMaxFraction,
                cycleCostCentsPerKwh, allowBatteryExport, epsilonGrid, epsilonBattery);
    }

    public double oneWayEfficiency() {
        return Math.sqrt(roundTripEfficiency);
    }

    public double socMinKwh() {
        return socMinFraction * capacityKwh;
    }

    public double socMaxKwh() {
        return socMaxFraction * capacityKwh;
    }

    /**
     * Converter limit for a period of the given length; a battery of zero capacity has no converter either.
     */
    public double energyLimit(double hours) {
        return capacityKwh > 0 ? powerKw * hours : 0;
    }
}
