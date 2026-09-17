package com.zuxos.desktopplus.hook;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.zuxos.desktopplus.core.Const;
import com.zuxos.desktopplus.core.Glass;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Ui;
import com.zuxos.desktopplus.desktop.GlassPanel;

import java.util.ArrayList;
import java.util.List;

/**
 * The taskbar's own context menu: hold or right-click empty taskbar space.
 *
 * <p>The menu is triggered from a transparent view added as the drag layer's <em>first</em> child.
 * Being first means it is drawn underneath everything and, because a {@code ViewGroup} offers a
 * touch to its children topmost-first, it only ever sees presses that no icon and no navigation
 * button wanted - which is exactly "empty taskbar space" without having to work out where that is.
 */
public final class TaskbarMenu {

    private static final String TAG_AREA = "zux-desktop-plus-taskbar-menu-area";

    /** The task manager this menu offers, as asked for. */
    private static final String TASK_MANAGER_PKG = "com.rk.taskmanager";

    private static View sCurrent;
    private static WindowManager sWm;

    private TaskbarMenu() {
    }

    /** Adds the hit area to a taskbar, once. */
    static void attachTo(ViewGroup dragLayer, View reference) {
        try {
            if (dragLayer.findViewWithTag(TAG_AREA) != null) {
                return;
            }
            final int displayId = TaskbarTray.displayIdOf(dragLayer);
            final float[] down = new float[2];

            View area = new View(dragLayer.getContext());
            area.setTag(TAG_AREA);
            area.setLongClickable(true);
            area.setOnTouchListener((v, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    down[0] = event.getRawX();
                    down[1] = event.getRawY();
                }
                // Observed, never consumed here: the view's own long-press timer needs the
                // events to carry on to onTouchEvent.
                return false;
            });
            area.setOnLongClickListener(v -> {
                show(v, displayId, down[0]);
                return true;
            });
            area.setOnContextClickListener(v -> {
                show(v, displayId, down[0]);
                return true;
            });

            ViewGroup.LayoutParams lp = TaskbarTray.dragLayerParams(dragLayer, reference);
            dragLayer.addView(area, 0, lp);
            sync(dragLayer, area, reference);
            if (reference != null) {
                reference.addOnLayoutChangeListener(
                        (v, l, t, r, b, ol, ot, or, ob) -> sync(dragLayer, area, reference));
            }
            L.i("taskbar menu: hold or right-click the taskbar");
        } catch (Throwable t) {
            L.e("taskbar menu: could not attach", t);
        }
    }

    /** Takes the hit area back out, so turning the setting off returns the taskbar to normal. */
    static void detachFrom(ViewGroup dragLayer) {
        try {
            View area = dragLayer.findViewWithTag(TAG_AREA);
            if (area != null) {
                dragLayer.removeView(area);
                L.i("taskbar menu: removed, the setting is off");
            }
        } catch (Throwable t) {
            L.d("taskbar menu: could not remove (" + t + ")");
        }
    }

    /** Stretches the hit area across the visible bar. */
    private static void sync(ViewGroup dragLayer, View area, View reference) {
        try {
            ViewGroup.LayoutParams raw = area.getLayoutParams();
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
            area.setLayoutParams(lp);
        } catch (Throwable t) {
            L.d("taskbar menu: could not place the hit area (" + t + ")");
        }
    }

    // --- the menu itself -------------------------------------------------

    public static void dismiss() {
        View current = sCurrent;
        WindowManager wm = sWm;
        sCurrent = null;
        sWm = null;
        if (current == null || wm == null) {
            return;
        }
        try {
            wm.removeViewImmediate(current);
        } catch (Throwable t) {
            L.d("taskbar menu already gone: " + t);
        }
    }

    private static void show(View source, int displayId, float rawX) {
        Context ctx = source.getContext();
        dismiss();
        if (!canShow(ctx)) {
            toast(ctx, "Allow \"display over other apps\" for the launcher to show this menu");
            return;
        }
        try {
            List<Entry> entries = new ArrayList<>();
            entries.add(new Entry("Task manager", () -> launch(ctx, TASK_MANAGER_PKG, displayId)));
            entries.add(new Entry("Desktop Plus settings",
                    () -> launch(ctx, Const.MODULE_PKG, displayId)));
            entries.add(new Entry("Display settings",
                    () -> open(ctx, Settings.ACTION_DISPLAY_SETTINGS, displayId)));

            FrameLayout root = new FrameLayout(ctx);
            GlassPanel glass = new GlassPanel(ctx, Ui.dp(ctx, 16), 0x4D1C1C22);
            LinearLayout body = new LinearLayout(ctx);
            body.setOrientation(LinearLayout.VERTICAL);
            int padV = Ui.dp(ctx, 8);
            body.setPadding(0, padV, 0, padV);
            glass.addView(body, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT));
            for (Entry entry : entries) {
                body.addView(rowFor(ctx, entry));
            }

            FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT);
            glp.gravity = Gravity.BOTTOM | Gravity.START;
            glp.leftMargin = (int) Math.max(0, rawX - Ui.dp(ctx, 90));
            glp.bottomMargin = barHeight(source) + Ui.dp(ctx, 8);
            root.addView(glass, glp);
            glass.setSource(root);
            glass.post(() -> {
                // Held near the right-hand edge - where the tray is - the menu would run off the
                // display. Its width is only known once it has been measured.
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) glass.getLayoutParams();
                int max = root.getWidth() - glass.getWidth() - Ui.dp(ctx, 8);
                int clamped = Math.max(0, Math.min(lp.leftMargin, Math.max(0, max)));
                if (clamped != lp.leftMargin) {
                    lp.leftMargin = clamped;
                    glass.setLayoutParams(lp);
                }
                glass.refresh();
            });

            root.setFocusableInTouchMode(true);
            root.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() == android.view.KeyEvent.ACTION_UP
                        && (keyCode == android.view.KeyEvent.KEYCODE_BACK
                        || keyCode == android.view.KeyEvent.KEYCODE_ESCAPE)) {
                    dismiss();
                    return true;
                }
                return false;
            });
            root.setOnTouchListener((v, event) -> {
                if (event.getAction() == MotionEvent.ACTION_OUTSIDE
                        || event.getAction() == MotionEvent.ACTION_DOWN) {
                    float x = event.getX();
                    float y = event.getY();
                    boolean inside = x >= glass.getLeft() && x <= glass.getRight()
                            && y >= glass.getTop() && y <= glass.getBottom();
                    if (!inside) {
                        dismiss();
                        return true;
                    }
                }
                return false;
            });

            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                    PixelFormat.TRANSLUCENT);
            lp.setTitle("ZuxOS Desktop Plus taskbar menu");
            Glass.blurBehind(ctx, lp, Glass.BEHIND_BLUR_DP);
            wm.addView(root, lp);
            sCurrent = root;
            sWm = wm;
            root.requestFocus();
        } catch (Throwable t) {
            L.e("could not show the taskbar menu", t);
        }
    }

    private static View rowFor(Context ctx, Entry entry) {
        TextView tv = new TextView(ctx);
        tv.setText(entry.title);
        tv.setTextColor(Ui.COLOR_TEXT);
        tv.setTextSize(14);
        tv.setSingleLine(true);
        int padH = Ui.dp(ctx, 18);
        int padV = Ui.dp(ctx, 11);
        tv.setPadding(padH, padV, padH, padV);
        tv.setMinimumWidth(Ui.dp(ctx, 180));
        tv.setBackground(Ui.ripple(ctx, 0x00000000, 0));
        tv.setOnClickListener(v -> {
            dismiss();
            try {
                entry.action.run();
            } catch (Throwable t) {
                L.e("taskbar menu action failed: " + entry.title, t);
            }
        });
        return tv;
    }

    private static void launch(Context ctx, String pkg, int displayId) {
        try {
            Intent intent = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
            if (intent == null) {
                toast(ctx, pkg + " is not installed");
                return;
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent, launchOptions(displayId));
        } catch (Throwable t) {
            L.e("could not launch " + pkg, t);
            toast(ctx, "Could not open " + pkg);
        }
    }

    private static void open(Context ctx, String action, int displayId) {
        try {
            Intent intent = new Intent(action);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent, launchOptions(displayId));
        } catch (Throwable t) {
            L.e("could not open " + action, t);
            toast(ctx, "That settings screen is not available here");
        }
    }

    /** Whatever is opened has to land on the display the taskbar is on. */
    private static Bundle launchOptions(int displayId) {
        try {
            ActivityOptions opts = ActivityOptions.makeBasic();
            if (displayId >= 0) {
                opts.setLaunchDisplayId(displayId);
            }
            return opts.toBundle();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * The height of the bar this press came from.
     *
     * <p>Walked up from the hit area rather than looked up by name: with a second display there
     * is more than one taskbar, and the menu has to clear the one it was opened on.
     */
    private static int barHeight(View source) {
        View root = source;
        while (root.getParent() instanceof View) {
            root = (View) root.getParent();
        }
        if (root.getHeight() > 0) {
            View reference = root instanceof ViewGroup
                    ? TaskbarTray.rowReference((ViewGroup) root) : null;
            if (reference != null && reference.getHeight() > 0) {
                return root.getHeight() - reference.getTop();
            }
            return root.getHeight();
        }
        return source.getHeight() > 0 ? source.getHeight() : Ui.dp(source.getContext(), 56);
    }

    private static boolean canShow(Context ctx) {
        try {
            return Settings.canDrawOverlays(ctx);
        } catch (Throwable t) {
            return false;
        }
    }

    private static void toast(Context ctx, String msg) {
        try {
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
            L.w(msg);
        }
    }

    private static final class Entry {
        final String title;
        final Runnable action;

        Entry(String title, Runnable action) {
            this.title = title;
            this.action = action;
        }
    }
}
