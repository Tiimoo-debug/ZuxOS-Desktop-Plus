package com.zuxos.desktopplus.hook.taskbar;

import android.graphics.Canvas;
import android.graphics.Insets;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.Const;
import com.zuxos.desktopplus.core.Health;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * The monitor's taskbar at the top of its screen, when the setting says so.
 *
 * <p>ZUI builds the monitor's bar for the bottom and has no setting for any other edge. So the
 * bar is put at the top where ZUI decides where it goes, and nowhere later:
 * <ul>
 *   <li>the window: top gravity, as ZUI makes its parameters - for the window it titles
 *       {@code Taskbar_dp} only, so the tablet's bars and ZUI's other windows are untouched;</li>
 *   <li>its height: the bar itself, without the room ZUI keeps over the bar for tooltips and
 *       corners, which at the top would sit between the screen's edge and the bar - and would
 *       move the touchable strip, which ZUI keeps at the window's foot, off the bar;</li>
 *   <li>the space it keeps clear for apps: ZUI's inset code knows bottom, left and right, and
 *       sends any other gravity to the right edge;</li>
 *   <li>ZUI's own background, which it paints at the window's foot: held at the top while ZUI
 *       stretches the window over the screen for a drag or a folder;</li>
 *   <li>the bar's two rows ({@code taskbar_view}, {@code navbuttons_view}), laid out at the top
 *       of the window instead of its foot, for the same moments - once, on the bar's arrival.</li>
 * </ul>
 *
 * <p>Read when ZUI builds the bar, so the setting takes effect when the launcher restarts. At
 * Bottom nothing here is hooked. Without every one of the first three targets nothing is
 * either: a bar half moved is worse than one left at the bottom, and the log says which is
 * missing.
 */
final class TaskbarEdge {

    private static final String BAR_TITLE = "Taskbar_dp";
    private static final String MONITOR_CONTEXT = "TaskbarActivityContextDp";
    private static final String[] ROWS = {"taskbar_view", "navbuttons_view"};

    private static boolean sInstalled;
    /** The bar is being moved: every essential hook went in. */
    private static boolean sOn;
    /**
     * Per background renderer: the height of the bar it paints when it is the monitor's, or 0
     * for any other - looked up once, as it is asked on every frame of every bar.
     */
    private static final Map<Object, Float> RENDERERS = new WeakHashMap<>();
    /** The canvas's state from before the background was moved, while ZUI paints it. */
    private static final ThreadLocal<Integer> SAVED = new ThreadLocal<>();
    /** Bars whose rows were moved, so each is moved and logged once. */
    private static final Map<View, Boolean> ROWS_MOVED = new WeakHashMap<>();

    private TaskbarEdge() {
    }

