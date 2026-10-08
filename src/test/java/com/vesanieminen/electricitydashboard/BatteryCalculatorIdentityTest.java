package com.vesanieminen.electricitydashboard;

import com.vesanieminen.froniusvisualizer.services.BatterySimulationService;
import com.vesanieminen.froniusvisualizer.services.BatterySimulationService.Tariff;
import com.vesanieminen.froniusvisualizer.services.BatterySimulationService.TransferProduct;
import com.vesanieminen.froniusvisualizer.services.PriceCalculatorService;
import com.vesanieminen.froniusvisualizer.services.battery.BatteryParameters;
import com.vesanieminen.froniusvisualizer.services.battery.BatterySimulator;
import com.vesanieminen.froniusvisualizer.services.battery.PlanningHorizon;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Random;
import java.util.stream.Stream;

import static com.vesanieminen.froniusvisualizer.services.PriceCalculatorService.calculateDayPrice;
import static com.vesanieminen.froniusvisualizer.services.PriceCalculatorService.calculateElectricityTaxPrice;
import static com.vesanieminen.froniusvisualizer.services.PriceCalculatorService.calculateFixedElectricityPrice;
import static com.vesanieminen.froniusvisualizer.services.PriceCalculatorService.calculateNightPrice;
import static com.vesanieminen.froniusvisualizer.services.PriceCalculatorService.calculateSeasonalOtherPrice;
import static com.vesanieminen.froniusvisualizer.services.PriceCalculatorService.calculateSeasonalWinterPrice;
import static com.vesanieminen.froniusvisualizer.services.PriceCalculatorService.calculateSpotElectricityPriceDetails;
import static com.vesanieminen.froniusvisualizer.services.PriceCalculatorService.getFingridUsageData;
import static com.vesanieminen.froniusvisualizer.util.Utils.fiZoneID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Criterion: with capacity 0 the battery simulation costs exactly what the existing calculator reports for the same
 * inputs (spot + margin + tax + transfer - production value), for 15 min and hourly data and every transfer product.
 */
public class BatteryCalculatorIdentityTest {

    // 29.9.2025 00:00 - 5.11.2025 00:00 Finnish time: hourly spot prices before 1.10.2025, quarters after, DST change
    // on 26.10. and the start of the seasonal transfer winter period on 1.11.
    private static final Instant START = Instant.parse("2025-09-28T21:00:00Z");
    private static final Instant END = Instant.parse("2025-11-04T22:00:00Z");
    private static final Instant QUARTER_PRICES = Instant.parse("2025-09-30T21:00:00Z");

    private static String quarterConsumption;
    private static String quarterProduction;
    private static String hourConsumption;
    private static String hourProduction;

    @BeforeAll
    static void setUp(@TempDir Path dir) throws Exception {
        final Random random = new Random(7);
        final StringBuilder spot15 = new StringBuilder("{\"prices\":[");
        final StringBuilder spot60 = new StringBuilder("{\"prices\":[");
        final StringBuilder qc = header();
        final StringBuilder qp = header();
        final StringBuilder hc = header();
        final StringBuilder hp = header();
        double hourCons = 0;
        double hourProd = 0;
        for (Instant t = START; t.isBefore(END); t = t.plusSeconds(900)) {
            final int hour = t.atZone(fiZoneID).getHour();
            final boolean hourStart = t.atZone(fiZoneID).getMinute() == 0;
            final double price = 60 + 50 * Math.sin(hour / 24.0 * 2 * Math.PI) + 30 * random.nextGaussian(); // EUR/MWh
            if (hourStart) {
                spot60.append(spot60.length() > 12 ? "," : "").append(price(t, price));
            }
            if (hourStart || !t.isBefore(QUARTER_PRICES)) {
                spot15.append(spot15.length() > 12 ? "," : "").append(price(t, price + (hourStart ? 0 : 5 * random.nextGaussian())));
            }
            final double net = 0.3 + 0.2 * random.nextDouble() - (hour >= 10 && hour < 15 ? 0.6 * random.nextDouble() : 0);
            final double cons = Math.round(Math.max(net, 0) * 1000) / 1000.0;
            final double prod = Math.round(Math.max(-net, 0) * 1000) / 1000.0;
            row(qc, "PT15M", t, cons);
            row(qp, "PT15M", t, prod);
            hourCons += cons;
            hourProd += prod;
            if (t.atZone(fiZoneID).getMinute() == 45) {
                row(hc, "PT1H", t.minusSeconds(2700), hourCons);
                row(hp, "PT1H", t.minusSeconds(2700), hourProd);
                hourCons = 0;
                hourProd = 0;
            }
        }
        final Path f15 = dir.resolve("spot15.json");
        final Path f60 = dir.resolve("spot60.json");
        Files.writeString(f15, spot15.append("]}"));
        Files.writeString(f60, spot60.append("]}"));
        PriceCalculatorService.readSpotFileAndUpdateSpotData(f15.toString());
        PriceCalculatorService.readSpotFileAndUpdateSpotData_60min(f60.toString());
        quarterConsumption = qc.toString();
        quarterProduction = qp.toString();
        hourConsumption = hc.toString();
        hourProduction = hp.toString();
    }

