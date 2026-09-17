package com.zuxos.desktopplus.hook;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;
import com.zuxos.desktopplus.core.Ui;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Glass for the launcher's own taskbar.
 *
 * <p>Three things have to happen together, and none of them works alone. The taskbar paints its
 * own opaque bar in {@code TaskbarDragLayer.onDraw}, so that is suppressed. A translucent pane is
 * put in its place as the drag layer's own backdrop. And the taskbar's window is asked to blur
 * what is behind it - which is the only part that makes it read as glass rather than as a grey
 * stripe, because what sits behind the taskbar belongs to another app's window and no view of
 * ours can capture or refract it.
 *
 * <p>Suppressing a launcher's drawing is the most invasive thing this module does, so it is a
 * setting of its own and every step reports what it managed.
 */
public final class TaskbarGlass {

    /** Tint of the bar itself. Light, because the blur behind does most of the work. */
    private static final int TINT = 0x59101014;

    private static final String TAG_GLASS = "zux-desktop-plus-taskbar-glass";

    private static final Map<View, WeakReference<View>> PANES = new WeakHashMap<>();
    /** Drag layers whose window we already asked to blur. */
    private static final Set<View> BLURRED =
            Collections.newSetFromMap(new WeakHashMap<>());

    private static boolean sInstalled;

    private TaskbarGlass() {
    }

