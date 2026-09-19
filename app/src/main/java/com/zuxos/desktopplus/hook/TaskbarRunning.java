package com.zuxos.desktopplus.hook;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Ui;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * A taskbar of what is actually open.
 *
 * <p>The launcher's own switch for this turned out to be inert here - {@code
 * setCanShowRunningApps(true)} reads back true and the controller shows nothing, because on this
 * firmware ZUI fills the bar itself with the pinned hotseat. So the bar is driven from here
 * instead: the apps that are not open are hidden, and the ones that are open but not pinned are
 * added.
 *
 * <p>Everything is reversible and nothing is destroyed. Hiding is a visibility change on the
 * launcher's own icons, remembered so it can be put back; the added icons are ours and are
 * removed with the setting. Turning it off restores the bar the launcher built.
 */
final class TaskbarRunning {

    /** How often the list is re-read while the taskbar is up. */
    private static final long REFRESH_MS = 3000L;

    /** Icons the launcher put there which we have hidden, so they can be shown again. */
    private static final Map<View, Boolean> HIDDEN = new WeakHashMap<>();

    /** The last list we acted on. Written on the UI thread, but published for safety. */
    private static volatile Set<String> sShowing = new LinkedHashSet<>();
    private static int sSource = -1;
    /** Per taskbar: with two displays, one ticking must not stand for the other. */
    private static final Map<View, Boolean> TICKING = new WeakHashMap<>();

    private TaskbarRunning() {
    }

    /** Called whenever a taskbar is (re)attached, and then on its own every few seconds. */
    static void apply(ViewGroup dragLayer) {
        if (!Cfg.taskbarRunningOnly()) {
            restore(dragLayer);
            return;
        }
        // Armed first, and before the row is even looked for: the row may not exist yet and the
        // list can start answering later than the first look at it. A feature that only engages
        // when something else happens to refresh the taskbar is not a feature.
        tick(dragLayer);
        ViewGroup icons = iconRow(dragLayer);
        if (icons == null) {
            return;
        }
        Set<String> running = running(dragLayer.getContext(), icons);
        if (running.isEmpty()) {
            // Nothing readable: better to leave the launcher's bar alone than to empty it.
            restore(dragLayer);
            return;
        }
        hideWhatIsNotOpen(icons, running);
        extras(dragLayer, icons, running);
    }

    /**
     * The row of app icons, by name.
     *
     * <p>Not {@code rowReference}, which answers "the row the tray should line up with" and will
     * happily give back the navigation buttons - and putting app icons in among back and home is
     * not a mistake worth risking for one shared helper.
     */
    private static ViewGroup iconRow(ViewGroup dragLayer) {
        for (View view : com.zuxos.desktopplus.core.Reflect.findByIdNames(dragLayer,
                "taskbar_view")) {
            if (view instanceof ViewGroup) {
                return (ViewGroup) view;
            }
        }
        return null;
    }

    /**
     * Puts the taskbar back exactly as the launcher built it.
     *
     * <p>The whole taskbar, not just its icon row: our own row of extra icons lives in the drag
     * layer, and unhiding the launcher's icons while leaving ours floating over the bar would be
     * a worse state than either.
     */
    static void restore(ViewGroup dragLayer) {
        RunningRow row = rowIn(dragLayer);
        if (row != null) {
            dragLayer.removeView(row);
        }
        ViewGroup icons = iconRow(dragLayer);
        if (icons == null) {
            return;
        }
        for (int i = icons.getChildCount() - 1; i >= 0; i--) {
            View child = icons.getChildAt(i);
            if (HIDDEN.remove(child) != null) {
                child.setVisibility(View.VISIBLE);
            }
        }
    }

    private static void hideWhatIsNotOpen(ViewGroup icons, Set<String> running) {
        for (int i = icons.getChildCount() - 1; i >= 0; i--) {
            View child = icons.getChildAt(i);
            String pkg = packageOf(child);
            if (pkg == null) {
                // The all-apps button and anything else without an app behind it stays.
                continue;
            }
            if (running.contains(pkg)) {
                if (HIDDEN.remove(child) != null) {
                    child.setVisibility(View.VISIBLE);
                }
            } else if (child.getVisibility() == View.VISIBLE) {
                HIDDEN.put(child, Boolean.TRUE);
                child.setVisibility(View.GONE);
            }
        }
    }

