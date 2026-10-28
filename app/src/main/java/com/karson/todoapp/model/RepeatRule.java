package com.karson.todoapp.model;

import java.util.List;

/**
 * A repeat schedule for a task or event. Mirrors the {@code repeatRule} object in the
 * ToDoApp-js web app so exported/imported JSON stays compatible.
 *
 * <p>The repeat engine that consumes these fields is built in a later phase; for now the
 * class exists so items carrying a rule round-trip through storage unchanged.
 */
public class RepeatRule {
    public int interval = 1;
    public String unit = "day"; // day | week | month | year
    public String stopDate = "";
    public String revealTime = "";
    public List<Integer> daysOfWeek;
    public List<Integer> daysOfMonth;
    public String monthPattern = "days"; // days | weekday
    public List<Integer> monthWeeks;
    public List<Integer> monthWeekdays;
}
