package com.zuxos.desktopplus.core;

/**
 * Activation probe.
 *
 * <p>{@link com.zuxos.desktopplus.hook.XposedEntry} replaces {@link #isActive()} inside our own
 * process, so the settings UI can tell "installed" from "actually hooked by LSPosed".
 */
public final class ModuleStatus {

    private ModuleStatus() {
    }

    public static boolean isActive() {
        return false;
    }
}
