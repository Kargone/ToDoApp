package com.karson.todoapp.alarm;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.karson.todoapp.MainActivity;
import com.karson.todoapp.model.Item;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

/**
 * Schedules per-task ringing alarms with {@link AlarmManager#setAlarmClock}, which is exact, wakes
 * the device even in Doze, and needs no exact-alarm permission. Each task with an {@code alarmTime}
 * gets one alarm keyed by its id; {@link #rescheduleAll} re-arms the whole set from the current task
 * list (called whenever data changes and after a reboot), cancelling alarms for tasks that no longer
 * qualify (completed, alarm removed, or the time has passed).
 */
public final class AlarmScheduler {
    private static final String TAG = "AlarmScheduler";
    public static final String ACTION_ALARM = "com.karson.todoapp.alarm.FIRE";
    public static final String EXTRA_TASK_ID = "taskId";
    public static final String EXTRA_TASK_TEXT = "taskText";
    public static final String EXTRA_ALARM_TIME = "alarmTime";

    private AlarmScheduler() {}

    /** Re-arm alarms for the given tasks; cancel any that no longer qualify. */
    public static void rescheduleAll(Context context, List<Item> tasks) {
        AlarmManager am = context.getSystemService(AlarmManager.class);
        if (am == null) return;
        long now = System.currentTimeMillis();
        for (Item task : tasks) {
            if (task == null || task.id == null) continue;
            long trigger = triggerMillis(task);
            if (shouldRing(task) && trigger > now) {
                schedule(context, am, task, trigger);
            } else {
                cancel(context, am, task.id);
            }
        }
    }

    /** Schedule (or snooze) a single task alarm for an absolute time. */
    public static void scheduleAt(Context context, Item task, long triggerMillis) {
        AlarmManager am = context.getSystemService(AlarmManager.class);
        if (am == null) return;
        schedule(context, am, task, triggerMillis);
    }

    public static void cancel(Context context, String taskId) {
        AlarmManager am = context.getSystemService(AlarmManager.class);
        if (am != null) cancel(context, am, taskId);
    }

    private static void schedule(Context context, AlarmManager am, Item task, long triggerMillis) {
        PendingIntent op = operation(context, task, PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent show = PendingIntent.getActivity(context, 0,
                new Intent(context, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try {
            am.setAlarmClock(new AlarmManager.AlarmClockInfo(triggerMillis, show), op);
            Log.d(TAG, "Scheduled alarm for \"" + task.text + "\" at " + triggerMillis);
        } catch (SecurityException e) {
            // Exact-alarm permission unexpectedly missing — never crash the render that scheduled it.
            Log.w(TAG, "Exact alarm not permitted; skipping schedule for " + task.id, e);
        }
    }

    private static void cancel(Context context, AlarmManager am, String taskId) {
        PendingIntent existing = operationForId(context, taskId,
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        if (existing != null) {
            am.cancel(existing);
            existing.cancel();
        }
    }

    private static PendingIntent operation(Context context, Item task, int extraFlags) {
        Intent intent = new Intent(context, AlarmReceiver.class)
                .setAction(ACTION_ALARM)
                .putExtra(EXTRA_TASK_ID, task.id)
                .putExtra(EXTRA_TASK_TEXT, task.text == null ? "" : task.text)
                .putExtra(EXTRA_ALARM_TIME, task.alarmTime == null ? "" : task.alarmTime);
        return PendingIntent.getBroadcast(context, reqCode(task.id), intent,
                extraFlags | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent operationForId(Context context, String taskId, int flags) {
        Intent intent = new Intent(context, AlarmReceiver.class).setAction(ACTION_ALARM);
        return PendingIntent.getBroadcast(context, reqCode(taskId), intent, flags);
    }

    /** Stable request code per task id (a filled PendingIntent matches a NO_CREATE lookup by this). */
    private static int reqCode(String taskId) {
        return taskId.hashCode();
    }

    private static boolean shouldRing(Item task) {
        return !task.completed && !task.isVirtual
                && task.alarmTime != null && !task.alarmTime.isEmpty()
                && task.itemDate() != null && !task.itemDate().isEmpty();
    }

    /** Epoch millis of the task's date at its alarm time, or -1 if it has no valid alarm. */
    public static long triggerMillis(Item task) {
        if (task.alarmTime == null || task.alarmTime.isEmpty()) return -1;
        String date = task.itemDate();
        if (date == null || date.isEmpty()) return -1;
        try {
            LocalDateTime when = LocalDateTime.of(LocalDate.parse(date), LocalTime.parse(task.alarmTime));
            return when.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (Exception e) {
            Log.w(TAG, "Bad alarm date/time for task " + task.id, e);
            return -1;
        }
    }
}
