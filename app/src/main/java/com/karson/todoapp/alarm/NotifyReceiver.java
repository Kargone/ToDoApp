package com.karson.todoapp.alarm;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.karson.todoapp.MainActivity;
import com.karson.todoapp.R;
import com.karson.todoapp.data.TaskRepositories;

/**
 * Posts a task's notification when its {@code notifyTime} arrives (a normal notification using the
 * channel's default sound; no ringing service, no full-screen takeover), then re-arms the next
 * occurrence so repeating tasks keep notifying without the app being opened.
 */
public class NotifyReceiver extends BroadcastReceiver {

    public static final String CHANNEL_ID = "task-notifications";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!NotificationScheduler.ACTION_NOTIFY.equals(intent.getAction())) return;
        String taskId = intent.getStringExtra(AlarmScheduler.EXTRA_TASK_ID);
        String text = intent.getStringExtra(AlarmScheduler.EXTRA_TASK_TEXT);

        ensureChannel(context);
        PendingIntent open = PendingIntent.getActivity(context, NotificationScheduler.reqCode(taskId),
                new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_mark_check)
                .setContentTitle(text == null || text.isEmpty()
                        ? context.getString(R.string.alarm_default_title) : text)
                .setContentText(context.getString(R.string.notify_notification_text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .setContentIntent(open);
        try {
            NotificationManagerCompat.from(context)
                    .notify(NotificationScheduler.reqCode(taskId), builder.build());
        } catch (SecurityException ignored) {
            // POST_NOTIFICATIONS denied: nothing to show, but still re-arm below.
        }

        final Context app = context.getApplicationContext();
        final PendingResult pending = goAsync();
        TaskRepositories.create(app).loadOnce((tasks, projects) -> {
            AlarmScheduler.rescheduleAll(app, tasks); // also re-arms notifications
            pending.finish();
        });
    }

    static void ensureChannel(Context context) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                context.getString(R.string.notify_channel_name), NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription(context.getString(R.string.notify_channel_desc));
        nm.createNotificationChannel(channel);
    }
}
