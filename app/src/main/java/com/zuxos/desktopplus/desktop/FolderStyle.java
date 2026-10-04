package com.zuxos.desktopplus.desktop;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.Motion;
import com.zuxos.desktopplus.core.Ui;

/**
 * How an open folder looks and moves - the same on the home screen, in the drawer and on the
 * taskbar, and modelled on the folders ZUI opens from its own taskbar.
 *
 * <p>Those are compact: a panel just big enough for its icons, the name small in the top-left
 * corner, and the whole thing grows out of the icon you tapped and shrinks back into it. Ours
 * were a large fixed panel with a big heading that simply appeared. ZUI's own folder view cannot
 * be borrowed - it needs the launcher's activity and its model objects behind every icon - so
 * this matches it instead, at the icon size set in the module.
 */
public final class FolderStyle {

    /** Corner radius of the panel. */
    public static final float RADIUS_DP = 24;
    /** Space between the panel's edge and what is in it. */
    public static final float PADDING_DP = 16;
    /** What a cell adds to the icon's width for its label. */
    public static final float CELL_EXTRA_DP = 28;
    /** Behind the panel: enough to set it apart, not enough to black the screen out. */
    public static final int SCRIM = 0x40000000;

    private static final float FROM_SCALE = 0.25f;

    private FolderStyle() {
    }

    /** Columns for this many icons: a square-ish block, never wider than four. */
    public static int columns(int count) {
        if (count <= 1) {
            return 1;
        }
        return Math.min(4, (int) Math.ceil(Math.sqrt(count)));
    }

    public static int cellWidth(Context ctx, int iconSizePx) {
        return iconSizePx + Ui.dp(ctx, CELL_EXTRA_DP);
    }

    /** The folder's name: small, top-left, a little dimmer than the labels under it. */
    public static void styleTitle(TextView title) {
        title.setTextSize(14);
        title.setTextColor(Ui.COLOR_TEXT_DIM);
        title.setSingleLine(true);
        title.setIncludeFontPadding(false);
        title.setPadding(Ui.dp(title.getContext(), 4), 0, 0, 0);
    }

    /**
     * Opens the panel out of the icon that was tapped, the way iOS opens a folder.
     *
     * <p>Not a zoom from a point with a fade: the panel starts as the icon - its size, its place -
     * and grows into itself, so the icons inside travel out of the little ones in the preview. The
     * icon itself is hidden for as long as the folder is open, so there are never two of it on
     * screen; {@link #zoomOut} shrinks the panel back into exactly that spot and hands over to the
     * icon as it lands. The scrim behind fades with it.
     */
    public static void zoomIn(View panel, View source) {
        cancel(panel);
        panel.setVisibility(View.VISIBLE);
        hide(panel, source);
        if (!Cfg.animations()) {
            reset(panel);
            setScrim(panel, 1f);
            return;
        }
        // Until it has been laid out it cannot be placed over the icon; not shown till then.
        panel.setAlpha(0f);
        setScrim(panel, 0f);
        panel.post(() -> {
            if (panel.getWidth() == 0) {
                reset(panel);
                setScrim(panel, 1f);
                return;
            }
            morph(panel, source, 0f, 1f, Motion.SPRING_MS, OPEN, () -> {
                // The glass took its picture when the panel opened; this only catches a panel
                // that has moved since.
                if (panel instanceof GlassPanel) {
                    ((GlassPanel) panel).refresh();
                }
            });
        });
    }

    /** Shrinks it back into the icon it came from, then runs {@code onEnd}. */
    public static void zoomOut(View panel, View source, Runnable onEnd) {
        cancel(panel);
        Runnable done = () -> {
            reset(panel);
            show(panel);
            if (onEnd != null) {
                onEnd.run();
            }
        };
        if (!Cfg.animations() || panel.getWidth() == 0) {
            done.run();
            return;
        }
        float from = panel.getTag(R_PROGRESS) instanceof Float ? (Float) panel.getTag(R_PROGRESS)
                : 1f;
        morph(panel, source, from, 0f, CLOSE_MS, Motion.EASE, done);
    }

    /** Opening: a spring that settles without a visible wobble, as iOS's folders do. */
    private static final android.animation.TimeInterpolator OPEN = Motion.spring(0.82f);
    private static final long CLOSE_MS = 300L;

    /** View tag keys: arbitrary but stable ids, kept clear of the launcher's own. */
    private static final int R_ANIMATOR = 0x7f0f2a01;
    private static final int R_PROGRESS = 0x7f0f2a02;
    private static final int R_HIDDEN = 0x7f0f2a03;

