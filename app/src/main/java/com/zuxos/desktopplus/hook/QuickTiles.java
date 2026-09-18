package com.zuxos.desktopplus.hook;

import android.app.ActivityOptions;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.hardware.camera2.CameraManager;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Su;
import com.zuxos.desktopplus.core.TrayIcons;
import com.zuxos.desktopplus.core.Ui;

/**
 * The round toggles in the quick-settings panel, and what they are actually able to do.
 *
 * <p>Almost none of these are things an ordinary app may still do. {@code setWifiEnabled} went in
 * Android 10, the Bluetooth equivalent in 13, and rotation, aeroplane mode and brightness all sit
 * behind permissions a launcher may or may not hold. Rather than guess, every tile tries the real
 * call, checks whether it took, and falls back to the system screen that can do it - and says in
 * the log which route it took, because that is the only way to learn what this firmware allows.
 *
 * <p>The torch is the exception: {@code CameraManager.setTorchMode} needs no permission at all.
 */
public final class QuickTiles {

    /** Lit tile: the accent, as DeX draws an enabled toggle. */
    private static final int ON_FILL = 0xFF3F7FF5;
    private static final int OFF_FILL = 0x33FFFFFF;

    private QuickTiles() {
    }

    /** What a tile needs to draw itself and to act. */
    public interface Tile {
        String label();

        Drawable icon(int color);

        /** True lit, false unlit, null when the platform will not say. */
        Boolean state();

        /** Runs on tap; returns true when the panel should close. */
        boolean toggle();
    }

    /**
     * One round icon over a caption, the shape DeX uses.
     *
     * @param onActed called after a tap so the panel can repaint or close
     */
    public static View view(Context ctx, Tile tile, Runnable onActed) {
        LinearLayout cell = new LinearLayout(ctx);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        int padV = Ui.dp(ctx, 10);
        cell.setPadding(0, padV, 0, padV);

        Boolean state = tile.state();
        boolean lit = Boolean.TRUE.equals(state);
        int diameter = Ui.dp(ctx, 46);

        ImageView icon = new ImageView(ctx);
        icon.setBackground(Ui.roundRect(lit ? ON_FILL : OFF_FILL, diameter / 2));
        int inset = Ui.dp(ctx, 12);
        icon.setPadding(inset, inset, inset, inset);
        icon.setImageDrawable(tile.icon(Ui.COLOR_TEXT));
        cell.addView(icon, new LinearLayout.LayoutParams(diameter, diameter));

        TextView caption = new TextView(ctx);
        caption.setText(tile.label());
        caption.setTextColor(lit ? Ui.COLOR_TEXT : Ui.COLOR_TEXT_DIM);
        caption.setTextSize(11);
        caption.setGravity(Gravity.CENTER_HORIZONTAL);
        caption.setMaxLines(2);
        caption.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.topMargin = Ui.dp(ctx, 6);
        cell.addView(caption, clp);

        cell.setBackground(Ui.ripple(ctx, 0x00000000, Ui.dp(ctx, 14)));
        cell.setOnClickListener(v -> {
            boolean close;
            try {
                close = tile.toggle();
            } catch (Throwable t) {
                L.e("tile failed: " + tile.label(), t);
                return;
            }
            if (close) {
                QuickPanel.dismiss();
            } else if (onActed != null) {
                onActed.run();
            }
        });
        return cell;
    }

    // --- the tiles -------------------------------------------------------

    public static Tile wifi(Context ctx, SysState state, int displayId) {
        return new Tile() {
            @Override
            public String label() {
                return "Wi-Fi";
            }

            @Override
            public Drawable icon(int color) {
                return state.wifiEnabled() ? TrayIcons.wifi(4, color) : TrayIcons.wifiOff(color);
            }

            @Override
            public Boolean state() {
                return state.wifiEnabled();
            }

            @Override
            public boolean toggle() {
                boolean want = !state.wifiEnabled();
                if (setWifi(ctx, want)) {
                    L.i("tiles: Wi-Fi toggled directly");
                    return false;
                }
                L.i("tiles: Wi-Fi is not ours to set, opening the system panel");
                open(ctx, Settings.Panel.ACTION_WIFI, displayId);
                return true;
            }
        };
    }

    private static boolean setWifi(Context ctx, boolean want) {
        try {
            WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
            if (wm == null || !wm.setWifiEnabled(want)) {
                return false;
            }
            // "Accepted" is not "done" on a locked-down build, so the state is read back.
            return wm.isWifiEnabled() == want || wm.getWifiState() == (want
                    ? WifiManager.WIFI_STATE_ENABLING : WifiManager.WIFI_STATE_DISABLING);
        } catch (Throwable t) {
            L.d("tiles: Wi-Fi refused (" + t + ")");
            return false;
        }
    }

