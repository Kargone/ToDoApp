package com.karson.todoapp.logic;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** Pure date helpers. Port of the date utilities in script.mjs. */
public final class Dates {
    private Dates() {}

    /** Today as an ISO date string, "yyyy-MM-dd". */
    public static String todayIso() {
        return LocalDate.now().toString();
    }

    public static String todayIso(LocalDate now) {
        return now.toString();
    }

    /** Add {@code interval} units of day/week/month/year to an ISO date. */
    public static String addInterval(String dateIso, int interval, String unit) {
        LocalDate date = LocalDate.parse(dateIso);
        switch (unit) {
            case "week":
                date = date.plusWeeks(interval);
                break;
            case "month":
                date = date.plusMonths(interval);
                break;
            case "year":
                date = date.plusYears(interval);
                break;
            default:
                date = date.plusDays(interval);
        }
        return date.toString();
    }

    /** Whole days from today to the given ISO date (negative if in the past). */
    public static long dayDifference(String dateIso, LocalDate now) {
        return ChronoUnit.DAYS.between(now, LocalDate.parse(dateIso));
    }

    public static long daysBetween(String startIso, String endIso) {
        return ChronoUnit.DAYS.between(LocalDate.parse(startIso), LocalDate.parse(endIso));
    }

    /** First 10 chars of a value, or today if blank — mirrors normalizeDate(). */
    public static String normalizeDate(String value) {
        if (value == null || value.isEmpty()) return todayIso();
        return value.length() > 10 ? value.substring(0, 10) : value;
    }
}
