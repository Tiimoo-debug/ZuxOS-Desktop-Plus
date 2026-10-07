package com.zuxos.desktopplus.hook;

import android.content.Context;
import android.hardware.input.InputManager;
import android.os.SystemClock;
import android.view.Display;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;
import com.zuxos.desktopplus.desktop.DesktopHost;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

/**
 * Back, home and recents on the monitor's taskbar, acting on the monitor.
 *
 * <p>ZUI hands its taskbar keys to the system, and the system acts on whichever screen it last
 * considered in front - so with apps open on both, back on the monitor could close an app on the
 * tablet, and recents could open on the wrong screen or behind everything. The probe names the
 * three keys ({@code #back #home #recent_apps}, plain image views with click listeners); this
 * wraps those clicks on every taskbar that is not the tablet's own:
 *
 * <p>Each is sent as that key, addressed to this display - so back reaches the window in front
 * <em>here</em>, home goes home on this screen, and recents opens this screen's overview.
 * In-process if the launcher may inject keys, through root if it may not (this firmware refuses
 * the launcher {@code INJECT_EVENTS}, so it is root).
 *
 * <p>Long presses are not touched, and with the setting off every key does exactly what ZUI does.
 */
final class TaskbarNav {

    private static boolean sInjectRefused;
    private static boolean sSaidRoot;

    private TaskbarNav() {
    }

    static void apply(ViewGroup dragLayer) {
        int display = TaskbarTray.displayIdOf(dragLayer);
        if (display == Display.DEFAULT_DISPLAY) {
            // The tablet's own bar. The system's idea of "in front" is the tablet's anyway, so
            // its keys stay ZUI's - but its recents button still says it was pressed here, or
            // recents cannot know which screen it is for.
            wrap(dragLayer, "recent_apps", display);
            return;
        }
        wrap(dragLayer, "back", display);
        wrap(dragLayer, "home", display);
        wrap(dragLayer, "recent_apps", display);
    }

    private static void wrap(ViewGroup dragLayer, String id, int display) {
        for (View key : Reflect.findByIdNames(dragLayer, id)) {
            Object current = clickListenerOf(key);
            if (current instanceof NavClick) {
                return;
            }
            if (!(current instanceof View.OnClickListener)) {
                L.d("taskbar nav: #" + id + " has no click listener to take over ("
                        + (current == null ? "none" : current.getClass().getName()) + ")");
                return;
            }
            key.setOnClickListener(new NavClick(id, display, (View.OnClickListener) current));
            L.i("taskbar nav: #" + id + " on display " + display + " acts on its own screen"
                    + " (the launcher's was " + current.getClass().getName() + ")");
            return;
        }
    }

    private static Object clickListenerOf(View view) {
        Object info = Reflect.field(view, "mListenerInfo");
        return info == null ? null : Reflect.field(info, "mOnClickListener");
    }

    private static final class NavClick implements View.OnClickListener {

        private final String mId;
        /** Read again at every press: ZUI moves a bar between screens (see onClick). */
        private int mDisplay;
        private final View.OnClickListener mOriginal;

        NavClick(String id, int display, View.OnClickListener original) {
            mId = id;
            mDisplay = display;
            mOriginal = original;
        }

        @Override
        public void onClick(View v) {
            // The screen the key is on now, not the one it was on when it was taken over. At
            // boot ZUI builds its bar on the tablet and moves it to the monitor a few seconds
            // later; a recents key that kept "display 0" sent every press to the tablet.
            if (v.getDisplay() != null) {
                mDisplay = v.getDisplay().getDisplayId();
            }
            if ("recent_apps".equals(mId)) {
                RecentsRoute.pressed(mDisplay, () -> mOriginal.onClick(v));
            }
            act(v);
        }

