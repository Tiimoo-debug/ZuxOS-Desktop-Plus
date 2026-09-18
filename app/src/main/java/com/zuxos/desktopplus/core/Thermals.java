package com.zuxos.desktopplus.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;

/**
 * CPU and GPU temperature, read from the kernel's thermal zones.
 *
 * <p>There is no app-facing API for this. {@code HardwarePropertiesManager} needs a permission
 * only the system holds, and {@code PowerManager.getCurrentThermalStatus} gives a severity rather
 * than a number. What is left is {@code /sys/class/thermal}, which the launcher may or may not be
 * allowed to read depending on the SELinux domain it runs in - so a failed read is an ordinary
 * outcome here, not an error, and the tray simply leaves the number out.
 *
 * <p>The zone list is scanned once. Reading it on every poll would mean a directory listing plus
 * a {@code type} read per zone, several times a minute, for a list that cannot change.
 */
public final class Thermals {

    /** Only believe a temperature inside this range; sensors report nonsense when unbound. */
    private static final float MIN_PLAUSIBLE_C = -40f;
    private static final float MAX_PLAUSIBLE_C = 150f;

    private static final File ROOT = new File("/sys/class/thermal");

    /** How many levels of "looks like a CPU sensor" {@link #cpuTier} distinguishes. */
    private static final int CPU_TIERS = 3;

    private static List<Zone> sZones;

    private Thermals() {
    }

    /** A snapshot in degrees Celsius; {@link Float#NaN} where nothing could be read. */
    public static final class Reading {
        public final float cpu;
        public final float gpu;

        Reading(float cpu, float gpu) {
            this.cpu = cpu;
            this.gpu = gpu;
        }
    }

    /** True when at least one usable zone was found, so the tray knows whether to show anything. */
    public static synchronized boolean available() {
        return !zones().isEmpty();
    }

    /**
     * Reads every known zone. Call this off the main thread - it touches the filesystem.
     *
     * <p>The median of the matching zones, not the hottest. A phone-class SoC exposes a dozen
     * sensors per die and some of them are stuck, virtual, or measure a nearby rail rather than
     * the core - taking the maximum hands the taskbar whichever one is most broken, which is how
     * an idle tablet came to report 105 degrees. The middle value is unmoved by an outlier at
     * either end.
     */
    public static Reading read() {
        List<Zone> zones = zones();
        return new Reading(median(zones, false), median(zones, true));
    }

    private static float median(List<Zone> zones, boolean gpu) {
        List<Float> values = new ArrayList<>();
        for (Zone zone : zones) {
            if (zone.gpu != gpu) {
                continue;
            }
            float value = zone.read();
            if (!Float.isNaN(value)) {
                values.add(value);
            }
        }
        if (values.isEmpty()) {
            return Float.NaN;
        }
        Collections.sort(values);
        int n = values.size();
        return n % 2 == 1 ? values.get(n / 2) : (values.get(n / 2 - 1) + values.get(n / 2)) / 2f;
    }

    /** A line per zone, for the probe dump - the only way to see what a device actually exposes. */
    public static String describe() {
        StringBuilder sb = new StringBuilder("\nthermal zones\n");
        File[] dirs = ROOT.listFiles();
        if (dirs == null) {
            return sb.append("  (").append(ROOT).append(" is not readable)\n").toString();
        }
        java.util.Arrays.sort(dirs, (a, b) -> a.getName().compareTo(b.getName()));
        // What the selection actually settled on, rather than what the name alone suggests -
        // this dump exists to explain a reading, so a guess in it would be worse than nothing.
        Map<String, Boolean> chosen = new HashMap<>();
        for (Zone zone : zones()) {
            chosen.put(zone.temp.getAbsolutePath(), zone.gpu);
        }
        int shown = 0;
        for (File dir : dirs) {
            if (!dir.getName().startsWith("thermal_zone")) {
                continue;
            }
            String type = readText(new File(dir, "type"));
            String raw = readText(new File(dir, "temp"));
            Boolean gpu = chosen.get(new File(dir, "temp").getAbsolutePath());
            sb.append("  ").append(dir.getName())
                    .append("  type=").append(type == null ? "(unreadable)" : type)
                    .append("  raw=").append(raw == null ? "(unreadable)" : raw)
                    .append("  used-as=").append(gpu == null ? "ignored" : gpu ? "gpu" : "cpu")
                    .append('\n');
            shown++;
        }
        if (shown == 0) {
            sb.append("  (no thermal zones visible to this process)\n");
        }
        Reading reading = read();
        String cpu = format(reading.cpu);
        String gpu = format(reading.gpu);
        sb.append("  -> cpu=").append(cpu.isEmpty() ? "-" : cpu)
                .append(" gpu=").append(gpu.isEmpty() ? "-" : gpu).append('\n');
        return sb.toString();
    }

    /** Formats a temperature the way the tray shows it, or an empty string for "unknown". */
    public static String format(float celsius) {
        if (Float.isNaN(celsius)) {
            return "";
        }
        return String.format(Locale.getDefault(), "%.0f\u00B0", celsius);
    }

