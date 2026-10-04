package com.zuxos.desktopplus.core;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Point;
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
import android.view.ViewTreeObserver;

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
            seedSoon(0L);
            for (Session s : new java.util.ArrayList<>(SESSIONS)) {
                s.wake();
            }
        });
    }

    // --- the seed: what a pane shows on its very first frame -----------------------------------

    /**
     * A small picture of the whole display, kept fresh in the background, so a pane that has just
     * opened has something to frost on its first frame.
     *
     * <p>A pane's own capture can only be asked for once it is on screen, and answers a frame
     * later at best - and a pane with nothing behind it draws nothing at all: the drawer slid up
     * as bare icons and the glass arrived when it stopped. The frost blurs tens of pixels wide,
     * so an eighth of the screen's size is all a first frame needs; the pane's own capture
     * replaces it on the next.
     */
    private static final float SEED_SCALE = 0.125f;
    /** How often the seed is renewed while the screen may be changing under it. */
    private static final long SEED_MS = 2000L;

    private static final class Seed {
        Bitmap bitmap;
        /** The display's width when the picture was taken, which maps screen to picture. */
        int screenWidth;
        long due;
        boolean inFlight;
        /** Older pictures, freed once two newer ones exist: one may still be drawing. */
        final ArrayDeque<Bitmap> retired = new ArrayDeque<>();
    }

    /** Per display; main thread only. */
    private static final android.util.SparseArray<Seed> SEEDS = new android.util.SparseArray<>();

    private static Seed seedFor(int display) {
        Seed seed = SEEDS.get(display);
        if (seed == null) {
            seed = new Seed();
            SEEDS.put(display, seed);
        }
        return seed;
    }

    /** The screen is changing: renew every seed after {@code delayMs}. */
    private static void seedSoon(long delayMs) {
        long due = SystemClock.uptimeMillis() + delayMs;
        for (int i = 0; i < SEEDS.size(); i++) {
            Seed seed = SEEDS.valueAt(i);
            seed.due = Math.min(seed.due, due);
        }
    }

    /** Renews the display's seed if it is due, leaving out {@code own}, the asking window. */
    private static void maybeSeed(View pane, SurfaceControl own, long now) {
        if (sState != WORKS || pane.getDisplay() == null) {
            return;
        }
        int display = pane.getDisplay().getDisplayId();
        Seed seed = seedFor(display);
        if (seed.inFlight || now < seed.due) {
            return;
        }
        seed.inFlight = true;
        seed.due = now + SEED_MS;
        Point size = new Point();
        pane.getDisplay().getRealSize(size);
        Rect crop = new Rect(0, 0, size.x, size.y);
        worker().post(() -> {
            Object shot = grab(display, crop, own, SEED_SCALE);
            MAIN.post(() -> {
                seed.inFlight = false;
                Bitmap bitmap = shot != null ? toBitmap(shot) : null;
                if (bitmap == null) {
                    return;
                }
                if (seed.bitmap != null) {
                    seed.retired.addLast(seed.bitmap);
                    while (seed.retired.size() > 2) {
                        seed.retired.removeFirst().recycle();
                    }
                }
                seed.bitmap = bitmap;
                seed.screenWidth = crop.width();
            });
        });
    }

    /**
     * Draws the part of the seed that is behind {@code view} into {@code dst}.
     *
     * @return false when there is no seed for its display yet
     */
    public static boolean drawSeed(Canvas canvas, View view, Rect dst, Paint paint) {
        if (view.getDisplay() == null) {
            return false;
        }
        Seed seed = SEEDS.get(view.getDisplay().getDisplayId());
        Bitmap bitmap = seed != null ? seed.bitmap : null;
        if (bitmap == null || bitmap.isRecycled()) {
            return false;
        }
        int[] at = new int[2];
        view.getLocationOnScreen(at);
        float sx = bitmap.getWidth() / (float) Math.max(1, seed.screenWidth);
        Rect src = new Rect(Math.round(at[0] * sx), Math.round(at[1] * sx),
                Math.round((at[0] + view.getWidth()) * sx),
                Math.round((at[1] + view.getHeight()) * sx));
        if (!src.intersect(0, 0, bitmap.getWidth(), bitmap.getHeight())) {
            return false;
        }
        canvas.drawBitmap(bitmap, src, dst, paint);
        return true;
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

        /** Hidden: no frame callbacks until the window draws with the pane shown again. */
        private boolean mParked;
        private ViewTreeObserver mObserver;
        private final ViewTreeObserver.OnPreDrawListener mPreDraw = () -> {
            onPreDraw();
            return true;
        };

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
            // The first picture is asked for just before the pane's first frame is drawn, the
            // moment it has a place on screen - not on the next frame callback, which came a
            // frame later, or a quarter of a second later for a pane not laid out yet.
            mObserver = mPane.getViewTreeObserver();
            if (mObserver.isAlive()) {
                mObserver.addOnPreDrawListener(mPreDraw);
            }
            if (showing()) {
                request(mStartedAt);
            }
            Choreographer.getInstance().postFrameCallback(this);
        }

        /**
         * The window is about to draw. A pane that has just been shown - laid out for the first
         * time, made visible, its window brought back - asks for its picture now.
         */
        private void onPreDraw() {
            if (!mRunning || !showing()) {
                return;
            }
            long now = SystemClock.uptimeMillis();
            if (mParked) {
                mParked = false;
                mForce = true;
                mIntervalMs = mBaseIntervalMs;
                Choreographer.getInstance().removeFrameCallback(this);
                Choreographer.getInstance().postFrameCallback(this);
                if (!mInFlight) {
                    request(now);
                }
            } else if (mCurrent == null && !mInFlight) {
                request(now);
            }
        }

        public void stop() {
            mRunning = false;
            SESSIONS.remove(this);
            Choreographer.getInstance().removeFrameCallback(this);
            if (mObserver != null && mObserver.isAlive()) {
                mObserver.removeOnPreDrawListener(mPreDraw);
            }
            mObserver = null;
            // The pane leaving changes the screen; renew the seed once its exit has played.
            seedSoon(400L);
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
                mParked = false;
                if (free && now - mLastStart >= mIntervalMs) {
                    request(now);
                }
                SurfaceControl own = surfaceOf(mPane);
                if (own != null) {
                    maybeSeed(mPane, own, now);
                }
                report(now);
                Choreographer.getInstance().postFrameCallback(this);
            } else {
                // Nothing to show it on. The window drawing it shown again wakes it at once
                // (onPreDraw); the slow look is only a backstop.
                mForce = true;
                mParked = true;
                Choreographer.getInstance().postFrameCallbackDelayed(this, 1000L);
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

    /** One capture, waited for; null if it failed. Worker thread only. */
    private static Object grab(int display, Rect crop, SurfaceControl own, float scale) {
        Object[] got = new Object[1];
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean late =
                new java.util.concurrent.atomic.AtomicBoolean();
        try {
            if (!bind()) {
                return null;
            }
            Object builder = sBuilder.getConstructor().newInstance();
            sBuilder.getMethod("setSourceCrop", Rect.class).invoke(builder, crop);
            sBuilder.getMethod("setFrameScale", float.class).invoke(builder, scale);
            sBuilder.getMethod("setExcludeLayers", SurfaceControl[].class)
                    .invoke(builder, (Object) new SurfaceControl[]{own});
            Object args = sBuilder.getMethod("build").invoke(builder);
            ObjIntConsumer<Object> answer = (shot, status) -> {
                synchronized (got) {
                    if (status == 0 && !late.get()) {
                        got[0] = shot;
                    } else {
                        // Failed, or answered after we stopped waiting: nobody will use it.
                        close(shot);
                    }
                }
                done.countDown();
            };
            sCapture.invoke(sWm, display, args, sListener.newInstance(answer));
            if (!done.await(250, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                synchronized (got) {
                    late.set(true);
                    close(got[0]);
                    return null;
                }
            }
            return got[0];
        } catch (Throwable t) {
            return null;
        }
    }

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
