package com.karson.todoapp.data;

import com.karson.todoapp.model.Item;
import com.karson.todoapp.model.Project;

import java.util.List;

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

    List<Project> loadProjects();

    void saveProjects(List<Project> projects);

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
