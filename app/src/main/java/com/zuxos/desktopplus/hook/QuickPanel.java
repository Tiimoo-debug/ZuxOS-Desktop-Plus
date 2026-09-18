package com.zuxos.desktopplus.hook;

import android.app.ActivityOptions;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.net.wifi.WifiManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.Glass;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Thermals;
import com.zuxos.desktopplus.core.TrayIcons;
import com.zuxos.desktopplus.core.Ui;
import com.zuxos.desktopplus.desktop.GlassPanel;

/**
 * The panel behind the taskbar tray: what the connection is, and the switches for it.
 *
 * <p>A window of its own, because the taskbar's window is a strip 80px tall that clips everything
 * inside it. It is anchored to the tray that opened it and closes on any tap outside.
 *
 * <p>Toggling radios is not something an ordinary app is allowed to do any more - the platform
 * dropped {@code setWifiEnabled} for apps in Android 10 and the Bluetooth equivalent in 13. The
 * launcher may be privileged enough for the direct call to work, so it is tried first and the
 * result is checked; where it is refused, the matching system panel opens instead, which does
 * the same job in one more tap rather than failing silently.
 */
public final class QuickPanel {

    /** How long between checks that a radio has reached the state we asked it for. */
    private static final long TOGGLE_POLL_MS = 400;

    /** How many of those checks before giving up and handing over to settings (~4 seconds). */
    private static final int TOGGLE_POLL_TRIES = 10;

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
            SysState state = SysState.get(ctx);

