package com.karson.todoapp.data;

import android.content.Context;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.karson.todoapp.model.Item;
import com.karson.todoapp.model.Project;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * Stores tasks as a JSON array in the app's private files dir. The on-disk shape matches the
 * web app's exported task JSON, so a later import/export feature can reuse it directly.
 */
public class LocalTaskRepository implements TaskRepository {
    private static final String TAG = "LocalTaskRepository";
    private static final String TASKS_FILE = "tasks.json";
    private static final String PROJECTS_FILE = "projects.json";

    private final File tasksFile;
    private final File projectsFile;
    private final Gson gson = new Gson();

    public LocalTaskRepository(Context context) {
        this.tasksFile = new File(context.getFilesDir(), TASKS_FILE);
        this.projectsFile = new File(context.getFilesDir(), PROJECTS_FILE);
    }

    @Override
    public List<Item> loadTasks() {
        Type listType = new TypeToken<ArrayList<Item>>() {}.getType();
        return read(tasksFile, listType);
    }

    @Override
    public void saveTasks(List<Item> tasks) {
        write(tasksFile, tasks);
    }

    @Override
    public void appendTask(Item task) {
        List<Item> tasks = loadTasks();
        tasks.add(task);
        write(tasksFile, tasks);
    }

    @Override
    public List<Project> loadProjects() {
        Type listType = new TypeToken<ArrayList<Project>>() {}.getType();
        return read(projectsFile, listType);
    }

    @Override
    public void saveProjects(List<Project> projects) {
        write(projectsFile, projects);
    }

    private <T> List<T> read(File file, Type listType) {
        if (!file.exists()) return new ArrayList<>();
        try (Reader reader = new FileReader(file)) {
            List<T> items = gson.fromJson(reader, listType);
            return items != null ? items : new ArrayList<>();
        } catch (Exception e) {
            Log.e(TAG, "Failed to read " + file.getName() + "; starting empty", e);
            return new ArrayList<>();
        }
    }

    private void write(File file, Object value) {
        try (FileWriter writer = new FileWriter(file)) {
            gson.toJson(value, writer);
        } catch (IOException e) {
            Log.e(TAG, "Failed to write " + file.getName(), e);
        }
    }
}
