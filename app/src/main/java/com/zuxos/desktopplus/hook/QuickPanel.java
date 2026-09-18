package com.zuxos.desktopplus.hook;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.TrayIcons;
import com.zuxos.desktopplus.core.Ui;
import com.zuxos.desktopplus.desktop.GlassPanel;

import java.util.ArrayList;
import java.util.List;

/**
 * Quick settings, in the shape Samsung DeX uses: the connection at the top, a grid of round
 * toggles, then the sliders.
 *
 * <p>A window of its own, because the taskbar's window is a strip that clips everything inside
 * it. There is deliberately no {@code FLAG_BLUR_BEHIND} here - the window is full-screen so that
 * flag blurs the whole display, which is not what a panel in the corner should do to the desktop
 * behind it.
 */
public final class QuickPanel {

    /** Tiles per row, as DeX lays them out. */
    private static final int COLUMNS = 5;

    private static View sCurrent;
    private static WindowManager sWm;

    private QuickPanel() {
    }

    public static void toggle(Context ctx, View anchor, int displayId) {
        if (sCurrent != null) {
            dismiss();
            return;
        }
        show(ctx, anchor, displayId);
    }

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
            L.d("quick panel already gone: " + t);
        }
    }

    private static void show(Context taskbarCtx, View anchor, int displayId) {
        if (!canShow(taskbarCtx)) {
            toast(taskbarCtx, "Allow \"display over other apps\" for the launcher to open the tray");
            return;
        }
        // The taskbar's own context is bound to the taskbar's window type, and the window manager
        // refuses a window of any other type from it.
        final Context ctx = Overlays.windowContext(taskbarCtx);
        try {
            FrameLayout root = new FrameLayout(ctx);
            GlassPanel glass = new GlassPanel(ctx, Ui.dp(ctx, 22), 0x59161620);

            ScrollView scroller = new ScrollView(ctx);
            scroller.setVerticalScrollBarEnabled(false);
            LinearLayout body = new LinearLayout(ctx);
            body.setOrientation(LinearLayout.VERTICAL);
            int pad = Ui.dp(ctx, 16);
            body.setPadding(pad, pad, pad, pad);
            scroller.addView(body, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT));
            glass.addView(scroller, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT));

            fill(ctx, body, displayId);

            FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(
                    Ui.dp(ctx, 360), FrameLayout.LayoutParams.WRAP_CONTENT);
            glp.gravity = Gravity.BOTTOM | Gravity.END;
            glp.rightMargin = Ui.dp(ctx, 12);
            glp.bottomMargin = anchorHeight(anchor) + Ui.dp(ctx, 12);
            root.addView(glass, glp);
            glass.setSource(root);
            glass.post(glass::refresh);

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

            WindowManager wm = Overlays.windowManager(ctx);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                    PixelFormat.TRANSLUCENT);
            lp.setTitle("ZuxOS Desktop Plus tray");
            wm.addView(root, lp);
            sCurrent = root;
            sWm = wm;
            root.requestFocus();
        } catch (Throwable t) {
            L.e("could not open the tray panel", t);
        }
    }

    /** Rebuilds the contents in place, so a toggle repaints without the panel blinking. */
    private static void fill(Context ctx, LinearLayout body, int displayId) {
        body.removeAllViews();
        SysState state = SysState.get(ctx);

        body.addView(networkHeader(ctx, state));
        body.addView(tiles(ctx, state, displayId, () -> fill(ctx, body, displayId)));
        body.addView(divider(ctx));
        SoundRows.addTo(ctx, body, displayId);
        body.addView(divider(ctx));
        body.addView(batteryRow(ctx, state));
        body.addView(action(ctx, "Network & internet",
                () -> QuickTiles.open(ctx, Settings.ACTION_WIFI_SETTINGS, displayId)));
        body.addView(action(ctx, "All settings",
                () -> QuickTiles.open(ctx, Settings.ACTION_SETTINGS, displayId)));
    }

    private static View tiles(Context ctx, SysState state, int displayId, Runnable onActed) {
        List<QuickTiles.Tile> tiles = new ArrayList<>();
        tiles.add(QuickTiles.wifi(ctx, state, displayId));
        tiles.add(QuickTiles.bluetooth(ctx, displayId));
        tiles.add(QuickTiles.torch(ctx));
        tiles.add(QuickTiles.rotation(ctx, displayId));
        tiles.add(QuickTiles.flightMode(ctx, displayId));
        tiles.add(QuickTiles.settings(ctx, displayId));

        GridLayout grid = new GridLayout(ctx);
        grid.setColumnCount(COLUMNS);
        for (QuickTiles.Tile tile : tiles) {
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            // Equal columns: an undefined spec with a weight is how GridLayout is told to share
            // the width rather than size each cell to its own caption.
            lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            lp.width = 0;
            grid.addView(QuickTiles.view(ctx, tile, onActed), lp);
        }
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        glp.topMargin = Ui.dp(ctx, 12);
        grid.setLayoutParams(glp);
        return grid;
    }

    // --- rows ------------------------------------------------------------

    private static View networkHeader(Context ctx, SysState state) {
        LinearLayout line = new LinearLayout(ctx);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);

        ImageView icon = new ImageView(ctx);
        icon.setImageDrawable(headerIcon(state));
        int size = Ui.dp(ctx, 26);
        line.addView(icon, new LinearLayout.LayoutParams(size, size));

        LinearLayout text = new LinearLayout(ctx);
        text.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = Ui.dp(ctx, 12);
        line.addView(text, tlp);

        TextView title = new TextView(ctx);
        title.setText(state.netLabel());
        title.setTextColor(Ui.COLOR_TEXT);
        title.setTextSize(16);
        text.addView(title);

        String detail = state.netDetail();
        if (!detail.isEmpty()) {
            TextView sub = new TextView(ctx);
            sub.setText(detail);
            sub.setTextColor(Ui.COLOR_TEXT_DIM);
            sub.setTextSize(12);
            text.addView(sub);
        }
        return line;
    }

    private static Drawable headerIcon(SysState state) {
        switch (state.netType()) {
            case SysState.NET_ETHERNET:
                return TrayIcons.ethernet(Ui.COLOR_TEXT);
            case SysState.NET_WIFI:
                return TrayIcons.wifi(state.netLevel(), Ui.COLOR_TEXT);
            case SysState.NET_CELLULAR:
                return TrayIcons.cellular(state.netLevel(), Ui.COLOR_TEXT);
            default:
                return TrayIcons.wifiOff(Ui.COLOR_TEXT);
        }
    }

    /**
     * The battery line.
     *
     * <p>Temperatures are deliberately not here. They belong in the tray, where they are glanced
     * at; a panel you opened to change something should not be a sensor readout.
     */
    private static View batteryRow(Context ctx, SysState state) {
        int percent = state.batteryPercent();
        String value = percent < 0 ? "Unknown"
                : percent + "%" + (state.charging() ? " - charging" : "");
        LinearLayout line = new LinearLayout(ctx);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Ui.dp(ctx, 8);
        line.setPadding(pad, pad, pad, pad);

        ImageView iv = new ImageView(ctx);
        iv.setImageDrawable(TrayIcons.battery(percent < 0 ? 0 : percent, state.charging(),
                Ui.COLOR_TEXT));
        int size = Ui.dp(ctx, 20);
        line.addView(iv, new LinearLayout.LayoutParams(size, size));

        TextView label = new TextView(ctx);
        label.setText("Battery");
        label.setTextColor(Ui.COLOR_TEXT);
        label.setTextSize(14);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        llp.leftMargin = Ui.dp(ctx, 12);
        line.addView(label, llp);

        TextView valueView = new TextView(ctx);
        valueView.setText(value);
        valueView.setTextColor(Ui.COLOR_TEXT_DIM);
        valueView.setTextSize(13);
        line.addView(valueView);
        return line;
    }

    private static View action(Context ctx, String title, Runnable action) {
        TextView tv = new TextView(ctx);
        tv.setText(title);
        tv.setTextColor(Ui.COLOR_TEXT);
        tv.setTextSize(14);
        int pad = Ui.dp(ctx, 10);
        tv.setPadding(pad, pad, pad, pad);
        tv.setBackground(Ui.ripple(ctx, 0x00000000, Ui.dp(ctx, 12)));
        tv.setOnClickListener(v -> {
            dismiss();
            try {
                action.run();
            } catch (Throwable t) {
                L.e("tray action failed: " + title, t);
            }
        });
        return tv;
    }

    /** A small caption above a group of rows. */
    static View sectionLabel(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(Ui.COLOR_TEXT_DIM);
        tv.setTextSize(11);
        tv.setAllCaps(true);
        tv.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(ctx, 10);
        lp.leftMargin = Ui.dp(ctx, 6);
        lp.bottomMargin = Ui.dp(ctx, 2);
        tv.setLayoutParams(lp);
        return tv;
    }

    private static View divider(Context ctx) {
        View v = new View(ctx);
        v.setBackgroundColor(0x1AFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(ctx, 0.5f)));
        lp.topMargin = Ui.dp(ctx, 10);
        lp.bottomMargin = Ui.dp(ctx, 6);
        v.setLayoutParams(lp);
        return v;
    }

    // --- plumbing --------------------------------------------------------

    private static int anchorHeight(View anchor) {
        View root = taskbarRoot(anchor);
        if (root instanceof ViewGroup && root.getHeight() > 0) {
            // The drag layer is taller than the bar you can see - it reserves room for the
            // stashed handle - so the panel is placed above the row, not above the window.
            View reference = TaskbarTray.rowReference((ViewGroup) root);
            if (reference != null && reference.getHeight() > 0) {
                return root.getHeight() - reference.getTop();
            }
            return root.getHeight();
        }
        return anchor != null ? anchor.getHeight() : 0;
    }

    private static View taskbarRoot(View anchor) {
        if (anchor == null) {
            return null;
        }
        View v = anchor;
        while (v.getParent() instanceof View) {
            v = (View) v.getParent();
        }
        return v;
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
}
