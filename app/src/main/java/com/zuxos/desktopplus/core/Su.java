package com.zuxos.desktopplus.core;

import java.io.DataOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Shell commands as root, for the handful of things a launcher may not do itself.
 *
 * <p>The log settled which those are. This launcher holds neither {@code BLUETOOTH_CONNECT} nor
 * the network or secure-settings permissions, so its Bluetooth and aeroplane tiles could only ever
 * open a settings screen, and there is no screenshot API for an ordinary app at all. On a device
 * with Magisk, {@code su} does all three.
 *
 * <p>Nothing here is required. Every caller tries the ordinary route first and only falls back to
 * this, so denying or revoking root costs the toggles and nothing else.
 *
 * <p>Commands never run on the calling thread: {@code su} means a round trip through Magisk's
 * daemon and, the first time, a dialog waiting on a human.
 */
public final class Su {

    /** How long to wait on a shell, dialog included, before giving up on root entirely. */
    private static final long TIMEOUT_SECONDS = 45;

    /** Tri-state: null not yet asked, then true or false for the process's life. */
    private static volatile Boolean sAvailable;

    private static final ExecutorService IO =
            Executors.newSingleThreadExecutor(r -> new Thread(r, "zux-desktop-plus-su"));

    private Su() {
    }

    /** Called with true when the command was accepted, false when root was refused or absent. */
    public interface Result {
        void done(boolean ok);
    }

    /**
     * Runs one or more shell lines as root, off the calling thread.
     *
     * @param onDone called on the shell thread, not the main one; hop back yourself if you must
     */
    public static void run(Result onDone, String... commands) {
        IO.execute(() -> {
            boolean ok = runBlocking(commands);
            if (onDone != null) {
                try {
                    onDone.done(ok);
                } catch (Throwable t) {
                    L.d("su: callback failed (" + t + ")");
                }
            }
        });
    }

    /**
     * Blocks. Only ever called from the shell thread above.
     *
     * <p>Two details matter. The exit status reported is "did <em>any</em> of these work", not
     * "did the last one", because a caller sends alternative routes to the same end - aeroplane
     * mode tries the connectivity command and the raw setting, and either landing is a success.
     * And the wait is bounded: the first {@code su} shows a dialog somebody has to answer, and an
     * unanswered one on a single-threaded executor would wedge every screenshot and every toggle
     * after it, for good.
     */
    private static boolean runBlocking(String... commands) {
        if (Boolean.FALSE.equals(sAvailable)) {
            // Already refused once. Asking again costs another timeout for the same answer.
            return false;
        }
        Process process = null;
        try {
            process = Runtime.getRuntime().exec("su");
            try (DataOutputStream out = new DataOutputStream(process.getOutputStream())) {
                out.writeBytes("RC=1\n");
                for (String command : commands) {
                    out.writeBytes(command + " && RC=0\n");
                }
                out.writeBytes("exit $RC\n");
                out.flush();
            }
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                L.w("su: no answer in " + TIMEOUT_SECONDS + "s - treating root as unavailable");
                sAvailable = Boolean.FALSE;
                return false;
            }
            int code = process.exitValue();
            boolean ok = code == 0;
            if (ok && !Boolean.TRUE.equals(sAvailable)) {
                sAvailable = Boolean.TRUE;
                L.i("su: granted");
            } else if (!ok) {
                L.i("su: command failed or was denied (exit " + code + ")");
            }
            return ok;
        } catch (Throwable t) {
            sAvailable = Boolean.FALSE;
            L.i("su: unavailable (" + t + ")");
            return false;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }
}
