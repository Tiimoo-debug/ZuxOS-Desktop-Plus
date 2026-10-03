package com.zuxos.desktopplus.hook;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;
import com.zuxos.desktopplus.core.Ui;
import com.zuxos.desktopplus.logic.RunningOrder;

import java.util.ArrayList;
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
 * <p>Ours go in a row of our own, because nothing of ours may be a child of {@code TaskbarView} -
 * that crash-looped the launcher 62 times, since it animates its children through a property that
 * casts every one of them to {@code Reorderable}. But a row of our own parked out by the tray is
 * what made the bar look like two taskbars, so it is placed where it belongs instead: hard against
 * the last icon the launcher is showing, at the launcher's own icon size and the launcher's own
 * spacing, so the whole thing reads as one row.
 *
 * <p>Everything is reversible and nothing is destroyed. Hiding is a visibility change on the
 * launcher's own icons, remembered so it can be put back; the added icons are ours and are
 * removed with the setting.
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
        Set<String> running = running(dragLayer.getContext(), icons,
                TaskbarTray.displayIdOf(dragLayer));
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
        for (View view : Reflect.findByIdNames(dragLayer, "taskbar_view")) {
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
            String pkg = IconInfo.packageOfView(child);
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
     * <p>This used to put them in the launcher's row, and that crash-looped the launcher: {@code
     * TaskbarView} animates its children through a property that casts every one of them to
     * {@code Reorderable}, so a plain image view there kills the process the moment the row
     * animates - which adding one is itself what triggers. So nothing of ours is ever its child.
     */
    private static void extras(ViewGroup dragLayer, ViewGroup icons, Set<String> running) {
        Set<String> missing = new LinkedHashSet<>(running);
        for (int i = 0; i < icons.getChildCount(); i++) {
            String pkg = IconInfo.packageOfView(icons.getChildAt(i));
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
        int size = iconSize(icons);
        int gap = spacing(icons);
        List<String> wanted = RunningOrder.trimToFit(
                RunningOrder.inOrder(row.mShowing, missing), room(dragLayer), size, gap);
        if (wanted.equals(row.mShowing)) {
            return;
        }
        row.removeAllViews();
        List<String> shown = new ArrayList<>();
        for (String pkg : wanted) {
            View icon = iconFor(dragLayer.getContext(), pkg, size,
                    TaskbarTray.displayIdOf(dragLayer));
            if (icon == null) {
                continue;
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.leftMargin = row.getChildCount() == 0 ? 0 : gap;
            row.addView(icon, lp);
            shown.add(pkg);
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
            if (!(lp instanceof FrameLayout.LayoutParams)) {
                L.w("taskbar running: the drag layer's layout params are not reproducible, so "
                        + "open apps that are not pinned cannot be shown");
                return null;
            }
            RunningRow row = new RunningRow(dragLayer.getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            dragLayer.addView(row, lp);
            place(dragLayer, row, reference);
            watchGeometry(dragLayer, reference);
            L.i("taskbar running: a row of our own, beside the launcher's, for open apps that "
                    + "are not pinned");
            return row;
        } catch (Throwable t) {
            L.e("taskbar running: could not add our row", t);
            return null;
        }
    }

    /**
     * Follows the launcher's own row wherever it goes.
     *
     * <p>Its icons are centred in a full-width row, so every icon hidden or shown moves the edge
     * ours has to sit against - and when the row is first added nothing has been measured at all.
     * One listener per taskbar, which finds the current row rather than holding one: ours comes
     * and goes with whether there is anything to put in it, and a listener per row would pile up
     * a dead one every time it went.
     */
    private static void watchGeometry(ViewGroup dragLayer, View reference) {
        if (WATCHING.containsKey(dragLayer)) {
            return;
        }
        View.OnLayoutChangeListener again = (v, l, t, r, b, ol, ot, or, ob) -> {
            RunningRow current = rowIn(dragLayer);
            if (current != null) {
                place(dragLayer, current, reference);
            }
        };
        if (reference != null) {
            reference.addOnLayoutChangeListener(again);
        }
        View icons = iconRow(dragLayer);
        if (icons != null && icons != reference) {
            icons.addOnLayoutChangeListener(again);
        }
        WATCHING.put(dragLayer, again);
    }

    /** One geometry listener per taskbar, whether or not our row is up at the moment. */
    private static final Map<View, View.OnLayoutChangeListener> WATCHING = new WeakHashMap<>();

    /**
     * Lines the row up with the launcher's icons.
     *
     * <p>Hard against the right-hand end of them, at the gap the launcher leaves between two of
     * its own, so that the bar reads as one row rather than as two clusters with a hole between
     * them. Which is what it looked like when this was measured from the tray instead.
     */
    private static void place(ViewGroup dragLayer, RunningRow row, View reference) {
        try {
            ViewGroup.LayoutParams raw = row.getLayoutParams();
            if (!(raw instanceof FrameLayout.LayoutParams)) {
                return;
            }
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) raw;
            int gravity;
            int height;
            int top;
            if (reference != null && reference.getHeight() > 0) {
                gravity = Gravity.TOP;
                height = reference.getHeight();
                top = reference.getTop();
            } else {
                // Without the row's geometry, the bottom of the drag layer is the bar; the top
                // of it is a row of icons floating in the middle of the screen.
                gravity = Gravity.BOTTOM;
                height = ViewGroup.LayoutParams.WRAP_CONTENT;
                top = 0;
            }
            int edge = leftEdge(dragLayer);
            int left;
            int right;
            if (edge >= 0) {
                gravity |= Gravity.START;
                left = edge;
                right = 0;
            } else {
                // No icons to sit beside - a bar of navigation buttons only. Beside the tray is
                // then the only place left that is not on top of something else.
                gravity |= Gravity.END;
                left = 0;
                right = TaskbarTray.trayWidth(dragLayer) + Ui.dp(dragLayer.getContext(), 8);
            }
            if (lp.gravity == gravity && lp.height == height && lp.topMargin == top
                    && lp.leftMargin == left && lp.rightMargin == right) {
                // Nothing moved. This runs from a layout listener, and setting layout params
                // asks for another layout whether or not they changed - which would be a pass
                // per frame, for ever, over a row that was already in the right place.
                return;
            }
            lp.gravity = gravity;
            lp.height = height;
            lp.topMargin = top;
            lp.leftMargin = left;
            lp.rightMargin = right;
            row.setLayoutParams(lp);
        } catch (Throwable t) {
            L.d("taskbar running: could not place our row (" + t + ")");
        }
    }

    /** Where our row starts: just past the last icon the launcher is showing. */
    private static int leftEdge(ViewGroup dragLayer) {
        ViewGroup icons = iconRow(dragLayer);
        if (icons == null || icons.getWidth() <= 0
                || icons.getVisibility() != View.VISIBLE) {
            return -1;
        }
        int edge = -1;
        for (int i = 0; i < icons.getChildCount(); i++) {
            View child = icons.getChildAt(i);
            if (child.getVisibility() == View.VISIBLE && child.getWidth() > 0) {
                edge = Math.max(edge, child.getRight());
            }
        }
        if (edge < 0) {
            return -1;
        }
        return edge + offsetIn(dragLayer, icons) + spacing(icons);
    }

    /** How much room there is between the launcher's icons and the tray. */
    private static int room(ViewGroup dragLayer) {
        int left = leftEdge(dragLayer);
        if (dragLayer.getWidth() <= 0 || left < 0) {
            return 0;
        }
        return dragLayer.getWidth() - TaskbarTray.trayWidth(dragLayer) - left;
    }

    /** How far a view's left edge is from the drag layer's. */
    private static int offsetIn(ViewGroup dragLayer, View view) {
        int left = 0;
        for (View v = view; v != null && v != dragLayer; ) {
            left += v.getLeft();
            v = v.getParent() instanceof View ? (View) v.getParent() : null;
        }
        return left;
    }

    /** The gap the launcher leaves between two of its own icons, so ours matches it. */
    private static int spacing(ViewGroup icons) {
        View previous = null;
        for (int i = 0; i < icons.getChildCount(); i++) {
            View child = icons.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE || child.getWidth() <= 0) {
                continue;
            }
            if (previous != null) {
                int gap = child.getLeft() - previous.getRight();
                if (gap > 0 && gap <= Ui.dp(icons.getContext(), 64)) {
                    return gap;
                }
            }
            previous = child;
        }
        return Ui.dp(icons.getContext(), 12);
    }

    /** Ours, and never a child of the launcher's icon row. */
    private static final class RunningRow extends LinearLayout {
        /** In the order they are on screen, which is the order that has to hold still. */
        List<String> mShowing = new ArrayList<>();

        RunningRow(Context ctx) {
            super(ctx);
        }
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
    private static Set<String> running(Context ctx, ViewGroup icons, int displayId) {
        Set<String> out = fromActivityManager(ctx, displayId);
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
     *
     * <p>What comes back is task <em>records</em>, which outlive the app that made them: without
     * the two tests below, everything the user had open this week would be in the bar. Both read
     * fields the framework keeps but does not publish, so both are skipped where they cannot be
     * read rather than guessed at.
     */
    private static Set<String> fromActivityManager(Context ctx, int displayId) {
        Set<String> out = new LinkedHashSet<>();
        Set<String> everything = new LinkedHashSet<>();
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
                if (task.baseActivity == null) {
                    continue;
                }
                describeTaskFields(task);
                String pkg = task.baseActivity.getPackageName();
                everything.add(pkg);
                if (isOpen(task) && onDisplay(task, displayId)) {
                    out.add(pkg);
                }
            }
            if (everything.size() <= 1) {
                return new LinkedHashSet<>();
            }
            if (out.isEmpty()) {
                // The tests threw everything away, which means they are reading something other
                // than what they are named after on this build. A bar of every task is wrong;
                // an empty one is worse.
                if (sSaidUnfiltered.add("")) {
                    L.i("taskbar running: nothing survived the running/display tests, so every "
                            + "task in the list counts");
                }
                return everything;
            }
        } catch (Throwable t) {
            L.d("taskbar running: the activity manager will not list tasks (" + t + ")");
        }
        return out;
    }

    private static final Set<String> sSaidUnfiltered = new LinkedHashSet<>();
    private static boolean sSaidFields;

    /** Which of the task list's unpublished fields this build actually lets us read. */
    private static void describeTaskFields(Object task) {
        if (sSaidFields) {
            return;
        }
        sSaidFields = true;
        L.i("taskbar running: the task list says isRunning="
                + Reflect.field(task, "isRunning") + ", isVisible="
                + Reflect.field(task, "isVisible") + ", displayId="
                + Reflect.field(task, "displayId"));
    }

    /**
     * Whether a task is an app that is open, rather than a record of one that was.
     *
     * <p>Visibility is not the test: an app minimised on the desktop is exactly what a taskbar is
     * for, and it is not visible. Running is the test, and where the build will not say, every
     * task counts - which is where this started.
     */
    private static boolean isOpen(Object task) {
        Object visible = Reflect.field(task, "isVisible");
        if (visible instanceof Boolean && (Boolean) visible) {
            return true;
        }
        Object running = Reflect.field(task, "isRunning");
        if (running instanceof Boolean) {
            return (Boolean) running;
        }
        return true;
    }

    /** This bar belongs to one display; an app on the tablet screen is not on it. */
    private static boolean onDisplay(Object task, int displayId) {
        if (displayId < 0) {
            return true;
        }
        Object where = Reflect.field(task, "displayId");
        return !(where instanceof Integer) || (Integer) where == displayId;
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
            String pkg = IconInfo.packageOfView(child);
            if (pkg == null) {
                continue;
            }
            Object state = Reflect.call(controller, "getRunningAppState", child.getTag());
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
            if (child.getWidth() > 0 && IconInfo.packageOfView(child) != null) {
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
            // The same menu a pinned icon gives, for the same reason: from here on the bar is
            // one row, and one row should not behave two ways.
            view.setOnLongClickListener(v -> TaskbarApps.showMenu(v, pkg,
                    android.os.Process.myUserHandle(), displayId));
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
