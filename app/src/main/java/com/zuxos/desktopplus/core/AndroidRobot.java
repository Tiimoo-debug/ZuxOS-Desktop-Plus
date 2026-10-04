package com.zuxos.desktopplus.core;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * The Android head, as a start button: the green half-dome with its antennae and eyes - the mark
 * Android itself uses since 2019 - with no body, so it reads as a logo rather than as one more
 * app on the bar.
 *
 * <p>Built from shapes on a 24-unit grid rather than shipped as an image - the module has no
 * resources of its own inside the launcher's process - and scaled to whatever bounds it is
 * given, centred, keeping its proportions.
 */
public final class AndroidRobot extends Drawable {

    /** Android's own green. */
    public static final int GREEN = 0xFF3DDC84;

    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mEye = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float mScale = 1f;
    private float mLeft;
    private float mTop;

    public AndroidRobot() {
        mFill.setColor(GREEN);
        mFill.setStyle(Paint.Style.FILL);
        mLine.setColor(GREEN);
        mLine.setStyle(Paint.Style.STROKE);
        mLine.setStrokeCap(Paint.Cap.ROUND);
        mEye.setColor(0xFFFFFFFF);
        mEye.setStyle(Paint.Style.FILL);
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        float size = Math.min(bounds.width(), bounds.height());
        mScale = size / 24f;
        mLeft = bounds.left + (bounds.width() - size) / 2f;
        mTop = bounds.top + (bounds.height() - size) / 2f;
    }

    @Override
    public void draw(Canvas canvas) {
        int saved = canvas.save();
        canvas.translate(mLeft, mTop);
        canvas.scale(mScale, mScale);
        // The scale is on the canvas, so the antennae are stroked in grid units.
        mLine.setStrokeWidth(1.4f);

        // Antennae, angled out from the top of the dome.
        canvas.drawLine(7.2f, 9.6f, 5.0f, 5.8f, mLine);
        canvas.drawLine(16.8f, 9.6f, 19.0f, 5.8f, mLine);
        // The dome: the top half of a disc, flat side down, filling the width and centred on
        // the grid so the logo sits in the middle of the button.
        canvas.drawArc(new RectF(1.5f, 7.7f, 22.5f, 28.7f), 180f, 180f, true, mFill);
        // Eyes.
        canvas.drawCircle(8.0f, 13.8f, 1.25f, mEye);
        canvas.drawCircle(16.0f, 13.8f, 1.25f, mEye);

        canvas.restoreToCount(saved);
    }

    @Override
    public void setAlpha(int alpha) {
        mFill.setAlpha(alpha);
        mLine.setAlpha(alpha);
        mEye.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter filter) {
        // Deliberately ignored: the launcher tints its button's icon to match the bar, and the
        // robot is green whatever the bar is.
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
