package com.karson.todoapp.alarm;

import android.app.KeyguardManager;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.karson.todoapp.MainActivity;
import com.karson.todoapp.R;

/**
 * Full-screen ringing alarm shown over the lockscreen (launched by the service's full-screen intent).
 * The looping sound lives in {@link AlarmService}; this is just the UI, so Dismiss and Snooze delegate
 * to the service, which also carries the ring when the screen is on and this screen can't take over.
 */
public class AlarmActivity extends AppCompatActivity {

    private String taskId;
    private String taskText;
    private String alarmTime;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        MainActivity.applyStoredTheme(this);
        super.onCreate(savedInstanceState);
        showOverLockscreen();
        setContentView(R.layout.activity_alarm);

        bind(getIntent());
        findViewById(R.id.alarm_dismiss).setOnClickListener(v -> { sendToService(AlarmService.ACTION_STOP); finish(); });
        findViewById(R.id.alarm_snooze).setOnClickListener(v -> { sendToService(AlarmService.ACTION_SNOOZE); finish(); });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        bind(intent);
    }

    private void bind(Intent intent) {
        taskId = intent.getStringExtra(AlarmScheduler.EXTRA_TASK_ID);
        taskText = intent.getStringExtra(AlarmScheduler.EXTRA_TASK_TEXT);
        alarmTime = intent.getStringExtra(AlarmScheduler.EXTRA_ALARM_TIME);
        TextView title = findViewById(R.id.alarm_task);
        title.setText(taskText == null || taskText.isEmpty()
                ? getString(R.string.alarm_default_title) : taskText);
        ((TextView) findViewById(R.id.alarm_time)).setText(alarmTime == null ? "" : alarmTime);
    }

    private void sendToService(String action) {
        Intent intent = new Intent(this, AlarmService.class).setAction(action)
                .putExtra(AlarmScheduler.EXTRA_TASK_ID, taskId)
                .putExtra(AlarmScheduler.EXTRA_TASK_TEXT, taskText)
                .putExtra(AlarmScheduler.EXTRA_ALARM_TIME, alarmTime);
        startService(intent);
    }

    private void showOverLockscreen() {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
            KeyguardManager km = getSystemService(KeyguardManager.class);
            if (km != null) km.requestDismissKeyguard(this, null);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    public void onBackPressed() {
        // Don't silently kill the alarm; snooze instead.
        sendToService(AlarmService.ACTION_SNOOZE);
        finish();
    }
}