    /**
     * The open apps the launcher has no icon for, in a row of our own.
     *
     * <p>This used to put them in the launcher's row, and that crash-looped the launcher 62
     * times: {@code TaskbarView} animates its children through a property that casts every one of
     * them to {@code Reorderable}, so a plain image view there kills the process the moment the
     * row animates - which adding one is itself what triggers. The row lays out a foreign child
     * perfectly well; it is what happens afterwards that does not.
     *
     * <p>So nothing of ours is ever its child. These go in the drag layer beside the tray, the
     * same way and by the same means the tray itself does, where the launcher's animations never
     * see them.
     */
    private static void extras(ViewGroup dragLayer, ViewGroup icons, Set<String> running) {
        Set<String> missing = new LinkedHashSet<>(running);
        for (int i = 0; i < icons.getChildCount(); i++) {
            String pkg = packageOf(icons.getChildAt(i));
            if (pkg != null) {
                missing.remove(pkg);
            }
        }
        // The launcher itself is the desktop, not an app you switch back to.
        missing.remove(dragLayer.getContext().getPackageName());

        RunningRow row = rowIn(dragLayer);
        if (missing.isEmpty()) {
            if (row != null) {
                dragLayer.removeView(row);
            }
            return;
        }
        if (row == null) {
            row = addRow(dragLayer);
            if (row == null) {
                return;
            }
        }
        if (missing.equals(row.mShowing)) {
            return;
        }
        row.removeAllViews();
        int size = iconSize(icons);
        Set<String> shown = new LinkedHashSet<>();
        for (String pkg : missing) {
            View icon = iconFor(dragLayer.getContext(), pkg, size,
                    TaskbarTray.displayIdOf(dragLayer));
            if (icon != null) {
                row.addView(icon);
                shown.add(pkg);
            }
        }
        // What went in, not what was asked for: an app whose icon could not be drawn would
        // otherwise be remembered as shown and never tried again.
        row.mShowing = shown;
    }

    private static RunningRow rowIn(ViewGroup dragLayer) {
        for (int i = 0; i < dragLayer.getChildCount(); i++) {
            if (dragLayer.getChildAt(i) instanceof RunningRow) {
                return (RunningRow) dragLayer.getChildAt(i);
            }
        }
        return null;
    }

    /** Puts our row in the drag layer, lined up with the bar the way the tray is. */
    private static RunningRow addRow(ViewGroup dragLayer) {
        try {
            View reference = TaskbarTray.rowReference(dragLayer);
            ViewGroup.LayoutParams lp = TaskbarTray.dragLayerParams(dragLayer, reference);
            if (!(lp instanceof android.widget.FrameLayout.LayoutParams)) {
                L.w("taskbar running: the drag layer's layout params are not reproducible, so "
                        + "open apps that are not pinned cannot be shown");
                return null;
            }
            RunningRow row = new RunningRow(dragLayer.getContext());
            row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            dragLayer.addView(row, lp);
            place(dragLayer, row, reference);
            if (reference != null) {
                // Placed again on every layout of the bar. At the moment the row is added the
                // tray beside it has usually not been measured yet, so its width is nought and
                // the margin meant to clear it clears nothing - the row would sit on top of the
                // clock until something else moved it.
                reference.addOnLayoutChangeListener(
                        (v, l, t, r, b, ol, ot, or, ob) -> place(dragLayer, row, reference));
            }
            L.i("taskbar running: a row of our own for open apps that are not pinned");
            return row;
        } catch (Throwable t) {
            L.e("taskbar running: could not add our row", t);
            return null;
        }
    }

