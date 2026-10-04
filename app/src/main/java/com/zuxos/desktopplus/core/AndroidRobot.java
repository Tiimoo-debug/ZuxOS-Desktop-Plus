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
 * app on the bar. It blinks every few seconds.
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

    /** How long one blink takes, shut and open again. */
    private static final long BLINK_MS = 180L;
    private final java.util.Random mRandom = new java.util.Random();
    /** When the next blink starts, on the uptime clock {@link #scheduleSelf} uses; 0 = not set. */
    private long mBlinkAt;
    private final Runnable mTick = this::invalidateSelf;

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
        // Eyes, which blink.
        float open = openness();
        float r = 1.25f;
        canvas.drawOval(new RectF(8.0f - r, 13.8f - r * open, 8.0f + r, 13.8f + r * open), mEye);
        canvas.drawOval(new RectF(16.0f - r, 13.8f - r * open, 16.0f + r, 13.8f + r * open),
                mEye);

        canvas.restoreToCount(saved);
        scheduleNext();
    }

    /**
     * How open the eyes are, 1 to nearly shut: they close and open again over {@link #BLINK_MS},
     * every few seconds.
     */
    private float openness() {
        long now = android.os.SystemClock.uptimeMillis();
        if (mBlinkAt == 0) {
            mBlinkAt = now + nextGap();
        }
        if (now < mBlinkAt) {
            return 1f;
        }
        float t = (now - mBlinkAt) / (float) BLINK_MS;
        if (t >= 1f) {
            mBlinkAt = now + nextGap();
            return 1f;
        }
        return Math.max(0.12f, Math.abs(1f - 2f * t));
    }

    /** Somewhere between 3.5 and 6.5 seconds: a fixed rhythm looks like a machine. */
    private long nextGap() {
        return 3500L + mRandom.nextInt(3000);
    }

    /**
     * Asks to be drawn again when something will have changed: every frame during a blink, and
     * otherwise not until the next one starts. Only while visible and attached to a view, so a
     * hidden bar has nothing ticking.
     */
    private void scheduleNext() {
        if (getCallback() == null || !isVisible()) {
            return;
        }
        long now = android.os.SystemClock.uptimeMillis();
        unscheduleSelf(mTick);
        scheduleSelf(mTick, now >= mBlinkAt ? now + 16L : mBlinkAt);
    }

    @Override
    public boolean setVisible(boolean visible, boolean restart) {
        boolean changed = super.setVisible(visible, restart);
        if (!visible) {
            unscheduleSelf(mTick);
        } else if (changed) {
            invalidateSelf();
        }
        return changed;
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