    static void install(ClassLoader loader) {
        if (sInstalled) {
            return;
        }
        sInstalled = true;
        if (Cfg.taskbarEdge() != Const.EDGE_TOP) {
            return;
        }
        try {
            Class<?> context = Reflect.findClass(
                    "com.android.launcher3.taskbar.TaskbarActivityContext", loader);
            Class<?> monitor = Reflect.findClass(
                    "com.zui.launcher.taskbar." + MONITOR_CONTEXT, loader);
            Class<?> insets = Reflect.findClass(
                    "com.android.launcher3.taskbar.TaskbarInsetsController", loader);
            Method params = context == null ? null
                    : declared(context, "createDefaultWindowLayoutParams", int.class,
                            String.class);
            Method size = monitor == null ? null
                    : declared(monitor, "getDefaultTaskbarWindowSize");
            List<Method> sides = insets == null ? new ArrayList<>() : insetsBySide(insets);
            if (params == null || size == null || sides.size() != 2) {
                L.w("taskbar edge: the bar stays at the bottom - not found:"
                        + (params == null ? " the window's parameters" : "")
                        + (size == null ? " the window's height" : "")
                        + (sides.size() != 2 ? " the space it keeps clear (" + sides.size()
                        + " of 2)" : ""));
                Health.hooked("taskbar: top edge (window, insets, background)", 0);
                return;
            }
            int hooked = 0;
            XposedBridge.hookMethod(params, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (BAR_TITLE.equals(param.args[1])
                                && param.getResult() instanceof WindowManager.LayoutParams) {
                            ((WindowManager.LayoutParams) param.getResult()).gravity =
                                    Gravity.TOP;
                        }
                    } catch (Throwable t) {
                        L.d("taskbar edge: window not moved (" + t + ")");
                    }
                }
            });
            hooked++;
            XposedBridge.hookMethod(size, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        int bar = barHeight(param.thisObject);
                        if (bar > 0) {
                            param.setResult(bar);
                        }
                    } catch (Throwable t) {
                        L.d("taskbar edge: window height kept (" + t + ")");
                    }
                }
            });
            hooked++;
            XC_MethodHook top = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        int gravity = (int) param.args[1];
                        Object result = param.getResult();
                        if ((gravity & Gravity.VERTICAL_GRAVITY_MASK) != Gravity.TOP
                                || !(result instanceof Insets)) {
                            return;
                        }
                        // ZUI put it on the right, the side it gives any gravity it does not
                        // know: the same amount, at the top. One answer is built on the other,
                        // so what is already at the top is left as it is.
                        Insets wrong = (Insets) result;
                        if (wrong.top == 0 && wrong.bottom == 0) {
                            param.setResult(Insets.of(0, Math.max(wrong.left, wrong.right), 0,
                                    0));
                        }
                    } catch (Throwable t) {
                        L.d("taskbar edge: insets left as ZUI made them (" + t + ")");
                    }
                }
            };
            for (Method side : sides) {
                XposedBridge.hookMethod(side, top);
                hooked++;
            }
            sOn = true;
            hooked += holdBackground(loader);
            Health.hooked("taskbar: top edge (window, insets, background)", hooked);
            L.i("taskbar edge: the monitor's bar goes at the top x" + hooked);
        } catch (Throwable t) {
            L.e("taskbar edge: could not install, the bar stays at the bottom", t);
        }
    }

    /**
     * The bar's two rows at the top of its window, for the moments ZUI stretches the window over
     * the screen: left at its foot, they dropped to the bottom of the screen with everything of
     * ours that follows them. Once per bar; ZUI never sets their gravity again.
     */
    static void apply(ViewGroup dragLayer) {
        if (!sOn || ROWS_MOVED.containsKey(dragLayer)) {
            return;
        }
        ViewGroup.LayoutParams window = dragLayer.getRootView().getLayoutParams();
        if (!(window instanceof WindowManager.LayoutParams) || !BAR_TITLE.contentEquals(
                ((WindowManager.LayoutParams) window).getTitle())) {
            // Not the window moved to the top: a tablet's bar.
            return;
        }
        ROWS_MOVED.put(dragLayer, Boolean.TRUE);
        try {
            StringBuilder moved = new StringBuilder();
            for (View row : Reflect.findByIdNames(dragLayer, ROWS)) {
                if (row.getParent() != dragLayer
                        || !(row.getLayoutParams() instanceof FrameLayout.LayoutParams)) {
                    moved.append(' ').append(Reflect.idName(row)).append(" (not ours to move)");
                    continue;
                }
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) row.getLayoutParams();
                lp.gravity = (lp.gravity & ~Gravity.VERTICAL_GRAVITY_MASK) | Gravity.TOP;
                row.setLayoutParams(lp);
                moved.append(' ').append(Reflect.idName(row));
            }
            L.i("taskbar edge: rows at the top of the window:" + moved);
        } catch (Throwable t) {
            L.d("taskbar edge: rows left at the window's foot (" + t + ")");
        }
    }

    /**
     * ZUI's background painted at the top while the window is stretched over the screen: its
     * renderer moves to the canvas's foot first, so the canvas is moved up by as much.
     */
    private static int holdBackground(ClassLoader loader) {
        Class<?> renderer = Reflect.findClass(
                "com.android.launcher3.taskbar.TaskbarBackgroundRenderer", loader);
        Method draw = renderer == null ? null : declared(renderer, "draw", Canvas.class);
        Method height = renderer == null ? null : declared(renderer, "getBackgroundHeight");
        if (draw == null || height == null) {
            L.w("taskbar edge: ZUI's background renderer not found - without the glass, its "
                    + "background drops to the screen's foot during drags and folders");
            return 0;
        }
        XposedBridge.hookMethod(draw, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                SAVED.remove();
                try {
                    Canvas canvas = (Canvas) param.args[0];
                    float bar = monitorBar(param.thisObject);
                    if (bar <= 0f || canvas.getHeight() <= bar + 1f) {
                        // Not the monitor's bar, or its window is the bar: nothing to move.
                        return;
                    }
                    // Stretched over the screen: the renderer paints at the canvas's foot, by
                    // the height it paints - read now, it can be mid-animation.
                    Object now = height.invoke(param.thisObject);
                    if (now instanceof Float && (Float) now > 0f) {
                        bar = Math.min(bar, (Float) now);
                    }
                    SAVED.set(canvas.save());
                    canvas.translate(0f, bar - canvas.getHeight());
                } catch (Throwable t) {
                    L.d("taskbar edge: background left where ZUI paints it (" + t + ")");
                }
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Integer saved = SAVED.get();
                if (saved == null) {
                    return;
                }
                SAVED.remove();
                try {
                    ((Canvas) param.args[0]).restoreToCount(saved);
                } catch (Throwable t) {
                    L.d("taskbar edge: canvas not restored (" + t + ")");
                }
            }
        });
        return 1;
    }

    /**
     * The height of the bar this renderer paints if it is the monitor's, else 0. Its bar's
     * context is found by type: the build renames the renderer's fields.
     */
    private static float monitorBar(Object renderer) {
        Float known = RENDERERS.get(renderer);
        if (known == null) {
            Object context = fieldOfType(renderer, MONITOR_CONTEXT);
            known = context != null ? (float) barHeight(context) : 0f;
            RENDERERS.put(renderer, known);
        }
        return known;
    }

    /** The first of the object's fields holding an instance of the named class, or null. */
    private static Object fieldOfType(Object target, String simpleName) {
        for (Field f : target.getClass().getDeclaredFields()) {
            try {
                f.setAccessible(true);
                Object value = f.get(target);
                if (value != null && value.getClass().getSimpleName().equals(simpleName)) {
                    return value;
                }
            } catch (Throwable ignored) {
                // The next field.
            }
        }
        return null;
    }

    /** The bar's own height on this context: its device profile's taskbar height. */
    private static int barHeight(Object context) {
        Object profile = Reflect.call(context, "getDeviceProfile");
        Object height = Reflect.field(profile, "taskbarHeight");
        return height instanceof Integer ? (Integer) height : 0;
    }

    /**
     * ZUI's two answers for the space a bar keeps clear on a side - an inset and a gravity, and
     * the same with a rotation - found by their shape, which survives the build's renaming.
     */
    private static List<Method> insetsBySide(Class<?> controller) {
        List<Method> found = new ArrayList<>();
        for (Method m : controller.getDeclaredMethods()) {
            Class<?>[] types = m.getParameterTypes();
            if (m.getReturnType() != Insets.class || types.length < 2 || types.length > 3) {
                continue;
            }
            boolean ints = true;
            for (Class<?> type : types) {
                ints &= type == int.class;
            }
            if (ints) {
                found.add(m);
            }
        }
        return found;
    }

    private static Method declared(Class<?> cls, String name, Class<?>... types) {
        try {
            return cls.getDeclaredMethod(name, types);
        } catch (Throwable t) {
            return null;
        }
    }
}
