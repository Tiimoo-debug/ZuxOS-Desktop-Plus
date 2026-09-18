package com.zuxos.desktopplus.hook;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Su;

/**
 * The screenshot button.
 *
 * <p>There is no screenshot API for an ordinary app. {@code GLOBAL_ACTION_TAKE_SCREENSHOT} belongs
 * to accessibility services, and the system's own capture is behind a signature permission - so on
 * a device with Magisk, pressing the key the system already listens for is both the simplest route
 * and the one that behaves exactly like a real screenshot: same animation, same save location,
 * same notification.
 */
public final class Shots {

    /** {@code KEYCODE_SYSRQ} - what a hardware screenshot key sends. */
    private static final int KEYCODE_SYSRQ = 120;

    private Shots() {
    }

    public static void take(Context ctx) {
        final Handler main = new Handler(Looper.getMainLooper());
        // The panel and any menu would otherwise be in the picture.
        QuickPanel.dismiss();
        TaskbarMenu.dismiss();
        // A beat for those windows to actually leave the screen before the shutter.
        main.postDelayed(() -> Su.run(ok -> {
            if (ok) {
                L.i("tray: screenshot taken via root");
                return;
            }
            L.i("tray: screenshot needs root and there is none");
            main.post(() -> toast(ctx, "A screenshot needs root - grant it to the launcher in "
                    + "Magisk, or use the hardware keys"));
        }, "input keyevent " + KEYCODE_SYSRQ), 150L);
    }

    private static void toast(Context ctx, String message) {
        try {
            Toast.makeText(ctx, message, Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
            L.w(message);
        }
    }
}
