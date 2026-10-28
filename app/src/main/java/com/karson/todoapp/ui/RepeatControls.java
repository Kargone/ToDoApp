package com.karson.todoapp.ui;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.karson.todoapp.R;
import com.karson.todoapp.logic.RepeatRules;
import com.karson.todoapp.model.RepeatRule;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Binds the shared view_repeat_controls layout to a RepeatRule. Used by the add sheet and editor. */
public class RepeatControls {
    private static final String[] UNITS = {"day", "week", "month", "year"};
    private static final String[] UNIT_LABELS = {"Day(s)", "Week(s)", "Month(s)", "Year(s)"};
    private static final String[] PATTERNS = {"days", "weekday"};
    private static final String[] PATTERN_LABELS = {"Days of month", "Week of month"};

    private final Context context;
    private final EditText interval, monthdays;
    private final Spinner unit, monthPattern;
    private final TextView revealTimeView, stopDateView;
    private final View revealTimeRow, weekdayField, monthdayField, monthDaysField, monthWeekField, monthWeekdayField;
    private final LinearLayout weekdayPills, monthWeekPills, monthWeekdayPills;

    private String revealTime = "", stopDate = "";
    private boolean allowRevealTime = true;

    public RepeatControls(View root) {
        this.context = root.getContext();
        interval = root.findViewById(R.id.repeat_interval);
        monthdays = root.findViewById(R.id.repeat_monthdays);
        unit = root.findViewById(R.id.repeat_unit);
        monthPattern = root.findViewById(R.id.repeat_month_pattern);
        revealTimeView = root.findViewById(R.id.repeat_time);
        stopDateView = root.findViewById(R.id.repeat_end);
        revealTimeRow = root.findViewById(R.id.repeat_time_row);
        weekdayField = root.findViewById(R.id.repeat_weekday_field);
        monthdayField = root.findViewById(R.id.repeat_monthday_field);
        monthDaysField = root.findViewById(R.id.repeat_month_days_field);
        monthWeekField = root.findViewById(R.id.repeat_month_week_field);
        monthWeekdayField = root.findViewById(R.id.repeat_month_weekday_field);
        weekdayPills = root.findViewById(R.id.weekday_pills);
        monthWeekPills = root.findViewById(R.id.month_week_pills);
        monthWeekdayPills = root.findViewById(R.id.month_weekday_pills);

        unit.setAdapter(spinnerAdapter(UNIT_LABELS));
        monthPattern.setAdapter(spinnerAdapter(PATTERN_LABELS));

        unit.setOnItemSelectedListener(onSelect(this::updateVisibility));
        monthPattern.setOnItemSelectedListener(onSelect(this::updateVisibility));
        bindPills(weekdayPills);
        bindPills(monthWeekPills);
        bindPills(monthWeekdayPills);

        revealTimeView.setOnClickListener(v -> pickTime(revealTime, iso -> { revealTime = iso; renderTime(revealTimeView, iso); }));
        stopDateView.setOnClickListener(v -> pickDate(stopDate, iso -> { stopDate = iso; renderDate(stopDateView, iso); }));

        resetDefaults();
    }

    /** Reveal time only applies to tasks, so events hide it. */
    public void setAllowRevealTime(boolean allow) {
        this.allowRevealTime = allow;
        revealTimeRow.setVisibility(allow ? View.VISIBLE : View.INVISIBLE);
    }

    private void resetDefaults() {
        interval.setText("1");
        unit.setSelection(0);
        monthPattern.setSelection(0);
        monthdays.setText("");
        revealTime = ""; renderTime(revealTimeView, "");
        stopDate = ""; renderDate(stopDateView, "");
        setPills(weekdayPills, java.util.Arrays.asList(0, 1, 2, 3, 4, 5, 6));
        setPills(monthWeekPills, java.util.Arrays.asList(1));
        setPills(monthWeekdayPills, java.util.Arrays.asList(0));
        updateVisibility();
    }

    public void loadFrom(RepeatRule rule) {
        interval.setText(String.valueOf(Math.max(1, rule.interval)));
        unit.setSelection(indexOf(UNITS, rule.unit, 0));
        monthPattern.setSelection(indexOf(PATTERNS, rule.monthPattern, 0));
        revealTime = rule.revealTime != null ? rule.revealTime : "";
        renderTime(revealTimeView, revealTime);
        stopDate = rule.stopDate != null ? rule.stopDate : "";
        renderDate(stopDateView, stopDate);
        setPills(weekdayPills, rule.daysOfWeek != null ? rule.daysOfWeek : java.util.Arrays.asList(0, 1, 2, 3, 4, 5, 6));
        monthdays.setText(join(rule.daysOfMonth));
        setPills(monthWeekPills, rule.monthWeeks != null && !rule.monthWeeks.isEmpty() ? rule.monthWeeks : java.util.Arrays.asList(1));
        setPills(monthWeekdayPills, rule.monthWeekdays != null && !rule.monthWeekdays.isEmpty() ? rule.monthWeekdays : java.util.Arrays.asList(0));
        updateVisibility();
    }

