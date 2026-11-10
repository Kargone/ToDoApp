package com.karson.todoapp.alarm;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.karson.todoapp.data.FirebaseTaskRepository;
import com.karson.todoapp.data.TaskRepositories;
import com.karson.todoapp.data.TaskRepository;
import com.karson.todoapp.model.Item;
import com.karson.todoapp.model.Project;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Periodic background sync: reads the current tasks once and re-arms every alarm. This is what lets an
 * alarm added on another device (the web app) get scheduled on this phone even while the app is never
 * opened — AlarmManager alarms are otherwise only (re)armed when the app runs or at boot.
 *
 * <p>Caveats: WorkManager's minimum period is 15 minutes and the OS may defer runs in Doze, so a
 * newly-added alarm can take up to ~15–30 min to arm; and a force-stopped app won't run workers until
 * it's next launched. (Instant scheduling would need a server push / FCM on the database write.)
 */
public class AlarmSyncWorker extends Worker {

    private static final String UNIQUE_NAME = "alarm-sync";

    public AlarmSyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    /** Enqueue the repeating sync (idempotent — keeps any already-scheduled copy). */
    public static void schedule(Context context) {
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                AlarmSyncWorker.class, 15, TimeUnit.MINUTES)
                .setConstraints(new Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build())
                .build();
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request);
    }

    @NonNull
    @Override
    public Result doWork() {
        final Context app = getApplicationContext();
        final TaskRepository repo = TaskRepositories.create(app);

        // Local (signed-out) data can't change from another device, and its one-shot read is
        // synchronous — just reschedule from it.
        if (!(repo instanceof FirebaseTaskRepository)) {
            repo.loadOnce((tasks, projects) -> AlarmScheduler.rescheduleAll(app, tasks));
            return Result.success();
        }

        // Signed in: take a server-fresh snapshot via the live listener (a single-value read with
        // persistence enabled can return stale cache and miss an alarm just added on the web), then
        // detach. The first callback is enough.
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean handled = new AtomicBoolean(false);
        repo.setListener(new TaskRepository.Listener() {
            @Override
            public void onTasksChanged(List<Item> tasks) {
                if (!handled.compareAndSet(false, true)) return;
                AlarmScheduler.rescheduleAll(app, tasks);
                latch.countDown();
            }

            @Override
            public void onProjectsChanged(List<Project> projects) {}
        });
        repo.start();
        try {
            if (!latch.await(45, TimeUnit.SECONDS)) return Result.retry();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.retry();
        } finally {
            repo.stop();
        }
        return Result.success();
    }
}
