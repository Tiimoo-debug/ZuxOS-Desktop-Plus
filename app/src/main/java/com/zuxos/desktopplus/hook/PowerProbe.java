package com.zuxos.desktopplus.hook;

import android.app.WallpaperInfo;
import android.app.WallpaperManager;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import android.view.View;
import android.view.WindowManager;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Su;
import com.zuxos.desktopplus.core.Thermals;
import com.zuxos.desktopplus.core.glass.ScreenBackdrop;
import com.zuxos.desktopplus.core.motion.FrameRate;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * What the screens, the module and the device are spending, for the probe: the refresh rates,
 * the rates our windows ask for, the wallpaper, the glass's captures, the temperatures, the CPU
 * each thread of this process uses, and - through root - each process and the GPU's load.
 *
 * <p>Read-only: it changes nothing it looks at. What needs time - two readings of the CPU
 * counters a moment apart, and the root shell - runs off the main thread, and only when someone
 * asks for a probe.
 */
public final class PowerProbe {

    /** How long the CPU counters are watched: long enough to average out a single frame. */
    private static final long SAMPLE_MS = 2000L;
    /** Threads listed, busiest first. */
    private static final int TOP_THREADS = 10;
    /** Longest the probe waits for root before it is written without it. */
    private static final long ROOT_WAIT_MS = 15_000L;
    private static final String GPU = "/sys/class/kgsl/kgsl-3d0/";
    /** The busiest processes on the device, then the GPU's load and clock where readable. */
    private static final String ROOT_READ = "top -b -n 1 -d 2 -m 12 2>&1; echo;"
            + " for f in gpu_busy_percentage devfreq/cur_freq devfreq/max_freq; do"
            + " echo \"gpu $f: $(cat " + GPU + "$f 2>/dev/null)\"; done";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private PowerProbe() {
    }

    /** The part read off windows and the glass: main thread, at once. */
    public static String now(Context ctx) {
        StringBuilder sb = new StringBuilder("\npower\n");
        sb.append("  wallpaper: ").append(wallpaper(ctx)).append('\n');
        sb.append(FrameRate.describe(ctx));
        sb.append("\nrefresh rates our windows ask for\n");
        for (View root : Windows.roots()) {
            if (!(root.getLayoutParams() instanceof WindowManager.LayoutParams)) {
                continue;
            }
            WindowManager.LayoutParams lp = (WindowManager.LayoutParams) root.getLayoutParams();
            sb.append("  ").append(lp.getTitle())
                    .append(" on display ")
                    .append(root.getDisplay() != null ? root.getDisplay().getDisplayId() : -1)
                    .append(root.isShown() ? ", shown" : ", hidden")
                    .append(": rate ").append(lp.preferredRefreshRate == 0f ? "any"
                            : String.format(Locale.ROOT, "%.0f Hz", lp.preferredRefreshRate))
                    .append(", mode ").append(lp.preferredDisplayModeId == 0 ? "any"
                            : String.valueOf(lp.preferredDisplayModeId));
            if (Build.VERSION.SDK_INT >= 35) {
                try {
                    sb.append(", view asks ").append(root.getRequestedFrameRate());
                } catch (Throwable ignored) {
                    // Not on this build.
                }
            }
            sb.append('\n');
        }
        sb.append(ScreenBackdrop.describe());
        return sb.toString();
    }

