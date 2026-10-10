package com.zuxos.desktopplus.core.theme;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.StateListDrawable;

import com.zuxos.desktopplus.core.Ui;
import com.zuxos.desktopplus.logic.BevelMath;

/**
 * Windows 98's box: a flat face and two edges a side, light where the light falls (top, left) and
 * shadow where it does not. Raised for buttons and panels, sunken for fields and pressed buttons,
 * shallow - one edge, the status bar's - for the tray. Square, whole pixels, no anti-aliasing:
 * one edge is one density-independent pixel. It asks for no padding: as a background it would
 * otherwise replace the padding its view already has.
 */
public final class Bevel extends Drawable {

    public static final int RAISED = 0;
    public static final int SUNKEN = 1;
    public static final int SHALLOW = 2;

    private final int mStyle;
    private final int mFace;
    private final int mLight;
    private final int mShadow;
    private final int mUnit;
    private final Paint mPaint = new Paint();
    private int mWidth = -1;
    private int mHeight = -1;

    public Bevel(Context ctx, int style, int face) {
        mStyle = style;
        mFace = face;
        mLight = BevelMath.light(face);
        mShadow = BevelMath.shadow(face);
        mUnit = Math.max(1, Ui.dp(ctx, 1));
        mPaint.setAntiAlias(false);
        mPaint.setStyle(Paint.Style.FILL);
    }

    public static Bevel raised(Context ctx) {
        return new Bevel(ctx, RAISED, BevelMath.FACE);
    }

    public static Bevel sunken(Context ctx) {
        return new Bevel(ctx, SUNKEN, BevelMath.FACE);
    }

    public static Bevel shallow(Context ctx) {
        return new Bevel(ctx, SHALLOW, BevelMath.FACE);
    }

    /**
     * A size of its own, for where the box is the whole of something - a slider's thumb - rather
     * than a background that takes its view's size.
     */
    public Bevel size(int width, int height) {
        mWidth = width;
        mHeight = height;
        return this;
    }

    /** A button: raised, sunken while pressed, selected or activated. */
    public static Drawable button(Context ctx) {
        StateListDrawable states = new StateListDrawable();
        Bevel down = sunken(ctx);
        states.addState(new int[]{android.R.attr.state_pressed}, down);
        states.addState(new int[]{android.R.attr.state_selected}, down);
        states.addState(new int[]{android.R.attr.state_activated}, down);
        states.addState(new int[0], raised(ctx));
        return states;
    }

    /**
     * An app's button on the bar, as Windows 98's taskbar has them: flat while the app is closed,
     * raised while it is open (selected), sunken while it is in front (activated) or pressed.
     */
    public static Drawable taskButton(Context ctx) {
        StateListDrawable states = new StateListDrawable();
        Bevel down = sunken(ctx);
        states.addState(new int[]{android.R.attr.state_pressed}, down);
        states.addState(new int[]{android.R.attr.state_activated}, down);
        states.addState(new int[]{android.R.attr.state_selected}, raised(ctx));
        return states;
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        if (b.isEmpty()) {
            return;
        }
        fill(canvas, b.left, b.top, b.right, b.bottom, mFace);
        int u = mUnit;
        switch (mStyle) {
            case RAISED:
                edges(canvas, b, 0, BevelMath.HIGHLIGHT, BevelMath.DARK_SHADOW);
                edges(canvas, b, u, mLight, mShadow);
                break;
            case SUNKEN:
                edges(canvas, b, 0, mShadow, BevelMath.HIGHLIGHT);
                edges(canvas, b, u, BevelMath.DARK_SHADOW, mLight);
                break;
            default:
                edges(canvas, b, 0, mShadow, BevelMath.HIGHLIGHT);
                break;
        }
    }

    /**
     * One ring of edges, {@code inset} in from the bounds: {@code topLeft} along the top and left,
     * {@code bottomRight} along the bottom and right, the light ones drawn last at the corners
     * they share so the box reads as lit from the top left.
     */
    private void edges(Canvas canvas, Rect b, int inset, int topLeft, int bottomRight) {
        int u = mUnit;
        int l = b.left + inset;
        int t = b.top + inset;
        int r = b.right - inset;
        int bt = b.bottom - inset;
        fill(canvas, l, bt - u, r, bt, bottomRight);
        fill(canvas, r - u, t, r, bt, bottomRight);
        fill(canvas, l, t, r - u, t + u, topLeft);
        fill(canvas, l, t, l + u, bt - u, topLeft);
    }

    private void fill(Canvas canvas, int l, int t, int r, int b, int color) {
        mPaint.setColor(color);
        canvas.drawRect(l, t, r, b, mPaint);
    }

    @Override
    public int getIntrinsicWidth() {
        return mWidth;
    }

    @Override
    public int getIntrinsicHeight() {
        return mHeight;
    }

    @Override
    public void setAlpha(int alpha) {
        // Opaque by design: a Windows 98 box has no translucency to fade.
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        // Its colours are the theme's; a tint from the view would only muddy the bevel.
    }

    @Override
    public int getOpacity() {
        return PixelFormat.OPAQUE;
    }
}
