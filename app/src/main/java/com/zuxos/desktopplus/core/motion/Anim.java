package com.zuxos.desktopplus.core.motion;

import android.animation.LayoutTransition;
import android.view.View;
import android.view.ViewGroup;

import com.zuxos.desktopplus.core.Cfg;

/**
 * Short, consistent motion for the module's surfaces.
 *
 * <p>Everything here is a no-op when animations are turned off, and every animation leaves the
 * view in a defined state even if it is interrupted - a half-faded drawer that never finishes is
 * worse than no animation at all.
 */
public final class Anim {

    // iOS fades take about a quarter to a third of a second; shorter reads as a blink.
    private static final int FAST = 220;
    private static final int NORMAL = 300;

    private Anim() {
    }

    public static void fadeIn(View view) {
        view.animate().cancel();
        view.setVisibility(View.VISIBLE);
        if (!Cfg.animations()) {
            view.setAlpha(1f);
            return;
        }
        view.setAlpha(0f);
        view.animate().alpha(1f).setDuration(FAST)
                .setInterpolator(Motion.EASE).start();
    }

    /** Slides a sheet up from the bottom edge. */
    public static void slideUp(View view, View sheet) {
        view.animate().cancel();
        sheet.animate().cancel();
        view.setVisibility(View.VISIBLE);
        if (!Cfg.animations()) {
            sheet.setTranslationY(0f);
            view.setAlpha(1f);
            return;
        }
        view.setAlpha(0f);
        view.animate().alpha(1f).setDuration(FAST).start();
        sheet.setTranslationY(sheet.getHeight() > 0 ? sheet.getHeight() : 600);
        sheet.animate().translationY(0f).setDuration(Motion.SMOOTH_MS)
                .setInterpolator(Motion.SMOOTH).withLayer().start();
    }

    public static void slideDown(View view, View sheet, Runnable onEnd) {
        view.animate().cancel();
        sheet.animate().cancel();
        if (!Cfg.animations()) {
            view.setVisibility(View.GONE);
            sheet.setTranslationY(0f);
            if (onEnd != null) {
                onEnd.run();
            }
            return;
        }
        float target = sheet.getHeight() > 0 ? sheet.getHeight() : 600;
        view.animate().alpha(0f).setDuration(NORMAL).start();
        sheet.animate().translationY(target).setDuration(NORMAL)
                .setInterpolator(Motion.EXIT)
                .withEndAction(() -> {
                    view.setVisibility(View.GONE);
                    view.setAlpha(1f);
                    sheet.setTranslationY(0f);
                    if (onEnd != null) {
                        onEnd.run();
                    }
                }).start();
    }

    /** Slides the desktop sideways when changing page. */
    public static void slidePage(View view, boolean forward, Runnable atMidpoint) {
        if (!Cfg.animations()) {
            atMidpoint.run();
            return;
        }
        int width = Math.max(1, view.getWidth());
        float out = forward ? -width * 0.25f : width * 0.25f;
        view.animate().translationX(out).alpha(0f).setDuration(Motion.SHORT)
                .setInterpolator(Motion.EXIT).withLayer()
                .withEndAction(() -> {
                    atMidpoint.run();
                    view.setTranslationX(-out);
                    view.animate().translationX(0f).alpha(1f).setDuration(Motion.SMOOTH_MS)
                            .setInterpolator(Motion.SMOOTH).withLayer().start();
                }).start();
    }

    /** Animates adds and removes inside a container, and hands back the transition it installed. */
    public static LayoutTransition enableLayoutTransitions(ViewGroup group) {
        if (!Cfg.animations()) {
            group.setLayoutTransition(null);
            return null;
        }
        LayoutTransition transition = new LayoutTransition();
        transition.setDuration(FAST);
        // Deliberately not CHANGING: animating bounds would lag behind a drag or a resize handle.
        transition.disableTransitionType(LayoutTransition.CHANGING);
        group.setLayoutTransition(transition);
        return transition;
    }
}
