package com.karson.todoapp.ui;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.karson.todoapp.R;
import com.karson.todoapp.model.RepeatRule;

/** Edits an existing task's repeat rule. Adapts the web app's inline Repeating-tab editor. */
public class RepeatEditorSheet extends BottomSheetDialogFragment {

    public interface Host {
        RepeatRule repeatRuleFor(String taskId);
        boolean isEventTask(String taskId);
        void onRepeatRuleSaved(String taskId, RepeatRule rule);
    }

    private static final String ARG_TASK_ID = "taskId";

    private Host host;
    private RepeatControls controls;

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

        if (host != null && taskId != null) {
            RepeatRule rule = host.repeatRuleFor(taskId);
            controls.setAllowRevealTime(!host.isEventTask(taskId));
            if (rule != null) controls.loadFrom(rule);
        }

        v.findViewById(R.id.repeat_cancel).setOnClickListener(x -> dismiss());
        v.findViewById(R.id.repeat_save).setOnClickListener(x -> {
            if (host != null && taskId != null) host.onRepeatRuleSaved(taskId, controls.toRule());
            dismiss();
        });
    }
}
