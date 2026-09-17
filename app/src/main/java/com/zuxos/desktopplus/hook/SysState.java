package com.zuxos.desktopplus.hook;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Thermals;

import java.net.Inet4Address;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * What the tray shows: the active network, the battery, and the clock's ticking.
 *
 * <p>One instance per process, started the first time a tray is built and never stopped - the
 * launcher process outlives every taskbar it creates, and re-registering callbacks each time a
 * display reconnects would leak them.
 *
 * <p>Everything is read through the launcher's own permissions. A system launcher has
 * {@code ACCESS_NETWORK_STATE}; where it does not, the reads fail softly and the tray shows what
 * it can rather than disappearing.
 */
public final class SysState {

    /** How often the thermal zones are re-read while a tray is visible. */
    private static final long THERMAL_POLL_SECONDS = 5;

    /** How the tray should draw the active connection. */
    public static final int NET_NONE = 0;
    public static final int NET_WIFI = 1;
    public static final int NET_ETHERNET = 2;
    public static final int NET_CELLULAR = 3;

    private static SysState sInstance;

    private final Context mCtx;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final Set<Runnable> mListeners = new LinkedHashSet<>();

    private int mNetType = NET_NONE;
    private int mNetLevel = 0;
    private boolean mValidated;
    private String mNetDetail = "";
    private int mBatteryPercent = -1;
    private boolean mCharging;
    private volatile float mBatteryTemp = Float.NaN;
    private volatile float mCpuTemp = Float.NaN;
    private volatile float mGpuTemp = Float.NaN;
    private ScheduledExecutorService mThermalPoll;
    private ScheduledFuture<?> mThermalTask;

    private SysState(Context ctx) {
        mCtx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
    }

    public static synchronized SysState get(Context ctx) {
        if (sInstance == null) {
            sInstance = new SysState(ctx);
            sInstance.start();
        }
        return sInstance;
    }

    /** Called on the main thread whenever anything below changes. */
    public void addListener(Runnable listener) {
        boolean wasIdle = mListeners.isEmpty();
        mListeners.add(listener);
        if (wasIdle) {
            startThermalPolling();
        }
    }

    public void removeListener(Runnable listener) {
        mListeners.remove(listener);
        if (mListeners.isEmpty()) {
            stopThermalPolling();
        }
    }

    public int netType() {
        return mNetType;
    }

    /** 0..4, meaningful for Wi-Fi and cellular. */
    public int netLevel() {
        return mNetLevel;
    }

    /** True when the network actually reaches the internet, not just an access point. */
    public boolean netValidated() {
        return mValidated;
    }

    /** A second line for the network: an SSID or address where readable, else a status. */
    public String netDetail() {
        return mNetDetail;
    }

    public String netLabel() {
        switch (mNetType) {
            case NET_WIFI:
                return "Wi-Fi";
            case NET_ETHERNET:
                return "Ethernet";
            case NET_CELLULAR:
                return "Mobile data";
            default:
                return "Not connected";
        }
    }

    /** -1 when the battery has not reported yet. */
    public int batteryPercent() {
        return mBatteryPercent;
    }

    public boolean charging() {
        return mCharging;
    }

    /** Degrees Celsius, or {@link Float#NaN} where the sensor is not readable. */
    public float batteryTemp() {
        return mBatteryTemp;
    }

    public float cpuTemp() {
        return mCpuTemp;
    }

    public float gpuTemp() {
        return mGpuTemp;
    }

    public boolean wifiEnabled() {
        try {
            WifiManager wm = (WifiManager) mCtx.getSystemService(Context.WIFI_SERVICE);
            return wm != null && wm.isWifiEnabled();
        } catch (Throwable t) {
            return false;
        }
    }

    // --- wiring ----------------------------------------------------------

    private void start() {
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) mCtx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(Network network) {
                        refreshNetwork();
                    }

                    @Override
                    public void onLost(Network network) {
                        refreshNetwork();
                    }

