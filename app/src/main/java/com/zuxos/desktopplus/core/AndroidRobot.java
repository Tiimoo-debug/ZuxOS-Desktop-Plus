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
 * The Android robot, drawn: head with antennae and eyes, body, arms and legs, in Android green.
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
        mLine.setStrokeWidth(1.1f);

        // Antennae.
        canvas.drawLine(8.6f, 3.2f, 7.4f, 1.2f, mLine);
        canvas.drawLine(15.4f, 3.2f, 16.6f, 1.2f, mLine);
        // Head: the top half of a disc, with a sliver of gap above the body.
        canvas.drawArc(new RectF(5.5f, 2.6f, 18.5f, 15.6f), 180f, 180f, true, mFill);
        // Eyes.
        canvas.drawCircle(9.4f, 6.4f, 0.8f, mEye);
        canvas.drawCircle(14.6f, 6.4f, 0.8f, mEye);
        // Body, rounded at the bottom.
        Path body = new Path();
        body.addRoundRect(new RectF(5.5f, 9.8f, 18.5f, 19.4f),
                new float[]{0, 0, 0, 0, 2.2f, 2.2f, 2.2f, 2.2f}, Path.Direction.CW);
        canvas.drawPath(body, mFill);
        // Arms.
        canvas.drawRoundRect(new RectF(2.2f, 10.2f, 4.6f, 17f), 1.2f, 1.2f, mFill);
        canvas.drawRoundRect(new RectF(19.4f, 10.2f, 21.8f, 17f), 1.2f, 1.2f, mFill);
        // Legs.
        canvas.drawRoundRect(new RectF(8.2f, 17.5f, 10.6f, 23f), 1.2f, 1.2f, mFill);
        canvas.drawRoundRect(new RectF(13.4f, 17.5f, 15.8f, 23f), 1.2f, 1.2f, mFill);

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
