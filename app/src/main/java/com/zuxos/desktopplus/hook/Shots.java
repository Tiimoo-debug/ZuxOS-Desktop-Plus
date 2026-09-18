package com.zuxos.desktopplus.hook;

import android.content.Context;
import android.media.MediaScannerConnection;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Su;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

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

    /** Where the system's own screenshots go, so the gallery already watches it. */
    private static final String DIR = "/storage/emulated/0/Pictures/Screenshots";

    private Shots() {
    }

    /**
     * Takes a screenshot of the display the taskbar is on.
     *
     * <p>Pressing the screenshot key was the first attempt and it always captured the built-in
     * panel, because a key goes to whichever display has focus and that is not this one. There is
     * no API for "capture display 2" either - but {@code screencap} takes a display argument, so
     * with root it is one command, and the file is written where the gallery already looks.
     */
    public static void take(Context ctx, int displayId) {
        final Handler main = new Handler(Looper.getMainLooper());
        // The panel and any menu would otherwise be in the picture.
        QuickPanel.dismiss();
        NotifyPanel.dismiss();
        TaskbarMenu.dismiss();
        final String path = DIR + "/Screenshot_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".png";
        // A beat for those windows to actually leave the screen before the shutter.
        final String command = capture(displayId, path);
        main.postDelayed(() -> Su.run(outcome -> {
            if (outcome.ok()) {
                L.i("tray: screenshot of display " + displayId + " saved to " + path);
                scan(ctx, path);
                main.post(() -> toast(ctx, "Screenshot saved"));
                return;
            }
            if (!outcome.shouldFallBack()) {
                L.i("tray: screenshot skipped, another root request is in flight");
                main.post(() -> toast(ctx, "Busy for a moment - press again"));
                return;
            }
            L.i("tray: screenshot needs root and there is none");
            final String why = Cfg.useRoot()
                    ? "A screenshot needs root - grant it to the launcher in Magisk, or use the "
                            + "hardware keys"
                    : "A screenshot needs root - turn \"Use root\" on in Desktop Plus settings";
            main.post(() -> toast(ctx, why));
        }, command), 150L);
    }

    /**
     * The whole capture as one command, so its exit code means what it says.
     *
     * <p>Chained with {@code &&} on purpose: sent as three separate commands, the first one -
     * making a directory that already exists - always succeeded, and "any command succeeded"
     * would have reported a screenshot that never happened.
     *
     * <p>{@code screencap -d} wants a <em>physical</em> display id, which is a sixteen-digit
     * number from SurfaceFlinger, not the logical id the rest of Android uses. There is no
     * mapping between them available to an app, so the shell resolves it: the internal panel is
     * listed first, so anything but the default display is the last one listed.
     */
    private static String capture(int displayId, String path) {
        String shot = displayId <= 0
                ? "screencap -p " + path
                : "id=$(dumpsys SurfaceFlinger --display-id | grep -oE '[0-9]{6,}' | tail -1); "
                        + "echo \"display $id\"; screencap -d $id -p " + path;
        return "mkdir -p " + DIR + " && " + shot + " && chmod 644 " + path
                + " && test -s " + path;
    }

    /** Tells the gallery the file is there; it was written by root, outside its usual watch. */
    private static void scan(Context ctx, String path) {
        try {
            MediaScannerConnection.scanFile(ctx, new String[]{path}, new String[]{"image/png"},
                    null);
        } catch (Throwable t) {
            L.d("tray: could not index the screenshot (" + t + ")");
        }
    }

    private static void toast(Context ctx, String message) {
        try {
            Toast.makeText(ctx, message, Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
            L.w(message);
        }
    }
}