            FrameLayout root = new FrameLayout(ctx);
            GlassPanel glass = new GlassPanel(ctx, Ui.dp(ctx, 22), 0x4D1C1C22);
            LinearLayout body = new LinearLayout(ctx);
            body.setOrientation(LinearLayout.VERTICAL);
            int pad = Ui.dp(ctx, 16);
            body.setPadding(pad, pad, pad, pad);
            glass.addView(body, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT));

            body.addView(networkHeader(ctx, state));
            body.addView(divider(ctx));
            body.addView(row(ctx, TrayIcons.wifi(4, Ui.COLOR_TEXT), "Wi-Fi",
                    state.wifiEnabled() ? "On" : "Off",
                    () -> setWifi(ctx, state, displayId)));
            Boolean bt = bluetoothOn(ctx);
            body.addView(row(ctx, TrayIcons.bluetooth(Ui.COLOR_TEXT), "Bluetooth",
                    bt == null ? "Settings" : bt ? "On" : "Off",
                    () -> setBluetooth(ctx, displayId)));
            body.addView(divider(ctx));
            body.addView(batteryRow(ctx, state));
            addTempRow(ctx, body, "CPU temperature", state.cpuTemp());
            addTempRow(ctx, body, "GPU temperature", state.gpuTemp());
            addTempRow(ctx, body, "Battery temperature", state.batteryTemp());
            body.addView(divider(ctx));
            body.addView(action(ctx, "Network & internet",
                    () -> openSettings(ctx, displayId, Settings.ACTION_WIFI_SETTINGS)));
            body.addView(action(ctx, "All settings",
                    () -> openSettings(ctx, displayId, Settings.ACTION_SETTINGS)));

            FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(
                    Ui.dp(ctx, 320), FrameLayout.LayoutParams.WRAP_CONTENT);
            glp.gravity = Gravity.BOTTOM | Gravity.END;
            glp.rightMargin = Ui.dp(ctx, 12);
            glp.bottomMargin = anchorHeight(anchor) + Ui.dp(ctx, 12);
            root.addView(glass, glp);
            // Nothing of ours sits behind this panel - the window under it belongs to whatever
            // app is running - so the lens has only the tint and the system's blur to work with.
            glass.setSource(root);
            glass.post(glass::refresh);

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

            WindowManager wm = Overlays.windowManager(ctx);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                    PixelFormat.TRANSLUCENT);
            lp.setTitle("ZuxOS Desktop Plus tray");
            Glass.blurBehind(ctx, lp, Glass.BEHIND_BLUR_DP);
            wm.addView(root, lp);
            sCurrent = root;
            sWm = wm;
            root.requestFocus();
        } catch (Throwable t) {
            L.e("could not open the tray panel", t);
        }
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

    private static android.graphics.drawable.Drawable headerIcon(SysState state) {
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

    private static View batteryRow(Context ctx, SysState state) {
        int percent = state.batteryPercent();
        String value = percent < 0 ? "Unknown"
                : percent + "%" + (state.charging() ? " - charging" : "");
        return row(ctx, TrayIcons.battery(percent < 0 ? 0 : percent, state.charging(),
                Ui.COLOR_TEXT), "Battery", value, null);
    }

    /** Adds a temperature line, or nothing at all when that sensor is not readable here. */
    private static void addTempRow(Context ctx, LinearLayout body, String title, float celsius) {
        if (!Cfg.taskbarTemps()) {
            return;
        }
        String value = Thermals.format(celsius);
        if (value.isEmpty()) {
            return;
        }
        body.addView(row(ctx, null, title, value, null));
    }

    private static View row(Context ctx, android.graphics.drawable.Drawable icon, String title,
            String value, Runnable action) {
        LinearLayout line = new LinearLayout(ctx);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Ui.dp(ctx, 8);
        line.setPadding(pad, pad, pad, pad);

        int size = Ui.dp(ctx, 20);
        if (icon != null) {
            ImageView iv = new ImageView(ctx);
            iv.setImageDrawable(icon);
            line.addView(iv, new LinearLayout.LayoutParams(size, size));
        } else {
            // An empty slot, so rows without an icon still line up with the ones that have one.
            line.addView(new View(ctx), new LinearLayout.LayoutParams(size, size));
        }

        TextView label = new TextView(ctx);
        label.setText(title);
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

        if (action != null) {
            line.setBackground(Ui.ripple(ctx, 0x00000000, Ui.dp(ctx, 12)));
            line.setOnClickListener(v -> {
                try {
                    action.run();
                } catch (Throwable t) {
                    L.e("tray action failed: " + title, t);
                }
            });
        }
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
            try {
                action.run();
            } catch (Throwable t) {
                L.e("tray action failed: " + title, t);
            }
        });
        return tv;
    }

    private static View divider(Context ctx) {
        View v = new View(ctx);
        v.setBackgroundColor(0x1AFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(ctx, 0.5f)));
        lp.topMargin = Ui.dp(ctx, 8);
        lp.bottomMargin = Ui.dp(ctx, 8);
        v.setLayoutParams(lp);
        return v;
    }

    // --- radios ----------------------------------------------------------

    private static void setWifi(Context ctx, SysState state, int displayId) {
        boolean want = !state.wifiEnabled();
        boolean applied = false;
        try {
            WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
            applied = wm != null && wm.setWifiEnabled(want);
        } catch (Throwable t) {
            L.d("tray: direct Wi-Fi toggle refused (" + t + ")");
        }
        if (!applied) {
            // Android 10 and up refuse this for anything that is not a system component. The
            // system's own panel has the switch, so hand the user straight to it.
            openSettings(ctx, displayId, Settings.Panel.ACTION_WIFI);
            return;
        }
        confirm(ctx, () -> state.wifiEnabled() == want, displayId, Settings.Panel.ACTION_WIFI);
    }

    /**
     * Whether Bluetooth is on, or null when the platform will not say.
     *
     * <p>{@code isEnabled()} needs BLUETOOTH_CONNECT from Android 12, which the launcher may not
     * hold. Treating that refusal as "off" would label the row wrongly and turn the switch into a
     * one-way trip, so it is reported as unknown and the row sends the user to settings instead.
     */
    private static Boolean bluetoothOn(Context ctx) {
        try {
            BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = bm != null ? bm.getAdapter() : null;
            if (adapter == null) {
                return null;
            }
            return adapter.isEnabled();
        } catch (Throwable t) {
            L.d("tray: Bluetooth state not readable (" + t + ")");
            return null;
        }
    }

    /**
     * Turns Bluetooth on or off.
     *
     * <p>{@code BluetoothAdapter.enable()} was taken away from apps in Android 13, so it is
     * called reflectively and only believed if it reports success. Failing that, turning it on
     * has a system consent dialog; turning it off has nothing, so that opens the settings screen.
     */
    private static void setBluetooth(Context ctx, int displayId) {
        Boolean on = bluetoothOn(ctx);
        if (on == null) {
            // We cannot read it, so we certainly cannot flip it sensibly.
            openSettings(ctx, displayId, Settings.ACTION_BLUETOOTH_SETTINGS);
            return;
        }
        boolean want = !on;
        if (invokeAdapter(ctx, want ? "enable" : "disable")) {
            dismiss();
            return;
        }
        // Turning it on has a system consent dialog; turning it off has nothing but settings.
        openSettings(ctx, displayId, want
                ? BluetoothAdapter.ACTION_REQUEST_ENABLE : Settings.ACTION_BLUETOOTH_SETTINGS);
    }

    private static boolean invokeAdapter(Context ctx, String method) {
        try {
            BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = bm != null ? bm.getAdapter() : null;
            if (adapter == null) {
                return false;
            }
            Object result = BluetoothAdapter.class.getMethod(method).invoke(adapter);
            return Boolean.TRUE.equals(result);
        } catch (Throwable t) {
            L.d("tray: direct Bluetooth toggle refused (" + t + ")");
            return false;
        }
    }

    /**
     * Gives a radio a moment to report the state it was asked for, and falls back if it does not.
     *
     * <p>{@code setWifiEnabled} returns true for "request accepted", which on a locked-down build
     * is not the same as "it happened".
     */
    private static void confirm(Context ctx, java.util.concurrent.Callable<Boolean> settled,
            int displayId, String fallbackAction) {
        final View current = sCurrent;
        if (current == null) {
            return;
        }
        poll(ctx, current, settled, displayId, fallbackAction, TOGGLE_POLL_TRIES);
    }

    private static void poll(Context ctx, View owner, java.util.concurrent.Callable<Boolean> settled,
            int displayId, String fallbackAction, int triesLeft) {
        owner.postDelayed(() -> {
            // The user may have closed the panel, or opened a new one, while we waited. Acting
            // now would throw a settings screen over whatever they moved on to.
            if (sCurrent != owner) {
                return;
            }
            boolean ok;
            try {
                ok = Boolean.TRUE.equals(settled.call());
            } catch (Throwable t) {
                ok = false;
            }
            if (ok) {
                dismiss();
            } else if (triesLeft > 1) {
                // Bringing a radio up takes seconds, not one frame - keep watching before
                // deciding the request was quietly refused.
                poll(ctx, owner, settled, displayId, fallbackAction, triesLeft - 1);
            } else {
                openSettings(ctx, displayId, fallbackAction);
            }
        }, TOGGLE_POLL_MS);
    }

    private static void openSettings(Context ctx, int displayId, String action) {
        try {
            Intent intent = new Intent(action);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent, launchOptions(displayId));
        } catch (Throwable t) {
            L.e("could not open " + action, t);
            toast(ctx, "That settings screen is not available here");
        }
        dismiss();
    }

    /** Settings has to land on the display the taskbar is on, not on the tablet screen. */
    private static android.os.Bundle launchOptions(int displayId) {
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

    // --- plumbing --------------------------------------------------------

    private static int anchorHeight(View anchor) {
        View root = taskbarRoot(anchor);
        if (root instanceof android.view.ViewGroup && root.getHeight() > 0) {
            // The drag layer is taller than the bar you can see - it reserves room for the
            // stashed handle - so the panel is placed above the row, not above the window.
            View reference = TaskbarTray.rowReference((android.view.ViewGroup) root);
            if (reference != null && reference.getHeight() > 0) {
                return root.getHeight() - reference.getTop();
            }
            return root.getHeight();
        }
        return anchor != null ? anchor.getHeight() : 0;
    }

    /** The taskbar window the tray lives in, used both to position and to capture the backdrop. */
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
