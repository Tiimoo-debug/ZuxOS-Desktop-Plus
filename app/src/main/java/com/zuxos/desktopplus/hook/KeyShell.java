package com.zuxos.desktopplus.hook;

import android.os.SystemClock;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.L;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One long-lived root shell for the navigation keys, so a press is never dropped.
 *
 * <p>Keys used to go through {@code Su}: a new root shell per press, and one request at a time
 * device-wide - a second press while the first was still starting was refused as busy and lost
 * ({@code taskbar nav: key 4 through root failed (BUSY)} in the log). Here the shell is started
 * once and kept; each key is a line written into it, in order, from one queue. Nothing waits on
 * the main thread and nothing is refused.
 */
public final class KeyShell {

    /** How long a first start may take: it may be waiting on a Magisk prompt being read. */
    private static final long READY_MS = 20_000L;

    private static final ExecutorService QUEUE = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "zux-desktop-plus-keys");
        t.setDaemon(true);
        return t;
    });

    private static Process sProcess;
    private static OutputStream sIn;
    private static volatile boolean sReady;
    private static volatile int sStarts;
    private static volatile String sLastError = "none";
    private static volatile long sLastSentAt;

    private KeyShell() {
    }

    /**
     * Presses {@code key} on {@code display}, off the main thread. {@code onFail} runs (on the
     * key thread) if root cannot be had, so the press can go somewhere else instead of nowhere.
     */
    public static void send(int display, int key, Runnable onFail) {
        QUEUE.execute(() -> {
            String line = "input -d " + display + " keyevent " + key + "\n";
            if (write(line) || (restart() && write(line))) {
                sLastSentAt = SystemClock.uptimeMillis();
                return;
            }
            if (onFail != null) {
                onFail.run();
            }
        });
    }

    /** Any one command as root, through the same shell, in order with the keys. */
    public static void run(String command) {
        QUEUE.execute(() -> {
            String line = command + "\n";
            if (!write(line)) {
                restart();
                write(line);
            }
        });
    }

    private static boolean write(String line) {
        try {
            if (!alive() && !start()) {
                return false;
            }
            sIn.write(line.getBytes(StandardCharsets.UTF_8));
            sIn.flush();
            return true;
        } catch (Throwable t) {
            sLastError = String.valueOf(t);
            return false;
        }
    }

    private static boolean alive() {
        return sProcess != null && sIn != null && sReady && sProcess.isAlive();
    }

    private static boolean restart() {
        stop();
        return start();
    }

    private static void stop() {
        try {
            if (sProcess != null) {
                sProcess.destroy();
            }
        } catch (Throwable ignored) {
            // Already gone.
        }
        sProcess = null;
        sIn = null;
        sReady = false;
    }

    /** Starts the shell and waits until it has answered once, so root is known to be granted. */
    private static boolean start() {
        if (!Cfg.useRoot()) {
            sLastError = "the root setting is off";
            return false;
        }
        if (sFailedAt > 0 && SystemClock.uptimeMillis() - sFailedAt < RETRY_AFTER_MS) {
            // Root just refused or did not answer: asking again at once is another prompt or
            // denial toast, and another twenty seconds the keys queued behind it wait.
            return false;
        }
        try {
            sStarts++;
            Process process = new ProcessBuilder("su").redirectErrorStream(true).start();
            sProcess = process;
            sIn = process.getOutputStream();
            drain(process.getInputStream());
            sIn.write("echo zux-keys-ready\n".getBytes(StandardCharsets.UTF_8));
            sIn.flush();
            long until = SystemClock.uptimeMillis() + READY_MS;
            while (!sReady && process.isAlive() && SystemClock.uptimeMillis() < until) {
                Thread.sleep(20);
            }
            if (!sReady) {
                sLastError = process.isAlive() ? "no answer from root" : "root refused";
                L.w("taskbar nav: the key shell did not start (" + sLastError + ")");
                stop();
                sFailedAt = SystemClock.uptimeMillis();
                return false;
            }
            sFailedAt = 0;
            L.i("taskbar nav: key shell ready (start " + sStarts + ")");
            return true;
        } catch (Throwable t) {
            sLastError = String.valueOf(t);
            L.w("taskbar nav: the key shell did not start (" + t + ")");
            stop();
            sFailedAt = SystemClock.uptimeMillis();
            return false;
        }
    }

    /** When root last failed to start; none is asked for again within the next half minute. */
    private static long sFailedAt;
    private static final long RETRY_AFTER_MS = 30_000L;

    /** Reads everything the shell prints, so its pipe never fills; spots the ready marker. */
    private static void drain(InputStream out) {
        Thread t = new Thread(() -> {
            byte[] buffer = new byte[512];
            StringBuilder seen = new StringBuilder();
            try {
                int n;
                while ((n = out.read(buffer)) > 0) {
                    if (!sReady) {
                        seen.append(new String(buffer, 0, n, StandardCharsets.UTF_8));
                        if (seen.indexOf("zux-keys-ready") >= 0) {
                            sReady = true;
                        }
                    }
                }
            } catch (Throwable ignored) {
                // The shell went away; the next press starts another.
            }
        }, "zux-desktop-plus-keys-out");
        t.setDaemon(true);
        t.start();
    }

    public static String describe() {
        return "key shell: " + (sReady ? "running" : "not running") + ", started "
                + sStarts + "x, last error: " + sLastError
                + (sLastSentAt == 0 ? "" : ", last key "
                        + (SystemClock.uptimeMillis() - sLastSentAt) + "ms ago");
    }
}
