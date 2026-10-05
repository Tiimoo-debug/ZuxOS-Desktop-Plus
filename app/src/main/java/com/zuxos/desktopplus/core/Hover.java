package com.zuxos.desktopplus.core;

import android.animation.Keyframe;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
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
        icon.animate().cancel();
        icon.animate().scaleX(LIFT).scaleY(LIFT).setDuration(Motion.IOS_MS)
                .setInterpolator(Motion.IOS).withLayer().start();
        Object old = icon.getTag(TAG_WIGGLE);
        if (old instanceof ObjectAnimator) {
            ((ObjectAnimator) old).cancel();
        }
        // Left, right, smaller each time, still: a jiggle that settles rather than a shake.
        PropertyValuesHolder rotation = PropertyValuesHolder.ofKeyframe(View.ROTATION,
                Keyframe.ofFloat(0f, 0f),
                Keyframe.ofFloat(0.18f, -7f),
                Keyframe.ofFloat(0.40f, 6f),
                Keyframe.ofFloat(0.62f, -4f),
                Keyframe.ofFloat(0.82f, 2f),
                Keyframe.ofFloat(1f, 0f));
        ObjectAnimator wiggle = ObjectAnimator.ofPropertyValuesHolder(icon, rotation);
        wiggle.setDuration(WIGGLE_MS);
        wiggle.setInterpolator(Motion.EASE);
        icon.setTag(TAG_WIGGLE, wiggle);
        wiggle.start();
    }

    /** The pointer left: back to rest on the same spring. */
    public static void exit(View icon) {
        Object old = icon.getTag(TAG_WIGGLE);
        if (old instanceof ObjectAnimator) {
            ((ObjectAnimator) old).cancel();
        }
        icon.animate().cancel();
        icon.animate().scaleX(1f).scaleY(1f).rotation(0f).setDuration(Motion.IOS_MS)
                .setInterpolator(Motion.IOS).withLayer().start();
    }
}
