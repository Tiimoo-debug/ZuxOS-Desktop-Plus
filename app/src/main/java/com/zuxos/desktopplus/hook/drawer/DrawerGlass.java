package com.zuxos.desktopplus.hook.drawer;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;
import com.zuxos.desktopplus.core.Ui;
import com.zuxos.desktopplus.core.glass.Blur;
import com.zuxos.desktopplus.core.glass.Glass;
import com.zuxos.desktopplus.core.glass.GlassBackdrop;
import com.zuxos.desktopplus.core.glass.LiquidGlass;
import com.zuxos.desktopplus.hook.Windows;
import com.zuxos.desktopplus.logic.GlassPick;
import com.zuxos.desktopplus.logic.ToneMath;

import java.util.ArrayList;
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
 * <p>Three rounds were spent guessing which view that is, by name and then by a fixed size
 * threshold, and every one of them went quiet: the log said the window held the sheet and nothing
 * was ever glazed. So nothing is guessed here any more. Every view in the drawer's window is
 * weighed on what actually matters - is it big enough to be the pane, and is its background opaque
 * enough to be what hides a blur - the biggest one wins, and every candidate it weighed is written
 * to the log. A miss now names itself instead of going silent.
 *
 * <p>The one thing that must not be guessed either is the tone. The drawer's own labels are painted
 * for whatever colour the sheet was, and a dark pane under dark text is unreadable. So the original
 * background is sampled - drawn into a single pixel, which works whatever kind of drawable it is -
 * and the glass is built light or dark to match.
 */
public final class DrawerGlass {

    /** How deep into a window the sheet can be. Beyond this are list rows, not panes. */
    private static final int MAX_DEPTH = 8;

    /** What each view we repainted had before, so it can be put back exactly. */
    private static final Map<View, Drawable> ORIGINALS = new WeakHashMap<>();
    /** Windows being watched for their sheet to appear. */
    private static final Map<View, ViewTreeObserver.OnGlobalLayoutListener> WATCHED =
            new WeakHashMap<>();
    /** Windows already glazed, so a later layout does not weigh the same tree again. */
    private static final Map<View, Boolean> DONE = new WeakHashMap<>();

    /** Windows whose candidates have been written down once. */
    private static final java.util.Set<String> DESCRIBED = new java.util.HashSet<>();

    private DrawerGlass() {
    }

    /** Called for every window the launcher opens; the drawer is one of them. */
    public static void onWindowAdded(View root) {
        if (!(root instanceof ViewGroup)) {
            return;
        }
        try {
            if (!on()) {
                restore((ViewGroup) root);
                unwatch(root);
                return;
            }
            if (!couldHoldTheDrawer(root)) {
                return;
            }
            // The window arrives empty - the log showed the drag layer with no children at all -
            // and is filled a moment later, so one look is never enough.
            watch(root);
            root.post(() -> scan((ViewGroup) root));
        } catch (Throwable t) {
            L.d("drawer glass: could not look at this window (" + t + ")");
        }
    }

    private static boolean on() {
        return Cfg.drawerGlass() && Cfg.glass();
    }

    /**
     * Only the window that can hold a drawer.
     *
     * <p>A listener on every launcher window would walk that window's whole tree on every layout
     * it ever does, on the UI thread, for windows that will never contain one.
     */
    private static boolean couldHoldTheDrawer(View root) {
        String name = root.getClass().getSimpleName();
        return name.contains("Overlay") || name.contains("AllApps");
    }

