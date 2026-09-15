package com.zuxos.desktopplus.drawer;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/** Flow layout with fixed-width cells: as many columns as fit, wrapping onto new rows. */
public class WrapGrid extends ViewGroup {

    private int mCellWidth;
    private int mCellHeight;
    private int mColumns = 1;

    public WrapGrid(Context ctx, int cellWidth, int cellHeight) {
        super(ctx);
        mCellWidth = Math.max(1, cellWidth);
        mCellHeight = Math.max(1, cellHeight);
    }

    public void setCellSize(int width, int height) {
        mCellWidth = Math.max(1, width);
        mCellHeight = Math.max(1, height);
        requestLayout();
    }

    public int getColumns() {
        return mColumns;
    }

    public int getCellWidth() {
        return mCellWidth;
    }

    public int getCellHeight() {
        return mCellHeight;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int usable = Math.max(1, width - getPaddingLeft() - getPaddingRight());
        mColumns = Math.max(1, usable / mCellWidth);
        int actualCell = usable / mColumns;

        int count = getChildCount();
        for (int i = 0; i < count; i++) {
            getChildAt(i).measure(
                    MeasureSpec.makeMeasureSpec(actualCell, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(mCellHeight, MeasureSpec.EXACTLY));
        }
        int rows = (int) Math.ceil(count / (float) mColumns);
        int height = getPaddingTop() + getPaddingBottom() + rows * mCellHeight;
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int usable = Math.max(1, getWidth() - getPaddingLeft() - getPaddingRight());
        int cell = usable / mColumns;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            int col = i % mColumns;
            int row = i / mColumns;
            int left = getPaddingLeft() + col * cell;
            int top = getPaddingTop() + row * mCellHeight;
            child.layout(left, top, left + cell, top + mCellHeight);
        }
    }

    /** Index of the slot at (x, y), clamped to the number of children. */
    public int indexAt(float x, float y) {
        int usable = Math.max(1, getWidth() - getPaddingLeft() - getPaddingRight());
        int cell = usable / mColumns;
        int col = (int) ((x - getPaddingLeft()) / Math.max(1, cell));
        int row = (int) ((y - getPaddingTop()) / Math.max(1, mCellHeight));
        col = Math.max(0, Math.min(col, mColumns - 1));
        row = Math.max(0, row);
        return Math.max(0, Math.min(row * mColumns + col, getChildCount()));
    }
}
