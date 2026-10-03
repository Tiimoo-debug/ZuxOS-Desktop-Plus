package com.zuxos.desktopplus.hook;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
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
import com.zuxos.desktopplus.core.Su;
import com.zuxos.desktopplus.desktop.DesktopHost;

import java.lang.reflect.Method;

/**
 * Back, home and recents on the monitor's taskbar, acting on the monitor.
 *
 * <p>ZUI hands its taskbar keys to the system, and the system acts on whichever screen it last
 * considered in front - so with apps open on both, back on the monitor could close an app on the
 * tablet, and recents could open on the wrong screen or behind everything. The probe names the
 * three keys ({@code #back #home #recent_apps}, plain image views with click listeners); this
 * wraps those clicks on every taskbar that is not the tablet's own:
 *
 * <ul>
 *   <li><b>Back</b> is sent as a key event addressed to this display, so it reaches the window in
 *   front <em>here</em>. In-process if the launcher may inject keys, through root if it may not.
 *   <li><b>Home</b> brings our desktop on this display to the front.
 *   <li><b>Recents</b> first brings the desktop on this display to the front, then runs ZUI's own
 *   handler - so the overview opens over the desktop it belongs to rather than behind other
 *   windows.
 * </ul>
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
            // The tablet's own bar. The system's idea of "in front" is the tablet's anyway.
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
        private final int mDisplay;
        private final View.OnClickListener mOriginal;

        NavClick(String id, int display, View.OnClickListener original) {
            mId = id;
            mDisplay = display;
            mOriginal = original;
        }

        @Override
        public void onClick(View v) {
            if (!Cfg.navKeysOwnScreen()) {
                mOriginal.onClick(v);
                return;
            }
            try {
                switch (mId) {
                    case "back":
                        back(v.getContext(), mDisplay);
                        return;
                    case "home":
                        if (!home(v.getContext(), mDisplay)) {
                            mOriginal.onClick(v);
                        }
                        return;
                    default:
                        // Recents: in front first, then the launcher's own overview over it.
                        home(v.getContext(), mDisplay);
                        v.postDelayed(() -> mOriginal.onClick(v), 250L);
                }
            } catch (Throwable t) {
                L.d("taskbar nav: " + mId + " fell back to the launcher's own (" + t + ")");
                mOriginal.onClick(v);
            }
        }
    }

    /** Back, delivered to the window in front on this display. */
    private static void back(Context ctx, int display) {
        if (!sInjectRefused && inject(ctx, KeyEvent.KEYCODE_BACK, display)) {
            return;
        }
        if (!sSaidRoot) {
            sSaidRoot = true;
            L.i("taskbar nav: the launcher may not send keys itself, so back goes through root");
        }
        Su.run(outcome -> {
            if (outcome != Su.Outcome.OK) {
                L.w("taskbar nav: back through root failed (" + outcome + ")");
            }
        }, "input -d " + display + " keyevent " + KeyEvent.KEYCODE_BACK);
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

    /** Our desktop on this display, brought to the front. False when there is none. */
    private static boolean home(Context ctx, int display) {
        Activity desktop = DesktopHost.activityOn(display);
        if (desktop == null) {
            return false;
        }
        Intent intent = new Intent(Intent.ACTION_MAIN)
                .setComponent(desktop.getComponentName())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        ActivityOptions options = ActivityOptions.makeBasic().setLaunchDisplayId(display);
        desktop.startActivity(intent, options.toBundle());
        // The launcher's drawer is a window of the taskbar's, not of the desktop, so bringing the
        // desktop forward leaves it open on top unless it is closed too.
        try {
            TaskbarBridge.closeStockDrawer();
        } catch (Throwable ignored) {
            // No drawer open, or none to reach. Either way home has happened.
        }
        return true;
    }
}
