package com.zuxos.desktopplus.core;

import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.SurfaceControl;
import android.view.View;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.function.ObjIntConsumer;

/**
 * What is on screen behind a pane, live - including other apps' windows.
 *
 * <p>A window cannot draw what another app's window shows; that is the wall every glass effect on
 * Android runs into, and why the system's own blur is all a floating pane normally gets. The window
 * manager can, though: {@code IWindowManager.captureDisplay} renders the display through
 * SurfaceFlinger, cropped and scaled, leaving out any layers it is told to - here, the pane's own
 * window, so the pane never sees itself. It is guarded by {@code READ_FRAME_BUFFER}, which Android
 * grants to the device's recents app; ZUI's launcher is that app, and this code runs inside it.
 *
 * <p>Captures are paced by {@link Choreographer}: at most one in flight, at the pane's frame rate
 * while it is on screen, backing off when a capture takes longer than a frame, and stopping
 * entirely while the pane is hidden. Whether the device allows it at all is found out once, on the
 * first try, and written to the log; a refusal leaves every pane on the system blur instead.
 */
public final class ScreenBackdrop {

    /** Where captured frames go: called on the main thread. */
    public interface Sink {
        /**
         * @param frame  what is behind the pane; drawn scaled up to the pane's size
         */
        void onFrame(Bitmap frame);

        /** The device will not capture; the pane should fall back to the system blur. */
        void onRefused();
    }

    private static final int UNKNOWN = 0;
    private static final int WORKS = 1;
    private static final int REFUSED = -1;
    private static volatile int sState = UNKNOWN;
    private static volatile String sReason;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Handler sWorker;

    private static Method sCapture;
    private static Object sWm;
    private static Constructor<?> sListener;
    private static Class<?> sBuilder;

    private ScreenBackdrop() {
    }

    /** True once a capture has been refused on this device; nothing will be tried again. */
    public static boolean refused() {
        return sState == REFUSED;
    }

    /** Why, for the log and the probe. */
    public static String reason() {
        return sReason;
    }

    private static synchronized Handler worker() {
        if (sWorker == null) {
            HandlerThread thread = new HandlerThread("zux-glass-capture",
                    android.os.Process.THREAD_PRIORITY_DISPLAY);
            thread.start();
            sWorker = new Handler(thread.getLooper());
        }
        return sWorker;
    }

    /** Every running session, so an event that changes the screen can wake them all. */
    private static final java.util.Set<Session> SESSIONS =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    /**
     * Something on screen is about to change - an app opened, closed or moved: every pane goes
     * back to full rate and takes a real capture next, without waiting for its probe.
     */
    public static void nudge() {
        MAIN.post(() -> {
            for (Session s : new java.util.ArrayList<>(SESSIONS)) {
                s.wake();
            }
        });
    }

    /** One pane's live backdrop. Start it when the pane is attached, stop it when it is not. */
    public static final class Session implements Choreographer.FrameCallback {

        /** Past this many pixels a pane is captured smaller: the frost would hide the detail. */
        private static final int LARGE_AREA = 600_000;
        /** Slowest the glass checks for change while nothing behind it moves. */
        private static final long IDLE_MS = 250L;

        private final View mPane;
        private final Sink mSink;
        private final float mScale;
        private final long mBaseIntervalMs;
        private long mIntervalMs;
        private boolean mRunning;
        private boolean mInFlight;
        private long mFlightSince;
        private long mLastStart;
        /** The next cycle skips the probe and captures for real. */
        private boolean mForce = true;
        private final Rect mLastCrop = new Rect();
        /** Hash of the last probe; written and read on the worker only. */
        volatile long mProbeHash;
        /**
         * Frames handed out, oldest first. A frame is recycled only once two newer ones have been
         * shown: the render thread may still be drawing the one just replaced.
         */
        private final ArrayDeque<Bitmap> mRetired = new ArrayDeque<>();
        private Bitmap mCurrent;

        private long mStartedAt;
        private int mCaptures;
        private int mSkipped;
        private long mCaptureMs;
        private boolean mReported;

