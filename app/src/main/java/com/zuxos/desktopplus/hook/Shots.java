package com.zuxos.desktopplus.hook;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Display;
import android.view.SurfaceControl;
import android.view.View;
import android.widget.Toast;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.ScreenBackdrop;
import com.zuxos.desktopplus.core.Su;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The screenshot button.
 *
 * <p>The launcher captures the display itself - the same capture the liquid glass uses - and
 * files the picture in Pictures/Screenshots through the media store. No root, no shell and no
 * wait: the picture is what was on screen the moment the button was pressed.
 *
 * <p>Root's {@code screencap} is kept only for a device where that capture is refused. It was
 * the only route before, and every press went through Magisk, which can flash its own app's
 * window up for a moment to log the request.
 */
public final class Shots {

    /** Where the system's own screenshots go, relative to shared storage. */
    private static final String RELATIVE = "Pictures/Screenshots";

    /** Encoding a full-screen PNG takes a moment; it never holds up anything else. */
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "zux-desktop-plus-shot");
        t.setDaemon(true);
        return t;
    });

    private Shots() {
    }

    /** Takes a screenshot of the display the taskbar is on. */
    /** A second press this soon after the first is the same press: a mouse's double click. */
    private static final long REPEAT_MS = 400L;
    private static long sLastPress;

    public static void take(Context ctx, int displayId) {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - sLastPress < REPEAT_MS) {
            return;
        }
        sLastPress = now;
        Handler main = new Handler(Looper.getMainLooper());
        // Our own panels, menu and preview are left out of the picture rather than waited out of
        // the screen: their layers are skipped by the capture itself, then they close.
        List<SurfaceControl> leave = new ArrayList<>();
        for (View open : new View[]{QuickPanel.current(), NotifyPanel.current(),
                TaskbarMenu.current(), TaskbarPreview.current(), ShotPreview.current(), ShotTargets.current()}) {
            SurfaceControl layer = open != null ? ScreenBackdrop.surfaceOf(open) : null;
            if (layer != null) {
                leave.add(layer);
            }
        }
        Rect crop = displayBounds(ctx, displayId);
        QuickPanel.dismiss();
        NotifyPanel.dismiss();
        TaskbarMenu.dismiss();
        TaskbarPreview.dismiss();
        ShotPreview.dismiss();
        String name = "Screenshot_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".png";
        if (crop == null) {
            takeWithRoot(ctx, displayId, name, main);
            return;
        }
        SurfaceControl[] exclude = leave.toArray(new SurfaceControl[0]);
        IO.execute(() -> {
            Object shot = ScreenBackdrop.grabWhole(displayId, crop, exclude);
            Bitmap hardware = shot != null ? ScreenBackdrop.toBitmap(shot) : null;
            if (hardware == null) {
                L.i("tray: screenshot capture refused, using root");
                main.post(() -> takeWithRoot(ctx, displayId, name, main));
                return;
            }
            // The pixels for the file are copied out first; then the capture itself goes on
            // screen at once - it is a GPU picture, shown with no copy - and the slow part,
            // encoding and writing the PNG, happens behind it. Waiting for the file first
            // left a second of nothing, and the button was pressed again.
            Bitmap picture = hardware.copy(Bitmap.Config.ARGB_8888, false);
            ShotPreview.Shot pending = new ShotPreview.Shot();
            main.post(() -> ShotPreview.show(ctx, displayId, hardware, pending));
            if (picture == null) {
                main.post(() -> ShotPreview.failed(ctx, pending));
                return;
            }
            Uri saved = pending.cancelled ? null : file(ctx, name, out ->
                    picture.compress(Bitmap.CompressFormat.PNG, 100, out));
            picture.recycle();
            report(ctx, main, displayId, name, saved, pending);
        });
    }

    /** The display's full size in pixels, or null when it is not there. */
    private static Rect displayBounds(Context ctx, int displayId) {
        try {
            DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            Display display = dm != null ? dm.getDisplay(displayId) : null;
            if (display == null) {
                return null;
            }
            Point size = new Point();
            display.getRealSize(size);
            return size.x > 0 && size.y > 0 ? new Rect(0, 0, size.x, size.y) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Saved: whatever was waiting on the file gets it. Not saved: say so. */
    private static void report(Context ctx, Handler main, int displayId, String name, Uri saved,
            ShotPreview.Shot shot) {
        if (shot.cancelled) {
            // Deleted from the preview while it was being written.
            if (saved != null) {
                ctx.getContentResolver().delete(saved, null, null);
            }
            L.i("tray: screenshot of display " + displayId + " deleted before it was kept");
            return;
        }
        if (saved == null) {
            main.post(() -> ShotPreview.failed(ctx, shot));
            return;
        }
        L.i("tray: screenshot of display " + displayId + " saved to " + RELATIVE + "/" + name);
        main.post(() -> ShotPreview.saved(shot, saved));
    }

    /**
     * The fallback: root's {@code screencap}.
     *
     * <p>Root never writes to shared storage. It used to, and a root shell can run in the
     * system's own view of storage, where folders it made broke storage for every app started
     * afterwards. It writes only into the launcher's own cache, and the launcher files the
     * picture through the media store. The panels were closed by then; a beat lets them leave.
     */
    private static void takeWithRoot(Context ctx, int displayId, String name, Handler main) {
        File temp = new File(ctx.getCacheDir(), "zux_shot.png");
        String command = capture(displayId, temp);
        main.postDelayed(() -> Su.run(outcome -> {
            if (outcome.ok()) {
                Bitmap thumb = decodeSmall(temp);
                Uri saved = file(ctx, name, out -> {
                    try (InputStream in = new FileInputStream(temp)) {
                        byte[] buffer = new byte[64 * 1024];
                        int n;
                        while ((n = in.read(buffer)) > 0) {
                            out.write(buffer, 0, n);
                        }
                        return true;
                    }
                });
                temp.delete();
                ShotPreview.Shot shot = new ShotPreview.Shot();
                if (thumb != null) {
                    main.post(() -> ShotPreview.show(ctx, displayId, thumb, shot));
                }
                report(ctx, main, displayId, name, saved, shot);
                if (saved != null && thumb == null) {
                    main.post(() -> toast(ctx, "Screenshot saved"));
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

    /** Writes the picture's bytes; false when it could not. */
    private interface Writer {
        boolean write(OutputStream out) throws IOException;
    }

    /** The picture root saved, read at a quarter size for the preview. */
    private static Bitmap decodeSmall(File file) {
        try {
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inSampleSize = 4;
            return android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath(), o);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Files the picture in Pictures/Screenshots the way any app saves an image. */
    private static Uri file(Context ctx, String name, Writer writer) {
        ContentResolver files = ctx.getContentResolver();
        Uri uri = null;
        try {
            ContentValues row = new ContentValues();
            row.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            row.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            row.put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE);
            row.put(MediaStore.Images.Media.IS_PENDING, 1);
            uri = files.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, row);
            if (uri == null) {
                return null;
            }
            boolean written;
            try (OutputStream out = files.openOutputStream(uri)) {
                written = out != null && writer.write(out);
            }
            if (!written) {
                files.delete(uri, null, null);
                return null;
            }
            row.clear();
            row.put(MediaStore.Images.Media.IS_PENDING, 0);
            files.update(uri, row, null, null);
            return uri;
        } catch (Throwable t) {
            L.d("tray: could not file the screenshot (" + t + ")");
            if (uri != null) {
                try {
                    files.delete(uri, null, null);
                } catch (Throwable ignored) {
                    // Left pending; the media store clears those itself.
                }
            }
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
