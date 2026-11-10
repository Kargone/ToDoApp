package com.karson.todoapp.alarm;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import com.karson.todoapp.MainActivity;
import com.karson.todoapp.logic.RepeatRules;
import com.karson.todoapp.model.Item;
import com.karson.todoapp.model.RepeatRule;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

    private static final String PREFS = "alarm-scheduler";
    private static final String KEY_SCHEDULED = "scheduled-ids";

    /**
     * Re-arm alarms for the given tasks; cancel any that no longer qualify. Ids armed on a previous
     * run but absent (or no longer qualifying) now are cancelled too — this is how a deleted task's
     * alarm gets torn down, since a deleted task is simply gone from {@code tasks} (whether deleted
     * on this phone or on another device and synced in).
     */
    public static void rescheduleAll(Context context, List<Item> tasks) {
        AlarmManager am = context.getSystemService(AlarmManager.class);
        if (am == null) return;
        long now = System.currentTimeMillis();
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> previous = new HashSet<>(prefs.getStringSet(KEY_SCHEDULED, Collections.emptySet()));
        Set<String> active = new HashSet<>();

        for (Item task : tasks) {
            if (task == null || task.id == null) continue;
            long trigger = nextTrigger(task, now);
            if (shouldRing(task) && trigger > now) {
                schedule(context, am, task, trigger);
                active.add(task.id);
            } else {
                cancel(context, am, task.id);
            }
        }

        // Tear down alarms for tasks that were armed before but are no longer present/qualifying.
        for (String id : previous) {
            if (!active.contains(id)) cancel(context, am, id);
        }

        prefs.edit().putStringSet(KEY_SCHEDULED, active).apply();
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
                && task.alarmTime != null && !task.alarmTime.isEmpty();
    }

    private static final int MAX_SCAN_DAYS = 366 * 5;

    /**
     * The next epoch-millis this task should ring, strictly after {@code now}; -1 if none.
     * For a repeating task this walks the repeat rule forward from today so EVERY upcoming
     * occurrence rings in turn (one alarm is armed at a time; {@link AlarmReceiver} re-arms the
     * next after each fires). For a one-off task it is simply its date at the alarm time.
     */
    public static long nextTrigger(Item task, long now) {
        if (task.alarmTime == null || task.alarmTime.isEmpty()) return -1;
        final LocalTime time;
        try {
            time = LocalTime.parse(task.alarmTime);
        } catch (Exception e) {
            return -1;
        }
        ZoneId zone = ZoneId.systemDefault();

        if (task.repeatRule == null) {
            long single = triggerMillis(task);
            return single > now ? single : -1;
        }

        String anchorIso = task.itemDate();
        if (anchorIso == null || anchorIso.isEmpty()) return -1;
        final LocalDate anchor;
        try {
            anchor = LocalDate.parse(anchorIso);
        } catch (Exception e) {
            return -1;
        }
        RepeatRule rule = task.repeatRule;
        LocalDate today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        LocalDate candidate = anchor.isAfter(today) ? anchor : today;
        for (int i = 0; i < MAX_SCAN_DAYS; i++, candidate = candidate.plusDays(1)) {
            String iso = candidate.toString();
            if (rule.stopDate != null && !rule.stopDate.isEmpty() && iso.compareTo(rule.stopDate) > 0) return -1;
            if (iso.equals(anchorIso) || RepeatRules.matches(task, iso, rule, anchorIso)) {
                long millis = LocalDateTime.of(candidate, time).atZone(zone).toInstant().toEpochMilli();
                if (millis > now) return millis;
            }
        }
        return -1;
    }

    /** Epoch millis of the task's own date at its alarm time, or -1 if it has no valid alarm. */
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
