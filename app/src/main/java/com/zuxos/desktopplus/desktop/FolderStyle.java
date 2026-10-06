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
    /**
     * The folder's material, closed and open. The icon is a pale frosted tile and the open
     * folder is the same thing, grown: it used to open as dark glass, so it changed colour as it
     * grew and the two looks cross-faded as it closed back into its icon.
     */
    public static final int TILE = 0x66FFFFFF;
    /** The open panel's tint: light, so it is the tile's glass at full size. */
    public static final int PANEL_TINT = 0x8CF4F4F8;
    /** Labels and the name on that light glass. */
    public static final int LABEL = 0xFF1C1C1E;
    public static final int TITLE = 0x991C1C1E;

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
        title.setTextColor(TITLE);
        title.setShadowLayer(0f, 0f, 0f, 0);
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
        android.animation.ValueAnimator anim = morphAnimator(panel, source, from, to, ms, curve,
                true);
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
     * The launcher's own folder animation, replaced by this one: the same morph out of the icon
     * and back, for the folders ZUI opens on the tablet's home screen. The launcher hides and
     * shows its icon itself, so that part is left to it; whoever runs this attaches its own
     * listeners and starts it.
     */
    public static android.animation.ValueAnimator nativeMorph(View folder, View icon,
            boolean opening) {
        return opening
                ? morphAnimator(folder, icon, 0f, 1f, Motion.SPRING_MS, OPEN, false)
                : morphAnimator(folder, icon, 1f, 0f, CLOSE_MS, Motion.EASE, false);
    }

    /**
     * The animator under both. Where the icon is is measured on its first frame with the panel
     * laid out, not when it is made: the launcher builds its animation before its folder has a
     * place, and a panel never laid out cannot be put over anything.
     */
    private static android.animation.ValueAnimator morphAnimator(View panel, View source,
            float from, float to, long ms, android.animation.TimeInterpolator curve,
            boolean handOver) {
        final float[][] geometry = new float[1][];
        android.animation.ValueAnimator anim = android.animation.ValueAnimator.ofFloat(from, to);
        anim.setDuration(Math.max(1L, (long) (ms * Math.abs(to - from))));
        anim.setInterpolator(curve);
        final boolean closing = to < from;
        anim.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            if (geometry[0] == null) {
                if (panel.getWidth() == 0) {
                    panel.setAlpha(0f);
                    return;
                }
                geometry[0] = geometry(panel, source);
                panel.setPivotX(panel.getWidth() / 2f);
                panel.setPivotY(panel.getHeight() / 2f);
            }
            float[] g = geometry[0];
            panel.setTag(R_PROGRESS, f);
            float scale = g[2] + (1f - g[2]) * f;
            panel.setScaleX(scale);
            panel.setScaleY(scale);
            panel.setTranslationX(g[0] * (1f - f));
            panel.setTranslationY(g[1] * (1f - f));
            if (closing) {
                // The icon comes back as the panel, now its size, fades over it.
                float hand = Math.max(0f, Math.min(1f, f / 0.2f));
                panel.setAlpha(hand);
                if (handOver) {
                    sourceAlpha(panel, 1f - hand);
                }
            } else {
                // Not quite the icon's own look at the very start, so in over the first moment.
                panel.setAlpha(Math.max(0f, Math.min(1f, 0.35f + f * 2.5f)));
            }
            setScrim(panel, Math.max(0f, Math.min(1f, f)));
        });
        return anim;
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
        android.graphics.RectF icon = iconOnScreen(source);
        int[] at = restingOrigin(panel);
        float side = Math.min(icon.width(), icon.height());
        float dx = icon.centerX() - (at[0] + w / 2f);
        float dy = icon.centerY() - (at[1] + h / 2f);
        float scale = Math.max(0.05f, Math.min(1f, side / Math.max(w, h)));
        return new float[]{dx, dy, scale};
    }

    /**
     * The icon itself inside the view that was tapped, on screen.
     *
     * <p>A drawer or home-screen icon is a view with its label under the picture; growing from
     * the middle of all that started the folder off too big and too low. An icon with its picture
     * as a top drawable gives that drawable's place; a launcher folder icon, whose name view is
     * pushed down below the preview by its top padding, gives the square above the name;
     * anything else is taken as all icon.
     */
    private static android.graphics.RectF iconOnScreen(View source) {
        int[] at = new int[2];
        source.getLocationOnScreen(at);
        float w = source.getWidth();
        float h = source.getHeight();
        android.graphics.RectF r = findIcon(source, 0f, 0f, 0);
        if (r == null) {
            float side = Math.min(w, h);
            r = new android.graphics.RectF((w - side) / 2f, (h - side) / 2f, (w + side) / 2f,
                    (h + side) / 2f);
        }
        if (SAID.add(source.getClass().getName())) {
            com.zuxos.desktopplus.core.L.i("folder motion: icon of "
                    + source.getClass().getSimpleName() + " " + (int) w + "x" + (int) h
                    + " is " + (int) r.width() + "x" + (int) r.height() + " at "
                    + (int) r.left + "," + (int) r.top);
        }
        r.offset(at[0], at[1]);
        return r;
    }

    private static final java.util.Set<String> SAID = new java.util.HashSet<>();

    /**
     * The picture in {@code view} or under it, in the coordinates of the view first asked
     * ({@code dx}, {@code dy} being this view's offset in it): a top drawable, an image view's
     * picture, or the space a label leaves above itself for an icon it draws by hand.
     */
    private static android.graphics.RectF findIcon(View view, float dx, float dy, int depth) {
        if (view.getVisibility() != View.VISIBLE || depth > 4) {
            return null;
        }
        float w = view.getWidth();
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            android.graphics.drawable.Drawable top = text.getCompoundDrawables()[1];
            if (top != null) {
                float dw = top.getBounds().width() > 0 ? top.getBounds().width()
                        : top.getIntrinsicWidth();
                float dh = top.getBounds().height() > 0 ? top.getBounds().height()
                        : top.getIntrinsicHeight();
                if (dw > 0 && dh > 0) {
                    float y = text.getPaddingTop();
                    return new android.graphics.RectF(dx + (w - dw) / 2f, dy + y,
                            dx + (w + dw) / 2f, dy + y + dh);
                }
            }
            // A launcher icon that draws its picture itself pushes its label down below it.
            float above = text.getTotalPaddingTop() - text.getCompoundDrawablePadding();
            if (above > view.getHeight() * 0.3f) {
                float side = Math.min(w, above);
                return new android.graphics.RectF(dx + (w - side) / 2f, dy + above - side,
                        dx + (w + side) / 2f, dy + above);
            }
            return null;
        }
        if (view instanceof android.widget.ImageView
                && ((android.widget.ImageView) view).getDrawable() != null) {
            float side = Math.min(w - view.getPaddingLeft() - view.getPaddingRight(),
                    view.getHeight() - view.getPaddingTop() - view.getPaddingBottom());
            if (side > 0) {
                float cx = dx + view.getPaddingLeft()
                        + (w - view.getPaddingLeft() - view.getPaddingRight()) / 2f;
                float cy = dy + view.getPaddingTop() + (view.getHeight()
                        - view.getPaddingTop() - view.getPaddingBottom()) / 2f;
                return new android.graphics.RectF(cx - side / 2f, cy - side / 2f,
                        cx + side / 2f, cy + side / 2f);
            }
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            // Pictures first: an image view beside a label is the icon, whatever the label does.
            for (int pass = 0; pass < 2; pass++) {
                for (int i = 0; i < group.getChildCount(); i++) {
                    View child = group.getChildAt(i);
                    if ((pass == 0) == (child instanceof TextView)) {
                        continue;
                    }
                    android.graphics.RectF r = findIcon(child, dx + child.getLeft(),
                            dy + child.getTop(), depth + 1);
                    if (r != null) {
                        return r;
                    }
                }
            }
        }
        return null;
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
            restoreIcon((View) hidden);
        }
    }

    /**
     * An icon a folder opened from, back to its full size. Opening the folder ourselves cut the
     * launcher's tap short: its press shrink was never undone, and the drawer folder stayed a
     * size smaller than its neighbours after it closed (the 15:35 recording).
     */
    public static void restoreIcon(View icon) {
        try {
            icon.setPressed(false);
            icon.setHovered(false);
            icon.jumpDrawablesToCurrentState();
            if (icon.getScaleX() != 1f || icon.getScaleY() != 1f) {
                icon.animate().scaleX(1f).scaleY(1f).setDuration(Motion.SPRING_MS)
                        .setInterpolator(Motion.SNAPPY).start();
            }
        } catch (Throwable ignored) {
            // A view gone with its window.
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
