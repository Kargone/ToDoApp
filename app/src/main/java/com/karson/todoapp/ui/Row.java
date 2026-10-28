package com.karson.todoapp.ui;

import com.karson.todoapp.model.Item;
import com.karson.todoapp.model.Project;

/** A single visible list row: either a task or a project head (Projects tab tree). */
public class Row {
    public static final int TYPE_TASK = 0;
    public static final int TYPE_PROJECT_HEAD = 1;

    public final int type;
    public final int depth;

    // Task row
    public final Item item;

    // Project head row
    public final Project project;
    public final int openCount;
    public final boolean expanded;

    private Row(int type, int depth, Item item, Project project, int openCount, boolean expanded) {
        this.type = type;
        this.depth = depth;
        this.item = item;
        this.project = project;
        this.openCount = openCount;
        this.expanded = expanded;
    }

    public static Row task(Item item, int depth) {
        return new Row(TYPE_TASK, depth, item, null, 0, false);
    }

    public static Row projectHead(Project project, int depth, int openCount, boolean expanded) {
        return new Row(TYPE_PROJECT_HEAD, depth, null, project, openCount, expanded);
    }
}