    private static StringBuilder header() {
        return new StringBuilder("Mittauspisteen tunnus;Tuotteen tyyppi;Resoluutio;Yksikkötyyppi;Lukeman tyyppi;Alkuaika;Määrä;Laatu\n");
    }

    private static void row(StringBuilder csv, String resolution, Instant t, double kwh) {
        csv.append("0;0;").append(resolution).append(";kWh;BN01;").append(t).append(';')
                .append(String.format(Locale.FRANCE, "%.3f", kwh)).append(";OK\n");
    }

    private static String price(Instant t, double value) {
        return "{\"date\":\"%s\",\"value\":%s}".formatted(t, String.format(Locale.ROOT, "%.2f", value));
    }

    static Stream<Arguments> cases() {
        return Stream.of(TransferProduct.values()).flatMap(transfer -> Stream.of(
                Arguments.of("15 min data, 15 min prices", true, true, transfer),
                Arguments.of("15 min data, hourly prices", true, false, transfer),
                Arguments.of("hourly data", false, false, transfer)));
    }

    @ParameterizedTest(name = "{0}, {3}")
    @MethodSource("cases")
    void zeroCapacityEqualsCalculatorTotal(String name, boolean quarterData, boolean quarterly, TransferProduct transfer) throws Exception {
        final var consumption = parse(quarterData ? quarterConsumption : hourConsumption, quarterly);
        final var production = parse(quarterData ? quarterProduction : hourProduction, quarterly);
        final Instant from = consumption.keySet().iterator().next();
        final Instant to = consumption.keySet().stream().reduce((a, b) -> b).orElseThrow();
        final double margin = 0.49;
        final double productionMargin = 0.3;
        final double taxPrice = 2.253;
        final var tariff = new Tariff(margin, true, quarterly, transfer, 3.0, 4.1, 2.2, 6.73, 3.23, true, taxPrice, productionMargin);

        // the calculator's own components
        double expected = calculateSpotElectricityPriceDetails(consumption, margin, true, from, to, quarterly).totalCost
                + calculateElectricityTaxPrice(consumption, taxPrice, from, to)
                - calculateSpotElectricityPriceDetails(production, -productionMargin, false, from, to, quarterly).totalCost;
        expected += switch (transfer) {
            case NONE -> 0;
            case GENERAL -> calculateFixedElectricityPrice(consumption, 3.0, from, to);
            case NIGHT -> calculateDayPrice(consumption, 4.1, from, to) + calculateNightPrice(consumption, 2.2, from, to);
            case SEASONAL -> calculateSeasonalWinterPrice(consumption, 6.73, from, to) + calculateSeasonalOtherPrice(consumption, 3.23, from, to);
        };

        assertTrue(expected > 50, "non-trivial total: " + expected);

        final var input = BatterySimulationService.buildInput(consumption, production, tariff, from, to);
        assertTrue(input.size() > 800);
        final var params = BatteryParameters.of(0, 10, 0.9, 0.1, 0.95, 1, true);
        for (PlanningHorizon horizon : new PlanningHorizon[]{new PlanningHorizon.DayAhead(14), new PlanningHorizon.FullForesight()}) {
            final var result = BatterySimulator.simulate(input, params, horizon, fiZoneID);
            assertEquals(expected, result.totalCost(), 0.01);
        }
    }

    private static LinkedHashMap<Instant, Double> parse(String csv, boolean quarterly) throws Exception {
        return getFingridUsageData(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), quarterly).data();
    }
}
