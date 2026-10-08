package com.zuxos.desktopplus.hook.taskbar;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Tone;
import com.zuxos.desktopplus.core.Ui;
import com.zuxos.desktopplus.hook.IconInfo;
import com.zuxos.desktopplus.logic.RunningOrder;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Set;

/**
 * A mark under every taskbar icon whose app is open.
 *
 * <p>This is the half of "show me what is running" that hiding icons cannot do. An app pinned to
 * the bar, or reached through a shortcut or a folder, looks exactly the same open or closed - which
 * is what "it does not show as opened" meant. Every desktop since the nineties answers it the same
 * way: a small mark under the icon.
 *
 * <p>One view, ours, over the whole bar, drawing nothing but dots. It reads the rows' own children
 * each time it draws, so it cannot go stale between refreshes, and it is not clickable, so a touch
 * passes straight through it to the icon underneath.
 */
final class TaskbarMarks {

    private static final int HEIGHT_DP = 3;
    private static final int WIDTH_DP = 14;
    private static final int BELOW_DP = 2;

    private TaskbarMarks() {
    }

    /** Puts the marks up, moves them, or takes them away, following the setting. */
    static void apply(ViewGroup dragLayer, ViewGroup icons, Set<String> running) {
        try {
            MarkView marks = markIn(dragLayer);
            if (!Cfg.runningMarks()) {
                remove(dragLayer);
                return;
            }
            if (marks == null) {
                marks = add(dragLayer);
                if (marks == null) {
                    return;
                }
            }
            marks.show(icons, running);
        } catch (Throwable t) {
            L.d("taskbar marks: not shown (" + t + ")");
        }
    }

    /** Takes the marks away, for when there is nothing left to mark. */
    static void remove(ViewGroup dragLayer) {
        MarkView marks = markIn(dragLayer);
        if (marks != null) {
            dragLayer.removeView(marks);
        }
    }

    /**
     * Draws one mark under an icon whose app is open, in the canvas of whatever view holds the
     * icon - our row draws its own with this, so its marks are part of what scrolls.
     *
     * @param left   the icon's left edge in the canvas, translation included
     * @param bottom where the icon ends vertically in the canvas
     * @param floor  how far down the canvas may be drawn on
     */
    static void drawMark(Canvas canvas, Paint paint, Context ctx, float left, float width,
            float bottom, float floor) {
        float height = Ui.dp(ctx, HEIGHT_DP);
        float markWidth = Ui.dp(ctx, WIDTH_DP);
        float end = Math.min(bottom + Ui.dp(ctx, BELOW_DP), floor);
        float centre = left + width / 2f;
        canvas.drawRoundRect(new RectF(centre - markWidth / 2f, end - height,
                centre + markWidth / 2f, end), height / 2f, height / 2f, paint);
    }

    /** The marks' colour: light over a dark bar, dark over a light one. */
    static int markColor(Context ctx) {
        return Tone.lightOnDark(ctx) ? 0xCCFFFFFF : 0x99000000;
    }

    /** Re-draws them without re-reading what is open; for when the bar has just been laid out. */
    static void refresh(ViewGroup dragLayer) {
        MarkView marks = markIn(dragLayer);
        if (marks != null) {
            marks.place();
            marks.invalidate();
        }
    }

    private static MarkView markIn(ViewGroup dragLayer) {
        for (int i = 0; i < dragLayer.getChildCount(); i++) {
            if (dragLayer.getChildAt(i) instanceof MarkView) {
                return (MarkView) dragLayer.getChildAt(i);
            }
        }
        return null;
    }

    private static MarkView add(ViewGroup dragLayer) {
        View reference = TaskbarTray.rowReference(dragLayer);
        ViewGroup.LayoutParams lp = TaskbarTray.dragLayerParams(dragLayer, reference);
        if (!(lp instanceof FrameLayout.LayoutParams)) {
            return null;
        }
        MarkView marks = new MarkView(dragLayer.getContext(), dragLayer, reference);
        ((FrameLayout.LayoutParams) lp).width = ViewGroup.LayoutParams.MATCH_PARENT;
        dragLayer.addView(marks, lp);
        marks.place();
        L.i("taskbar marks: a mark under every icon that is open");
        return marks;
    }