    /**
     * Waits for the drawer to be put into its window.
     *
     * <p>On the view tree rather than on the root itself: a layout listener on the root fires only
     * when the root's own bounds change, and the root is the window - it is the same size before
     * and after the sheet is put into it. That is what kept this quiet.
     */
    private static void watch(View root) {
        if (WATCHED.containsKey(root)) {
            return;
        }
        ViewTreeObserver.OnGlobalLayoutListener listener = new ViewTreeObserver
                .OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                if (!on()) {
                    restore((ViewGroup) root);
                    unwatch(root);
                    return;
                }
                if (scan((ViewGroup) root)) {
                    unwatch(root);
                }
            }
        };
        root.post(() -> {
            try {
                root.getViewTreeObserver().addOnGlobalLayoutListener(listener);
                WATCHED.put(root, listener);
                forgetOnDetach(root, WATCHED);
            } catch (Throwable t) {
                L.d("drawer glass: could not watch this window (" + t + ")");
            }
        });
    }

    private static void unwatch(View root) {
        ViewTreeObserver.OnGlobalLayoutListener listener = WATCHED.remove(root);
        if (listener == null) {
            return;
        }
        try {
            root.getViewTreeObserver().removeOnGlobalLayoutListener(listener);
        } catch (Throwable ignored) {
            // The tree is gone, which is the same outcome.
        }
    }

    // --- choosing the pane --------------------------------------------------

    /**
     * One view and what was measured about it, so a rejection can say why.
     *
     * <p>The view is all this adds: the decision itself is {@link GlassPick}, which is four numbers
     * and no Android at all, so it can be tested without a device - which is what this choosing
     * going quietly wrong three times in a row earned it.
     */
    private static final class Candidate {
        final View view;
        final GlassPick.Pane pane;
        final int colour;

        Candidate(View view, int order, int width, int height, int colour) {
            this.view = view;
            this.pane = new GlassPick.Pane(order, width, height, colour);
            this.colour = colour;
        }

        @Override
        public String toString() {
            String id = Reflect.idName(view);
            return view.getClass().getSimpleName() + (id != null ? "#" + id : "")
                    + " [" + view.getWidth() + "x" + view.getHeight() + "] bg="
                    + view.getBackground().getClass().getSimpleName()
                    + " #" + Integer.toHexString(colour);
        }
    }

    /**
     * Weighs everything in the window and glazes the pane, if it is there yet.
     *
     * @return true once the window has been dealt with and need not be watched any more
     */
    private static boolean scan(ViewGroup window) {
        try {
            if (Boolean.TRUE.equals(DONE.get(window))) {
                return true;
            }
            long area = (long) window.getWidth() * window.getHeight();
            if (area <= 0 || window.getChildCount() == 0) {
                // Nothing laid out yet. Every view is nought by nought and every test passes,
                // which would pick whatever came first and glaze it for good.
                return false;
            }
            List<Candidate> candidates = new ArrayList<>();
            collect(window, 0, candidates);
            // A pane's order is its place in this list, so the choice comes back as an index.
            Candidate best = candidateFor(candidates, GlassPick.sheet(panesOf(candidates), area));
            describe(window, candidates, best, area);
            if (best == null || !apply(best.view, window, candidates, best)) {
                // Not there yet, or it would not take. Either way this window stays watched:
                // giving up here is how the last three rounds ended in silence.
                return false;
            }
            DONE.put(window, Boolean.TRUE);
            stopAtTheBar(window);
            return true;
        } catch (Throwable t) {
            L.d("drawer glass: could not weigh this window (" + t + ")");
            return false;
        }
    }

    /** Drawer windows clipped to end at the taskbar, and the listener keeping that current. */
    private static final Map<View, View.OnLayoutChangeListener> CLIPPED = new WeakHashMap<>();

    /**
     * Ends the drawer's drawing where the taskbar begins, on the desktop's screen.
     *
     * <p>ZUI's sheet, its scrim and the next row of its apps all carry on underneath the bar,
     * where ZUI's own opaque bar hid them. Under our see-through one they showed: a pale band
     * across the bar and stray icons beside the open apps. Only drawing is clipped; touches are
     * untouched.
     */
    /**
     * Drops a window's entry once the window is gone. The listener kept as the entry's value
     * holds the window itself, so a weak map alone never let go of it: every drawer opened on
     * the monitor - a new window each time - stayed in memory.
     */
    private static void forgetOnDetach(View window, Map<View, ?> map) {
        window.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                v.removeOnAttachStateChangeListener(this);
                map.remove(v);
            }
        });
    }

    private static void stopAtTheBar(ViewGroup window) {
        if (CLIPPED.containsKey(window)) {
            return;
        }
        android.view.Display display = window.getDisplay();
        if (display == null || display.getDisplayId() == android.view.Display.DEFAULT_DISPLAY) {
            // The tablet's own bar is the launcher's, opaque, and none of ours.
            return;
        }
        View.OnLayoutChangeListener listener =
                (v, l, t, r, b, ol, ot, or, ob) -> clipAboveTheBar(window);
        window.addOnLayoutChangeListener(listener);
        CLIPPED.put(window, listener);
        forgetOnDetach(window, CLIPPED);
        clipAboveTheBar(window);
        noWindowBlur(window);
    }

    // --- the launcher's own blur ---------------------------------------------

    /** Drawer windows whose whole-window blur is being kept off, so only the sheet blurs. */
    private static final Map<View, Boolean> BLUR_FREE =
            java.util.Collections.synchronizedMap(new WeakHashMap<>());
    /** What the window's own blur-behind settings were, to put back. */
    private static final Map<View, int[]> BLUR_PARAMS = new WeakHashMap<>();
    private static boolean sBlurHooked;
    private static boolean sSaidSurfaceBlur;
    private static boolean sSaidOtherBlur;

    /**
     * Takes ZUI's blur off the drawer's whole window.
     *
     * <p>ZUI blurs everything behind its drawer, edge to edge. With the sheet glazed, the sheet
     * blurred the same content by about the same amount, so it looked like no glass at all on
     * top of a blurred screen. Without the window blur, the screen behind is only dimmed and the
     * sheet is the one frosted thing on it. Android offers two ways to blur a window, and both
     * are covered, since which one ZUI uses cannot be seen from outside.
     */
    private static void noWindowBlur(ViewGroup window) {
        BLUR_FREE.put(window, Boolean.TRUE);
        hookSurfaceBlur();
        try {
            android.view.WindowManager wm = (android.view.WindowManager) window.getContext()
                    .getSystemService(android.content.Context.WINDOW_SERVICE);
            if (wm != null && android.os.Build.VERSION.SDK_INT >= 31) {
                L.i("drawer glass: cross-window blur is "
                        + (wm.isCrossWindowBlurEnabled() ? "enabled" : "DISABLED - the sheet "
                        + "cannot blur what is behind it on this device right now"));
            }
            if (!(window.getLayoutParams() instanceof android.view.WindowManager.LayoutParams)
                    || wm == null) {
                return;
            }
            android.view.WindowManager.LayoutParams lp =
                    (android.view.WindowManager.LayoutParams) window.getLayoutParams();
            int radius = android.os.Build.VERSION.SDK_INT >= 31 ? lp.getBlurBehindRadius() : 0;
            boolean flag = (lp.flags & android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                    != 0;
            if (!flag && radius <= 0) {
                return;
            }
            BLUR_PARAMS.put(window, new int[]{flag ? 1 : 0, radius});
            lp.flags &= ~android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                lp.setBlurBehindRadius(0);
            }
            wm.updateViewLayout(window, lp);
            L.i("drawer glass: took the launcher's window blur off the drawer (blur-behind "
                    + radius + "px" + (flag ? ", flag set" : "") + ") - only the sheet blurs now");
        } catch (Throwable t) {
            L.d("drawer glass: could not look at the drawer window's blur (" + t + ")");
        }
    }

    /**
     * The other way: the launcher setting a blur radius on the window's surface directly, as
     * Launcher3 does for its depth effect. Held at 0 for the drawer windows above, and only
     * those - every other blur the launcher sets goes through untouched.
     */
    private static void hookSurfaceBlur() {
        if (sBlurHooked) {
            return;
        }
        sBlurHooked = true;
        try {
            Class<?> tx = Class.forName("android.view.SurfaceControl$Transaction");
            int hooked = de.robv.android.xposed.XposedBridge.hookAllMethods(tx,
                    "setBackgroundBlurRadius", new de.robv.android.xposed.XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                onSurfaceBlur(param);
                            } catch (Throwable ignored) {
                                // Never in the way of a blur the launcher is setting.
                            }
                        }
                    }).size();
            L.d("drawer glass: watching surface blurs x" + hooked);
        } catch (Throwable t) {
            L.d("drawer glass: could not watch surface blurs (" + t + ")");
        }
    }

    private static void onSurfaceBlur(de.robv.android.xposed.XC_MethodHook.MethodHookParam param) {
        if (param.args.length < 2 || !(param.args[0] instanceof android.view.SurfaceControl)
                || !(param.args[1] instanceof Integer) || (Integer) param.args[1] <= 0
                || BLUR_FREE.isEmpty() || !on()) {
            return;
        }
        android.view.SurfaceControl target = (android.view.SurfaceControl) param.args[0];
        // Transactions are built on more than one thread; the set is read from a copy.
        List<View> windows;
        synchronized (BLUR_FREE) {
            windows = new ArrayList<>(BLUR_FREE.keySet());
        }
        for (View window : windows) {
            if (window == null) {
                continue;
            }
            android.view.SurfaceControl own = surfaceOf(window);
            if (own != null && sameSurface(own, target)) {
                int asked = (Integer) param.args[1];
                param.args[1] = 0;
                if (!sSaidSurfaceBlur) {
                    sSaidSurfaceBlur = true;
                    L.i("drawer glass: took the launcher's surface blur (" + asked
                            + "px) off the drawer window - only the sheet blurs now");
                }
                return;
            }
        }
        if (!sSaidOtherBlur) {
            sSaidOtherBlur = true;
            L.i("drawer glass: the launcher blurred another surface (" + param.args[1] + "px) "
                    + "from " + caller() + " - not the drawer's window, left alone");
        }
    }

    /** The same surface, by the framework's own test where it has one, else by identity. */
    private static boolean sameSurface(Object a, Object b) {
        if (a == b) {
            return true;
        }
        try {
            Object same = a.getClass().getMethod("isSameSurface", a.getClass()).invoke(a, b);
            return Boolean.TRUE.equals(same);
        } catch (Throwable t) {
            return false;
        }
    }

    private static android.view.SurfaceControl surfaceOf(View window) {
        try {
            Object root = View.class.getMethod("getViewRootImpl").invoke(window);
            Object surface = root == null ? null
                    : root.getClass().getMethod("getSurfaceControl").invoke(root);
            return surface instanceof android.view.SurfaceControl
                    ? (android.view.SurfaceControl) surface : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** The first frame of the launcher's own code on the stack, for the log. */
    private static String caller() {
        for (StackTraceElement e : new Throwable().getStackTrace()) {
            String c = e.getClassName();
            if (!c.startsWith("android.") && !c.startsWith("com.android.internal")
                    && !c.startsWith("java.") && !c.startsWith("de.robv")
                    && !c.startsWith("com.zuxos") && !c.startsWith("LSP")
                    && !c.startsWith("org.lsposed") && !c.startsWith("dalvik")) {
                return c + "." + e.getMethodName();
            }
        }
        return "unknown";
    }

    /** The window's own blur-behind, as it was. */
    private static void restoreWindowBlur(ViewGroup window) {
        BLUR_FREE.remove(window);
        int[] was = BLUR_PARAMS.remove(window);
        if (was == null || !(window.getLayoutParams()
                instanceof android.view.WindowManager.LayoutParams)) {
            return;
        }
        try {
            android.view.WindowManager wm = (android.view.WindowManager) window.getContext()
                    .getSystemService(android.content.Context.WINDOW_SERVICE);
            android.view.WindowManager.LayoutParams lp =
                    (android.view.WindowManager.LayoutParams) window.getLayoutParams();
            if (was[0] != 0) {
                lp.flags |= android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
            }
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                lp.setBlurBehindRadius(was[1]);
            }
            wm.updateViewLayout(window, lp);
        } catch (Throwable ignored) {
            // The window is gone, which is the same outcome.
        }
    }

    private static boolean sSaidClipped;

    private static void clipAboveTheBar(ViewGroup window) {
        try {
            android.view.Display display = window.getDisplay();
            if (display == null || window.getHeight() <= 0) {
                return;
            }
            int bar = Windows.taskbarHeight(display.getDisplayId());
            if (bar <= 0) {
                return;
            }
            android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
            display.getRealMetrics(metrics);
            int[] at = new int[2];
            window.getLocationOnScreen(at);
            // How much of the window lies on the bar: none, for a drawer that already ends above.
            int under = at[1] + window.getHeight() - (metrics.heightPixels - bar);
            android.graphics.Rect clip = under > 0
                    ? new android.graphics.Rect(0, 0, window.getWidth(), window.getHeight() - under)
                    : null;
            if (java.util.Objects.equals(clip, window.getClipBounds())) {
                return;
            }
            window.setClipBounds(clip);
            if (clip != null && !sSaidClipped) {
                sSaidClipped = true;
                L.i("drawer glass: the drawer now ends at the taskbar (" + under
                        + "px of it was under the bar)");
            }
        } catch (Throwable t) {
            L.d("drawer glass: could not stop the drawer at the bar (" + t + ")");
        }
    }

    /**
     * Every view worth weighing, in draw order.
     *
     * <p>Pre-order, so a view's index is greater than every view drawn before it - which is how
     * {@link #clearWhatIsDrawnOver} knows which backgrounds would paint over the glass. Lists are
     * not descended into: a row of a recycler is never the pane, and there can be hundreds of them.
     */
    private static void collect(View view, int depth, List<Candidate> out) {
        if (depth > MAX_DEPTH || out.size() > 200 || view.getVisibility() != View.VISIBLE) {
            // Nothing under a view that is not on screen is on screen either.
            return;
        }
        if (view.getBackground() != null && view.getWidth() > 0 && view.getHeight() > 0) {
            out.add(new Candidate(view, out.size(), view.getWidth(), view.getHeight(),
                    sample(view.getBackground())));
        }
        if (!(view instanceof ViewGroup) || isAList(view)) {
            return;
        }
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            collect(group.getChildAt(i), depth + 1, out);
        }
    }

    /** The numbers the decision is made on, in the order they were collected. */
    private static List<GlassPick.Pane> panesOf(List<Candidate> candidates) {
        List<GlassPick.Pane> panes = new ArrayList<>(candidates.size());
        for (Candidate c : candidates) {
            panes.add(c.pane);
        }
        return panes;
    }

    /** Back from a chosen pane to the view it came from; its order is its index. */
    private static Candidate candidateFor(List<Candidate> candidates, GlassPick.Pane pane) {
        if (pane == null || pane.order < 0 || pane.order >= candidates.size()) {
            return null;
        }
        return candidates.get(pane.order);
    }

    private static boolean isAList(View view) {
        String name = view.getClass().getName();
        return name.contains("RecyclerView") || name.contains("ListView")
                || name.contains("GridView");
    }

    /** What was weighed and what was chosen - once per kind of window. */
    private static void describe(ViewGroup window, List<Candidate> candidates, Candidate best,
            long area) {
        String key = window.getClass().getSimpleName() + ":" + candidates.size();
        if (candidates.isEmpty() || DESCRIBED.size() > 12 || !DESCRIBED.add(key)) {
            // Keyed on what the window held, so a tree that has changed is heard about once
            // more - but not on every layout of a window that keeps changing shape.
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (Candidate c : candidates) {
            if (sb.length() > 600) {
                sb.append(", ...");
                break;
            }
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(c);
            if (c == best) {
                sb.append(" <- the sheet");
            } else if (!c.pane.bigEnough(area)) {
                sb.append(" (wrong size)");
            } else if (!c.pane.opaque()) {
                sb.append(" (see-through already)");
            }
        }
        L.i("drawer glass: " + window.getClass().getSimpleName() + " is "
                + window.getWidth() + "x" + window.getHeight() + ", weighed " + sb);
    }

    // --- glazing ------------------------------------------------------------

    private static boolean apply(View sheet, ViewGroup window, List<Candidate> candidates,
            Candidate best) {
        try {
            if (ORIGINALS.containsKey(sheet)) {
                return true;
            }
            Drawable original = sheet.getBackground();
            int tint = tintFor(best.colour);
            ORIGINALS.put(sheet, original);
            float corner = cornerOf(original, sheet);
            // A sheet that reaches the bottom of the window is sitting on the screen edge: round
            // its top and leave the bottom square, or the blur cuts two notches out of it.
            boolean toTheEdge = bottomOf(sheet, window) >= window.getHeight() - Ui.dp(
                    sheet.getContext(), 2);
            float bottom = toTheEdge ? 0f : corner;
            if (GlassBackdrop.possible() && sheet instanceof ViewGroup) {
                liquid((ViewGroup) sheet, corner, toTheEdge, best.colour);
                clearWhatIsDrawnOver(candidates, best, (long) window.getWidth()
                        * window.getHeight());
                return true;
            }
            Drawable backdrop = Blur.backdrop(sheet, Ui.dp(sheet.getContext(), 40),
                    corner, corner, bottom, bottom, tint);
            if (backdrop != null) {
                sheet.setBackground(backdrop);
                clearWhatIsDrawnOver(candidates, best, (long) window.getWidth()
                        * window.getHeight());
                L.i("drawer glass: applied with a real blur to "
                        + sheet.getClass().getSimpleName() + ", tint #"
                        + Integer.toHexString(tint));
                return true;
            }
            // Nothing real behind it, so the pane has to carry itself: a 35%-alpha sheet over
            // the wallpaper is a smear with unreadable labels.
            int solid = (tint | 0xFF000000) & 0xB8FFFFFF;
            sheet.setBackground(Glass.pill(sheet.getContext(), (int) corner, solid));
            clearWhatIsDrawnOver(candidates, best, (long) window.getWidth() * window.getHeight());
            L.i("drawer glass: applied without blur, tint #" + Integer.toHexString(solid));
            return true;
        } catch (Throwable t) {
            L.d("drawer glass: could not apply (" + t + ")");
            return false;
        }
    }

    /**
     * Real liquid glass under the sheet: the screen behind the drawer's window, live, frosted
     * and bent at the rim - as the sheet's first child, under ZUI's own content.
     */
    private static void liquid(ViewGroup sheet, float corner, boolean toTheEdge, int colour) {
        // ZUI's own sheet colour, mostly opaque, until the first capture lands.
        GlassBackdrop glass = new GlassBackdrop(sheet.getContext(), LiquidGlass.THICK, corner,
                toTheEdge ? corner : 0f, LiquidGlass.tintFor(!ToneMath.isLight(colour)),
                (colour & 0x00FFFFFF) | 0xD9000000, 16L);
        sheet.addView(glass, 0, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        sheet.setBackground(null);
        glass.setLive(true, () -> {
            // Refused: the system blur, as before.
            sheet.removeView(glass);
            Drawable backdrop = Blur.backdrop(sheet, Ui.dp(sheet.getContext(), 60), corner,
                    corner, toTheEdge ? 0f : corner, toTheEdge ? 0f : corner, tintFor(colour));
            if (backdrop != null) {
                sheet.setBackground(backdrop);
            }
        });
        L.i("drawer glass: liquid glass over the live screen on "
                + sheet.getClass().getSimpleName());
    }

    /**
     * Takes the opaque backgrounds that would paint over the glass out of the way.
     *
     * <p>Glazing one layer while an opaque one on top of it still paints is indistinguishable from
     * doing nothing at all - which is exactly what the user has reported three rounds running. Only
     * what is drawn after the sheet counts: its own children, and the views after it in the
     * window's draw order. Anything behind it is hidden by the glass anyway and is left alone -
     * the drawer's scrim among it, which is the launcher's own dimming and not ours to remove.
     */
    private static void clearWhatIsDrawnOver(List<Candidate> candidates, Candidate best,
            long window) {
        for (GlassPick.Pane pane : GlassPick.drawnOver(panesOf(candidates), best.pane, window)) {
            Candidate c = candidateFor(candidates, pane);
            if (c == null) {
                continue;
            }
            ORIGINALS.put(c.view, c.view.getBackground());
            c.view.setBackground(null);
            L.i("drawer glass: cleared " + c.view.getClass().getSimpleName()
                    + ", which was painting over the glass");
        }
    }

    /** The sheet's own corner radius where the drawable will say, or a sensible one. */
    private static float cornerOf(Drawable original, View sheet) {
        try {
            if (original instanceof GradientDrawable) {
                float radius = ((GradientDrawable) original).getCornerRadius();
                if (radius > 0f) {
                    return radius;
                }
                float[] radii = ((GradientDrawable) original).getCornerRadii();
                if (radii != null && radii.length > 0 && radii[0] > 0f) {
                    return radii[0];
                }
            }
        } catch (Throwable ignored) {
            // Older shapes do not keep their radius where it can be read back.
        }
        return Ui.dp(sheet.getContext(), 28);
    }

    /** Where the view's bottom edge is, in the window's own coordinates. */
    private static int bottomOf(View view, ViewGroup window) {
        int bottom = view.getHeight();
        for (View v = view; v != null && v != window; ) {
            bottom += v.getTop();
            v = v.getParent() instanceof View ? (View) v.getParent() : null;
        }
        return bottom;
    }

    /** Puts a window back exactly as the launcher built it. */
    static void restore(ViewGroup window) {
        DONE.remove(window);
        View.OnLayoutChangeListener clipper = CLIPPED.remove(window);
        if (clipper != null) {
            window.removeOnLayoutChangeListener(clipper);
            window.setClipBounds(null);
        }
        restoreWindowBlur(window);
        View glass = window.findViewWithTag(GlassBackdrop.TAG);
        if (glass != null && glass.getParent() instanceof ViewGroup) {
            ((ViewGroup) glass.getParent()).removeView(glass);
        }
        if (ORIGINALS.isEmpty()) {
            // Nothing was ever repainted, and this runs for every window the launcher opens -
            // walking each of their trees to find nothing would be the expensive way to do that.
            return;
        }
        for (View view : Reflect.findByClassFragments(window, "")) {
            Drawable original = ORIGINALS.remove(view);
            if (original != null) {
                view.setBackground(original);
            }
        }
    }

    /**
     * A glass tint in the sheet's own tone.
     *
     * <p>Light sheet, light glass; dark sheet, dark glass. The drawer's labels were coloured for
     * the sheet it had, and this is what keeps them readable without repainting a recycler's worth
     * of views that scroll in and out from under us.
     */
    private static int tintFor(int base) {
        if (base == 0) {
            // Nothing to sample. Dark is the safer guess under a taskbar that is itself dark.
            return 0x59202024;
        }
        // Enough to keep the drawer's own labels readable, little enough to see through.
        return ToneMath.isLight(base) ? 0x73F2F3F7 : 0x59202024;
    }

    /** The drawable's colour, by drawing it into one pixel - works for any kind of drawable. */
    private static int sample(Drawable drawable) {
        if (drawable == null) {
            return 0;
        }
        if (drawable instanceof ColorDrawable) {
            return ((ColorDrawable) drawable).getColor();
        }
        android.graphics.Rect bounds = new android.graphics.Rect(drawable.getBounds());
        try {
            // Eight pixels rather than one, and the middle of them: a rounded sheet drawn into a
            // single pixel is a circle the size of that pixel, and its antialiased edge would
            // read back as half transparent on a background that is not.
            Bitmap patch = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(patch);
            drawable.setBounds(0, 0, 8, 8);
            drawable.draw(canvas);
            int colour = patch.getPixel(4, 4);
            patch.recycle();
            return colour;
        } catch (Throwable t) {
            return 0;
        } finally {
            // Put them back. These are the launcher's own drawables and a view only re-bounds
            // its background when its size changes - so a view sampled and then left alone
            // would have been left with a one-pixel background.
            drawable.setBounds(bounds);
        }
    }
}
