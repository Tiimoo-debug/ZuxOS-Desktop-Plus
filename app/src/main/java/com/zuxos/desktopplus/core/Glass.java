package com.zuxos.desktopplus.core;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Build;
import android.view.WindowManager;

/**
 * The module's glass look: a translucent pane, a light edge, and a highlight running off the top.
 *
 * <p>Android only offers real backdrop blur to something that owns a window, so the folder window
 * gets blurred properly while in-activity panels use layered translucency that reads the same way
 * over a wallpaper.
 */
public final class Glass {

    private Glass() {
    }

    public static Drawable panel(Context ctx, int radiusPx) {
        return build(ctx, radiusPx, radiusPx, 0xB0202024);
    }

    /** Sheet with only the top corners rounded, for bottom-anchored panels. */
    public static Drawable sheet(Context ctx, int radiusPx) {
        return build(ctx, radiusPx, 0, 0xC01A1A1E);
    }

    public static Drawable pill(Context ctx, int radiusPx, int tint) {
        return build(ctx, radiusPx, radiusPx, tint);
    }

    private static Drawable build(Context ctx, int topRadius, int bottomRadius, int tint) {
        float[] radii = {
                topRadius, topRadius, topRadius, topRadius,
                bottomRadius, bottomRadius, bottomRadius, bottomRadius};

        GradientDrawable base = new GradientDrawable();
        base.setShape(GradientDrawable.RECTANGLE);
        base.setColor(tint);
        base.setCornerRadii(radii);
        base.setStroke(Ui.dp(ctx, 1), 0x33FFFFFF);

        GradientDrawable sheen = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x26FFFFFF, 0x05FFFFFF, Color.TRANSPARENT});
        sheen.setShape(GradientDrawable.RECTANGLE);
        sheen.setCornerRadii(radii);

        return new LayerDrawable(new Drawable[]{base, sheen});
    }

    /** Real blur behind a window we own. Silently does nothing where it is unsupported. */
    public static void blurBehind(Context ctx, WindowManager.LayoutParams lp, int radiusDp) {
        if (!Cfg.glass() || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return;
        }
        try {
            lp.flags |= WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
            lp.setBlurBehindRadius(Ui.dp(ctx, radiusDp));
        } catch (Throwable t) {
            L.d("blur behind unavailable: " + t);
        }
    }
}
