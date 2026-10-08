package com.vesanieminen.froniusvisualizer.services.battery;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * How the battery is planned: which periods each optimisation sees and which of them it commits.
 */
public sealed interface PlanningHorizon {

    /**
     * One optimisation over the whole period: the theoretical upper bound with perfect price knowledge.
     */
    record FullForesight() implements PlanningHorizon {
    }

    /**
     * Realistic day-ahead operation: a plan is made every day at {@code planHour} local time when the next day's spot
     * prices are known. It covers the periods until the end of the next day and is followed until the next plan.
     */
    record DayAhead(int planHour) implements PlanningHorizon {
    }

    /**
     * Windows starting at local midnight, {@code windowDays} days long, of which the first day is committed.
     */
    record DailyRolling(int windowDays) implements PlanningHorizon {
    }

    /**
     * Planning windows over periods {@code [from, to)}; periods {@code [from, commitTo)} are committed.
     * The window with {@code terminal} values energy left at the end of the whole simulation.
     */
    record Window(int from, int to, int commitTo, boolean terminal) {
    }

    static List<Window> windows(PlanningHorizon horizon, Instant[] time, ZoneId zone) {
        final int n = time.length;
        final List<Window> windows = new ArrayList<>();
        if (n == 0) {
            return windows;
        }
        final List<Integer> dayStarts = dayStarts(time, zone);
        switch (horizon) {
            case FullForesight ignored -> windows.add(new Window(0, n, n, true));
            case DailyRolling rolling -> {
                final int days = dayStarts.size() - 1;
                for (int d = 0; d < days; d++) {
                    windows.add(new Window(dayStarts.get(d), dayStarts.get(Math.min(d + rolling.windowDays(), days)),
                            dayStarts.get(d + 1), d == days - 1));
                }
            }
            case DayAhead dayAhead -> {
                final List<Integer> plans = new ArrayList<>();
                plans.add(0);
                for (int d = 0; d + 1 < dayStarts.size(); d++) {
                    if (d == 0 && time[0].atZone(zone).getHour() >= dayAhead.planHour()) {
                        continue; // the first plan at the start already knows the next day's prices
                    }
                    for (int t = Math.max(dayStarts.get(d), 1); t < dayStarts.get(d + 1); t++) {
                        if (time[t].atZone(zone).getHour() >= dayAhead.planHour()) {
                            plans.add(t);
                            break;
                        }
                    }
                }
                for (int k = 0; k < plans.size(); k++) {
                    final int from = plans.get(k);
                    final int commitTo = k + 1 < plans.size() ? plans.get(k + 1) : n;
                    final ZonedDateTime planTime = time[from].atZone(zone);
                    final LocalDate lastKnownDay = planTime.getHour() >= dayAhead.planHour()
                            ? planTime.toLocalDate().plusDays(1) : planTime.toLocalDate();
                    final int to = Math.max(firstIndexOnOrAfter(time, lastKnownDay.plusDays(1).atStartOfDay(zone).toInstant()), commitTo);
                    windows.add(new Window(from, to, commitTo, commitTo == n));
                }
            }
        }
        return windows;
    }

    /**
     * Indices where a new local day starts, plus {@code n} at the end.
     */
    static List<Integer> dayStarts(Instant[] time, ZoneId zone) {
        final List<Integer> starts = new ArrayList<>();
        LocalDate previous = null;
        for (int t = 0; t < time.length; t++) {
            final LocalDate day = time[t].atZone(zone).toLocalDate();
            if (!day.equals(previous)) {
                starts.add(t);
                previous = day;
            }
        }
        starts.add(time.length);
        return starts;
    }

    private static int firstIndexOnOrAfter(Instant[] time, Instant instant) {
        int lo = 0;
        int hi = time.length;
        while (lo < hi) {
            final int mid = (lo + hi) >>> 1;
            if (time[mid].isBefore(instant)) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }
}
