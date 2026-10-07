package com.zuxos.desktopplus.hook;

import android.graphics.Rect;
import android.graphics.Region;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Our pieces of a taskbar going along with ZUI's own, instead of ZUI being held open for them.
 *
 * <p><b>Hiding.</b> On the tablet's regular bar ZUI hides its icon row whenever the bar is
 * switched off or stashed - gesture navigation stashes it into a handle. Our row, start button,
 * tray, marks and glass are the drag layer's own children, which that never touches, so they
 * stayed where the bar had been. Just before each frame of the bar, they take the row's fade and
 * its slide; while ZUI's drawer is open they stay put, so the start button is there to close it.
 *
 * <p><b>Touch.</b> When ZUI limits the bar's window to a touchable region - its own icons, in the
 * states where the rest of the bar is meant to let touches through - our icons fell outside it,
 * and taps on them went to the app behind: the bar "stopped responding". The region is widened by
 * whichever of ours are visible, right after ZUI computes it, and by nothing while they are not.
 */
final class TaskbarFollow {

    /** Per bar: what was installed where, and what the pieces were last set to. */
    private static final Map<View, State> STATES = new WeakHashMap<>();

    private static Class<?> sInsetsListener;
    private static Method sAddInsets;
    private static Method sRemoveInsets;
    private static Field sTouchableInsets;
    private static Field sTouchableRegion;
    private static boolean sInsetsUnavailable;
    private static boolean sSaidFollow;
    private static final Map<View, Boolean> TOUCH_SAID = new WeakHashMap<>();

    /** {@code InternalInsetsInfo.TOUCHABLE_INSETS_REGION}. */
    private static final int TOUCHABLE_REGION = 3;

    private TaskbarFollow() {
    }

    private static final class State {
        ViewTreeObserver observer;
        ViewTreeObserver.OnPreDrawListener preDraw;
        Object insets;
        float alpha = 1f;
        float shift = 0f;
        String lastDescribed;
        int described;
        int transitions;
        /** ZUI's icon row, found once rather than searched for on every frame. */
        java.lang.ref.WeakReference<View> row;
        /** ZUI's navigation keys, and where they were last drawn across the bar. */
        java.lang.ref.WeakReference<View> keys;
        boolean keysLooked;
        int keysLeft = Integer.MIN_VALUE;
        Runnable relayout;
        /** Why ZUI's icons are hidden, one channel per reason; null when unreadable. */
        Channels channels;
        boolean channelsLooked;
    }

    /**
     * The taskbar's icon fade as ZUI keeps it: one value per reason - its home screen, the lock
     * screen, the bar stashed or switched off, recents, the shade - multiplied together.
     *
     * <p>Ours follow every reason but home. On its own home ZUI fades only its icons, handing them
     * to the home screen, while the bar and its keys stay; following that fade made our open apps
     * and start button vanish there, and stop taking taps.
     */
    private static final class Channels {
        final Object[] values;
        final Field value;
        /** Each channel's name, from its {@code ALPHA_INDEX_*} constant; null where unnamed. */
        final String[] names;
        /** The channels that mean "ZUI's home": not followed. */
        final boolean[] home;

        Channels(Object[] values, Field value, String[] names, boolean[] home) {
            this.values = values;
            this.value = value;
            this.names = names;
            this.home = home;
        }

        /** Every reason but home, multiplied; NaN if a value cannot be read. */
        float allButHome() {
            return product(true);
        }

        /** Every reason, home included: what ZUI's icon row itself is faded to. */
        float all() {
            return product(false);
        }

        /**
         * Every reason but home and stashing: what holds ours down on the tablet's home and
         * Recents. ZUI stashes its bar for its Recents; ours stay there, as over an app.
         */
        float onHome() {
            float product = 1f;
            try {
                for (int i = 0; i < values.length; i++) {
                    if (!home[i] && !"STASH".equals(names[i]) && values[i] != null) {
                        product *= value.getFloat(values[i]);
                    }
                }
                return product;
            } catch (Throwable t) {
                return Float.NaN;
            }
        }

        private float product(boolean skipHome) {
            float product = 1f;
            try {
                for (int i = 0; i < values.length; i++) {
                    if (!(skipHome && home[i]) && values[i] != null) {
                        product *= value.getFloat(values[i]);
                    }
                }
                return product;
            } catch (Throwable t) {
                return Float.NaN;
            }
        }

