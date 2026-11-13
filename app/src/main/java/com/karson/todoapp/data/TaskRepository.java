package com.karson.todoapp.data;

import com.karson.todoapp.model.Item;
import com.karson.todoapp.model.Project;

import java.util.List;
import java.util.Map;

/**
 * Storage for tasks and projects. A single seam so the backend can change without touching the
 * UI: the local JSON implementation ({@link LocalTaskRepository}) is the signed-out fallback;
 * {@link FirebaseTaskRepository} slots in when a user is signed in.
 *
 * <p>Local storage is synchronous — {@link #loadTasks()} returns what's on disk right away.
 * Live backends (Firebase) can't; they return the last-known snapshot from the load methods and
 * push every change through a {@link Listener}. The lifecycle hooks ({@link #start()} /
 * {@link #stop()}) and the listener default to no-ops so local storage needs none of them.
 */
public interface TaskRepository {
    List<Item> loadTasks();

    void saveTasks(List<Item> tasks);

    /**
     * Add a single task, appending it without rewriting the rest. This exists for short-lived
     * callers (the quick-add widget) that never load the full list: the Firebase implementation
     * writes just this one record's key, so it is safe even before any snapshot has arrived —
     * unlike {@link #saveTasks}, which is guarded until the live load establishes its diff baseline.
     */
    void appendTask(Item task);

    List<Project> loadProjects();

    void saveProjects(List<Project> projects);

    /**
     * Read tasks and projects once, off the live listener. Used by the home-screen widget, which
     * runs in a short-lived process and never attaches {@link #setListener}. Local storage answers
     * synchronously on the calling thread; a live backend reads a single snapshot and, crucially,
     * records it as the write baseline so a following {@link #saveTasks} on the same instance writes
     * only the diff (otherwise it would be guarded off, like a cold {@link #start()}).
     */
    default void loadOnce(SnapshotCallback callback) {
        callback.onLoaded(loadTasks(), loadProjects());
    }

    interface SnapshotCallback {
        void onLoaded(List<Item> tasks, List<Project> projects);
    }

    /**
     * Read the archive branch (completed records aged out of the live list) once, for export.
     * Each entry is a raw stored record with its key injected as {@code id}, so fields the model
     * does not carry (such as {@code archivedOn}) survive. Backends without an archive — local
     * storage, and anything signed out — answer empty.
     */
    default void loadArchive(ArchiveCallback callback) {
        callback.onLoaded(new java.util.ArrayList<>());
    }

    interface ArchiveCallback {
        void onLoaded(List<Map<String, Object>> archived);
    }

    /** Register the sink for live updates. Local storage never calls back. */
    default void setListener(Listener listener) {}

    /** Begin observing remote changes. No-op for local storage. */
    default void start() {}

    /** Stop observing and release resources. No-op for local storage. */
    default void stop() {}

    interface Listener {
        void onTasksChanged(List<Item> tasks);

        void onProjectsChanged(List<Project> projects);
    }
}
