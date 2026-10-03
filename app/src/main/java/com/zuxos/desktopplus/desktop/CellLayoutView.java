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

    /** Which cell of a multi-cell item the finger holds; see {@link #setGrabCells}. */
    private int mGrabX;
    private int mGrabY;
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

    /** Moves and resizes in one step, used by the widget resize frame. */
    public void setItemCell(View v, int cellX, int cellY, int spanX, int spanY) {
        CellParams lp = params(v);
        lp.cellX = clampX(cellX);
        lp.cellY = clampY(cellY);
        lp.spanX = Math.max(1, Math.min(spanX, mCols - lp.cellX));
        lp.spanY = Math.max(1, Math.min(spanY, mRows - lp.cellY));
        requestLayout();
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
        return isFree(occupancy(ignore), cellX, cellY, spanX, spanY);
    }

    /**
     * Cell occupancy as a flat grid.
     *
     * <p>Searching for a free spot used to walk every child for every cell of every candidate
     * position; building this once per search makes it one pass over the children instead.
     */
    private boolean[] occupancy(View ignore) {
        boolean[] taken = new boolean[Math.max(1, mCols * mRows)];
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child == ignore) {
                continue;
            }
            CellParams lp = params(child);
            for (int x = lp.cellX; x < lp.cellX + lp.spanX && x < mCols; x++) {
                for (int y = lp.cellY; y < lp.cellY + lp.spanY && y < mRows; y++) {
                    if (x >= 0 && y >= 0) {
                        taken[y * mCols + x] = true;
                    }
                }
            }
        }
        return taken;
    }

    private boolean isFree(boolean[] taken, int cellX, int cellY, int spanX, int spanY) {
        if (cellX < 0 || cellY < 0 || cellX + spanX > mCols || cellY + spanY > mRows) {
            return false;
        }
        for (int x = cellX; x < cellX + spanX; x++) {
            for (int y = cellY; y < cellY + spanY; y++) {
                if (taken[y * mCols + x]) {
                    return false;
                }
            }
        }
        return true;
    }

    /** First free spot scanning row by row, or {@code null} when the grid is full. */
    public int[] findFreeCell(int spanX, int spanY, View ignore) {
        return findFreeCell(occupancy(ignore), spanX, spanY);
    }

    private int[] findFreeCell(boolean[] taken, int spanX, int spanY) {
        for (int y = 0; y + spanY <= mRows; y++) {
            for (int x = 0; x + spanX <= mCols; x++) {
                if (isFree(taken, x, y, spanX, spanY)) {
                    return new int[]{x, y};
                }
            }
        }
        return null;
    }

    /** Nearest free cell to (cellX, cellY) by expanding rings - used when a drop lands busy. */
    public int[] findNearestFreeCell(int cellX, int cellY, int spanX, int spanY, View ignore) {
        boolean[] taken = occupancy(ignore);
        if (isFree(taken, cellX, cellY, spanX, spanY)) {
            return new int[]{cellX, cellY};
        }
        int maxRadius = Math.max(mCols, mRows);
        for (int radius = 1; radius <= maxRadius; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    if (Math.abs(dx) != radius && Math.abs(dy) != radius) {
                        continue;
                    }
                    if (isFree(taken, cellX + dx, cellY + dy, spanX, spanY)) {
                        return new int[]{cellX + dx, cellY + dy};
                    }
                }
            }
        }
        return findFreeCell(taken, spanX, spanY);
    }

    // --- drag & drop -----------------------------------------------------

    @Override
    public boolean onDrag(View v, DragEvent event) {
        // Asked of the description, not of the payload: a drag from another window - the stock
        // drawer is one - hands over nothing readable until it is dropped, and answering false
        // here would mean never hearing about the drop at all.
        if (!DragPayload.isOurs(event)) {
            return false;
        }
        DragPayload payload = DragPayload.of(event);
        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED:
                return true;
            case DragEvent.ACTION_DRAG_LOCATION:
                if (payload != null) {
                    updateHint(payload, event.getX(), event.getY());
                }
                return true;
            case DragEvent.ACTION_DRAG_EXITED:
                clearHint();
                return true;
            case DragEvent.ACTION_DROP:
                clearHint();
                // A drag from another window is only readable now, and unreadable means a drop
                // that does nothing rather than one that throws in the launcher's face.
                return payload != null && payload.item != null
                        && handleDrop(payload, event.getX(), event.getY());
            case DragEvent.ACTION_DRAG_ENDED:
                clearHint();
                mGrabX = 0;
                mGrabY = 0;
                if (mCallbacks != null && payload != null) {
                    mCallbacks.onDragEnded(payload);
                }
                return true;
            default:
                return true;
        }
    }

    /**
     * For a widget picked up by one of its inner cells: the drop puts the item's top-left that
     * many cells up and left of the cell under the finger. Cleared when the drag ends.
     */
    public void setGrabCells(int dx, int dy) {
        mGrabX = Math.max(0, dx);
        mGrabY = Math.max(0, dy);
    }

    private int dropCellX(float x) {
        return Math.max(0, cellXForPixel(x) - mGrabX);
    }

    private int dropCellY(float y) {
        return Math.max(0, cellYForPixel(y) - mGrabY);
    }

    private void updateHint(DragPayload payload, float x, float y) {
        int cx = dropCellX(x);
        int cy = dropCellY(y);
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
        int cx = dropCellX(x);
        int cy = dropCellY(y);
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
            final int grabX = mGrabX;
            final int grabY = mGrabY;
            mCallbacks.onDropOnCell(payload, cx, cy);
            // After the callback has put the item in its cell - and maybe rebuilt the view - the
            // view starts where the finger let go and glides into place, rather than appearing.
            post(() -> glide(payload.item, x, y, grabX, grabY));
            return true;
        } catch (Throwable t) {
            L.e("drop handling failed", t);
            return false;
        }
    }

    /**
     * Slides an item's view from the drop point into its cell.
     *
     * <p>The finger held the item at some point inside it - the middle of an icon, wherever on a
     * widget it was picked up - so the view starts with that point under the finger and travels
     * from there, settling from a slightly lifted size to its own.
     */
    private void glide(Item item, float dropX, float dropY, int grabCellX, int grabCellY) {
        View view = viewForItem(item);
        if (view == null || view.getWidth() == 0 || !com.zuxos.desktopplus.core.Cfg.animations()) {
            return;
        }
        float heldX = item.spanX > 1 || item.spanY > 1
                ? (grabCellX + 0.5f) * mCellW : view.getWidth() / 2f;
        float heldY = item.spanX > 1 || item.spanY > 1
                ? (grabCellY + 0.5f) * mCellH : view.getHeight() / 2f;
        view.animate().cancel();
        view.setTranslationX(dropX - (view.getLeft() + heldX));
        view.setTranslationY(dropY - (view.getTop() + heldY));
        view.setScaleX(1.08f);
        view.setScaleY(1.08f);
        view.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f)
                .setDuration(220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f))
                .start();
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
