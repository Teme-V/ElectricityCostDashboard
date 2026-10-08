package com.vesanieminen.electricitydashboard;

import com.vesanieminen.froniusvisualizer.services.battery.BatteryParameters;
import com.vesanieminen.froniusvisualizer.services.battery.BatterySimulationInput;
import com.vesanieminen.froniusvisualizer.services.battery.BatterySimulator;
import com.vesanieminen.froniusvisualizer.services.battery.PlanningHorizon;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Random;

import static com.vesanieminen.froniusvisualizer.util.Utils.fiZoneID;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Slf4j
public class BatterySimulatorPerformanceTest {

    static BatterySimulationInput syntheticYear() {
        final int n = 365 * 96;
        final Random random = new Random(42);
        final Instant start = Instant.parse("2025-09-30T21:00:00Z");
        final Instant[] time = new Instant[n];
        final double[] hours = new double[n];
        final double[] cons = new double[n];
        final double[] prod = new double[n];
        final double[] buy = new double[n];
        final double[] sell = new double[n];
        double level = 0;
        for (int t = 0; t < n; t++) {
            time[t] = start.plusSeconds(900L * t);
            hours[t] = 0.25;
            final double hour = (t % 96) / 4.0;
            final double season = Math.cos((t / 96.0 - 15) / 365 * 2 * Math.PI); // 1 in winter, -1 in summer
            if (t % 96 == 0) {
                level = 0.8 * level + 3 * random.nextGaussian();
            }
            final double net = 0.25 * (1.2 + 0.8 * season + 0.5 * Math.sin((hour - 7) / 24 * 2 * Math.PI) + 0.3 * random.nextDouble()
                    - Math.max(0, Math.sin((hour - 7) / 10 * Math.PI)) * (2 - 2 * season) * random.nextDouble());
            final double spot = 6 + 3 * season + level + 5 * Math.sin((hour - 8) / 24 * 2 * Math.PI) + random.nextGaussian();
            cons[t] = Math.max(net, 0);
            prod[t] = Math.max(-net, 0);
            buy[t] = spot * 1.255 + 0.5 + 5 + 2.83;
            sell[t] = spot - 0.3;
        }
        return new BatterySimulationInput(time, hours, cons, prod, buy, sell);
    }

    /**
     * One year of 15 min data, five capacities, both planning horizons. Target: 30 s on one core.
     */
    @Test
    void yearOfQuarterHoursWithFiveCapacities() {
        final var input = syntheticYear();
        final var params = BatteryParameters.of(0, 10, 0.9, 0.1, 0.95, 1, true);
        final long start = System.nanoTime();
        for (double capacity : new double[]{5, 10, 15, 20, 30}) {
            BatterySimulator.simulate(input, params.withCapacity(capacity), new PlanningHorizon.DayAhead(14), fiZoneID);
            BatterySimulator.simulate(input, params.withCapacity(capacity), new PlanningHorizon.FullForesight(), fiZoneID);
        }
        final double seconds = (System.nanoTime() - start) / 1e9;
        log.info("battery simulation: 35040 periods x 5 capacities x 2 horizons in {} s", "%.2f".formatted(seconds));
        assertTrue(seconds < 120, "far slower than the 30 s target: " + seconds);
    }
}