    private static synchronized List<Zone> zones() {
        if (sZones != null) {
            return sZones;
        }
        List<Zone> gpus = new ArrayList<>();
        // CPU candidates by how specific their name is; only the best tier found is used, so a
        // chip that names its cores properly never falls back to a vague "soc" sensor.
        List<List<Zone>> cpuTiers = new ArrayList<>();
        for (int i = 0; i < CPU_TIERS; i++) {
            cpuTiers.add(new ArrayList<>());
        }
        try {
            File[] dirs = ROOT.listFiles();
            if (dirs != null) {
                for (File dir : dirs) {
                    if (!dir.getName().startsWith("thermal_zone")) {
                        continue;
                    }
                    String type = readText(new File(dir, "type"));
                    if (type == null) {
                        continue;
                    }
                    type = type.toLowerCase(Locale.ROOT);
                    boolean gpu = isGpuZone(type);
                    int tier = gpu ? 0 : cpuTier(type);
                    if (!gpu && tier == 0) {
                        continue;
                    }
                    File temp = new File(dir, "temp");
                    if (!temp.canRead()) {
                        continue;
                    }
                    Zone zone = new Zone(temp, gpu);
                    // Only keep zones that actually produce a plausible number.
                    if (Float.isNaN(zone.read())) {
                        continue;
                    }
                    if (gpu) {
                        gpus.add(zone);
                    } else {
                        cpuTiers.get(tier - 1).add(zone);
                    }
                }
            }
        } catch (Throwable t) {
            L.d("thermals: zone scan failed (" + t + ")");
        }

        List<Zone> zones = new ArrayList<>(gpus);
        for (List<Zone> tier : cpuTiers) {
            if (!tier.isEmpty()) {
                zones.addAll(tier);
                break;
            }
        }
        if (zones.isEmpty()) {
            L.d("thermals: no readable CPU or GPU zone under " + ROOT);
        }
        sZones = zones;
        return sZones;
    }

    private static boolean isGpuZone(String type) {
        return !isOffTopic(type) && (type.contains("gpu") || type.contains("kgsl"));
    }

    /**
     * How specifically a zone name says "this is a CPU", 1 being the most specific and 0 none.
     * Only the best tier with any members is used.
     *
     * <p>A CPU zone that names itself a thermometer - {@code cpu-0-0-usr}, {@code cpuss-N-usr},
     * {@code cpu_thermal} - is a measurement and wins outright. The application-processor
     * thermistor comes next. Bare numbered {@code cpu-N-N-N} zones come last, because on this
     * tablet's chip that is what they are: every one of them sits between 90 and 106 degrees
     * while the GPU on the same die reads 65, which is the signature of a limits-management
     * threshold register rather than a temperature.
     */
    private static int cpuTier(String type) {
        if (isOffTopic(type)) {
            return 0;
        }
        if ((type.contains("cpu") || type.contains("kryo"))
                && (type.contains("usr") || type.contains("therm"))) {
            return 1;
        }
        // "therm" is required here too: the same chip exposes a zone called "socd" - a debug
        // counter, not a thermometer - which matching on "soc" alone averaged in as 21 degrees.
        if (type.contains("therm")
                && (type.startsWith("ap-") || type.contains("apc") || type.contains("soc"))) {
            return 2;
        }
        if (type.contains("cpu") || type.contains("kryo") || type.contains("silver")
                || type.contains("gold") || type.contains("prime") || type.contains("cluster")) {
            return 3;
        }
        return 0;
    }

    /** Sensors that measure something other than the chip, whatever else their name contains. */
    private static boolean isOffTopic(String type) {
        return type.contains("batt") || type.contains("charg") || type.contains("chg")
                || type.contains("usb") || type.contains("skin") || type.contains("disp")
                || type.contains("case") || type.contains("modem") || type.contains("camera")
                || type.contains("wifi") || type.contains("wlan") || type.contains("pa-")
                || type.contains("pm8") || type.contains("quiet") || type.contains("virtual")
                || type.contains("monitor") || isThreshold(type);
    }

    /**
     * Names that announce a configured limit rather than a measurement.
     *
     * <p>This chip exposes {@code cpu-hw-trip-0} and {@code cpu-hw-trip-1}, both permanently
     * reading exactly 105 degrees. They are throttling thresholds, and mixing them in drags the
     * reported temperature towards a number the silicon never actually reaches.
     */
    private static boolean isThreshold(String type) {
        return type.contains("trip") || type.contains("limit") || type.contains("lmh")
                || type.contains("thresh");
    }

    private static String readText(File file) {
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[64];
            int read = in.read(buffer);
            if (read <= 0) {
                return null;
            }
            return new String(buffer, 0, read, StandardCharsets.UTF_8).trim();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static final class Zone {
        final File temp;
        final boolean gpu;

        Zone(File temp, boolean gpu) {
            this.temp = temp;
            this.gpu = gpu;
        }

        /** Degrees Celsius, or NaN. */
        float read() {
            String raw = readText(temp);
            if (raw == null || raw.isEmpty()) {
                return Float.NaN;
            }
            long value;
            try {
                value = Long.parseLong(raw);
            } catch (NumberFormatException e) {
                return Float.NaN;
            }
            // Kernels report milli-, deci- or whole degrees depending on the driver, and the
            // magnitude is the only thing that distinguishes them.
            float celsius;
            long magnitude = Math.abs(value);
            if (magnitude >= 10000) {
                celsius = value / 1000f;
            } else if (magnitude >= 1000) {
                celsius = value / 100f;
            } else if (magnitude >= 200) {
                celsius = value / 10f;
            } else {
                celsius = value;
            }
            return celsius >= MIN_PLAUSIBLE_C && celsius <= MAX_PLAUSIBLE_C ? celsius : Float.NaN;
        }
    }
}
