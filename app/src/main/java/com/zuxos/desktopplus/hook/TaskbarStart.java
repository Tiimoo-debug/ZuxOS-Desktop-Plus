package com.zuxos.desktopplus.hook;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;
import com.zuxos.desktopplus.core.Ui;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * The drawer button, at the end of the bar where a desktop keeps it.
 *
 * <p>ZUI puts its all-apps button at the right-hand end of the centred icon cluster, which leaves
 * it floating in the middle of the bar once there are open apps either side of it. Every desktop
 * since Windows 95 puts that button in a corner, so this moves it to the left, beside the
 * navigation keys.
 *
 * <p>Moved, not rebuilt. The launcher's own button is hidden and ours is a <em>picture of it</em> -
 * drawn from the real view, so it looks exactly like whatever the firmware draws - and a tap calls
 * {@code performClick()} on the hidden original. The drawer opens the launcher's own way, with the
 * launcher's own animation, and nothing about how it opens is reimplemented here.
 *
 * <p>If the picture cannot be taken, the launcher's button is left exactly where it is. No setting
 * should be able to leave somebody with no way into their app drawer.
 */
final class TaskbarStart {

    private static final Map<View, Boolean> HIDDEN = new WeakHashMap<>();

    private TaskbarStart() {
    }

    /** Puts our button in, or takes it out and gives the launcher its own back. */
    static void apply(ViewGroup dragLayer, ViewGroup icons) {
        try {
            View original = allAppsButton(icons);
            StartButton ours = buttonIn(dragLayer);
            if (!Cfg.startButtonLeft() || original == null) {
                if (ours != null) {
                    dragLayer.removeView(ours);
                }
                show(original);
                return;
            }
            if (ours == null) {
                ours = add(dragLayer, original);
                if (ours == null) {
                    // Nothing was hidden, because nothing replaced it.
                    return;
                }
            }
            ours.place(dragLayer);
            hide(original);
        } catch (Throwable t) {
            L.d("taskbar start: not moved (" + t + ")");
        }
    }

    /** The launcher's own all-apps button, by the name its class carries on every build. */
    private static View allAppsButton(ViewGroup icons) {
        if (icons == null) {
            return null;
        }
        for (View view : Reflect.findByClassFragments(icons, "AllAppsButton")) {
            return view;
        }
        return null;
    }

    private static StartButton buttonIn(ViewGroup dragLayer) {
        for (int i = 0; i < dragLayer.getChildCount(); i++) {
            if (dragLayer.getChildAt(i) instanceof StartButton) {
                return (StartButton) dragLayer.getChildAt(i);
            }
        }
        return null;
    }

    private static StartButton add(ViewGroup dragLayer, View original) {
        Bitmap picture = pictureOf(original);
        if (picture == null) {
            L.w("taskbar start: the launcher's drawer button could not be copied, so it stays "
                    + "where it is");
            return null;
        }
        View reference = TaskbarTray.rowReference(dragLayer);
        ViewGroup.LayoutParams lp = TaskbarTray.dragLayerParams(dragLayer, reference);
        if (!(lp instanceof FrameLayout.LayoutParams)) {
            return null;
        }
        StartButton button = new StartButton(dragLayer.getContext(), original, picture, reference);
        dragLayer.addView(button, lp);
        L.i("taskbar start: the drawer button sits at the left of the bar now");
        return button;
    }

    /**
     * The button as it is drawn, as a bitmap.
     *
     * <p>A copy of the pixels rather than an attempt to find the drawable inside it: the button is
     * a container with a themed icon inside, and whichever of those a firmware uses, what it draws
     * is what we want.
     */
    private static Bitmap pictureOf(View view) {
        try {
            if (view.getWidth() <= 0 || view.getHeight() <= 0) {
                return null;
            }
            Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(),
                    Bitmap.Config.ARGB_8888);
            view.draw(new Canvas(bitmap));
            return bitmap;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void hide(View original) {
        if (original != null && original.getVisibility() == View.VISIBLE) {
            HIDDEN.put(original, Boolean.TRUE);
            original.setVisibility(View.GONE);
            if (original.getParent() instanceof View) {
                ((View) original.getParent()).requestLayout();
            }
        }
    }

    private static void show(View original) {
        if (original != null && HIDDEN.remove(original) != null) {
            original.setVisibility(View.VISIBLE);
            if (original.getParent() instanceof View) {
                ((View) original.getParent()).requestLayout();
            }
        }
    }

    /** Ours: a picture of the launcher's button that forwards its taps to the real one. */
    private static final class StartButton extends ImageView {

        private final View mOriginal;
        private final View mReference;

        StartButton(Context ctx, View original, Bitmap picture, View reference) {
            super(ctx);
            mOriginal = original;
            mReference = reference;
            setImageDrawable(new BitmapDrawable(ctx.getResources(), picture));
            setContentDescription("All apps");
            setBackground(Ui.ripple(ctx, 0x00000000, picture.getWidth() / 2));
            // The launcher's own button does the work, hidden or not: a click listener fires
            // whether or not the view it is on can be seen.
            setOnClickListener(v -> mOriginal.performClick());
            setOnLongClickListener(v -> mOriginal.performLongClick());
        }

        void place(ViewGroup dragLayer) {
            ViewGroup.LayoutParams raw = getLayoutParams();
            if (!(raw instanceof FrameLayout.LayoutParams)) {
                return;
            }
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) raw;
            int size = mOriginal.getWidth() > 0 ? mOriginal.getWidth() : Ui.dp(getContext(), 44);
            int left = navButtonsEnd(dragLayer) + Ui.dp(getContext(), 12);
            int top = mReference != null && mReference.getHeight() > 0 ? mReference.getTop() : 0;
            int height = mReference != null && mReference.getHeight() > 0
                    ? mReference.getHeight() : size;
            if (lp.leftMargin == left && lp.topMargin == top && lp.height == height
                    && lp.width == size) {
                return;
            }
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.leftMargin = left;
            lp.topMargin = top;
            lp.width = size;
            lp.height = height;
            setLayoutParams(lp);
        }

        /** Where the navigation keys end, so the button sits beside them rather than on them. */
        private int navButtonsEnd(ViewGroup dragLayer) {
            for (View view : Reflect.findByIdNames(dragLayer, "end_nav_buttons",
                    "start_contextual_buttons", "navbuttons_view")) {
                if (view.getVisibility() == View.VISIBLE && view.getWidth() > 0) {
                    int[] at = new int[2];
                    int[] layer = new int[2];
                    view.getLocationOnScreen(at);
                    dragLayer.getLocationOnScreen(layer);
                    return at[0] - layer[0] + view.getWidth();
                }
            }
            return Ui.dp(getContext(), 8);
        }
    }
}