        /** The channels followed that are holding ours down at the moment, by name. */
        String why() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < values.length; i++) {
                try {
                    float v = values[i] == null ? 1f : value.getFloat(values[i]);
                    if (!home[i] && v < 0.999f) {
                        sb.append(sb.length() == 0 ? "" : ", ").append(nameOf(i)).append('=')
                                .append(v);
                    }
                } catch (Throwable t) {
                    sb.append('?');
                }
            }
            return sb.length() == 0 ? "no channel" : sb.toString();
        }

        String nameOf(int i) {
            return names[i] != null ? names[i] : String.valueOf(i);
        }

        String describe() {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < values.length; i++) {
                try {
                    sb.append(i == 0 ? "" : ", ").append(home[i] ? "home " : "")
                            .append(values[i] == null ? "-" : value.getFloat(values[i]));
                } catch (Throwable t) {
                    sb.append("?");
                }
            }
            return sb.append(']').toString();
        }
    }

    /** On a bar of ours; again whenever the bar is refreshed, which re-installs after a re-attach. */
    static void install(ViewGroup dragLayer) {
        ViewTreeObserver observer = dragLayer.getViewTreeObserver();
        State state = STATES.get(dragLayer);
        if (state != null && state.observer == observer && observer.isAlive()) {
            if (state.keys == null) {
                // Navigation switched from gestures to keys since: look for them again.
                state.keysLooked = false;
            }
            // Back to the end of the line: a bar that registers its own touch region after ours
            // - one built on a window context may, each time it shows - would otherwise
            // overwrite what ours adds, and our icons would take no taps.
            if (state.insets != null) {
                try {
                    sRemoveInsets.invoke(observer, state.insets);
                    sAddInsets.invoke(observer, state.insets);
                } catch (Throwable ignored) {
                    // Left where it was.
                }
            }
            return;
        }
        if (state != null) {
            detach(state);
        }
        state = new State();
        state.observer = observer;
        if (TaskbarScope.tablet(dragLayer)) {
            State held = state;
            state.preDraw = () -> {
                follow(dragLayer, held);
                return true;
            };
            observer.addOnPreDrawListener(state.preDraw);
            if (!sSaidFollow) {
                sSaidFollow = true;
                L.i("taskbar follow: ours hide, stash and come back with the tablet's own bar");
            }
        }
        state.insets = insetsListener(dragLayer);
        if (state.insets != null) {
            try {
                sAddInsets.invoke(observer, state.insets);
            } catch (Throwable t) {
                state.insets = null;
                L.d("taskbar follow: no touch region (" + t + ")");
            }
        }
        STATES.put(dragLayer, state);
    }

    private static void detach(State state) {
        ViewTreeObserver observer = state.observer;
        if (observer == null || !observer.isAlive()) {
            return;
        }
        if (state.preDraw != null) {
            observer.removeOnPreDrawListener(state.preDraw);
        }
        if (state.insets != null) {
            try {
                sRemoveInsets.invoke(observer, state.insets);
            } catch (Throwable ignored) {
                // Gone with the observer.
            }
        }
    }

    // --- hiding ------------------------------------------------------------------------------

    /** Just before a frame: our pieces take the row's fade and slide, unless the drawer is up. */
    private static void follow(ViewGroup dragLayer, State state) {
        View row = state.row != null ? state.row.get() : null;
        if (row == null || !isUnder(row, dragLayer)) {
            row = iconRow(dragLayer);
            if (row == null) {
                return;
            }
            state.row = new java.lang.ref.WeakReference<>(row);
        }
        watchKeys(dragLayer, state);
        // ZUI hides a bar by fading or sliding its icon row, or by fading and sliding each icon
        // in it; its drawer button - invisible, ours stands in for it - still gets the latter.
        View zui = row instanceof ViewGroup ? TaskbarStart.allAppsButton((ViewGroup) row) : null;
        float alpha;
        float shift;
        float reasons = channelsOf(dragLayer, state);
        if (!Float.isNaN(reasons) && row.getAlpha() < state.channels.all() - 0.02f) {
            // Faded further than its channels say: ZUI is hiding the row some other way - the
            // bar switched off - so ours follow the row itself, as they always did.
            reasons = Float.NaN;
        }
        if (Float.isNaN(reasons)) {
            alpha = row.isShown() ? row.getAlpha() : 0f;
            shift = row.getTranslationY();
            if (zui != null) {
                alpha *= zui.getAlpha();
                shift += zui.getTranslationY();
            }
        } else {
            // Every reason ZUI has to hide its icons, except its home screen.
            alpha = row.isShown() ? reasons : 0f;
            if (zui != null) {
                alpha *= zui.getAlpha();
            }
            // On its home ZUI slides its icons towards the home screen's; ours stay put, and
            // slide only when the bar itself is going.
            shift = reasons >= 0.999f ? 0f : row.getTranslationY()
                    + (zui != null ? zui.getTranslationY() : 0f);
        }
        if (keyboardUp(dragLayer)) {
            // The keyboard comes up over where the bar is; ours go, as ZUI's icons do.
            alpha = 0f;
        }
        boolean home = onTabletHome(dragLayer);
        if (home && !keyboardUp(dragLayer)) {
            // ZUI's home or Recents on the tablet - its own, or Lawnchair's home: ZUI hides or
            // stashes its own icon row there, ours stay, as in apps.
            float held = state.channels != null ? state.channels.onHome() : Float.NaN;
            alpha = Float.isNaN(held) ? 1f : held;
            shift = 0f;
        }
        describe(dragLayer, state, row, zui);
        if ((alpha < 0.999f || shift != 0f)
                && TaskbarStart.drawerOpen(TaskbarTray.displayIdOf(dragLayer))) {
            // ZUI hides its row for its drawer; ours stays, so the start button can close it.
            alpha = 1f;
            shift = 0f;
        }
        float parked = 0f;
        if (alpha > 0f && alpha < SNAP) {
            // ZUI parks its bar at a few percent rather than at nothing - stashed for the
            // keyboard it rests near 5%. Ours there were invisible yet still counted as touchable,
            // and the taskbar's window took the keyboard's bottom row. Below a tenth they are
            // gone, and take nothing; a fade in or out passes through too fast to see the step.
            parked = alpha;
            alpha = 0f;
            shift = 0f;
        }
        if (alpha == state.alpha && shift == state.shift) {
            // Unchanged - but a piece put in since (a row added while the bar was stashed) still
            // stands at full alpha over the app and would take its taps. A dozen field reads.
            if (strayed(dragLayer, alpha, shift)) {
                apply(dragLayer, alpha, shift);
            }
            return;
        }
        boolean hidden = alpha < 0.01f;
        if (hidden != state.alpha < 0.01f && state.transitions < 40) {
            // Each time ours go or come back, and which of ZUI's reasons did it.
            state.transitions++;
            L.i("taskbar follow: " + TaskbarScope.label(dragLayer) + " ours "
                    + (hidden ? "hide" : "show") + " - "
                    + (keyboardUp(dragLayer) ? "keyboard up"
                    : parked > 0f ? "parked by ZUI at " + parked
                    : state.channels != null ? state.channels.why() : "the row's fade"));
        }
        state.alpha = alpha;
        state.shift = shift;
        apply(dragLayer, alpha, shift);
    }

    /**
     * One line per change in how ZUI shows a tablet bar, a few dozen at most per bar: what each
     * of its hiding channels reads. Enough to see from a log alone how it hides for the keyboard.
     */
    private static void describe(ViewGroup dragLayer, State state, View row, View zui) {
        if (state.described >= 20) {
            return;
        }
        String line = "row " + row.getVisibility() + "/" + row.getAlpha() + "/"
                + row.getTranslationY()
                + (zui != null ? " button " + zui.getAlpha() + "/" + zui.getTranslationY() : "")
                + " layer " + dragLayer.getAlpha() + "/" + dragLayer.getTranslationY()
                + " window " + dragLayer.getWindowVisibility()
                + (state.channels != null ? " channels " + state.channels.describe() : "")
                + (keyboardUp(dragLayer) ? " keyboard up" : "");
        if (!line.equals(state.lastDescribed)) {
            state.lastDescribed = line;
            state.described++;
            L.i("taskbar follow: " + TaskbarScope.label(dragLayer) + " " + line);
        }
    }

    /**
     * Re-places our start button and row when ZUI moves its navigation keys - it slides them
     * from one end of the tablet's bar to the other, between its home and an app - so ours never
     * end up under them. Measured each frame from where they are drawn, which is a few field
     * reads; acted on only when they moved.
     */
    private static void watchKeys(ViewGroup dragLayer, State state) {
        View keys = state.keys != null ? state.keys.get() : null;
        if (keys == null || !isUnder(keys, dragLayer)) {
            if (state.keysLooked && keys == null) {
                // None on this bar - gesture navigation. Looked for again on the next install.
                return;
            }
            state.keysLooked = true;
            keys = TaskbarStart.navKeys(dragLayer);
            state.keys = keys != null ? new java.lang.ref.WeakReference<>(keys) : null;
            if (keys == null) {
                return;
            }
        }
        int left = TaskbarStart.drawnLeftIn(dragLayer, keys);
        if (left == state.keysLeft) {
            return;
        }
        boolean first = state.keysLeft == Integer.MIN_VALUE;
        state.keysLeft = left;
        if (first) {
            return;
        }
        // Once the keys have come to rest, not on every frame of their slide: each relayout is a
        // search of the bar and a layout pass. Every move pushes it back a little.
        if (state.relayout == null) {
            state.relayout = () -> TaskbarRunning.relayout(dragLayer);
        }
        dragLayer.removeCallbacks(state.relayout);
        dragLayer.postDelayed(state.relayout, KEYS_SETTLE_MS);
    }

    private static final long KEYS_SETTLE_MS = 50L;

    /** Below this ours count as gone: drawn at nothing and taking no touches. */
    private static final float SNAP = 0.1f;

    /**
     * Whether this is the tablet's bar with the home screen or Recents in front - the launcher's
     * own package, which Recents is part of, or the default home. ZUI shows no icons of its
     * own there; ours show as they do over an app. Only while the bar's window is up: on the
     * lock screen it is not.
     */
    private static boolean onTabletHome(ViewGroup dragLayer) {
        int display = TaskbarTray.displayIdOf(dragLayer);
        if (display != android.view.Display.DEFAULT_DISPLAY || !dragLayer.isShown()
                || dragLayer.getWindowVisibility() != View.VISIBLE) {
            return false;
        }
        String front = TaskbarRunning.frontPackage(display);
        return front != null && isHome(dragLayer.getContext(), front);
    }

    /** The launcher itself, or whatever is the default home now - Lawnchair, some days. */
    private static boolean isHome(android.content.Context ctx, String pkg) {
        if (pkg.equals(ctx.getPackageName())) {
            return true;
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (sHomePkg == null || now - sHomeAt > 30_000L) {
            sHomeAt = now;
            try {
                android.content.pm.ResolveInfo home = ctx.getPackageManager().resolveActivity(
                        new android.content.Intent(android.content.Intent.ACTION_MAIN)
                                .addCategory(android.content.Intent.CATEGORY_HOME), 0);
                sHomePkg = home != null && home.activityInfo != null
                        ? home.activityInfo.packageName : "";
            } catch (Throwable t) {
                sHomePkg = "";
            }
        }
        return pkg.equals(sHomePkg);
    }

    private static String sHomePkg;
    private static long sHomeAt;

    /**
     * ZUI's icon fade with its home screen left out, or NaN when ZUI's channels cannot be read -
     * then the row's own fade is followed, as before.
     */
    private static float channelsOf(ViewGroup dragLayer, State state) {
        if (!state.channelsLooked) {
            state.channelsLooked = true;
            state.channels = findChannels(dragLayer);
            L.i("taskbar follow: " + TaskbarScope.label(dragLayer) + " - "
                    + (state.channels == null
                    ? "ZUI's icon channels unreadable, following the row's fade"
                    : "following ZUI's icon channels but home " + state.channels.describe()));
        }
        return state.channels == null ? Float.NaN : state.channels.allButHome();
    }

    /** {@code TaskbarViewController}'s icon alpha, through the bar's controllers. */
    private static Channels findChannels(ViewGroup dragLayer) {
        try {
            Object controllers = null;
            for (android.content.Context ctx = dragLayer.getContext(); ctx != null
                    && controllers == null; ) {
                controllers = Reflect.field(ctx, "mControllers");
                ctx = ctx instanceof android.content.ContextWrapper
                        ? ((android.content.ContextWrapper) ctx).getBaseContext() : null;
            }
            Object view = controllers == null ? null
                    : Reflect.field(controllers, "taskbarViewController");
            if (view == null) {
                return null;
            }
            // The icon alpha: the multi-value alpha with the most channels.
            Object[] best = null;
            Object bestHolder = null;
            for (Field f : view.getClass().getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())
                        || !isMultiValue(f.getType())) {
                    continue;
                }
                f.setAccessible(true);
                Object holder = f.get(view);
                Object[] values = holder == null ? null : valuesOf(holder);
                if (values != null && (best == null || values.length > best.length)) {
                    best = values;
                    bestHolder = holder;
                }
            }
            if (best == null || best.length == 0 || best[0] == null) {
                return null;
            }
            Field value = valueField(best, String.valueOf(bestHolder));
            if (value == null) {
                return null;
            }
            // Each channel by name: ZUI has more of them than Launcher3, and more than one of
            // its own is about its home screen. Every one whose name says HOME is left out.
            String[] names = new String[best.length];
            boolean[] home = new boolean[best.length];
            StringBuilder map = new StringBuilder();
            for (Field f : view.getClass().getDeclaredFields()) {
                if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())
                        || f.getType() != int.class || !f.getName().startsWith("ALPHA_INDEX_")) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    int i = f.getInt(null);
                    if (i >= 0 && i < names.length) {
                        String name = f.getName().substring("ALPHA_INDEX_".length());
                        names[i] = name;
                        home[i] = name.contains("HOME");
                        map.append(' ').append(i).append('=').append(name);
                    }
                } catch (Throwable ignored) {
                    // One name less.
                }
            }
            if (map.length() == 0) {
                // Unnamed on this build: Launcher3 has always put home first.
                home[0] = true;
            }
            L.i("taskbar follow: " + TaskbarScope.label(dragLayer) + " channels"
                    + (map.length() == 0 ? " unnamed, home taken as 0" : map));
            return new Channels(best, value, names, home);
        } catch (Throwable t) {
            L.d("taskbar follow: no icon channels (" + t + ")");
            return null;
        }
    }

    /**
     * The float each channel keeps its value in. Names are minified, so it is checked against
     * the values the holder prints of itself - "[1.0, 0.0, 1.0]" - and the first float field
     * is taken only when that cannot be read.
     */
    private static Field valueField(Object[] values, String printed) {
        java.util.List<Float> shown = new java.util.ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("-?\\d+(\\.\\d+)?(E-?\\d+)?")
                .matcher(printed);
        while (m.find()) {
            try {
                shown.add(Float.parseFloat(m.group()));
            } catch (NumberFormatException ignored) {
                // Not one of the values.
            }
        }
        Field first = null;
        for (Class<?> c = values[0].getClass(); c != null && c != Object.class;
                c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType() != float.class
                        || java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                f.setAccessible(true);
                if (first == null) {
                    first = f;
                }
                if (shown.size() != values.length) {
                    continue;
                }
                boolean matches = true;
                try {
                    for (int i = 0; i < values.length && matches; i++) {
                        matches = values[i] != null
                                && Math.abs(f.getFloat(values[i]) - shown.get(i)) < 0.001f;
                    }
                } catch (Throwable t) {
                    matches = false;
                }
                if (matches) {
                    return f;
                }
            }
        }
        return shown.size() == values.length ? null : first;
    }

    private static boolean isMultiValue(Class<?> type) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            String name = c.getSimpleName();
            if (name.equals("MultiValueAlpha") || name.equals("MultiPropertyFactory")) {
                return true;
            }
        }
        return false;
    }

    /** The per-reason values inside a multi-value alpha: its one array of objects. */
    private static Object[] valuesOf(Object holder) throws IllegalAccessException {
        for (Class<?> c = holder.getClass(); c != null && c != Object.class;
                c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType().isArray() && !f.getType().getComponentType().isPrimitive()
                        && !java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    f.setAccessible(true);
                    Object array = f.get(holder);
                    if (array instanceof Object[]) {
                        return (Object[]) array;
                    }
                }
            }
        }
        return null;
    }

    private static boolean keyboardUp(View view) {
        android.view.WindowInsets insets = view.getRootWindowInsets();
        return insets != null && insets.isVisible(android.view.WindowInsets.Type.ime());
    }

    private static void apply(ViewGroup dragLayer, float alpha, float shift) {
        for (int i = 0; i < dragLayer.getChildCount(); i++) {
            View child = dragLayer.getChildAt(i);
            if (!isOurs(child)) {
                continue;
            }
            child.setAlpha(alpha);
            child.setTranslationY(shift);
        }
    }

    /** Whether any piece of ours is not at the fade and slide the others are at. */
    private static boolean strayed(ViewGroup dragLayer, float alpha, float shift) {
        for (int i = 0; i < dragLayer.getChildCount(); i++) {
            View child = dragLayer.getChildAt(i);
            if (isOurs(child) && (child.getAlpha() != alpha
                    || child.getTranslationY() != shift)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isOurs(View child) {
        return child.getClass().getName().startsWith("com.zuxos.desktopplus.");
    }

    /** Whether {@code view} is still attached somewhere inside {@code group}. */
    private static boolean isUnder(View view, ViewGroup group) {
        if (!view.isAttachedToWindow()) {
            return false;
        }
        for (Object p = view.getParent(); p instanceof View; p = ((View) p).getParent()) {
            if (p == group) {
                return true;
            }
        }
        return false;
    }

    private static View iconRow(ViewGroup dragLayer) {
        List<View> found = Reflect.findByIdNames(dragLayer, "taskbar_view");
        return found.isEmpty() ? null : found.get(0);
    }

    // --- touch -------------------------------------------------------------------------------

    /** A listener of the hidden insets interface, made at run time, or null when it cannot be. */
    private static Object insetsListener(ViewGroup dragLayer) {
        if (!bindInsets()) {
            return null;
        }
        return Proxy.newProxyInstance(TaskbarFollow.class.getClassLoader(),
                new Class<?>[]{sInsetsListener}, (self, method, args) -> {
                    switch (method.getName()) {
                        case "onComputeInternalInsets":
                            if (args != null && args.length == 1) {
                                widen(dragLayer, args[0]);
                            }
                            return null;
                        case "equals":
                            return self == (args != null && args.length > 0 ? args[0] : null);
                        case "hashCode":
                            return System.identityHashCode(self);
                        case "toString":
                            return "ZuxDesktopPlus taskbar touch";
                        default:
                            return null;
                    }
                });
    }

    private static synchronized boolean bindInsets() {
        if (sInsetsListener != null) {
            return true;
        }
        if (sInsetsUnavailable) {
            return false;
        }
        try {
            sInsetsListener = Class.forName(
                    "android.view.ViewTreeObserver$OnComputeInternalInsetsListener");
            Class<?> info = Class.forName("android.view.ViewTreeObserver$InternalInsetsInfo");
            sAddInsets = ViewTreeObserver.class.getMethod("addOnComputeInternalInsetsListener",
                    sInsetsListener);
            sRemoveInsets = ViewTreeObserver.class.getMethod(
                    "removeOnComputeInternalInsetsListener", sInsetsListener);
            sTouchableInsets = info.getDeclaredField("mTouchableInsets");
            sTouchableInsets.setAccessible(true);
            sTouchableRegion = info.getField("touchableRegion");
            return true;
        } catch (Throwable t) {
            sInsetsListener = null;
            sInsetsUnavailable = true;
            L.i("taskbar follow: the bar's touch region cannot be reached (" + t + ")");
            return false;
        }
    }

    /** Right after ZUI set the bar's touchable region: ours are added where they are showing. */
    private static void widen(ViewGroup dragLayer, Object info) {
        try {
            if (sTouchableInsets.getInt(info) != TOUCHABLE_REGION) {
                // The whole window takes touches already, or none of it is meant to.
                return;
            }
            Region region = (Region) sTouchableRegion.get(info);
            if (region == null) {
                return;
            }
            Rect zuis = region.getBounds();
            int[] at = new int[2];
            boolean added = false;
            for (View piece : new View[]{TaskbarRunning.scrollerOf(dragLayer),
                    TaskbarStart.buttonIn(dragLayer), TaskbarTray.trayOf(dragLayer)}) {
                if (piece == null || !piece.isShown() || piece.getAlpha() < SNAP
                        || piece.getWidth() <= 0) {
                    continue;
                }
                piece.getLocationInWindow(at);
                region.op(at[0], at[1], at[0] + piece.getWidth(), at[1] + piece.getHeight(),
                        Region.Op.UNION);
                added = true;
            }
            if (added && TOUCH_SAID.put(dragLayer, Boolean.TRUE) == null) {
                View start = TaskbarStart.buttonIn(dragLayer);
                String startIn = "no start button";
                if (start != null && start.getWidth() > 0) {
                    start.getLocationInWindow(at);
                    startIn = "start button " + (region.contains(at[0] + start.getWidth() / 2,
                            at[1] + start.getHeight() / 2) ? "inside" : "OUTSIDE");
                }
                L.i("taskbar follow: " + TaskbarScope.label(dragLayer) + " - ZUI limited its"
                        + " touch to " + zuis + "; with ours " + startIn);
            }
        } catch (Throwable t) {
            L.d("taskbar follow: could not widen the touch region (" + t + ")");
        }
    }
}
