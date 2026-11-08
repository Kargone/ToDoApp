package com.karson.todoapp.ui;

import android.app.TimePickerDialog;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.karson.todoapp.R;
import com.karson.todoapp.logic.ViewLogic;
import com.karson.todoapp.model.RepeatRule;

import java.time.LocalTime;
import java.util.Locale;

/** Edits an existing task's repeat rule (and its alarm). Adapts the web app's inline Repeating-tab editor. */
public class RepeatEditorSheet extends BottomSheetDialogFragment {

    public interface Host {
        RepeatRule repeatRuleFor(String taskId);
        boolean isEventTask(String taskId);
        /** The task's current alarm time ("HH:mm"), or null when it has none. */
        String alarmTimeFor(String taskId);
        /** Save the edited repeat rule and alarm ("HH:mm", or null to clear the alarm). */
        void onRepeatRuleSaved(String taskId, RepeatRule rule, String alarmTime);
    }

    private static final String ARG_TASK_ID = "taskId";

    private Host host;
    private RepeatControls controls;
    private SwitchMaterial alarmSwitch;
    private View alarmControls;
    private TextView alarmTimeView;
    private String alarmTime = "";

    public static RepeatEditorSheet forTask(String taskId) {
        RepeatEditorSheet sheet = new RepeatEditorSheet();
        Bundle args = new Bundle();
        args.putString(ARG_TASK_ID, taskId);
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
        return inflater.inflate(R.layout.sheet_repeat_editor, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        String taskId = getArguments() != null ? getArguments().getString(ARG_TASK_ID) : null;
        controls = new RepeatControls(v);
        alarmSwitch = v.findViewById(R.id.repeat_alarm_switch);
        alarmControls = v.findViewById(R.id.repeat_alarm_controls);
        alarmTimeView = v.findViewById(R.id.repeat_alarm_time_value);

        if (host != null && taskId != null) {
            RepeatRule rule = host.repeatRuleFor(taskId);
            controls.setAllowRevealTime(!host.isEventTask(taskId));
            if (rule != null) controls.loadFrom(rule);
            String existing = host.alarmTimeFor(taskId);
            alarmTime = existing == null ? "" : existing;
        }

        alarmSwitch.setChecked(!alarmTime.isEmpty());
        alarmControls.setVisibility(alarmTime.isEmpty() ? View.GONE : View.VISIBLE);
        renderAlarmTime();
        alarmSwitch.setOnCheckedChangeListener((btn, checked) -> {
            alarmControls.setVisibility(checked ? View.VISIBLE : View.GONE);
            if (checked && alarmTime.isEmpty()) { alarmTime = "09:00"; renderAlarmTime(); }
        });
        alarmTimeView.setOnClickListener(x -> pickAlarmTime());

        v.findViewById(R.id.repeat_cancel).setOnClickListener(x -> dismiss());
        v.findViewById(R.id.repeat_save).setOnClickListener(x -> {
            if (host != null && taskId != null) {
                String alarm = alarmSwitch.isChecked() && !alarmTime.isEmpty() ? alarmTime : null;
                host.onRepeatRuleSaved(taskId, controls.toRule(), alarm);
            }
            dismiss();
        });
    }

    private void pickAlarmTime() {
        LocalTime base = alarmTime.isEmpty() ? LocalTime.of(9, 0) : LocalTime.parse(alarmTime);
        new TimePickerDialog(requireContext(), (view, hour, minute) -> {
            alarmTime = String.format(Locale.US, "%02d:%02d", hour, minute);
            renderAlarmTime();
        }, base.getHour(), base.getMinute(), false).show();
    }

    private void renderAlarmTime() {
        alarmTimeView.setText(alarmTime.isEmpty() ? getString(R.string.pick_time) : ViewLogic.formatClock(alarmTime));
    }
}