        private void act(View v) {
            if (mDisplay == Display.DEFAULT_DISPLAY || !Cfg.navKeysOwnScreen()) {
                mOriginal.onClick(v);
                record(mId, "ZUI's own");
                return;
            }
            try {
                switch (mId) {
                    case "back":
                        back(v);
                        return;
                    case "home":
                        home(v);
                        return;
                    default:
                        // Both the recents key and ZUI's own button were measured to open recents
                        // only for an instant: a transient launch the system undid 14 ms later.
                        // RecentsRoute opens ZUI's recents on this screen with a real move.
                        if (!Cfg.recentsRoute()) {
                            mOriginal.onClick(v);
                            record(mId, "ZUI's own (route off)");
                            return;
                        }
                        TaskOverview.toggle(v, mDisplay);
                        record(mId, "the module's recents");
                }
            } catch (Throwable t) {
                L.d("taskbar nav: " + mId + " fell back to the launcher's own (" + t + ")");
                mOriginal.onClick(v);
            }
        }

        /**
         * Back, without ever sending it to a screen with no window to take it.
         *
         * <p>With our desktop in front the launcher's window there can be without focus, and a
         * back key sent to it waits five seconds for a window that never comes - the log showed
         * exactly that ANR, and the launcher being killed for it. So with the desktop in front,
         * back is done here: it closes what our desktop and our windows have open.
         */
        private void back(View v) {
            if (homeInFront(v.getContext(), mDisplay)) {
                boolean closed = closeOurWindows(mDisplay) || DesktopHost.backOn(mDisplay);
                record("back", closed ? "closed a panel here (desktop in front)"
                        : "nothing to close (desktop in front)");
                return;
            }
            key(v, KeyEvent.KEYCODE_BACK);
        }

        /** Home, sent once - and not at all when the desktop is already in front. */
        private void home(View v) {
            closeDrawer();
            // Recents first, in the same press: home then lands on the desktop, not on recents.
            if (TaskOverview.isOpen()) {
                TaskOverview.close();
            }
            RecentsRoute.closeOn(mDisplay);
            if (homeInFront(v.getContext(), mDisplay)) {
                closeOurWindows(mDisplay);
                DesktopHost.backOn(mDisplay);
                record("home", "desktop already in front");
                return;
            }
            key(v, KeyEvent.KEYCODE_HOME);
        }

        private void key(View v, int code) {
            String route = TaskbarNav.key(v.getContext(), code, mDisplay,
                    () -> v.post(() -> mOriginal.onClick(v)));
            record(mId, route);
        }
    }

    /** Our own windows that back should close first: menus, panels, a drawer folder. */
    private static boolean closeOurWindows(int display) {
        boolean drawer = false;
        try {
            drawer = TaskbarBridge.closeStockDrawer(display);
        } catch (Throwable ignored) {
            // Not reachable; the rest still close.
        }
        if (TaskOverview.isOpen()) {
            TaskOverview.close();
            drawer = true;
        }
        TaskbarMenu.dismiss();
        QuickPanel.dismiss();
        NotifyPanel.dismiss();
        DrawerFolderWindow.close();
        return drawer;
    }

    /**
     * Whether a back or home key sent to this display would land on the launcher's own home -
     * our desktop - rather than on an app.
     *
     * <p>Read from the task list: the task the system calls focused on this display, or, failing
     * that, the first one listed there. No task at all on the display counts as the desktop, since
     * a key sent there would have nothing to go to either. The display id and the focus flag are
     * fields the framework keeps but does not publish, read by name as the probe does.
     */
    static boolean homeInFront(Context ctx, int display) {
        try {
            android.app.ActivityManager am = (android.app.ActivityManager)
                    ctx.getSystemService(Context.ACTIVITY_SERVICE);
            android.app.ActivityManager.RunningTaskInfo first = null;
            android.app.ActivityManager.RunningTaskInfo focused = null;
            for (android.app.ActivityManager.RunningTaskInfo task : am.getRunningTasks(24)) {
                Object d = Reflect.field(task, "displayId");
                if (!(d instanceof Integer) || (Integer) d != display) {
                    continue;
                }
                if (first == null) {
                    first = task;
                }
                if (Boolean.TRUE.equals(Reflect.field(task, "isFocused"))) {
                    focused = task;
                    break;
                }
            }
            android.app.ActivityManager.RunningTaskInfo front = focused != null ? focused : first;
            if (front == null) {
                return true;
            }
            android.content.ComponentName top = front.topActivity != null
                    ? front.topActivity : front.baseActivity;
            return top != null && top.getPackageName().equals(ctx.getPackageName())
                    && top.getClassName().contains("Launcher");
        } catch (Throwable t) {
            L.d("taskbar nav: could not read the front task (" + t + ")");
            return false;
        }
    }

