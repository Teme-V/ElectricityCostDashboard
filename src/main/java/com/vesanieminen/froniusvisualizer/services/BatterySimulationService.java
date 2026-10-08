package com.vesanieminen.froniusvisualizer.services;

import com.vesanieminen.froniusvisualizer.services.battery.BatteryParameters;
import com.vesanieminen.froniusvisualizer.services.battery.BatterySimulationInput;
import com.vesanieminen.froniusvisualizer.services.battery.BatterySimulationResult;
import com.vesanieminen.froniusvisualizer.services.battery.BatterySimulator;
import com.vesanieminen.froniusvisualizer.services.battery.PlanningHorizon;

import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;

import static com.vesanieminen.froniusvisualizer.util.Utils.fiZoneID;
import static com.vesanieminen.froniusvisualizer.util.Utils.getVAT;
import static com.vesanieminen.froniusvisualizer.util.Utils.isBetweenHours;
import static com.vesanieminen.froniusvisualizer.util.Utils.isBetweenNovAndMar;
import static com.vesanieminen.froniusvisualizer.util.Utils.isMondayToSaturday;

/**
 * Battery simulation on top of the price calculator: builds per-period purchase and sale prices with the same rules
 * as the calculator (spot, VAT history, margin, transfer product, electricity tax) and runs the battery dispatch for a
 * list of capacities.
 */
public final class BatterySimulationService {

    public static final int MAX_CAPACITIES = 8;
    public static final int DAY_AHEAD_PLAN_HOUR = 14;

    private BatterySimulationService() {
    }

    public enum TransferProduct {
        NONE, GENERAL, NIGHT, SEASONAL
    }

    /**
     * Calculator settings that make up the per-kWh prices, in c/kWh as entered in the calculator.
     */
    public record Tariff(double spotMargin, boolean vat, boolean quarterly, TransferProduct transfer,
                         double generalTransfer, double nightTransferDay, double nightTransferNight,
                         double seasonalTransferWinter, double seasonalTransferOther, boolean taxes, double taxPrice,
                         double productionMargin) {
    }

    /**
     * Metered data and prices of the calculation period. Periods without a spot price are skipped, as in the calculator.
     */
    public static BatterySimulationInput buildInput(Map<Instant, Double> consumption, Map<Instant, Double> production,
                                                    Tariff tariff, Instant from, Instant to) {
        final LinkedHashMap<Instant, Double> spot = tariff.quarterly()
                ? PriceCalculatorService.getSpotData() : PriceCalculatorService.getSpotData_60min();
        final TreeSet<Instant> keys = new TreeSet<>();
        addKeys(keys, consumption, spot, from, to);
        addKeys(keys, production, spot, from, to);
        final int n = keys.size();
        final Instant[] time = keys.toArray(new Instant[0]);
        final double[] hours = periodHours(time);
        final double[] cons = new double[n];
        final double[] prod = new double[n];
        final double[] buy = new double[n];
        final double[] sell = new double[n];
        for (int t = 0; t < n; t++) {
            final Instant instant = time[t];
            final double spotPrice = spot.get(instant);
            cons[t] = consumption.getOrDefault(instant, 0d);
            prod[t] = production == null ? 0 : production.getOrDefault(instant, 0d);
            buy[t] = spotPrice * getVAT(instant, tariff.vat()) + tariff.spotMargin() + transferPrice(instant, tariff)
                    + (tariff.taxes() ? tariff.taxPrice() * getVAT(instant) : 0);
            sell[t] = spotPrice - tariff.productionMargin();
        }
        return new BatterySimulationInput(time, hours, cons, prod, buy, sell);
    }

    private static void addKeys(TreeSet<Instant> keys, Map<Instant, Double> data, Map<Instant, Double> spot, Instant from, Instant to) {
        if (data == null) {
            return;
        }
        for (Instant instant : data.keySet()) {
            if (!instant.isBefore(from) && !instant.isAfter(to) && spot.containsKey(instant)) {
                keys.add(instant);
            }
        }
    }

    static double transferPrice(Instant instant, Tariff tariff) {
        final var entry = Map.entry(instant, 0d);
        return switch (tariff.transfer()) {
            case NONE -> 0;
            case GENERAL -> tariff.generalTransfer();
            case NIGHT -> isBetweenHours(7, 22).test(entry) ? tariff.nightTransferDay() : tariff.nightTransferNight();
            case SEASONAL -> isBetweenNovAndMar().and(isMondayToSaturday().and(isBetweenHours(7, 22))).test(entry)
                    ? tariff.seasonalTransferWinter() : tariff.seasonalTransferOther();
        };
    }

