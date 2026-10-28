package com.karson.todoapp.logic;

import com.karson.todoapp.model.Item;
import com.karson.todoapp.model.RepeatRule;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** The repeat engine. Pure port of the repeat logic in script.mjs. No Android dependencies. */
public final class RepeatRules {
    private RepeatRules() {}

    private static final String[] WEEK_NAMES = {"", "first", "second", "third", "fourth", "fifth"};
    private static final String[] DAY_NAMES = {"Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"};
    private static final String[] DAY_SHORT = {"Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};

    private static final int MAX_SCAN_DAYS = 366 * 10; // guard against never-matching rules

    public static boolean isActive(RepeatRule rule, String todayIso) {
        return rule == null || rule.stopDate == null || rule.stopDate.isEmpty()
                || rule.stopDate.compareTo(todayIso) >= 0;
    }

    /** JS getDay(): 0 = Sunday .. 6 = Saturday. */
    private static int jsDay(LocalDate d) {
        return d.getDayOfWeek().getValue() % 7;
    }

    private static int monthsBetween(LocalDate a, LocalDate b) {
        return (b.getYear() - a.getYear()) * 12 + (b.getMonthValue() - a.getMonthValue());
    }

    /** The date of the {@code week}-th {@code weekday} in a month, or null if it overflows. */
    static LocalDate nthWeekdayDate(int year, int month1, int week, int weekday) {
        int firstDow = jsDay(LocalDate.of(year, month1, 1));
        int day = 1 + ((weekday - firstDow + 7) % 7) + (week - 1) * 7;
        if (day > YearMonth.of(year, month1).lengthOfMonth()) return null;
        return LocalDate.of(year, month1, day);
    }

    public static List<Integer> parseMonthDays(String value) {
        Set<Integer> out = new LinkedHashSet<>();
        if (value != null) {
            for (String part : value.split("[^0-9]+")) {
                if (part.isEmpty()) continue;
                try {
                    int n = Integer.parseInt(part.trim());
                    if (n >= 1 && n <= 31) out.add(n);
                } catch (NumberFormatException ignored) {}
            }
        }
        List<Integer> list = new ArrayList<>(out);
        list.sort(Integer::compareTo);
        return list;
    }

    public static boolean matches(Item item, String dateIso, RepeatRule rule, String anchorIso) {
        LocalDate date = LocalDate.parse(dateIso);
        LocalDate anchor = LocalDate.parse(anchorIso);
        long diffDays = ChronoUnit.DAYS.between(anchor, date);
        if (diffDays < 0) return false;
        int interval = Math.max(1, rule.interval);

        switch (rule.unit) {
            case "day":
                return diffDays % interval == 0;
            case "week": {
                if (rule.daysOfWeek == null || rule.daysOfWeek.isEmpty()) return false;
                if (!rule.daysOfWeek.contains(jsDay(date))) return false;
                long weeks = diffDays / 7;
                return weeks % interval == 0;
            }
            case "month": {
                if (monthsBetween(anchor, date) % interval != 0) return false;
                if ("weekday".equals(rule.monthPattern)) {
                    List<Integer> weeks = orDefault(rule.monthWeeks, 1);
                    List<Integer> weekdays = orDefault(rule.monthWeekdays, 0);
                    for (int week : weeks) {
                        for (int weekday : weekdays) {
                            LocalDate occ = nthWeekdayDate(date.getYear(), date.getMonthValue(), week, weekday);
                            if (occ != null && occ.getDayOfMonth() == date.getDayOfMonth()) return true;
                        }
                    }
                    return false;
                }
                return rule.daysOfMonth != null && rule.daysOfMonth.contains(date.getDayOfMonth());
            }
            case "year":
                return date.getMonthValue() == anchor.getMonthValue()
                        && date.getDayOfMonth() == anchor.getDayOfMonth()
                        && (date.getYear() - anchor.getYear()) % interval == 0;
            default:
                return false;
        }
    }

    private static List<Integer> orDefault(List<Integer> list, int fallback) {
        return (list != null && !list.isEmpty()) ? list : Arrays.asList(fallback);
    }

    /** First date on or after {@code startIso} that matches the rule (anchored at startIso). */
    public static String nextValidStartDate(String startIso, RepeatRule rule, Item item) {
        LocalDate candidate = LocalDate.parse(startIso);
        for (int i = 0; i < MAX_SCAN_DAYS; i++) {
            String iso = candidate.toString();
            if (matches(item, iso, rule, startIso)) return iso;
            candidate = candidate.plusDays(1);
        }
        return startIso;
    }

    /** The next occurrence date after the task's current anchor, or null if the rule has ended. */
    public static String nextOccurrenceDate(Item task) {
        if (task.repeatRule == null) return null;
        RepeatRule rule = task.repeatRule;
        String anchorIso = task.isEvent() ? task.startDate : task.dueDate;
        if (anchorIso == null) return null;
        LocalDate candidate = LocalDate.parse(anchorIso);
        for (int i = 0; i < MAX_SCAN_DAYS; i++) {
            candidate = candidate.plusDays(1);
            String iso = candidate.toString();
            if (rule.stopDate != null && !rule.stopDate.isEmpty() && iso.compareTo(rule.stopDate) > 0) return null;
            if (matches(task, iso, rule, anchorIso)) return iso;
        }
        return null;
    }

    public static Item createOccurrence(Item task, String dateIso) {
        Item occ = task.copy();
        occ.id = task.id + "-occ-" + dateIso;
        occ.isVirtual = true;
        if (task.isEvent()) {
            occ.startDate = dateIso;
            occ.startTime = task.startTime != null ? task.startTime : "";
            occ.endTime = task.endTime != null ? task.endTime : "";
        } else {
            occ.dueDate = dateIso;
            occ.dueTime = task.dueTime != null ? task.dueTime : "";
        }
        return occ;
    }

    /** Virtual future occurrences of a repeating task within [startIso, endIso]. */
    public static List<Item> getOccurrences(Item task, String startIso, String endIso, String todayIso) {
        List<Item> out = new ArrayList<>();
        if (task.repeatRule == null || task.completed || !isActive(task.repeatRule, todayIso)) return out;

        RepeatRule rule = task.repeatRule;
        String anchorIso = task.isEvent() ? task.startDate : (task.dueDate != null ? task.dueDate : startIso);
        if (anchorIso == null) return out;

        LocalDate anchor = LocalDate.parse(anchorIso);
        LocalDate start = LocalDate.parse(startIso);
        LocalDate end = LocalDate.parse(endIso);
        LocalDate candidate = anchor.isAfter(start) ? anchor : start;

        while (!candidate.isAfter(end)) {
            String iso = candidate.toString();
            if (!candidate.isBefore(anchor) && !iso.equals(anchorIso) && matches(task, iso, rule, anchorIso)) {
                if (rule.stopDate != null && !rule.stopDate.isEmpty() && iso.compareTo(rule.stopDate) > 0) break;
                out.add(createOccurrence(task, iso));
            }
            candidate = candidate.plusDays(1);
        }
        return out;
    }

    public static String formatLabel(RepeatRule rule) {
        StringBuilder sb = new StringBuilder("Repeats every ").append(rule.interval).append(' ').append(rule.unit);
        if (rule.revealTime != null && !rule.revealTime.isEmpty()) {
            sb.append(" at ").append(rule.revealTime);
        }
        if ("week".equals(rule.unit) && rule.daysOfWeek != null && !rule.daysOfWeek.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (int d : rule.daysOfWeek) names.add(DAY_SHORT[d]);
            sb.append(" on ").append(String.join(", ", names));
        }
        if ("month".equals(rule.unit) && rule.daysOfMonth != null && !rule.daysOfMonth.isEmpty()) {
            List<String> days = new ArrayList<>();
            for (int d : rule.daysOfMonth) days.add(String.valueOf(d));
            sb.append(" on ").append(String.join(", ", days));
        }
        if ("month".equals(rule.unit) && "weekday".equals(rule.monthPattern)) {
            List<Integer> weeks = orDefault(rule.monthWeeks, 1);
            List<Integer> weekdays = orDefault(rule.monthWeekdays, 0);
            List<String> weekLabels = new ArrayList<>();
            for (int w : weeks) weekLabels.add(WEEK_NAMES[w]);
            List<String> dayLabels = new ArrayList<>();
            for (int d : weekdays) dayLabels.add(DAY_NAMES[d]);
            sb.append(" on the ").append(String.join(" or ", weekLabels))
                    .append(' ').append(String.join(" or ", dayLabels));
        }
        return sb.toString();
    }
}