    /** Lines the row up with the bar, clear of the tray. */
    private static void place(ViewGroup dragLayer, RunningRow row, View reference) {
        try {
            ViewGroup.LayoutParams raw = row.getLayoutParams();
            if (!(raw instanceof android.widget.FrameLayout.LayoutParams)) {
                return;
            }
            android.widget.FrameLayout.LayoutParams lp =
                    (android.widget.FrameLayout.LayoutParams) raw;
            lp.rightMargin = TaskbarTray.trayWidth(dragLayer) + Ui.dp(dragLayer.getContext(), 8);
            if (reference != null && reference.getHeight() > 0) {
                lp.gravity = android.view.Gravity.TOP | android.view.Gravity.END;
                lp.height = reference.getHeight();
                lp.topMargin = reference.getTop();
            } else {
                // Without the row's geometry, the bottom of the drag layer is the bar; the top
                // of it is a row of icons floating in the middle of the screen.
                lp.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.END;
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                lp.topMargin = 0;
            }
            row.setLayoutParams(lp);
        } catch (Throwable t) {
            L.d("taskbar running: could not place our row (" + t + ")");
        }
    }

    /** Ours, and never a child of the launcher's icon row. */
    private static final class RunningRow extends android.widget.LinearLayout {
        Set<String> mShowing = new LinkedHashSet<>();

        RunningRow(Context ctx) {
            super(ctx);
        }
    }

    private static String packageOf(View icon) {
        Object info = icon.getTag();
        if (info == null) {
            return null;
        }
        Object direct = com.zuxos.desktopplus.core.Reflect.field(info, "packageName");
        if (direct instanceof String && !((String) direct).isEmpty()) {
            return (String) direct;
        }
        Object intent = com.zuxos.desktopplus.core.Reflect.field(info, "intent");
        if (intent instanceof Intent && ((Intent) intent).getComponent() != null) {
            return ((Intent) intent).getComponent().getPackageName();
        }
        return null;
    }

    // --- what is open ------------------------------------------------------

    /**
     * The packages that are open.
     *
     * <p>Two ways of asking, and root is deliberately not one of them. Reading the task list
     * through the shell would mean a root round trip every few seconds for as long as the
     * taskbar is up - which would raise a prompt unasked, and, since one root request at a time
     * is the rule here, would leave the quick settings toggles failing at random. A feature
     * that quietly breaks another one is not worth having.
     *
     * <p>So: the activity manager, which a system launcher is usually allowed to ask and an
     * ordinary app is not; and failing that the launcher's own recent-apps controller, which
     * will say whether a given icon is running even though it does not fill this bar. The
     * second only knows about icons that are already there, so with it the bar can be narrowed
     * but not added to - which is said plainly in the log rather than silently half-done.
     */
    private static Set<String> running(Context ctx, ViewGroup icons) {
        Set<String> out = fromActivityManager(ctx);
        if (!out.isEmpty()) {
            source(0, "the activity manager");
            sShowing = out;
            return out;
        }
        out = fromLauncher(icons);
        if (!out.isEmpty()) {
            source(1, "the launcher's own running-app state (open apps that are not pinned "
                    + "cannot be added this way)");
            sShowing = out;
            return out;
        }
        source(2, "nowhere - neither the activity manager nor the launcher will say what is "
                + "open, so the taskbar is left alone");
        return out;
    }

    private static void source(int which, String what) {
        if (sSource != which) {
            sSource = which;
            L.i("taskbar running: reading what is open from " + what);
        }
    }

