package com.karson.todoapp.ui;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.karson.todoapp.R;
import com.karson.todoapp.logic.Dates;
import com.karson.todoapp.logic.ProjectLogic;
import com.karson.todoapp.logic.RepeatRules;
import com.karson.todoapp.model.Item;
import com.karson.todoapp.model.Project;
import com.karson.todoapp.model.RepeatRule;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The "New item" bottom sheet: task/event, dates, times, no-due + section, project. */
public class AddItemSheet extends BottomSheetDialogFragment {

    public interface Host {
        void onAddItem(Item item);
        List<Project> currentProjects();
    }

    private static final String ARG_PROJECT_ID = "projectId";

    private static final DateTimeFormatter DISPLAY_DATE =
            DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US);

    private String type = "task";
    private String dueDate = "", dueTime = "";
    private String startDate = "", startTime = "", endTime = "";

    private TextView typeTask, typeEvent;
    private View taskFields, eventFields, taskSectionField, projectRow, dateTabsSwitch;
    private TextView dueDateView, dueTimeView, startDateView, startTimeView, endTimeView;
    private SwitchMaterial noDue, inDateTabs, repeatSwitch;
    private Spinner sectionSpinner, projectSpinner;
    private View repeatControlsView;
    private RepeatControls repeatControls;

    private final List<String> projectIds = new ArrayList<>(); // parallel to projectSpinner entries
    private Host host;

    /** Open the sheet preselecting a project (and starting undated, like a project's "Add task"). */
    public static AddItemSheet forProject(String projectId) {
        AddItemSheet sheet = new AddItemSheet();
        Bundle args = new Bundle();
        args.putString(ARG_PROJECT_ID, projectId);
        sheet.setArguments(args);
        return sheet;
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (context instanceof Host) host = (Host) context;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.sheet_add_item, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        typeTask = v.findViewById(R.id.type_task);
        typeEvent = v.findViewById(R.id.type_event);
        taskFields = v.findViewById(R.id.task_fields);
        eventFields = v.findViewById(R.id.event_fields);
        taskSectionField = v.findViewById(R.id.task_section_field);
        projectRow = v.findViewById(R.id.project_row);
        dateTabsSwitch = v.findViewById(R.id.date_tabs_switch);
        dueDateView = v.findViewById(R.id.due_date);
        dueTimeView = v.findViewById(R.id.due_time);
        startDateView = v.findViewById(R.id.start_date);
        startTimeView = v.findViewById(R.id.start_time);
        endTimeView = v.findViewById(R.id.end_time);
        noDue = v.findViewById(R.id.no_due);
        inDateTabs = v.findViewById(R.id.in_date_tabs);
        sectionSpinner = v.findViewById(R.id.task_section);
        projectSpinner = v.findViewById(R.id.project_select);
        repeatSwitch = v.findViewById(R.id.repeat_task);
        repeatControlsView = v.findViewById(R.id.repeat_controls);
        repeatControls = new RepeatControls(repeatControlsView);

        sectionSpinner.setAdapter(new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_dropdown_item, new String[]{"Today", "Later"}));

        populateProjects();

        typeTask.setOnClickListener(x -> setType("task"));
        typeEvent.setOnClickListener(x -> setType("event"));

        dueDateView.setOnClickListener(x -> pickDate(dueDate, iso -> { dueDate = iso; renderDate(dueDateView, iso); }));
        dueTimeView.setOnClickListener(x -> pickTime(dueTime, iso -> { dueTime = iso; renderTime(dueTimeView, iso); }));
        startDateView.setOnClickListener(x -> pickDate(startDate, iso -> { startDate = iso; renderDate(startDateView, iso); }));
        startTimeView.setOnClickListener(x -> pickTime(startTime, iso -> {
            startTime = iso;
            renderTime(startTimeView, iso);
            autoFillEndTime();
        }));
        endTimeView.setOnClickListener(x -> pickTime(endTime, iso -> { endTime = iso; renderTime(endTimeView, iso); }));

        noDue.setOnCheckedChangeListener((btn, checked) -> { updateNoDue(checked); updateDateTabsSwitch(); });
        repeatSwitch.setOnCheckedChangeListener((btn, checked) ->
                repeatControlsView.setVisibility(checked ? View.VISIBLE : View.GONE));
        projectSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View vw, int pos, long id) { updateDateTabsSwitch(); }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });

        v.findViewById(R.id.sheet_cancel).setOnClickListener(x -> dismiss());
        v.findViewById(R.id.sheet_add).setOnClickListener(x -> submit(v));

        setType("task");
        renderDate(dueDateView, dueDate);
        renderTime(dueTimeView, dueTime);
        renderDate(startDateView, startDate);
        renderTime(startTimeView, startTime);
        renderTime(endTimeView, endTime);

        // A project's "Add task" preselects it and starts undated.
        String preselect = getArguments() != null ? getArguments().getString(ARG_PROJECT_ID) : null;
        if (preselect != null) {
            int index = projectIds.indexOf(preselect);
            if (index >= 0) projectSpinner.setSelection(index);
            noDue.setChecked(true);
        }
        updateDateTabsSwitch();
    }

    private void populateProjects() {
        List<Project> projects = host != null ? host.currentProjects() : new ArrayList<>();
        projectRow.setVisibility(projects.isEmpty() ? View.GONE : View.VISIBLE);

        List<String> labels = new ArrayList<>();
        projectIds.clear();
        labels.add(getString(R.string.no_project));
        projectIds.add("");
        for (ProjectLogic.FlatProject fp : ProjectLogic.flattenProjects(projects)) {
            labels.add(String.join(" › ", fp.path));
            projectIds.add(fp.project.id);
        }
        projectSpinner.setAdapter(new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_dropdown_item, labels));
    }

    private void setType(String next) {
        type = next;
        boolean event = "event".equals(next);
        typeTask.setSelected(!event);
        typeEvent.setSelected(event);
        taskFields.setVisibility(event || noDue.isChecked() ? View.GONE : View.VISIBLE);
        v(R.id.task_section_row).setVisibility(event ? View.GONE : View.VISIBLE);
        eventFields.setVisibility(event ? View.VISIBLE : View.GONE);
        if (repeatControls != null) repeatControls.setAllowRevealTime(!event);
        updateDateTabsSwitch();
    }

    private void updateNoDue(boolean checked) {
        taskFields.setVisibility(checked || "event".equals(type) ? View.GONE : View.VISIBLE);
        taskSectionField.setVisibility(checked ? View.VISIBLE : View.GONE);
    }

    /** The "also show in date tabs" switch only makes sense for a dated item filed in a project. */
    private void updateDateTabsSwitch() {
        boolean hasProject = selectedProjectId() != null;
        boolean dated = "event".equals(type) || !noDue.isChecked();
        dateTabsSwitch.setVisibility(hasProject && dated ? View.VISIBLE : View.GONE);
    }

    private String selectedProjectId() {
        int pos = projectSpinner.getSelectedItemPosition();
        if (pos < 0 || pos >= projectIds.size()) return null;
        String id = projectIds.get(pos);
        return id.isEmpty() ? null : id;
    }

    private void autoFillEndTime() {
        if (startTime.isEmpty() || !endTime.isEmpty()) return;
        LocalTime end = LocalTime.parse(startTime).plusHours(1);
        endTime = String.format(Locale.US, "%02d:%02d", end.getHour(), end.getMinute());
        renderTime(endTimeView, endTime);
    }

    private void submit(View root) {
        TextView input = root.findViewById(R.id.todo_input);
        String text = input.getText().toString().trim();
        if (text.isEmpty()) {
            Toast.makeText(requireContext(), R.string.title_hint, Toast.LENGTH_SHORT).show();
            return;
        }

        Item item = new Item();
        item.id = createTaskId();
        item.type = type;
        item.text = text;
        item.completed = false;

        RepeatRule rule = repeatSwitch.isChecked() ? repeatControls.toRule() : null;
        String today = Dates.todayIso();

        if ("event".equals(type)) {
            if (startTime.isEmpty() || endTime.isEmpty()) {
                Toast.makeText(requireContext(), R.string.events_need_times, Toast.LENGTH_SHORT).show();
                return;
            }
            String sd = Dates.normalizeDate(startDate.isEmpty() ? today : startDate);
            if (rule != null) sd = RepeatRules.nextValidStartDate(sd, rule, item);
            item.startDate = sd;
            item.startTime = startTime;
            item.endTime = endTime;
        } else {
            boolean nd = noDue.isChecked();
            String base = Dates.normalizeDate(nd ? today : (dueDate.isEmpty() ? today : dueDate));
            if (rule != null) base = RepeatRules.nextValidStartDate(base, rule, item);
            item.noDue = nd;
            item.section = nd ? (sectionSpinner.getSelectedItemPosition() == 1 ? "later" : "today") : "today";
            item.dueDate = nd ? today : base;
            item.dueTime = nd ? "" : dueTime;
        }
        item.repeatRule = rule;

        String projectId = selectedProjectId();
        if (projectId != null) {
            item.projectId = projectId;
            item.inDateTabs = inDateTabs.isChecked();
        }

        if (host != null) host.onAddItem(item);
        dismiss();
    }

    private static String createTaskId() {
        return System.currentTimeMillis() + "-" + Integer.toHexString((int) (Math.random() * 0xffffff));
    }

    // ---- date / time pickers ----

    private interface OnPicked { void run(String iso); }

    private void pickDate(String currentIso, OnPicked cb) {
        LocalDate base = currentIso.isEmpty() ? LocalDate.now() : LocalDate.parse(currentIso);
        new DatePickerDialog(requireContext(),
                (view, year, month, day) -> cb.run(LocalDate.of(year, month + 1, day).toString()),
                base.getYear(), base.getMonthValue() - 1, base.getDayOfMonth()).show();
    }

    private void pickTime(String currentIso, OnPicked cb) {
        LocalTime base = currentIso.isEmpty() ? LocalTime.of(9, 0) : LocalTime.parse(currentIso);
        new TimePickerDialog(requireContext(),
                (view, hour, minute) -> cb.run(String.format(Locale.US, "%02d:%02d", hour, minute)),
                base.getHour(), base.getMinute(), true).show();
    }

    private void renderDate(TextView view, String iso) {
        if (iso == null || iso.isEmpty()) {
            view.setText(R.string.pick_date);
            view.setTextColor(ContextCompat.getColor(requireContext(), R.color.muted));
        } else {
            view.setText(LocalDate.parse(iso).format(DISPLAY_DATE));
            view.setTextColor(ContextCompat.getColor(requireContext(), R.color.fg));
        }
    }

    private void renderTime(TextView view, String iso) {
        if (iso == null || iso.isEmpty()) {
            view.setText(R.string.pick_time);
            view.setTextColor(ContextCompat.getColor(requireContext(), R.color.muted));
        } else {
            view.setText(iso);
            view.setTextColor(ContextCompat.getColor(requireContext(), R.color.fg));
        }
    }

    private View v(int id) {
        return requireView().findViewById(id);
    }
}
