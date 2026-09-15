package com.zuxos.desktopplus.hook;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.Const;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.ModuleStatus;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** LSPosed entry point. */
public class XposedEntry implements IXposedHookLoadPackage {

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            if (Const.MODULE_PKG.equals(lpparam.packageName)) {
                markSelfActive(lpparam.classLoader);
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
            ActivityWatcher.install(lpparam.classLoader);
            if (Cfg.nativeDrawer()) {
                NativeDrawerHooks.install(lpparam.classLoader);
            }
            if (Cfg.unlockStock()) {
                StockUnlockHooks.install(lpparam.classLoader);
            }
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
