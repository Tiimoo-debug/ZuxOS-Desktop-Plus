package com.zuxos.desktopplus.core;

import android.animation.LayoutTransition;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;

/**
 * Short, consistent motion for the module's surfaces.
 *
 * <p>Everything here is a no-op when animations are turned off, and every animation leaves the
 * view in a defined state even if it is interrupted - a half-faded drawer that never finishes is
 * worse than no animation at all.
 */
public final class Anim {

    private static final int FAST = 160;
    private static final int NORMAL = 220;

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
                .setInterpolator(new DecelerateInterpolator()).start();
    }

    public static void fadeOut(View view, Runnable onEnd) {
        view.animate().cancel();
        if (!Cfg.animations()) {
            view.setVisibility(View.GONE);
            if (onEnd != null) {
                onEnd.run();
            }
            return;
        }
        view.animate().alpha(0f).setDuration(FAST)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> {
                    view.setVisibility(View.GONE);
                    view.setAlpha(1f);
                    if (onEnd != null) {
                        onEnd.run();
                    }
                }).start();
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
        sheet.animate().translationY(0f).setDuration(NORMAL)
                .setInterpolator(new DecelerateInterpolator(1.6f)).start();
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
                .setInterpolator(new DecelerateInterpolator(1.6f))
                .withEndAction(() -> {
                    view.setVisibility(View.GONE);
                    view.setAlpha(1f);
                    sheet.setTranslationY(0f);
                    if (onEnd != null) {
                        onEnd.run();
                    }
                }).start();
    }

    /** Pops a panel open from slightly small and transparent. */
    public static void popIn(View view, View panel) {
        view.animate().cancel();
        panel.animate().cancel();
        view.setVisibility(View.VISIBLE);
        if (!Cfg.animations()) {
            panel.setScaleX(1f);
            panel.setScaleY(1f);
            view.setAlpha(1f);
            return;
        }
        view.setAlpha(0f);
        panel.setScaleX(0.92f);
        panel.setScaleY(0.92f);
        view.animate().alpha(1f).setDuration(FAST).start();
        panel.animate().scaleX(1f).scaleY(1f).setDuration(NORMAL)
                .setInterpolator(new DecelerateInterpolator(1.8f)).start();
    }

    /** Slides the desktop sideways when changing page. */
    public static void slidePage(View view, boolean forward, Runnable atMidpoint) {
        if (!Cfg.animations()) {
            atMidpoint.run();
            return;
        }
        int width = Math.max(1, view.getWidth());
        float out = forward ? -width * 0.25f : width * 0.25f;
        view.animate().translationX(out).alpha(0f).setDuration(120)
                .withEndAction(() -> {
                    atMidpoint.run();
                    view.setTranslationX(-out);
                    view.animate().translationX(0f).alpha(1f).setDuration(180)
                            .setInterpolator(new DecelerateInterpolator()).start();
                }).start();
    }

    /** Animates adds and removes inside a container. */
    public static void enableLayoutTransitions(ViewGroup group) {
        if (!Cfg.animations()) {
            group.setLayoutTransition(null);
            return;
        }
        LayoutTransition transition = new LayoutTransition();
        transition.setDuration(FAST);
        // Deliberately not CHANGING: animating bounds would lag behind a drag or a resize handle.
        transition.disableTransitionType(LayoutTransition.CHANGING);
        group.setLayoutTransition(transition);
    }
}
