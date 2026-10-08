package com.vesanieminen.froniusvisualizer.components;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.charts.Chart;
import com.vaadin.flow.component.charts.model.ChartType;
import com.vaadin.flow.component.charts.model.DataSeries;
import com.vaadin.flow.component.charts.model.DataSeriesItem;
import com.vaadin.flow.component.charts.model.ListSeries;
import com.vaadin.flow.component.charts.model.PlotOptionsColumn;
import com.vaadin.flow.component.charts.model.PlotOptionsColumnrange;
import com.vaadin.flow.component.charts.model.PlotOptionsLine;
import com.vaadin.flow.component.charts.model.RangeSeries;
import com.vaadin.flow.component.charts.model.Tooltip;
import com.vaadin.flow.component.charts.model.XAxis;
import com.vaadin.flow.component.charts.model.YAxis;
import com.vaadin.flow.component.charts.model.AxisType;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.ListItem;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.html.UnorderedList;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.page.WebStorage;
import com.vaadin.flow.component.progressbar.ProgressBar;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.theme.lumo.LumoUtility;
import com.vesanieminen.froniusvisualizer.services.BatterySimulationService;
import com.vesanieminen.froniusvisualizer.services.BatterySimulationService.CapacityResult;
import com.vesanieminen.froniusvisualizer.services.BatterySimulationService.SweepResult;
import com.vesanieminen.froniusvisualizer.services.BatterySimulationService.TransferProduct;
import com.vesanieminen.froniusvisualizer.services.battery.BatteryParameters;
import com.vesanieminen.froniusvisualizer.services.battery.BatterySimulationInput;
import com.vesanieminen.froniusvisualizer.services.battery.BatterySimulationResult;
import lombok.extern.slf4j.Slf4j;
import org.vaadin.miki.superfields.numbers.SuperDoubleField;

import java.text.NumberFormat;
import java.time.Instant;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.vesanieminen.froniusvisualizer.util.Utils.fiZoneID;
import static com.vesanieminen.froniusvisualizer.util.Utils.getNumberFormat;

/**
 * Battery section of the price calculator: battery parameters, an asynchronous simulation run and its results.
 * The uploaded data is only kept in memory for the duration of the run.
 */
@Slf4j
public class BatterySimulationSection extends Div {

