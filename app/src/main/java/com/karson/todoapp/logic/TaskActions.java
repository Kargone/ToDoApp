package com.karson.todoapp.logic;

import com.karson.todoapp.model.Item;

import java.util.List;

/**
 * Pure task mutations shared by the main screen and the home-screen widget, so completing a task
 * behaves identically wherever it is tapped. Each method mutates the given list in place and the
 * caller persists it. Ported from the toggle/complete logic in {@code MainActivity}, which mirrors
 * the web app's completeTask / completeRepeating / undo.
 */
public final class TaskActions {

    private TaskActions() {}

    /** A fresh item id in the web app's {@code millis-hex} shape (also a valid Firebase key). */
    public static String createId() {
        return System.currentTimeMillis() + "-" + Integer.toHexString((int) (Math.random() * 0xffffff));
    }

    /**
     * Toggle completion of the task with {@code id}, applying the same three cases as the main
     * screen: a same-day undo of a repeat completion, complete-and-advance for a live repeat, or a
     * plain complete/uncomplete. Returns {@code true} if the list changed.
     */
    public static boolean toggle(List<Item> tasks, String id, String today) {
        Item item = findById(tasks, id);
        if (item == null) return false;

        // Undo an accidental repeat completion the same day.
        if (item.completed && item.undo != null && today.equals(item.completedOn)
                && undoRepeatCompletion(tasks, item)) {
            return true;
        }

        if (item.repeatRule != null && !item.isVirtual && !item.completed) {
            completeRepeating(tasks, item, today);
            return true;
        }

        if (item.completed) {
            item.completed = false;
            item.completedOn = null;
            item.dismissed = false;
            item.undo = null;
        } else {
            item.completed = true;
            item.completedOn = today;
            item.dismissed = false;
        }
        return true;
    }

    /** Complete the current occurrence of a repeating task: log it, then advance (or end) the template. */
    public static void completeRepeating(List<Item> tasks, Item task, String today) {
        String currentIso = task.isEvent() ? task.startDate : task.dueDate;

        Item occ = new Item();
        occ.id = createId();
        occ.type = task.type;
        occ.text = task.text;
        occ.completed = true;
        occ.completedOn = today;
        occ.dismissed = false;
        occ.repeatRule = null;
        occ.isVirtual = false;
        if (task.projectId != null) { occ.projectId = task.projectId; occ.inDateTabs = task.inDateTabs; }

        Item.Undo undo = new Item.Undo();
        undo.templateId = task.id;
        if (task.isEvent()) {
            occ.startDate = currentIso;
            occ.startTime = task.startTime;
            occ.endTime = task.endTime;
            undo.startDate = task.startDate;
            undo.startTime = task.startTime;
            undo.endTime = task.endTime;
        } else {
            occ.dueDate = currentIso;
            occ.dueTime = task.dueTime;
            occ.noDue = task.noDue;
            occ.section = task.section;
            undo.dueDate = task.dueDate;
            undo.dueTime = task.dueTime;
        }
        occ.undo = undo;

        String next = RepeatRules.nextOccurrenceDate(task);
        if (next != null) {
            if (task.isEvent()) task.startDate = next;
            else task.dueDate = next;
            tasks.add(occ);
        } else {
            // Last occurrence: the task itself completes and drops its rule.
            Item.Undo lastUndo = new Item.Undo();
            lastUndo.templateId = task.id;
            lastUndo.repeatRule = task.repeatRule;
            task.completed = true;
            task.completedOn = today;
            task.dismissed = false;
            task.repeatRule = null;
            task.undo = lastUndo;
        }
    }

    public static boolean undoRepeatCompletion(List<Item> tasks, Item occurrence) {
        Item.Undo undo = occurrence.undo;
        if (undo == null) return false;

        if (undo.templateId != null && undo.templateId.equals(occurrence.id)) {
            // Last occurrence: the item itself was completed and lost its rule.
            occurrence.completed = false;
            occurrence.completedOn = null;
            occurrence.dismissed = false;
            applyUndoFields(occurrence, undo);
            occurrence.undo = null;
            return true;
        }

        Item template = null;
        for (Item t : tasks) if (t.id.equals(undo.templateId)) { template = t; break; }
        if (template == null) return false;

        tasks.remove(occurrence);
        applyUndoFields(template, undo);
        return true;
    }

    private static void applyUndoFields(Item target, Item.Undo u) {
        if (u.dueDate != null) target.dueDate = u.dueDate;
        if (u.dueTime != null) target.dueTime = u.dueTime;
        if (u.startDate != null) target.startDate = u.startDate;
        if (u.startTime != null) target.startTime = u.startTime;
        if (u.endTime != null) target.endTime = u.endTime;
        if (u.repeatRule != null) target.repeatRule = u.repeatRule;
    }

    private static Item findById(List<Item> tasks, String id) {
        for (Item t : tasks) if (id.equals(t.id)) return t;
        return null;
    }
}
