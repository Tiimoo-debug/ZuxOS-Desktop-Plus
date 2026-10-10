package com.zuxos.desktopplus.hook;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.Const;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.ModuleStatus;
import com.zuxos.desktopplus.hook.drawer.DrawerFromTop;
import com.zuxos.desktopplus.hook.drawer.DrawerRetro;
import com.zuxos.desktopplus.hook.drawer.NativeDrawerHooks;
import com.zuxos.desktopplus.hook.home.ActivityWatcher;
import com.zuxos.desktopplus.hook.home.HotseatButton;
import com.zuxos.desktopplus.hook.home.PinRequestHooks;
import com.zuxos.desktopplus.hook.home.SettingsRestart;
import com.zuxos.desktopplus.hook.home.StockUnlockHooks;
import com.zuxos.desktopplus.hook.recents.RecentsRoute;
import com.zuxos.desktopplus.hook.system.SystemBridge;
import com.zuxos.desktopplus.hook.system.SystemDrag;
import com.zuxos.desktopplus.hook.system.SystemFullscreen;
import com.zuxos.desktopplus.hook.system.SystemKeepAlive;
import com.zuxos.desktopplus.hook.system.SystemNewWindow;
import com.zuxos.desktopplus.hook.system.SystemTrace;
import com.zuxos.desktopplus.hook.systemui.MonitorStatusBar;
import com.zuxos.desktopplus.hook.systemui.StableBounds;
import com.zuxos.desktopplus.hook.systemui.WindowCaptions;
import com.zuxos.desktopplus.hook.taskbar.TaskbarTray;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** LSPosed entry point. */
public class XposedEntry implements IXposedHookLoadPackage {

    private static final String SYSTEMUI = "com.android.systemui";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            if (Const.MODULE_PKG.equals(lpparam.packageName)) {
                markSelfActive(lpparam.classLoader);
                return;
            }
            if ("android".equals(lpparam.packageName)) {
                // The system itself - ticked as System Framework in LSPosed. Only the keep-alive and
                // the minimise bridge run here; nothing of the launcher's belongs in system_server.
                Cfg.reload();
                if (Cfg.enabled()) {
                    SystemKeepAlive.install(lpparam.classLoader);
                    SystemBridge.install();
                    SystemNewWindow.install(lpparam.classLoader);
                    SystemFullscreen.install(lpparam.classLoader);
                    SystemDrag.install(lpparam.classLoader);
                    SystemTrace.install(lpparam.classLoader);
                }
                return;
            }
            if (SYSTEMUI.equals(lpparam.packageName)) {
                // ZUI's SystemUI - ticked as System UI in LSPosed. Its main process only: the
                // monitor's status bar, the window buttons it took with it, and where windows
                // may go with the bars where they really are.
                Cfg.reload();
                if (SYSTEMUI.equals(lpparam.processName) && Cfg.enabled()) {
                    MonitorStatusBar.install(lpparam.classLoader);
                    WindowCaptions.install(lpparam.classLoader);
                    StableBounds.install(lpparam.classLoader);
                }
                return;
            }
            if (!Targets.isCandidate(lpparam.packageName)) {
                return;
            }
            Cfg.reload();
            if (!Cfg.enabled()) {
                L.i("module disabled in settings, skipping " + lpparam.packageName);
                return;
            }
            L.i("loaded into " + lpparam.packageName + " (" + lpparam.processName + ")");
            SettingsRestart.remember();
            ActivityWatcher.install(lpparam.classLoader);
            PinRequestHooks.install();
            LaunchDisplay.install(lpparam.classLoader);
            if (Cfg.nativeDrawer()) {
                NativeDrawerHooks.install(lpparam.classLoader);
            }
            DrawerRetro.install(lpparam.classLoader);
            DrawerFromTop.install(lpparam.classLoader);
            RecentsRoute.install(lpparam.classLoader);
            DetachGuard.install();
            if (Cfg.unlockStock()) {
                StockUnlockHooks.install(lpparam.classLoader);
            }
            if (Cfg.taskbarTray()) {
                TaskbarTray.install(lpparam.classLoader);
            }
            HotseatButton.install(lpparam.classLoader);
        } catch (Throwable t) {
            L.e("handleLoadPackage failed for " + lpparam.packageName, t);
        }
    }

    /** Lets our own settings UI report "module active" honestly. */
    private void markSelfActive(ClassLoader loader) {
        try {
            Class<?> status = Class.forName(ModuleStatus.class.getName(), false, loader);
            XposedBridge.hookAllMethods(status, "isActive",
                    XC_MethodReplacement.returnConstant(Boolean.TRUE));
        } catch (Throwable t) {
            L.e("could not mark module active", t);
        }
    }
}
