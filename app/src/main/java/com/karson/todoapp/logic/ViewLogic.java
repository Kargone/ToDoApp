package com.karson.todoapp.logic;

import com.karson.todoapp.model.Item;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/** Pure display helpers. Port of view-logic.mjs. No Android/UI dependencies. */
public final class ViewLogic {
    private ViewLogic() {}

    /** Colour state for a row's time cell. */
    public enum TimeState { NORMAL, SOON, LATE }

    private static final DateTimeFormatter MONTH_DAY =
            DateTimeFormatter.ofPattern("MMM d", Locale.US);

    public static boolean isOverdue(Item item, LocalDateTime now) {
        if (item.completed || item.isVirtual || item.noDue) return false;
        String dateIso = item.itemDate();
        if (dateIso == null || dateIso.isEmpty()) return false;

        String todayIso = now.toLocalDate().toString();
        if (dateIso.compareTo(todayIso) < 0) return true;
        if (dateIso.compareTo(todayIso) > 0) return false;

        String time = item.itemTime();
        if (time == null || time.isEmpty()) return false;
        return LocalDateTime.of(LocalDate.parse(dateIso), LocalTime.parse(time)).isBefore(now);
    }

    /** Due within the next four hours. Mirrors isItemUrgent(). */
    public static boolean isUrgent(Item item, LocalDateTime now) {
        if (item.completed || item.isVirtual) return false;
        String dateIso = item.itemDate();
        String time = item.itemTime();
        if (dateIso == null || dateIso.isEmpty() || time == null || time.isEmpty()) return false;
        LocalDateTime target = LocalDateTime.of(LocalDate.parse(dateIso), LocalTime.parse(time));
        long minutes = ChronoUnit.MINUTES.between(now, target);
        return minutes >= 0 && minutes <= 4 * 60;
    }

    public static TimeState timeState(Item item, LocalDateTime now) {
        if (isOverdue(item, now)) return TimeState.LATE;
        if (isUrgent(item, now)) return TimeState.SOON;
        return TimeState.NORMAL;
    }

    /** The short text for a row's time column. Port of formatTimeCell(). */
    public static String formatTimeCell(Item item, LocalDateTime now) {
        String dateIso = item.itemDate();
        if (item.noDue || dateIso == null || dateIso.isEmpty()) return "—"; // em dash

        long offset = Dates.dayDifference(dateIso, now.toLocalDate());
        String time = item.itemTime();
        if (offset == 0 && time != null && !time.isEmpty()) {
            return time;
        }

        LocalDate date = LocalDate.parse(dateIso);
        if (Math.abs(offset) <= 6) {
            return date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.US).toUpperCase(Locale.US);
        }
        return date.format(MONTH_DAY).toUpperCase(Locale.US);
    }

    /** Whether a completed item still shows in its date tab today. Port of showsInDateTab(). */
    public static boolean showsInDateTab(Item item, String todayIso) {
        if (!item.completed) return true;
        return todayIso.equals(item.completedOn) && !item.dismissed;
    }
}
