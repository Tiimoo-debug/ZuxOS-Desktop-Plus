package com.zuxos.desktopplus.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
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
     * <p>The hottest matching zone wins: a chip reports one zone per cluster and the interesting
     * number is the one that is about to throttle, not the average.
     */
    public static Reading read() {
        float cpu = Float.NaN;
        float gpu = Float.NaN;
        for (Zone zone : zones()) {
            float value = zone.read();
            if (Float.isNaN(value)) {
                continue;
            }
            if (zone.gpu) {
                gpu = Float.isNaN(gpu) ? value : Math.max(gpu, value);
            } else {
                cpu = Float.isNaN(cpu) ? value : Math.max(cpu, value);
            }
        }
        return new Reading(cpu, gpu);
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
        List<Zone> zones = new ArrayList<>();
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
                    boolean gpu = type.contains("gpu");
                    boolean cpu = !gpu && isCpuZone(type);
                    if (!gpu && !cpu) {
                        continue;
                    }
                    File temp = new File(dir, "temp");
                    if (!temp.canRead()) {
                        continue;
                    }
                    Zone zone = new Zone(temp, gpu);
                    // Only keep zones that actually produce a plausible number.
                    if (!Float.isNaN(zone.read())) {
                        zones.add(zone);
                    }
                }
            }
        } catch (Throwable t) {
            L.d("thermals: zone scan failed (" + t + ")");
        }
        if (zones.isEmpty()) {
            L.d("thermals: no readable CPU or GPU zone under " + ROOT);
        }
        sZones = zones;
        return sZones;
    }

    /**
     * Whether a zone name is a CPU sensor.
     *
     * <p>Qualcomm names them {@code cpu-0-0-usr}, {@code cpuss-2-usr} and similar; other vendors
     * use {@code soc_thermal} or {@code tsens_tz_sensor*}. Anything that mentions a battery,
     * charger, display or skin is deliberately excluded - those are not what "CPU temperature"
     * means to anyone reading a taskbar.
     */
    private static boolean isCpuZone(String type) {
        if (type.contains("batt") || type.contains("charg") || type.contains("usb")
                || type.contains("skin") || type.contains("disp") || type.contains("case")
                || type.contains("modem") || type.contains("camera") || type.contains("wifi")) {
            return false;
        }
        return type.contains("cpu") || type.contains("soc") || type.contains("apc")
                || type.contains("silver") || type.contains("gold") || type.contains("prime");
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