                    @Override
                    public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                        refreshNetwork();
                    }

                    @Override
                    public void onLinkPropertiesChanged(Network network, LinkProperties props) {
                        refreshNetwork();
                    }
                }, mMain);
            }
        } catch (Throwable t) {
            L.d("tray: no network callback (" + t + ")");
        }

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(Intent.ACTION_TIME_TICK);
        filter.addAction(Intent.ACTION_TIME_CHANGED);
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null) {
                    return;
                }
                if (Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) {
                    readBattery(intent);
                } else if (WifiManager.WIFI_STATE_CHANGED_ACTION.equals(intent.getAction())) {
                    refreshNetwork();
                    return;
                }
                // A time tick carries nothing, but it is the minute boundary the clock wants.
                notifyListeners();
            }
        };
        try {
            // These are all protected system broadcasts, so the receiver never needs to be
            // reachable by other apps - and from Android 13 that has to be said out loud.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                mCtx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                mCtx.registerReceiver(receiver, filter);
            }
        } catch (Throwable t) {
            L.d("tray: no battery/clock broadcasts (" + t + ")");
        }

        refreshNetwork();
    }

    private void readBattery(Intent intent) {
        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        mBatteryPercent = level >= 0 && scale > 0 ? Math.round(level * 100f / scale) : -1;
        int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        mCharging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL
                || intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        // Tenths of a degree, and -1 when the battery has no thermistor to ask.
        int tenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
        mBatteryTemp = tenths == Integer.MIN_VALUE || tenths <= 0 ? Float.NaN : tenths / 10f;
    }

    /**
     * Polls the thermal zones while something is watching.
     *
     * <p>Reading them means touching the filesystem, so it happens on a background thread and
     * only while a tray is actually on screen - an idle launcher polls nothing.
     */
    private void startThermalPolling() {
        if (mThermalTask != null) {
            return;
        }
        if (mThermalPoll == null) {
            mThermalPoll = Executors.newSingleThreadScheduledExecutor(
                    r -> new Thread(r, "zux-desktop-plus-thermals"));
        }
        // Finding the zones means listing a directory, so the question of whether there is
        // anything to poll is itself answered off the main thread - and where the answer is no,
        // no repeating task is ever created.
        mThermalPoll.execute(() -> {
            if (!Thermals.available()) {
                return;
            }
            mMain.post(this::scheduleThermalPoll);
        });
    }

    private void scheduleThermalPoll() {
        if (mThermalTask != null || mListeners.isEmpty() || mThermalPoll == null) {
            return;
        }
        mThermalTask = mThermalPoll.scheduleWithFixedDelay(() -> {
            try {
                Thermals.Reading reading = Thermals.read();
                if (sameTemps(reading)) {
                    return;
                }
                mCpuTemp = reading.cpu;
                mGpuTemp = reading.gpu;
                notifyListeners();
            } catch (Throwable t) {
                L.d("tray: thermal read failed (" + t + ")");
            }
        }, 0, THERMAL_POLL_SECONDS, TimeUnit.SECONDS);
    }

    private boolean sameTemps(Thermals.Reading reading) {
        return same(reading.cpu, mCpuTemp) && same(reading.gpu, mGpuTemp);
    }

    /** NaN never equals itself, so "both unknown" has to be spelled out. */
    private static boolean same(float a, float b) {
        return Float.isNaN(a) ? Float.isNaN(b) : a == b;
    }

    private void stopThermalPolling() {
        if (mThermalTask != null) {
            mThermalTask.cancel(false);
            mThermalTask = null;
        }
    }

    private void refreshNetwork() {
        int type = NET_NONE;
        int level = 0;
        boolean validated = false;
        String detail = "";
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) mCtx.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network network = cm != null ? cm.getActiveNetwork() : null;
            NetworkCapabilities caps = network != null ? cm.getNetworkCapabilities(network) : null;
            if (caps != null) {
                validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                    type = NET_ETHERNET;
                    level = 4;
                } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    type = NET_WIFI;
                    level = wifiLevel(caps);
                } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                    type = NET_CELLULAR;
                    level = 3;
                }
                detail = describe(cm, network, type, validated);
            }
        } catch (Throwable t) {
            L.d("tray: network state unreadable (" + t + ")");
        }
        mNetType = type;
        mNetLevel = level;
        mValidated = validated;
        mNetDetail = detail;
        notifyListeners();
    }

    /**
     * Signal strength as 0..4.
     *
     * <p>Taken from the capabilities where the platform reports it, and from the RSSI otherwise.
     * Both routes need only {@code ACCESS_NETWORK_STATE}, unlike reading the network's name.
     */
    private int wifiLevel(NetworkCapabilities caps) {
        try {
            int rssi = caps.getSignalStrength();
            if (rssi != NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED) {
                return WifiManager.calculateSignalLevel(rssi, 5);
            }
        } catch (Throwable ignored) {
            // Fall through to the WifiManager.
        }
        try {
            WifiManager wm = (WifiManager) mCtx.getSystemService(Context.WIFI_SERVICE);
            WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
            if (info != null) {
                return WifiManager.calculateSignalLevel(info.getRssi(), 5);
            }
        } catch (Throwable ignored) {
            // Nothing readable; a full bar is a better guess than none.
        }
        return 4;
    }

    /**
     * The second line.
     *
     * <p>The network's name needs location permission the launcher will not have, so it is used
     * when it is readable and the IPv4 address stands in when it is not - which is the more
     * useful of the two on a wired desktop anyway.
     */
    private String describe(ConnectivityManager cm, Network network, int type, boolean validated) {
        if (type == NET_NONE) {
            return "";
        }
        if (!validated) {
            return "No internet";
        }
        if (type == NET_WIFI) {
            String ssid = readSsid();
            if (ssid != null) {
                return ssid;
            }
        }
        String address = readAddress(cm, network);
        return address != null ? address : "Connected";
    }

    private String readSsid() {
        try {
            WifiManager wm = (WifiManager) mCtx.getSystemService(Context.WIFI_SERVICE);
            WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
            String ssid = info != null ? info.getSSID() : null;
            if (ssid == null) {
                return null;
            }
            ssid = ssid.replace("\"", "").trim();
            // What the platform hands back when it will not tell you.
            if (ssid.isEmpty() || ssid.equalsIgnoreCase("<unknown ssid>")
                    || ssid.equals("0x")) {
                return null;
            }
            return ssid;
        } catch (Throwable t) {
            return null;
        }
    }

    private String readAddress(ConnectivityManager cm, Network network) {
        try {
            LinkProperties props = cm != null && network != null
                    ? cm.getLinkProperties(network) : null;
            if (props == null) {
                return null;
            }
            for (LinkAddress la : props.getLinkAddresses()) {
                if (la.getAddress() instanceof Inet4Address) {
                    return la.getAddress().getHostAddress();
                }
            }
        } catch (Throwable ignored) {
            // No address to show.
        }
        return null;
    }

    private void notifyListeners() {
        mMain.post(() -> {
            for (Runnable listener : new LinkedHashSet<>(mListeners)) {
                try {
                    listener.run();
                } catch (Throwable t) {
                    L.d("tray listener failed: " + t);
                }
            }
        });
    }
}
