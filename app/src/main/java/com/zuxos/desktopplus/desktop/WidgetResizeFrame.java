package com.zuxos.desktopplus.desktop;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import com.zuxos.desktopplus.core.Ui;
import com.zuxos.desktopplus.model.Item;

/**
 * The frame drawn around a widget while it is being resized.
 *
 * <p>Drag an edge handle to change the widget's span, or the middle to move it. Sizes snap to
 * grid cells and a move that would overlap something is simply not applied, so the widget can
 * never end up on top of another item.
 */
public class WidgetResizeFrame extends View {

    public interface Callback {
        void onFrameChanged(Item item, int cellX, int cellY, int spanX, int spanY);

        void onFrameReleased(Item item);
    }

    private static final int HANDLE_NONE = -1;
    private static final int HANDLE_LEFT = 0;
    private static final int HANDLE_TOP = 1;
    private static final int HANDLE_RIGHT = 2;
    private static final int HANDLE_BOTTOM = 3;
    private static final int HANDLE_BODY = 4;

    private final CellLayoutView mGrid;
    private final Item mItem;
    private final View mTarget;
    private final Callback mCallback;

    private final Paint mBorder = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mHandle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int mPad;
    private final float mHandleRadius;

    private int mActiveHandle = HANDLE_NONE;
    private float mDownRawX;
    private float mDownRawY;
    private int mBaseCellX;
    private int mBaseCellY;
    private int mBaseSpanX;
    private int mBaseSpanY;

    public WidgetResizeFrame(Context ctx, CellLayoutView grid, Item item, View target,
            Callback callback) {
        super(ctx);
        mGrid = grid;
        mItem = item;
        mTarget = target;
        mCallback = callback;
        mPad = Ui.dp(ctx, 18);
        mHandleRadius = Ui.dp(ctx, 8);

        mBorder.setStyle(Paint.Style.STROKE);
        mBorder.setStrokeWidth(Ui.dp(ctx, 2));
        mBorder.setColor(Ui.COLOR_ACCENT);
        mHandle.setColor(Ui.COLOR_ACCENT);
    }

    public Item getItem() {
        return mItem;
    }

    /** Positions the frame over the widget's current cells. */
    public void syncToItem() {
        int cellW = mGrid.getCellWidth();
        int cellH = mGrid.getCellHeight();
        int left = mGrid.getPaddingLeft() + mItem.x * cellW - mPad;
        int top = mGrid.getPaddingTop() + mItem.y * cellH - mPad;
        int width = mItem.spanX * cellW + mPad * 2;
        int height = mItem.spanY * cellH + mPad * 2;

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) getLayoutParams();
        if (lp == null) {
            lp = new FrameLayout.LayoutParams(width, height);
            setLayoutParams(lp);
        }
        lp.width = width;
        lp.height = height;
        lp.leftMargin = left;
        lp.topMargin = top;
        lp.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
        setLayoutParams(lp);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        RectF rect = new RectF(mPad, mPad, getWidth() - mPad, getHeight() - mPad);
        float radius = Ui.dp(getContext(), 12);
        canvas.drawRoundRect(rect, radius, radius, mBorder);
        canvas.drawCircle(rect.left, rect.centerY(), mHandleRadius, mHandle);
        canvas.drawCircle(rect.right, rect.centerY(), mHandleRadius, mHandle);
        canvas.drawCircle(rect.centerX(), rect.top, mHandleRadius, mHandle);
        canvas.drawCircle(rect.centerX(), rect.bottom, mHandleRadius, mHandle);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mActiveHandle = handleAt(event.getX(), event.getY());
                mDownRawX = event.getRawX();
                mDownRawY = event.getRawY();
                mBaseCellX = mItem.x;
                mBaseCellY = mItem.y;
                mBaseSpanX = mItem.spanX;
                mBaseSpanY = mItem.spanY;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (mActiveHandle != HANDLE_NONE) {
                    apply(event.getRawX(), event.getRawY());
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (mActiveHandle != HANDLE_NONE) {
                    mActiveHandle = HANDLE_NONE;
                    mCallback.onFrameReleased(mItem);
                }
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private int handleAt(float x, float y) {
        float grab = mPad * 2f;
        if (x < grab) {
            return HANDLE_LEFT;
        }
        if (x > getWidth() - grab) {
            return HANDLE_RIGHT;
        }
        if (y < grab) {
            return HANDLE_TOP;
        }
        if (y > getHeight() - grab) {
            return HANDLE_BOTTOM;
        }
        return HANDLE_BODY;
    }

    private void apply(float rawX, float rawY) {
        int cellW = Math.max(1, mGrid.getCellWidth());
        int cellH = Math.max(1, mGrid.getCellHeight());
        int dx = Math.round((rawX - mDownRawX) / cellW);
        int dy = Math.round((rawY - mDownRawY) / cellH);

        int cellX = mBaseCellX;
        int cellY = mBaseCellY;
        int spanX = mBaseSpanX;
        int spanY = mBaseSpanY;

        switch (mActiveHandle) {
            case HANDLE_LEFT:
                cellX = mBaseCellX + dx;
                spanX = mBaseSpanX - dx;
                break;
            case HANDLE_RIGHT:
                spanX = mBaseSpanX + dx;
                break;
            case HANDLE_TOP:
                cellY = mBaseCellY + dy;
                spanY = mBaseSpanY - dy;
                break;
            case HANDLE_BOTTOM:
                spanY = mBaseSpanY + dy;
                break;
            default:
                cellX = mBaseCellX + dx;
                cellY = mBaseCellY + dy;
                break;
        }

        if (spanX < 1 || spanY < 1 || cellX < 0 || cellY < 0
                || cellX + spanX > mGrid.getCols() || cellY + spanY > mGrid.getRows()) {
            return;
        }
        if (!mGrid.isFree(cellX, cellY, spanX, spanY, mTarget)) {
            return;
        }
        if (cellX == mItem.x && cellY == mItem.y && spanX == mItem.spanX && spanY == mItem.spanY) {
            return;
        }
        mCallback.onFrameChanged(mItem, cellX, cellY, spanX, spanY);
        syncToItem();
    }
}
