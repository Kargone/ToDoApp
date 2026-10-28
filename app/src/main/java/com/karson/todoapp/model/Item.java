package com.karson.todoapp.model;

/**
 * A task or event. Field names mirror the item shape in the ToDoApp-js web app so JSON
 * import/export stays compatible. Dates are ISO strings ("yyyy-MM-dd"); times are "HH:mm".
 */
public class Item {
    public String id;
    public String type = "task"; // "task" | "event"
    public String text;
    public boolean completed;
    public String completedOn;   // ISO date the item was completed
    public boolean dismissed;    // pressed X: leaves the date tab, lives in Completed

    // Task fields
    public boolean noDue;
    public String section;       // "today" | "later" (for no-due tasks)
    public String dueDate;
    public String dueTime;

    // Event fields
    public String startDate;
    public String startTime;
    public String endTime;

    // Projects (wired up in a later phase)
    public String projectId;
    public boolean inDateTabs;

    // Repeat (engine built in a later phase)
    public RepeatRule repeatRule;
    public boolean isVirtual;    // a generated future occurrence, not a stored item

    /** Epoch millis before which a "Later today" postpone keeps the task out of Today. */
    public Long minShowTime;

    /** Snapshot that lets a repeat completion be undone the same day. */
    public Undo undo;

    public static class Undo {
        public String templateId;
        public String dueDate;
        public String dueTime;
        public String startDate;
        public String startTime;
        public String endTime;
        public RepeatRule repeatRule;
    }

    /** Shallow copy of every field (repeatRule is shared by reference, matching the JS spread). */
    public Item copy() {
        Item c = new Item();
        c.id = id;
        c.type = type;
        c.text = text;
        c.completed = completed;
        c.completedOn = completedOn;
        c.dismissed = dismissed;
        c.noDue = noDue;
        c.section = section;
        c.dueDate = dueDate;
        c.dueTime = dueTime;
        c.startDate = startDate;
        c.startTime = startTime;
        c.endTime = endTime;
        c.projectId = projectId;
        c.inDateTabs = inDateTabs;
        c.repeatRule = repeatRule;
        c.isVirtual = isVirtual;
        c.minShowTime = minShowTime;
        c.undo = undo;
        return c;
    }

    public boolean isEvent() {
        return "event".equals(type);
    }

    /** The date this item sorts/filters by: start date for events, due date for tasks. */
    public String itemDate() {
        return isEvent() ? startDate : dueDate;
    }

    /** The time this item shows: start time for events, due time for tasks. */
    public String itemTime() {
        return isEvent() ? startTime : dueTime;
    }

    /** Events always have a date; tasks have one unless they are explicitly no-due. */
    public boolean hasDate() {
        return isEvent() || !noDue;
    }
}