    public static Tile bluetooth(Context ctx, int displayId, Runnable onChanged) {
        return new Tile() {
            @Override
            public String label() {
                return "Bluetooth";
            }

            @Override
            public Drawable icon(int color) {
                return TrayIcons.bluetooth(color);
            }

            @Override
            public Boolean state() {
                return bluetoothOn(ctx);
            }

            @Override
            public boolean toggle() {
                Boolean on = bluetoothOn(ctx);
                if (on == null) {
                    // Nothing to invert. Toggling blind would only ever be able to guess "on".
                    L.i("tiles: Bluetooth state unreadable, opening settings");
                    open(ctx, Settings.ACTION_BLUETOOTH_SETTINGS, displayId);
                    return true;
                }
                boolean want = !on;
                if (invokeAdapter(ctx, want ? "enable" : "disable")) {
                    L.i("tiles: Bluetooth toggled directly");
                    return false;
                }
                // This launcher holds neither BLUETOOTH_CONNECT nor BLUETOOTH_ADMIN - the log
                // showed enable() throwing and even ACTION_REQUEST_ENABLE being refused - so the
                // only route left that actually toggles anything is the shell.
                viaRoot(ctx, "Bluetooth", onChanged,
                        () -> open(ctx, Settings.ACTION_BLUETOOTH_SETTINGS, displayId),
                        "svc bluetooth " + (want ? "enable" : "disable"));
                return false;
            }
        };
    }

    /**
     * Runs a privileged command, and falls back to a settings screen if root is not there.
     *
     * <p>Both the attempt and the fallback happen away from the caller: {@code su} is a round trip
     * through Magisk's daemon and, the first time, a dialog someone has to answer.
     */
    private static void viaRoot(Context ctx, String what, Runnable onChanged, Runnable fallback,
            String... commands) {
        final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        // Remembered now: root can take seconds to answer, and by then this panel may be closed
        // and another one open. Closing that one would be someone else's window disappearing.
        final Object token = QuickPanel.token();
        Su.run(ok -> main.post(() -> {
            if (ok) {
                L.i("tiles: " + what + " set via root");
                if (onChanged != null) {
                    onChanged.run();
                }
                return;
            }
            L.i("tiles: " + what + " needs root and there is none, opening settings");
            QuickPanel.dismissIf(token);
            fallback.run();
        }), commands);
    }