        /**
         * @param scale      how much smaller than the screen the capture is; the frost blurs it
         *                   anyway, so half size costs a quarter and looks the same
         * @param intervalMs the fastest the pane is refreshed while what is behind it moves: a
         *                   frame (16) for panes you look at, two (33) for the always-on taskbar
         */
        public Session(View pane, Sink sink, float scale, long intervalMs) {
            mPane = pane;
            mSink = sink;
            mScale = scale;
            mBaseIntervalMs = intervalMs;
            mIntervalMs = intervalMs;
        }

        public void start() {
            if (mRunning || sState == REFUSED) {
                if (sState == REFUSED) {
                    mSink.onRefused();
                }
                return;
            }
            mRunning = true;
            mForce = true;
            mStartedAt = SystemClock.uptimeMillis();
            SESSIONS.add(this);
            // The first picture as soon as the pane can be measured, not a frame later: a pane
            // with nothing behind it yet is a pane with no background.
            if (showing()) {
                request(mStartedAt);
            }
            Choreographer.getInstance().postFrameCallback(this);
        }

        public void stop() {
            mRunning = false;
            SESSIONS.remove(this);
            Choreographer.getInstance().removeFrameCallback(this);
            for (Bitmap b : mRetired) {
                b.recycle();
            }
            mRetired.clear();
            // The current frame may be in the last frame drawn; it is left to the collector.
            mCurrent = null;
        }

        void wake() {
            mIntervalMs = mBaseIntervalMs;
            mForce = true;
        }

        private boolean showing() {
            return mPane.isAttachedToWindow() && mPane.isShown()
                    && mPane.getWidth() > 0 && mPane.getHeight() > 0;
        }

        @Override
        public void doFrame(long frameTimeNanos) {
            if (!mRunning) {
                return;
            }
            if (sState == REFUSED) {
                mRunning = false;
                mSink.onRefused();
                return;
            }
            long now = SystemClock.uptimeMillis();
            if (showing()) {
                // A capture that never answered is given up on after a second.
                boolean free = !mInFlight || now - mFlightSince > 1000;
                if (free && now - mLastStart >= mIntervalMs) {
                    request(now);
                }
                report(now);
                Choreographer.getInstance().postFrameCallback(this);
            } else {
                // Nothing to show it on: look again in a while instead of every frame.
                mForce = true;
                Choreographer.getInstance().postFrameCallbackDelayed(this, IDLE_MS);
            }
        }

        private void request(long now) {
            SurfaceControl own = surfaceOf(mPane);
            if (own == null || mPane.getDisplay() == null) {
                // Never without leaving our own window out: a pane that captured itself would
                // feed back into itself every frame.
                return;
            }
            int[] at = new int[2];
            mPane.getLocationOnScreen(at);
            Rect crop = new Rect(at[0], at[1], at[0] + mPane.getWidth(),
                    at[1] + mPane.getHeight());
            if (!crop.equals(mLastCrop)) {
                // Moved or resized: what is behind it is new, whatever the probe would say.
                mLastCrop.set(crop);
                mForce = true;
                mIntervalMs = mBaseIntervalMs;
            }
            float scale = (long) crop.width() * crop.height() > LARGE_AREA
                    ? Math.min(mScale, 0.35f) : mScale;
            int display = mPane.getDisplay().getDisplayId();
            boolean force = mForce;
            mForce = false;
            mInFlight = true;
            mFlightSince = now;
            mLastStart = now;
            worker().post(() -> cycle(this, display, crop, own, scale, force, now));
        }

        /** Nothing behind the pane changed: no capture, and the next check comes later. */
        void skipped() {
            mInFlight = false;
            mSkipped++;
            mIntervalMs = Math.min(IDLE_MS, Math.max(mBaseIntervalMs, mIntervalMs * 2));
        }

