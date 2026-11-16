package com.karson.todoapp.logic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.karson.todoapp.model.Project;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Manual ordering of projects: mirrors the order cases in the web's project-logic.test.mjs. */
public class ProjectLogicOrderTest {

    private static List<Project> sample() {
        return new ArrayList<>(Arrays.asList(
                new Project("a", "Alpha", null),
                new Project("b", "Beta", null),
                new Project("c", "Gamma", null),
                new Project("b1", "Child", "b")));
    }

    private static List<String> childIds(List<Project> projects, String parentId) {
        List<String> ids = new ArrayList<>();
        for (Project p : ProjectLogic.getChildProjects(projects, parentId)) ids.add(p.id);
        return ids;
    }

    @Test
    public void orderedSiblingsComeFirstThenAlphabetical() {
        List<Project> projects = sample();
        projects.get(2).order = 0.0; // Gamma
        assertEquals(Arrays.asList("c", "a", "b"), childIds(projects, null));
    }

    @Test
    public void compareOrderPutsMissingLast() {
        assertTrue(ProjectLogic.compareOrder(0.0, 1.0) < 0);
        assertTrue(ProjectLogic.compareOrder(5.0, null) < 0);
        assertTrue(ProjectLogic.compareOrder(null, 5.0) > 0);
        assertEquals(0, ProjectLogic.compareOrder(null, null));
    }

    @Test
    public void placeIdMovesBeforeOrToEnd() {
        List<String> ids = Arrays.asList("a", "b", "c");
        assertEquals(Arrays.asList("c", "a", "b"), ProjectLogic.placeId(ids, "c", "a"));
        assertEquals(Arrays.asList("b", "c", "a"), ProjectLogic.placeId(ids, "a", null));
        assertEquals(Arrays.asList("a", "b", "c", "x"), ProjectLogic.placeId(ids, "x", "missing"));
    }

    @Test
    public void moveReordersWithinALevel() {
        List<Project> projects = sample();
        assertTrue(ProjectLogic.moveProjectTo(projects, "c", null, "a"));
        assertEquals(Arrays.asList("c", "a", "b"), childIds(projects, null));
        assertNull(projects.get(3).order); // other levels are left alone
    }

    @Test
    public void moveNestsAndPlacesLastByDefault() {
        List<Project> projects = sample();
        assertTrue(ProjectLogic.moveProjectTo(projects, "a", "b", null));
        assertEquals("b", projects.get(0).parentId);
        assertEquals(Arrays.asList("b1", "a"), childIds(projects, "b"));
        assertEquals(Arrays.asList("b", "c"), childIds(projects, null));
    }

    @Test
    public void moveOutToTopLevel() {
        List<Project> projects = sample();
        assertTrue(ProjectLogic.moveProjectTo(projects, "b1", null, "b"));
        assertNull(projects.get(3).parentId);
        assertEquals(Arrays.asList("a", "b1", "b", "c"), childIds(projects, null));
    }

    @Test
    public void moveIntoItselfOrADescendantIsRefused() {
        List<Project> projects = sample();
        assertFalse(ProjectLogic.moveProjectTo(projects, "b", "b", null));
        assertFalse(ProjectLogic.moveProjectTo(projects, "b", "b1", null));
        assertFalse(ProjectLogic.moveProjectTo(projects, "nope", null, null));
        assertNull(projects.get(1).parentId);
        assertNull(projects.get(1).order);
    }
}
