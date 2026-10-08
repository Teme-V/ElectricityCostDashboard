package com.vesanieminen.froniusvisualizer.services.battery;

import java.time.Instant;

/**
 * Metered series of one metering point on a common time grid.
 *
 * @param time        period start times, ascending
 * @param hours       period lengths, h (0.25 for 15 min periods, 1 for hourly periods)
 * @param consumption metered consumption per period, kWh
 * @param production  metered production (export) per period, kWh
 * @param buy         purchase price per period incl. all per-kWh components, c/kWh
 * @param sell        sale price per period, c/kWh
 */
public record BatterySimulationInput(Instant[] time, double[] hours, double[] consumption, double[] production,
                                     double[] buy, double[] sell) {

    public BatterySimulationInput {
        final int n = time.length;
        if (hours.length != n || consumption.length != n || production.length != n || buy.length != n || sell.length != n) {
            throw new IllegalArgumentException("all series must have the same length");
        }
    }

    public int size() {
        return time.length;
    }

    public double meanSell() {
        double sum = 0;
        for (double s : sell) {
            sum += s;
        }
        return sell.length == 0 ? 0 : sum / sell.length;
    }

    public double totalHours() {
        double sum = 0;
        for (double h : hours) {
            sum += h;
        }
        return sum;
    }
}
