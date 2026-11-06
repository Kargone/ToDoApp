package com.karson.todoapp.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.RemoteViews;

import com.karson.todoapp.MainActivity;
import com.karson.todoapp.R;
import com.karson.todoapp.data.TaskRepositories;
import com.karson.todoapp.data.TaskRepository;
import com.karson.todoapp.logic.Dates;
import com.karson.todoapp.logic.TaskActions;
import com.karson.todoapp.ui.QuickAddActivity;

/**
 * Today list widget host. Draws the card, wires the quick-add button and the per-row toggle, and
 * refreshes the list. Tapping a row broadcasts {@link #ACTION_TOGGLE}; completing goes through the
 * shared {@link TaskActions#toggle} so it behaves exactly like the app, then the list is refreshed.
 */
public class TodayWidgetProvider extends AppWidgetProvider {

    public static final String EXTRA_TASK_ID = "com.karson.todoapp.widget.TASK_ID";
    private static final String ACTION_TOGGLE = "com.karson.todoapp.widget.ACTION_TOGGLE";

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
        for (int id : appWidgetIds) {
            manager.updateAppWidget(id, buildViews(context, id));
        }
        manager.notifyAppWidgetViewDataChanged(appWidgetIds, R.id.widget_list);
    }

    private static RemoteViews buildViews(Context context, int appWidgetId) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_today);

        // List adapter backed by the service.
        Intent serviceIntent = new Intent(context, TodayWidgetService.class);
        serviceIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
        serviceIntent.setData(Uri.parse(serviceIntent.toUri(Intent.URI_INTENT_SCHEME)));
        views.setRemoteAdapter(R.id.widget_list, serviceIntent);
        views.setEmptyView(R.id.widget_list, R.id.widget_empty);

        // Tapping the header opens the app (like the Calendar widget's date header).
        Intent openApp = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        views.setOnClickPendingIntent(R.id.widget_title, PendingIntent.getActivity(
                context, 1, openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));

        // Quick-add button opens the input dialog.
        Intent addIntent = new Intent(context, QuickAddActivity.class);
        addIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        views.setOnClickPendingIntent(R.id.widget_add, PendingIntent.getActivity(
                context, 0, addIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));

        // Row-tap template: each row fills in its task id, then this broadcasts a toggle.
        Intent toggleIntent = new Intent(context, TodayWidgetProvider.class);
        toggleIntent.setAction(ACTION_TOGGLE);
        toggleIntent.setData(Uri.parse("todoapp://today/" + appWidgetId));
        views.setPendingIntentTemplate(R.id.widget_list, PendingIntent.getBroadcast(
                context, appWidgetId, toggleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE));

        return views;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent); // handles APPWIDGET_UPDATE -> onUpdate
        String action = intent.getAction();
        if (ACTION_TOGGLE.equals(action)) {
            String taskId = intent.getStringExtra(EXTRA_TASK_ID);
            if (taskId != null) toggleTask(context, taskId);
        } else if (Intent.ACTION_DATE_CHANGED.equals(action)
                || Intent.ACTION_TIME_CHANGED.equals(action)
                || Intent.ACTION_TIMEZONE_CHANGED.equals(action)) {
            // The day rolled over (or the clock changed) — "Today" is now a different set.
            refreshAll(context);
        }
    }

    /** Load once, apply the shared toggle, persist the diff, then refresh every Today widget. */
    private void toggleTask(Context context, String taskId) {
        final Context app = context.getApplicationContext();
        final PendingResult result = goAsync();
        final TaskRepository repo = TaskRepositories.create(app);
        repo.loadOnce((tasks, projects) -> {
            boolean changed = TaskActions.toggle(tasks, taskId, Dates.todayIso());
            if (changed) repo.saveTasks(tasks);
            notifyDataChanged(app);
            result.finish();
        });
    }

    /** Reload the list data of every placed Today widget (after a toggle, and by the app on change). */
    public static void notifyDataChanged(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, TodayWidgetProvider.class));
        if (ids.length > 0) manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_list);
    }

    /** Rebuild the views and reload the data of every placed Today widget (periodic / day rollover). */
    static void refreshAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, TodayWidgetProvider.class));
        for (int id : ids) manager.updateAppWidget(id, buildViews(context, id));
        if (ids.length > 0) manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_list);
    }
}
