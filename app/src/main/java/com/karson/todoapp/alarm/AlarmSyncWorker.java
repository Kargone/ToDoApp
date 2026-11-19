package com.karson.todoapp.alarm;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.karson.todoapp.data.TaskRepositories;
import com.karson.todoapp.data.TaskRepository;
import com.karson.todoapp.widget.TodayWidgetProvider;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Background sync: reads the current tasks once, re-arms every alarm, and refreshes the Today
 * widget. This is what lets an alarm (or task) added on another device (the web app) reach this
 * phone while the app is never opened — alarms are otherwise only (re)armed when the app runs or at
 * boot, and the widget only redraws when something tells it to.
 *
 * <p>Runs every ~15 minutes, plus once on demand (see {@link #runNow}) right after midnight so the
 * morning widget shows the server's copy rather than last night's cache.
 *
 * <p>Caveats: WorkManager's minimum period is 15 minutes and the OS may defer runs in Doze, so a
 * newly-added alarm can take up to ~15–30 min to arm; and a force-stopped app won't run workers until
 * it's next launched. (Instant scheduling would need a server push / FCM on the database write.)
 */
public class AlarmSyncWorker extends Worker {

    private static final String UNIQUE_NAME = "alarm-sync";
    private static final String UNIQUE_NAME_NOW = "alarm-sync-now";

    public AlarmSyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    private static Constraints networkConstraint() {
        return new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
    }

    /** Enqueue the repeating sync (idempotent — keeps any already-scheduled copy). */
    public static void schedule(Context context) {
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                AlarmSyncWorker.class, 15, TimeUnit.MINUTES)
                .setConstraints(networkConstraint())
                .build();
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request);
    }

    /** Run one sync as soon as the network allows (e.g. at the day rollover). */
    public static void runNow(Context context) {
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(AlarmSyncWorker.class)
                .setConstraints(networkConstraint())
                .build();
        WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NAME_NOW, ExistingWorkPolicy.REPLACE, request);
    }

    @NonNull
    @Override
    public Result doWork() {
        final Context app = getApplicationContext();
        final TaskRepository repo = TaskRepositories.create(app);

        // loadFresh asks the server when signed in (the cache can be stale — that's the whole point
        // of this worker); signed out it's just the local file.
        final CountDownLatch latch = new CountDownLatch(1);
        repo.loadFresh((tasks, projects) -> {
            AlarmScheduler.rescheduleAll(app, tasks);
            latch.countDown();
        });
        try {
            if (!latch.await(45, TimeUnit.SECONDS)) return Result.retry();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.retry();
        }
        // The Today widget reloads from the (now updated) cache.
        TodayWidgetProvider.refreshAll(app);
        return Result.success();
    }
}