    /**
     * The task list, where this launcher is allowed to see it.
     *
     * <p>{@code getRunningTasks} is refused to ordinary apps - they get their own task and
     * nothing else, which is why one result counts as no answer rather than as one app.
     */
    private static Set<String> fromActivityManager(Context ctx) {
        Set<String> out = new LinkedHashSet<>();
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) {
                return out;
            }
            List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(25);
            if (tasks == null) {
                return out;
            }
            for (ActivityManager.RunningTaskInfo task : tasks) {
                if (task.baseActivity != null) {
                    out.add(task.baseActivity.getPackageName());
                }
            }
            if (out.size() <= 1) {
                out.clear();
            }
        } catch (Throwable t) {
            L.d("taskbar running: the activity manager will not list tasks (" + t + ")");
        }
        return out;
    }

    /**
     * Asks the launcher which of its own icons are running.
     *
     * <p>{@code getRunningAppState} is on the controller the probe found; it answers per item
     * even on a firmware where that controller does not fill the bar. The values it returns are
     * logged the first time, because which of them means "not running" is the whole question.
     */
    private static Set<String> fromLauncher(ViewGroup icons) {
        Set<String> out = new LinkedHashSet<>();
        Object controller = TaskbarApps.recentAppsController();
        if (controller == null) {
            return out;
        }
        for (int i = 0; i < icons.getChildCount(); i++) {
            View child = icons.getChildAt(i);
            String pkg = packageOf(child);
            if (pkg == null) {
                continue;
            }
            Object state = com.zuxos.desktopplus.core.Reflect.call(
                    controller, "getRunningAppState", child.getTag());
            if (state == null) {
                // Not "this app is not running" - no answer at all, which for the first icon
                // means the method is not there. Hiding every icon on the strength of that
                // would empty the taskbar over a failed reflection call.
                return new LinkedHashSet<>();
            }
            if (!state.getClass().isEnum()) {
                // The states are named constants; anything else and the test below is reading
                // tea leaves, so the whole source is declined rather than half-trusted.
                if (sStates.add("?")) {
                    L.i("taskbar running: the launcher answers with "
                            + state.getClass().getName() + ", which cannot be read as a state");
                }
                return new LinkedHashSet<>();
            }
            String name = String.valueOf(state);
            if (sStates.add(name)) {
                L.i("taskbar running: the launcher calls one of these states '" + name + "'");
            }
            if (!name.toUpperCase(java.util.Locale.US).contains("NOT")) {
                out.add(pkg);
            }
        }
        return out;
    }

    private static final Set<String> sStates = new LinkedHashSet<>();

    // --- our own icons -----------------------------------------------------

    /** The size the launcher's own icons are, so ours are not the odd ones out. */
    private static int iconSize(ViewGroup icons) {
        for (int i = 0; i < icons.getChildCount(); i++) {
            View child = icons.getChildAt(i);
            if (child.getWidth() > 0 && packageOf(child) != null) {
                return child.getWidth();
            }
        }
        return Ui.dp(icons.getContext(), 44);
    }

    private static View iconFor(Context ctx, String pkg, int size, int displayId) {
        try {
            PackageManager pm = ctx.getPackageManager();
            Drawable art = pm.getApplicationIcon(pkg);
            ImageView view = new ImageView(ctx);
            view.setImageDrawable(art);
            int inset = Math.max(1, size / 8);
            view.setPadding(inset, inset, inset, inset);
            view.setContentDescription(pm.getApplicationLabel(
                    pm.getApplicationInfo(pkg, 0)).toString());
            view.setBackground(Ui.ripple(ctx, 0x00000000, size / 2));
            view.setOnClickListener(v -> {
                try {
                    Intent intent = pm.getLaunchIntentForPackage(pkg);
                    if (intent == null) {
                        return;
                    }
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(intent, QuickTiles.launchOptions(displayId));
                } catch (Throwable t) {
                    L.d("taskbar running: could not open " + pkg + " (" + t + ")");
                }
            });
            view.setLayoutParams(new android.widget.LinearLayout.LayoutParams(size, size));
            return view;
        } catch (Throwable t) {
            // An app we cannot draw is an app we leave out.
            return null;
        }
    }

    // --- keeping up -------------------------------------------------------

    private static void tick(ViewGroup dragLayer) {
        if (Boolean.TRUE.equals(TICKING.get(dragLayer))) {
            return;
        }
        TICKING.put(dragLayer, Boolean.TRUE);
        dragLayer.postDelayed(new Runnable() {
            @Override
            public void run() {
                TICKING.remove(dragLayer);
                if (dragLayer.getParent() == null || !Cfg.taskbarRunningOnly()) {
                    return;
                }
                apply(dragLayer);
            }
        }, REFRESH_MS);
    }
}