    /** Draws the marks, and nothing else. */
    private static final class MarkView extends View {

        private final WeakReference<ViewGroup> mDragLayer;
        private final WeakReference<View> mReference;
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private WeakReference<ViewGroup> mIcons = new WeakReference<>(null);
        private Set<String> mRunning = Collections.emptySet();

        MarkView(Context ctx, ViewGroup dragLayer, View reference) {
            super(ctx);
            mDragLayer = new WeakReference<>(dragLayer);
            mReference = new WeakReference<>(reference);
            // Nothing here reacts to a touch; the icons underneath do.
            setClickable(false);
            setFocusable(false);
        }

        void show(ViewGroup icons, Set<String> running) {
            mIcons = new WeakReference<>(icons);
            mRunning = running;
            place();
            invalidate();
        }

        /** Over the bar, exactly as high as it is. */
        void place() {
            ViewGroup dragLayer = mDragLayer.get();
            View reference = dragLayer != null ? TaskbarTray.rowReference(dragLayer) : null;
            if (reference == null) {
                reference = mReference.get();
            }
            ViewGroup.LayoutParams raw = getLayoutParams();
            if (reference == null || reference.getHeight() <= 0
                    || !(raw instanceof FrameLayout.LayoutParams)) {
                return;
            }
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) raw;
            int top = reference.getTop();
            int height = reference.getHeight();
            if (lp.topMargin == top && lp.height == height) {
                // Setting them again would ask for another layout, from inside a layout.
                return;
            }
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.topMargin = top;
            lp.height = height;
            setLayoutParams(lp);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            ViewGroup dragLayer = mDragLayer.get();
            if (dragLayer == null || mRunning.isEmpty()) {
                return;
            }
            mPaint.setColor(markColor(getContext()));
            float height = Ui.dp(getContext(), HEIGHT_DP);
            float width = Ui.dp(getContext(), WIDTH_DP);
            float below = Ui.dp(getContext(), BELOW_DP);
            // The launcher's own row is a child of the drag layer, so its own left is where it
            // is. Ours is inside a scroller, so it has to be asked where it has ended up.
            ViewGroup icons = mIcons.get();
            if (icons != null) {
                mark(canvas, icons, icons.getLeft(), icons.getTop(), height, width, below);
            }
            // Our own row draws its marks itself (TaskbarRunning.RunningRow), inside the scroller,
            // so they move in the same frame as the icons. Drawn from here they had to wait for
            // this view to redraw, which scrolling never asked for - so they trailed behind.
        }

        private void mark(Canvas canvas, ViewGroup row, int rowLeft, int rowTop,
                float height, float width, float below) {
            if (row == null || row.getVisibility() != VISIBLE) {
                return;
            }
            for (int i = 0; i < row.getChildCount(); i++) {
                View icon = row.getChildAt(i);
                if (icon.getVisibility() != VISIBLE || icon.getWidth() <= 0) {
                    continue;
                }
                // The launcher's icons carry an item info; ours carry their package as the tag.
                // Either way the question is the same, and a folder answers for everything in it.
                if (!RunningOrder.anyRunning(IconInfo.packagesOfView(icon), mRunning)) {
                    continue;
                }
                float centre = rowLeft + icon.getLeft() + icon.getWidth() / 2f - getLeft();
                float bottom = rowTop + icon.getBottom() - getTop() + below;
                if (bottom > getHeight()) {
                    bottom = getHeight();
                }
                RectF dot = new RectF(centre - width / 2f, bottom - height,
                        centre + width / 2f, bottom);
                canvas.drawRoundRect(dot, height / 2f, height / 2f, mPaint);
            }
        }
    }
}
