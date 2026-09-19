package com.zuxos.desktopplus.hook;

import android.content.Context;
import android.database.ContentObserver;
import android.graphics.PixelFormat;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.zuxos.desktopplus.core.GlassSurface;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Tone;
import com.zuxos.desktopplus.core.Ui;

import java.util.List;

/**
 * The notification shade, as its own panel.
 *
 * <p>Separate from quick settings on purpose: the two are different things you reach for, and the
 * shade grows - four notifications in a settings panel is a compromise, and a list of twenty in
 * one is a mess. Its own panel can be as long as it needs and can be cleared in one go.
 */
public final class NotifyPanel {

    private static final int WIDTH_DP = 400;
    private static final int EDGE_MARGIN_DP = 8;

    private static final android.os.Handler MAIN =
            new android.os.Handler(android.os.Looper.getMainLooper());

    private static View sCurrent;
    private static WindowManager sWm;
    private static ContentObserver sShadeWatcher;
    private static Context sShadeCtx;
    private static Runnable sPending;
    private static long sClosedByTouchAt;
    private static View sAnchor;

    private NotifyPanel() {
    }

    public static void toggle(Context ctx, View anchor, int displayId) {
        if (sCurrent != null) {
            dismiss();
            return;
        }
        if (android.os.SystemClock.uptimeMillis() - sClosedByTouchAt < 400L) {
            // The press that closed it also reached the bell underneath.
            return;
        }
        show(ctx, anchor, displayId);
    }

    public static void dismiss() {
        View current = sCurrent;
        WindowManager wm = sWm;
        sCurrent = null;
        sWm = null;
        sAnchor = null;
        unwatchShade();
        if (current == null || wm == null) {
            return;
        }
        try {
            wm.removeViewImmediate(current);
        } catch (Throwable t) {
            L.d("notification panel already gone: " + t);
        }
    }

    /** Closes it because the taskbar was pressed - the bar's window is above this one. */
    static void onTaskbarPressed(float rawX, float rawY) {
        if (sCurrent == null) {
            return;
        }
        if (hits(sAnchor, rawX, rawY)) {
            sClosedByTouchAt = android.os.SystemClock.uptimeMillis();
        }
        dismiss();
    }

    private static boolean hits(View view, float rawX, float rawY) {
        if (view == null || view.getWindowToken() == null || view.getWidth() <= 0) {
            return true;
        }
        int[] at = new int[2];
        view.getLocationOnScreen(at);
        return rawX >= at[0] && rawX <= at[0] + view.getWidth()
                && rawY >= at[1] && rawY <= at[1] + view.getHeight();
    }

    private static void show(Context taskbarCtx, View anchor, int displayId) {
        dismiss();
        final Context ctx = Overlays.windowContext(taskbarCtx);
        try {
            final int inset = TaskbarTray.barInset(anchor);
            FrameLayout root = new FrameLayout(ctx);
            GlassSurface glass = new GlassSurface(ctx, Ui.dp(ctx, 22), Tone.panelTint(ctx));

            LinearLayout body = new LinearLayout(ctx);
            body.setOrientation(LinearLayout.VERTICAL);
            int pad = Ui.dp(ctx, 14);
            body.setPadding(pad, pad, pad, pad);

            fill(ctx, body, displayId);
            // The shade moves while you are looking at it, and a dismissal is not finished when
            // the tap is: the provider says when it is actually gone.
            watchShade(ctx, () -> fill(ctx, body, displayId));

            ScrollView scroller = new ScrollView(ctx);
            scroller.setVerticalScrollBarEnabled(false);
            scroller.addView(body, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT));
            glass.addView(scroller, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT));

            FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(
                    Ui.dp(ctx, WIDTH_DP), FrameLayout.LayoutParams.WRAP_CONTENT);
            glp.gravity = Gravity.BOTTOM | Gravity.END;
            glp.rightMargin = Ui.dp(ctx, EDGE_MARGIN_DP);
            glp.bottomMargin = inset;
            root.addView(glass, glp);

