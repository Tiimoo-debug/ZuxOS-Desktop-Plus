package com.zuxos.desktopplus.core;

import android.content.ContentValues;
import android.content.Context;
import android.os.Build;
import android.provider.MediaStore;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Small text-file store living in the hooked launcher's private data dir.
 *
 * <p>The desktop layout is edited on the desktop itself, so it belongs to the process that
 * owns it. A copy of anything diagnostic is additionally written to the launcher's external
 * files dir so it can be pulled without root.
 */
public final class Storage {

    /**
     * One background thread for all layout writes.
     *
     * <p>Callers serialise on their own thread and hand over a finished string, so the data can
     * never be read while it is being changed; only the file write itself moves off the caller.
     */
    private static final String IO_THREAD = "zux-desktop-plus-io";

    private static final java.util.concurrent.ExecutorService IO =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, IO_THREAD);
                t.setPriority(Thread.MIN_PRIORITY);
                t.setDaemon(true);
                return t;
            });

    private Storage() {
    }

    /** Queues a write. Writes stay in order because they all share this one thread. */
    public static void writeAsync(File f, String content) {
        try {
            IO.execute(() -> writeNow(f, content));
        } catch (Throwable t) {
            // Executor gone (process shutting down): write inline rather than lose the layout.
            writeNow(f, content);
        }
    }

    public static File dir(Context ctx) {
        File d = new File(ctx.getFilesDir(), Const.DATA_DIR);
        if (!d.exists() && !d.mkdirs()) {
            L.w("could not create " + d);
        }
        return d;
    }

    public static File file(Context ctx, String name) {
        return new File(dir(ctx), name);
    }

    public static String read(File f) {
        if (f == null || !f.isFile()) {
            return null;
        }
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toString(StandardCharsets.UTF_8.name());
        } catch (Throwable t) {
            L.e("read failed: " + f, t);
            return null;
        }
    }

    /**
     * Writes and waits.
     *
     * <p>Goes through the same single thread as {@link #writeAsync}: a queued layout save and a
     * blocking one would otherwise share a temp file and interleave into a mixed document, and a
     * stale queued write could land after the final one.
     */
    public static boolean write(File f, String content) {
        if (Thread.currentThread().getName().equals(IO_THREAD)) {
            return writeNow(f, content);
        }
        try {
            return IO.submit(() -> writeNow(f, content)).get(3, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Throwable t) {
            L.e("write did not complete: " + f, t);
            return false;
        }
    }

    /** Writes through a temp file so a crash mid-write cannot leave a truncated file. */
    private static boolean writeNow(File f, String content) {
        File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (Throwable t) {
            L.e("write failed: " + f, t);
            return false;
        }
        // Rename straight over the target: on this filesystem that is atomic, so a concurrent
        // reader sees either the old file or the new one, never a missing one.
        if (!tmp.renameTo(f)) {
            if (f.exists() && f.delete() && tmp.renameTo(f)) {
                return true;
            }
            L.w("could not replace " + f);
            return false;
        }
        return true;
    }

    /**
     * The name with this moment in it, before the extension - {@code probe.txt} becomes
     * {@code probe-2026-10-09_17-42-05.txt} - so an export never replaces an earlier one, and a
     * file manager sorts them by time. No colons: file names cannot hold them.
     */
    public static String stamped(String name) {
        String now = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(new Date());
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name + "-" + now : name.substring(0, dot) + "-" + now
                + name.substring(dot);
    }

    /**
     * Writes a copy somewhere you can actually find it.
     *
     * <p>Downloads, through the media store, which needs no permission and no root and lands in
     * the folder every file manager opens on. The old destination - the launcher's own external
     * files directory - is still there as the fallback, because it always works; it is just buried
     * four levels deep in Android/data where nothing but a file manager with its own permissions
     * can reach it.
     *
     * @return where it went, in words, or null if nowhere
     */
    public static String export(Context ctx, String name, String content) {
        String downloads = toDownloads(ctx, name, content);
        if (downloads != null) {
            return downloads;
        }
        File copy = exportCopy(ctx, name, content);
        return copy == null ? null : copy.getAbsolutePath();
    }

    private static String toDownloads(Context ctx, String name, String content) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return null;
        }
        try {
            android.content.ContentResolver files = ctx.getContentResolver();
            android.net.Uri folder = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
            // Ours from a previous export. Without this the store keeps every one of them as
            // "probe (1).txt", "probe (2).txt", and the newest is never the obvious one.
            try {
                files.delete(folder, MediaStore.Downloads.DISPLAY_NAME + "=?", new String[]{name});
            } catch (Throwable keepGoing) {
                // Not ours to delete, or nothing there. The insert below still works.
            }
            ContentValues row = new ContentValues();
            row.put(MediaStore.Downloads.DISPLAY_NAME, name);
            row.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
            row.put(MediaStore.Downloads.IS_PENDING, 1);
            android.net.Uri uri = files.insert(folder, row);
            if (uri == null) {
                return null;
            }
            OutputStream out = files.openOutputStream(uri);
            if (out == null) {
                files.delete(uri, null, null);
                return null;
            }
            try {
                out.write(content.getBytes(StandardCharsets.UTF_8));
            } finally {
                out.close();
            }
            row.clear();
            row.put(MediaStore.Downloads.IS_PENDING, 0);
            files.update(uri, row, null, null);
            return "Download/" + name;
        } catch (Throwable t) {
            L.d("export to Downloads failed, falling back (" + t + ")");
            return null;
        }
    }

    public static File exportCopy(Context ctx, String name, String content) {
        try {
            File ext = ctx.getExternalFilesDir(null);
            if (ext == null) {
                return null;
            }
            File d = new File(ext, Const.DATA_DIR);
            if (!d.exists() && !d.mkdirs()) {
                return null;
            }
            File out = new File(d, name);
            return write(out, content) ? out : null;
        } catch (Throwable t) {
            L.e("export failed", t);
            return null;
        }
    }
}
