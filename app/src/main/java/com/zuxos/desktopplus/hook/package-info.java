/**
 * The LSPosed entry point ({@link com.zuxos.desktopplus.hook.XposedEntry}) and what every area of
 * hooks shares: which processes to hook, overlay windows, the launcher's windows, reflection that
 * survives obfuscation, the root key shell, keeping monitor apps alive, and the probe.
 *
 * <p>Each area of ZUI the module changes has a package of its own below this one.
 */
package com.zuxos.desktopplus.hook;
