package com.zuxos.desktopplus.hook;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.Glass;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;
import com.zuxos.desktopplus.core.Ui;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Glass for the launcher's own app drawer.
 *
 * <p>The sheet that slides up from the taskbar is an ordinary view with an ordinary background, so
 * this is a background swap and nothing more - no hooks in its drawing, no window flags, and the
 * exact drawable it had is put back when the setting goes off.
 *
 * <p>The one thing that must not be guessed is the tone. The drawer's own labels are painted for
 * whatever colour the sheet was, and a dark pane under dark text is unreadable. So the original
 * background is sampled - drawn into a single pixel, which works whatever kind of drawable it is -
 * and the glass is built light or dark to match. The labels stay legible without being touched.
 */
final class DrawerGlass {

    /** Class names the all-apps sheet goes under; the first one found is the one glazed. */
    private static final String[] SHEET_CLASSES = {
            "AllAppsSlideInView", "AllAppsContainerView", "AppsContainerView", "AllAppsView",
    };

    /** What each glazed sheet had before, so it can be put back exactly. */
    private static final Map<View, Drawable[]> ORIGINALS = new WeakHashMap<>();

    private static boolean sDescribed;

    private DrawerGlass() {
    }

    /** Called for every window the launcher opens; the drawer is one of them. */
    static void onWindowAdded(View root) {
        if (!(root instanceof ViewGroup)) {
            return;
        }
        try {
            List<View> found = Reflect.findByClassFragments(root, SHEET_CLASSES);
            if (found.isEmpty()) {
                return;
            }
            View sheet = pick(found);
            if (sheet == null) {
                return;
            }
            if (!sDescribed) {
                sDescribed = true;
                L.i("drawer glass: found " + sheet.getClass().getSimpleName()
                        + " with background " + describe(sheet.getBackground()));
            }
            // After the window has been laid out: a sheet asked for its background before it has
            // any size is a sheet whose own background has not been set yet.
            sheet.post(() -> apply(sheet));
        } catch (Throwable t) {
            L.d("drawer glass: could not look at this window (" + t + ")");
        }
    }

    /**
     * Which of the matching views is the pane you can see.
     *
     * <p>The search returns the outermost first, and on this launcher that is the slide-in
     * wrapper: it has no background of its own, so glazing it would paint behind an opaque sheet
     * and, worse, sample nothing and guess the tone. The one that carries a background is the
     * sheet, so that is the one taken - innermost first, since that is the one drawn last.
     */
    private static View pick(List<View> candidates) {
        for (int i = candidates.size() - 1; i >= 0; i--) {
            View view = candidates.get(i);
            if (ORIGINALS.containsKey(view) || sample(view.getBackground()) != 0) {
                return view;
            }
        }
        return null;
    }

    private static void apply(View sheet) {
        try {
            if (!(Cfg.drawerGlass() && Cfg.glass())) {
                restore(sheet);
                return;
            }
            if (ORIGINALS.containsKey(sheet)) {
                return;
            }
            Drawable original = sheet.getBackground();
            int tint = tintFor(original);
            ORIGINALS.put(sheet, new Drawable[]{original});
            sheet.setBackground(Glass.pill(sheet.getContext(),
                    Ui.dp(sheet.getContext(), 28), tint));
            L.i("drawer glass: applied, tint #" + Integer.toHexString(tint));
        } catch (Throwable t) {
            L.d("drawer glass: could not apply (" + t + ")");
        }
    }

    private static void restore(View sheet) {
        Drawable[] original = ORIGINALS.remove(sheet);
        if (original != null) {
            sheet.setBackground(original[0]);
        }
    }

    /**
     * A glass tint in the sheet's own tone.
     *
     * <p>Light sheet, light glass; dark sheet, dark glass. The drawer's labels were coloured for
     * the sheet it had, and this is what keeps them readable without repainting a recycler's worth
     * of views that scroll in and out from under us.
     */
    private static int tintFor(Drawable original) {
        int base = sample(original);
        if (base == 0) {
            // Nothing to sample - no background at all, or one that drew nothing. Dark is the
            // safer guess under a taskbar that is itself dark.
            return 0xB0202024;
        }
        double luminance = (0.299 * Color.red(base) + 0.587 * Color.green(base)
                + 0.114 * Color.blue(base)) / 255.0;
        return luminance > 0.5 ? 0xB8F2F3F7 : 0xB0202024;
    }

    /** The drawable's colour, by drawing it into one pixel - works for any kind of drawable. */
    private static int sample(Drawable drawable) {
        if (drawable == null) {
            return 0;
        }
        if (drawable instanceof ColorDrawable) {
            return ((ColorDrawable) drawable).getColor();
        }
        try {
            Bitmap pixel = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(pixel);
            drawable.setBounds(0, 0, 1, 1);
            drawable.draw(canvas);
            int colour = pixel.getPixel(0, 0);
            pixel.recycle();
            return Color.alpha(colour) < 16 ? 0 : colour;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static String describe(Drawable drawable) {
        return drawable == null ? "none" : drawable.getClass().getSimpleName();
    }
}
