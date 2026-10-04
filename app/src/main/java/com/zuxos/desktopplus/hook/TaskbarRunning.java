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
import com.zuxos.desktopplus.desktop.FolderIconDrawable;
import com.zuxos.desktopplus.logic.PinList;
import com.zuxos.desktopplus.logic.RunningOrder;
import com.zuxos.desktopplus.model.Item;

import java.util.ArrayList;
import java.util.Collections;
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

    /** As many open apps as the row will ever draw at once, however many are running. */
    private static final int MOST_ICONS = 24;

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
        // Both of these come first, and before any setting is read: the strip is what catches an
        // app dropped on the bar, and nothing can be pinned until it is there - so a taskbar with
        // every one of these settings off still has to be able to receive the first pin.
        watchGeometry(dragLayer, TaskbarTray.rowReference(dragLayer));
        TaskbarDrop.apply(dragLayer);
        TaskbarNav.apply(dragLayer);

        boolean onlyOpen = Cfg.taskbarRunningOnly();
        List<Item> pins = TaskbarPins.pins(dragLayer.getContext());
        if (!onlyOpen && pins.isEmpty() && !Cfg.runningMarks()) {
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
        if (onlyOpen && running.isEmpty()) {
            // Nothing readable: better to leave the launcher's bar alone than to empty it.
            restore(dragLayer);
            return;
        }
        // Measured while the launcher's icons are still up to be measured: once they are hidden
        // their size and spacing are what our row keeps using.
        iconSize(icons);
        spacing(icons);
        if (onlyOpen) {
            hideLauncherApps(icons);
            keepStill(icons);
        } else {
            showEverythingAgain(icons);
        }
        // The launcher's own icons have no hold menu on this bar; ours is put on them here,
        // where the row is already being walked.
        TaskbarApps.installRowMenu(icons);
        TaskbarStart.apply(dragLayer, icons);
        extras(dragLayer, icons, running, pins, onlyOpen);
        watchTray(dragLayer);
        RunningRow row = rowIn(dragLayer);
        if (row != null) {
            place(dragLayer, row, TaskbarTray.rowReference(dragLayer));
        }
        TaskbarMarks.apply(dragLayer, icons, running);
        RunningRow ours = rowIn(dragLayer);
        if (ours != null) {
            ours.setOpen(running);
        }
        describe(dragLayer, icons, running);
    }

    /**
     * Follows the tray too: its clock and temperatures change length, and the room our row may
     * use ends where the tray begins. Watching only the launcher's row is how the icons ended up
     * over the tray's text.
     */
    private static void watchTray(ViewGroup dragLayer) {
        View tray = TaskbarTray.trayOf(dragLayer);
        if (tray == null || TRAY_WATCHED.containsKey(tray)) {
            return;
        }
        TRAY_WATCHED.put(tray, Boolean.TRUE);
        tray.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol) {
                RunningRow current = rowIn(dragLayer);
                if (current != null) {
                    place(dragLayer, current, TaskbarTray.rowReference(dragLayer));
                }
            }
        });
    }

    private static final Map<View, Boolean> TRAY_WATCHED = new WeakHashMap<>();

    /**
     * Holds the launcher's row still while ours scrolls.
     *
     * <p>A swipe along the bar scrolled the launcher's own row as well as ours, and the drawer
     * button is a child of that row - so it slid away under the navigation keys and the robot
     * "disappeared" mid-scroll. With every app of the launcher's hidden there is nothing in its
     * row to scroll to, so it is put straight back whenever it moves.
     */
    private static void keepStill(ViewGroup icons) {
        if (STILL.containsKey(icons)) {
            return;
        }
        STILL.put(icons, Boolean.TRUE);
        icons.setOnScrollChangeListener((v, x, y, oldX, oldY) -> {
            if ((x != 0 || y != 0) && Cfg.taskbarRunningOnly()) {
                v.scrollTo(0, 0);
            }
        });
        if (icons.getScrollX() != 0 || icons.getScrollY() != 0) {
            icons.scrollTo(0, 0);
        }
    }

    private static final Map<View, Boolean> STILL = new WeakHashMap<>();

    /** The last description logged per taskbar, so only a change is logged. */
    private static final Map<View, String> DESCRIBED = new WeakHashMap<>();

    /**
     * One line per taskbar whenever what it shows changes: what is open on its display, which of
     * the launcher's own icons are showing, and what is in our row. Enough to answer "why is that
     * app not on the bar" from a log alone.
     */
    private static void describe(ViewGroup dragLayer, ViewGroup icons, Set<String> running) {
        try {
            List<String> zui = new ArrayList<>();
            for (int i = 0; i < icons.getChildCount(); i++) {
                View child = icons.getChildAt(i);
                if (child.getVisibility() != View.VISIBLE) {
                    continue;
                }
                String pkg = IconInfo.packageOfView(child);
                List<String> inside = IconInfo.packagesOfView(child);
                zui.add(pkg != null ? pkg : inside.isEmpty()
                        ? child.getClass().getSimpleName() : "folder" + inside);
            }
            RunningRow row = rowIn(dragLayer);
            String line = "display " + TaskbarTray.displayIdOf(dragLayer) + " open=" + running
                    + " zui=" + zui + " ours=" + (row == null ? "[]"
                    : "pins" + row.mPins + " open" + row.mRunning);
            if (!line.equals(DESCRIBED.get(dragLayer))) {
                DESCRIBED.put(dragLayer, line);
                L.i("taskbar running: " + line);
            }
        } catch (Throwable t) {
            L.d("taskbar running: could not describe the bar (" + t + ")");
        }
    }

    /**
     * Called straight after ZUI has rebuilt its icon row, before that frame is drawn.
     *
     * <p>Opening an app makes ZUI's recent-used model rebind the whole row - its hotseat, its
     * recommendations and the apps it thinks are open - centred on the bar. With "Only open
     * apps" on, every app in our bar is drawn by our own row, so all of ZUI's are hidden again
     * here, in the same frame they were added in: nothing flashes, and nothing pushes our row
     * along.
     *
     * <p>The rebind also hands ZUI's icons fresh listeners, so the hold menu goes back on too,
     * for when the setting is off and ZUI's icons are the ones showing.
     */
    static void rebound(ViewGroup icons) {
        try {
            TaskbarApps.installRowMenu(icons);
            View root = icons.getRootView();
            if (!(root instanceof ViewGroup) || !Cfg.taskbarRunningOnly()
                    || !Cfg.hideRecommendedFlash()) {
                return;
            }
            ViewGroup dragLayer = (ViewGroup) root;
            hideLauncherApps(icons);
            // And a real read soon, rather than at the next tick, now that something changed.
            dragLayer.removeCallbacks(REREAD.get(dragLayer));
            // Weakly, because it is also the value of a weak map keyed by this same view.
            java.lang.ref.WeakReference<ViewGroup> layer = new java.lang.ref.WeakReference<>(
                    dragLayer);
            Runnable again = () -> {
                ViewGroup current = layer.get();
                if (current != null && current.isAttachedToWindow()) {
                    apply(current);
                }
            };
            REREAD.put(dragLayer, again);
            dragLayer.postDelayed(again, 600L);
        } catch (Throwable t) {
            L.d("taskbar running: could not tidy the rebuilt row (" + t + ")");
        }
    }

    private static final Map<View, Runnable> REREAD = new WeakHashMap<>();

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
        ScrollRow scroller = scrollerIn(dragLayer);
        if (scroller != null) {
            dragLayer.removeView(scroller);
        }
        TaskbarMarks.remove(dragLayer);
        ViewGroup icons = iconRow(dragLayer);
        if (icons == null) {
            return;
        }
        showEverythingAgain(icons);
        TaskbarApps.installRowMenu(icons);
        TaskbarStart.apply(dragLayer, icons);
    }

    /** Puts back icons an earlier run hid, without touching anything else in the bar. */
    private static void showEverythingAgain(ViewGroup icons) {
        for (int i = icons.getChildCount() - 1; i >= 0; i--) {
            View child = icons.getChildAt(i);
            if (HIDDEN.remove(child) != null) {
                child.setVisibility(View.VISIBLE);
            }
        }
    }

    /**
     * Hides every app and folder in the launcher's own row, leaving its drawer button.
     *
     * <p>The open apps are all drawn by our row instead, which is what lets them line up from the
     * drawer button in the order they opened. ZUI centres its icons on the whole bar and adds
     * them in its own order, so with both showing the bar was two clusters that shifted on every
     * launch and ran under the tray.
     */
    /** Called from the launcher's layout pass: hides its apps when "Only open apps" is on. */
    static void hideOnSight(ViewGroup icons) {
        if (Cfg.taskbarRunningOnly() && Cfg.hideRecommendedFlash()) {
            hideLauncherApps(icons);
        }
    }

    /**
     * Hides one icon the launcher has just added to its row, if it stands for an app.
     *
     * <p>Remembered like the rest, so the setting going off shows it again.
     */
    static void hideIfApp(View child) {
        if (!Cfg.taskbarRunningOnly() || !Cfg.hideRecommendedFlash()
                || IconInfo.packagesOfView(child).isEmpty()) {
            return;
        }
        HIDDEN.put(child, Boolean.TRUE);
        child.setVisibility(View.GONE);
    }

    private static void hideLauncherApps(ViewGroup icons) {
        boolean changed = false;
        for (int i = icons.getChildCount() - 1; i >= 0; i--) {
            View child = icons.getChildAt(i);
            if (IconInfo.packagesOfView(child).isEmpty()) {
                // The drawer button, and anything else with no app behind it.
                continue;
            }
            if (child.getVisibility() != View.GONE) {
                HIDDEN.put(child, Boolean.TRUE);
                child.setVisibility(View.GONE);
                changed = true;
            }
        }
        if (changed) {
            icons.requestLayout();
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
    private static void extras(ViewGroup dragLayer, ViewGroup icons, Set<String> running,
            List<Item> pins, boolean onlyOpen) {
        Set<String> missing = new LinkedHashSet<>(
                onlyOpen ? running : Collections.<String>emptySet());
        // Only an icon of the app itself counts as already showing it. An app inside a folder -
        // the launcher's or one pinned here - still gets its own: the folder only says that
        // something in it is open, and with ZUI as the main launcher every hotseat folder sits on
        // this bar, which hid every open app that lived in one.
        for (int i = 0; i < icons.getChildCount(); i++) {
            View child = icons.getChildAt(i);
            if (child.getVisibility() == View.VISIBLE) {
                missing.remove(IconInfo.packageOfView(child));
            }
        }
        missing.removeAll(PinList.directPackagesOf(pins));
        // The launcher itself is the desktop, not an app you switch back to.
        missing.remove(dragLayer.getContext().getPackageName());

        RunningRow row = rowIn(dragLayer);
        if (missing.isEmpty() && pins.isEmpty()) {
            ScrollRow scroller = scrollerIn(dragLayer);
            if (scroller != null) {
                dragLayer.removeView(scroller);
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
        // Not trimmed to the room any more - the row scrolls now. The cap is only so that a
        // machine with eighty things open does not build eighty views every three seconds.
        List<String> wanted = RunningOrder.trimToFit(
                RunningOrder.inOrder(row.mRunning, missing), MOST_ICONS * (size + gap), size, gap);
        List<String> pinKeys = new ArrayList<>();
        for (Item pin : pins) {
            pinKeys.add(pin.key());
        }
        if (wanted.equals(row.mRunning) && pinKeys.equals(row.mPins)) {
            return;
        }
        row.removeAllViews();
        Context ctx = dragLayer.getContext();
        int displayId = TaskbarTray.displayIdOf(dragLayer);
        List<String> shownPins = new ArrayList<>();
        for (Item pin : pins) {
            View icon = pinIcon(ctx, pin, size, displayId);
            if (icon != null) {
                add(row, icon, size, gap);
                shownPins.add(pin.key());
            }
        }
        List<String> shown = new ArrayList<>();
        for (String pkg : wanted) {
            View icon = iconFor(ctx, pkg, size, displayId);
            if (icon != null) {
                add(row, icon, size, gap);
                shown.add(pkg);
            }
        }
        // What went in, not what was asked for: an app whose icon could not be drawn would
        // otherwise be remembered as shown and never tried again.
        row.mRunning = shown;
        row.mPins = shownPins;
    }

    private static void add(RunningRow row, View icon, int size, int gap) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.leftMargin = row.getChildCount() == 0 ? 0 : gap;
        row.addView(icon, lp);
    }

    /**
     * While a pin is dragged along the bar: its own slot empties and the pins between where it
     * was and where it would land slide over by one, opening the gap it will drop into. The drop
     * then rebuilds the row with every icon already standing where the preview put it, so
     * nothing jumps.
     *
     * @param slot where it would land, counted with the dragged pin still in the row
     */
    static void previewMove(ViewGroup dragLayer, String key, int slot) {
        RunningRow row = rowIn(dragLayer);
        if (row == null) {
            return;
        }
        int from = row.mPins.indexOf(key);
        if (from < 0 || from >= row.getChildCount()) {
            return;
        }
        int to = Math.min(slot > from ? slot - 1 : slot, row.mPins.size() - 1);
        View dragged = row.getChildAt(from);
        int step = dragged.getWidth() + ((LinearLayout.LayoutParams) dragged.getLayoutParams())
                .leftMargin;
        if (row.getChildCount() > 1) {
            step = Math.abs(row.getChildAt(1).getLeft() - row.getChildAt(0).getLeft());
        }
        for (int i = 0; i < row.mPins.size() && i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            float shift;
            if (i == from) {
                child.setAlpha(0f);
                shift = (to - from) * step;
            } else if (from < to && i > from && i <= to) {
                shift = -step;
            } else if (to < from && i >= to && i < from) {
                shift = step;
            } else {
                shift = 0f;
            }
            if (child.getTranslationX() != shift) {
                child.animate().translationX(shift).setDuration(150)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator())
                        .start();
            }
        }
    }

    /** Puts every icon back where it stands, for a drag that left the bar or ended. */
    static void clearPreview(ViewGroup dragLayer) {
        RunningRow row = rowIn(dragLayer);
        if (row == null) {
            return;
        }
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            child.animate().translationX(0f).alpha(1f).setDuration(150).start();
        }
    }

    /** Our row, for whoever needs to measure a drop against what is already in it. */
    static ViewGroup rowOf(ViewGroup dragLayer) {
        return rowIn(dragLayer);
    }

    /**
     * Where the row's first icon starts, in the drag layer's own coordinates.
     *
     * <p>The row sits inside a scroller now, so its own {@code getLeft()} is relative to that and
     * says nothing about where it is on screen - and what has been scrolled out of sight has to
     * come off as well, or the marks under the icons drift away from them.
     */
    static int rowLeft(ViewGroup dragLayer) {
        RunningRow row = rowIn(dragLayer);
        if (row == null) {
            return 0;
        }
        int left = offsetIn(dragLayer, row);
        if (row.getParent() instanceof ScrollRow) {
            left -= ((ScrollRow) row.getParent()).getScrollX();
        }
        return left;
    }

    /** Where the row's icons sit vertically, in the drag layer's own coordinates. */
    static int rowTop(ViewGroup dragLayer) {
        RunningRow row = rowIn(dragLayer);
        if (row == null) {
            return 0;
        }
        int top = row.getTop();
        for (View v = row.getParent() instanceof View ? (View) row.getParent() : null;
                v != null && v != dragLayer; ) {
            top += v.getTop();
            v = v.getParent() instanceof View ? (View) v.getParent() : null;
        }
        return top;
    }

    /** The scroller's bounds in the drag layer, so what it hides is not drawn over. */
    static int[] rowBounds(ViewGroup dragLayer) {
        ScrollRow scroller = scrollerIn(dragLayer);
        if (scroller == null) {
            return null;
        }
        int left = offsetIn(dragLayer, scroller);
        return new int[]{left, left + scroller.getWidth()};
    }

    /**
     * Whether the launcher is already showing this app in its own row.
     *
     * <p>Pinning it again would put a second copy of the same icon on the same bar, a few pixels
     * from the first, which looks like a bug whatever the reason for it.
     */
    static boolean alreadyInTheBar(ViewGroup dragLayer, String pkg) {
        ViewGroup icons = iconRow(dragLayer);
        if (icons == null || pkg == null) {
            return false;
        }
        for (int i = 0; i < icons.getChildCount(); i++) {
            if (IconInfo.packagesOfView(icons.getChildAt(i)).contains(pkg)) {
                return true;
            }
        }
        return false;
    }

    private static RunningRow rowIn(ViewGroup dragLayer) {
        for (int i = 0; i < dragLayer.getChildCount(); i++) {
            View child = dragLayer.getChildAt(i);
            if (child instanceof ScrollRow && ((ScrollRow) child).getChildCount() > 0) {
                return (RunningRow) ((ScrollRow) child).getChildAt(0);
            }
        }
        return null;
    }

    /** What is actually placed in the drag layer: the scroller the row sits in. */
    private static ScrollRow scrollerIn(ViewGroup dragLayer) {
        for (int i = 0; i < dragLayer.getChildCount(); i++) {
            if (dragLayer.getChildAt(i) instanceof ScrollRow) {
                return (ScrollRow) dragLayer.getChildAt(i);
            }
        }
        return null;
    }

    /**
     * The row, and the scroller around it.
     *
     * <p>Open enough apps and the row runs out of bar. It used to drop the ones that would not
     * fit; now it scrolls, which is what a taskbar does when it is full.
     */
    static final class ScrollRow extends android.widget.HorizontalScrollView {
        ScrollRow(Context ctx) {
            super(ctx);
            setHorizontalScrollBarEnabled(false);
            setOverScrollMode(OVER_SCROLL_NEVER);
            // More icons than room: the ends fade out instead of anything being drawn on top,
            // which is the whole indication there is more to scroll to - and nothing when not.
            setHorizontalFadingEdgeEnabled(true);
            setFadingEdgeLength(Ui.dp(ctx, 28));
            // Nothing here reacts to a touch unless it is a scroll, so a tap goes to the icon.
            setFillViewport(false);
        }

        /**
         * Draws the row inside its own bounds and nowhere else.
         *
         * <p>Scrolled, the row's icons were drawn past both ends - over the drawer button on the
         * left and the tray on the right - because the bar's own layer does not clip what is in
         * it. The clip is set here, on the scroller, so it holds whatever the parents do.
         */
        @Override
        protected void dispatchDraw(android.graphics.Canvas canvas) {
            int saved = canvas.save();
            canvas.clipRect(getScrollX(), 0, getScrollX() + getWidth(), getHeight());
            super.dispatchDraw(canvas);
            canvas.restoreToCount(saved);
        }
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
            ScrollRow scroller = new ScrollRow(dragLayer.getContext());
            scroller.addView(row, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
            dragLayer.addView(scroller, lp);
            // Centred on the bar when nothing of the launcher's is showing, so a change in what
            // the row holds moves where it starts.
            row.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                if (r - l != or - ol) {
                    place(dragLayer, row, reference);
                }
            });
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
            // The marks sit under the icons that moved, and the drop strip spans the same bar.
            TaskbarMarks.refresh(dragLayer);
            TaskbarDrop.apply(dragLayer);
            // A relayout puts ZUI's drawer button back in its slot as far as the row is
            // concerned; the move is re-measured from where it now is.
            ViewGroup row = iconRow(dragLayer);
            if (row != null) {
                TaskbarStart.apply(dragLayer, row);
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
            View placed = row.getParent() instanceof ScrollRow ? (View) row.getParent() : row;
            ViewGroup.LayoutParams raw = placed.getLayoutParams();
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
            int right = trayGap(dragLayer);
            int width;
            if (edge >= 0) {
                // Anchored at both ends - after the drawer button on the left, short of the tray
                // on the right - and as wide as whatever lies between, worked out by the layout
                // pass itself. A width computed here went stale whenever the button or the tray
                // moved after it was measured, which is how icons ended up over the tray.
                gravity |= Gravity.START;
                left = edge;
                width = ViewGroup.LayoutParams.MATCH_PARENT;
            } else {
                // No icons to sit beside - a bar of navigation buttons only. Beside the tray is
                // then the only place left that is not on top of something else.
                gravity |= Gravity.END;
                left = 0;
                width = ViewGroup.LayoutParams.WRAP_CONTENT;
            }
            if (lp.gravity == gravity && lp.height == height && lp.topMargin == top
                    && lp.leftMargin == left && lp.rightMargin == right && lp.width == width) {
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
            lp.width = width;
            placed.setLayoutParams(lp);
        } catch (Throwable t) {
            L.d("taskbar running: could not place our row (" + t + ")");
        }
    }

    /**
     * Where our row starts.
     *
     * <p>With "Only open apps" on, the whole bar is ours: right after the drawer button, the way
     * Windows lines its taskbar up after Start. Otherwise just past the last icon the launcher
     * is showing.
     */
    private static int leftEdge(ViewGroup dragLayer) {
        ViewGroup icons = iconRow(dragLayer);
        if (icons == null || icons.getWidth() <= 0
                || icons.getVisibility() != View.VISIBLE) {
            return -1;
        }
        if (Cfg.taskbarRunningOnly()) {
            int start = TaskbarStart.rightEdge(dragLayer, icons);
            // A clear gap after the start button, so it reads as the button it is and not as the
            // first of the apps.
            return start >= 0 ? start + Ui.dp(dragLayer.getContext(), 18) : -1;
        }
        int edge = -1;
        for (int i = 0; i < icons.getChildCount(); i++) {
            View child = icons.getChildAt(i);
            if (child.getVisibility() == View.VISIBLE && child.getWidth() > 0
                    && !TaskbarStart.isMoved(child)) {
                edge = Math.max(edge, child.getRight());
            }
        }
        if (edge < 0) {
            // Nothing of the launcher's showing - no app open, and the drawer button moved to the
            // corner. The row goes where the launcher's cluster would be, centred on the bar,
            // rather than off beside the clock where it used to land.
            RunningRow row = rowIn(dragLayer);
            int width = row != null ? row.getWidth() : 0;
            return Math.max(0, offsetIn(dragLayer, icons) + (icons.getWidth() - width) / 2);
        }
        return edge + offsetIn(dragLayer, icons) + spacing(icons);
    }

    /**
     * How far our row has to stay from the right-hand end of the bar: up to the tray's actual
     * left edge, and a little more, so neither the last icon nor its fading edge touches it.
     */
    private static int trayGap(ViewGroup dragLayer) {
        int margin = Ui.dp(dragLayer.getContext(), 8);
        View tray = TaskbarTray.trayOf(dragLayer);
        if (tray != null && tray.getWidth() > 0 && dragLayer.getWidth() > 0
                && tray.getParent() == dragLayer) {
            return Math.max(0, dragLayer.getWidth() - tray.getLeft()) + margin;
        }
        return TaskbarTray.trayWidth(dragLayer) + margin;
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

    /**
     * The gap the launcher leaves between two of its own icons, so ours matches it.
     *
     * <p>The smallest of them, not the first. An icon we have hidden leaves its slot empty - the
     * launcher lays the rest out where they always were - so the gap measured across that hole is
     * a whole icon wider than the real one. Taking the first gap found is how our row ended up
     * with 66px between icons that should have had four.
     */
    private static int spacing(ViewGroup icons) {
        List<Integer> gaps = new ArrayList<>();
        View previous = null;
        for (int i = 0; i < icons.getChildCount(); i++) {
            View child = icons.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE || child.getWidth() <= 0) {
                continue;
            }
            if (previous != null) {
                gaps.add(child.getLeft() - previous.getRight());
            }
            previous = child;
        }
        if (gaps.isEmpty()) {
            // Nothing of the launcher's showing to measure, as when all of it is hidden: the gap
            // last measured, or the one the probe shows ZUI using.
            return sGap > 0 ? sGap : 3;
        }
        sGap = RunningOrder.spacing(gaps, Ui.dp(icons.getContext(), 64),
                Ui.dp(icons.getContext(), 8));
        return sGap;
    }

    /** The launcher's icon gap and size, last measured; kept for when its icons are hidden. */
    private static int sGap;
    private static int sIconSize;

    /** Ours, and never a child of the launcher's icon row. */
    private static final class RunningRow extends LinearLayout {
        /** Pinned items, by key, in the order they are on screen. */
        List<String> mPins = new ArrayList<>();
        /** Open apps that are not pinned anywhere, in the order that has to hold still. */
        List<String> mRunning = new ArrayList<>();
        /** What is open right now, for the marks under the icons. */
        private Set<String> mOpen = Collections.emptySet();
        private final android.graphics.Paint mMarkPaint =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        RunningRow(Context ctx) {
            super(ctx);
            setWillNotDraw(false);
        }

        void setOpen(Set<String> open) {
            if (!open.equals(mOpen)) {
                mOpen = new LinkedHashSet<>(open);
                invalidate();
            }
        }

        /**
         * The mark under each open app, drawn with the icons.
         *
         * <p>Part of the row's own drawing, so it is part of what the scroller scrolls and moves
         * in the same frame as the icon above it - at any speed, and through a pin's slide when
         * the row is being rearranged, since it follows each icon's translation too.
         */
        @Override
        protected void dispatchDraw(android.graphics.Canvas canvas) {
            super.dispatchDraw(canvas);
            if (mOpen.isEmpty() || !Cfg.runningMarks()) {
                return;
            }
            mMarkPaint.setColor(TaskbarMarks.markColor(getContext()));
            for (int i = 0; i < getChildCount(); i++) {
                View icon = getChildAt(i);
                if (icon.getVisibility() != VISIBLE || icon.getWidth() <= 0 || icon.getAlpha() <= 0f
                        || !RunningOrder.anyRunning(IconInfo.packagesOfView(icon), mOpen)) {
                    continue;
                }
                TaskbarMarks.drawMark(canvas, mMarkPaint, getContext(),
                        icon.getLeft() + icon.getTranslationX(), icon.getWidth(),
                        icon.getBottom() + icon.getTranslationY(), getHeight());
            }
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
                sIconSize = child.getWidth();
                return sIconSize;
            }
        }
        // 60px is what ZUI draws them at on the external bar, per the probe.
        return sIconSize > 0 ? sIconSize : 60;
    }

    /** True when any of these packages is in that set. */
    private static boolean anyOf(List<String> packages, Set<String> running) {
        for (String pkg : packages) {
            if (running.contains(pkg)) {
                return true;
            }
        }
        return false;
    }

    /**
     * An icon for something pinned here.
     *
     * <p>Pinned by us, in our own row, and so unpinned from the same menu - the launcher knows
     * nothing about it either way.
     */
    private static View pinIcon(Context ctx, Item pin, int size, int displayId) {
        View icon = pin.type == Item.TYPE_FOLDER
                ? folderIcon(ctx, pin, size, displayId) : iconFor(ctx, pin.pkg, size, displayId);
        if (icon == null) {
            return null;
        }
        final String key = pin.key();
        Runnable menu = () -> {
            List<TaskbarMenu.Entry> entries = new ArrayList<>();
            if (pin.pkg != null) {
                entries.addAll(TaskbarApps.entriesFor(ctx, pin.pkg,
                        android.os.Process.myUserHandle(), displayId));
            } else {
                entries.add(new TaskbarMenu.Entry("Open folder",
                        () -> openFolder(ctx, icon, pin, displayId)));
            }
            entries.add(new TaskbarMenu.Entry("Unpin", () -> {
                TaskbarPins.unpin(ctx, key);
                refreshAll();
            }));
            int[] at = new int[2];
            icon.getLocationOnScreen(at);
            TaskbarMenu.showEntries(icon, displayId, at[0] + icon.getWidth() / 2f, entries);
        };
        icon.setOnLongClickListener(null);
        icon.setLongClickable(false);
        icon.setOnTouchListener(new PinGesture(pin, menu));
        return icon;
    }

    /**
     * A folder pinned to the bar, drawn the way folders are drawn everywhere else here.
     *
     * <p>Opening it is the window the stock drawer's folders already use, so a folder behaves the
     * same whether it is in the drawer or on the taskbar.
     */
    private static View folderIcon(Context ctx, Item folder, int size, int displayId) {
        try {
            List<Drawable> previews = new ArrayList<>();
            for (Item child : folder.children) {
                if (child.pkg == null || previews.size() >= 4) {
                    continue;
                }
                try {
                    previews.add(ctx.getPackageManager().getApplicationIcon(child.pkg));
                } catch (Throwable missing) {
                    // An app that has been uninstalled since. The folder still opens.
                }
            }
            ImageView view = new ImageView(ctx);
            view.setImageDrawable(new FolderIconDrawable(previews, size));
            view.setContentDescription(folder.label != null ? folder.label : "Folder");
            view.setBackground(Ui.ripple(ctx, 0x00000000, size / 2));
            view.setOnClickListener(v -> openFolder(ctx, v, folder, displayId));
            return view;
        } catch (Throwable t) {
            L.d("taskbar running: could not draw the pinned folder (" + t + ")");
            return null;
        }
    }

    private static void openFolder(Context ctx, View icon, Item folder, int displayId) {
        try {
            // The same repository the drawer's own folders open with, rather than a second one.
            DrawerFolderWindow.show(ctx, folder, NativeDrawerHooks.repo(ctx), displayId,
                    Ui.dp(ctx, Cfg.iconSizeDp()), null, icon);
        } catch (Throwable t) {
            L.e("taskbar running: could not open the pinned folder", t);
        }
    }

    /**
     * What a pinned icon does under a finger or a mouse.
     *
     * <p>Tap opens it. Hold and let go without moving: its menu. Hold and then move: it comes off
     * the bar and can be dropped somewhere else along it. Right-click: the menu straight away.
     * One gesture, three outcomes, the way a taskbar behaves - and a quick swipe before the hold
     * is still a scroll of the row, because nothing is armed until the hold has happened.
     */
    private static final class PinGesture implements View.OnTouchListener {

        private final Item mPin;
        private final Runnable mMenu;
        private float mDownX;
        private float mDownY;
        private boolean mArmed;
        private Runnable mArm;

        PinGesture(Item pin, Runnable menu) {
            mPin = pin;
            mMenu = menu;
        }

        @Override
        public boolean onTouch(View v, android.view.MotionEvent e) {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN: {
                    if (e.isFromSource(android.view.InputDevice.SOURCE_MOUSE)
                            && (e.getButtonState()
                            & android.view.MotionEvent.BUTTON_SECONDARY) != 0) {
                        mMenu.run();
                        return true;
                    }
                    mDownX = e.getRawX();
                    mDownY = e.getRawY();
                    mArmed = false;
                    mArm = () -> {
                        mArmed = true;
                        // From here the row must not take the gesture for a scroll.
                        if (v.getParent() != null) {
                            v.getParent().requestDisallowInterceptTouchEvent(true);
                        }
                        v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                        v.animate().scaleX(1.12f).scaleY(1.12f).setDuration(120).start();
                    };
                    v.postDelayed(mArm, android.view.ViewConfiguration.getLongPressTimeout());
                    return false;
                }
                case android.view.MotionEvent.ACTION_MOVE: {
                    float slop = android.view.ViewConfiguration.get(v.getContext())
                            .getScaledTouchSlop();
                    boolean moved = Math.abs(e.getRawX() - mDownX) > slop
                            || Math.abs(e.getRawY() - mDownY) > slop;
                    if (!moved) {
                        return mArmed;
                    }
                    if (!mArmed) {
                        v.removeCallbacks(mArm);
                        return false;
                    }
                    mArmed = false;
                    settle(v, e);
                    startDrag(v);
                    return true;
                }
                case android.view.MotionEvent.ACTION_UP: {
                    v.removeCallbacks(mArm);
                    if (!mArmed) {
                        return false;
                    }
                    mArmed = false;
                    settle(v, e);
                    mMenu.run();
                    return true;
                }
                case android.view.MotionEvent.ACTION_CANCEL:
                    v.removeCallbacks(mArm);
                    if (mArmed) {
                        mArmed = false;
                        v.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                    }
                    return false;
                default:
                    return mArmed;
            }
        }

        /**
         * Back to rest, and the view told the touch is over.
         *
         * <p>It saw the press go down and will not see it come up - this listener takes the up -
         * so without the cancel it stays pressed and its ripple never fades.
         */
        private void settle(View v, android.view.MotionEvent e) {
            v.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
            android.view.MotionEvent cancel = android.view.MotionEvent.obtain(e);
            cancel.setAction(android.view.MotionEvent.ACTION_CANCEL);
            v.onTouchEvent(cancel);
            cancel.recycle();
        }

        private void startDrag(View v) {
            try {
                com.zuxos.desktopplus.desktop.DragPayload payload =
                        new com.zuxos.desktopplus.desktop.DragPayload(mPin,
                                com.zuxos.desktopplus.desktop.DragPayload.SRC_TASKBAR, null);
                // Local: a pin moves along the bar and nowhere else, so the desktop never sees it.
                v.startDragAndDrop(payload.toClip(), new View.DragShadowBuilder(v), payload, 0);
            } catch (Throwable t) {
                L.d("taskbar running: could not pick the pin up (" + t + ")");
            }
        }
    }

    /** Re-reads every taskbar, for when what the row should hold has just changed. */
    static void refreshAll() {
        for (View root : Windows.roots()) {
            if (root instanceof ViewGroup && rowIn((ViewGroup) root) != null) {
                apply((ViewGroup) root);
            }
        }
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
            // What this icon stands for, read back by the marks and by anything else that asks an
            // icon what it is. The launcher's own icons carry an item info here; ours carry this.
            view.setTag(pkg);
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
