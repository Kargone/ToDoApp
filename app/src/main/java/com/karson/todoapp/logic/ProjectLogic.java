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

    /**
     * Direct children of {@code parentId} (null = top level). Siblings with an order (set by
     * dragging) come first in that order; the rest follow case-insensitively by name.
     */
    public static List<Project> getChildProjects(List<Project> projects, String parentId) {
        Set<String> ids = idSet(projects);
        List<Project> children = new ArrayList<>();
        for (Project p : projects) {
            String resolved = resolveParentId(p, ids);
            if (equalsNullable(resolved, parentId)) children.add(p);
        }
        children.sort((a, b) -> {
            int byOrder = compareOrder(a.order, b.order);
            return byOrder != 0 ? byOrder : a.name.compareToIgnoreCase(b.name);
        });
        return children;
    }

    /** Ascending by a manual order, with unordered (missing) values after every ordered one. */
    public static int compareOrder(Double a, Double b) {
        double aKey = isFinite(a) ? a : Double.POSITIVE_INFINITY;
        double bKey = isFinite(b) ? b : Double.POSITIVE_INFINITY;
        return Double.compare(aKey, bKey);
    }

    private static boolean isFinite(Double value) {
        return value != null && !value.isNaN() && !value.isInfinite();
    }

    /**
     * The ids with {@code movedId} taken out and put back in front of {@code beforeId} (or at the
     * end when {@code beforeId} is null or absent).
     */
    public static List<String> placeId(List<String> ids, String movedId, String beforeId) {
        List<String> rest = new ArrayList<>(ids);
        rest.remove(movedId);
        int index = beforeId == null ? -1 : rest.indexOf(beforeId);
        rest.add(index == -1 ? rest.size() : index, movedId);
        return rest;
    }

    /**
     * Moves a project under {@code parentId} (null for top level), in front of the sibling
     * {@code beforeId} or last, and renumbers that level's order. Edits the projects in place.
     * Returns false, changing nothing, when the project is unknown or the move would put it
     * inside itself.
     */
    public static boolean moveProjectTo(List<Project> projects, String projectId, String parentId,
                                        String beforeId) {
        if (projectId == null || !idSet(projects).contains(projectId)) return false;
        if (projectId.equals(parentId)) return false;
        if (parentId != null && getDescendantIds(projects, projectId).contains(parentId)) return false;

        List<String> siblingIds = new ArrayList<>();
        for (Project sibling : getChildProjects(projects, parentId)) siblingIds.add(sibling.id);
        List<String> placed = placeId(siblingIds, projectId, beforeId);
        for (Project project : projects) {
            int index = placed.indexOf(project.id);
            if (index == -1) continue;
            project.order = (double) index;
            if (project.id.equals(projectId)) project.parentId = parentId;
        }
        return true;
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

    /** Depth-first, in sibling order within each level: what a picker or the tree shows top to bottom. */
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
