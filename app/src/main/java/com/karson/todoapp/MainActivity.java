package com.karson.todoapp;

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.karson.todoapp.auth.GoogleAuthManager;
import com.karson.todoapp.data.FirebaseTaskRepository;
import com.karson.todoapp.data.LocalTaskRepository;
import com.karson.todoapp.data.TaskRepository;
import com.karson.todoapp.logic.Categories;
import com.karson.todoapp.logic.Dates;
import com.karson.todoapp.logic.ProjectLogic;
import com.karson.todoapp.logic.TaskActions;
import com.karson.todoapp.model.Item;
import com.karson.todoapp.model.Project;
import com.karson.todoapp.ui.AddItemSheet;
import com.karson.todoapp.ui.RepeatEditorSheet;
import com.karson.todoapp.ui.Row;
import com.karson.todoapp.ui.TaskAdapter;
import com.karson.todoapp.widget.TodayWidgetProvider;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class MainActivity extends AppCompatActivity
        implements TaskAdapter.Listener, AddItemSheet.Host, RepeatEditorSheet.Host {

    private static final String PREFS = "todoapp-prefs";
    private static final String THEME_KEY = "todo-theme";
    private static final String COLLAPSED_KEY = "todo-collapsed-projects";

    private static final String[][] TABS = {
            {Categories.TODAY, "Today"},
            {Categories.TOMORROW, "Tomorrow"},
            {Categories.NEXT3, "Next 3 days"},
            {Categories.NEXT_MONTH, "Next month"},
            {Categories.LATER, "Later"},
            {Categories.PROJECTS, "Projects"},
            {Categories.REPEATING, "Repeating"},
            {Categories.COMPLETED, "Completed"},
    };

    private static final DateTimeFormatter DAY_DATE =
            DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.US);

    private TaskRepository repository;
    private GoogleAuthManager authManager;
    private final List<Item> tasks = new ArrayList<>();
    private final List<Project> projects = new ArrayList<>();
    private final Set<String> collapsed = new HashSet<>();
    private String activeTab = Categories.TODAY;
    private String searchQuery = "";

    private TaskAdapter adapter;
    private LinearLayout tabsContainer;
    private final List<View> tabViews = new ArrayList<>();
    private final List<TextView> tabCounts = new ArrayList<>();
    private TextView dayDate, dayTitle, dayCount, emptyState;
    private View searchBar, newProjectButton, searchClear;
    private EditText searchInput;
    private ImageButton themeButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        applyStoredTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        authManager = new GoogleAuthManager();
        repository = createRepository();
        repository.setListener(repoListener);

        View root = findViewById(R.id.root);
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.systemBars()
                            | androidx.core.view.WindowInsetsCompat.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        tasks.addAll(repository.loadTasks());
        projects.addAll(repository.loadProjects());
        collapsed.addAll(getSharedPreferences(PREFS, MODE_PRIVATE)
                .getStringSet(COLLAPSED_KEY, new HashSet<>()));

        dayDate = findViewById(R.id.day_date);
        dayTitle = findViewById(R.id.day_title);
        dayCount = findViewById(R.id.day_count);
        emptyState = findViewById(R.id.empty_state);
        searchBar = findViewById(R.id.search_bar);
        searchInput = findViewById(R.id.search_input);
        searchClear = findViewById(R.id.search_clear);
        themeButton = findViewById(R.id.theme_button);
        tabsContainer = findViewById(R.id.tabs_container);
        newProjectButton = findViewById(R.id.new_project_button);

        RecyclerView list = findViewById(R.id.todo_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        DividerItemDecoration divider = new DividerItemDecoration(this, DividerItemDecoration.VERTICAL);
        divider.setDrawable(getDrawable(R.drawable.divider_hairline));
        list.addItemDecoration(divider);
        adapter = new TaskAdapter(this);
        list.setAdapter(adapter);
        // Quick, crisp open/close: animate child rows in/out but keep the durations short so it
        // never drags, and skip change cross-fades so a row updating in place doesn't flicker.
        DefaultItemAnimator animator = new DefaultItemAnimator();
        animator.setAddDuration(150);
        animator.setRemoveDuration(120);
        animator.setMoveDuration(180);
        animator.setSupportsChangeAnimations(false);
        list.setItemAnimator(animator);

        buildTabs();
        updateThemeIcon();

        findViewById(R.id.new_item_button).setOnClickListener(v -> openAddSheet(null));
        newProjectButton.setOnClickListener(v -> createProject(null));
        findViewById(R.id.search_button).setOnClickListener(v -> toggleSearch());
        searchClear.setOnClickListener(v -> searchInput.setText(""));
        themeButton.setOnClickListener(v -> toggleTheme());
        findViewById(R.id.account_button).setOnClickListener(this::openAccountMenu);

        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                searchQuery = s.toString();
                searchClear.setVisibility(searchQuery.isEmpty() ? View.GONE : View.VISIBLE);
                render();
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        render();
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Safety net: if the session ended (sign-out, revoked token) bounce back to the gate.
        if (!authManager.isSignedIn()) {
            goToSignIn();
            return;
        }
        repository.start();
    }

    @Override
    protected void onStop() {
        super.onStop();
        repository.stop();
    }

    /** Signed in → Firebase (live sync); signed out → local JSON fallback. */
    private TaskRepository createRepository() {
        if (authManager.isSignedIn()) {
            return new FirebaseTaskRepository(authManager.currentUser().getUid());
        }
        return new LocalTaskRepository(this);
    }

    /** Remote changes (Firebase live listener) replace the in-memory lists and re-render. */
    private final TaskRepository.Listener repoListener = new TaskRepository.Listener() {
        @Override
        public void onTasksChanged(List<Item> remote) {
            tasks.clear();
            tasks.addAll(remote);
            render();
        }

        @Override
        public void onProjectsChanged(List<Project> remote) {
            projects.clear();
            projects.addAll(remote);
            render();
        }
    };

    private void goToSignIn() {
        Intent intent = new Intent(this, SignInActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        finish();
    }

    // ---------- Tabs ----------

    private void buildTabs() {
        LayoutInflater inflater = LayoutInflater.from(this);
        for (int i = 0; i < TABS.length; i++) {
            final String key = TABS[i][0];
            View chip = inflater.inflate(R.layout.item_tab, tabsContainer, false);
            ((TextView) chip.findViewById(R.id.tab_label)).setText(TABS[i][1]);
            chip.setSelected(key.equals(activeTab));
            chip.setOnClickListener(v -> setActiveTab(key));
            tabsContainer.addView(chip);
            tabViews.add(chip);
            tabCounts.add(chip.findViewById(R.id.tab_count));
        }
    }

    private void setActiveTab(String key) {
        activeTab = key;
        for (int i = 0; i < TABS.length; i++) {
            tabViews.get(i).setSelected(TABS[i][0].equals(key));
        }
        render();
    }

    // ---------- Rendering ----------

    private void render() {
        expireStoppedRepeats();
        LocalDateTime now = LocalDateTime.now();
        Set<String> projectIds = getProjectIds();
        List<Item> items = Categories.getItemsForTab(tasks, activeTab, searchQuery, now, projectIds);

        List<Row> rows;
        if (Categories.PROJECTS.equals(activeTab)) {
            rows = buildTree(items, projectIds);
            emptyState.setText(projects.isEmpty() ? "No projects yet." : "Nothing here.");
        } else {
            sortForTab(items);
            rows = new ArrayList<>();
            for (Item it : items) rows.add(Row.task(it, 0));
            emptyState.setText(R.string.empty_state);
        }

        adapter.submit(rows, activeTab, now);
        emptyState.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        newProjectButton.setVisibility(Categories.PROJECTS.equals(activeTab) ? View.VISIBLE : View.GONE);

        updateTabCounts(now, projectIds);
        updateDayHeading(items, now);

        // Keep the home-screen Today widget in step with any change rendered here.
        TodayWidgetProvider.notifyDataChanged(this);
    }

    /** Ascending by datetime; completed sinks to the bottom. Completed tab is descending. */
    private void sortForTab(List<Item> items) {
        items.sort((a, b) -> sortKey(a).compareTo(sortKey(b)));
        if (Categories.COMPLETED.equals(activeTab)) {
            java.util.Collections.reverse(items);
        } else {
            items.sort((a, b) -> Boolean.compare(a.completed, b.completed));
        }
    }

    private String sortKey(Item item) {
        String date = item.itemDate();
        if (date == null || date.isEmpty()) date = Dates.todayIso();
        String time = item.itemTime();
        if (time == null || time.isEmpty()) time = "00:00";
        return date + "T" + time;
    }

    // ---------- Project tree ----------

    private List<Row> buildTree(List<Item> items, Set<String> projectIds) {
        Map<String, List<Item>> byProject = new HashMap<>();
        for (Item it : items) {
            byProject.computeIfAbsent(it.projectId, k -> new ArrayList<>()).add(it);
        }
        String query = searchQuery.trim().toLowerCase(Locale.ROOT);
        List<Row> out = new ArrayList<>();
        renderLevel(null, 0, byProject, query, out);
        return out;
    }

    private void renderLevel(String parentId, int depth, Map<String, List<Item>> byProject,
                             String query, List<Row> out) {
        for (Project project : ProjectLogic.getChildProjects(projects, parentId)) {
            if (!projectMatches(project, byProject, query)) continue;

            List<String> subtree = new ArrayList<>();
            subtree.add(project.id);
            subtree.addAll(ProjectLogic.getDescendantIds(projects, project.id));
            int openCount = 0;
            for (String id : subtree) {
                for (Item it : byProject.getOrDefault(id, new ArrayList<>())) {
                    if (!it.completed) openCount++;
                }
            }

            boolean expanded = !query.isEmpty() || !collapsed.contains(project.id);
            out.add(Row.projectHead(project, depth, openCount, expanded));
            if (!expanded) continue;

            for (Item task : sortProjectTasks(byProject.getOrDefault(project.id, new ArrayList<>()))) {
                out.add(Row.task(task, depth + 1));
            }
            renderLevel(project.id, depth + 1, byProject, query, out);
        }
    }

    private boolean projectMatches(Project project, Map<String, List<Item>> byProject, String query) {
        if (query.isEmpty()) return true;
        if (project.name.toLowerCase(Locale.ROOT).contains(query)) return true;
        if (byProject.containsKey(project.id)) return true;
        for (Project child : ProjectLogic.getChildProjects(projects, project.id)) {
            if (projectMatches(child, byProject, query)) return true;
        }
        return false;
    }

    /** Open items first by date (undated last), struck-through items at the bottom. */
    private List<Item> sortProjectTasks(List<Item> items) {
        List<Item> sorted = new ArrayList<>(items);
        sorted.sort((a, b) -> {
            int byDone = Boolean.compare(a.completed, b.completed);
            if (byDone != 0) return byDone;
            return Long.compare(projectSortKey(a), projectSortKey(b));
        });
        return sorted;
    }

    private long projectSortKey(Item item) {
        if (!item.hasDate()) return Long.MAX_VALUE;
        String date = item.itemDate();
        if (date == null || date.isEmpty()) date = Dates.todayIso();
        String time = item.itemTime();
        LocalTime t = (time == null || time.isEmpty()) ? LocalTime.MIDNIGHT : LocalTime.parse(time);
        return LocalDateTime.of(LocalDate.parse(date), t).toEpochSecond(java.time.ZoneOffset.UTC);
    }

    // ---------- Headings / counts ----------

    private void updateTabCounts(LocalDateTime now, Set<String> projectIds) {
        for (int i = 0; i < TABS.length; i++) {
            String key = TABS[i][0];
            String text = "";
            if (!Categories.COMPLETED.equals(key)) {
                int open = 0;
                for (Item it : Categories.getItemsForTab(tasks, key, "", now, projectIds)) {
                    if (!it.completed) open++;
                }
                if (open > 0) text = String.valueOf(open);
            }
            tabCounts.get(i).setText(text);
        }
    }

    private void updateDayHeading(List<Item> items, LocalDateTime now) {
        dayDate.setText(now.format(DAY_DATE));
        dayTitle.setText(tabLabel(activeTab));

        int done = 0;
        for (Item it : items) if (it.completed) done++;
        int open = items.size() - done;

        if (Categories.COMPLETED.equals(activeTab)) {
            dayCount.setText(done + " done");
        } else if (Categories.REPEATING.equals(activeTab)) {
            dayCount.setText(items.size() + " active");
        } else {
            dayCount.setText(done > 0 ? (open + " open · " + done + " done") : (open + " open"));
        }
    }

    private String tabLabel(String key) {
        for (String[] t : TABS) if (t[0].equals(key)) return t[1];
        return "";
    }

    // ---------- Row actions ----------

    @Override
    public void onToggle(Item item) {
        // Shared with the home-screen Today widget so completing behaves identically in both.
        TaskActions.toggle(tasks, item.id, Dates.todayIso());
        persistTasksAndRender();
    }

    private void expireStoppedRepeats() {
        String today = Dates.todayIso();
        boolean changed = false;
        for (Item task : tasks) {
            if (task.repeatRule != null && task.repeatRule.stopDate != null
                    && !task.repeatRule.stopDate.isEmpty()
                    && task.repeatRule.stopDate.compareTo(today) < 0) {
                task.repeatRule = null;
                changed = true;
            }
        }
        if (changed) repository.saveTasks(tasks);
    }

    @Override
    public void onDismiss(Item item) {
        item.dismissed = true;
        persistTasksAndRender();
    }

    /** Commit an inline rename (web's commitTaskRename): re-resolve the task by id so a live Firebase
     *  snapshot swapping in a fresh instance mid-edit can't orphan it. Empty text cancels (no change);
     *  either way we re-render to leave edit mode. */
    @Override
    public void onRenameTask(String id, String newText) {
        String trimmed = newText.trim();
        if (!trimmed.isEmpty()) {
            for (Item t : tasks) {
                if (t.id.equals(id)) { t.text = trimmed; break; }
            }
            repository.saveTasks(tasks);
        }
        render();
    }

    @Override
    public void onMenu(Item item, View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);

        if (Categories.COMPLETED.equals(activeTab)) {
            popup.getMenu().add("Mark active").setOnMenuItemClickListener(mi -> { onToggle(item); return true; });
            popup.getMenu().add("Delete").setOnMenuItemClickListener(mi -> { deleteItem(item); return true; });
            popup.show();
            return;
        }

        if (Categories.REPEATING.equals(activeTab)) {
            popup.getMenu().add(R.string.edit_repeat_rule).setOnMenuItemClickListener(mi -> { openRepeatEditor(item.id); return true; });
            popup.getMenu().add("Delete").setOnMenuItemClickListener(mi -> { deleteItem(item); return true; });
            popup.show();
            return;
        }

        boolean onProjectsTab = Categories.PROJECTS.equals(activeTab);
        if (!onProjectsTab && item.noDue) {
            popup.getMenu().add("Show in Today").setOnMenuItemClickListener(mi -> { item.section = "today"; persistTasksAndRender(); return true; });
            popup.getMenu().add("Show in Later").setOnMenuItemClickListener(mi -> { item.section = "later"; persistTasksAndRender(); return true; });
        } else if (!onProjectsTab && !item.noDue) {
            popup.getMenu().add("Later today  +2h").setOnMenuItemClickListener(mi -> { postponeTask(item, 1.0 / 12.0); return true; });
            popup.getMenu().add("Tomorrow  +1d").setOnMenuItemClickListener(mi -> { postponeTask(item, 1); return true; });
            popup.getMenu().add("In 2 days  +2d").setOnMenuItemClickListener(mi -> { postponeTask(item, 2); return true; });
            popup.getMenu().add("In 3 days  +3d").setOnMenuItemClickListener(mi -> { postponeTask(item, 3); return true; });
        }

        // Project actions (match getTaskProjectMenuItems).
        if (!projects.isEmpty()) {
            boolean inProject = item.projectId != null && getProjectIds().contains(item.projectId);
            if (inProject && item.hasDate()) {
                String label = item.inDateTabs ? "Hide from date tabs" : "Show in date tabs";
                popup.getMenu().add(label).setOnMenuItemClickListener(mi -> {
                    item.inDateTabs = !item.inDateTabs;
                    persistTasksAndRender();
                    return true;
                });
            }
            String moveLabel = inProject ? "Move to project…" : "Add to project…";
            popup.getMenu().add(moveLabel).setOnMenuItemClickListener(mi -> {
                openTaskProjectPicker(item, anchor, inProject);
                return true;
            });
        }

        popup.getMenu().add("Delete").setOnMenuItemClickListener(mi -> { deleteItem(item); return true; });
        popup.show();
    }

    private void openTaskProjectPicker(Item item, View anchor, boolean inProject) {
        PopupMenu picker = new PopupMenu(this, anchor);
        if (inProject) {
            picker.getMenu().add("No project").setOnMenuItemClickListener(mi -> {
                moveTaskToProject(item, null);
                return true;
            });
        }
        for (ProjectLogic.FlatProject fp : ProjectLogic.flattenProjects(projects)) {
            if (fp.project.id.equals(item.projectId)) continue;
            String label = String.join(" › ", fp.path);
            String targetId = fp.project.id;
            picker.getMenu().add(label).setOnMenuItemClickListener(mi -> {
                moveTaskToProject(item, targetId);
                return true;
            });
        }
        picker.show();
    }

    /** Postpone a dated task: less than a day hides it from Today; whole days shift its date. */
    private void postponeTask(Item item, double days) {
        if (item.noDue) return;
        if (days < 1) {
            item.minShowTime = System.currentTimeMillis() + (long) (days * 24 * 60 * 60 * 1000);
        } else if (item.isEvent()) {
            item.startDate = Dates.addInterval(item.startDate, (int) days, "day");
        } else {
            item.dueDate = Dates.addInterval(item.dueDate, (int) days, "day");
        }
        persistTasksAndRender();
    }

    private void deleteItem(Item item) {
        new AlertDialog.Builder(this)
                .setMessage(R.string.delete_item_confirm)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (d, w) -> {
                    tasks.remove(item);
                    persistTasksAndRender();
                })
                .show();
    }

    // ---------- Project actions ----------

    @Override
    public void onProjectToggle(Project project) {
        setCollapsed(project.id, !collapsed.contains(project.id));
        render();
    }

    @Override
    public void onProjectMenu(Project project, View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add("Add task").setOnMenuItemClickListener(mi -> { openAddSheet(project.id); return true; });
        popup.getMenu().add("Add sub-project").setOnMenuItemClickListener(mi -> { createProject(project.id); return true; });
        popup.getMenu().add("Rename").setOnMenuItemClickListener(mi -> { renameProject(project); return true; });
        popup.getMenu().add("Move under…").setOnMenuItemClickListener(mi -> { openProjectMovePicker(project, anchor); return true; });
        popup.getMenu().add("Delete").setOnMenuItemClickListener(mi -> { deleteProject(project); return true; });
        popup.show();
    }

    private void openProjectMovePicker(Project project, View anchor) {
        Set<String> excluded = new HashSet<>();
        excluded.add(project.id);
        if (project.parentId != null) excluded.add(project.parentId);
        excluded.addAll(ProjectLogic.getDescendantIds(projects, project.id));

        PopupMenu picker = new PopupMenu(this, anchor);
        boolean any = false;
        if (project.parentId != null) {
            picker.getMenu().add(R.string.top_level).setOnMenuItemClickListener(mi -> { moveProject(project, null); return true; });
            any = true;
        }
        for (ProjectLogic.FlatProject fp : ProjectLogic.flattenProjects(projects)) {
            if (excluded.contains(fp.project.id)) continue;
            String label = String.join(" › ", fp.path);
            String targetId = fp.project.id;
            picker.getMenu().add(label).setOnMenuItemClickListener(mi -> { moveProject(project, targetId); return true; });
            any = true;
        }
        if (!any) {
            new AlertDialog.Builder(this).setMessage(R.string.no_other_project)
                    .setPositiveButton(android.R.string.ok, null).show();
            return;
        }
        picker.show();
    }

    private void createProject(String parentId) {
        promptText(parentId != null ? R.string.sub_project_name : R.string.new_project_name, "", name -> {
            Project project = new Project(createId(), name, parentId);
            projects.add(project);
            if (parentId != null) setCollapsed(parentId, false);
            repository.saveProjects(projects);
            render();
        });
    }

    private void renameProject(Project project) {
        promptText(R.string.rename_project, project.name, name -> {
            if (name.equals(project.name)) return;
            project.name = name;
            repository.saveProjects(projects);
            render();
        });
    }

    private void moveProject(Project project, String parentId) {
        project.parentId = parentId;
        if (parentId != null) setCollapsed(parentId, false);
        repository.saveProjects(projects);
        render();
    }

    private void deleteProject(Project project) {
        Project parent = findProject(project.parentId);
        String outcome = parent != null
                ? "Its tasks and sub-projects move to \"" + parent.name + "\"."
                : "Its sub-projects move to the top level and its tasks leave the project.";
        new AlertDialog.Builder(this)
                .setMessage("Delete \"" + project.name + "\"? " + outcome)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (d, w) -> {
                    String newParentId = parent != null ? parent.id : null;
                    projects.remove(project);
                    for (Project p : projects) {
                        if (project.id.equals(p.parentId)) p.parentId = newParentId;
                    }
                    for (Item t : tasks) {
                        if (project.id.equals(t.projectId)) assignProject(t, newParentId);
                    }
                    setCollapsed(project.id, false);
                    repository.saveProjects(projects);
                    repository.saveTasks(tasks);
                    render();
                })
                .show();
    }

    /** Puts a task in the given project (or removes it), clearing the date-tab flag when unassigned. */
    private void assignProject(Item task, String projectId) {
        if (projectId != null) {
            task.projectId = projectId;
        } else {
            task.projectId = null;
            task.inDateTabs = false;
        }
    }

    private void moveTaskToProject(Item task, String projectId) {
        assignProject(task, projectId);
        if (projectId != null) setCollapsed(projectId, false);
        persistTasksAndRender();
    }

    private Project findProject(String id) {
        if (id == null) return null;
        for (Project p : projects) if (p.id.equals(id)) return p;
        return null;
    }

    private Set<String> getProjectIds() {
        return ProjectLogic.idSet(projects);
    }

    private void setCollapsed(String projectId, boolean isCollapsed) {
        if (isCollapsed) collapsed.add(projectId);
        else collapsed.remove(projectId);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putStringSet(COLLAPSED_KEY, new HashSet<>(collapsed)).apply();
    }

    // ---------- Helpers ----------

    private interface OnText { void run(String text); }

    private void promptText(int titleRes, String initial, OnText cb) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setText(initial);
        input.setSelection(input.getText().length());

        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(pad, pad / 2, pad, 0);
        wrap.addView(input);

        new AlertDialog.Builder(this)
                .setTitle(titleRes)
                .setView(wrap)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (!name.isEmpty()) cb.run(name);
                })
                .show();
    }

    private static String createId() {
        return TaskActions.createId();
    }

    private void persistTasksAndRender() {
        repository.saveTasks(tasks);
        render();
    }

    // ---------- Add sheet ----------

    private void openAddSheet(String projectId) {
        AddItemSheet sheet = projectId != null ? AddItemSheet.forProject(projectId) : new AddItemSheet();
        sheet.show(getSupportFragmentManager(), "add-item");
    }

    @Override
    public void onAddItem(Item item) {
        tasks.add(item);
        persistTasksAndRender();
    }

    @Override
    public List<Project> currentProjects() {
        return projects;
    }

    // ---------- Repeat editor ----------

    private void openRepeatEditor(String taskId) {
        RepeatEditorSheet.forTask(taskId).show(getSupportFragmentManager(), "repeat-editor");
    }

    private Item findTask(String id) {
        for (Item t : tasks) if (id.equals(t.id)) return t;
        return null;
    }

    @Override
    public com.karson.todoapp.model.RepeatRule repeatRuleFor(String taskId) {
        Item t = findTask(taskId);
        return t != null ? t.repeatRule : null;
    }

    @Override
    public boolean isEventTask(String taskId) {
        Item t = findTask(taskId);
        return t != null && t.isEvent();
    }

    @Override
    public void onRepeatRuleSaved(String taskId, com.karson.todoapp.model.RepeatRule rule) {
        Item t = findTask(taskId);
        if (t != null) {
            t.repeatRule = rule;
            persistTasksAndRender();
        }
    }

    // ---------- Account: import / export ----------

    private void openAccountMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add("Copy tasks (export)").setOnMenuItemClickListener(mi -> { exportToClipboard(); return true; });
        popup.getMenu().add("Import from clipboard").setOnMenuItemClickListener(mi -> { importFromClipboard(); return true; });
        popup.getMenu().add(R.string.sign_out).setOnMenuItemClickListener(mi -> { signOut(); return true; });
        popup.show();
    }

    private void signOut() {
        repository.stop(); // detach live listeners before auth goes away
        authManager.signOut(this, this::goToSignIn);
    }

    private void exportToClipboard() {
        Backup backup = new Backup();
        backup.tasks = tasks;
        backup.projects = projects;
        String json = new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(backup);

        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(android.content.ClipData.newPlainText("ToDo tasks", json));
        toast("Tasks copied to clipboard");
    }

    private void importFromClipboard() {
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm.getPrimaryClip() == null || cm.getPrimaryClip().getItemCount() == 0) {
            toast("Clipboard is empty");
            return;
        }
        CharSequence text = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
        try {
            com.google.gson.Gson gson = new com.google.gson.Gson();
            com.google.gson.JsonElement root = com.google.gson.JsonParser.parseString(text.toString());

            java.lang.reflect.Type taskListType = new com.google.gson.reflect.TypeToken<ArrayList<Item>>() {}.getType();
            List<Item> importedTasks;
            List<Project> importedProjects = null;

            if (root.isJsonArray()) {
                // Older exports are a bare task array.
                importedTasks = gson.fromJson(root, taskListType);
            } else {
                com.google.gson.JsonObject obj = root.getAsJsonObject();
                importedTasks = gson.fromJson(obj.get("tasks"), taskListType);
                if (obj.has("projects") && obj.get("projects").isJsonArray()) {
                    java.lang.reflect.Type projListType = new com.google.gson.reflect.TypeToken<ArrayList<Project>>() {}.getType();
                    importedProjects = normalizeProjects(gson.fromJson(obj.get("projects"), projListType));
                }
            }
            if (importedTasks == null) throw new IllegalArgumentException("No tasks");

            for (Item it : importedTasks) {
                if (it.id == null || it.id.isEmpty()) it.id = createId();
            }
            tasks.clear();
            tasks.addAll(importedTasks);
            repository.saveTasks(tasks);

            if (importedProjects != null) {
                projects.clear();
                projects.addAll(importedProjects);
                repository.saveProjects(projects);
            }
            render();
            toast("Tasks imported from clipboard");
        } catch (Exception e) {
            toast("Failed to import. Make sure valid task JSON is on your clipboard.");
        }
    }

    private List<Project> normalizeProjects(List<Project> data) {
        List<Project> out = new ArrayList<>();
        if (data == null) return out;
        for (Project p : data) {
            if (p != null && p.id != null && p.name != null) {
                out.add(new Project(p.id, p.name, p.parentId));
            }
        }
        return out;
    }

    private void toast(String message) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show();
    }

    private static class Backup {
        List<Item> tasks;
        List<Project> projects;
    }

    // ---------- Search ----------

    private void toggleSearch() {
        boolean show = searchBar.getVisibility() != View.VISIBLE;
        searchBar.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            searchInput.requestFocus();
        } else {
            searchInput.setText("");
            searchQuery = "";
            render();
        }
    }

    // ---------- Theme ----------

    static void applyStoredTheme(Context context) {
        String theme = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(THEME_KEY, null);
        if ("dark".equals(theme)) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        } else if ("light".equals(theme)) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        }
    }

    private void toggleTheme() {
        String next = isNightMode() ? "light" : "dark";
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(THEME_KEY, next).apply();
        applyStoredTheme(this);
    }

    private void updateThemeIcon() {
        themeButton.setImageResource(isNightMode() ? R.drawable.ic_moon : R.drawable.ic_sun);
    }

    private boolean isNightMode() {
        int mode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }
}
