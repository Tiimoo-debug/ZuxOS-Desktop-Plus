package com.zuxos.desktopplus.core;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.view.Choreographer;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;

import java.util.Locale;

/**
 * Every window the module draws asks for the monitor's fastest refresh rate, so its motion runs
 * at what the screen can show - 165 frames a second on a 165 Hz monitor - rather than whatever
 * the system settled on for a window that did not say.
 *
 * <p>Animations here are all driven by the frame clock, so they need nothing more than this: a
 * faster screen means more frames of the same motion.
 */
public final class FrameRate {

    private static final StringBuilder MEASURED = new StringBuilder();

    private FrameRate() {
    }

    /** The display's fastest mode at the resolution it is running now, or null. */
    public static Display.Mode fastest(Display display) {
        if (display == null) {
            return null;
        }
        Display.Mode now = display.getMode();
        Display.Mode best = now;
        for (Display.Mode m : display.getSupportedModes()) {
            if (m.getPhysicalWidth() == now.getPhysicalWidth()
                    && m.getPhysicalHeight() == now.getPhysicalHeight()
                    && m.getRefreshRate() > best.getRefreshRate() + 0.5f) {
                best = m;
            }
        }
        return best;
    }

    /** A window of ours, before it is added: the display's fastest mode, kept up while it is up. */
    public static void forWindow(WindowManager.LayoutParams lp, Display display) {
        Display.Mode best = fastest(display);
        if (best == null) {
            return;
        }
        lp.preferredDisplayModeId = best.getModeId();
        lp.preferredRefreshRate = best.getRefreshRate();
        if (Build.VERSION.SDK_INT >= 35) {
            try {
                lp.setFrameRateBoostOnTouchEnabled(true);
            } catch (Throwable ignored) {
                // Not on this build.
            }
        }
    }

    /** The same for a window that is always up - the taskbar: the rate only, never the mode. */
    public static void rateOnly(WindowManager.LayoutParams lp, Display display) {
        Display.Mode best = fastest(display);
        if (best != null) {
            lp.preferredRefreshRate = best.getRefreshRate();
        }
    }

    /** The window's own content asks for the high frame rate category as it animates. */
    public static void forView(View root) {
        if (Build.VERSION.SDK_INT >= 35) {
            try {
                root.setRequestedFrameRate(View.REQUESTED_FRAME_RATE_CATEGORY_HIGH);
            } catch (Throwable ignored) {
                // Not on this build.
            }
        }
    }

    /**
     * Counts the frames the clock actually delivers for half a second from now and logs them
     * against the monitor's rate - the proof that motion here runs at what the screen shows.
     */
    public static void measure(View view, String what) {
        Display display = view.getDisplay();
        if (display == null) {
            return;
        }
        final long[] first = {0L};
        final int[] frames = {0};
        Choreographer.getInstance().postFrameCallback(new Choreographer.FrameCallback() {
            @Override
            public void doFrame(long nanos) {
                if (first[0] == 0L) {
                    first[0] = nanos;
                }
                frames[0]++;
                long span = nanos - first[0];
                if (span < 500_000_000L) {
                    Choreographer.getInstance().postFrameCallback(this);
                    return;
                }
                float fps = (frames[0] - 1) * 1e9f / span;
                String line = String.format(Locale.ROOT,
                        "%s animated at ~%.0f fps on display %d (monitor at %.0f Hz)", what, fps,
                        display.getDisplayId(), display.getRefreshRate());
                L.i("frame rate: " + line);
                synchronized (MEASURED) {
                    MEASURED.setLength(0);
                    MEASURED.append(line);
                }
            }
        });
    }

    /** Every display's modes, what is running and what we ask for - for the probe. */
    public static String describe(Context ctx) {
        StringBuilder sb = new StringBuilder("\nframe rate\n");
        try {
            DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            for (Display d : dm.getDisplays()) {
                Display.Mode now = d.getMode();
                Display.Mode best = fastest(d);
                sb.append("  display ").append(d.getDisplayId()).append(": now ")
                        .append(mode(now)).append(", asking for ").append(mode(best))
                        .append("\n    modes:");
                for (Display.Mode m : d.getSupportedModes()) {
                    sb.append(' ').append(mode(m));
                }
                sb.append('\n');
            }
        } catch (Throwable t) {
            sb.append("  could not read displays (").append(t).append(")\n");
        }
        synchronized (MEASURED) {
            sb.append("  last measured: ").append(MEASURED.length() == 0 ? "nothing yet" : MEASURED)
                    .append('\n');
        }
        return sb.toString();
    }

    private static String mode(Display.Mode m) {
        return m == null ? "?" : String.format(Locale.ROOT, "%dx%d@%.0f", m.getPhysicalWidth(),
                m.getPhysicalHeight(), m.getRefreshRate());
    }
}
