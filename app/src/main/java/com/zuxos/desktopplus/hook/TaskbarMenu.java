package com.zuxos.desktopplus.hook;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.Const;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;
import com.zuxos.desktopplus.core.Ui;
import com.zuxos.desktopplus.core.GlassSurface;
import com.zuxos.desktopplus.core.MenuRows;

import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * The taskbar's own context menu: hold or right-click empty taskbar space.
 *
 * <p>Nothing is added to the taskbar for this. The gesture is recognised by watching touches on
 * their way through the drag layer and consuming none of them, which is the only way to add a
 * gesture to someone else's view tree without risking the gestures already there.
 */
public final class TaskbarMenu {

    /** The task manager this menu offers, as asked for. */
    private static final String TASK_MANAGER_PKG = "com.rk.taskmanager";

    private static View sCurrent;

    /** Its window's root while it is up, else null - for a screenshot to leave out. */
    static View current() {
        return sCurrent;
    }
    private static WindowManager sWm;

    private static boolean sInstalled;
    private static Runnable sPending;
    private static ViewGroup sPendingHost;
    private static float sDownX;
    private static float sDownY;
    private static long sDownTime = -1;
    private static int sSlop = 16;

    private TaskbarMenu() {
    }

    /**
     * Watches the taskbar's touches without taking any.
     *
     * <p>The first attempt at this put a transparent, long-clickable view across the bar. That
     * view consumed every press that reached it, the drag layer cancelled the long press before
     * it could fire, and while the taskbar's window was expanded - which it is whenever the app
     * drawer is open - it swallowed input meant for everything underneath. Nothing about a
     * context menu justifies taking touches, so this observes instead: it reads events on their
     * way through the drag layer and consumes nothing, ever.
     */
    static void install(ClassLoader loader) {
        if (sInstalled) {
            return;
        }
        sInstalled = true;
        try {
            Class<?> cls = Reflect.findClass(
                    "com.android.launcher3.taskbar.TaskbarDragLayer", loader);
            if (cls == null) {
                L.w("taskbar menu: TaskbarDragLayer not found");
                return;
            }
            XC_MethodHook watcher = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.thisObject instanceof ViewGroup
                            && param.args.length > 0 && param.args[0] instanceof MotionEvent) {
                        onTouch((ViewGroup) param.thisObject, (MotionEvent) param.args[0]);
                    }
                }
            };
            // Both, because neither alone sees a whole gesture. A ViewGroup stops offering
            // events to onInterceptTouchEvent unless a child has claimed the press, and only
            // reaches its own onTouchEvent when none has - and this taskbar has a full-size
            // clickable scrim that claims most presses. Whichever way a given press goes, one of
            // these two sees all of it; the de-duplication in onDown makes the overlap harmless.
            int hooked = hookUpTo(cls, "onInterceptTouchEvent", watcher)
                    + hookUpTo(cls, "onTouchEvent", watcher);
            L.i("taskbar menu: watching taskbar touches x" + hooked);
            if (hooked == 0) {
                L.w("taskbar menu: no touch method on this build - hold and right-click will "
                        + "do nothing");
            }
        } catch (Throwable t) {
            L.e("taskbar menu: could not install", t);
        }
    }

    /**
     * Hooks a method declared anywhere between {@code cls} and {@code ViewGroup}, exclusive.
     *
     * <p>Stopping short of {@code ViewGroup} matters: its touch methods run for every view in
     * the process, and hooking those to serve a taskbar menu would be indefensible.
     */
    private static int hookUpTo(Class<?> cls, String name, XC_MethodHook hook) {
        int count = 0;
        for (Class<?> c = cls; c != null && c != ViewGroup.class && c != View.class;
                c = c.getSuperclass()) {
            try {
                count += XposedBridge.hookAllMethods(c, name, hook).size();
            } catch (Throwable ignored) {
                // Not declared here; keep walking.
            }
        }
        return count;
    }

    private static void onTouch(ViewGroup dragLayer, MotionEvent event) {
        // The hook lands on whichever class declares the method, which is the shared BaseDragLayer
        // - and the desktop's own drag layer inherits from it too. Without this the home screen
        // would answer a long press with the taskbar's menu.
        if (!TaskbarTray.isTaskbar(dragLayer)) {
            return;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                onDown(dragLayer, event);
                return;
            case MotionEvent.ACTION_MOVE:
                if (sPending != null && (Math.abs(event.getRawX() - sDownX) > sSlop
                        || Math.abs(event.getRawY() - sDownY) > sSlop)) {
                    cancelPending();
                }
                return;
            default:
                cancelPending();
        }
    }

    private static void onDown(ViewGroup dragLayer, MotionEvent event) {
        // A class and its superclass can both declare the method, and the override calls super,
        // so the same press can arrive here more than once.
        if (event.getDownTime() == sDownTime) {
            return;
        }
        sDownTime = event.getDownTime();
        cancelPending();
        // The taskbar's window floats above our popups, so a press on the bar never reaches them
        // and they are never told it happened. This is that telling: the bar is "outside" as far
        // as they are concerned, and pressing it should close them, exactly as pressing the
        // desktop does.
        QuickPanel.onTaskbarPressed(event.getRawX(), event.getRawY());
        NotifyPanel.onTaskbarPressed(event.getRawX(), event.getRawY());
        dismiss();
        // Read once per press rather than per event: this runs on the input thread, and the
        // preference read takes a lock and stats a file.
        if (!Cfg.taskbarMenu()) {
            return;
        }
        if (!onBar(dragLayer, event.getY())
                || TaskbarTray.isOnTray(dragLayer, event.getX(), event.getY())
                || !isEmptySpace(dragLayer, event.getX(), event.getY())) {
            return;
        }
        sDownX = event.getRawX();
        sDownY = event.getRawY();
        if ((event.getButtonState() & MotionEvent.BUTTON_SECONDARY) != 0) {
            // A right-click is already the whole gesture; there is nothing to wait for.
            show(dragLayer, TaskbarTray.displayIdOf(dragLayer), sDownX);
            return;
        }
        schedule(dragLayer);
    }

    /**
     * Whether the press landed on the visible bar.
     *
     * <p>The taskbar's window grows to fill the display while the app drawer is open, so without
     * this a long press on the drawer's own empty space would open the taskbar's menu. The bar is
     * the icon row, not the window - and if that row cannot be found, the gesture is declined
     * rather than guessed at.
     */
    private static boolean onBar(ViewGroup dragLayer, float y) {
        View reference = TaskbarTray.rowReference(dragLayer);
        if (reference == null || reference.getHeight() <= 0) {
            return false;
        }
        return y >= reference.getTop() && y <= reference.getBottom();
    }

    private static void schedule(ViewGroup dragLayer) {
        final ViewGroup host = dragLayer;
        sSlop = ViewConfiguration.get(dragLayer.getContext()).getScaledTouchSlop();
        sPending = () -> {
            sPending = null;
            show(host, TaskbarTray.displayIdOf(host), sDownX);
        };
        dragLayer.postDelayed(sPending, ViewConfiguration.getLongPressTimeout());
        sPendingHost = dragLayer;
    }

    private static void cancelPending() {
        if (sPending != null && sPendingHost != null) {
            sPendingHost.removeCallbacks(sPending);
        }
        sPending = null;
        sPendingHost = null;
    }

    /**
     * Whether this point is bare taskbar rather than a button or an icon.
     *
     * <p>Asked of the live view tree, so it needs no knowledge of where the launcher chose to put
     * anything - and the tray counts as occupied, since it has its own tap.
     */
    private static boolean isEmptySpace(ViewGroup dragLayer, float x, float y) {
        // Measured once, off the bar itself, and carried down: a threshold recomputed per level
        // would shrink inside every container and start excluding the buttons it is meant to
        // find - the navigation row is only 207px wide and holds 69px buttons.
        return !hitsClickable(dragLayer, x, y, dragLayer.getWidth() / 2);
    }

    /**
     * Whether a real, pressable thing sits under this point.
     *
     * <p>Anything spanning half the bar or more is not one. The taskbar's scrim is full width,
     * sits above the icons and is clickable, so counting it made every point occupied and put
     * the gesture permanently out of reach.
     */
    private static boolean hitsClickable(ViewGroup group, float x, float y, int wide) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE || child.getWidth() == 0
                    || child.getAlpha() <= 0.01f) {
                continue;
            }
            // Into the child's coordinates, the group's scroll included: our row scrolls, and
            // without it a hold after scrolling was tested against icons that had moved away.
            float cx = x + group.getScrollX() - child.getLeft();
            float cy = y + group.getScrollY() - child.getTop();
            if (cx < 0 || cy < 0 || cx > child.getWidth() || cy > child.getHeight()) {
                continue;
            }
            if ((child.isClickable() || child.isLongClickable())
                    && (wide <= 0 || child.getWidth() < wide)) {
                return true;
            }
            if (child instanceof ViewGroup && hitsClickable((ViewGroup) child, cx, cy, wide)) {
                return true;
            }
        }
        return false;
    }

    // --- the menu itself -------------------------------------------------

    public static void dismiss() {
        View current = sCurrent;
        WindowManager wm = sWm;
        if (current == null || wm == null) {
            return;
        }
        sCurrent = null;
        sWm = null;
        // Faded out first, the iOS way; the next menu can open meanwhile.
        com.zuxos.desktopplus.core.MenuRows.close(current, () -> removeMenu(current, wm));
    }

    private static void removeMenu(View current, WindowManager wm) {
        try {
            wm.removeViewImmediate(current);
        } catch (IllegalArgumentException notThere) {
            L.d("taskbar menu already gone: " + notThere);
        } catch (Throwable t) {
            // Ask again the asynchronous way, then let go regardless: keeping the reference to
            // retry later only wedges the menu shut, because nothing ever retries.
            L.e("taskbar menu would not close", t);
            try {
                wm.removeView(current);
            } catch (Throwable ignored) {
                L.w("taskbar menu: a window may have been left behind");
            }
        }
    }

    static void show(View source, int displayId, float rawX) {
        // The plain context, not a window one: these only start activities, and asking for a
        // window context here made a second one for every menu - before we even knew whether a
        // menu was allowed.
        final Context ctx = source.getContext();
        List<Entry> entries = new ArrayList<>();
        entries.add(new Entry("Task manager", () -> launch(ctx, TASK_MANAGER_PKG, displayId)));
        entries.add(new Entry("Desktop Plus settings",
                () -> launch(ctx, Const.MODULE_PKG, displayId)));
        entries.add(new Entry("Display settings",
                () -> open(ctx, Settings.ACTION_DISPLAY_SETTINGS, displayId)));
        showEntries(source, displayId, rawX, entries);
    }

    /**
     * The same menu, with whatever someone wants in it.
     *
     * <p>Split out so the menu for an app icon is this menu and not a second one that looks
     * almost like it - one window, one glass pane, one set of rules about closing.
     */
    static boolean showEntries(View source, int displayId, float rawX, List<Entry> entries) {
        return showEntries(source, displayId, rawX, -1f, entries);
    }

    /**
     * The same menu, anchored where it was asked for rather than on the bar.
     *
     * <p>A menu for a taskbar icon belongs on the taskbar, which is what a negative {@code rawY}
     * asks for. A menu for something in the middle of the screen - a folder in the app drawer -
     * belongs beside the thing that was held, and one held near the bottom edge is nudged back up
     * once its height is known.
     */
    static boolean showEntries(View source, int displayId, float rawX, float rawY,
            List<Entry> entries) {
        dismiss();
        if (!canShow(source.getContext())) {
            toast(source.getContext(),
                    "Allow \"display over other apps\" for the launcher to show this menu");
            return false;
        }
        if (entries.isEmpty()) {
            return false;
        }
        // The taskbar's own context is bound to the taskbar's window type, and the window manager
        // refuses a window of any other type from it.
        final Context ctx = Overlays.windowContext(source.getContext());
        try {
            FrameLayout root = new FrameLayout(ctx);
            GlassSurface glass = MenuRows.pane(ctx);
            LinearLayout body = MenuRows.body(glass);
            boolean anyIcon = false;
            for (Entry entry : entries) {
                anyIcon |= entry.icon() != null;
            }
            for (Entry entry : entries) {
                body.addView(rowFor(ctx, entry, anyIcon), new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
            }

            final boolean onTheBar = rawY < 0;
            FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT);
            glp.leftMargin = (int) Math.max(0, rawX - Ui.dp(ctx, 90));
            if (onTheBar) {
                glp.gravity = Gravity.BOTTOM | Gravity.START;
                // Measured off the bar on screen, and the window reaches the screen's edge, so the
                // menu sits on the taskbar rather than a bar's height above it.
                glp.bottomMargin = TaskbarTray.barInset(source);
            } else {
                glp.gravity = Gravity.TOP | Gravity.START;
                glp.topMargin = (int) Math.max(0, rawY);
            }
            root.addView(glass, glp);
            MenuRows.popIn(glass, onTheBar);
            glass.post(() -> {
                // Held near the right-hand edge - where the tray is - the menu would run off the
                // display. Its size is only known once it has been measured.
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) glass.getLayoutParams();
                int edge = Ui.dp(ctx, 8);
                int maxLeft = root.getWidth() - glass.getWidth() - edge;
                int left = Math.max(0, Math.min(lp.leftMargin, Math.max(0, maxLeft)));
                int top = lp.topMargin;
                if (!onTheBar) {
                    int maxTop = root.getHeight() - glass.getHeight() - TaskbarTray
                            .barInset(source);
                    top = Math.max(edge, Math.min(lp.topMargin, Math.max(edge, maxTop)));
                }
                if (left != lp.leftMargin || top != lp.topMargin) {
                    lp.leftMargin = left;
                    lp.topMargin = top;
                    glass.setLayoutParams(lp);
                }
            });

            root.setFocusableInTouchMode(true);
            root.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() == android.view.KeyEvent.ACTION_UP
                        && (keyCode == android.view.KeyEvent.KEYCODE_BACK
                        || keyCode == android.view.KeyEvent.KEYCODE_ESCAPE)) {
                    dismiss();
                    return true;
                }
                return false;
            });
            root.setOnTouchListener((v, event) -> {
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_OUTSIDE) {
                    dismiss();
                    return true;
                }
                if (action == MotionEvent.ACTION_DOWN) {
                    float x = event.getX();
                    float y = event.getY();
                    boolean inside = x >= glass.getLeft() && x <= glass.getRight()
                            && y >= glass.getTop() && y <= glass.getBottom();
                    if (!inside) {
                        dismiss();
                        return true;
                    }
                }
                return false;
            });

            WindowManager wm = Overlays.windowManager(ctx);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                    PixelFormat.TRANSLUCENT);
            lp.setTitle("ZuxOS Desktop Plus taskbar menu");
            QuickPanel.edgeToEdge(lp);
            // No blur behind: on this firmware it blurs the whole display, whatever the window.
            // The monitor's fastest refresh rate while this is up: its motion at what the
            // screen can show.
            com.zuxos.desktopplus.core.FrameRate.forWindow(lp, wm.getDefaultDisplay());
            com.zuxos.desktopplus.core.FrameRate.forView(root);
            wm.addView(root, lp);
            sCurrent = root;
            sWm = wm;
            root.requestFocus();
            return true;
        } catch (Throwable t) {
            L.e("could not show the taskbar menu", t);
            return false;
        }
    }

    private static View rowFor(Context ctx, Entry entry, boolean indent) {
        return MenuRows.row(ctx, entry.title, entry.icon(), indent, true, v -> {
            dismiss();
            try {
                entry.action.run();
            } catch (Throwable t) {
                L.e("taskbar menu action failed: " + entry.title, t);
            }
        });
    }

    static void launch(Context ctx, String pkg, int displayId) {
        try {
            Intent intent = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
            if (intent == null) {
                toast(ctx, pkg + " is not installed");
                return;
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent, launchOptions(displayId));
        } catch (Throwable t) {
            L.e("could not launch " + pkg, t);
            toast(ctx, "Could not open " + pkg);
        }
    }

    static void open(Context ctx, String action, int displayId) {
        try {
            Intent intent = new Intent(action);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent, launchOptions(displayId));
        } catch (Throwable t) {
            L.e("could not open " + action, t);
            toast(ctx, "That settings screen is not available here");
        }
    }

    /** Whatever is opened has to land on the display the taskbar is on. */
    static Bundle launchOptions(int displayId) {
        try {
            ActivityOptions opts = ActivityOptions.makeBasic();
            if (displayId >= 0) {
                opts.setLaunchDisplayId(displayId);
            }
            return opts.toBundle();
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean canShow(Context ctx) {
        try {
            return Settings.canDrawOverlays(ctx);
        } catch (Throwable t) {
            return false;
        }
    }

    static void toast(Context ctx, String msg) {
        try {
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
            L.w(msg);
        }
    }

    static final class Entry {
        final String title;
        final Runnable action;
        final android.graphics.drawable.Drawable icon;

        Entry(String title, Runnable action) {
            this(title, null, action);
        }

        /** With an icon of its own - an app shortcut's - rather than the one its title suggests. */
        Entry(String title, android.graphics.drawable.Drawable icon, Runnable action) {
            this.title = title;
            this.icon = icon;
            this.action = action;
        }

        android.graphics.drawable.Drawable icon() {
            return icon != null ? icon : com.zuxos.desktopplus.core.Glyphs.forTitle(title);
        }
    }
}