    /**
     * Stops the taskbar painting its own bar.
     *
     * <p>Hooked on {@code TaskbarDragLayer} itself rather than on {@code View}, so it runs only
     * for the taskbar. {@code onDraw} paints the background; children are drawn from
     * {@code dispatchDraw}, which is untouched, so the icons and buttons are unaffected.
     */
    static void install(ClassLoader loader) {
        if (sInstalled) {
            return;
        }
        sInstalled = true;
        try {
            Class<?> cls = Reflect.findClass(
                    "com.android.launcher3.taskbar.TaskbarDragLayer", loader);
            if (cls == null) {
                L.w("taskbar glass: TaskbarDragLayer not found");
                return;
            }
            int hooked = XposedBridge.hookAllMethods(cls, "onDraw", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    // Asked per drag layer, not from a global flag: with a second display there
                    // are two taskbars, and one of them being glazed must not blank the other.
                    if (isGlazed(param.thisObject)) {
                        param.setResult(null);
                    }
                }
            }).size();
            L.i("taskbar glass: background suppression installed x" + hooked);
            if (hooked == 0) {
                L.w("taskbar glass: this build does not paint its bar in onDraw - the stock "
                        + "background will stay");
            }
        } catch (Throwable t) {
            L.e("taskbar glass: could not install", t);
        }
    }

    /** Applies or removes the glass on one taskbar, following the setting. */
    static void apply(View root) {
        if (!(root instanceof ViewGroup)) {
            return;
        }
        ViewGroup dragLayer = (ViewGroup) root;
        try {
            if (!(Cfg.taskbarGlass() && Cfg.glass())) {
                remove(dragLayer);
                return;
            }
            if (paneIn(dragLayer) != null) {
                return;
            }
            View reference = TaskbarTray.rowReference(dragLayer);
            ViewGroup.LayoutParams lp = TaskbarTray.dragLayerParams(dragLayer, reference);
            if (lp == null) {
                L.w("taskbar glass: the drag layer's layout params are not reproducible, "
                        + "leaving its background alone");
                return;
            }
            BarView pane = new BarView(dragLayer.getContext());
            pane.setTag(TAG_GLASS);
            // Index 0 so it is behind every icon, and behind the menu hit area too.
            dragLayer.addView(pane, 0, lp);
            PANES.put(root, new WeakReference<>(pane));
            sync(dragLayer, pane, reference);
            if (reference != null) {
                reference.addOnLayoutChangeListener(
                        (v, l, t, r, b, ol, ot, or, ob) -> sync(dragLayer, pane, reference));
            }
            blurBehind(dragLayer, true);
            dragLayer.invalidate();
            L.i("taskbar glass: applied");
        } catch (Throwable t) {
            // The pane is what licenses the suppression, so dropping it re-exposes the stock bar
            // rather than leaving a transparent one behind.
            PANES.remove(root);
            L.e("taskbar glass: could not apply", t);
        }
    }

    /**
     * Whether this drag layer has our pane, which is what licenses hiding its own background.
     *
     * <p>Map lookup only. This is asked once per taskbar frame, so it must not walk the tree.
     */
    private static boolean isGlazed(Object dragLayer) {
        WeakReference<View> ref = PANES.get(dragLayer);
        View pane = ref != null ? ref.get() : null;
        return pane != null && pane.getParent() != null;
    }

    private static View paneIn(ViewGroup dragLayer) {
        WeakReference<View> ref = PANES.get(dragLayer);
        View pane = ref != null ? ref.get() : null;
        if (pane != null && pane.getParent() != null) {
            return pane;
        }
        View tagged = dragLayer.findViewWithTag(TAG_GLASS);
        if (tagged != null) {
            // Ours from an earlier pass whose bookkeeping was lost; adopt it rather than
            // stacking a second pane on top.
            PANES.put(dragLayer, new WeakReference<>(tagged));
        }
        return tagged;
    }

    private static void remove(ViewGroup dragLayer) {
        PANES.remove(dragLayer);
        View pane = dragLayer.findViewWithTag(TAG_GLASS);
        if (pane != null && pane.getParent() instanceof ViewGroup) {
            ((ViewGroup) pane.getParent()).removeView(pane);
            L.i("taskbar glass: removed, the setting is off");
        }
        // The blur was ours to add, so it is ours to take away - otherwise turning the setting
        // off would leave the launcher's window blurring for the rest of its life.
        blurBehind(dragLayer, false);
        dragLayer.invalidate();
    }

    /**
     * Asks the system to blur whatever is behind the taskbar's window.
     *
     * <p>The wallpaper and any app behind the bar are drawn by other processes, so this is the
     * only route to them. Done once per window: the params are the launcher's, and re-pushing
     * them on every resume would make the window manager re-layout the taskbar for nothing.
     */
    private static void blurBehind(ViewGroup dragLayer, boolean on) {
        if (BLURRED.contains(dragLayer) == on) {
            return;
        }
        try {
            ViewGroup.LayoutParams raw = dragLayer.getLayoutParams();
            if (!(raw instanceof WindowManager.LayoutParams)) {
                L.d("taskbar glass: the drag layer is not a window root, no blur behind");
                return;
            }
            Context ctx = dragLayer.getContext();
            WindowManager.LayoutParams lp = (WindowManager.LayoutParams) raw;
            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) {
                return;
            }
            if (on) {
                com.zuxos.desktopplus.core.Glass.blurBehind(ctx, lp,
                        com.zuxos.desktopplus.core.Glass.BEHIND_BLUR_DP);
            } else {
                lp.flags &= ~WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
                lp.setBlurBehindRadius(0);
            }
            wm.updateViewLayout(dragLayer, lp);
            if (on) {
                BLURRED.add(dragLayer);
            } else {
                BLURRED.remove(dragLayer);
            }
            L.i("taskbar glass: blur behind the taskbar window " + (on ? "requested" : "cleared"));
        } catch (Throwable t) {
            // A refused blur is cosmetic; the tint still stands.
            L.d("taskbar glass: blur behind unavailable (" + t + ")");
        }
    }

    private static void sync(ViewGroup dragLayer, View pane, View reference) {
        try {
            ViewGroup.LayoutParams raw = pane.getLayoutParams();
            if (!(raw instanceof FrameLayout.LayoutParams)) {
                return;
            }
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) raw;
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            if (reference != null && reference.getHeight() > 0) {
                lp.gravity = Gravity.TOP | Gravity.START;
                lp.height = reference.getHeight();
                lp.topMargin = reference.getTop();
            } else {
                lp.gravity = Gravity.BOTTOM | Gravity.START;
                lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
                lp.topMargin = 0;
            }
            pane.setLayoutParams(lp);
        } catch (Throwable t) {
            L.d("taskbar glass: could not place the pane (" + t + ")");
        }
    }

    /**
     * The bar: a translucent fill and a hairline along the top edge.
     *
     * <p>No lens here. The liquid-glass shader refracts a captured backdrop, and behind the
     * taskbar there is nothing of ours to capture - so what makes this read as glass is the
     * window blur underneath it, not a distortion of pixels we do not have.
     */
    private static final class BarView extends View {

        private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float mRadius;

        BarView(Context ctx) {
            super(ctx);
            mFill.setColor(TINT);
            mEdge.setStyle(Paint.Style.STROKE);
            mEdge.setStrokeWidth(Math.max(1f, Ui.dp(ctx, 1)));
            mEdge.setColor(0x26FFFFFF);
            mRadius = Ui.dp(ctx, 18);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) {
                return;
            }
            // Rounded at the top, square at the bottom: the bar sits on the screen edge.
            RectF r = new RectF(0, 0, w, h + mRadius);
            canvas.drawRoundRect(r, mRadius, mRadius, mFill);
            canvas.drawRoundRect(r, mRadius, mRadius, mEdge);
        }
    }
}
