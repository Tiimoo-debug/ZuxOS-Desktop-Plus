package de.robv.android.xposed;

import java.lang.reflect.Member;
import java.util.Set;

/** Stub. See xposed-api/build.gradle. */
public final class XposedBridge {
    public static int XPOSED_BRIDGE_VERSION;

    private XposedBridge() {
    }

    public static void log(String text) {
        throw new UnsupportedOperationException("Stub!");
    }

    public static void log(Throwable t) {
        throw new UnsupportedOperationException("Stub!");
    }

    public static XC_MethodHook.Unhook hookMethod(Member hookMethod, XC_MethodHook callback) {
        throw new UnsupportedOperationException("Stub!");
    }

    public static Set<XC_MethodHook.Unhook> hookAllMethods(Class<?> hookClass, String methodName,
            XC_MethodHook callback) {
        throw new UnsupportedOperationException("Stub!");
    }

    public static Set<XC_MethodHook.Unhook> hookAllConstructors(Class<?> hookClass, XC_MethodHook callback) {
        throw new UnsupportedOperationException("Stub!");
    }

    public static Object invokeOriginalMethod(Member method, Object thisObject, Object[] args)
            throws Throwable {
        throw new UnsupportedOperationException("Stub!");
    }
}
