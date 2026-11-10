package com.karson.todoapp.alarm;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.core.content.ContextCompat;

import com.karson.todoapp.R;
import com.karson.todoapp.data.TaskRepositories;

/**
 * Fires when a task alarm goes off and hands straight to {@link AlarmService}, which does the actual
 * ringing. An alarm-triggered receiver is on the allowlist for starting a foreground service from the
 * background, which is why the ringing must run in a service rather than a direct activity launch
 * (that is blocked while the screen is on and unlocked).
 */
public class AlarmReceiver extends BroadcastReceiver {

    public static final String CHANNEL_ID = "task-alarms";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!AlarmScheduler.ACTION_ALARM.equals(intent.getAction())) return;
        ensureChannel(context);
        Intent service = new Intent(context, AlarmService.class)
                .setAction(AlarmService.ACTION_RING)
                .putExtra(AlarmScheduler.EXTRA_TASK_ID, intent.getStringExtra(AlarmScheduler.EXTRA_TASK_ID))
                .putExtra(AlarmScheduler.EXTRA_TASK_TEXT, intent.getStringExtra(AlarmScheduler.EXTRA_TASK_TEXT))
                .putExtra(AlarmScheduler.EXTRA_ALARM_TIME, intent.getStringExtra(AlarmScheduler.EXTRA_ALARM_TIME));
        ContextCompat.startForegroundService(context, service);

        // Re-arm the task's NEXT occurrence now that this one fired, so a repeating task keeps
        // ringing day after day even if the app is never opened. Reads the current tasks once
        // (Firebase cache / local JSON) and reschedules the whole set.
        final Context app = context.getApplicationContext();
        final PendingResult pending = goAsync();
        TaskRepositories.create(app).loadOnce((tasks, projects) -> {
            AlarmScheduler.rescheduleAll(app, tasks);
            pending.finish();
        });
    }

    static int notifyId(String taskId) {
        return taskId == null ? 424242 : taskId.hashCode();
    }

    static void ensureChannel(Context context) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) return;
        // Silent, high-importance channel: the AlarmService owns the looping alarm sound and
        // vibration, so the channel itself stays quiet to avoid a double ding.
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                context.getString(R.string.alarm_channel_name), NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription(context.getString(R.string.alarm_channel_desc));
        channel.setSound(null, null);
        channel.enableVibration(false);
        channel.setBypassDnd(true);
        nm.createNotificationChannel(channel);
    }
}
