package com.karson.todoapp.logic;

import com.karson.todoapp.model.Item;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Which tab an item belongs to, and which items a tab shows.
 * Port of getCategory() / getItemsForTab() from script.mjs.
 *
 * <p>Repeat "virtual" occurrences (Phase 3) and the Projects tab (Phase 2) are intentionally
 * not produced here yet; the date tabs and Completed match the web app.
 */
public final class Categories {
    private Categories() {}

    public static final String TODAY = "today";
    public static final String TOMORROW = "tomorrow";
    public static final String NEXT3 = "next3";
    public static final String NEXT_MONTH = "nextMonth";
    public static final String LATER = "later";
    public static final String PROJECTS = "projects";
    public static final String REPEATING = "repeating";
    public static final String COMPLETED = "completed";

    private static final Set<String> DATE_TABS = new HashSet<>(
            Arrays.asList(TODAY, TOMORROW, NEXT3, NEXT_MONTH, LATER));

    /** The category tab an item sorts into by date. Port of getCategory(). */
    public static String getCategory(Item item) {
        if (item.completed) return COMPLETED;

        String today = Dates.todayIso();
        String tomorrow = Dates.addInterval(today, 1, "day");
        String dayThree = Dates.addInterval(today, 3, "day");
        String dateIso = item.itemDate();

        if (item.noDue) return item.section != null ? item.section : LATER;
        if (dateIso == null || dateIso.isEmpty()) return LATER;
        if (dateIso.compareTo(today) <= 0) return TODAY;
        if (dateIso.equals(tomorrow)) return TOMORROW;
        if (dateIso.compareTo(dayThree) <= 0) return NEXT3;
        return LATER;
    }

