package com.zuxos.desktopplus.desktop;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import java.util.List;

/** A folder icon: rounded translucent tile with up to four preview icons inside. */
public class FolderIconDrawable extends Drawable {

    private final List<Drawable> mPreviews;
    private final Paint mBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int mSize;

    public FolderIconDrawable(List<Drawable> previews, int sizePx) {
        mPreviews = previews;
        mSize = sizePx;
        mBg.setColor(0x66FFFFFF);
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        float r = b.width() * 0.22f;
        canvas.drawRoundRect(new RectF(b), r, r, mBg);

        int count = Math.min(4, mPreviews.size());
        if (count == 0) {
            return;
        }
        float pad = b.width() * 0.12f;
        float inner = b.width() - 2 * pad;
        float cell = inner / 2f;
        float gap = cell * 0.12f;
        for (int i = 0; i < count; i++) {
            Drawable d = mPreviews.get(i);
            if (d == null) {
                continue;
            }
            int col = i % 2;
            int row = i / 2;
            int left = (int) (b.left + pad + col * cell + gap / 2);
            int top = (int) (b.top + pad + row * cell + gap / 2);
            int size = (int) (cell - gap);
            d.setBounds(left, top, left + size, top + size);
            d.draw(canvas);
        }
    }

    @Override
    public int getIntrinsicWidth() {
        return mSize;
    }

    @Override
    public int getIntrinsicHeight() {
        return mSize;
    }

    @Override
    public void setAlpha(int alpha) {
        mBg.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        mBg.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