    private static final long TIMEOUT_SECONDS = 60;
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2, runnable -> {
        final Thread thread = new Thread(runnable, "battery-simulation");
        thread.setDaemon(true);
        return thread;
    });

    private final TextField capacitiesField = new TextField();
    private final SuperDoubleField powerField = new SuperDoubleField();
    private final SuperDoubleField efficiencyField = new SuperDoubleField();
    private final SuperDoubleField saleMarginField = new SuperDoubleField();
    private final SuperDoubleField cycleCostField = new SuperDoubleField();
    private final SuperDoubleField priceField = new SuperDoubleField();
    private final SuperDoubleField holdingPeriodField = new SuperDoubleField();
    private final Checkbox batteryExportCheckbox = new Checkbox();
    private final Select<TransferProduct> transferSelect = new Select<>();
    private final Div results = new Div();
    private final ProgressBar progressBar = new ProgressBar(0, 1);
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final Div resultsComponent = new Div(progressBar, results);

    public BatterySimulationSection() {
        addClassNames(LumoUtility.Display.FLEX, LumoUtility.FlexDirection.COLUMN);
        final var inputs = new Div();
        inputs.addClassNames(LumoUtility.Display.FLEX, LumoUtility.Gap.Column.MEDIUM, LumoUtility.FlexWrap.WRAP);

        capacitiesField.setId("battery.capacities");
        capacitiesField.setLabel(getTranslation("battery.capacities"));
        capacitiesField.setHelperText(getTranslation("battery.capacities.helper", BatterySimulationService.MAX_CAPACITIES));
        capacitiesField.setValue("5, 10, 15, 20, 30");
        capacitiesField.setSuffixComponent(new Span("kWh"));
        configure(powerField, "battery.power", "battery.power.helper", "kW", 10);
        configure(efficiencyField, "battery.efficiency", "battery.efficiency.helper", "%", 90);
        configure(saleMarginField, "battery.sale-margin", "battery.sale-margin.helper", getTranslation("c/kWh"), 0.3);
        configure(cycleCostField, "battery.cycle-cost", "battery.cycle-cost.helper", getTranslation("c/kWh"), 1);
        configure(priceField, "battery.price", "battery.price.helper", "€/kWh", 400);
        configure(holdingPeriodField, "battery.holding-period", "battery.holding-period.helper", getTranslation("battery.years"), 15);
        transferSelect.setLabel(getTranslation("battery.transfer"));
        transferSelect.setHelperText(getTranslation("battery.transfer.helper"));
        transferSelect.setItemLabelGenerator(item -> getTranslation("battery.transfer." + item.name().toLowerCase()));
        transferSelect.setItems(TransferProduct.NONE);
        transferSelect.setValue(TransferProduct.NONE);
        batteryExportCheckbox.setId("battery.export");
        batteryExportCheckbox.setLabel(getTranslation("battery.export"));
        batteryExportCheckbox.addValueChangeListener(e -> save(batteryExportCheckbox.getId().orElseThrow(), String.valueOf(e.getValue())));
        capacitiesField.addValueChangeListener(e -> save(capacitiesField.getId().orElseThrow(), e.getValue()));
        inputs.add(capacitiesField, powerField, efficiencyField, saleMarginField, cycleCostField, priceField, holdingPeriodField, transferSelect);

        final var productionNote = new Span(getTranslation("battery.production.note"));
        productionNote.addClassNames(LumoUtility.FontSize.SMALL, LumoUtility.TextColor.SECONDARY);
        add(inputs, batteryExportCheckbox, productionNote);

        progressBar.setVisible(false);
        results.addClassNames(LumoUtility.Display.FLEX, LumoUtility.FlexDirection.COLUMN);
        resultsComponent.addClassNames(LumoUtility.Display.FLEX, LumoUtility.FlexDirection.COLUMN);
        resultsComponent.setWidthFull();
    }

    /**
     * Progress and results, to be placed with the calculator's other results.
     */
    public Div getResultsComponent() {
        return resultsComponent;
    }

    private void configure(SuperDoubleField field, String label, String helper, String suffix, double defaultValue) {
        field.setId(label);
        field.setLocale(getLocale());
        field.setLabel(getTranslation(label));
        field.setHelperText(getTranslation(helper));
        field.setSuffixComponent(new Span(suffix));
        field.setValue(defaultValue);
        field.addValueChangeListener(e -> {
            if (e.getValue() != null) {
                save(label, String.valueOf(e.getValue()));
            }
        });
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        cancelled.set(false);
        for (SuperDoubleField field : List.of(powerField, efficiencyField, saleMarginField, cycleCostField, priceField, holdingPeriodField)) {
            WebStorage.getItem(field.getId().orElseThrow(), value -> {
                try {
                    if (value != null) {
                        field.setValue(Double.parseDouble(value));
                    }
                } catch (NumberFormatException ignored) {
                    // keep the default
                }
            });
        }
        WebStorage.getItem(capacitiesField.getId().orElseThrow(), value -> {
            if (value != null) {
                capacitiesField.setValue(value);
            }
        });
        WebStorage.getItem(batteryExportCheckbox.getId().orElseThrow(), value -> batteryExportCheckbox.setValue(Boolean.parseBoolean(value)));
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        cancelled.set(true);
    }

    private static void save(String key, String value) {
        WebStorage.setItem(key, value);
    }

    /**
     * Transfer products available for the simulation: the ones selected in the calculator (or none).
     */
    public void setTransferProducts(Collection<TransferProduct> selected) {
        final var items = new ArrayList<TransferProduct>(selected);
        items.add(TransferProduct.NONE);
        final var current = transferSelect.getValue();
        transferSelect.setItems(items);
        transferSelect.setValue(items.contains(current) && current != TransferProduct.NONE ? current : items.getFirst());
    }

    public TransferProduct getTransferProduct() {
        return transferSelect.getValue() == null ? TransferProduct.NONE : transferSelect.getValue();
    }

    public double getSaleMargin() {
        return valueOrZero(saleMarginField);
    }

    private static double valueOrZero(SuperDoubleField field) {
        return field.getValue() == null ? 0 : field.getValue();
    }

    /**
     * Capacities separated by spaces, semicolons or ", "; a single comma inside a number is a decimal separator.
     */
    public static List<Double> parseCapacities(String text) {
        final List<Double> capacities = new ArrayList<>();
        for (String token : text.replace(", ", " ").replace(';', ' ').trim().split("\\s+")) {
            if (token.isBlank()) {
                continue;
            }
            final String[] parts = token.chars().filter(ch -> ch == ',').count() > 1 ? token.split(",") : new String[]{token};
            for (String part : parts) {
                final double value = Double.parseDouble(part.replace(',', '.'));
                if (value > 0 && !capacities.contains(value)) {
                    capacities.add(value);
                }
            }
        }
        capacities.sort(Double::compare);
        return capacities;
    }

    /**
     * Runs the simulation in the background and shows the results when done.
     */
    public void simulate(BatterySimulationInput input) {
        final List<Double> capacities;
        try {
            capacities = parseCapacities(capacitiesField.getValue());
        } catch (NumberFormatException e) {
            capacitiesField.setInvalid(true);
            capacitiesField.setErrorMessage(getTranslation("battery.capacities.invalid"));
            return;
        }
        if (capacities.isEmpty() || capacities.size() > BatterySimulationService.MAX_CAPACITIES) {
            capacitiesField.setInvalid(true);
            capacitiesField.setErrorMessage(getTranslation("battery.capacities.helper", BatterySimulationService.MAX_CAPACITIES));
            return;
        }
        capacitiesField.setInvalid(false);
        final var params = BatteryParameters.of(0, valueOrZero(powerField), Math.min(Math.max(valueOrZero(efficiencyField), 1), 100) / 100,
                0.10, 0.95, valueOrZero(cycleCostField), batteryExportCheckbox.getValue());
        final var ui = UI.getCurrent();
        results.removeAll();
        progressBar.setValue(0);
        progressBar.setVisible(true);
        ui.setPollInterval(500);
        final long deadline = System.nanoTime() + TIMEOUT_SECONDS * 1_000_000_000L;
        final long started = System.nanoTime();
        EXECUTOR.submit(() -> {
            try {
                final SweepResult sweep = BatterySimulationService.simulate(input, params, capacities,
                        fraction -> ui.access(() -> progressBar.setValue(fraction)),
                        () -> cancelled.get() || System.nanoTime() > deadline);
                log.info("battery simulation: {} periods, {} capacities in {} ms", input.size(), capacities.size(),
                        (System.nanoTime() - started) / 1_000_000);
                ui.access(() -> {
                    finish(ui);
                    showResults(sweep);
                });
            } catch (CancellationException e) {
                ui.access(() -> {
                    finish(ui);
                    if (!cancelled.get()) {
                        Notification.show(getTranslation("battery.timeout", TIMEOUT_SECONDS));
                    }
                });
            } catch (RuntimeException e) {
                log.error("battery simulation failed", e);
                ui.access(() -> {
                    finish(ui);
                    Notification.show(getTranslation("battery.failed"));
                });
            }
        });
    }

    private void finish(UI ui) {
        progressBar.setVisible(false);
        ui.setPollInterval(-1);
    }

    private void showResults(SweepResult sweep) {
        final NumberFormat zero = getNumberFormat(getLocale(), 0);
        final NumberFormat one = getNumberFormat(getLocale(), 1);
        results.removeAll();
        results.add(header("battery.results"));
        final var period = new Span(getTranslation("battery.results.period", one.format(sweep.years() * 365),
                zero.format(sweep.baseline().totalCost()), zero.format(sweep.baseline().totalCost() / sweep.years())));
        period.addClassNames(LumoUtility.TextColor.SECONDARY, LumoUtility.FontSize.SMALL);
        results.add(period);

        final double price = valueOrZero(priceField);
        final double holding = valueOrZero(holdingPeriodField);
        final Grid<CapacityResult> grid = new Grid<>();
        grid.addThemeVariants(GridVariant.LUMO_NO_BORDER, GridVariant.LUMO_COMPACT);
        grid.setAllRowsVisible(true);
        grid.addColumn(r -> zero.format(r.capacityKwh()) + " kWh").setHeader(getTranslation("battery.capacity")).setAutoWidth(true);
        grid.addColumn(r -> range(zero, sweep.annualSavings(r.dayAhead()), sweep.annualSavings(r.fullForesight())) + " €")
                .setHeader(getTranslation("battery.savings.per.year")).setAutoWidth(true);
        grid.addColumn(r -> payback(one, price * r.capacityKwh(), sweep.annualSavings(r.fullForesight()), sweep.annualSavings(r.dayAhead())))
                .setHeader(getTranslation("battery.payback")).setAutoWidth(true);
        grid.addColumn(r -> range(zero, sweep.annualSavings(r.dayAhead()) * holding - price * r.capacityKwh(),
                sweep.annualSavings(r.fullForesight()) * holding - price * r.capacityKwh()) + " €")
                .setHeader(getTranslation("battery.net.benefit")).setAutoWidth(true);
        grid.addColumn(r -> zero.format(r.dayAhead().dischargedEnergy() / sweep.years() / Math.max(r.capacityKwh(), 1e-9)))
                .setHeader(getTranslation("battery.cycles")).setAutoWidth(true);
        grid.setItems(sweep.capacities());
        results.add(grid);
        final var legend = new Span(getTranslation("battery.range.legend"));
        legend.addClassNames(LumoUtility.TextColor.SECONDARY, LumoUtility.FontSize.SMALL);
        results.add(legend, savingsChart(sweep));

        final Select<CapacityResult> capacitySelect = new Select<>();
        capacitySelect.setLabel(getTranslation("battery.capacity"));
        capacitySelect.setItems(sweep.capacities());
        capacitySelect.setItemLabelGenerator(r -> zero.format(r.capacityKwh()) + " kWh");
        final var details = new Div();
        capacitySelect.addValueChangeListener(e -> {
            details.removeAll();
            if (e.getValue() != null) {
                details.add(monthlyChart(sweep, e.getValue()), weekChart(sweep, e.getValue()));
            }
        });
        results.add(header("battery.details"), capacitySelect, details);
        capacitySelect.setValue(sweep.capacities().getLast());
        results.add(limitations());
    }

    private static String range(NumberFormat format, double low, double high) {
        return "%s … %s".formatted(format.format(Math.min(low, high)), format.format(Math.max(low, high)));
    }

    private String payback(NumberFormat format, double investment, double bestSavings, double worstSavings) {
        if (investment <= 0) {
            return "-";
        }
        if (bestSavings <= 0) {
            return getTranslation("battery.payback.never");
        }
        final String best = format.format(investment / bestSavings);
        final String worst = worstSavings > 0 ? format.format(investment / worstSavings) : "∞";
        return "%s … %s %s".formatted(best, worst, getTranslation("battery.years"));
    }

    private H2 header(String key) {
        final var h2 = new H2(getTranslation(key));
        h2.addClassNames(LumoUtility.FontSize.LARGE, LumoUtility.Margin.Top.MEDIUM);
        return h2;
    }

    private Chart savingsChart(SweepResult sweep) {
        final var chart = new Chart(ChartType.COLUMNRANGE);
        final var conf = chart.getConfiguration();
        conf.getChart().setStyledMode(true);
        conf.setTitle(getTranslation("battery.chart.savings"));
        final var xAxis = new XAxis();
        xAxis.setCategories(sweep.capacities().stream().map(r -> getNumberFormat(getLocale(), 0).format(r.capacityKwh()) + " kWh").toArray(String[]::new));
        conf.addxAxis(xAxis);
        final var yAxis = new YAxis();
        yAxis.setTitle("€ / " + getTranslation("battery.year"));
        conf.addyAxis(yAxis);
        final var tooltip = new Tooltip();
        tooltip.setValueDecimals(0);
        tooltip.setValueSuffix(" €");
        conf.setTooltip(tooltip);
        final var series = new RangeSeries(getTranslation("battery.range"));
        for (int i = 0; i < sweep.capacities().size(); i++) {
            final CapacityResult r = sweep.capacities().get(i);
            series.add(new DataSeriesItem(i, sweep.annualSavings(r.dayAhead()), sweep.annualSavings(r.fullForesight())));
        }
        series.setPlotOptions(new PlotOptionsColumnrange());
        conf.addSeries(series);
        return chart;
    }

    private Chart monthlyChart(SweepResult sweep, CapacityResult result) {
        final var chart = new Chart(ChartType.COLUMN);
        final var conf = chart.getConfiguration();
        conf.getChart().setStyledMode(true);
        conf.setTitle(getTranslation("battery.chart.monthly", getNumberFormat(getLocale(), 0).format(result.capacityKwh())));
        final Map<YearMonth, Double> dayAhead = sweep.monthlySavings(result.dayAhead());
        final Map<YearMonth, Double> full = sweep.monthlySavings(result.fullForesight());
        final var xAxis = new XAxis();
        xAxis.setCategories(dayAhead.keySet().stream()
                .map(m -> "%s %d".formatted(m.getMonth().getDisplayName(TextStyle.SHORT_STANDALONE, getLocale()), m.getYear() % 100))
                .toArray(String[]::new));
        conf.addxAxis(xAxis);
        final var yAxis = new YAxis();
        yAxis.setTitle("€");
        conf.addyAxis(yAxis);
        final var tooltip = new Tooltip();
        tooltip.setShared(true);
        tooltip.setValueDecimals(2);
        tooltip.setValueSuffix(" €");
        conf.setTooltip(tooltip);
        final var dayAheadSeries = new ListSeries(getTranslation("battery.day-ahead"), dayAhead.values().toArray(Number[]::new));
        final var fullSeries = new ListSeries(getTranslation("battery.full-foresight"), full.values().toArray(Number[]::new));
        dayAheadSeries.setPlotOptions(new PlotOptionsColumn());
        fullSeries.setPlotOptions(new PlotOptionsColumn());
        conf.addSeries(dayAheadSeries);
        conf.addSeries(fullSeries);
        return chart;
    }

    private Div weekChart(SweepResult sweep, CapacityResult result) {
        final BatterySimulationResult r = result.dayAhead();
        final BatterySimulationInput input = sweep.input();
        final int start = sweep.bestWeekStart(r);
        final Instant end = input.time()[start].plusSeconds(7 * 24 * 3600);
        final var chart = new Chart(ChartType.LINE);
        final var conf = chart.getConfiguration();
        conf.getChart().setStyledMode(true);
        conf.setTitle(getTranslation("battery.chart.week"));
        final var xAxis = new XAxis();
        xAxis.setType(AxisType.DATETIME);
        conf.addxAxis(xAxis);
        final var energyAxis = new YAxis();
        energyAxis.setTitle("kWh");
        conf.addyAxis(energyAxis);
        final var priceAxis = new YAxis();
        priceAxis.setTitle(getTranslation("c/kWh"));
        priceAxis.setOpposite(true);
        conf.addyAxis(priceAxis);
        final var tooltip = new Tooltip();
        tooltip.setShared(true);
        tooltip.setValueDecimals(2);
        tooltip.setXDateFormat("%a %d.%m. %H:%M");
        conf.setTooltip(tooltip);
        final var soc = new DataSeries(getTranslation("battery.soc"));
        final var grid = new DataSeries(getTranslation("battery.grid"));
        final var noBattery = new DataSeries(getTranslation("battery.grid.without"));
        final var buy = new DataSeries(getTranslation("battery.buy-price"));
        final var offset = (long) input.time()[start].atZone(fiZoneID).getOffset().getTotalSeconds() * 1000;
        for (int t = start; t < input.size() && input.time()[t].isBefore(end); t++) {
            final long x = input.time()[t].toEpochMilli() + offset; // charts show UTC: shift to Finnish wall time
            final double perHour = 1 / input.hours()[t];
            soc.add(new DataSeriesItem(x, r.soc()[t]));
            grid.add(new DataSeriesItem(x, (r.gridImport()[t] - r.gridExport()[t]) * perHour));
            noBattery.add(new DataSeriesItem(x, (input.consumption()[t] - input.production()[t]) * perHour));
            buy.add(new DataSeriesItem(x, input.buy()[t]));
        }
        for (DataSeries series : List.of(soc, grid, noBattery)) {
            final var options = new PlotOptionsLine();
            options.setMarker(new com.vaadin.flow.component.charts.model.Marker(false));
            series.setPlotOptions(options);
            conf.addSeries(series);
        }
        final var priceOptions = new PlotOptionsLine();
        priceOptions.setMarker(new com.vaadin.flow.component.charts.model.Marker(false));
        buy.setPlotOptions(priceOptions);
        conf.addSeries(buy);
        buy.setyAxis(priceAxis);
        noBattery.setVisible(false);
        final var note = new Span(getTranslation("battery.chart.week.note"));
        note.addClassNames(LumoUtility.TextColor.SECONDARY, LumoUtility.FontSize.SMALL);
        return new Div(chart, note);
    }

    private Div limitations() {
        final var list = new UnorderedList();
        for (String key : List.of("netted", "within-period", "phases", "known-load", "full-foresight", "history", "average-power", "model")) {
            list.add(new ListItem(getTranslation("battery.limitation." + key)));
        }
        final var title = new Span(getTranslation("battery.limitations"));
        title.addClassNames(LumoUtility.FontWeight.SEMIBOLD);
        final var div = new Div(title, list);
        div.addClassNames(LumoUtility.FontSize.SMALL, LumoUtility.TextColor.SECONDARY, LumoUtility.Margin.Top.MEDIUM);
        return div;
    }
}