    public static List<Item> getItemsForTab(List<Item> tasks, String tab, String query,
                                            LocalDateTime now, Set<String> projectIds) {
        String todayIso = now.toLocalDate().toString();
        List<Item> items = new ArrayList<>();

        switch (tab) {
            case REPEATING:
                for (Item it : tasks) {
                    if (it.repeatRule != null && isRepeatActive(it.repeatRule, todayIso) && !it.isVirtual) {
                        items.add(it);
                    }
                }
                break;

            case PROJECTS: {
                // Like Next 3 days: anything dated further out stays hidden until it gets closer.
                String horizon = Dates.addInterval(todayIso, 3, "day");
                for (Item it : tasks) {
                    if (it.isVirtual || it.projectId == null || !projectIds.contains(it.projectId)) continue;
                    if (!ViewLogic.showsInDateTab(it, todayIso)) continue;
                    if (it.hasDate() && it.itemDate() != null && it.itemDate().compareTo(horizon) > 0) continue;
                    items.add(it);
                }
                break;
            }

            case COMPLETED:
                for (Item it : tasks) {
                    if (it.completed) items.add(it);
                }
                break;

            case LATER: {
                for (Item it : tasks) {
                    boolean isTask = !it.isEvent();
                    boolean qualifies = (it.noDue && LATER.equals(it.section))
                            || (!it.noDue && it.repeatRule == null);
                    if (isTask && qualifies && ViewLogic.showsInDateTab(it, todayIso)) {
                        items.add(it);
                    }
                }
                items.sort(laterComparator());
                break;
            }

            case NEXT_MONTH: {
                // Events only: real events in range, plus virtual repeat occurrences that are events.
                String rangeEnd = Dates.addInterval(todayIso, 1, "month");
                for (Item it : tasks) {
                    if (!ViewLogic.showsInDateTab(it, todayIso) || it.isVirtual || !it.isEvent()) continue;
                    String d = it.startDate;
                    if (d != null && d.compareTo(todayIso) >= 0 && d.compareTo(rangeEnd) <= 0) {
                        items.add(it);
                    }
                }
                for (Item it : tasks) {
                    for (Item occ : RepeatRules.getOccurrences(it, todayIso, rangeEnd, todayIso)) {
                        if (occ.isEvent()) items.add(occ);
                    }
                }
                break;
            }

            case TODAY: {
                LocalDateTime endOfToday = LocalDateTime.of(now.toLocalDate(), LocalTime.of(23, 59));
                long nowMillis = System.currentTimeMillis();
                for (Item it : tasks) {
                    if (it.minShowTime != null && it.minShowTime > nowMillis) continue;
                    if (!ViewLogic.showsInDateTab(it, todayIso)) continue;
                    if (afterInstant(it.dueDate, it.dueTime, endOfToday)) continue;
                    if (afterInstant(it.startDate, it.startTime, endOfToday)) continue;
                    if (LATER.equals(it.section)) continue;
                    // A repeating task with a reveal time stays hidden until that time on its due date.
                    if (it.repeatRule != null && it.repeatRule.revealTime != null
                            && !it.repeatRule.revealTime.isEmpty() && it.dueDate != null
                            && afterInstant(it.dueDate, it.repeatRule.revealTime, now)) continue;
                    items.add(it);
                }
                break;
            }

            case TOMORROW:
            case NEXT3: {
                for (Item it : tasks) {
                    if (matchesDateTab(it, tab, todayIso)) items.add(it);
                }
                String tomorrow = Dates.addInterval(todayIso, 1, "day");
                String rangeEnd = TOMORROW.equals(tab) ? tomorrow : Dates.addInterval(todayIso, 3, "day");
                for (Item it : tasks) {
                    items.addAll(RepeatRules.getOccurrences(it, tomorrow, rangeEnd, todayIso));
                }
                break;
            }

            default:
                for (Item it : tasks) {
                    if (matchesDateTab(it, tab, todayIso)) items.add(it);
                }
        }

        // Project tasks stay out of the date tabs unless dated and opted in (not for completed/repeating/projects).
        if (DATE_TABS.contains(tab) && !projectIds.isEmpty()) {
            List<Item> visible = new ArrayList<>();
            for (Item it : items) {
                if (!ProjectLogic.isHiddenFromDateTabs(it, projectIds)) visible.add(it);
            }
            items = visible;
        }

        if (query != null && !query.trim().isEmpty()) {
            String q = query.trim().toLowerCase(Locale.ROOT);
            List<Item> filtered = new ArrayList<>();
            for (Item it : items) {
                if (it.text != null && it.text.toLowerCase(Locale.ROOT).contains(q)) filtered.add(it);
            }
            return filtered;
        }
        return items;
    }

    private static Comparator<Item> laterComparator() {
        return (a, b) -> {
            if (a.noDue && !b.noDue) return -1;
            if (!a.noDue && b.noDue) return 1;
            if (a.dueDate != null && b.dueDate != null) {
                int byDate = a.dueDate.compareTo(b.dueDate);
                if (byDate != 0) return byDate;
                if (a.dueTime != null && b.dueTime != null) return a.dueTime.compareTo(b.dueTime);
            }
            return 0;
        };
    }

    /** Whether an item belongs in a date tab now (categorise completed items as if open). */
    private static boolean matchesDateTab(Item it, String tab, String todayIso) {
        if (!it.completed) return tab.equals(getCategory(it));
        if (!ViewLogic.showsInDateTab(it, todayIso)) return false;
        it.completed = false;
        boolean match = tab.equals(getCategory(it));
        it.completed = true;
        return match;
    }

    /** True if (date,time) is strictly after the given instant. Empty date never excludes. */
    private static boolean afterInstant(String dateIso, String timeIso, LocalDateTime limit) {
        if (dateIso == null || dateIso.isEmpty()) return false;
        LocalTime time = (timeIso == null || timeIso.isEmpty()) ? LocalTime.MIDNIGHT : LocalTime.parse(timeIso);
        return LocalDateTime.of(LocalDate.parse(dateIso), time).isAfter(limit);
    }

    public static boolean isRepeatActive(com.karson.todoapp.model.RepeatRule rule, String todayIso) {
        return RepeatRules.isActive(rule, todayIso);
    }
}
