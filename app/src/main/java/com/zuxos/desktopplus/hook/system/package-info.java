/**
 * Runs inside the system process (system_server), ticked as System Framework in LSPosed.
 *
 * <p>A crash here is a boot loop, so every class in this package follows the same rules: nothing
 * runs at load time but the hooks themselves, no service or data-directory calls, every hook
 * wrapped in try/catch, and no thread that can throw.
 */
package com.zuxos.desktopplus.hook.system;
