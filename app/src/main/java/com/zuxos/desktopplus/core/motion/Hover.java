package com.zuxos.desktopplus.core.motion;

import android.animation.Keyframe;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.view.View;

/**
 * What an icon does under a mouse or a stylus: it lifts, and gives a short jiggle - the iOS one.
 *
 * <p>Started straight from the hover event, never posted: a frame of delay is what makes hover
 * feel late. Both run on the render thread through a hardware layer, so nothing else the launcher
 * is doing can make them stutter.
 */
public final class Hover {

    /** How much the icon grows under the pointer. */
    private static final float LIFT = 1.14f;
    private static final long WIGGLE_MS = 420L;
    private static final int TAG_WIGGLE = 0x7A000101;

    private Hover() {
    }

    /** The pointer arrived on the icon. */
    public static void enter(View icon) {
        lift(icon, LIFT, false);
    }

    /**
     * The same with a lift of its own - and with room to grow: whatever holds the icon stops
     * cutting off what is outside its bounds, or the grown icon is clipped at its cell's edge
     * (the folders, the desktop and the drawer all showed it).
     */
    public static void enter(View icon, float lift) {
        lift(icon, lift, true);
    }

    private static void lift(View icon, float lift, boolean unclip) {
        if (unclip) {
            unclip(icon);
        }
        icon.animate().cancel();
        Object old = icon.getTag(TAG_WIGGLE);
        if (old instanceof ValueAnimator) {
            ((ValueAnimator) old).cancel();
        }
        // Left, right, smaller each time, still: a jiggle that settles rather than a shake.
        PropertyValuesHolder turn = PropertyValuesHolder.ofKeyframe("turn",
                Keyframe.ofFloat(0f, 0f),
                Keyframe.ofFloat(0.18f, -5f),
                Keyframe.ofFloat(0.40f, 4f),
                Keyframe.ofFloat(0.62f, -2.5f),
                Keyframe.ofFloat(0.82f, 1f),
                Keyframe.ofFloat(1f, 0f));
        // The lift and the turn are one animation, so the icon's turned corners never reach past
        // where the settled, lifted icon ends: the scale gives back what the turn takes. Two
        // separate ones - a springy grow and a rotation - added up, and for a few frames the
        // icon reached past its holder and was cut off there.
        float from = icon.getScaleX();
        float liftAt = (float) Motion.IOS_MS / WIGGLE_MS;
        ValueAnimator wiggle = ValueAnimator.ofPropertyValuesHolder(turn);
        wiggle.setDuration(WIGGLE_MS);
        wiggle.addUpdateListener(a -> {
            float t = a.getAnimatedFraction();
            float grown = from + (lift - from) * Motion.EASE.getInterpolation(
                    Math.min(1f, t / liftAt));
            float degrees = (Float) a.getAnimatedValue("turn");
            double r = Math.toRadians(Math.abs(degrees));
            float fit = (float) (Math.cos(r) + Math.sin(r));
            icon.setRotation(degrees);
            icon.setScaleX(grown / fit);
            icon.setScaleY(grown / fit);
        });
        icon.setTag(TAG_WIGGLE, wiggle);
        wiggle.start();
    }

    private static final int TAG_UNCLIPPED = 0x7A000102;
    private static final int TAG_GLOW = 0x7A000103;

    /** The icon's holders, three deep, let it draw past their edges. Once per holder. */
    public static void unclip(View icon) {
        android.view.ViewParent p = icon.getParent();
        for (int i = 0; i < 3 && p instanceof android.view.ViewGroup; i++) {
            android.view.ViewGroup g = (android.view.ViewGroup) p;
            if (g instanceof android.widget.ScrollView
                    || g instanceof android.widget.HorizontalScrollView
                    || g instanceof android.widget.AbsListView
                    || g.getClass().getName().contains("RecyclerView")) {
                // A scrolling list has to keep clipping, or what is scrolled out of it shows.
                return;
            }
            if (g.getTag(TAG_UNCLIPPED) == null) {
                g.setTag(TAG_UNCLIPPED, Boolean.TRUE);
                g.setClipChildren(false);
                g.setClipToPadding(false);
            }
            p = g.getParent();
        }
    }

    /**
     * A button under the pointer - the power button, the picture: it grows a little and a soft
     * round light comes up behind it. No jiggle: that is for app icons.
     */
    public static void lift(View button) {
        unclip(button);
        button.animate().cancel();
        button.animate().scaleX(1.08f).scaleY(1.08f).setDuration(Motion.IOS_MS)
                .setInterpolator(Motion.SNAPPY).withLayer().start();
        glow(button, true);
    }

    /** The pointer left the button. */
    public static void drop(View button) {
        button.animate().cancel();
        button.animate().scaleX(1f).scaleY(1f).setDuration(Motion.IOS_MS)
                .setInterpolator(Motion.SNAPPY).withLayer().start();
        glow(button, false);
    }

    private static void glow(View button, boolean on) {
        android.graphics.drawable.Drawable fg = button.getForeground();
        if (!(fg instanceof android.graphics.drawable.GradientDrawable)
                || button.getTag(TAG_GLOW) == null) {
            android.graphics.drawable.GradientDrawable light =
                    new android.graphics.drawable.GradientDrawable();
            light.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            light.setColor(0x33FFFFFF);
            light.setAlpha(0);
            button.setForeground(light);
            button.setTag(TAG_GLOW, light);
            fg = light;
        }
        android.graphics.drawable.Drawable light = fg;
        android.animation.ValueAnimator fade = android.animation.ValueAnimator.ofInt(
                light.getAlpha(), on ? 255 : 0);
        fade.setDuration(Motion.IOS_MS);
        fade.setInterpolator(Motion.SMOOTH);
        fade.addUpdateListener(a -> light.setAlpha((Integer) a.getAnimatedValue()));
        fade.start();
    }

    /** The pointer left: back to rest on the same spring. */
    public static void exit(View icon) {
        Object old = icon.getTag(TAG_WIGGLE);
        if (old instanceof ValueAnimator) {
            ((ValueAnimator) old).cancel();
        }
        icon.animate().cancel();
        // No overshoot on the way down either: a spring would dip it below its resting size.
        icon.animate().scaleX(1f).scaleY(1f).rotation(0f).setDuration(Motion.IOS_MS)
                .setInterpolator(Motion.EASE).start();
    }
}
