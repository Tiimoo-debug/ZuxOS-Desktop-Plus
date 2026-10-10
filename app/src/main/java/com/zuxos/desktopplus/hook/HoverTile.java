package com.zuxos.desktopplus.hook;

import android.content.Context;
import android.view.MotionEvent;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.zuxos.desktopplus.core.Ui;
import com.zuxos.desktopplus.core.motion.Motion;
import com.zuxos.desktopplus.core.theme.RetroParts;
import com.zuxos.desktopplus.core.theme.Theme;

/**
 * A tile under the pointer: a soft highlight comes up behind it and its picture grows a
 * little; the icon, name and X stay exactly where they are.
 *
 * <p>Hover is read from what reaches the tile as a whole, not from the tile's own
 * enter/exit: those fire every time the pointer crosses the tile's X or icon, and the tile
 * used to grow and shrink with each crossing - taking the icon and X with it.
 *
 * <p>In Retro nothing glows or grows: the tile's owner is told, and lights the tile's title bar
 * as Windows 98 lit the active window's.
 */
public final class HoverTile extends LinearLayout {

    /** Told when the pointer comes onto the tile and when it leaves. */
    public interface OnHover {
        void hovered(boolean on);
    }

    public ImageView mThumb;
    private boolean mHovered;
    private final Theme mTheme;
    private final android.graphics.drawable.GradientDrawable mGlow;
    private android.animation.ValueAnimator mFade;
    private OnHover mOnHover;

    public HoverTile(Context ctx, Theme theme) {
        super(ctx);
        mTheme = theme;
        mGlow = Ui.roundRect(0x1FFFFFFF, Ui.dp(ctx, 16));
        mGlow.setAlpha(0);
        if (!theme.retro()) {
            setBackground(mGlow);
        }
    }

    public void setOnHover(OnHover onHover) {
        mOnHover = onHover;
    }

    /**
     * Retro's title bar on this tile: grey with grey text, and navy with white text while the
     * pointer is on the tile - the active window's, as Windows 98 drew it.
     */
    public void retroTitle(android.view.View bar, android.widget.TextView name) {
        bar.setBackgroundColor(RetroParts.INACTIVE_TITLE);
        name.setTextColor(RetroParts.INACTIVE_TITLE_TEXT);
        setOnHover(on -> {
            bar.setBackgroundColor(on ? Theme.NAVY : RetroParts.INACTIVE_TITLE);
            name.setTextColor(on ? RetroParts.ACTIVE_TITLE_TEXT : RetroParts.INACTIVE_TITLE_TEXT);
        });
    }

    @Override
    public boolean dispatchHoverEvent(MotionEvent event) {
        int action = event.getActionMasked();
        // Crossing onto the X or the icon is a move within the tile, not an exit from it;
        // only leaving the tile itself sends one here.
        boolean inside = action != MotionEvent.ACTION_HOVER_EXIT;
        if (inside != mHovered) {
            mHovered = inside;
            hovered(inside);
        }
        return super.dispatchHoverEvent(event);
    }

    private void hovered(boolean on) {
        if (mOnHover != null) {
            mOnHover.hovered(on);
        }
        if (mTheme.retro()) {
            return;
        }
        if (mFade != null) {
            mFade.cancel();
        }
        mFade = android.animation.ValueAnimator.ofInt(mGlow.getAlpha(), on ? 255 : 0);
        mFade.setDuration(Motion.IOS_MS);
        mFade.setInterpolator(Motion.SMOOTH);
        mFade.addUpdateListener(a -> mGlow.setAlpha((Integer) a.getAnimatedValue()));
        mFade.start();
        if (mThumb != null) {
            mThumb.animate().scaleX(on ? 1.04f : 1f).scaleY(on ? 1.04f : 1f)
                    .setDuration(Motion.IOS_MS).setInterpolator(Motion.SNAPPY).withLayer()
                    .start();
        }
    }
}