            root.setFocusableInTouchMode(true);
            root.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() == KeyEvent.ACTION_UP
                        && (keyCode == KeyEvent.KEYCODE_BACK
                        || keyCode == KeyEvent.KEYCODE_ESCAPE)) {
                    dismiss();
                    return true;
                }
                return false;
            });
            root.setOnTouchListener((v, event) -> {
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_OUTSIDE) {
                    dismiss();
                    return true;
                }
                if (action == MotionEvent.ACTION_DOWN) {
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

            WindowManager wm = Overlays.windowManager(ctx);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                    PixelFormat.TRANSLUCENT);
            lp.setTitle("ZuxOS Desktop Plus notifications");
            QuickPanel.edgeToEdge(lp);
            wm.addView(root, lp);
            sCurrent = root;
            sWm = wm;
            sAnchor = anchor;
            root.requestFocus();
        } catch (Throwable t) {
            L.e("could not open the notification panel", t);
        }
    }

    private static void fill(Context ctx, LinearLayout body, int displayId) {
        body.removeAllViews();
        boolean granted = Notifications.available(ctx);
        // Asked first: querying would start the module's process from cold only to be handed an
        // empty list.
        List<Notifications.Note> notes = granted
                ? Notifications.list(ctx, 0) : new java.util.ArrayList<>();

        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(ctx);
        title.setText("Notifications");
        title.setTextColor(Ui.COLOR_TEXT);
        title.setTextSize(16);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        header.addView(title, hlp);

        if (!notes.isEmpty()) {
            TextView clear = new TextView(ctx);
            clear.setText("Clear all");
            clear.setTextColor(0xFF7FB0FF);
            clear.setTextSize(13);
            int cpad = Ui.dp(ctx, 8);
            clear.setPadding(cpad, cpad, cpad, cpad);
            clear.setBackground(Ui.ripple(ctx, 0x00000000, Ui.dp(ctx, 10)));
            clear.setOnClickListener(v -> {
                Notifications.clearAll(ctx);
                dismiss();
            });
            header.addView(clear);
        }
        body.addView(header);

        if (notes.isEmpty()) {
            TextView empty = new TextView(ctx);
            empty.setText(granted
                    ? "Nothing new"
                    : "Switch on notification access in Desktop Plus settings");
            empty.setTextColor(Ui.COLOR_TEXT_DIM);
            empty.setTextSize(13);
            int epad = Ui.dp(ctx, 10);
            empty.setPadding(0, epad, 0, epad);
            body.addView(empty);
            return;
        }
        Notifications.addRows(ctx, body, notes, displayId);
    }

    /** Follows the shade while the panel is open, and lets go when it closes. */
    private static void watchShade(Context ctx, Runnable onChanged) {
        unwatchShade();
        if (!Notifications.available(ctx)) {
            return;
        }
        try {
            ContentObserver observer = new ContentObserver(MAIN) {
                @Override
                public void onChange(boolean selfChange) {
                    Notifications.countChanged();
                    MAIN.removeCallbacks(onChanged);
                    // A moment's grace: a dismissal usually arrives as two or three changes.
                    MAIN.postDelayed(onChanged, 80L);
                }
            };
            ctx.getContentResolver().registerContentObserver(Notifications.uri(), true, observer);
            sShadeWatcher = observer;
            sShadeCtx = ctx;
            sPending = onChanged;
        } catch (Throwable t) {
            L.d("notification panel: could not follow the shade (" + t + ")");
        }
    }

    private static void unwatchShade() {
        if (sPending != null) {
            MAIN.removeCallbacks(sPending);
            sPending = null;
        }
        ContentObserver observer = sShadeWatcher;
        Context ctx = sShadeCtx;
        sShadeWatcher = null;
        sShadeCtx = null;
        if (observer != null && ctx != null) {
            try {
                ctx.getContentResolver().unregisterContentObserver(observer);
            } catch (Throwable ignored) {
                // Never registered, or already gone.
            }
        }
    }
}
