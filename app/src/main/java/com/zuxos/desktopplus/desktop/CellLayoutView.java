package com.zuxos.desktopplus.desktop;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.DragEvent;
import android.view.View;
import android.view.ViewGroup;

import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Ui;
import com.zuxos.desktopplus.model.Item;

/**
 * The desktop grid.
 *
 * <p>Children are placed on integer cells and can span several of them (widgets). The grid
 * owns hit-testing and the drop highlight; deciding what a drop *means* is the host's job.
 */
public class CellLayoutView extends ViewGroup implements View.OnDragListener {

    /** What the host wants to know about. */
    public interface Callbacks {
        void onDropOnCell(DragPayload payload, int cellX, int cellY);

        void onDropOnItem(DragPayload payload, Item target);

        void onDropRejected(DragPayload payload);

        void onDragEnded(DragPayload payload);

        void onEmptySpaceMenu(float x, float y);
    }

    public static class CellParams extends ViewGroup.LayoutParams {
        public int cellX;
        public int cellY;
        public int spanX = 1;
        public int spanY = 1;

        public CellParams(int cellX, int cellY, int spanX, int spanY) {
            super(WRAP_CONTENT, WRAP_CONTENT);
            this.cellX = cellX;
            this.cellY = cellY;
            this.spanX = Math.max(1, spanX);
            this.spanY = Math.max(1, spanY);
        }
    }

    private final Paint mHintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mMergePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private Callbacks mCallbacks;
    private int mPreferredCellPx;
    private int mCols = 1;
    private int mRows = 1;
    private int mCellW = 1;
    private int mCellH = 1;

    private int mHintX = -1;
    private int mHintY = -1;
    private boolean mHintIsMerge;
    private boolean mFoldersEnabled = true;

    public CellLayoutView(Context ctx, int preferredCellPx) {
        super(ctx);
        mPreferredCellPx = preferredCellPx;
        setWillNotDraw(false);
        setOnDragListener(this);
        mHintPaint.setColor(Ui.COLOR_DROP_HINT);
        mMergePaint.setColor(0x554C8DFF);
        mMergePaint.setStyle(Paint.Style.STROKE);
        mMergePaint.setStrokeWidth(Ui.dp(ctx, 3));
    }

    public void setCallbacks(Callbacks cb) {
        mCallbacks = cb;
    }

    public void setFoldersEnabled(boolean enabled) {
        mFoldersEnabled = enabled;
    }

    public void setPreferredCellPx(int px) {
        mPreferredCellPx = Math.max(px, Ui.dp(getContext(), 48));
        requestLayout();
    }

    public int getCols() {
        return mCols;
    }

    public int getRows() {
        return mRows;
    }

    public int getCellWidth() {
        return mCellW;
    }

    public int getCellHeight() {
        return mCellH;
    }

    // --- layout ----------------------------------------------------------

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        int usableW = Math.max(1, width - getPaddingLeft() - getPaddingRight());
        int usableH = Math.max(1, height - getPaddingTop() - getPaddingBottom());

