package com.karson.todoapp.logic;

import com.karson.todoapp.model.Item;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure helpers for the keyed Firebase layout, mirroring the web app's {@code sync-logic.mjs}:
 *
 * <pre>
 * users/{uid}/
 *   tasks/{key}:    task fields (the key is the task's id)
 *   projects/{key}: { name, parentId? }
 *   archive/{key}:  task fields + archivedOn, never loaded during normal use
 * </pre>
 *
 * <p>No Firebase or Gson dependency, so these can be reasoned about (and unit-tested) in isolation.
 * They operate on the plain {@code Map}/{@code List}/primitive trees that Gson produces from either
 * a model object or a Firebase snapshot — feeding both sides through the same conversion is what lets
 * a record read back from the database compare equal (via {@link Map#equals}) to the one written.
 */
public final class SyncLogic {

    private SyncLogic() {}

    /** Characters Firebase does not allow in a key. */
    public static boolean isValidKey(String key) {
        return key != null && !key.isEmpty() && key.length() <= 768
                && !key.matches(".*[.#$\\[\\]/\\u0000-\\u001f\\u007f].*");
    }

    /**
     * What Firebase would hand back for a value: no nulls, empty lists or empty maps. Returns
     * {@code null} when nothing survives (an empty container), matching the web's {@code prune}.
     */
    public static Object prune(Object value) {
        if (value instanceof List) {
            List<Object> out = new ArrayList<>();
            for (Object item : (List<?>) value) {
                Object pruned = prune(item);
                if (pruned != null) out.add(pruned);
            }
            return out.isEmpty() ? null : out;
        }
        if (value instanceof Map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                Object pruned = prune(entry.getValue());
                if (pruned != null) out.put(String.valueOf(entry.getKey()), pruned);
            }
            return out.isEmpty() ? null : out;
        }
        return value;
    }

    /**
     * The stored form of an in-memory record map: the {@code id} moves into the key, so it is
     * dropped here, and the rest is pruned. Never {@code null} — an empty record becomes {@code {}}.
     */
    public static Map<String, Object> toStored(Map<String, Object> fields) {
        Map<String, Object> copy = new LinkedHashMap<>(fields);
        copy.remove("id");
        Object pruned = prune(copy);
        if (pruned instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) pruned;
            return map;
        }
        return new LinkedHashMap<>();
    }

    // --- Archive ----------------------------------------------------------------
    //
    // Completed records older than a year are moved out of the live task list into a separate
    // {@code archive/} branch that is never loaded during normal use (see the web app's
    // {@code archiveOldTasks}). Selection works on in-memory {@link Item}s, matching the rest of
    // {@code logic/}; building the Firebase update works on the stored {@code Map} form, matching
    // {@link #toStored} — the caller converts the selected items to maps (the same Gson round-trip
    // the repository already uses) and hands them to {@link #buildArchiveUpdates}.

    /** Completed records finished at least this many years ago are archived. */
    public static final int ARCHIVE_AFTER_YEARS = 1;

    /**
     * When a completed record was finished. Mirrors the web's {@code completionDate}: prefer
     * {@code completedOn}, else the record's own date ({@code startDate} for an event, {@code dueDate}
     * for a task), else {@code startDate}, treating blank strings as absent. Returns {@code ""} when
     * nothing is set.
     */
    public static String completionDate(Item item) {
        String byType = item.isEvent() ? item.startDate : item.dueDate;
        return firstNonEmpty(item.completedOn, byType, item.startDate);
    }

    /**
     * Completed, non-virtual records finished more than {@link #ARCHIVE_AFTER_YEARS} year(s) before
     * {@code todayIso}. Open records are never archived however old, and a generated (virtual) repeat
     * occurrence is skipped since it is not a stored item. Mirrors the web's {@code selectArchivable}.
     */
    public static List<Item> selectArchivable(List<Item> items, String todayIso) {
        String cutoff = yearsBefore(todayIso, ARCHIVE_AFTER_YEARS);
        List<Item> out = new ArrayList<>();
        for (Item item : items) {
            if (item == null || !item.completed || item.isVirtual) continue;
            String finished = completionDate(item);
            if (!finished.isEmpty() && finished.compareTo(cutoff) < 0) out.add(item);
        }
        return out;
    }

    /**
     * One atomic multi-path update that moves records into {@code archive/} (stamped with
     * {@code archivedOn}) and deletes them from {@code tasks/} (a {@code null} value, which Firebase
     * treats as a delete). Mirrors the web's {@code buildArchiveUpdates}. Each input map is a full
     * record including its {@code id}; the id becomes the key and is stripped from the stored value by
     * {@link #toStored}.
     */
    public static Map<String, Object> buildArchiveUpdates(List<Map<String, Object>> items, String todayIso) {
        Map<String, Object> updates = new LinkedHashMap<>();
        for (Map<String, Object> item : items) {
            String id = String.valueOf(item.get("id"));
            Map<String, Object> stored = toStored(item);
            stored.put("archivedOn", todayIso);
            updates.put("archive/" + id, stored);
            updates.put("tasks/" + id, null);
        }
        return updates;
    }

    /** The ISO date {@code years} before {@code todayIso}, by string surgery (matches the web). */
    private static String yearsBefore(String todayIso, int years) {
        int year = Integer.parseInt(todayIso.substring(0, 4));
        String rest = todayIso.substring(4);
        return (year - years) + rest;
    }

    /** First non-null, non-empty string, or {@code ""} — the web's {@code a || b || c || ''}. */
    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.isEmpty()) return value;
        }
        return "";
    }
}
