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
import com.zuxos.desktopplus.core.Su;

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
                        key(v.getContext(), KeyEvent.KEYCODE_BACK, mDisplay);
                        return;
                    case "home":
                        key(v.getContext(), KeyEvent.KEYCODE_HOME, mDisplay);
                        closeDrawer();
                        return;
                    default:
                        key(v.getContext(), KeyEvent.KEYCODE_APP_SWITCH, mDisplay);
                }
            } catch (Throwable t) {
                L.d("taskbar nav: " + mId + " fell back to the launcher's own (" + t + ")");
                mOriginal.onClick(v);
            }
        }
    }

    /**
     * A navigation key, pressed on this display.
     *
     * <p>Sent as the key itself rather than acted out: the system already knows what back, home
     * and recents mean on a second screen, and only needs to be told which screen they were
     * pressed on. Home used to bring our desktop forward by starting its activity directly, and
     * the system quietly ignores that for a home activity - which is why it did nothing.
     */
    private static void key(Context ctx, int keyCode, int display) {
        if (!sInjectRefused && inject(ctx, keyCode, display)) {
            return;
        }
        if (!sSaidRoot) {
            sSaidRoot = true;
            L.i("taskbar nav: the launcher may not send keys itself, so the keys go through root");
        }
        Su.run(outcome -> {
            if (outcome != Su.Outcome.OK) {
                L.w("taskbar nav: key " + keyCode + " through root failed (" + outcome + ")");
            }
        }, "input -d " + display + " keyevent " + keyCode);
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
