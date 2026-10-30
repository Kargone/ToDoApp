package com.karson.todoapp.widget;

import android.content.Context;
import android.content.Intent;
import android.graphics.Paint;
import android.text.SpannableString;
import android.text.style.StrikethroughSpan;
import android.util.Log;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import androidx.core.content.ContextCompat;

import com.karson.todoapp.R;
import com.karson.todoapp.data.TaskRepositories;
import com.karson.todoapp.data.TaskRepository;
import com.karson.todoapp.logic.Categories;
import com.karson.todoapp.logic.Dates;
import com.karson.todoapp.logic.ProjectLogic;
import com.karson.todoapp.logic.ViewLogic;
import com.karson.todoapp.model.Item;
import com.karson.todoapp.model.Project;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Supplies the rows of the Today list widget, computed with the same logic as the app's Today tab. */
public class TodayWidgetService extends RemoteViewsService {
    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new TodayFactory(getApplicationContext());
    }

    private static class TodayFactory implements RemoteViewsFactory {
        private static final String TAG = "TodayWidget";
        private final Context ctx;
        private final List<Item> items = new ArrayList<>();
        private LocalDateTime now = LocalDateTime.now();

        TodayFactory(Context ctx) {
            this.ctx = ctx;
        }

        @Override
        public void onCreate() {}

        /** Runs on a binder thread (may block): load once, then compute and sort the Today list. */
        @Override
        public void onDataSetChanged() {
            now = LocalDateTime.now();
            final List<Item> tasks = new ArrayList<>();
            final List<Project> projects = new ArrayList<>();
            final CountDownLatch latch = new CountDownLatch(1);

            TaskRepository repo = TaskRepositories.create(ctx);
            repo.loadOnce((loadedTasks, loadedProjects) -> {
                tasks.addAll(loadedTasks);
                projects.addAll(loadedProjects);
                latch.countDown();
            });
            try {
                latch.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Log.w(TAG, "load interrupted", e);
            }

            Set<String> projectIds = ProjectLogic.idSet(projects);
            List<Item> today = Categories.getItemsForTab(tasks, Categories.TODAY, "", now, projectIds);
            sortTodayOrder(today);

            items.clear();
            items.addAll(today);
        }

        /** Ascending by datetime, with completed tasks sunk to the bottom (mirrors MainActivity). */
        private void sortTodayOrder(List<Item> list) {
            list.sort((a, b) -> sortKey(a).compareTo(sortKey(b)));
            list.sort((a, b) -> Boolean.compare(a.completed, b.completed));
        }

        private String sortKey(Item item) {
            String date = item.itemDate();
            if (date == null || date.isEmpty()) date = Dates.todayIso();
            String time = item.itemTime();
            if (time == null || time.isEmpty()) time = "00:00";
            return date + "T" + time;
        }

        @Override
        public void onDestroy() {
            items.clear();
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public RemoteViews getViewAt(int position) {
            if (position < 0 || position >= items.size()) return null;
            Item item = items.get(position);
            RemoteViews row = new RemoteViews(ctx.getPackageName(), R.layout.widget_today_row);

            // Title: strike through and dim when completed.
            CharSequence title = item.text == null ? "" : item.text;
            if (item.completed) {
                SpannableString s = new SpannableString(title);
                s.setSpan(new StrikethroughSpan(), 0, s.length(), SpannableString.SPAN_INCLUSIVE_INCLUSIVE);
                title = s;
                row.setTextColor(R.id.widget_row_title, color(R.color.muted));
            } else {
                row.setTextColor(R.id.widget_row_title, color(R.color.fg));
            }
            row.setTextViewText(R.id.widget_row_title, title);

            // Check indicator.
            row.setImageViewResource(R.id.widget_row_check,
                    item.completed ? R.drawable.widget_check_done : R.drawable.chk_unchecked);

            // Time column, red when overdue.
            row.setTextViewText(R.id.widget_row_time, ViewLogic.formatTimeCell(item, now));
            row.setTextColor(R.id.widget_row_time,
                    ViewLogic.isOverdue(item, now) ? color(R.color.warn) : color(R.color.muted));

            // Whole row toggles this task.
            Intent fillIn = new Intent();
            fillIn.putExtra(TodayWidgetProvider.EXTRA_TASK_ID, item.id);
            row.setOnClickFillInIntent(R.id.widget_row, fillIn);
            return row;
        }

        private int color(int res) {
            return ContextCompat.getColor(ctx, res);
        }

        @Override
        public RemoteViews getLoadingView() {
            return null;
        }

        @Override
        public int getViewTypeCount() {
            return 1;
        }

        @Override
        public long getItemId(int position) {
            return position < items.size() ? items.get(position).id.hashCode() : position;
        }

        @Override
        public boolean hasStableIds() {
            return true;
        }
    }
}
