package com.karson.todoapp.alarm;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.karson.todoapp.R;
import com.karson.todoapp.model.Item;

/**
 * Foreground service that actually rings: it loops the alarm sound and vibrates until stopped, which
 * keeps the alarm audible whether or not the full-screen {@link AlarmActivity} can take over the
 * screen (Android only auto-launches a full-screen intent when the device is locked or the screen is
 * off). An alarm-triggered broadcast receiver is exempt from the background FGS-start restriction, so
 * {@link AlarmReceiver} can start this. Dismiss and Snooze come in as actions from the notification
 * or the activity.
 */
public class AlarmService extends Service {
    static final String ACTION_RING = "com.karson.todoapp.alarm.RING";
    static final String ACTION_STOP = "com.karson.todoapp.alarm.STOP";
    static final String ACTION_SNOOZE = "com.karson.todoapp.alarm.SNOOZE";
    private static final int SNOOZE_MINUTES = 5;

    private MediaPlayer player;
    private Vibrator vibrator;
    private String taskId;
    private String taskText;
    private String alarmTime;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopEverything();
            return START_NOT_STICKY;
        }
        if (ACTION_SNOOZE.equals(action)) {
            bind(intent);
            scheduleSnooze();
            stopEverything();
            return START_NOT_STICKY;
        }

        bind(intent);
        AlarmReceiver.ensureChannel(this);
        startForeground(AlarmReceiver.notifyId(taskId), buildNotification());
        startRinging();
        return START_NOT_STICKY;
    }

    private void bind(Intent intent) {
        if (intent == null) return;
        if (intent.hasExtra(AlarmScheduler.EXTRA_TASK_ID)) taskId = intent.getStringExtra(AlarmScheduler.EXTRA_TASK_ID);
        if (intent.hasExtra(AlarmScheduler.EXTRA_TASK_TEXT)) taskText = intent.getStringExtra(AlarmScheduler.EXTRA_TASK_TEXT);
        if (intent.hasExtra(AlarmScheduler.EXTRA_ALARM_TIME)) alarmTime = intent.getStringExtra(AlarmScheduler.EXTRA_ALARM_TIME);
    }

    private Notification buildNotification() {
        String text = taskText == null || taskText.isEmpty()
                ? getString(R.string.alarm_default_title) : taskText;

        Intent full = new Intent(this, AlarmActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(AlarmScheduler.EXTRA_TASK_ID, taskId)
                .putExtra(AlarmScheduler.EXTRA_TASK_TEXT, taskText)
                .putExtra(AlarmScheduler.EXTRA_ALARM_TIME, alarmTime);
        PendingIntent fullPi = PendingIntent.getActivity(this, reqCode(), full,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        PendingIntent stopPi = PendingIntent.getService(this, reqCode() + 1,
                action(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent snoozePi = PendingIntent.getService(this, reqCode() + 2,
                action(ACTION_SNOOZE), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, AlarmReceiver.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_mark_check)
                .setContentTitle(getString(R.string.alarm_notification_title))
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setOngoing(true)
                .setAutoCancel(false)
                .setFullScreenIntent(fullPi, true)
                .setContentIntent(fullPi)
                .addAction(0, getString(R.string.alarm_snooze), snoozePi)
                .addAction(0, getString(R.string.alarm_dismiss), stopPi)
                .build();
    }

    private Intent action(String a) {
        return new Intent(this, AlarmService.class).setAction(a)
                .putExtra(AlarmScheduler.EXTRA_TASK_ID, taskId)
                .putExtra(AlarmScheduler.EXTRA_TASK_TEXT, taskText)
                .putExtra(AlarmScheduler.EXTRA_ALARM_TIME, alarmTime);
    }

    private int reqCode() {
        return taskId == null ? 7000 : taskId.hashCode();
    }

    private void startRinging() {
        if (player != null) return; // already ringing
        Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
        try {
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            player.setDataSource(this, uri);
            player.setLooping(true);
            player.prepare();
            player.start();
        } catch (Exception ignored) {
            player = null;
        }

        vibrator = vibrator();
        if (vibrator != null && vibrator.hasVibrator()) {
            vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0, 600, 600}, 0));
        }
    }

    private void stopEverything() {
        if (player != null) {
            try { if (player.isPlaying()) player.stop(); } catch (Exception ignored) {}
            player.release();
            player = null;
        }
        if (vibrator != null) {
            vibrator.cancel();
            vibrator = null;
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void scheduleSnooze() {
        Item task = new Item();
        task.id = taskId;
        task.text = taskText;
        task.alarmTime = alarmTime;
        AlarmScheduler.scheduleAt(this, task, System.currentTimeMillis() + SNOOZE_MINUTES * 60_000L);
    }

    private Vibrator vibrator() {
        if (Build.VERSION.SDK_INT >= 31) {
            VibratorManager vm = getSystemService(VibratorManager.class);
            return vm == null ? null : vm.getDefaultVibrator();
        }
        return (Vibrator) getSystemService(VIBRATOR_SERVICE);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopEverything();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
