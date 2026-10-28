package com.karson.todoapp.logic;

import com.karson.todoapp.model.Item;
import com.karson.todoapp.model.Project;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Pure helpers for the project tree. Port of project-logic.mjs. No Android dependencies. */
public final class ProjectLogic {
    private ProjectLogic() {}

    /** A project with its depth and full name path, as a picker or the tree shows it. */
    public static class FlatProject {
        public final Project project;
        public final int depth;
        public final List<String> path;

        FlatProject(Project project, int depth, List<String> path) {
            this.project = project;
            this.depth = depth;
            this.path = path;
        }
    }

    private static String resolveParentId(Project project, Set<String> ids) {
        return project.parentId != null && ids.contains(project.parentId) ? project.parentId : null;
    }

    /** Direct children of {@code parentId} (null = top level), sorted case-insensitively by name. */
    public static List<Project> getChildProjects(List<Project> projects, String parentId) {
        Set<String> ids = idSet(projects);
        List<Project> children = new ArrayList<>();
        for (Project p : projects) {
            String resolved = resolveParentId(p, ids);
            if (equalsNullable(resolved, parentId)) children.add(p);
        }
        children.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        return children;
    }

    public static List<String> getDescendantIds(List<Project> projects, String projectId) {
        List<String> descendants = new ArrayList<>();
        visitDescendants(projects, projectId, descendants);
        return descendants;
    }

    private static void visitDescendants(List<Project> projects, String parentId, List<String> out) {
        for (Project child : getChildProjects(projects, parentId)) {
            out.add(child.id);
            visitDescendants(projects, child.id, out);
        }
    }

    /** Depth-first, alphabetical within each level: what a picker or the tree shows top to bottom. */
    public static List<FlatProject> flattenProjects(List<Project> projects) {
        List<FlatProject> flat = new ArrayList<>();
        visitFlatten(projects, null, 0, new ArrayList<>(), flat);
        return flat;
    }

    private static void visitFlatten(List<Project> projects, String parentId, int depth,
                                     List<String> path, List<FlatProject> out) {
        for (Project project : getChildProjects(projects, parentId)) {
            List<String> projectPath = new ArrayList<>(path);
            projectPath.add(project.name);
            out.add(new FlatProject(project, depth, projectPath));
            visitFlatten(projects, project.id, depth + 1, projectPath, out);
        }
    }

    /** Project tasks stay out of the date tabs unless they have a date and were opted in. */
    public static boolean isHiddenFromDateTabs(Item item, Set<String> projectIds) {
        if (item.projectId == null || !projectIds.contains(item.projectId)) return false;
        return !item.hasDate() || !item.inDateTabs;
    }

    public static Set<String> idSet(List<Project> projects) {
        Set<String> ids = new HashSet<>();
        for (Project p : projects) ids.add(p.id);
        return ids;
    }

    private static boolean equalsNullable(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
