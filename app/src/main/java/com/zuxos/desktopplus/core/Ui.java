package com.zuxos.desktopplus.core;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.widget.TextView;

/**
 * Programmatic UI helpers.
 *
 * <p>All module UI inside the launcher is built in code on purpose: our own resources are not
 * on the host's resource path, so anything inflated from XML there would resolve against the
 * launcher's resources and blow up.
 */
public final class Ui {

    public static final int COLOR_ACCENT = 0xFF4C8DFF;
    public static final int COLOR_TEXT = 0xFFFFFFFF;
    public static final int COLOR_TEXT_DIM = 0xB3FFFFFF;
    public static final int COLOR_DROP_HINT = 0x334C8DFF;

    private Ui() {
    }

    public static int dp(Context ctx, float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                ctx.getResources().getDisplayMetrics()));
    }

    public static GradientDrawable roundRect(int color, int radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(color);
        d.setCornerRadius(radiusPx);
        return d;
    }

    public static RippleDrawable ripple(Context ctx, int color, int radiusPx) {
        return new RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), roundRect(color, radiusPx), null);
    }

    /**
     * The display a view is on: its window's, or before it has one, the display its context is
     * for - rather than assuming the tablet. 0 when neither can be read.
     */
    public static int displayOf(android.view.View view) {
        try {
            if (view.getDisplay() != null) {
                return view.getDisplay().getDisplayId();
            }
            android.view.Display d = view.getContext().getDisplay();
            return d != null ? d.getDisplayId() : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    public static void styleLabel(TextView tv, float spSize, boolean shadow) {
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, spSize);
        tv.setTextColor(COLOR_TEXT);
        tv.setMaxLines(2);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tv.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        if (shadow) {
            tv.setShadowLayer(4f, 0f, 1f, Color.argb(190, 0, 0, 0));
        }
    }
}
