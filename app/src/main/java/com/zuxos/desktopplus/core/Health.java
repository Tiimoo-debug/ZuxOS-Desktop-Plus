package com.zuxos.desktopplus.core;

import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Which of the module's hooks found what they look for in this process, for the probe.
 *
 * <p>ZUI renames its internals with each update; a hook that finds nothing is the first sign.
 * Every install that counts its hooks reports here as well as to the log, so one probe lists them
 * all side by side - the start of the health screen on the roadmap (#13).
 */
public final class Health {

    /** What was hooked, by name, sorted: "x3", or "not found" for none. */
    private static final Map<String, String> HOOKS = new ConcurrentSkipListMap<>();

    private Health() {
    }

    /** {@code count} hooks went in for {@code what}. */
    public static void hooked(String what, int count) {
        HOOKS.put(what, count > 0 ? "x" + count : "not found");
    }

    public static String describe() {
        StringBuilder sb = new StringBuilder("\nhooks in this process\n");
        if (HOOKS.isEmpty()) {
            return sb.append("  (none reported yet)\n").toString();
        }
        for (Map.Entry<String, String> e : HOOKS.entrySet()) {
            sb.append("  ").append(e.getValue().equals("not found") ? "!! " : "   ")
                    .append(e.getKey()).append(": ").append(e.getValue()).append('\n');
        }
        return sb.toString();
    }
}
