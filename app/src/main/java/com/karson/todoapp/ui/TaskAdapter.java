package com.karson.todoapp.ui;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Interpolator;
import android.view.animation.OvershootInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.karson.todoapp.R;
import com.karson.todoapp.logic.Categories;
import com.karson.todoapp.logic.ViewLogic;
import com.karson.todoapp.model.Item;
import com.karson.todoapp.model.Project;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class TaskAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    // Chevron turn on expand/collapse: rotate past the mark and settle back for a springy feel.
    private static final Interpolator CHEVRON_INTERPOLATOR = new OvershootInterpolator(2.6f);
    private static final long CHEVRON_DURATION_MS = 200L;

    public interface Listener {
        void onToggle(Item item);
        void onMenu(Item item, View anchor);
        void onDismiss(Item item);
        void onRenameTask(String id, String newText);
        void onProjectToggle(com.karson.todoapp.model.Project project);
        void onProjectMenu(com.karson.todoapp.model.Project project, View anchor);
    }

    // Manual double-tap detection for inline rename (Views have no built-in double-click).
    private static final long DOUBLE_TAP_MS = 300L;
    private String lastTapId;
    private long lastTapTime;

    /** The task currently being renamed inline (its row shows an EditText), or null. */
    private String editingTaskId;

    private final List<Row> rows = new ArrayList<>();
    /** Per-row display signature, parallel to {@link #rows}, captured at submit time so an in-place
     *  edit of the underlying Item is still seen as a change by the next diff. */
    private final List<String> rowSigs = new ArrayList<>();
    private final Listener listener;
    private LocalDateTime now = LocalDateTime.now();
    private String activeTab = Categories.TODAY;

    public TaskAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submit(List<Row> next, String activeTab, LocalDateTime now) {
        this.activeTab = activeTab;
        this.now = now;
        // Capture each new row's signature NOW. Rows are rebuilt every render but hold the live Item
        // by reference, so diffing live objects would miss an in-place edit (the "previous" row would
        // already reflect the new value). Snapshotted strings are immutable, so a rename shows up.
        List<String> nextSigs = new ArrayList<>(next.size());
        for (Row r : next) nextSigs.add(signature(r));
        List<Row> prevRows = new ArrayList<>(rows);
        List<String> prevSigs = new ArrayList<>(rowSigs);

        // detectMoves = false on purpose: a row that both moves and changes (e.g. a task sorting to
        // the bottom the instant it's completed) would otherwise animate to its new spot WITHOUT
        // rebinding, showing stale contents. Treating it as remove+insert forces a fresh bind, and
        // open/close only ever needs insert/remove anyway, never moves.
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(
                new RowDiff(prevRows, next, prevSigs, nextSigs), false);
        rows.clear();
        rows.addAll(next);
        rowSigs.clear();
        rowSigs.addAll(nextSigs);
        diff.dispatchUpdatesTo(this);
    }

    /** Everything a bind reads, flattened to a string. Includes the active tab (a task renders
     *  differently per tab) and whether the row is mid-rename (so entering/leaving edit rebinds). */
    private String signature(Row r) {
        if (r.type == Row.TYPE_PROJECT_HEAD) {
            return "P|" + r.depth + "|" + r.expanded + "|" + r.openCount + "|" + r.project.name;
        }
        Item i = r.item;
        boolean editing = i.id != null && i.id.equals(editingTaskId);
        return "T|" + activeTab + "|" + r.depth + "|" + editing + "|" + i.completed + "|" + i.dismissed
                + "|" + i.isVirtual + "|" + i.type + "|" + i.text + "|" + i.dueDate + "|" + i.dueTime
                + "|" + i.startDate + "|" + i.startTime + "|" + i.endTime
                + "|" + i.projectId + "|" + i.minShowTime + "|" + (i.repeatRule != null);
    }

    @Override
    public int getItemViewType(int position) {
        return rows.get(position).type;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == Row.TYPE_PROJECT_HEAD) {
            return new ProjectHeadHolder(inflater.inflate(R.layout.item_project_head, parent, false));
        }
        return new RowHolder(inflater.inflate(R.layout.item_row, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Row row = rows.get(position);
        if (holder instanceof ProjectHeadHolder) {
            bindProjectHead((ProjectHeadHolder) holder, row);
        } else {
            bindTask((RowHolder) holder, row);
        }
    }

    // Row actions resolve their model object from the live rows list at click time, by the holder's
    // current position — never a reference captured during bind. A Firebase echo can swap in a fresh,
    // content-identical Item instance without rebinding the row; a captured reference would then be
    // orphaned, and mutating it (e.g. toggling completed) would never reach the list that gets saved.
    private Item taskAt(RecyclerView.ViewHolder h) {
        int pos = h.getBindingAdapterPosition();
        if (pos == RecyclerView.NO_POSITION || pos >= rows.size()) return null;
        Row r = rows.get(pos);
        return r.type == Row.TYPE_TASK ? r.item : null;
    }

    private Project projectAt(RecyclerView.ViewHolder h) {
        int pos = h.getBindingAdapterPosition();
        if (pos == RecyclerView.NO_POSITION || pos >= rows.size()) return null;
        Row r = rows.get(pos);
        return r.type == Row.TYPE_PROJECT_HEAD ? r.project : null;
    }

    /** Renameable where the web allows it: not a virtual occurrence, not completed, not in the
     *  Repeating or Completed tabs (mirrors canRenameTask's editableTabs). */
    private boolean canRename(Item item) {
        return !item.isVirtual && !item.completed
                && !Categories.REPEATING.equals(activeTab)
                && !Categories.COMPLETED.equals(activeTab);
    }

    private void handleTitleTap(RowHolder h) {
        Item t = taskAt(h);
        if (t == null || !canRename(t)) return;
        long nowMs = android.os.SystemClock.uptimeMillis();
        if (t.id.equals(lastTapId) && nowMs - lastTapTime <= DOUBLE_TAP_MS) {
            lastTapId = null;
            lastTapTime = 0;
            beginRename(h, t);
        } else {
            lastTapId = t.id;
            lastTapTime = nowMs;
        }
    }

    /** Enter inline-rename mode for a task: its row swaps the title for an EditText on next bind. */
    private void beginRename(RowHolder h, Item t) {
        editingTaskId = t.id;
        int pos = h.getBindingAdapterPosition();
        if (pos != RecyclerView.NO_POSITION) {
            if (pos < rowSigs.size()) rowSigs.set(pos, signature(rows.get(pos)));
            notifyItemChanged(pos);
        }
    }

    /** Commit (or cancel, if left empty) an inline rename and leave edit mode. */
    private void commitRename(RowHolder h) {
        if (editingTaskId == null) return;
        String id = editingTaskId;
        String text = h.titleEdit.getText().toString();
        editingTaskId = null;
        InputMethodManager imm = (InputMethodManager) h.titleEdit.getContext()
                .getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(h.titleEdit.getWindowToken(), 0);
        h.titleEdit.clearFocus();
        listener.onRenameTask(id, text);
    }

    private void bindTask(RowHolder h, Row row) {
        Item item = row.item;
        boolean done = item.completed;

        indent(h.itemView, row.depth);

        boolean repeatingTab = Categories.REPEATING.equals(activeTab);
        if (repeatingTab) {
            // The Repeating tab shows the rule, not a checkbox.
            h.checkCircle.setVisibility(View.GONE);
            h.checkTick.setVisibility(View.GONE);
            h.repeatLead.setVisibility(View.VISIBLE);
            h.rowCheck.setOnClickListener(null);
            h.rowCheck.setClickable(false);
        } else {
            h.repeatLead.setVisibility(View.GONE);
            h.checkCircle.setVisibility(View.VISIBLE);
            if (item.isVirtual) {
                // Upcoming occurrence: a dashed, non-interactive circle.
                h.checkCircle.setBackgroundResource(R.drawable.chk_virtual);
                h.checkTick.setVisibility(View.GONE);
                h.rowCheck.setOnClickListener(null);
                h.rowCheck.setClickable(false);
            } else {
                h.checkCircle.setBackgroundResource(R.drawable.bg_chk);
                h.checkCircle.setSelected(done);
                h.checkTick.setVisibility(done ? View.VISIBLE : View.GONE);
                h.rowCheck.setClickable(true);
                h.rowCheck.setOnClickListener(v -> { Item t = taskAt(h); if (t != null) listener.onToggle(t); });
            }
        }

        h.title.setText(item.text);
        if (done) {
            h.title.setPaintFlags(h.title.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
            h.title.setTextColor(color(h, R.color.muted));
        } else {
            h.title.setPaintFlags(h.title.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
            h.title.setTextColor(color(h, item.isVirtual ? R.color.muted : R.color.fg));
        }

        // Inline rename: while this task is being edited its row shows an EditText in place of the
        // title. Otherwise the title is a double-tap target (matching the web's dblclick), and a
        // clickable TextView still lets the list scroll — RecyclerView cancels the click on scroll.
        boolean editing = item.id != null && item.id.equals(editingTaskId);
        if (editing) {
            h.title.setVisibility(View.GONE);
            h.title.setOnClickListener(null);
            h.title.setClickable(false);
            h.titleEdit.setVisibility(View.VISIBLE);
            h.titleEdit.setText(item.text);
            h.titleEdit.setSelection(h.titleEdit.getText().length());
            h.titleEdit.setOnEditorActionListener((v, actionId, e) -> {
                if (actionId == EditorInfo.IME_ACTION_DONE) { commitRename(h); return true; }
                return false;
            });
            h.titleEdit.setOnFocusChangeListener((v, hasFocus) -> { if (!hasFocus) commitRename(h); });
            h.titleEdit.post(() -> {
                if (!item.id.equals(editingTaskId)) return;
                if (h.titleEdit.requestFocus()) {
                    InputMethodManager imm = (InputMethodManager) h.titleEdit.getContext()
                            .getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) imm.showSoftInput(h.titleEdit, InputMethodManager.SHOW_IMPLICIT);
                }
            });
        } else {
            h.titleEdit.setOnFocusChangeListener(null);
            h.titleEdit.setOnEditorActionListener(null);
            h.titleEdit.setVisibility(View.GONE);
            h.title.setVisibility(View.VISIBLE);
            if (canRename(item)) {
                h.title.setClickable(true);
                h.title.setOnClickListener(v -> handleTitleTap(h));
            } else {
                h.title.setOnClickListener(null);
                h.title.setClickable(false);
            }
        }

        String meta = buildMeta(item);
        h.meta.setVisibility(meta.isEmpty() ? View.GONE : View.VISIBLE);
        h.meta.setText(meta);

        // An undated project task reads as due today rather than a dash.
        Item cellItem = item;
        if (Categories.PROJECTS.equals(activeTab) && !item.hasDate()) {
            cellItem = new Item();
            cellItem.type = item.type;
            cellItem.noDue = false;
            cellItem.dueDate = now.toLocalDate().toString();
            cellItem.dueTime = "";
        }
        h.time.setText(ViewLogic.formatTimeCell(cellItem, now));

        ViewLogic.TimeState state = ViewLogic.timeState(item, now);
        switch (state) {
            case LATE:
                h.time.setTextColor(color(h, R.color.warn));
                h.time.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
                break;
            case SOON:
                h.time.setTextColor(color(h, R.color.fg));
                h.time.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
                break;
            default:
                h.time.setTextColor(color(h, R.color.muted));
                h.time.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
        }

        if (item.isVirtual) {
            h.menu.setVisibility(View.INVISIBLE);
            h.menu.setOnClickListener(null);
        } else if (done && !Categories.COMPLETED.equals(activeTab)) {
            h.menu.setVisibility(View.VISIBLE);
            h.menu.setImageResource(R.drawable.ic_x);
            h.menu.setOnClickListener(v -> { Item t = taskAt(h); if (t != null) listener.onDismiss(t); });
        } else {
            h.menu.setVisibility(View.VISIBLE);
            h.menu.setImageResource(R.drawable.ic_dots);
            h.menu.setOnClickListener(v -> { Item t = taskAt(h); if (t != null) listener.onMenu(t, v); });
        }
    }

    private void bindProjectHead(ProjectHeadHolder h, Row row) {
        indent(h.itemView, row.depth);
        h.name.setText(row.project.name);
        h.count.setText(row.openCount > 0 ? String.valueOf(row.openCount) : "");

        float target = row.expanded ? 90f : 0f;
        Object boundId = h.chevron.getTag(R.id.project_chevron);
        boolean sameProject = row.project.id.equals(boundId);
        h.chevron.animate().cancel();
        if (sameProject && h.chevron.getRotation() != target) {
            // The project already on screen was just toggled — animate the chevron turning.
            h.chevron.animate()
                    .rotation(target)
                    .setDuration(CHEVRON_DURATION_MS)
                    .setInterpolator(CHEVRON_INTERPOLATOR)
                    .start();
        } else {
            // First bind, or a recycled holder now showing a different project — snap, don't spin.
            h.chevron.setRotation(target);
        }
        h.chevron.setTag(R.id.project_chevron, row.project.id);

        h.toggle.setOnClickListener(v -> { Project p = projectAt(h); if (p != null) listener.onProjectToggle(p); });
        h.menu.setOnClickListener(v -> { Project p = projectAt(h); if (p != null) listener.onProjectMenu(p, v); });
    }

    /** Left padding grows with tree depth: 8dp + depth * 20dp (matches .list--tree in styles.css). */
    private void indent(View view, int depth) {
        int start = dp(view, 8 + depth * 20);
        view.setPaddingRelative(start, view.getPaddingTop(), view.getPaddingEnd(), view.getPaddingBottom());
    }

    private int dp(View view, int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                view.getResources().getDisplayMetrics());
    }

    private String buildMeta(Item item) {
        List<String> parts = new ArrayList<>();
        if (item.repeatRule != null) {
            parts.add(com.karson.todoapp.logic.RepeatRules.formatLabel(item.repeatRule));
        }
        if (item.isEvent() && item.endTime != null && !item.endTime.isEmpty()) {
            parts.add("until " + item.endTime);
        }
        return String.join("  ·  ", parts);
    }

    private int color(RecyclerView.ViewHolder h, int res) {
        return ContextCompat.getColor(h.itemView.getContext(), res);
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    static class RowHolder extends RecyclerView.ViewHolder {
        final View rowCheck;
        final View checkCircle;
        final ImageView checkTick;
        final ImageView repeatLead;
        final TextView title;
        final EditText titleEdit;
        final TextView meta;
        final TextView time;
        final ImageButton menu;

        RowHolder(@NonNull View v) {
            super(v);
            rowCheck = v.findViewById(R.id.row_check);
            checkCircle = v.findViewById(R.id.check_circle);
            checkTick = v.findViewById(R.id.check_tick);
            repeatLead = v.findViewById(R.id.row_repeat_lead);
            title = v.findViewById(R.id.row_title);
            titleEdit = v.findViewById(R.id.row_title_edit);
            meta = v.findViewById(R.id.row_meta);
            time = v.findViewById(R.id.row_time);
            menu = v.findViewById(R.id.row_menu);
        }
    }

    static class ProjectHeadHolder extends RecyclerView.ViewHolder {
        final View toggle;
        final ImageView chevron;
        final TextView name;
        final TextView count;
        final ImageButton menu;

        ProjectHeadHolder(@NonNull View v) {
            super(v);
            toggle = v.findViewById(R.id.project_toggle);
            chevron = v.findViewById(R.id.project_chevron);
            name = v.findViewById(R.id.project_name);
            count = v.findViewById(R.id.project_count);
            menu = v.findViewById(R.id.project_menu);
        }
    }

    /**
     * Tells RecyclerView which rows are the same across a render so it can animate the difference.
     * Identity is type + id (so a project's children sliding in/out are seen as inserts/removes);
     * contents is compared via the signatures captured at submit time (see {@link #signature}), so
     * an in-place Item edit is still detected.
     */
    private static class RowDiff extends DiffUtil.Callback {
        private final List<Row> oldRows;
        private final List<Row> newRows;
        private final List<String> oldSigs;
        private final List<String> newSigs;

        RowDiff(List<Row> oldRows, List<Row> newRows, List<String> oldSigs, List<String> newSigs) {
            this.oldRows = oldRows;
            this.newRows = newRows;
            this.oldSigs = oldSigs;
            this.newSigs = newSigs;
        }

        @Override public int getOldListSize() { return oldRows.size(); }
        @Override public int getNewListSize() { return newRows.size(); }

        @Override
        public boolean areItemsTheSame(int oldPos, int newPos) {
            Row a = oldRows.get(oldPos);
            Row b = newRows.get(newPos);
            if (a.type != b.type) return false;
            if (a.type == Row.TYPE_PROJECT_HEAD) return idEquals(a.project.id, b.project.id);
            return idEquals(a.item.id, b.item.id);
        }

        @Override
        public boolean areContentsTheSame(int oldPos, int newPos) {
            return oldSigs.get(oldPos).equals(newSigs.get(newPos));
        }

        private static boolean idEquals(String a, String b) {
            return a != null && a.equals(b);
        }
    }
}
