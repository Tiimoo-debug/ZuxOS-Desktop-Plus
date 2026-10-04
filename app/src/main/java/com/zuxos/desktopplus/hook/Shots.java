package com.zuxos.desktopplus.hook;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.widget.Toast;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Su;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
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

    /** Where the system's own screenshots go, relative to shared storage. */
    private static final String RELATIVE = "Pictures/Screenshots";

    private Shots() {
    }

    /**
     * Takes a screenshot of the display the taskbar is on.
     *
     * <p>Pressing the screenshot key was the first attempt and it always captured the built-in
     * panel, because a key goes to whichever display has focus and that is not this one. There is
     * no API for "capture display 2" either - but {@code screencap} takes a display argument, so
     * root takes the picture.
     *
     * <p>Root never writes it to shared storage, though. It used to: {@code mkdir} and
     * {@code screencap} straight into {@code /storage/emulated/0}. A root shell can run in the
     * system's own view of storage, where that path is not your storage at all - and folders made
     * there broke storage for every app started afterwards: downloads failing, file managers
     * stuck on their logo. Now root writes only into the launcher's own cache, hands the file to
     * the launcher, and the launcher files it in Pictures/Screenshots through the media store,
     * exactly as any app saves a picture.
     */
    public static void take(Context ctx, int displayId) {
        final Handler main = new Handler(Looper.getMainLooper());
        // The panel and any menu would otherwise be in the picture.
        QuickPanel.dismiss();
        NotifyPanel.dismiss();
        TaskbarMenu.dismiss();
        final String name = "Screenshot_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".png";
        final File temp = new File(ctx.getCacheDir(), "zux_shot.png");
        final String command = capture(displayId, temp);
        // A beat for those windows to actually leave the screen before the shutter.
        main.postDelayed(() -> Su.run(outcome -> {
            if (outcome.ok()) {
                String saved = file(ctx, temp, name);
                temp.delete();
                if (saved != null) {
                    L.i("tray: screenshot of display " + displayId + " saved to " + saved);
                    main.post(() -> toast(ctx, "Screenshot saved"));
                } else {
                    main.post(() -> toast(ctx, "Could not save the screenshot"));
                }
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
     * The capture as one command, into the launcher's cache and handed to the launcher.
     *
     * <p>Chained with {@code &&} so its exit code means what it says. {@code screencap -d} wants a
     * <em>physical</em> display id, a sixteen-digit number from SurfaceFlinger, not the logical id
     * the rest of Android uses; the internal panel is listed first, so anything but the default
     * display is the last one listed. The file is then given to the launcher's user and its
     * security label restored, or the launcher could not open what root made.
     */
    private static String capture(int displayId, File temp) {
        String path = temp.getAbsolutePath();
        int uid = android.os.Process.myUid();
        String shot = displayId <= 0
                ? "screencap -p " + path
                : "id=$(dumpsys SurfaceFlinger --display-id | grep -oE '[0-9]{6,}' | tail -1); "
                        + "screencap -d $id -p " + path;
        return shot + " && chown " + uid + ":" + uid + " " + path + " && chmod 600 " + path
                + " && (restorecon " + path + " || true) && test -s " + path;
    }

    /** Files the picture in Pictures/Screenshots the way any app saves an image. */
    private static String file(Context ctx, File temp, String name) {
        try {
            ContentResolver files = ctx.getContentResolver();
            ContentValues row = new ContentValues();
            row.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            row.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            row.put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE);
            row.put(MediaStore.Images.Media.IS_PENDING, 1);
            Uri uri = files.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, row);
            if (uri == null) {
                return null;
            }
            try (InputStream in = new FileInputStream(temp);
                 OutputStream out = files.openOutputStream(uri)) {
                if (out == null) {
                    files.delete(uri, null, null);
                    return null;
                }
                byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                }
            }
            row.clear();
            row.put(MediaStore.Images.Media.IS_PENDING, 0);
            files.update(uri, row, null, null);
            return RELATIVE + "/" + name;
        } catch (Throwable t) {
            L.d("tray: could not file the screenshot (" + t + ")");
            return null;
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
