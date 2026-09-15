package com.zuxos.desktopplus.hook;

import com.zuxos.desktopplus.core.Cfg;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Which processes are worth hooking.
 *
 * <p>ZuxOS/ZUI has renamed its home app across releases, so instead of hard-coding one package
 * we accept a small known list plus anything that looks like a home app, and let the user add
 * packages from the settings screen.
 */
public final class Targets {

    private static final List<String> KNOWN = Arrays.asList(
            "com.zui.home",
            "com.zui.launcher",
            "com.zui.desktop",
            "com.zui.pcmode",
            "com.lenovo.launcher",
            "com.android.launcher3");

    private static final String[] NAME_HINTS = {"launcher", "home", "desktop", "pcmode", "workspace"};

    private Targets() {
    }

    public static boolean isCandidate(String packageName) {
        if (packageName == null) {
            return false;
        }
        if (KNOWN.contains(packageName) || extraTargets().contains(packageName)) {
            return true;
        }
        String lower = packageName.toLowerCase();
        for (String hint : NAME_HINTS) {
            if (lower.contains(hint)) {
                return true;
            }
        }
        return false;
    }

    /** Extra packages typed into the settings app, comma or newline separated. */
    public static Set<String> extraTargets() {
        Set<String> out = new LinkedHashSet<>();
        String raw = Cfg.extraTargets();
        if (raw == null || raw.trim().isEmpty()) {
            return out;
        }
        for (String part : raw.split("[,\\s]+")) {
            String p = part.trim();
            if (!p.isEmpty()) {
                out.add(p);
            }
        }
        return out;
    }
}
