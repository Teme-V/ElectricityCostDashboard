package com.vesanieminen.electricitydashboard;

import com.vesanieminen.froniusvisualizer.services.battery.BatteryParameters;
import com.vesanieminen.froniusvisualizer.services.battery.BatterySimulationInput;
import com.vesanieminen.froniusvisualizer.services.battery.BatterySimulationResult;
import com.vesanieminen.froniusvisualizer.services.battery.BatterySimulator;
import com.vesanieminen.froniusvisualizer.services.battery.PlanningHorizon;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static com.vesanieminen.froniusvisualizer.util.Utils.fiZoneID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BatterySimulatorTest {

    private static final double TOL = 1e-7;

    private static PlanningHorizon horizon(String windowing) {
        return switch (windowing) {
            case "single" -> new PlanningHorizon.FullForesight();
            case "daily2" -> new PlanningHorizon.DailyRolling(2);
            default -> throw new IllegalArgumentException(windowing);
        };
    }

    /**
     * Same cost as the reference LP within 0.1 % or 0.01 EUR, whichever is larger.
     */
    @TestFactory
    List<DynamicTest> goldenCostsMatchReference() throws IOException {
        final List<DynamicTest> tests = new ArrayList<>();
        for (String name : BatteryFixtures.NAMES) {
            final var fixture = BatteryFixtures.load(name);
            for (var expected : fixture.expected()) {
                tests.add(DynamicTest.dynamicTest("%s C=%s %s".formatted(name, expected.capacityKwh(), expected.windowing()), () -> {
                    final var result = BatterySimulator.simulate(fixture.input(), fixture.battery().withCapacity(expected.capacityKwh()),
                            horizon(expected.windowing()), fiZoneID);
                    final double limit = Math.max(0.001 * Math.abs(expected.totalCostEur()), 0.01);
                    assertEquals(expected.totalCostEur(), result.totalCost(), limit);
                }));
            }
        }
        return tests;
    }

    @TestFactory
    List<DynamicTest> invariantsHold() throws IOException {
        final List<DynamicTest> tests = new ArrayList<>();
        for (String name : BatteryFixtures.NAMES) {
            final var fixture = BatteryFixtures.load(name);
            for (double capacity : new double[]{0, 5, 15, 30}) {
                for (boolean export : new boolean[]{true, false}) {
                    tests.add(DynamicTest.dynamicTest("%s C=%s export=%s".formatted(name, capacity, export), () -> {
                        final var b = fixture.battery();
                        final var params = BatteryParameters.of(capacity, b.powerKw(), b.roundTripEfficiency(), b.socMinFraction(),
                                b.socMaxFraction(), b.cycleCostCentsPerKwh(), export);
                        final var dayAhead = BatterySimulator.simulate(fixture.input(), params, new PlanningHorizon.DayAhead(14), fiZoneID);
                        final var full = BatterySimulator.simulate(fixture.input(), params, new PlanningHorizon.FullForesight(), fiZoneID);
                        final var none = BatterySimulator.simulate(fixture.input(), params.withCapacity(0), new PlanningHorizon.FullForesight(), fiZoneID);
                        assertPhysical(fixture.input(), params, dayAhead);
                        assertPhysical(fixture.input(), params, full);
                        // perfect foresight is an upper bound of the savings and any plan is at least as good as no battery
                        assertTrue(full.totalCost() <= dayAhead.totalCost() + 1e-6, "full foresight <= day-ahead");
                        assertTrue(dayAhead.totalCost() <= none.totalCost() + 1e-6, "day-ahead <= no battery");
                    }));
                }
            }
        }
        return tests;
    }

    private static void assertPhysical(BatterySimulationInput input, BatteryParameters params, BatterySimulationResult r) {
        final double eta = params.oneWayEfficiency();
        double previous = params.socMinKwh();
        for (int t = 0; t < input.size(); t++) {
            final double limit = params.energyLimit(input.hours()[t]);
            final double c = r.charge()[t];
            final double d = r.discharge()[t];
            final double a = input.consumption()[t];
            final double b = input.production()[t];
            final double gi = r.gridImport()[t];
            final double ge = r.gridExport()[t];
            assertTrue(r.soc()[t] >= params.socMinKwh() - TOL && r.soc()[t] <= params.socMaxKwh() + TOL, "SoC within bounds");
            assertTrue(c >= -TOL && d >= -TOL && c <= limit + TOL && d <= limit + TOL && c + d <= limit + TOL, "converter limit");
            assertEquals(previous + eta * c - d / eta, r.soc()[t], 1e-6, "SoC dynamics");
            assertEquals(a - b + c - d, gi - ge, 1e-6, "energy balance");
            assertTrue(gi >= -TOL && ge >= -TOL, "non-negative grid flows");
            // no import and export in the same period beyond what the metered data already contains
            assertTrue(Math.min(gi, ge) <= Math.min(a, b) + 1e-6, "no simultaneous import and export");
            if (!params.allowBatteryExport()) {
                assertTrue(ge <= b + 1e-6 && d <= a + 1e-6, "no export from the battery");
            }
            previous = r.soc()[t];
        }
    }

    /**
     * Without a battery the simulation reproduces metered import and export exactly, also when an aggregated period
     * contains both consumption and production.
     */
    @Test
    void zeroCapacityKeepsMeteredFlows() {
        final Instant start = Instant.parse("2025-10-01T00:00:00Z");
        final int n = 48;
        final Instant[] time = new Instant[n];
        final double[] hours = new double[n];
        final double[] cons = new double[n];
        final double[] prod = new double[n];
        final double[] buy = new double[n];
        final double[] sell = new double[n];
        for (int t = 0; t < n; t++) {
            time[t] = start.plusSeconds(3600L * t);
            hours[t] = 1;
            cons[t] = 0.5 + (t % 5) * 0.1;
            prod[t] = t % 3 == 0 ? 0.7 : 0;
            buy[t] = 10 + (t % 7);
            sell[t] = t % 11 == 0 ? 30 : 4; // sale above purchase price must not create artificial flows
        }
        final var input = new BatterySimulationInput(time, hours, cons, prod, buy, sell);
        final var params = BatteryParameters.of(0, 10, 0.9, 0.1, 0.95, 1, true);
        final var result = BatterySimulator.simulate(input, params, new PlanningHorizon.DayAhead(14), fiZoneID);
        double expected = 0;
        for (int t = 0; t < n; t++) {
            assertEquals(cons[t], result.gridImport()[t], 1e-12);
            assertEquals(prod[t], result.gridExport()[t], 1e-12);
            expected += (buy[t] * cons[t] - sell[t] * prod[t]) / 100;
        }
        assertEquals(expected, result.totalCost(), 1e-9);
    }

    @Test
    void dayAheadPlansAtTwoPmUntilEndOfNextDay() {
        final Instant start = Instant.parse("2025-10-19T21:00:00Z"); // 20.10.2025 00:00 Finnish time
        final Instant[] time = new Instant[24 * 4];
        for (int t = 0; t < time.length; t++) {
            time[t] = start.plusSeconds(3600L * t);
        }
        final var windows = PlanningHorizon.windows(new PlanningHorizon.DayAhead(14), time, fiZoneID);
        // start 00:00: prices known until the end of the day, plan followed until 14:00
        assertEquals(new PlanningHorizon.Window(0, 24, 14, false), windows.get(0));
        // 14:00: next day's prices known, plan until the end of the next day, followed until the next 14:00
        assertEquals(new PlanningHorizon.Window(14, 48, 38, false), windows.get(1));
        assertEquals(new PlanningHorizon.Window(62, 96, 86, false), windows.get(3));
        // only the plan that runs to the end of the data values the energy left in the battery
        assertEquals(new PlanningHorizon.Window(86, 96, 96, true), windows.get(4));
        assertEquals(5, windows.size());
    }
}
