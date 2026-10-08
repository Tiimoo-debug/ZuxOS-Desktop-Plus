package com.zuxos.desktopplus.core;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
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
    private static final long BLINK_MS = 220L;
    /** The pause between the two halves of a double blink. */
    private static final long DOUBLE_GAP_MS = 90L;
    /** Whether the blink under way is followed straight away by a second. */
    private boolean mDouble;
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
        // Eyes, which blink - and grow while the drawer is open.
        float open = openness();
        float r = 1.25f * (1f + (WIDE_SCALE - 1f) * mWide);
        canvas.drawOval(new RectF(8.0f - r, 13.8f - r * open, 8.0f + r, 13.8f + r * open), mEye);
        canvas.drawOval(new RectF(16.0f - r, 13.8f - r * open, 16.0f + r, 13.8f + r * open),
                mEye);

        canvas.restoreToCount(saved);
        scheduleNext();
    }

    /**
     * How open the eyes are, 1 to nearly shut.
     *
     * <p>Lids close quickly and open a little slower, on eased curves - the way an eye actually
     * blinks - rather than the straight V a linear blink makes. About one blink in five is a
     * double blink, which is what keeps it reading as alive rather than as a timer.
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
            if (mDouble) {
                mDouble = false;
                mBlinkAt = now + DOUBLE_GAP_MS;
            } else {
                mDouble = mRandom.nextInt(5) == 0;
                mBlinkAt = now + nextGap();
            }
            return 1f;
        }
        // Closing takes the first 40% of the blink, opening the rest.
        float shut;
        if (t < 0.4f) {
            float x = t / 0.4f;
            shut = x * x * (3f - 2f * x);
        } else {
            float x = (t - 0.4f) / 0.6f;
            shut = 1f - x * x * (3f - 2f * x);
        }
        return Math.max(0.1f, 1f - shut);
    }

    /** How much bigger the eyes are while the drawer is open. */
    private static final float WIDE_SCALE = 1.45f;
    /** 0 = normal eyes, 1 = wide; overshoots a touch on the way, which is the spring. */
    private float mWide;
    private boolean mWideTarget;
    private android.animation.ValueAnimator mWideAnim;

    /**
     * Eyes wide while the app drawer is open, back to normal when it closes.
     *
     * <p>On the module's spring, from wherever they are now, so opening and closing the drawer
     * quickly in a row turns the motion round smoothly instead of jumping.
     */
    public void setWide(boolean wide) {
        if (wide == mWideTarget) {
            return;
        }
        mWideTarget = wide;
        if (mWideAnim != null) {
            mWideAnim.cancel();
        }
        mWideAnim = android.animation.ValueAnimator.ofFloat(mWide, wide ? 1f : 0f);
        mWideAnim.setDuration(Motion.SPRING_MS);
        mWideAnim.setInterpolator(Motion.SPRING);
        mWideAnim.addUpdateListener(a -> {
            mWide = (float) a.getAnimatedValue();
            invalidateSelf();
        });
        mWideAnim.start();
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