    // --- what happened, for the probe -----------------------------------------------------------

    private static final java.util.ArrayDeque<String> HISTORY = new java.util.ArrayDeque<>();
    private static final Set<String> SAID = new HashSet<>();

    private static synchronized void record(String key, String route) {
        HISTORY.addLast(SystemClock.uptimeMillis() / 1000 + "s " + key + ": " + route);
        while (HISTORY.size() > 12) {
            HISTORY.removeFirst();
        }
        if (SAID.size() < 40 && SAID.add(key + route)) {
            L.i("taskbar nav: " + key + " -> " + route);
        }
    }

    static synchronized String describe() {
        StringBuilder sb = new StringBuilder("\nnavigation\n  ")
                .append(KeyShell.describe()).append('\n');
        for (String h : HISTORY) {
            sb.append("  - ").append(h).append('\n');
        }
        return sb.toString();
    }

    /** Last press of each key per display, so a quick repeat counts once. */
    private static final java.util.Map<String, Long> LAST = new java.util.HashMap<>();
    private static final long REPEAT_MS = 300L;

    /**
     * A navigation key, pressed on this display.
     *
     * <p>Sent as the key itself rather than acted out: the system already knows what back and
     * home mean on a second screen, and only needs to be told which screen they were pressed on.
     * In-process if the launcher may inject keys; through the key shell if not (this firmware
     * refuses the launcher {@code INJECT_EVENTS}). A repeat within {@link #REPEAT_MS} counts once:
     * five homes in a second used to restart the home screen five times.
     *
     * @return the route taken, for the record
     */
    static String key(Context ctx, int keyCode, int display, Runnable onFail) {
        String id = display + ":" + keyCode;
        long now = SystemClock.uptimeMillis();
        synchronized (LAST) {
            Long last = LAST.get(id);
            LAST.put(id, now);
            if (last != null && now - last < REPEAT_MS) {
                return "skipped as a repeat";
            }
        }
        if (!sInjectRefused && inject(ctx, keyCode, display)) {
            return "key sent in-process";
        }
        if (!sSaidRoot) {
            sSaidRoot = true;
            L.i("taskbar nav: the launcher may not send keys itself, so the keys go through root");
        }
        KeyShell.send(display, keyCode, onFail);
        return "key sent through the key shell";
    }

    /**
     * Sends a key press to one display, in-process.
     *
     * <p>Both calls are hidden API - {@code KeyEvent.setDisplayId} and
     * {@code InputManager.injectInputEvent} - and the second needs {@code INJECT_EVENTS}, which
     * a launcher may or may not hold depending on how the firmware signed it. A refusal is
     * remembered, so the next press goes straight to root instead of failing first.
     */
    private static boolean inject(Context ctx, int keyCode, int display) {
        try {
            InputManager input = ctx.getSystemService(InputManager.class);
            Method setDisplay = KeyEvent.class.getMethod("setDisplayId", int.class);
            Method send = InputManager.class.getMethod("injectInputEvent", InputEvent.class,
                    int.class);
            long now = SystemClock.uptimeMillis();
            for (int action : new int[]{KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP}) {
                KeyEvent event = new KeyEvent(now, now, action, keyCode, 0, 0,
                        KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                        KeyEvent.FLAG_FROM_SYSTEM | KeyEvent.FLAG_VIRTUAL_HARD_KEY,
                        InputDevice.SOURCE_KEYBOARD);
                setDisplay.invoke(event, display);
                // 0: asynchronous - the press is on the main thread and must not wait on it.
                Object sent = send.invoke(input, event, 0);
                if (Boolean.FALSE.equals(sent)) {
                    throw new IllegalStateException("injectInputEvent returned false");
                }
            }
            return true;
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            sInjectRefused = true;
            L.i("taskbar nav: in-process key injection refused (" + cause + ")");
            return false;
        }
    }

    /**
     * The launcher's drawer is a window of the taskbar's, not of the desktop, so going home
     * leaves it open on top unless it is closed too.
     */
    private static void closeDrawer() {
        try {
            TaskbarBridge.closeStockDrawer();
        } catch (Throwable ignored) {
            // No drawer open, or none to reach. Either way home has happened.
        }
    }
}
