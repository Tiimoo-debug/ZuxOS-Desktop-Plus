package de.robv.android.xposed;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Set;

/** Stub. See xposed-api/build.gradle. */
public final class XposedHelpers {
    private XposedHelpers() {
    }

    public static Class<?> findClass(String className, ClassLoader classLoader) {
        throw new UnsupportedOperationException("Stub!");
    }

    public static Class<?> findClassIfExists(String className, ClassLoader classLoader) {
        throw new UnsupportedOperationException("Stub!");
    }

    public static XC_MethodHook.Unhook findAndHookMethod(Class<?> clazz, String methodName,
            Object... parameterTypesAndCallback) {
        throw new UnsupportedOperationException("Stub!");
    }

    public static XC_MethodHook.Unhook findAndHookMethod(String className, ClassLoader classLoader,
            String methodName, Object... parameterTypesAndCallback) {
        throw new UnsupportedOperationException("Stub!");
    }

    public static Set<XC_MethodHook.Unhook> hookAllMethods(Class<?> hookClass, String methodName,
            XC_MethodHook callback) {
        throw new UnsupportedOperationException("Stub!");
    }

    public static Set<XC_MethodHook.Unhook> hookAllConstructors(Class<?> hookClass, XC_MethodHook callback) {
        throw new UnsupportedOperationException("Stub!");
    }

    public static Method findMethodExact(Class<?> clazz, String methodName, Object... parameterTypes) {
        throw new UnsupportedOperationException("Stub!");
    }

    public static Method findMethodExactIfExists(Class<?> clazz, String methodName, Object... parameterTypes) {
        throw new UnsupportedOperationException("Stub!");
    }

    public static Constructor<?> findConstructorExact(Class<?> clazz, Object... parameterTypes) {
        throw new UnsupportedOperationException("Stub!");
    }

    public static Field findField(Class<?> clazz, String fieldName) {
        throw new UnsupportedOperationException("Stub!");
    }

    public static Field findFieldIfExists(Class<?> clazz, String fieldName) {
        throw new UnsupportedOperationException("Stub!");
    }

    public static Field findFirstFieldByExactType(Class<?> clazz, Class<?> type) {
        throw new UnsupportedOperationException("Stub!");
    }

    public static Object callMethod(Object obj, String methodName, Object... args) {
        throw new UnsupportedOperationException("Stub!");
    }

    public static Object callStaticMethod(Class<?> clazz, String methodName, Object... args) {
        throw new UnsupportedOperationException("Stub!");
    }

    public static Object newInstance(Class<?> clazz, Object... args) {
        throw new UnsupportedOperationException("Stub!");
    }

    public static Object getObjectField(Object obj, String fieldName) {
        throw new UnsupportedOperationException("Stub!");
    }

    public static void setObjectField(Object obj, String fieldName, Object value) {
        throw new UnsupportedOperationException("Stub!");
    }

    public static int getIntField(Object obj, String fieldName) {
        throw new UnsupportedOperationException("Stub!");
    }

    public static void setIntField(Object obj, String fieldName, int value) {
        throw new UnsupportedOperationException("Stub!");
    }

    public static boolean getBooleanField(Object obj, String fieldName) {
        throw new UnsupportedOperationException("Stub!");
    }

    public static void setBooleanField(Object obj, String fieldName, boolean value) {
        throw new UnsupportedOperationException("Stub!");
    }

    public static Object getStaticObjectField(Class<?> clazz, String fieldName) {
        throw new UnsupportedOperationException("Stub!");
    }

    public static void setStaticObjectField(Class<?> clazz, String fieldName, Object value) {
        throw new UnsupportedOperationException("Stub!");
    }

    public static Object getAdditionalInstanceField(Object obj, String key) {
        throw new UnsupportedOperationException("Stub!");
    }

    public static Object setAdditionalInstanceField(Object obj, String key, Object value) {
        throw new UnsupportedOperationException("Stub!");
    }

    public static Object removeAdditionalInstanceField(Object obj, String key) {
        throw new UnsupportedOperationException("Stub!");
    }
}
