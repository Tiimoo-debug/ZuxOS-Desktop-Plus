package com.zuxos.desktopplus.hook;

import android.view.ViewGroup;

import com.zuxos.desktopplus.core.L;

import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Keeps one teardown bug from taking the whole launcher down.
 *
 * <p>Twice now, plugging the monitor in relaunched its home screen and the launcher crashed while
 * the old window was taken down: {@code ViewGroup.dispatchDetachedFromWindow} met a missing child
 * - something removed a view while its parent was still walking its children. The window is dying
 * at that point anyway, so the walk is allowed to end there instead of killing the process; which
 * view group it was is written to the log, so the real cause can be fixed rather than guarded.
 */
final class DetachGuard {

    private static final Set<String> SAID = new HashSet<>();

    private DetachGuard() {
    }

    static void install() {
        try {
            XposedBridge.hookAllMethods(ViewGroup.class, "dispatchDetachedFromWindow",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Throwable t = param.getThrowable();
                            if (!(t instanceof NullPointerException) || t.getMessage() == null
                                    || !t.getMessage().contains("dispatchDetachedFromWindow")) {
                                return;
                            }
                            param.setThrowable(null);
                            ViewGroup group = (ViewGroup) param.thisObject;
                            String name = group.getClass().getName();
                            if (SAID.add(name)) {
                                StringBuilder kids = new StringBuilder();
                                for (int i = 0; i < group.getChildCount(); i++) {
                                    if (group.getChildAt(i) != null) {
                                        kids.append(group.getChildAt(i).getClass().getSimpleName())
                                                .append(' ');
                                    }
                                }
                                L.w("detach guard: " + name + " lost a child while being taken "
                                        + "down - the crash on replug, caught; children now: "
                                        + kids.toString().trim());
                            }
                        }
                    });
        } catch (Throwable t) {
            L.d("detach guard: not installed (" + t + ")");
        }
    }
}
