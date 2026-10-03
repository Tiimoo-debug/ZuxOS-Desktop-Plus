package com.zuxos.desktopplus.hook;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Tone;
import com.zuxos.desktopplus.core.Ui;
import com.zuxos.desktopplus.desktop.DragPayload;
import com.zuxos.desktopplus.logic.PinList;
import com.zuxos.desktopplus.model.Item;

/**
 * Drop an app on the taskbar to pin it there.
 *
 * <p>The strip that catches the drop is ours and spans the bar. It is not the launcher's drag
 * layer: that view belongs to the launcher and may have a drag listener of its own, and a view
 * only has one - taking it would be breaking the launcher's own drag and drop to add ours.
 *
 * <p>Nothing here can be touched. It has no background and no listeners but the drag one, so every
 * press goes through it to the icons underneath; the only time you know it is there is when
 * something is being dragged over it.
 */
final class TaskbarDrop {

    private TaskbarDrop() {
    }

    /** Puts the strip in, or takes it out when nothing may be pinned any more. */
    static void apply(ViewGroup dragLayer) {
        try {
            DropStrip strip = stripIn(dragLayer);
            if (!Cfg.enabled()) {
                if (strip != null) {
                    dragLayer.removeView(strip);
                }
                return;
            }
            if (strip == null) {
                add(dragLayer);
            } else {
                strip.place();
            }
        } catch (Throwable t) {
            L.d("taskbar drop: not installed (" + t + ")");
        }
    }

    private static DropStrip stripIn(ViewGroup dragLayer) {
        for (int i = 0; i < dragLayer.getChildCount(); i++) {
            if (dragLayer.getChildAt(i) instanceof DropStrip) {
                return (DropStrip) dragLayer.getChildAt(i);
            }
        }
        return null;
    }

    private static void add(ViewGroup dragLayer) {
        View reference = TaskbarTray.rowReference(dragLayer);
        ViewGroup.LayoutParams lp = TaskbarTray.dragLayerParams(dragLayer, reference);
        if (!(lp instanceof FrameLayout.LayoutParams)) {
            return;
        }
        DropStrip strip = new DropStrip(dragLayer, reference);
        ((FrameLayout.LayoutParams) lp).width = ViewGroup.LayoutParams.MATCH_PARENT;
        // Appended, the way the tray is, rather than pushed in at the front of the launcher's own
        // children. Being on top costs nothing here: the strip is not clickable, so a touch goes
        // straight past it to whatever is underneath, and only a drag ever stops at it.
        dragLayer.addView(strip, lp);
        strip.place();
        L.i("taskbar drop: an app dropped on the bar is pinned to it");
    }

    /** The strip itself: a drag target, and a line showing where the app would land. */
    private static final class DropStrip extends View {

        private final ViewGroup mDragLayer;
        private final View mReference;
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float mWhere = -1f;

        DropStrip(ViewGroup dragLayer, View reference) {
            super(dragLayer.getContext());
            mDragLayer = dragLayer;
            mReference = reference;
            setClickable(false);
            setFocusable(false);
            setOnDragListener(this::onDrag);
        }

        void place() {
            ViewGroup.LayoutParams raw = getLayoutParams();
            if (!(raw instanceof FrameLayout.LayoutParams) || mReference == null
                    || mReference.getHeight() <= 0) {
                return;
            }
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) raw;
            if (lp.topMargin == mReference.getTop() && lp.height == mReference.getHeight()) {
                return;
            }
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.topMargin = mReference.getTop();
            lp.height = mReference.getHeight();
            setLayoutParams(lp);
        }

        private boolean onDrag(View v, DragEvent event) {
            if (!DragPayload.isOurs(event)) {
                return false;
            }
            switch (event.getAction()) {
                case DragEvent.ACTION_DRAG_STARTED:
                    return true;
                case DragEvent.ACTION_DRAG_LOCATION:
                    mWhere = event.getX();
                    invalidate();
                    return true;
                case DragEvent.ACTION_DRAG_EXITED:
                case DragEvent.ACTION_DRAG_ENDED:
                    mWhere = -1f;
                    invalidate();
                    return true;
                case DragEvent.ACTION_DROP:
                    mWhere = -1f;
                    invalidate();
                    return pin(DragPayload.of(event), event.getX());
                default:
                    return true;
            }
        }

        private boolean pin(DragPayload payload, float x) {
            if (payload == null || payload.item == null) {
                return false;
            }
            Item item = payload.item;
            if (item.pkg == null) {
                // A widget, or a shortcut with nothing but an intent. The bar launches packages.
                TaskbarMenu.toast(getContext(), "Only apps can be pinned to the taskbar");
                return false;
            }
            if (TaskbarRunning.alreadyInTheBar(mDragLayer, item.pkg)) {
                // Said rather than done: pinning it would leave two of the same icon side by
                // side, and a drop that quietly does nothing looks like a drop that missed.
                TaskbarMenu.toast(getContext(), "That app is already in the taskbar");
                return false;
            }
            TaskbarPins.pin(getContext(), item, where(x));
            TaskbarRunning.apply(mDragLayer);
            return true;
        }

        /** Which place in the row a drop at this x belongs to. */
        private int where(float x) {
            ViewGroup row = TaskbarRunning.rowOf(mDragLayer);
            if (row == null) {
                return PinList.AT_THE_END;
            }
            int index = 0;
            for (int i = 0; i < row.getChildCount(); i++) {
                View child = row.getChildAt(i);
                if (x > row.getLeft() + child.getLeft() + child.getWidth() / 2f) {
                    index = i + 1;
                }
            }
            return index;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (mWhere < 0) {
                return;
            }
            // Where it would land, drawn only while something is actually over the bar.
            mPaint.setColor(Tone.lightOnDark(getContext()) ? 0x66FFFFFF : 0x33000000);
            float width = Ui.dp(getContext(), 3);
            float inset = Ui.dp(getContext(), 8);
            RectF line = new RectF(mWhere - width / 2f, inset,
                    mWhere + width / 2f, getHeight() - inset);
            canvas.drawRoundRect(line, width / 2f, width / 2f, mPaint);
        }
    }
}
