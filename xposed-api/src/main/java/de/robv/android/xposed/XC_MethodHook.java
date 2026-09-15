package de.robv.android.xposed;

import java.lang.reflect.Member;

/** Stub. See xposed-api/build.gradle. */
public abstract class XC_MethodHook extends XCallback {
    public XC_MethodHook() {
        super();
    }

    public XC_MethodHook(int priority) {
        super(priority);
    }

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
    }

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
    }

    public static final class MethodHookParam extends XCallback.Param {
        public Member method;
        public Object thisObject;
        public Object[] args;

        public Object getResult() {
            throw new UnsupportedOperationException("Stub!");
        }

        public void setResult(Object result) {
            throw new UnsupportedOperationException("Stub!");
        }

        public Throwable getThrowable() {
            throw new UnsupportedOperationException("Stub!");
        }

        public boolean hasThrowable() {
            throw new UnsupportedOperationException("Stub!");
        }

        public void setThrowable(Throwable throwable) {
            throw new UnsupportedOperationException("Stub!");
        }

        public Object getResultOrThrowable() throws Throwable {
            throw new UnsupportedOperationException("Stub!");
        }
    }

    public class Unhook {
        public Member getHookedMethod() {
            throw new UnsupportedOperationException("Stub!");
        }

        public XC_MethodHook getCallback() {
            throw new UnsupportedOperationException("Stub!");
        }

        public void unhook() {
            throw new UnsupportedOperationException("Stub!");
        }
    }
}