    /** Null when the platform will not say - which it will not without BLUETOOTH_CONNECT. */
    static Boolean bluetoothOn(Context ctx) {
        try {
            BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = bm != null ? bm.getAdapter() : null;
            return adapter == null ? null : adapter.isEnabled();
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean invokeAdapter(Context ctx, String method) {
        try {
            BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = bm != null ? bm.getAdapter() : null;
            if (adapter == null) {
                return false;
            }
            return Boolean.TRUE.equals(
                    BluetoothAdapter.class.getMethod(method).invoke(adapter));
        } catch (Throwable t) {
            L.d("tiles: direct Bluetooth toggle refused (" + t + ")");
            return false;
        }
    }

    /** The one tile that needs no permission and so always works. */
    public static Tile torch(Context ctx) {
        return new Tile() {
            @Override
            public String label() {
                return "Torch";
            }

            @Override
            public Drawable icon(int color) {
                return TrayIcons.torch(color);
            }

            @Override
            public Boolean state() {
                Torch.prime(ctx);
                return Torch.isOn();
            }

            @Override
            public boolean toggle() {
                Torch.set(ctx, !Boolean.TRUE.equals(Torch.isOn()));
                return false;
            }
        };
    }

    public static Tile rotation(Context ctx, int displayId, Runnable onChanged) {
        return new Tile() {
            @Override
            public String label() {
                return "Auto-rotate";
            }

            @Override
            public Drawable icon(int color) {
                return TrayIcons.rotate(color);
            }

            @Override
            public Boolean state() {
                return readSystem(ctx, Settings.System.ACCELEROMETER_ROTATION, -1) == 1;
            }

            @Override
            public boolean toggle() {
                int want = Boolean.TRUE.equals(state()) ? 0 : 1;
                if (writeSystem(ctx, Settings.System.ACCELEROMETER_ROTATION, want)) {
                    L.i("tiles: rotation set directly");
                    return false;
                }
                viaRoot(ctx, "Auto-rotate", onChanged,
                        () -> open(ctx, Settings.ACTION_DISPLAY_SETTINGS, displayId),
                        "settings put system accelerometer_rotation " + want);
                return false;
            }
        };
    }

    /**
     * Aeroplane mode.
     *
     * <p>Readable by anyone, writable by almost nobody: the setting is in {@code Settings.Global},
     * which needs {@code WRITE_SECURE_SETTINGS}. Writing it is also not enough on modern Android -
     * the radios follow {@code cmd connectivity airplane-mode}, so both are sent.
     */
    public static Tile flightMode(Context ctx, int displayId, Runnable onChanged) {
        return new Tile() {
            @Override
            public String label() {
                return "Flight mode";
            }

            @Override
            public Drawable icon(int color) {
                return TrayIcons.flight(color);
            }

            @Override
            public Boolean state() {
                return readGlobal(ctx, Settings.Global.AIRPLANE_MODE_ON, 0) == 1;
            }

            @Override
            public boolean toggle() {
                boolean want = !Boolean.TRUE.equals(state());
                viaRoot(ctx, "Flight mode", onChanged,
                        () -> open(ctx, Settings.ACTION_AIRPLANE_MODE_SETTINGS, displayId),
                        // This order matters: the connectivity command is what actually moves
                        // the radios, and it can read as a no-op if the setting already says
                        // what it is about to set.
                        "cmd connectivity airplane-mode " + (want ? "enable" : "disable"),
                        "settings put global airplane_mode_on " + (want ? 1 : 0));
                return false;
            }
        };
    }

    // --- shared plumbing -------------------------------------------------

    static int readSystem(Context ctx, String key, int def) {
        try {
            return Settings.System.getInt(ctx.getContentResolver(), key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    static boolean writeSystem(Context ctx, String key, int value) {
        try {
            if (!Settings.System.canWrite(ctx)) {
                return false;
            }
        } catch (Throwable t) {
            return false;
        }
        return putSystem(ctx, key, value);
    }

    /**
     * The write without the permission check, for callers that have already made it.
     *
     * <p>A slider asks this once per pixel of travel, and {@code canWrite} is a package-manager
     * round trip - not something to do on the UI thread sixty times a second.
     */
    static boolean putSystem(Context ctx, String key, int value) {
        try {
            return Settings.System.putInt(ctx.getContentResolver(), key, value);
        } catch (Throwable t) {
            L.d("tiles: could not write " + key + " (" + t + ")");
            return false;
        }
    }

    private static int readGlobal(Context ctx, String key, int def) {
        try {
            return Settings.Global.getInt(ctx.getContentResolver(), key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    static void open(Context ctx, String action, int displayId) {
        try {
            Intent intent = new Intent(action);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent, launchOptions(displayId));
        } catch (Throwable t) {
            L.e("tiles: could not open " + action, t);
            try {
                Toast.makeText(ctx, "That settings screen is not available here",
                        Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) {
                // A missing toast is not worth a second failure.
            }
        }
    }

    /** Whatever opens has to land on the display the taskbar is on. */
    static Bundle launchOptions(int displayId) {
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
     * The torch, and the only reliable way to know whether it is lit.
     *
     * <p>There is no "is the torch on" getter. What there is, is a callback the camera service
     * sends on registration with the current state and again on every change - so the panel
     * starts watching as soon as it is built, long before anything can be tapped. Doing that only
     * on the first tap, as this did, meant a torch lit from anywhere else read as off and the
     * first press turned it on again, which is to say did nothing.
     */
    static final class Torch {

        private static Boolean sOn;
        private static String sCameraId;
        private static boolean sPrimed;

        private Torch() {
        }

        static Boolean isOn() {
            return sOn;
        }

        /**
         * Resolves the flash camera and starts listening. Safe to call repeatedly; does its work
         * once.
         */
        static void prime(Context ctx) {
            if (sPrimed) {
                return;
            }
            sPrimed = true;
            try {
                CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
                if (cm == null) {
                    return;
                }
                // Resolved first: the callback filters on this id, and a front-facing flash
                // reporting before it was known would be recorded as the rear one's state.
                sCameraId = findFlashCamera(cm);
                if (sCameraId == null) {
                    L.d("tiles: no camera with a flash, the torch tile will do nothing");
                    return;
                }
                cm.registerTorchCallback(new CameraManager.TorchCallback() {
                    @Override
                    public void onTorchModeChanged(String cameraId, boolean enabled) {
                        if (cameraId.equals(sCameraId)) {
                            sOn = enabled;
                        }
                    }

                    @Override
                    public void onTorchModeUnavailable(String cameraId) {
                        if (cameraId.equals(sCameraId)) {
                            sOn = null;
                        }
                    }
                }, null);
            } catch (Throwable t) {
                L.d("tiles: torch unavailable (" + t + ")");
            }
        }

        static void set(Context ctx, boolean on) {
            prime(ctx);
            if (sCameraId == null) {
                return;
            }
            try {
                CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
                if (cm == null) {
                    return;
                }
                cm.setTorchMode(sCameraId, on);
                // Optimistic, so the tile lights immediately; the callback confirms or corrects.
                sOn = on;
            } catch (Throwable t) {
                L.d("tiles: torch refused (" + t + ")");
            }
        }

        private static String findFlashCamera(CameraManager cm) throws Exception {
            for (String id : cm.getCameraIdList()) {
                Boolean flash = cm.getCameraCharacteristics(id)
                        .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (Boolean.TRUE.equals(flash)) {
                    return id;
                }
            }
            return null;
        }
    }
}