    /**
     * Runs the panel between the icon (0) and its own place (1).
     *
     * <p>Scaled about its centre, by the same amount both ways so the icons in it keep their
     * shape, from the icon's size to its own; moved from the icon's centre to its own. On the way
     * out the last stretch is a cross-fade: the panel, icon-sized by then, gives way to the icon.
     */
    private static void morph(View panel, View source, float from, float to, long ms,
            android.animation.TimeInterpolator curve, Runnable onEnd) {
        float[] geometry = geometry(panel, source);
        panel.setPivotX(panel.getWidth() / 2f);
        panel.setPivotY(panel.getHeight() / 2f);
        android.animation.ValueAnimator anim = android.animation.ValueAnimator.ofFloat(from, to);
        anim.setDuration(Math.max(1L, (long) (ms * Math.abs(to - from))));
        anim.setInterpolator(curve);
        final boolean closing = to < from;
        anim.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            panel.setTag(R_PROGRESS, f);
            float scale = geometry[2] + (1f - geometry[2]) * f;
            panel.setScaleX(scale);
            panel.setScaleY(scale);
            panel.setTranslationX(geometry[0] * (1f - f));
            panel.setTranslationY(geometry[1] * (1f - f));
            if (closing) {
                // The icon comes back as the panel, now its size, fades over it.
                float hand = Math.max(0f, Math.min(1f, f / 0.2f));
                panel.setAlpha(hand);
                sourceAlpha(panel, 1f - hand);
            } else {
                // Not quite the icon's own look at the very start, so in over the first moment.
                panel.setAlpha(Math.max(0f, Math.min(1f, 0.35f + f * 2.5f)));
            }
            setScrim(panel, Math.max(0f, Math.min(1f, f)));
        });
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            private boolean mCancelled;

            @Override
            public void onAnimationCancel(android.animation.Animator animation) {
                mCancelled = true;
            }

            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (panel.getTag(R_ANIMATOR) == animation) {
                    panel.setTag(R_ANIMATOR, null);
                }
                if (!mCancelled && onEnd != null) {
                    onEnd.run();
                }
            }
        });
        panel.setTag(R_ANIMATOR, anim);
        anim.start();
    }

    /**
     * Where the icon is, against the panel at rest: the offset of its centre from the panel's,
     * and the scale that makes the panel its size. With no icon, a small grow from where it is.
     */
    private static float[] geometry(View panel, View source) {
        float w = panel.getWidth();
        float h = panel.getHeight();
        if (source == null || !source.isAttachedToWindow() || w == 0 || h == 0) {
            return new float[]{0f, 0f, 0.85f};
        }
        int[] from = new int[2];
        source.getLocationOnScreen(from);
        int[] at = restingOrigin(panel);
        float side = Math.min(source.getWidth(), source.getHeight());
        float dx = from[0] + source.getWidth() / 2f - (at[0] + w / 2f);
        float dy = from[1] + source.getHeight() / 2f - (at[1] + h / 2f);
        float scale = Math.max(0.05f, Math.min(1f, side / Math.max(w, h)));
        return new float[]{dx, dy, scale};
    }

    /** The panel's top-left on screen with no animation applied to it. */
    private static int[] restingOrigin(View panel) {
        int[] origin = new int[2];
        if (panel.getParent() instanceof View) {
            View parent = (View) panel.getParent();
            parent.getLocationOnScreen(origin);
            origin[0] += panel.getLeft() - parent.getScrollX();
            origin[1] += panel.getTop() - parent.getScrollY();
        } else {
            panel.getLocationOnScreen(origin);
        }
        return origin;
    }

    private static void cancel(View panel) {
        panel.animate().cancel();
        Object running = panel.getTag(R_ANIMATOR);
        if (running instanceof android.animation.Animator) {
            ((android.animation.Animator) running).cancel();
        }
        panel.setTag(R_ANIMATOR, null);
    }

    /** Hides the icon the folder opens from, giving back one hidden by an earlier opening. */
    private static void hide(View panel, View source) {
        Object old = panel.getTag(R_HIDDEN);
        if (old instanceof View && old != source) {
            ((View) old).setAlpha(1f);
        }
        panel.setTag(R_HIDDEN, source);
        if (source != null) {
            source.setAlpha(0f);
        }
    }

    /** Gives the icon back: the folder has closed, or gone without closing. */
    public static void show(View panel) {
        Object hidden = panel.getTag(R_HIDDEN);
        panel.setTag(R_HIDDEN, null);
        if (hidden instanceof View) {
            ((View) hidden).setAlpha(1f);
        }
    }

    private static void sourceAlpha(View panel, float alpha) {
        Object hidden = panel.getTag(R_HIDDEN);
        if (hidden instanceof View) {
            ((View) hidden).setAlpha(alpha);
        }
    }

    /** The scrim is the background of whatever holds the panel, when that is a plain colour. */
    private static void setScrim(View panel, float amount) {
        if (panel.getParent() instanceof View) {
            android.graphics.drawable.Drawable bg = ((View) panel.getParent()).getBackground();
            if (bg instanceof android.graphics.drawable.ColorDrawable) {
                bg.mutate().setAlpha(Math.round(255 * amount));
            }
        }
    }

    private static void reset(View panel) {
        panel.setAlpha(1f);
        panel.setScaleX(1f);
        panel.setScaleY(1f);
        panel.setTranslationX(0f);
        panel.setTranslationY(0f);
        panel.setTag(R_PROGRESS, null);
    }
}
