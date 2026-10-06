package com.zuxos.desktopplus.hook;

import android.content.Context;
import android.view.MotionEvent;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.zuxos.desktopplus.core.Motion;
import com.zuxos.desktopplus.core.Ui;

/**
 * A tile under the pointer: a soft highlight comes up behind it and its picture grows a
 * little; the icon, name and X stay exactly where they are.
 *
 * <p>Hover is read from what reaches the tile as a whole, not from the tile's own
 * enter/exit: those fire every time the pointer crosses the tile's X or icon, and the tile
 * used to grow and shrink with each crossing - taking the icon and X with it.
 */
final class HoverTile extends LinearLayout {
    ImageView mThumb;
    private boolean mHovered;
    private final android.graphics.drawable.GradientDrawable mGlow;
    private android.animation.ValueAnimator mFade;

    HoverTile(Context ctx) {
        super(ctx);
        mGlow = Ui.roundRect(0x1FFFFFFF, Ui.dp(ctx, 16));
        mGlow.setAlpha(0);
        setBackground(mGlow);
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
