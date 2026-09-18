package com.zuxos.desktopplus.hook;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;
import com.zuxos.desktopplus.core.Ui;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Glass for the launcher's own taskbar.
 *
 * <p>Two things have to happen: the opaque bar the launcher paints has to stop being opaque, and
 * a translucent pane has to take its place. A pane alone is not enough - it is translucent, so
 * the original shows straight through it, which is what "the white is still behind" looked like.
 *
 * <p>There is deliberately no window blur here any more. An earlier version added
 * {@code FLAG_BLUR_BEHIND} to the taskbar's own window; that window expands to fill the display
 * whenever the app drawer opens, so it blurred the entire screen, and pushing new layout params
 * into a window the launcher owns is the likeliest cause of the input that stopped responding
 * along with it. Nothing here touches the launcher's window any more. It swaps a drawable on a
 * view and adds a child, and both are undone exactly.
 */
public final class TaskbarGlass {

    /** Tint of the bar itself. */
    private static final int TINT = 0x66101014;

    private static final String TAG_GLASS = "zux-desktop-plus-taskbar-glass";

    private static final Map<View, WeakReference<View>> PANES = new WeakHashMap<>();
    /** The background each taskbar had before we replaced it, so it can be put back. */
    private static final Map<View, Drawable> ORIGINAL_BACKGROUNDS = new WeakHashMap<>();

    private static boolean sInstalled;

    private TaskbarGlass() {
    }

    /**
     * Stops the taskbar painting its own bar, where it paints it in {@code onDraw}.
     *
     * <p>Hooked on {@code TaskbarDragLayer} itself rather than on {@code View}, so it runs only
     * for the taskbar. On this firmware the method is not declared there at all, so the hook
     * matches nothing and {@link #apply} deals with the background drawable instead; other builds
     * do paint the bar here, and a hook that matches nothing costs nothing.
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
            L.i("taskbar glass: onDraw suppression installed x" + hooked);
            if (hooked == 0) {
                // Whatever paints the bar on this build, it is not onDraw. Naming the drawing
                // methods it does declare is what makes the next attempt aimed rather than
                // another guess.
                L.i("taskbar glass: drawing methods on " + cls.getSimpleName() + ": "
                        + drawMethodsOf(cls));
            }
        } catch (Throwable t) {
            L.e("taskbar glass: could not install", t);
        }
    }

    /** The draw-related methods this build actually overrides, for the log. */
    private static String drawMethodsOf(Class<?> cls) {
        StringBuilder sb = new StringBuilder();
        for (Class<?> c = cls; c != null && c != View.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                String name = m.getName();
                if (name.equals("draw") || name.equals("onDraw") || name.equals("dispatchDraw")
                        || name.startsWith("drawBackground")) {
                    if (sb.length() > 0) {
                        sb.append(", ");
                    }
                    sb.append(c.getSimpleName()).append('.').append(name)
                            .append('(').append(m.getParameterCount()).append(')');
                }
            }
        }
        return sb.length() == 0 ? "none" : sb.toString();
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
            if (reference == null || reference.getHeight() <= 0) {
                // Without the row's geometry the pane has no height to take, and a plain View
                // asked to wrap its content fills the space it is offered - which is the whole
                // drag layer. Wait for a layout pass instead; this runs again on every resume.
                L.d("taskbar glass: the bar's geometry is not known yet");
                return;
            }
            ViewGroup.LayoutParams lp = TaskbarTray.dragLayerParams(dragLayer, reference);
            if (lp == null) {
                L.w("taskbar glass: the drag layer's layout params are not reproducible, "
                        + "leaving its background alone");
                return;
            }
            BarView pane = new BarView(dragLayer.getContext());
            pane.setTag(TAG_GLASS);
            // Index 0 so it is behind every icon and every button.
            dragLayer.addView(pane, 0, lp);
            PANES.put(root, new WeakReference<>(pane));
            sync(dragLayer, pane, reference);
            if (reference != null) {
                reference.addOnLayoutChangeListener(
                        (v, l, t, r, b, ol, ot, or, ob) -> sync(dragLayer, pane, reference));
            }
            takeBackground(dragLayer);
            dragLayer.invalidate();
            L.i("taskbar glass: applied");
        } catch (Throwable t) {
            // The pane is what licenses hiding the launcher's own bar, so dropping it puts the
            // stock one back rather than leaving a transparent taskbar behind.
            PANES.remove(root);
            L.e("taskbar glass: could not apply", t);
        }
    }

    /**
     * Takes the taskbar's own background off, remembering it.
     *
     * <p>A background drawable is painted by {@code View.draw}, not by {@code onDraw}, so no hook
     * on {@code onDraw} can suppress it - and a child pane is drawn over it but is translucent,
     * so it shows through. Removing it is the only thing that works, and putting the same
     * instance back afterwards is an exact undo.
     */
    private static void takeBackground(ViewGroup dragLayer) {
        if (ORIGINAL_BACKGROUNDS.containsKey(dragLayer)) {
            return;
        }
        Drawable background = dragLayer.getBackground();
        ORIGINAL_BACKGROUNDS.put(dragLayer, background);
        if (background == null) {
            L.i("taskbar glass: the taskbar has no background drawable - if the stock bar is "
                    + "still visible, this build paints it in its own drawing code");
            return;
        }
        dragLayer.setBackground(null);
        L.i("taskbar glass: removed the taskbar's background ("
                + background.getClass().getName() + ")");
    }

    private static void restoreBackground(ViewGroup dragLayer) {
        if (!ORIGINAL_BACKGROUNDS.containsKey(dragLayer)) {
            return;
        }
        Drawable background = ORIGINAL_BACKGROUNDS.remove(dragLayer);
        if (background != null) {
            dragLayer.setBackground(background);
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
        restoreBackground(dragLayer);
        dragLayer.invalidate();
    }

    private static void sync(ViewGroup dragLayer, View pane, View reference) {
        try {
            ViewGroup.LayoutParams raw = pane.getLayoutParams();
            if (!(raw instanceof FrameLayout.LayoutParams)) {
                return;
            }
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) raw;
            if (reference == null || reference.getHeight() <= 0) {
                // Never fall back to a size that fills the parent: this pane paints a tint, and
                // one covering the whole drag layer is the "glass took over the screen" bug.
                return;
            }
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.height = reference.getHeight();
            lp.topMargin = reference.getTop();
            pane.setLayoutParams(lp);
        } catch (Throwable t) {
            L.d("taskbar glass: could not place the pane (" + t + ")");
        }
    }

    /**
     * The bar: a translucent fill and a hairline along the top edge.
     *
     * <p>No lens and no blur. The liquid-glass shader refracts a captured backdrop, and behind
     * the taskbar is another process's window - nothing this module can capture. The one API
     * that could blur it works on the window rather than the view, which is exactly what caused
     * the damage this replaces.
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