        void deliver(Object shot, int status, Rect crop, long started) {
            mInFlight = false;
            if (!mRunning) {
                close(shot);
                return;
            }
            Bitmap frame = shot != null && status == 0 ? toBitmap(shot) : null;
            if (frame == null) {
                if (sState == UNKNOWN) {
                    refuse("the window manager answered with status " + status);
                    mRunning = false;
                    mSink.onRefused();
                }
                return;
            }
            long took = SystemClock.uptimeMillis() - started;
            if (sState == UNKNOWN) {
                sState = WORKS;
                L.i("liquid glass: live screen capture works (" + took + "ms at "
                        + frame.getWidth() + "x" + frame.getHeight() + ")");
            }
            mCaptures++;
            mCaptureMs += took;
            // Something changed: back to full rate.
            mIntervalMs = took > mBaseIntervalMs * 3 / 2
                    ? Math.min(66, mBaseIntervalMs * 2) : mBaseIntervalMs;
            if (mCurrent != null) {
                mRetired.addLast(mCurrent);
                while (mRetired.size() > 2) {
                    mRetired.removeFirst().recycle();
                }
            }
            mCurrent = frame;
            mSink.onFrame(frame);
        }

        /** Once per session, after its first ten seconds: what the glass actually cost. */
        private void report(long now) {
            if (mReported || now - mStartedAt < 10_000) {
                return;
            }
            mReported = true;
            View parent = mPane.getParent() instanceof View ? (View) mPane.getParent() : mPane;
            L.i("liquid glass: " + parent.getClass().getSimpleName() + " - " + mCaptures
                    + " captures, " + mSkipped + " skipped as unchanged in 10s, avg "
                    + (mCaptures > 0 ? mCaptureMs / mCaptures : 0) + "ms");
        }
    }

    /**
     * One cycle on the worker: a tiny probe first, and a real capture only if the probe shows
     * that something behind the pane changed since the last one.
     */
    private static void cycle(Session session, int display, Rect crop, SurfaceControl own,
            float scale, boolean force, long started) {
        if (!force && sState == WORKS) {
            long hash = probe(display, crop, own);
            if (hash != 0 && hash == session.mProbeHash) {
                MAIN.post(session::skipped);
                return;
            }
            session.mProbeHash = hash;
        } else {
            session.mProbeHash = 0;
        }
        capture(session, display, crop, own, scale, started);
    }

    /**
     * A fingerprint of what is behind the pane, from a capture a few dozen pixels across. Costs a
     * fraction of a real one, and is all a static desktop ever pays for.
     *
     * @return 0 when it could not be taken, which counts as "changed"
     */
    private static long probe(int display, Rect crop, SurfaceControl own) {
        Object[] got = new Object[1];
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        try {
            float scale = Math.min(1f, 64f / Math.max(1, Math.max(crop.width(), crop.height())));
            Object builder = sBuilder.getConstructor().newInstance();
            sBuilder.getMethod("setSourceCrop", Rect.class).invoke(builder, crop);
            sBuilder.getMethod("setFrameScale", float.class).invoke(builder, scale);
            sBuilder.getMethod("setExcludeLayers", SurfaceControl[].class)
                    .invoke(builder, (Object) new SurfaceControl[]{own});
            Object args = sBuilder.getMethod("build").invoke(builder);
            ObjIntConsumer<Object> answer = (shot, status) -> {
                got[0] = status == 0 ? shot : null;
                if (status != 0) {
                    close(shot);
                }
                done.countDown();
            };
            sCapture.invoke(sWm, display, args, sListener.newInstance(answer));
            if (!done.await(250, java.util.concurrent.TimeUnit.MILLISECONDS) || got[0] == null) {
                return 0;
            }
            Bitmap hardware = toBitmap(got[0]);
            if (hardware == null) {
                return 0;
            }
            Bitmap soft = hardware.copy(Bitmap.Config.ARGB_8888, false);
            hardware.recycle();
            if (soft == null) {
                return 0;
            }
            int[] px = new int[soft.getWidth() * soft.getHeight()];
            soft.getPixels(px, 0, soft.getWidth(), 0, 0, soft.getWidth(), soft.getHeight());
            soft.recycle();
            long h = 0xcbf29ce484222325L;
            for (int p : px) {
                h = (h ^ p) * 0x100000001b3L;
            }
            return h == 0 ? 1 : h;
        } catch (Throwable t) {
            return 0;
        }
    }

    // --- the capture itself, on the worker thread ------------------------------------------------

