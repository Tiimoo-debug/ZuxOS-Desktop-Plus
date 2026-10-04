package com.zuxos.desktopplus.core;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * One look for every menu: a pane of glass with a row per entry, icon on the left.
 *
 * <p>The desktop's menus and the taskbar's used to be two different things - the system's popup
 * on one, glass on the other - so the same app's menu looked different depending on where you
 * held it. Both now build their rows here.
 */
public final class MenuRows {

    /** Corner radius of a menu, matched to the folder panels. */
    private static final float RADIUS_DP = 16;

    private MenuRows() {
    }

    /** The pane, holding a vertical list the rows go into - see {@link #body}. */
    public static GlassSurface pane(Context ctx) {
        GlassSurface glass = new GlassSurface(ctx, Ui.dp(ctx, RADIUS_DP), Tone.panelTint(ctx));
        LinearLayout body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        int padV = Ui.dp(ctx, 6);
        body.setPadding(0, padV, 0, padV);
        glass.addView(body, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        return glass;
    }

    public static LinearLayout body(GlassSurface pane) {
        // Once the pane is on screen its glass is child 0, under the rows.
        for (int i = 0; i < pane.getChildCount(); i++) {
            if (pane.getChildAt(i) instanceof LinearLayout) {
                return (LinearLayout) pane.getChildAt(i);
            }
        }
        return null;
    }

    /**
     * A row.
     *
     * @param icon   drawn at the left; a line glyph takes the text's colour, an app's own icon
     *               (a shortcut's, say) is drawn as it is
     * @param indent whether to leave the icon's room when there is no icon, so that a menu where
     *               some rows have one keeps every title lined up
     */
    public static View row(Context ctx, String title, Drawable icon, boolean indent,
            boolean enabled, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int padH = Ui.dp(ctx, 14);
        int padV = Ui.dp(ctx, 9);
        row.setPadding(padH, padV, Ui.dp(ctx, 20), padV);
        row.setMinimumWidth(Ui.dp(ctx, 200));
        int color = enabled ? Ui.COLOR_TEXT : Ui.COLOR_TEXT_DIM;

        int iconSize = Ui.dp(ctx, 20);
        if (icon != null || indent) {
            ImageView image = new ImageView(ctx);
            if (icon != null) {
                if (Glyphs.isGlyph(icon)) {
                    icon.setTint(color);
                }
                image.setImageDrawable(icon);
                image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            }
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(iconSize, iconSize);
            ilp.rightMargin = Ui.dp(ctx, 12);
            row.addView(image, ilp);
        }

        TextView text = new TextView(ctx);
        text.setText(title);
        text.setTextColor(color);
        text.setTextSize(14);
        text.setSingleLine(true);
        // An app's own shortcut titles come through here and some of them are sentences. Left to
        // wrap, the pane grows wider than the display and the clamp that keeps it on screen has
        // nothing left to work with.
        text.setEllipsize(TextUtils.TruncateAt.END);
        text.setMaxWidth(Ui.dp(ctx, 300));
        row.addView(text, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        if (enabled) {
            row.setBackground(Ui.ripple(ctx, 0x00000000, 0));
            row.setOnClickListener(onClick);
        }
        return row;
    }

    /** The menu coming up: a short grow and fade from where it was asked for. */
    public static void popIn(View pane, boolean fromBelow) {
        popIn(pane, fromBelow, null);
    }

    /**
     * The same, then {@code settled} once the pane is at rest - which is when a pane of liquid
     * glass can capture what is behind it.
     */
    public static void popIn(View pane, boolean fromBelow, Runnable settled) {
        if (!Cfg.animations()) {
            if (settled != null) {
                pane.post(settled);
            }
            return;
        }
        pane.setAlpha(0f);
        pane.setScaleX(0.94f);
        pane.setScaleY(0.94f);
        pane.post(() -> {
            pane.setPivotX(pane.getWidth() / 2f);
            pane.setPivotY(fromBelow ? pane.getHeight() : 0f);
            android.view.ViewPropertyAnimator anim = pane.animate().alpha(1f).scaleX(1f)
                    .scaleY(1f).setDuration(Motion.SPRING_MS).setInterpolator(Motion.SPRING);
            if (settled != null) {
                anim.withEndAction(settled);
            }
            anim.start();
        });
    }
}
