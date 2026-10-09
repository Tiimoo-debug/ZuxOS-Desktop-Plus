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
 * each thread of this process uses, and - through root - each process, the GPU's load and the
 * power policy the device itself runs.
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
    /**
     * Longest the probe waits for root before it is written without it: longer than the root
     * shell's own limit, so a slow read on a hot, throttled device still lands in the probe.
     */
    private static final long ROOT_WAIT_MS = 25_000L;
    /**
     * Read through root, and only read: nothing here writes, lowers or tests anything.
     *
     * <p>First what is busy - the device's busiest processes and the GPU's load. Then the policy
     * the device runs: each CPU cluster's governor and limits, the GPU's, which thermal limits
     * are holding something back right now, the vendor's thermal and perf configs, ZUI's own
     * (its performance service's heat, cleaning and refresh-rate lists, the system's memory
     * cleaner and its kill whitelists) and the apps force-stopped or cleaned lately, the
     * properties, whether the kernel offers any control of voltage at all - the question behind
     * undervolting, answered by looking, never by trying - and Android's own thermal service.
     * Last, the clusters again, seconds later, and the apps holding a root shell: a minimum still
     * at the top is held there, and those are who can hold it. Missing files print nothing; the
     * script always ends well, so a partial answer still comes.
     */
    private static final String ROOT_READ = """
            top -b -n 1 -d 2 -m 12 2>&1
            g=/sys/class/kgsl/kgsl-3d0
            echo
            echo "gpu busy $(cat $g/gpu_busy_percentage 2>/dev/null), clock $(cat $g/devfreq/cur_freq 2>/dev/null)"
            echo
            echo "--- cpu clusters"
            uname -r
            for p in /sys/devices/system/cpu/cpufreq/policy*; do
              echo "${p##*/} cpus $(cat $p/related_cpus 2>/dev/null): $(cat $p/scaling_governor 2>/dev/null), now $(cat $p/scaling_cur_freq 2>/dev/null), min $(cat $p/scaling_min_freq 2>/dev/null), max $(cat $p/scaling_max_freq 2>/dev/null), hardware max $(cat $p/cpuinfo_max_freq 2>/dev/null)"
            done
            echo "--- gpu"
            echo "governor $(cat $g/devfreq/governor 2>/dev/null), min $(cat $g/devfreq/min_freq 2>/dev/null), max $(cat $g/devfreq/max_freq 2>/dev/null), thermal level $(cat $g/thermal_pwrlevel 2>/dev/null), throttling $(cat $g/throttling 2>/dev/null)"
            echo "steps $(cat $g/devfreq/available_frequencies 2>/dev/null)"
            echo "--- held back now (cooling devices not at 0)"
            for c in /sys/class/thermal/cooling_device*; do
              s=$(cat $c/cur_state 2>/dev/null)
              [ -n "$s" ] && [ "$s" != "0" ] && echo "$(cat $c/type 2>/dev/null): $s of $(cat $c/max_state 2>/dev/null)"
            done | head -30
            echo "--- gpu thermal trips"
            for z in /sys/class/thermal/thermal_zone*; do
              t=$(cat $z/type 2>/dev/null)
              case "$t" in *gpu*|*GPU*) echo "$t: $(cat $z/trip_point_*_temp 2>/dev/null | tr '\\n' ' ')";; esac
            done | head -20
            echo "--- vendor configs"
            ls /vendor/etc 2>/dev/null | grep -i -E 'therm|perf|power|game'
            for f in $(ls /vendor/etc 2>/dev/null | grep -i -E '^thermal.*[.](conf|xml|json)$' | head -3); do
              echo "== $f"
              head -25 /vendor/etc/$f 2>/dev/null
            done
            echo "--- ZUX Performance Service's configs (#12, #18)"
            ls -l /system/etc/zuipp_*.xml /system/etc/*refresh_app.xml /system/etc/game_policy.xml /system/etc/otherapp_policy.xml /system/etc/performanceconfig.xml /system/etc/special_game_temp.xml 2>/dev/null
            grep -v -E '^[[:space:]]*$|^[[:space:]]*<!--' /system/etc/zuipp_powercfg.xml 2>/dev/null | head -250
            for f in white_refresh_app full_screen_white_refresh_app; do
              echo "== $f"
              grep -v -E '^[[:space:]]*$|^[[:space:]]*<!--' /system/etc/$f.xml 2>/dev/null | head -40
            done
            echo "--- the system's memory cleaner and kill whitelists (#12)"
            for f in ZuiMemCleanerConfig adj_customize_config zui_app_ranking_config; do
              echo "== $f"
              grep -v -E '^[[:space:]]*$|^[[:space:]]*<!--' /system/etc/$f.xml 2>/dev/null | head -60
            done
            for f in zui_zmc_whitelist zui_lmkd_whitelist; do
              echo "== $f: $(tr '\\n' ' ' < /data/system/zui/$f 2>/dev/null)"
            done
            echo "--- apps force-stopped or cleaned lately (#12)"
            logcat -d -b main,system 2>/dev/null | grep -E 'Force stopping|force stop \\[|ZuiMemoryCleaner\\[' | tail -20
            echo "--- properties"
            getprop | grep -i -E 'perf|therm|power|game' | head -40
            echo "--- voltage controls (read only)"
            for r in /sys/class/regulator/regulator.*; do
              v=$(cat $r/microvolts 2>/dev/null)
              [ -n "$v" ] && echo "$(cat $r/name 2>/dev/null): $v uV"
            done | head -40
            echo "debugfs mounted: $(grep -c debugfs /proc/mounts); voltage entries in it: $(ls /sys/kernel/debug 2>/dev/null | grep -i -E 'regulator|cpr' | tr '\\n' ' ')"
            echo "--- android thermal service"
            dumpsys thermalservice 2>/dev/null | head -80
            echo "--- cpu clusters again, seconds later (a minimum that stays at the top is held there)"
            for p in /sys/devices/system/cpu/cpufreq/policy*; do
              echo "${p##*/}: now $(cat $p/scaling_cur_freq 2>/dev/null), min $(cat $p/scaling_min_freq 2>/dev/null), max $(cat $p/scaling_max_freq 2>/dev/null)"
            done
            echo "--- apps holding a root shell (they can set clocks)"
            for s in $(pidof su); do
              pp=$(cut -d' ' -f4 /proc/$s/stat 2>/dev/null)
              echo "su $s for $(tr '\0' ' ' < /proc/$pp/cmdline 2>/dev/null)"
            done | sort -u -k4 | head -20
            true
            """;

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
     *
     * @param moreRoot further read-only shell lines run in the same root request, after this
     *                 probe's own: root answers one request at a time
     */
    public static void sample(Context ctx, String moreRoot, Consumer<String> onDone) {
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
                }, ROOT_READ + "\n" + moreRoot);
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
