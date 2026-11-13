package com.karson.todoapp.logic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.karson.todoapp.model.Item;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Unit tests for the archive helpers in {@link SyncLogic}, ported from the web app's
 * {@code tests/sync-logic.test.mjs} and extended with boundary and edge cases. The archive branch
 * moves completed records older than {@link SyncLogic#ARCHIVE_AFTER_YEARS} year out of the live
 * task list; these verify the pure selection and update-building logic in isolation.
 */
public class SyncLogicArchiveTest {

    // --- completionDate ---------------------------------------------------------

    @Test
    public void completionDate_prefersCompletedOn() {
        Item item = task();
        item.completedOn = "2025-01-01";
        item.dueDate = "2024-06-01";
        assertEquals("2025-01-01", SyncLogic.completionDate(item));
    }

    @Test
    public void completionDate_fallsBackToDueDateForTask() {
        Item item = task();
        item.dueDate = "2024-06-01";
        assertEquals("2024-06-01", SyncLogic.completionDate(item));
    }

    @Test
    public void completionDate_fallsBackToStartDateForEvent() {
        Item item = event();
        item.startDate = "2024-06-01";
        assertEquals("2024-06-01", SyncLogic.completionDate(item));
    }

    @Test
    public void completionDate_treatsBlankStringAsAbsent() {
        Item item = task();
        item.completedOn = "";       // blank, not a real date
        item.dueDate = "2024-06-01";
        assertEquals("2024-06-01", SyncLogic.completionDate(item));
    }

    @Test
    public void completionDate_emptyWhenNothingSet() {
        assertEquals("", SyncLogic.completionDate(task()));
    }

    // --- selectArchivable -------------------------------------------------------

    /** Mirrors the web test {@code selectArchivable takes completed records finished over a year ago}. */
    @Test
    public void selectArchivable_mirrorsWebScenario() {
        List<Item> items = Arrays.asList(
                completedOn("old", "2025-10-26"),          // over a year ago -> archived
                completedOn("edge", "2025-10-27"),         // exactly the cutoff -> kept
                completedOn("recent", "2026-01-01"),       // within the year -> kept
                completedDue("fallback", "2024-03-01"),    // no completedOn, old dueDate -> archived
                completedEvent("event", "2024-03-01"),     // event, old startDate -> archived
                openDue("open-old", "2020-01-01"),         // not completed -> kept however old
                task("undated", true)                      // completed but no date -> kept
        );
        assertEquals(Arrays.asList("old", "fallback", "event"), ids(SyncLogic.selectArchivable(items, "2026-10-27")));
    }

    @Test
    public void selectArchivable_cutoffIsExclusive() {
        // Cutoff for today 2026-10-27 is 2025-10-27: strictly-earlier archives, the cutoff itself does not.
        assertEquals(Arrays.asList("before"), ids(SyncLogic.selectArchivable(Arrays.asList(
                completedOn("before", "2025-10-26"),
                completedOn("on", "2025-10-27"),
                completedOn("after", "2025-10-28")
        ), "2026-10-27")));
    }

    @Test
    public void selectArchivable_skipsOpenRecords() {
        assertTrue(SyncLogic.selectArchivable(Arrays.asList(openDue("x", "2000-01-01")), "2026-10-27").isEmpty());
    }

    @Test
    public void selectArchivable_skipsVirtualOccurrences() {
        Item virtual = completedOn("v", "2000-01-01");
        virtual.isVirtual = true;
        assertTrue(SyncLogic.selectArchivable(Arrays.asList(virtual), "2026-10-27").isEmpty());
    }

    @Test
    public void selectArchivable_skipsCompletedButUndated() {
        assertTrue(SyncLogic.selectArchivable(Arrays.asList(task("u", true)), "2026-10-27").isEmpty());
    }

    @Test
    public void selectArchivable_handlesYearBoundaryAcrossNewYear() {
        // Today just after new year: cutoff is the same day one year earlier.
        assertEquals(Arrays.asList("y"), ids(SyncLogic.selectArchivable(Arrays.asList(
                completedOn("y", "2025-01-01"),   // > 1 year before 2026-01-02 -> archived
                completedOn("n", "2025-01-02")    // exactly the cutoff -> kept
        ), "2026-01-02")));
    }

    // --- buildArchiveUpdates ----------------------------------------------------

    /** Mirrors the web test {@code buildArchiveUpdates moves records in one update}. */
    @Test
    public void buildArchiveUpdates_movesRecordInOneUpdate() {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("id", "a");
        record.put("text", "A");
        record.put("completed", true);

        Map<String, Object> updates = SyncLogic.buildArchiveUpdates(Arrays.asList(record), "2026-10-27");

        assertEquals(2, updates.size());

        Map<String, Object> expectedArchive = new LinkedHashMap<>();
        expectedArchive.put("text", "A");
        expectedArchive.put("completed", true);
        expectedArchive.put("archivedOn", "2026-10-27");
        assertEquals(expectedArchive, updates.get("archive/a"));

        // A null value is how Firebase multi-path updates delete the live record.
        assertTrue(updates.containsKey("tasks/a"));
        assertNull(updates.get("tasks/a"));
    }

    @Test
    public void buildArchiveUpdates_stripsIdAndPrunesEmptyFields() {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("id", "x");
        record.put("text", "T");
        record.put("section", null);          // dropped by prune
        record.put("labels", new ArrayList<>()); // empty list dropped by prune

        Map<String, Object> updates = SyncLogic.buildArchiveUpdates(Arrays.asList(record), "2026-10-27");

        @SuppressWarnings("unchecked")
        Map<String, Object> archived = (Map<String, Object>) updates.get("archive/x");
        assertFalse("id moves into the key, never the stored value", archived.containsKey("id"));
        assertFalse(archived.containsKey("section"));
        assertFalse(archived.containsKey("labels"));

        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("text", "T");
        expected.put("archivedOn", "2026-10-27");
        assertEquals(expected, archived);
    }

    @Test
    public void buildArchiveUpdates_handlesMultipleRecords() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("id", "a");
        a.put("text", "A");
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("id", "b");
        b.put("text", "B");

        Map<String, Object> updates = SyncLogic.buildArchiveUpdates(Arrays.asList(a, b), "2026-10-27");

        assertEquals(4, updates.size());
        assertTrue(updates.containsKey("archive/a"));
        assertTrue(updates.containsKey("archive/b"));
        assertNull(updates.get("tasks/a"));
        assertNull(updates.get("tasks/b"));
    }

    @Test
    public void buildArchiveUpdates_emptyInputGivesEmptyUpdate() {
        assertTrue(SyncLogic.buildArchiveUpdates(new ArrayList<>(), "2026-10-27").isEmpty());
    }

    // --- builders ---------------------------------------------------------------

    private static Item task() {
        Item item = new Item();
        item.type = "task";
        return item;
    }

    private static Item event() {
        Item item = new Item();
        item.type = "event";
        return item;
    }

    private static Item task(String id, boolean completed) {
        Item item = task();
        item.id = id;
        item.completed = completed;
        return item;
    }

    private static Item completedOn(String id, String completedOn) {
        Item item = task(id, true);
        item.completedOn = completedOn;
        return item;
    }

    private static Item completedDue(String id, String dueDate) {
        Item item = task(id, true);
        item.dueDate = dueDate;
        return item;
    }

    private static Item completedEvent(String id, String startDate) {
        Item item = event();
        item.id = id;
        item.completed = true;
        item.startDate = startDate;
        return item;
    }

    private static Item openDue(String id, String dueDate) {
        Item item = task(id, false);
        item.dueDate = dueDate;
        return item;
    }

    private static List<String> ids(List<Item> items) {
        List<String> out = new ArrayList<>();
        for (Item item : items) out.add(item.id);
        return out;
    }
}
