package de.robv.android.xposed;

/** Stub. See xposed-api/build.gradle. */
public abstract class XC_MethodReplacement extends XC_MethodHook {
    public XC_MethodReplacement() {
        super();
    }

    public XC_MethodReplacement(int priority) {
        super(priority);
    }

    protected abstract Object replaceHookedMethod(MethodHookParam param) throws Throwable;

    public static final XC_MethodReplacement DO_NOTHING = null;

    public static XC_MethodReplacement returnConstant(final Object result) {
        throw new UnsupportedOperationException("Stub!");
    }

    public static XC_MethodReplacement returnConstant(int priority, final Object result) {
        throw new UnsupportedOperationException("Stub!");
    }
}