        mCols = Math.max(1, usableW / mPreferredCellPx);
        mRows = Math.max(1, usableH / mPreferredCellPx);
        mCellW = usableW / mCols;
        mCellH = usableH / mRows;

        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            CellParams lp = params(child);
            int w = mCellW * lp.spanX;
            int h = mCellH * lp.spanY;
            child.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
        }
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            CellParams lp = params(child);
            int left = getPaddingLeft() + lp.cellX * mCellW;
            int top = getPaddingTop() + lp.cellY * mCellH;
            child.layout(left, top, left + mCellW * lp.spanX, top + mCellH * lp.spanY);
        }
    }

    private CellParams params(View child) {
        ViewGroup.LayoutParams lp = child.getLayoutParams();
        if (lp instanceof CellParams) {
            return (CellParams) lp;
        }
        CellParams cp = new CellParams(0, 0, 1, 1);
        child.setLayoutParams(cp);
        return cp;
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        if (mHintX >= 0 && mHintY >= 0) {
            float left = getPaddingLeft() + mHintX * mCellW;
            float top = getPaddingTop() + mHintY * mCellH;
            RectF rect = new RectF(left + 4, top + 4, left + mCellW - 4, top + mCellH - 4);
            float radius = Ui.dp(getContext(), 14);
            if (mHintIsMerge) {
                canvas.drawRoundRect(rect, radius, radius, mMergePaint);
            } else {
                canvas.drawRoundRect(rect, radius, radius, mHintPaint);
            }
        }
        super.dispatchDraw(canvas);
    }

    // --- cell helpers ----------------------------------------------------

    public void addItemView(View v, int cellX, int cellY, int spanX, int spanY) {
        addView(v, new CellParams(clampX(cellX), clampY(cellY), spanX, spanY));
    }

    public void moveItemView(View v, int cellX, int cellY) {
        CellParams lp = params(v);
        lp.cellX = clampX(cellX);
        lp.cellY = clampY(cellY);
        requestLayout();
    }

    private int clampX(int x) {
        return Math.max(0, Math.min(x, mCols - 1));
    }

    private int clampY(int y) {
        return Math.max(0, Math.min(y, mRows - 1));
    }

    public int cellXForPixel(float px) {
        return clampX((int) ((px - getPaddingLeft()) / Math.max(1, mCellW)));
    }

    public int cellYForPixel(float py) {
        return clampY((int) ((py - getPaddingTop()) / Math.max(1, mCellH)));
    }

    public View childAtCell(int cellX, int cellY) {
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            CellParams lp = params(child);
            if (cellX >= lp.cellX && cellX < lp.cellX + lp.spanX
                    && cellY >= lp.cellY && cellY < lp.cellY + lp.spanY) {
                return child;
            }
        }
        return null;
    }

    public boolean isFree(int cellX, int cellY, int spanX, int spanY, View ignore) {
        if (cellX < 0 || cellY < 0 || cellX + spanX > mCols || cellY + spanY > mRows) {
            return false;
        }
        for (int x = cellX; x < cellX + spanX; x++) {
            for (int y = cellY; y < cellY + spanY; y++) {
                View at = childAtCell(x, y);
                if (at != null && at != ignore) {
                    return false;
                }
            }
        }
        return true;
    }

    /** First free spot scanning row by row, or {@code null} when the grid is full. */
    public int[] findFreeCell(int spanX, int spanY, View ignore) {
        for (int y = 0; y + spanY <= mRows; y++) {
            for (int x = 0; x + spanX <= mCols; x++) {
                if (isFree(x, y, spanX, spanY, ignore)) {
                    return new int[]{x, y};
                }
            }
        }
        return null;
    }

    /** Nearest free cell to (cellX, cellY) by expanding rings - used when a drop lands busy. */
    public int[] findNearestFreeCell(int cellX, int cellY, int spanX, int spanY, View ignore) {
        if (isFree(cellX, cellY, spanX, spanY, ignore)) {
            return new int[]{cellX, cellY};
        }
        int maxRadius = Math.max(mCols, mRows);
        for (int radius = 1; radius <= maxRadius; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    if (Math.abs(dx) != radius && Math.abs(dy) != radius) {
                        continue;
                    }
                    int x = cellX + dx;
                    int y = cellY + dy;
                    if (isFree(x, y, spanX, spanY, ignore)) {
                        return new int[]{x, y};
                    }
                }
            }
        }
        return findFreeCell(spanX, spanY, ignore);
    }

    // --- drag & drop -----------------------------------------------------

    @Override
    public boolean onDrag(View v, DragEvent event) {
        Object local = event.getLocalState();
        if (!(local instanceof DragPayload)) {
            return false;
        }
        DragPayload payload = (DragPayload) local;
        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED:
                return true;
            case DragEvent.ACTION_DRAG_LOCATION:
                updateHint(payload, event.getX(), event.getY());
                return true;
            case DragEvent.ACTION_DRAG_EXITED:
                clearHint();
                return true;
            case DragEvent.ACTION_DROP:
                clearHint();
                return handleDrop(payload, event.getX(), event.getY());
            case DragEvent.ACTION_DRAG_ENDED:
                clearHint();
                if (mCallbacks != null) {
                    mCallbacks.onDragEnded(payload);
                }
                return true;
            default:
                return true;
        }
    }

    private void updateHint(DragPayload payload, float x, float y) {
        int cx = cellXForPixel(x);
        int cy = cellYForPixel(y);
        View at = childAtCell(cx, cy);
        boolean merge = false;
        if (at instanceof ItemView && ((ItemView) at).getItem() != payload.item) {
            Item target = ((ItemView) at).getItem();
            merge = mFoldersEnabled && canMerge(payload.item, target);
        }
        if (cx != mHintX || cy != mHintY || merge != mHintIsMerge) {
            mHintX = cx;
            mHintY = cy;
            mHintIsMerge = merge;
            invalidate();
        }
    }

    private void clearHint() {
        if (mHintX != -1 || mHintY != -1) {
            mHintX = -1;
            mHintY = -1;
            mHintIsMerge = false;
            invalidate();
        }
    }

    static boolean canMerge(Item dragged, Item target) {
        if (dragged == null || target == null || dragged == target) {
            return false;
        }
        if (dragged.type == Item.TYPE_WIDGET || target.type == Item.TYPE_WIDGET) {
            return false;
        }
        // Folders do not nest: dropping a folder onto a folder is a reject, not a merge.
        return !(dragged.type == Item.TYPE_FOLDER && target.type == Item.TYPE_FOLDER);
    }

    private boolean handleDrop(DragPayload payload, float x, float y) {
        if (mCallbacks == null) {
            return false;
        }
        int cx = cellXForPixel(x);
        int cy = cellYForPixel(y);
        View at = childAtCell(cx, cy);
        try {
            if (at instanceof ItemView) {
                Item target = ((ItemView) at).getItem();
                if (target != payload.item) {
                    if (mFoldersEnabled && canMerge(payload.item, target)) {
                        mCallbacks.onDropOnItem(payload, target);
                        return true;
                    }
                    int[] free = findNearestFreeCell(cx, cy, payload.item.spanX, payload.item.spanY,
                            viewForItem(payload.item));
                    if (free == null) {
                        mCallbacks.onDropRejected(payload);
                        return true;
                    }
                    cx = free[0];
                    cy = free[1];
                }
            } else if (!isFree(cx, cy, payload.item.spanX, payload.item.spanY,
                    viewForItem(payload.item))) {
                int[] free = findNearestFreeCell(cx, cy, payload.item.spanX, payload.item.spanY,
                        viewForItem(payload.item));
                if (free == null) {
                    mCallbacks.onDropRejected(payload);
                    return true;
                }
                cx = free[0];
                cy = free[1];
            }
            mCallbacks.onDropOnCell(payload, cx, cy);
            return true;
        } catch (Throwable t) {
            L.e("drop handling failed", t);
            return false;
        }
    }

    public View viewForItem(Item item) {
        if (item == null) {
            return null;
        }
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child instanceof ItemView && ((ItemView) child).getItem() == item) {
                return child;
            }
            if (child instanceof WidgetFrame && ((WidgetFrame) child).getItem() == item) {
                return child;
            }
        }
        return null;
    }
}
