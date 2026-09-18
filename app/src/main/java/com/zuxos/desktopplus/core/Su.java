package com.zuxos.desktopplus.core;

import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shell commands as root, for the handful of things a launcher may not do itself.
 *
 * <p>The log settled which those are. This launcher holds neither {@code BLUETOOTH_CONNECT} nor
 * the network or secure-settings permissions, so its Bluetooth, aeroplane and eye-protection tiles
 * could only ever open a settings screen, and there is no screenshot API for an ordinary app at
 * all. On a device with Magisk, {@code su} does all of them.
 *
 * <p>Nothing here is required. Every caller tries the ordinary route first and only falls back to
 * this, so denying root - or turning the setting off - costs the toggles and nothing else.
 *
 * <p>Three rules, each of them learned the hard way. One command per process, not an interactive
 * shell. Always drain the output, because a process whose pipe buffer fills stops dead and never
 * exits. And never queue: a second request while one is pending is dropped, not stacked, since
 * root requests are serialised device-wide and a stuck one holds up every other app that asks.
 */
public final class Su {

    /**
     * How long to wait for an answer.
     *
     * <p>Two numbers, because the first request and the rest are not the same thing. The first
     * one may be putting a Magisk dialog in front of someone who has to read it; the ones after
     * it are a daemon that already knows the answer and replies in milliseconds. Twenty seconds
     * on a first grant is how a dialog gets killed while it is being read.
     */
    private static final long FIRST_TIMEOUT_SECONDS = 45;
    private static final long TIMEOUT_SECONDS = 20;

    /** After a wait that went nowhere, how long before anything asks again. */
    private static final long QUIET_MS = 30_000L;

    /** Tri-state: null not yet asked, then true or false for the process's life. */
    private static volatile Boolean sAvailable;

    /** One request at a time, device-wide. See the class note on queuing. */
    private static final AtomicBoolean BUSY = new AtomicBoolean();

    /**
     * Set after a timeout: nothing asks again until this passes. See {@link #QUIET_MS}.
     *
     * <p>Measured on {@link SystemClock#elapsedRealtime()}, not the wall clock, which a time-zone
     * change or an NTP correction can move by hours in either direction.
     */
    private static volatile long sQuietUntil;

