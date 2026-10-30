package com.karson.todoapp.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import com.karson.todoapp.R;
import com.karson.todoapp.ui.QuickAddActivity;

/**
 * Home-screen widget whose whole card launches {@link QuickAddActivity} to add one task fast.
 * Stateless — it draws the same card on every update; the tap target does all the work.
 */
public class QuickAddWidgetProvider extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
        for (int appWidgetId : appWidgetIds) {
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_quick_add);

            Intent intent = new Intent(context, QuickAddActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pending = PendingIntent.getActivity(
                    context, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            views.setOnClickPendingIntent(R.id.widget_root, pending);

            manager.updateAppWidget(appWidgetId, views);
        }
    }
}
