package com.karson.todoapp.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.karson.todoapp.R;
import com.karson.todoapp.logic.ProjectLogic;
import com.karson.todoapp.model.Project;

import java.util.ArrayList;
import java.util.List;

/**
 * Drag to reorder in the Projects tab: press and hold a row, then move. Port of the web app's
 * "Drag to reorder" block in script.mjs. A project carries its whole sub-tree and drops before or
 * after another project, or onto its middle to nest inside it; under the end of a nested group,
 * sliding sideways picks the level, out to the top level. A task drops between tasks, or onto a
 * project head to land first in it. The controller only works out where the row lands; the
 * {@link Host} applies and saves the move.
 */
public class ProjectDragController extends RecyclerView.ItemDecoration
        implements RecyclerView.OnItemTouchListener {

    public interface Host {
        /** Whether rows can be dragged right now (Projects tab, no search filter). */
        boolean canDrag();

        List<Project> projects();

        /** Task ids in a project, top to bottom as the tree shows them (also when collapsed). */
        List<String> projectTaskOrder(String projectId);

        /** Put the project under {@code parentId} (null = top level), before {@code beforeId} or last. */
        void onDropProject(String projectId, String parentId, String beforeId, boolean into);

        /** Put the task in {@code projectId}, before {@code beforeId} or last. */
        void onDropTask(String taskId, String projectId, String beforeId, boolean into);
    }

    /** Where the dragged row would land if released now. */
    private static class Target {
        boolean into;        // dropped onto a project (highlighted) rather than on a line
        String containerId;  // new parent project (project drag) or owning project (task drag)
        String beforeId;     // sibling to land in front of; null = last
        int anchorPos;       // adapter position of the row the line or highlight is drawn on
        boolean lineAtTop;
        int depth;           // indent level of the line
    }

    private static final float DRAGGED_ALPHA = 0.35f;
    private static final int EDGE_DP = 48;       // auto-scroll zone at the top and bottom of the list
    private static final int LEVEL_DP = 24;      // sideways travel that changes the level by one

    private final RecyclerView list;
    private final TaskAdapter adapter;
    private final Host host;
    private final GestureDetector detector;
    private final Paint accentFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint accentStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ghostBorder = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ghostPaint = new Paint();

    private float lastX, lastY; // latest pointer position, list-local

    private boolean dragging;
    private boolean isProject;
    private String dragId;
    private int dragDepth;
    private int dragStart, dragEnd; // adapter positions [start, end) of the rows being carried
    private Bitmap ghost;
    private float ghostLeft, offsetY, startX, maxShift;
    private float pointerX, pointerY;
    private Target target;

    private final Runnable autoScroll = new Runnable() {
        @Override
        public void run() {
            if (!dragging) return;
            float edge = dp(EDGE_DP);
            float over = 0;
            if (pointerY < edge) over = pointerY - edge;
            else if (pointerY > list.getHeight() - edge) over = pointerY - (list.getHeight() - edge);
            int step = (int) Math.max(-dp(12), Math.min(dp(12), over / 4f));
            if (step != 0 && list.canScrollVertically(step)) {
                list.scrollBy(0, step);
                update();
            }
            if (dragging) list.postOnAnimation(this);
        }
    };

    public ProjectDragController(RecyclerView list, TaskAdapter adapter, Host host) {
        this.list = list;
        this.adapter = adapter;
        this.host = host;

        Context context = list.getContext();
        int accent = ContextCompat.getColor(context, R.color.accent);
        accentFill.setColor(accent);
        accentStroke.setColor(accent);
        accentStroke.setStyle(Paint.Style.STROKE);
        accentStroke.setStrokeWidth(dp(1.5f));
        ghostBorder.setColor(ContextCompat.getColor(context, R.color.line));
        ghostBorder.setStyle(Paint.Style.STROKE);
        ghostBorder.setStrokeWidth(dp(1));
        ghostPaint.setAlpha(225); // a little see-through, so the rows underneath stay readable

        detector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public void onLongPress(@NonNull MotionEvent e) {
                begin(e.getX(), e.getY());
            }
        });

        list.addOnItemTouchListener(this);
        list.addItemDecoration(this);
    }

    public boolean isDragging() {
        return dragging;
    }

    // ---------- Touch ----------

    @Override
    public boolean onInterceptTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
        lastX = e.getX();
        lastY = e.getY();
        if (!dragging) {
            detector.onTouchEvent(e);
            return false;
        }
        // Claim the gesture: the row under the finger gets a cancel instead of a click, and the list
        // stops scrolling. A release that arrives here never reaches onTouchEvent, so end on it now.
        handle(e);
        return true;
    }

    @Override
    public void onTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
        lastX = e.getX();
        lastY = e.getY();
        if (dragging) handle(e);
    }

    @Override
    public void onRequestDisallowInterceptTouchEvent(boolean disallowIntercept) {}

    private void handle(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                pointerX = e.getX();
                pointerY = e.getY();
                update();
                break;
            case MotionEvent.ACTION_UP:
                finish(true);
                break;
            case MotionEvent.ACTION_CANCEL:
                finish(false);
                break;
            default:
                break;
        }
    }

    // ---------- Drag lifecycle ----------

    private void begin(float x, float y) {
        if (dragging || !host.canDrag() || adapter.isEditing()) return;
        View child = list.findChildViewUnder(x, y);
        if (child == null) return;
        int pos = list.getChildAdapterPosition(child);
        List<Row> rows = adapter.getRows();
        if (pos == RecyclerView.NO_POSITION || pos >= rows.size()) return;
        Row row = rows.get(pos);
        if (row.type == Row.TYPE_TASK && (row.item.isVirtual || row.item.id == null)) return;
        if (child.getWidth() <= 0 || child.getHeight() <= 0) return;

        isProject = row.type == Row.TYPE_PROJECT_HEAD;
        dragId = isProject ? row.project.id : row.item.id;
        dragDepth = row.depth;

        ghost = Bitmap.createBitmap(child.getWidth(), child.getHeight(), Bitmap.Config.ARGB_8888);
        child.draw(new Canvas(ghost));
        ghostLeft = child.getLeft();
        offsetY = lastY - child.getTop();
        maxShift = child.getWidth() / 3f;
        startX = lastX;
        pointerX = lastX;
        pointerY = lastY;

        dragging = true;
        list.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        if (list.getParent() != null) list.getParent().requestDisallowInterceptTouchEvent(true);
        update();
        list.postOnAnimation(autoScroll);
    }

    private void update() {
        List<Row> rows = adapter.getRows();
        int start = -1;
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            String id = row.type == Row.TYPE_PROJECT_HEAD ? row.project.id : row.item.id;
            if ((row.type == Row.TYPE_PROJECT_HEAD) == isProject && dragId.equals(id)) {
                start = i;
                break;
            }
        }
        if (start == -1) { // the row went away under the finger (a sync from another device)
            finish(false);
            return;
        }
        int end = start + 1;
        if (isProject) {
            while (end < rows.size() && rows.get(end).depth > rows.get(start).depth) end++;
        }
        dragStart = start;
        dragEnd = end;

        for (int i = 0; i < list.getChildCount(); i++) {
            View child = list.getChildAt(i);
            child.setAlpha(isDragged(list.getChildAdapterPosition(child)) ? DRAGGED_ALPHA : 1f);
        }
        target = findTarget(rows);
        list.invalidate();
    }

    private void finish(boolean commit) {
        if (!dragging) return;
        dragging = false;
        list.removeCallbacks(autoScroll);
        for (int i = 0; i < list.getChildCount(); i++) list.getChildAt(i).setAlpha(1f);
        if (ghost != null) {
            ghost.recycle();
            ghost = null;
        }
        Target drop = target;
        target = null;
        list.invalidate();

        if (!commit || drop == null) return;
        if (isProject) host.onDropProject(dragId, drop.containerId, drop.beforeId, drop.into);
        else host.onDropTask(dragId, drop.containerId, drop.beforeId, drop.into);
    }

    private boolean isDragged(int pos) {
        return pos >= dragStart && pos < dragEnd;
    }

    // ---------- Drop target ----------

    /** A row on screen: its adapter position and vertical extent. */
    private static class Shown {
        final int pos;
        final float top, bottom;

        Shown(int pos, float top, float bottom) {
            this.pos = pos;
            this.top = top;
            this.bottom = bottom;
        }
    }

    private Target findTarget(List<Row> rows) {
        // Rows on screen, top to bottom, without the ones being carried. Over those there is no target.
        List<Shown> shown = new ArrayList<>();
        for (int i = 0; i < list.getChildCount(); i++) {
            View child = list.getChildAt(i);
            int pos = list.getChildAdapterPosition(child);
            if (pos == RecyclerView.NO_POSITION || pos >= rows.size()) continue;
            float top = child.getTop() + child.getTranslationY();
            float bottom = child.getBottom() + child.getTranslationY();
            if (isDragged(pos)) {
                if (pointerY >= top && pointerY < bottom) return null;
                continue;
            }
            shown.add(new Shown(pos, top, bottom));
        }
        if (shown.isEmpty()) return null;
        shown.sort((a, b) -> Integer.compare(a.pos, b.pos));

        Shown hit = null;
        float fraction = 0f;
        boolean below = false;
        if (pointerY < shown.get(0).top) {
            hit = shown.get(0);
        } else {
            for (Shown row : shown) {
                if (pointerY < row.bottom) {
                    hit = row;
                    fraction = Math.max(0f, (pointerY - row.top) / Math.max(1f, row.bottom - row.top));
                    break;
                }
            }
            if (hit == null) {
                hit = shown.get(shown.size() - 1);
                fraction = 1f;
                // Below the whole list (not just the part on screen) always means the top level.
                int lastPos = dragEnd == rows.size() ? dragStart - 1 : rows.size() - 1;
                below = hit.pos == lastPos;
            }
        }
        return isProject
                ? findProjectTarget(rows, hit.pos, fraction, below)
                : findTaskTarget(rows, hit.pos, fraction);
    }

    private Target findTaskTarget(List<Row> rows, int pos, float fraction) {
        Row row = rows.get(pos);
        if (row.type == Row.TYPE_PROJECT_HEAD) {
            List<String> order = orderWithoutDragged(row.project.id);
            return target(true, row.project.id, order.isEmpty() ? null : order.get(0), pos, false, row.depth);
        }
        String projectId = row.item.projectId;
        if (fraction < 0.5f) return target(false, projectId, row.item.id, pos, true, row.depth);
        List<String> order = orderWithoutDragged(projectId);
        int index = order.indexOf(row.item.id);
        String next = index >= 0 && index + 1 < order.size() ? order.get(index + 1) : null;
        return target(false, projectId, next, pos, false, row.depth);
    }

    private List<String> orderWithoutDragged(String projectId) {
        List<String> order = new ArrayList<>(host.projectTaskOrder(projectId));
        order.remove(dragId);
        return order;
    }

    private Target findProjectTarget(List<Row> rows, int pos, float fraction, boolean below) {
        Row row = rows.get(pos);
        // Over a task, act as if over its project's head, so hovering a project's tasks nests into it.
        int headPos = row.type == Row.TYPE_PROJECT_HEAD ? pos : headPosition(rows, row.item.projectId);
        if (headPos == -1) return null;
        List<Project> projects = host.projects();
        Project over = rows.get(headPos).project;
        if (over.id.equals(dragId) || ProjectLogic.getDescendantIds(projects, dragId).contains(over.id)) {
            return null;
        }

        boolean onHead = headPos == pos;
        if (onHead && fraction < 0.3f) {
            return target(false, resolveParent(projects, over), over.id, pos, true, rows.get(headPos).depth);
        }

        // Under the last row of a nested group, the gap is "after" several projects at once: the
        // row's project, then each ancestor whose visible sub-tree ends at the same row (deepest
        // first). Sliding the finger left picks a shallower one, out to the top level.
        if (fraction > (onHead ? 0.7f : 0.5f)) {
            List<Project> levels = new ArrayList<>();
            List<Integer> levelDepths = new ArrayList<>();
            for (Project project = over; project != null; project = find(projects, project.parentId)) {
                int head = headPosition(rows, project.id);
                if (head == -1) break;
                int last = head;
                for (int i = head + 1; i < rows.size() && rows.get(i).depth > rows.get(head).depth; i++) {
                    if (!isDragged(i)) last = i;
                }
                if (last != pos) break;
                levels.add(project);
                levelDepths.add(rows.get(head).depth);
            }
            if (!levels.isEmpty()) {
                int wanted = below ? Integer.MIN_VALUE
                        : dragDepth + Math.round((pointerX - startX) / dp(LEVEL_DP));
                int chosen = levels.size() - 1;
                for (int i = 0; i < levels.size(); i++) {
                    if (levelDepths.get(i) <= wanted) {
                        chosen = i;
                        break;
                    }
                }
                Project level = levels.get(chosen);
                String parentId = resolveParent(projects, level);
                List<String> siblings = new ArrayList<>();
                for (Project sibling : ProjectLogic.getChildProjects(projects, parentId)) {
                    if (!sibling.id.equals(dragId)) siblings.add(sibling.id);
                }
                int index = siblings.indexOf(level.id);
                String beforeId = index >= 0 && index + 1 < siblings.size() ? siblings.get(index + 1) : null;
                return target(false, parentId, beforeId, pos, false, levelDepths.get(chosen));
            }
        }
        return target(true, over.id, null, headPos, false, rows.get(headPos).depth);
    }

    private static Target target(boolean into, String containerId, String beforeId, int anchorPos,
                                 boolean lineAtTop, int depth) {
        Target t = new Target();
        t.into = into;
        t.containerId = containerId;
        t.beforeId = beforeId;
        t.anchorPos = anchorPos;
        t.lineAtTop = lineAtTop;
        t.depth = depth;
        return t;
    }

    private static int headPosition(List<Row> rows, String projectId) {
        if (projectId == null) return -1;
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            if (row.type == Row.TYPE_PROJECT_HEAD && projectId.equals(row.project.id)) return i;
        }
        return -1;
    }

    private static Project find(List<Project> projects, String id) {
        if (id == null) return null;
        for (Project project : projects) if (id.equals(project.id)) return project;
        return null;
    }

    private static String resolveParent(List<Project> projects, Project project) {
        return find(projects, project.parentId) != null ? project.parentId : null;
    }

    // ---------- Drawing ----------

    @Override
    public void onDrawOver(@NonNull Canvas c, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        if (!dragging || ghost == null) return;

        // A project's ghost follows the finger sideways too, since that picks the nesting level.
        float shift = isProject ? Math.max(-maxShift, Math.min(maxShift, pointerX - startX)) : 0f;
        float ghostX = ghostLeft + shift;
        float ghostY = pointerY - offsetY;
        c.drawBitmap(ghost, ghostX, ghostY, ghostPaint);
        float half = ghostBorder.getStrokeWidth() / 2f;
        c.drawRoundRect(ghostX + half, ghostY + half, ghostX + ghost.getWidth() - half,
                ghostY + ghost.getHeight() - half, dp(6), dp(6), ghostBorder);

        // The drop mark goes over the ghost: the finger usually holds the ghost right on the target.
        if (target != null) {
            RecyclerView.ViewHolder holder = parent.findViewHolderForAdapterPosition(target.anchorPos);
            if (holder != null) {
                View row = holder.itemView;
                float top = row.getTop() + row.getTranslationY();
                float bottom = row.getBottom() + row.getTranslationY();
                if (target.into) {
                    float inset = accentStroke.getStrokeWidth() / 2f;
                    c.drawRoundRect(row.getLeft() + inset, top + inset, row.getRight() - inset,
                            bottom - inset, dp(6), dp(6), accentStroke);
                } else {
                    // Clamped so a line on the first or last row isn't cut off by the list's edge.
                    float y = Math.max(dp(1), Math.min(parent.getHeight() - dp(1), target.lineAtTop ? top : bottom));
                    float left = row.getLeft() + dp(8 + target.depth * 20);
                    float right = Math.max(left + dp(24), row.getRight() - dp(6));
                    c.drawRoundRect(left, y - dp(1), right, y + dp(1), dp(1), dp(1), accentFill);
                }
            }
        }
    }

    private float dp(float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                list.getResources().getDisplayMetrics());
    }
}
