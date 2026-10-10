/**
 * Runs inside ZUI's SystemUI, ticked as System UI in LSPosed - its main process only.
 *
 * <p>SystemUI is the whole screen's interface, and Android resets a phone's settings when it
 * keeps crashing, so the system process's rules hold here too: nothing runs at load time but the
 * hooks themselves, no service calls, every hook wrapped in try/catch, and no thread that can
 * throw. Each hook switches one of ZUI's decisions off where ZUI makes it, and nothing else.
 */
package com.zuxos.desktopplus.hook.systemui;