    /**
     * The part that takes time - this process's threads over two seconds, the temperatures,
     * and the device through root - handed to {@code onDone} on the main thread.
     */
    public static void sample(Context ctx, Consumer<String> onDone) {
        StringBuilder sb = new StringBuilder();
        AtomicBoolean done = new AtomicBoolean();
        // Once, whichever way it ends: root answering, root timing out, or something failing.
        Runnable finish = () -> {
            if (done.compareAndSet(false, true)) {
                MAIN.post(() -> {
                    String text;
                    synchronized (sb) {
                        text = sb.toString();
                    }
                    try {
                        onDone.accept(text);
                    } catch (Throwable t) {
                        L.e("power probe: could not hand over the result", t);
                    }
                });
            }
        };
        new Thread(() -> {
            // Nothing thrown here may escape: an uncaught throw on any thread ends the launcher.
            try {
                String counted = threads() + Thermals.describe();
                synchronized (sb) {
                    sb.append(counted);
                }
                if (!Cfg.useRoot()) {
                    synchronized (sb) {
                        sb.append("\ndevice (root)\n  skipped: the root setting is off\n");
                    }
                    finish.run();
                    return;
                }
                MAIN.postDelayed(() -> {
                    synchronized (sb) {
                        if (done.get()) {
                            return;
                        }
                        sb.append("\ndevice (root)\n  no answer from root in ")
                                .append(ROOT_WAIT_MS / 1000).append(" s\n");
                    }
                    finish.run();
                }, ROOT_WAIT_MS);
                Su.read((outcome, text) -> {
                    synchronized (sb) {
                        if (done.get()) {
                            return;
                        }
                        sb.append("\ndevice (root)\n");
                        if (outcome.ok() && text != null) {
                            for (String line : text.split("\n")) {
                                sb.append("  ").append(line).append('\n');
                            }
                        } else {
                            sb.append("  not read (").append(outcome).append(")\n");
                        }
                    }
                    finish.run();
                }, ROOT_READ);
            } catch (Throwable t) {
                synchronized (sb) {
                    sb.append("\n  (could not sample: ").append(t).append(")\n");
                }
                finish.run();
            }
        }, "zux-power-probe").start();
    }

    private static String wallpaper(Context ctx) {
        try {
            WallpaperInfo info = WallpaperManager.getInstance(ctx).getWallpaperInfo();
            return info == null ? "still image"
                    : "live, " + info.getComponent().flattenToShortString();
        } catch (Throwable t) {
            return "unreadable (" + t + ")";
        }
    }

    /** CPU per thread of this process over {@link #SAMPLE_MS}, busiest first. */
    private static String threads() throws InterruptedException {
        Map<String, long[]> before = threadTicks();
        long start = SystemClock.elapsedRealtime();
        Thread.sleep(SAMPLE_MS);
        Map<String, long[]> after = threadTicks();
        float seconds = (SystemClock.elapsedRealtime() - start) / 1000f;
        long hz = Os.sysconf(OsConstants._SC_CLK_TCK);
        List<Object[]> busy = new ArrayList<>();
        float total = 0f;
        for (Map.Entry<String, long[]> e : after.entrySet()) {
            long[] was = before.get(e.getKey());
            long ticks = e.getValue()[0] - (was != null ? was[0] : 0L);
            float percent = ticks * 100f / hz / seconds;
            total += percent;
            if (ticks > 0) {
                busy.add(new Object[]{percent, e.getKey()});
            }
        }
        busy.sort((a, b) -> Float.compare((Float) b[0], (Float) a[0]));
        StringBuilder sb = new StringBuilder("\nthis process's threads over ")
                .append(String.format(Locale.ROOT, "%.1f", seconds)).append(" s (")
                .append(String.format(Locale.ROOT, "%.0f", total))
                .append("% of one core in all)\n");
        for (int i = 0; i < Math.min(TOP_THREADS, busy.size()); i++) {
            sb.append(String.format(Locale.ROOT, "  %5.1f%%  %s%n", (Float) busy.get(i)[0],
                    busy.get(i)[1]));
        }
        if (busy.isEmpty()) {
            sb.append("  (idle, or the counters are unreadable)\n");
        }
        return sb.toString();
    }

    /** Every thread of this process: "name (tid)" to its user + system CPU ticks so far. */
    private static Map<String, long[]> threadTicks() {
        Map<String, long[]> out = new HashMap<>();
        File[] tasks = new File("/proc/self/task").listFiles();
        if (tasks == null) {
            return out;
        }
        for (File task : tasks) {
            try {
                String stat = new String(Files.readAllBytes(new File(task, "stat").toPath()),
                        StandardCharsets.US_ASCII);
                // "tid (name) state ..." - the name may hold spaces or brackets of its own.
                int open = stat.indexOf('(');
                int close = stat.lastIndexOf(')');
                String[] rest = stat.substring(close + 2).split(" ");
                // After the name: state is field 3, utime 14 and stime 15.
                long ticks = Long.parseLong(rest[11]) + Long.parseLong(rest[12]);
                out.put(stat.substring(open + 1, close) + " (" + task.getName() + ")",
                        new long[]{ticks});
            } catch (Throwable ignored) {
                // A thread that ended between the listing and the read.
            }
        }
        return out;
    }
}
