package com.karson.todoapp.model;

/**
 * A project label. Tasks reference it by {@link Item#projectId}. A {@code parentId} that names
 * no existing project counts as top level. Mirrors the web app's project shape.
 */
public class Project {
    public String id;
    public String name;
    public String parentId;
    /** Manual position among siblings, set by dragging; null (absent in JSON) until then. */
    public Double order;

    public Project() {}

    public Project(String id, String name, String parentId) {
        this.id = id;
        this.name = name;
        this.parentId = parentId;
    }

    public Project(String id, String name, String parentId, Double order) {
        this(id, name, parentId);
        this.order = order;
    }
}