    private static void capture(Session session, int display, Rect crop, SurfaceControl own,
            float scale, long started) {
        try {
            if (!bind()) {
                MAIN.post(() -> session.deliver(null, -1, crop, started));
                return;
            }
            Object builder = sBuilder.getConstructor().newInstance();
            sBuilder.getMethod("setSourceCrop", Rect.class).invoke(builder, crop);
            sBuilder.getMethod("setFrameScale", float.class).invoke(builder, scale);
            sBuilder.getMethod("setExcludeLayers", SurfaceControl[].class)
                    .invoke(builder, (Object) new SurfaceControl[]{own});
            Object args = sBuilder.getMethod("build").invoke(builder);
            ObjIntConsumer<Object> answer = (shot, status) ->
                    MAIN.post(() -> session.deliver(shot, status, crop, started));
            Object listener = sListener.newInstance(answer);
            sCapture.invoke(sWm, display, args, listener);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            refuse(cause.getClass().getSimpleName() + ": " + cause.getMessage());
            MAIN.post(() -> session.deliver(null, -1, crop, started));
        } catch (Throwable t) {
            refuse(t.toString());
            MAIN.post(() -> session.deliver(null, -1, crop, started));
        }
    }

    /** Finds the hidden pieces once. */
    private static synchronized boolean bind() {
        if (sCapture != null) {
            return true;
        }
        try {
            sWm = Class.forName("android.view.WindowManagerGlobal")
                    .getMethod("getWindowManagerService").invoke(null);
            Class<?> argsClass = Class.forName("android.window.ScreenCapture$CaptureArgs");
            Class<?> listenerClass =
                    Class.forName("android.window.ScreenCapture$ScreenCaptureListener");
            sBuilder = Class.forName("android.window.ScreenCapture$CaptureArgs$Builder");
            sListener = listenerClass.getConstructor(ObjIntConsumer.class);
            for (Method m : sWm.getClass().getMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (m.getName().equals("captureDisplay") && p.length == 3 && p[0] == int.class
                        && p[1] == argsClass && p[2] == listenerClass) {
                    sCapture = m;
                    break;
                }
            }
            if (sCapture == null) {
                refuse("this build's window manager has no captureDisplay(int, CaptureArgs, "
                        + "listener)");
                return false;
            }
            return true;
        } catch (Throwable t) {
            refuse("the capture API is not here (" + t + ")");
            return false;
        }
    }

    private static void refuse(String why) {
        if (sState == REFUSED) {
            return;
        }
        sState = REFUSED;
        sReason = why;
        L.i("liquid glass: capture refused (" + why + ") - using the system blur");
    }

    private static Bitmap toBitmap(Object shot) {
        try {
            HardwareBuffer buffer = (HardwareBuffer) shot.getClass()
                    .getMethod("getHardwareBuffer").invoke(shot);
            ColorSpace space = (ColorSpace) shot.getClass().getMethod("getColorSpace")
                    .invoke(shot);
            if (buffer == null) {
                return null;
            }
            Bitmap bitmap = Bitmap.wrapHardwareBuffer(buffer,
                    space != null ? space : ColorSpace.get(ColorSpace.Named.SRGB));
            // The bitmap holds its own reference to the buffer.
            buffer.close();
            return bitmap;
        } catch (Throwable t) {
            L.d("liquid glass: could not read a captured frame (" + t + ")");
            return null;
        }
    }

    private static void close(Object shot) {
        if (shot == null) {
            return;
        }
        try {
            Object buffer = shot.getClass().getMethod("getHardwareBuffer").invoke(shot);
            if (buffer instanceof HardwareBuffer) {
                ((HardwareBuffer) buffer).close();
            }
        } catch (Throwable ignored) {
            // Gone already.
        }
    }

    /** The window's own layer, which every capture for a pane in it leaves out. */
    public static SurfaceControl surfaceOf(View view) {
        try {
            Object root = View.class.getMethod("getViewRootImpl").invoke(view);
            Object surface = root == null ? null
                    : root.getClass().getMethod("getSurfaceControl").invoke(root);
            return surface instanceof SurfaceControl && ((SurfaceControl) surface).isValid()
                    ? (SurfaceControl) surface : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