    public RepeatRule toRule() {
        RepeatRule r = new RepeatRule();
        r.interval = Math.max(1, parseInt(interval.getText().toString(), 1));
        r.unit = UNITS[unit.getSelectedItemPosition()];
        r.stopDate = stopDate;
        r.revealTime = allowRevealTime ? revealTime : "";
        r.daysOfWeek = getPills(weekdayPills);
        r.daysOfMonth = RepeatRules.parseMonthDays(monthdays.getText().toString());
        r.monthPattern = PATTERNS[monthPattern.getSelectedItemPosition()];
        r.monthWeeks = getPills(monthWeekPills);
        r.monthWeekdays = getPills(monthWeekdayPills);
        return r;
    }

    private void updateVisibility() {
        String u = UNITS[unit.getSelectedItemPosition()];
        boolean isMonth = "month".equals(u);
        boolean weekdayPattern = isMonth && "weekday".equals(PATTERNS[monthPattern.getSelectedItemPosition()]);
        weekdayField.setVisibility("week".equals(u) ? View.VISIBLE : View.GONE);
        monthdayField.setVisibility(isMonth ? View.VISIBLE : View.GONE);
        monthDaysField.setVisibility(isMonth && !weekdayPattern ? View.VISIBLE : View.GONE);
        monthWeekField.setVisibility(weekdayPattern ? View.VISIBLE : View.GONE);
        monthWeekdayField.setVisibility(weekdayPattern ? View.VISIBLE : View.GONE);
    }

    // ---- pills ----

    private void bindPills(LinearLayout container) {
        for (int i = 0; i < container.getChildCount(); i++) {
            View pill = container.getChildAt(i);
            pill.setOnClickListener(v -> v.setSelected(!v.isSelected()));
        }
    }

    private void setPills(LinearLayout container, List<Integer> selected) {
        for (int i = 0; i < container.getChildCount(); i++) {
            View pill = container.getChildAt(i);
            int value = Integer.parseInt(pill.getTag().toString());
            pill.setSelected(selected.contains(value));
        }
    }

    private List<Integer> getPills(LinearLayout container) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < container.getChildCount(); i++) {
            View pill = container.getChildAt(i);
            if (pill.isSelected()) out.add(Integer.parseInt(pill.getTag().toString()));
        }
        return out;
    }

    // ---- helpers ----

    private ArrayAdapter<String> spinnerAdapter(String[] values) {
        return new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, values);
    }

    private AdapterView.OnItemSelectedListener onSelect(Runnable r) {
        return new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { r.run(); }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        };
    }

    private interface OnPicked { void run(String iso); }

    private void pickTime(String current, OnPicked cb) {
        LocalTime base = current.isEmpty() ? LocalTime.of(9, 0) : LocalTime.parse(current);
        new TimePickerDialog(context, (view, h, m) -> cb.run(String.format(Locale.US, "%02d:%02d", h, m)),
                base.getHour(), base.getMinute(), true).show();
    }

    private void pickDate(String current, OnPicked cb) {
        LocalDate base = current.isEmpty() ? LocalDate.now() : LocalDate.parse(current);
        new DatePickerDialog(context, (view, y, mo, d) -> cb.run(LocalDate.of(y, mo + 1, d).toString()),
                base.getYear(), base.getMonthValue() - 1, base.getDayOfMonth()).show();
    }

    private void renderTime(TextView view, String iso) {
        boolean empty = iso == null || iso.isEmpty();
        view.setText(empty ? context.getString(R.string.pick_time) : iso);
        view.setTextColor(ContextCompat.getColor(context, empty ? R.color.muted : R.color.fg));
    }

    private void renderDate(TextView view, String iso) {
        boolean empty = iso == null || iso.isEmpty();
        view.setText(empty ? context.getString(R.string.pick_date) : iso);
        view.setTextColor(ContextCompat.getColor(context, empty ? R.color.muted : R.color.fg));
    }

    private static int parseInt(String s, int fallback) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return fallback; }
    }

    private static int indexOf(String[] arr, String value, int fallback) {
        for (int i = 0; i < arr.length; i++) if (arr[i].equals(value)) return i;
        return fallback;
    }

    private static String join(List<Integer> list) {
        if (list == null || list.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(list.get(i));
        }
        return sb.toString();
    }
}
