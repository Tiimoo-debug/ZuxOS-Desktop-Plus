package com.zuxos.desktopplus.core;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.widget.FrameLayout;

/**
 * A pane of glass: what is behind it, blurred, with the light caught at its edge.
 *
 * <p>The blur is the system's own and is cropped to this view - see {@link Blur} - so nothing
 * outside the pane is touched. The rest is drawn here, over the children, and is what turns a
 * blurred rectangle into something that reads as glass: a bright hairline along the top where the
 * light catches, a soft sheen falling away from it, a darker line along the bottom where the pane
 * is thick, and a faint warm/cool split at the very edge - the trace of light bending through a
 * lens, which is the part you actually notice.
 *
 * <p>There is no colour in it worth the name. A tint is how you hide a blur that is not really
 * there; with a real one the glass should be the scene behind it, softened.
 */
public class GlassSurface extends FrameLayout {

    /** How far the compositor blurs. Past this it costs more and looks the same. */
    private static final int BLUR_RADIUS_DP = 40;

    private final Paint mEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mSheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mWarm = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mCool = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float mRadius;
    private final int mTint;
    private boolean mBlurred;

    public GlassSurface(Context ctx, float radiusPx, int tint) {
        super(ctx);
        mRadius = radiusPx;
        mTint = tint;
        setWillNotDraw(false);

        mEdge.setStyle(Paint.Style.STROKE);
        mEdge.setStrokeWidth(Math.max(1f, Ui.dp(ctx, 1)));

        mSheen.setStyle(Paint.Style.STROKE);
        mSheen.setStrokeWidth(Math.max(1f, Ui.dp(ctx, 1.5f)));

        // The two halves of the rim. Glass splits light; a single white line does not.
        mWarm.setStyle(Paint.Style.STROKE);
        mWarm.setStrokeWidth(Math.max(1f, Ui.dp(ctx, 0.75f)));
        mWarm.setColor(0x33FFE8C8);
        mCool.setStyle(Paint.Style.STROKE);
        mCool.setStrokeWidth(Math.max(1f, Ui.dp(ctx, 0.75f)));
        mCool.setColor(0x2ACFE4FF);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        applyBackdrop();
    }

    /**
     * Asks the window for a blur behind this pane.
     *
     * <p>From here rather than the constructor: the drawable is made by the view root, and a view
     * that is not in a window yet has no view root to ask.
     */
    private void applyBackdrop() {
        if (mBlurred) {
            return;
        }
        Drawable backdrop = Blur.backdrop(this, Ui.dp(getContext(), BLUR_RADIUS_DP),
                mRadius, mTint);
        if (backdrop != null) {
            setBackground(backdrop);
            mBlurred = true;
            return;
        }
        // No blur on this build: the layered translucency is the honest second best.
        setBackground(Glass.pill(getContext(), (int) mRadius, fallbackTint()));
    }

    /** With no blur behind it, a pane this faint would be a smear, so it darkens to stay legible. */
    private int fallbackTint() {
        return 0xB0202024;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (h <= 0) {
            return;
        }
        mSheen.setShader(new LinearGradient(0, 0, 0, h,
                new int[]{0x4DFFFFFF, 0x0DFFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.35f, 1f}, Shader.TileMode.CLAMP));
        mEdge.setShader(new LinearGradient(0, 0, 0, h,
                new int[]{0x59FFFFFF, 0x1AFFFFFF, 0x40000000},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override
    public void onDrawForeground(Canvas canvas) {
        super.onDrawForeground(canvas);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        float inset = mEdge.getStrokeWidth() / 2f;
        RectF rect = new RectF(inset, inset, w - inset, h - inset);

        // The rim, top-lit and bottom-weighted.
        canvas.drawRoundRect(rect, mRadius, mRadius, mEdge);

        // Just inside it, the sheen: the light that has entered the glass and is falling away.
        float in = mSheen.getStrokeWidth();
        RectF inner = new RectF(rect.left + in, rect.top + in, rect.right - in, rect.bottom - in);
        canvas.drawRoundRect(inner, Math.max(0f, mRadius - in), Math.max(0f, mRadius - in),
                mSheen);

        // And the split: warm above, cool below, a pixel apart. This is the refraction you can
        // actually see - light through an edge does not come out one colour.
        float split = Math.max(1f, in * 0.6f);
        RectF warm = new RectF(rect.left + split, rect.top + split,
                rect.right - split, rect.bottom - split);
        canvas.save();
        canvas.clipRect(0, 0, w, h / 2f);
        canvas.drawRoundRect(warm, mRadius, mRadius, mWarm);
        canvas.restore();
        canvas.save();
        canvas.clipRect(0, h / 2f, w, h);
        canvas.drawRoundRect(warm, mRadius, mRadius, mCool);
        canvas.restore();
    }
}
