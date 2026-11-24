package com.karson.todoapp.alarm;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import com.karson.todoapp.model.Item;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Schedules per-task notifications ({@link Item#notifyTime}): the alarm system without the ringing.
 * Trigger times come from {@link AlarmScheduler#nextTrigger(Item, String, long)}, so a repeating task
 * notifies on every occurrence. Armed with {@code setExactAndAllowWhileIdle} (not {@code setAlarmClock},
 * which would put an alarm icon in the status bar); {@link NotifyReceiver} posts the notification and
 * re-arms the next one. Called from {@link AlarmScheduler#rescheduleAll}, so every place that re-arms
 * alarms (app render, boot, background sync, after an alarm fires) re-arms these too.
 */
public final class NotificationScheduler {
    private static final String TAG = "NotificationScheduler";
    static final String ACTION_NOTIFY = "com.karson.todoapp.alarm.NOTIFY";

    private static final String PREFS = "notification-scheduler";
    private static final String KEY_SCHEDULED = "scheduled-ids";

    private NotificationScheduler() {}

    /** Re-arm notifications for the given tasks; cancel any that no longer qualify or are gone. */
    public static void rescheduleAll(Context context, List<Item> tasks) {
        AlarmManager am = context.getSystemService(AlarmManager.class);
        if (am == null) return;
        long now = System.currentTimeMillis();
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> previous = new HashSet<>(prefs.getStringSet(KEY_SCHEDULED, Collections.emptySet()));
        Set<String> active = new HashSet<>();

        for (Item task : tasks) {
            if (task == null || task.id == null) continue;
            long trigger = shouldNotify(task) ? AlarmScheduler.nextTrigger(task, task.notifyTime, now) : -1;
            if (trigger > now) {
                schedule(context, am, task, trigger);
                active.add(task.id);
            } else {
                cancel(context, am, task.id);
            }
        }
        for (String id : previous) {
            if (!active.contains(id)) cancel(context, am, id);
        }
        prefs.edit().putStringSet(KEY_SCHEDULED, active).apply();
    }

    private static boolean shouldNotify(Item task) {
        return !task.completed && !task.isVirtual
                && task.notifyTime != null && !task.notifyTime.isEmpty();
    }

    private static void schedule(Context context, AlarmManager am, Item task, long triggerMillis) {
        Intent intent = new Intent(context, NotifyReceiver.class)
                .setAction(ACTION_NOTIFY)
                .putExtra(AlarmScheduler.EXTRA_TASK_ID, task.id)
                .putExtra(AlarmScheduler.EXTRA_TASK_TEXT, task.text == null ? "" : task.text);
        PendingIntent op = PendingIntent.getBroadcast(context, reqCode(task.id), intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMillis, op);
        } catch (SecurityException e) {
            // No exact-alarm permission: a slightly late reminder beats none.
            Log.w(TAG, "Exact alarm not permitted; using inexact for " + task.id, e);
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMillis, op);
        }
    }

    private static void cancel(Context context, AlarmManager am, String taskId) {
        Intent intent = new Intent(context, NotifyReceiver.class).setAction(ACTION_NOTIFY);
        PendingIntent existing = PendingIntent.getBroadcast(context, reqCode(taskId), intent,
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        if (existing != null) {
            am.cancel(existing);
            existing.cancel();
        }
    }

    /** Distinct from the alarm's request code so the two never collide. */
    static int reqCode(String taskId) {
        return ("notify:" + taskId).hashCode();
    }
}