    /**
     * Period lengths from the time grid: 15 min periods where the data has them, otherwise hours.
     */
    static double[] periodHours(Instant[] time) {
        final double[] hours = new double[time.length];
        for (int t = 0; t < time.length; t++) {
            long gap = Duration.ofHours(1).toMinutes();
            if (t > 0) {
                gap = Math.min(gap, Duration.between(time[t - 1], time[t]).toMinutes());
            }
            if (t + 1 < time.length) {
                gap = Math.min(gap, Duration.between(time[t], time[t + 1]).toMinutes());
            }
            hours[t] = gap <= 15 ? 0.25 : 1;
        }
        return hours;
    }

    public record CapacityResult(double capacityKwh, BatterySimulationResult dayAhead, BatterySimulationResult fullForesight) {
    }

    /**
     * Results of all capacities against the same no-battery baseline.
     */
    public record SweepResult(BatterySimulationInput input, BatterySimulationResult baseline, List<CapacityResult> capacities) {

        /**
         * Length of the simulated period in years (for annualising).
         */
        public double years() {
            return input.totalHours() / 8760;
        }

        public double savings(BatterySimulationResult result) {
            return baseline.totalCost() - result.totalCost();
        }

        public double annualSavings(BatterySimulationResult result) {
            return savings(result) / years();
        }

        /**
         * Savings per calendar month (Finnish time); the value of the energy left at the end goes to the last month.
         */
        public Map<YearMonth, Double> monthlySavings(BatterySimulationResult result) {
            final Map<YearMonth, Double> months = new TreeMap<>();
            final Instant[] time = input.time();
            for (int t = 0; t < time.length; t++) {
                months.merge(YearMonth.from(time[t].atZone(fiZoneID)), baseline.cashCost()[t] - result.cashCost()[t], Double::sum);
            }
            if (time.length > 0) {
                months.merge(YearMonth.from(time[time.length - 1].atZone(fiZoneID)), result.terminalCredit() - baseline.terminalCredit(), Double::sum);
            }
            return months;
        }

        /**
         * Start index of the 7-day stretch where the given result saves the most (for the example week).
         */
        public int bestWeekStart(BatterySimulationResult result) {
            final Instant[] time = input.time();
            double window = 0;
            double best = Double.NEGATIVE_INFINITY;
            int bestStart = 0;
            int start = 0;
            for (int t = 0; t < time.length; t++) {
                window += baseline.cashCost()[t] - result.cashCost()[t];
                while (Duration.between(time[start], time[t]).toDays() >= 7) {
                    window -= baseline.cashCost()[start] - result.cashCost()[start];
                    start++;
                }
                if (window > best) {
                    best = window;
                    bestStart = start;
                }
            }
            return bestStart;
        }
    }

    /**
     * Simulates every capacity with day-ahead planning and with full foresight.
     *
     * @param progress  receives the completed fraction 0..1
     * @param cancelled polled between capacities and planning windows; when true the run ends with
     *                  {@link CancellationException}
     */
    public static SweepResult simulate(BatterySimulationInput input, BatteryParameters battery, List<Double> capacities,
                                       DoubleConsumer progress, BooleanSupplier cancelled) {
        if (capacities.size() > MAX_CAPACITIES) {
            throw new IllegalArgumentException("at most " + MAX_CAPACITIES + " capacities");
        }
        final var dayAhead = new PlanningHorizon.DayAhead(DAY_AHEAD_PLAN_HOUR);
        final var full = new PlanningHorizon.FullForesight();
        final var baseline = BatterySimulator.simulate(input, battery.withCapacity(0), full, fiZoneID, cancelled);
        final List<CapacityResult> results = new ArrayList<>();
        final int steps = capacities.size() * 2;
        int done = 0;
        for (double capacity : capacities) {
            final var params = battery.withCapacity(capacity);
            final var realistic = BatterySimulator.simulate(input, params, dayAhead, fiZoneID, cancelled);
            progress.accept((double) ++done / steps);
            final var upper = BatterySimulator.simulate(input, params, full, fiZoneID, cancelled);
            progress.accept((double) ++done / steps);
            results.add(new CapacityResult(capacity, realistic, upper));
        }
        return new SweepResult(input, baseline, results);
    }
}
