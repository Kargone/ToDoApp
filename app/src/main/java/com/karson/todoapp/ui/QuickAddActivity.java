package com.karson.todoapp.ui;

import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.widget.AppCompatButton;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.karson.todoapp.R;
import com.karson.todoapp.data.TaskRepositories;
import com.karson.todoapp.logic.Dates;
import com.karson.todoapp.logic.TaskActions;
import com.karson.todoapp.model.Item;
import com.karson.todoapp.widget.TodayWidgetProvider;

/**
 * Lightweight floating input launched by the quick-add home-screen widget. RemoteViews can't host a
 * keyboard-bound text field, so the widget opens this one-field dialog, which writes a single task
 * through {@code TaskRepository.appendTask} and finishes. The write goes to Firebase when signed in
 * (offline persistence queues it) or to local JSON otherwise — mirroring {@code MainActivity}.
 */
public class QuickAddActivity extends AppCompatActivity {

    private boolean submitted;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_quick_add);

        // A floating window wraps to its content, which collapses to the title's width. Give it a
        // comfortable width (capped) so the input and action row get full room.
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int width = Math.min((int) (dm.widthPixels * 0.92f), (int) (420 * dm.density));
        getWindow().setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);

        EditText input = findViewById(R.id.quick_add_input);
        AppCompatButton submit = findViewById(R.id.quick_add_submit);
        AppCompatButton cancel = findViewById(R.id.quick_add_cancel);

        submit.setOnClickListener(v -> save(input));
        cancel.setOnClickListener(v -> finish());
        input.setOnEditorActionListener((v, actionId, event) -> {
            // Fire once: on the DONE action, or a hardware Enter's key-down (not again on key-up).
            boolean done = actionId == EditorInfo.IME_ACTION_DONE;
            boolean enterDown = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN;
            if (done || enterDown) {
                save(input);
                return true;
            }
            return false;
        });

        // Open with the field focused and the keyboard up.
        input.requestFocus();
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
    }

    private void save(EditText input) {
        if (submitted) return; // guard against a double editor-action / button race
        String text = input.getText().toString().trim();
        if (text.isEmpty()) {
            finish();
            return;
        }
        submitted = true;

        Item item = new Item();
        item.id = TaskActions.createId();
        item.type = "task";
        item.text = text;
        item.completed = false;
        item.noDue = false;
        item.section = "today";
        item.dueDate = Dates.todayIso();
        item.dueTime = "";

        TaskRepositories.create(this).appendTask(item);
        TodayWidgetProvider.notifyDataChanged(this);

        Toast.makeText(this, R.string.task_added, Toast.LENGTH_SHORT).show();
        finish();
    }
}
