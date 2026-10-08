package com.vesanieminen.electricitydashboard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vesanieminen.froniusvisualizer.services.battery.BatteryParameters;
import com.vesanieminen.froniusvisualizer.services.battery.BatterySimulationInput;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Synthetic battery fixtures (src/test/resources/battery) with expected costs from an independent LP reference.
 */
final class BatteryFixtures {

    static final List<String> NAMES = List.of("week-15min-autumn-dst", "month-1h-spring-dst", "week-15min-extreme-prices");

    record Expected(double capacityKwh, String windowing, double totalCostEur) {
    }

    record Fixture(String name, BatterySimulationInput input, BatteryParameters battery, List<Expected> expected) {
    }

    private BatteryFixtures() {
    }

    static Fixture load(String name) throws IOException {
        try (InputStream in = BatteryFixtures.class.getResourceAsStream("/battery/" + name + ".json")) {
            final JsonNode root = new ObjectMapper().readTree(in);
            final int n = root.get("time").size();
            final Instant[] time = new Instant[n];
            final double[] hours = new double[n];
            for (int t = 0; t < n; t++) {
                time[t] = Instant.parse(root.get("time").get(t).asText());
                hours[t] = root.get("periodMinutes").asInt() / 60.0;
            }
            final var input = new BatterySimulationInput(time, hours, doubles(root.get("consumption")),
                    doubles(root.get("production")), doubles(root.get("buy")), doubles(root.get("sell")));
            final JsonNode b = root.get("battery");
            final var battery = BatteryParameters.of(0, b.get("powerKw").asDouble(), b.get("roundTripEfficiency").asDouble(),
                    b.get("socMinFraction").asDouble(), b.get("socMaxFraction").asDouble(),
                    b.get("cycleCostCentsPerKwh").asDouble(), true);
            final List<Expected> expected = new ArrayList<>();
            for (JsonNode e : root.get("expected")) {
                expected.add(new Expected(e.get("capacityKwh").asDouble(), e.get("windowing").asText(), e.get("totalCostEur").asDouble()));
            }
            return new Fixture(name, input, battery, expected);
        }
    }

    private static double[] doubles(JsonNode array) {
        final double[] values = new double[array.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = array.get(i).asDouble();
        }
        return values;
    }
}
