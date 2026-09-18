package com.zuxos.desktopplus.hook;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;

import com.zuxos.desktopplus.core.Blur;
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

    /**
     * Names the drawer's sheet may go under.
     *
     * <p>Wider than it looks like it needs to be, because the first attempt guessed Launcher3's
     * own names and found nothing at all on this firmware - which means ZUI has named its own.
     * Both the class names and the view ids are tried, and what the window actually contained is
     * written to the log, so a build that matches none of these can be named from a log rather
     * than from another guess.
     */
    private static final String[] SHEET_CLASSES = {
            "AllAppsSlideInView", "AllAppsContainerView", "AppsContainerView", "AllAppsView",
            "AppsSlideInView", "AllAppsSheet", "AppDrawer", "DrawerContainer", "AppsView",
    };

    private static final String[] SHEET_IDS = {
            "apps_view", "all_apps", "apps_list_view", "search_container_all_apps",
            "apps_container", "all_apps_sheet",
    };

    /** What each glazed sheet had before, so it can be put back exactly. */
    private static final Map<View, Drawable[]> ORIGINALS = new WeakHashMap<>();
    /** Windows being watched for their sheet to appear. */
    private static final Map<View, View.OnLayoutChangeListener> WATCHED = new WeakHashMap<>();

    private static final java.util.Set<String> SEEN = new java.util.HashSet<>();

    private static boolean sDescribed;

    private DrawerGlass() {
    }

    /** Called for every window the launcher opens; the drawer is one of them. */
    static void onWindowAdded(View root) {
        if (!(root instanceof ViewGroup)) {
            return;
        }
        try {
            List<View> found = sheetsIn(root);
            if (found.isEmpty()) {
                // This is the ordinary case, not a failure: the log said the drawer's window -
                // a TaskbarOverlayDragLayer - arrives with no children at all, because the sheet
                // is put into it afterwards. So the window is watched instead of inspected once.
                watch(root);
                note(root);
                return;
            }
            View sheet = pick(found);
            if (sheet == null) {
                // Matched by name but none of them owns a background - which is worth hearing
                // about, since it means the pane you can see is something else again.
                note(root);
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
    private static List<View> sheetsIn(View root) {
        List<View> found = Reflect.findByClassFragments(root, SHEET_CLASSES);
        if (found.isEmpty()) {
            found = Reflect.findByIdNames(root, SHEET_IDS);
        }
        return found;
    }

    /**
     * Waits for the drawer to be put into its window.
     *
     * <p>The window is created empty and filled a moment later, so a single look finds nothing.
     * A layout listener costs nothing while the window is idle and takes itself off as soon as
     * the sheet has been glazed.
     */
    private static void watch(View root) {
        if (!(root instanceof ViewGroup) || WATCHED.containsKey(root)) {
            return;
        }
        if (!(Cfg.drawerGlass() && Cfg.glass())) {
            return;
        }
        // Only the window that can hold one. A listener on every launcher window would run two
        // recursive scans of that window's whole tree on every layout it ever does, on the UI
        // thread, for windows that will never contain a drawer.
        String name = root.getClass().getSimpleName();
        if (!name.contains("Overlay") && !name.contains("AllApps")) {
            return;
        }
        View.OnLayoutChangeListener listener = new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b,
                    int ol, int ot, int or, int ob) {
                View sheet = pick(sheetsIn(v));
                if (sheet == null) {
                    return;
                }
                v.removeOnLayoutChangeListener(this);
                WATCHED.remove(v);
                L.i("drawer glass: the sheet turned up - " + sheet.getClass().getSimpleName());
                apply(sheet);
            }
        };
        root.addOnLayoutChangeListener(listener);
        WATCHED.put(root, listener);
    }

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
            float corner = Ui.dp(sheet.getContext(), 28);
            Drawable backdrop = Blur.backdrop(sheet, Ui.dp(sheet.getContext(), 40), corner, tint);
            if (backdrop != null) {
                sheet.setBackground(backdrop);
                L.i("drawer glass: applied with a real blur, tint #" + Integer.toHexString(tint));
                return;
            }
            // Nothing real behind it, so the pane has to carry itself: a 35%-alpha sheet over
            // the wallpaper is a smear with unreadable labels.
            int solid = (tint | 0xFF000000) & 0xB8FFFFFF;
            sheet.setBackground(Glass.pill(sheet.getContext(), (int) corner, solid));
            L.i("drawer glass: applied without blur, tint #" + Integer.toHexString(solid));
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
            return 0x59202024;
        }
        double luminance = (0.299 * Color.red(base) + 0.587 * Color.green(base)
                + 0.114 * Color.blue(base)) / 255.0;
        // Enough to keep the drawer's own labels readable, little enough to see through.
        return luminance > 0.5 ? 0x73F2F3F7 : 0x59202024;
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

    /**
     * Writes down what a window held when nothing matched.
     *
     * <p>Once per class of window. This is how the drawer gets named on a firmware whose views
     * are called something nobody has guessed.
     */
    private static void note(View root) {
        if (root.getClass().getName().startsWith("com.zuxos")) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (View child : Reflect.findByClassFragments(root, "View", "Layout", "Group")) {
            if (sb.length() > 240) {
                break;
            }
            String simple = child.getClass().getSimpleName();
            if (simple.isEmpty() || sb.indexOf(simple) >= 0) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(simple);
        }
        // Keyed on what the window holds, not on its class: every launcher window is a
        // DecorView, so keying on that would spend the one slot on the first window opened and
        // never describe the drawer at all.
        String description = root.getClass().getSimpleName() + " [" + sb + "]";
        if (SEEN.size() < 8 && SEEN.add(description)) {
            L.i("drawer glass: no sheet in " + description);
        }
    }

    private static String describe(Drawable drawable) {
        return drawable == null ? "none" : drawable.getClass().getSimpleName();
    }
}
