package com.karson.todoapp.data;

import android.util.Log;

import androidx.annotation.NonNull;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.karson.todoapp.logic.SyncLogic;
import com.karson.todoapp.model.Item;
import com.karson.todoapp.model.Project;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Syncs tasks and projects with Firebase Realtime Database under {@code users/<uid>}, matching the
 * web app's keyed layout: {@code users/<uid>/tasks/<key>} and {@code users/<uid>/projects/<key>}
 * each hold one record's fields, and the <b>key is the record's id</b> (see {@link SyncLogic}).
 *
 * <p><b>Reads are live.</b> A {@link ValueEventListener} on each collection pushes every remote
 * change straight to the UI via {@link Listener}, so edits made on the web (or another device)
 * appear here without a refresh. Each child's key is injected as the record's {@code id}.
 *
 * <p><b>Writes are per-key (push-style), not whole-array.</b> {@link #saveTasks}/{@link #saveProjects}
 * still take the full list, but only the records that actually changed since the last snapshot are
 * written — each to its own key — and records that disappeared are deleted, all in one atomic
 * {@link DatabaseReference#updateChildren}. This mirrors the web app's {@code writeChanges}: adding a
 * task writes just that one key, so it appends rather than rewriting everyone else. Writes are
 * guarded until the collection's first snapshot has arrived, so a startup race cannot clobber data.
 */
public class FirebaseTaskRepository implements TaskRepository {
    private static final String TAG = "FirebaseTaskRepository";
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {}.getType();

    private final DatabaseReference tasksRef;
    private final DatabaseReference projectsRef;
    private final Gson gson = new Gson();

    private final List<Item> taskCache = new ArrayList<>();
    private final List<Project> projectCache = new ArrayList<>();

    /** id -> stored form last seen in the database, used to diff writes down to what changed. */
    private final Map<String, Object> syncedTasks = new HashMap<>();
    private final Map<String, Object> syncedProjects = new HashMap<>();

    private Listener listener;
    private ValueEventListener tasksSub;
    private ValueEventListener projectsSub;
    private boolean tasksLoaded;
    private boolean projectsLoaded;

    public FirebaseTaskRepository(@NonNull String uid) {
        DatabaseReference root = FirebaseDatabase.getInstance().getReference("users").child(uid);
        this.tasksRef = root.child("tasks");
        this.projectsRef = root.child("projects");
    }

    @Override
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** Returns the last snapshot delivered by the live listener (empty until the first load). */
    @Override
    public List<Item> loadTasks() {
        return new ArrayList<>(taskCache);
    }

    @Override
    public List<Project> loadProjects() {
        return new ArrayList<>(projectCache);
    }

    @Override
    public void start() {
        if (tasksSub != null) return; // already observing
        tasksSub = tasksRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                applyTasksSnapshot(snapshot);
                Log.d(TAG, "Loaded " + taskCache.size() + " tasks from Firebase");
                if (listener != null) listener.onTasksChanged(new ArrayList<>(taskCache));
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.w(TAG, "tasks listener cancelled: " + error.getMessage());
            }
        });
        projectsSub = projectsRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                applyProjectsSnapshot(snapshot);
                if (listener != null) listener.onProjectsChanged(new ArrayList<>(projectCache));
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.w(TAG, "projects listener cancelled: " + error.getMessage());
            }
        });
    }

    /**
     * One-shot read for the widget. Reads both collections once (not a live listener), parsing them
     * through the same path as {@link #start} so the write baseline is seeded — a following
     * {@link #saveTasks} on this instance then writes only what the widget changed.
     */
    @Override
    public void loadOnce(SnapshotCallback callback) {
        tasksRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot taskSnap) {
                applyTasksSnapshot(taskSnap);
                projectsRef.addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot projectSnap) {
                        applyProjectsSnapshot(projectSnap);
                        callback.onLoaded(new ArrayList<>(taskCache), new ArrayList<>(projectCache));
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        Log.w(TAG, "loadOnce projects cancelled: " + error.getMessage());
                        callback.onLoaded(new ArrayList<>(taskCache), new ArrayList<>());
                    }
                });
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.w(TAG, "loadOnce tasks cancelled: " + error.getMessage());
                callback.onLoaded(new ArrayList<>(), new ArrayList<>());
            }
        });
    }

    /** Parse a tasks snapshot into {@link #taskCache} and reset the {@link #syncedTasks} baseline. */
    private void applyTasksSnapshot(DataSnapshot snapshot) {
        taskCache.clear();
        syncedTasks.clear();
        for (DataSnapshot child : snapshot.getChildren()) {
            Map<String, Object> fields = asMap(child.getValue());
            if (fields == null) continue;
            String id = resolveId(child.getKey(), fields);
            try {
                Item item = gson.fromJson(gson.toJson(child.getValue()), Item.class);
                if (item == null) continue;
                item.id = id;
                taskCache.add(item);
                syncedTasks.put(id, SyncLogic.toStored(fields));
            } catch (Exception e) {
                Log.w(TAG, "Skipping unparseable task at " + child.getKey(), e);
            }
        }
        tasksLoaded = true;
    }

    /** Parse a projects snapshot into {@link #projectCache} and reset the baseline. */
    private void applyProjectsSnapshot(DataSnapshot snapshot) {
        projectCache.clear();
        syncedProjects.clear();
        for (DataSnapshot child : snapshot.getChildren()) {
            Map<String, Object> fields = asMap(child.getValue());
            if (fields == null) continue;
            String id = resolveId(child.getKey(), fields);
            try {
                Project p = gson.fromJson(gson.toJson(child.getValue()), Project.class);
                // Firebase drops null fields, so rebuild the shape; skip ill-formed rows.
                if (p == null || p.name == null) continue;
                projectCache.add(new Project(id, p.name, p.parentId));
                syncedProjects.put(id, SyncLogic.toStored(fields));
            } catch (Exception e) {
                Log.w(TAG, "Skipping unparseable project at " + child.getKey(), e);
            }
        }
        projectsLoaded = true;
    }

    @Override
    public void stop() {
        if (tasksSub != null) {
            tasksRef.removeEventListener(tasksSub);
            tasksSub = null;
        }
        if (projectsSub != null) {
            projectsRef.removeEventListener(projectsSub);
            projectsSub = null;
        }
        // Mirror the web's stopSync: forget what we'd loaded so writes stay blocked until the next
        // snapshot re-establishes the baseline (a write off a stale baseline could delete real data).
        tasksLoaded = false;
        projectsLoaded = false;
        syncedTasks.clear();
        syncedProjects.clear();
    }

    @Override
    public void saveTasks(List<Item> tasks) {
        if (!tasksLoaded) {
            // First snapshot hasn't arrived — a diff off an empty baseline would delete everything.
            Log.w(TAG, "saveTasks skipped: initial load not complete");
            return;
        }
        writeChanges(tasksRef, syncedTasks, tasks);
    }

    /**
     * Appends one task by writing just its own key — no diff, no baseline, no deletes. Safe to call
     * from a short-lived process (the quick-add widget) that never attached the live listener:
     * {@link DatabaseReference#setValue} on a single child only ever adds that record, and offline
     * persistence queues it if there's no connection. Mirrors the web app's single-key append.
     */
    @Override
    public void appendTask(Item task) {
        Map<String, Object> fields = asMap(task);
        if (fields == null) return;
        Object rawId = fields.get("id");
        if (!(rawId instanceof String) || !SyncLogic.isValidKey((String) rawId)) return;
        tasksRef.child((String) rawId).setValue(SyncLogic.toStored(fields));
    }

    @Override
    public void saveProjects(List<Project> projects) {
        if (!projectsLoaded) {
            Log.w(TAG, "saveProjects skipped: initial load not complete");
            return;
        }
        writeChanges(projectsRef, syncedProjects, projects);
    }

    /**
     * Writes only what changed, each record under its own key. Mirrors the web's writeChanges():
     * records whose stored form differs from the last snapshot are written whole; records that are
     * gone are set to {@code null} (which deletes them); unchanged records are left untouched. The
     * whole set goes up as one atomic {@link DatabaseReference#updateChildren}.
     */
    private void writeChanges(DatabaseReference ref, Map<String, Object> synced, List<?> items) {
        Map<String, Object> next = new HashMap<>();
        Map<String, Object> updates = new HashMap<>();

        for (Object item : items) {
            Map<String, Object> fields = asMap(item);
            if (fields == null) continue;
            Object rawId = fields.get("id");
            if (!(rawId instanceof String) || ((String) rawId).isEmpty()) continue; // no key, no write
            String id = (String) rawId;
            Object stored = SyncLogic.toStored(fields);
            next.put(id, stored);
            if (!stored.equals(synced.get(id))) updates.put(id, stored);
        }
        for (String id : synced.keySet()) {
            if (!next.containsKey(id)) updates.put(id, null); // removed since last snapshot -> delete
        }

        // Record the intended state before writing: the local listener may fire synchronously, and
        // it will re-establish this baseline from the echoed snapshot anyway.
        synced.clear();
        synced.putAll(next);

        if (updates.isEmpty()) return;
        ref.updateChildren(updates);
    }

    /**
     * The id for a child. In the keyed layout the child key <i>is</i> the id. A legacy array node
     * (numeric keys) is read defensively: its embedded {@code id} field wins over the array index.
     */
    private String resolveId(String key, Map<String, Object> fields) {
        if (key != null && key.matches("\\d+")) {
            Object embedded = fields.get("id");
            if (embedded instanceof String && !((String) embedded).isEmpty()) return (String) embedded;
        }
        return key;
    }

    /**
     * Normalizes any record source — a model object or a Firebase snapshot value — to the same plain
     * {@code Map} of {@code String}/{@code Double}/{@code Boolean}/{@code List}/{@code Map}, so stored
     * forms from reads and writes compare equal. Returns {@code null} for non-object values.
     */
    private Map<String, Object> asMap(Object value) {
        if (value == null) return null;
        try {
            return gson.fromJson(gson.toJson(value), MAP_TYPE);
        } catch (Exception e) {
            return null;
        }
    }
}
