package com.zuxos.desktopplus.core;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Small text-file store living in the hooked launcher's private data dir.
 *
 * <p>The desktop layout is edited on the desktop itself, so it belongs to the process that
 * owns it. A copy of anything diagnostic is additionally written to the launcher's external
 * files dir so it can be pulled without root.
 */
public final class Storage {

    private Storage() {
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

    /** Writes through a temp file so a crash mid-write cannot leave a truncated layout. */
    public static boolean write(File f, String content) {
        File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (Throwable t) {
            L.e("write failed: " + f, t);
            return false;
        }
        if (f.exists() && !f.delete()) {
            L.w("could not replace " + f);
        }
        if (!tmp.renameTo(f)) {
            L.w("could not rename " + tmp);
            return false;
        }
        return true;
    }

    /** Best-effort copy into {@code /sdcard/Android/data/<launcher>/files/} for easy pulling. */
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