    private static final ExecutorService IO =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "zux-desktop-plus-su");
                t.setDaemon(true);
                return t;
            });

    private Su() {
    }

    /** How a request ended. Three of these are failures, and they are not the same failure. */
    public enum Outcome {
        /** A command ran and returned success. */
        OK,
        /** Root answered and said no, or the command itself failed. */
        FAILED,
        /** There is no root here, or the setting is off. */
        UNAVAILABLE,
        /**
         * Nothing was asked at all.
         *
         * <p>Either another request was already in flight, or the last one went unanswered and
         * root is being left alone for a moment. Both say nothing about whether root works.
         */
        BUSY;

        public boolean ok() {
            return this == OK;
        }

        /**
         * Whether the caller should now take its ordinary route instead.
         *
         * <p>{@link #BUSY} is deliberately not one of these. A dropped request says nothing about
         * whether root would have worked, and treating it as "no root" is how a second tap within
         * a second of the first used to throw the user into a settings screen.
         */
        public boolean shouldFallBack() {
            return this == FAILED || this == UNAVAILABLE;
        }
    }

    /** Called when the request ends, whichever way it ended. */
    public interface Result {
        void done(Outcome outcome);
    }

    /**
     * Runs shell lines as root, off the calling thread.
     *
     * @param onDone called on the shell thread, not the main one; hop back yourself if you must
     */
    public static void run(Result onDone, String... commands) {
        if (!Cfg.useRoot()) {
            L.i("su: the root setting is off, not asking");
            finish(onDone, Outcome.UNAVAILABLE);
            return;
        }
        if (Boolean.FALSE.equals(sAvailable)) {
            finish(onDone, Outcome.UNAVAILABLE);
            return;
        }
        if (SystemClock.elapsedRealtime() < sQuietUntil) {
            // The last request sat unanswered for the full timeout - almost always a Magisk
            // dialog nobody is looking at. Asking again straight away would only queue a second
            // dialog behind the first. Not a failure, though: root may well be fine, so callers
            // wait rather than throwing the user into a settings screen.
            L.i("su: the last request timed out, not asking again yet");
            finish(onDone, Outcome.BUSY);
            return;
        }
        if (!BUSY.compareAndSet(false, true)) {
            // Dropped rather than queued. Waiting behind a pending prompt is how one unanswered
            // dialog turns into minutes of held root for every app on the device.
            L.i("su: a request is already in flight, dropping this one");
            finish(onDone, Outcome.BUSY);
            return;
        }
        try {
            IO.execute(() -> {
                Outcome outcome = Outcome.FAILED;
                try {
                    outcome = runAll(commands);
                } catch (Throwable t) {
                    L.d("su: the request went wrong (" + t + ")");
                } finally {
                    BUSY.set(false);
                }
                // Outside the finally, but after it: the caller is always told something, and
                // it is told it with the flag already cleared.
                finish(onDone, outcome);
            });
        } catch (Throwable t) {
            // The executor would not take it. Holding BUSY here would refuse every root request
            // for the rest of the process.
            BUSY.set(false);
            L.d("su: could not start the shell thread (" + t + ")");
            finish(onDone, Outcome.FAILED);
        }
    }

    private static void finish(Result onDone, Outcome outcome) {
        if (onDone == null) {
            return;
        }
        try {
            onDone.done(outcome);
        } catch (Throwable t) {
            L.d("su: callback failed (" + t + ")");
        }
    }

    /**
     * OK when any command succeeded - callers send alternative routes to the same end.
     *
     * <p>One timeout ends the whole request, not just the command that hit it. The aeroplane tile
     * sends two commands; without this a single unanswered prompt would cost two full waits, with
     * every other root request on the device dropped for the whole forty seconds.
     */
    private static Outcome runAll(String... commands) {
        boolean any = false;
        boolean timedOut = false;
        for (String command : commands) {
            Step step = runOne(command);
            if (step == Step.OK) {
                any = true;
                continue;
            }
            if (step == Step.TIMED_OUT) {
                timedOut = true;
                break;
            }
            if (step == Step.NO_ROOT) {
                break;
            }
        }
        if (any) {
            return Outcome.OK;
        }
        if (timedOut) {
            // Nobody answered. That is not "there is no root here" - it is almost always a Magisk
            // dialog still sitting on the screen - so the caller waits rather than falling back
            // to a settings screen the user did not ask for.
            return Outcome.BUSY;
        }
        return Boolean.FALSE.equals(sAvailable) ? Outcome.UNAVAILABLE : Outcome.FAILED;
    }

    /** How one command ended, which is not always how the request should end. */
    private enum Step {
        OK, FAILED, TIMED_OUT, NO_ROOT
    }

    /** Longer before root has ever answered; see {@link #FIRST_TIMEOUT_SECONDS}. */
    private static long timeoutSeconds() {
        return sAvailable == null ? FIRST_TIMEOUT_SECONDS : TIMEOUT_SECONDS;
    }

    private static Step runOne(String command) {
        long timeout = timeoutSeconds();
        Process process;
        try {
            ProcessBuilder builder = new ProcessBuilder("su", "-c", command);
            // Merged, so there is exactly one stream to drain and stderr cannot fill unread.
            builder.redirectErrorStream(true);
            process = builder.start();
        } catch (IOException noSu) {
            // The only failure that really means "there is no root here": no su binary to run.
            // Everything below is this command going wrong, which is a different thing and must
            // not cost the device its root for the rest of the process.
            sAvailable = Boolean.FALSE;
            L.i("su: unavailable (" + noSu + ")");
            return Step.NO_ROOT;
        }
        try {
            process.getOutputStream().close();

            // Drained on a thread of its own, and this is the whole reason for the thread: a
            // process nobody reads from fills its pipe buffer and stops dead, but draining it
            // here would block until it exits - which is exactly what the timeout below exists
            // to bound. One of the two has to wait somewhere else.
            AtomicReference<String> output = new AtomicReference<>("");
            Thread drain = drain(process, output);

            if (!process.waitFor(timeout, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                // Not marked unavailable: an unanswered Magisk prompt is not the same as a device
                // without root, and one ignored dialog should not cost the toggles until the
                // launcher restarts. A quiet spell instead, so the next tap answers at once.
                sQuietUntil = SystemClock.elapsedRealtime() + QUIET_MS;
                L.w("su: no answer in " + timeout + "s for '" + command
                        + "' - not asking again for " + (QUIET_MS / 1000) + "s");
                return Step.TIMED_OUT;
            }
            // The reader is at EOF the moment the process exits; this is the last few bytes.
            drain.join(500L);
            int code = process.exitValue();
            boolean ok = code == 0;
            if (ok && !Boolean.TRUE.equals(sAvailable)) {
                sAvailable = Boolean.TRUE;
                L.i("su: granted");
            }
            String text = output.get();
            L.i("su: ran '" + command + "' -> exit " + code
                    + (text.isEmpty() ? "" : " (" + text + ")"));
            return ok ? Step.OK : Step.FAILED;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            L.d("su: interrupted while waiting for '" + command + "'");
            return Step.FAILED;
        } catch (Throwable t) {
            // This command went wrong, not root itself. Nothing is latched here.
            L.d("su: '" + command + "' failed (" + t + ")");
            return Step.FAILED;
        } finally {
            process.destroy();
        }
    }

    /**
     * Starts reading the process's output, and hands back the thread doing it.
     *
     * <p>The text is only for the log. The reading is not optional: a process whose pipe buffer
     * fills blocks on its next write and never exits, which turns a fast command into a timeout.
     */
    private static Thread drain(Process process, AtomicReference<String> into) {
        Thread thread = new Thread(() -> {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (sb.length() < 200) {
                        sb.append(sb.length() == 0 ? "" : " | ").append(line.trim());
                    }
                }
            } catch (Throwable ignored) {
                // The command still ran; its exit code is what matters.
            }
            into.set(sb.toString().trim());
        }, "zux-desktop-plus-su-out");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }
}
