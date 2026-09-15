package de.robv.android.xposed.callbacks;

import de.robv.android.xposed.XCallback;

/** Stub. See xposed-api/build.gradle. */
public abstract class XC_LoadPackage extends XCallback {
    public XC_LoadPackage() {
        super();
    }

    public static final class LoadPackageParam extends XCallback.Param {
        public String packageName;
        public String processName;
        public ClassLoader classLoader;
        public boolean isFirstApplication;
    }
}
