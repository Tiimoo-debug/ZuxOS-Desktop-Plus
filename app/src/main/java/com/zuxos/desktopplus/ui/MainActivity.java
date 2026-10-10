package com.zuxos.desktopplus.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import com.zuxos.desktopplus.core.Const;
import com.zuxos.desktopplus.core.ModuleStatus;
import com.zuxos.desktopplus.core.Prefs;
import com.zuxos.desktopplus.core.Tone;
import com.zuxos.desktopplus.core.Ui;
import com.zuxos.desktopplus.core.theme.Theme;
import com.zuxos.desktopplus.notify.TestNotifications;

/** Settings for the module, plus a short explanation of what to expect on the device. */
public class MainActivity extends Activity {

    private static final int REQUEST_NOTIFY = 1;

    private SharedPreferences mPrefs;
    private LinearLayout mRoot;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mPrefs = Prefs.get(this);

        ScrollView scroll = new ScrollView(this);
        mRoot = new LinearLayout(this);
        mRoot.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 20);
        mRoot.setPadding(pad, pad, pad, pad);
        scroll.addView(mRoot, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        addStatus();

        addHeader("General");
        addSwitch("Module enabled", "Turn the whole desktop surface off without uninstalling",
                Const.KEY_ENABLED, true);
        addSpinner("Which desktop mode", new String[]{
                        "External display only (recommended)",
                        "Tablet screen only",
                        "Both"},
                Const.KEY_DISPLAY_MODE, Const.DISPLAY_EXTERNAL);
        addSummary("ZUI's own home on the tablet is never covered - it keeps its icons, folders "
                + "and Recents, and gets the taskbar only.");
        addSpinner("Stock home content", new String[]{
                        "Leave it alone (overlay on top)",
                        "Hide the stock icon grid (recommended)",
                        "Hide everything the stock home draws"},
                Const.KEY_TAKEOVER, Const.TAKEOVER_GRID);
        addSwitch("Attach to any activity",
                "Only if the desktop home is not detected automatically",
                Const.KEY_ATTACH_ANY, false);
        addText("Extra launcher packages",
                "Comma separated, if your ZuxOS build uses a package the module does not know",
                Const.KEY_EXTRA_TARGETS);

        addHeader("Desktop");
        addSwitch("Widgets", "Add widgets through the desktop right-click menu",
                Const.KEY_WIDGETS_ENABLED, true);
        addSwitch("Folders", "Drop one icon on another to make a folder",
                Const.KEY_FOLDERS_ENABLED, true);
        addSwitch("Show icon labels", null, Const.KEY_SHOW_LABELS, true);
        addSwitch("Shadow behind labels", "Keeps labels readable on light wallpapers",
                Const.KEY_LABEL_SHADOW, true);
        addSwitch("Show the Apps button",
                "Off by default - the stock taskbar covers it. The drawer still opens from the "
                        + "desktop menu or the All-apps key",
                Const.KEY_DRAWER_BUTTON, false);
        addSwitch("Desktop pages", "More than one page of icons, with arrows to move between them",
                Const.KEY_PAGES, true);
        addSwitch("Page dots",
                "Dots under the desktop for its pages. The arrows at the sides already show "
                        + "there is another page",
                Const.KEY_PAGE_DOTS, false);
        addSwitch("Catch pinned shortcuts",
                "A web page or file pinned from any app also lands on the desktop",
                Const.KEY_CATCH_PINS, true);
        addSwitch("Glass style", "Translucent panels, with real blur where Android allows it",
                Const.KEY_GLASS, true);
        addSwitch("Animations", "Sliding, fading and page transitions",
                Const.KEY_ANIMATIONS, true);
        addSlider("Grid cell size", "dp", Const.KEY_CELL_SIZE, 104, 72, 180);
        addSlider("Icon size", "dp", Const.KEY_ICON_SIZE, 52, 32, 96);

        addHeader("Customize");
        addSpinner("Theme", new String[]{
                        "Glass",
                        "Retro (monitor)"},
                Const.KEY_THEME, Theme.GLASS_ID);
        addSummary("Retro is Windows 98 on the external screen: grey bevelled boxes, a pixel font, "
                + "no blur and no live glass, which also saves the GPU there. The tablet keeps "
                + "glass. The launcher restarts to apply it.");
        addSpinner("Taskbar position (monitor)", new String[]{
                        "Bottom",
                        "Top"},
                Const.KEY_TASKBAR_EDGE, Const.EDGE_BOTTOM);
        addSummary("The launcher restarts to apply it. The tablet's taskbar never moves.");
        addSwitch("Hide the monitor's status bar",
                "ZUI's bar at the top of the external screen only shows icons - it cannot be "
                        + "pulled down. The tray and its panels do its job, and apps get its "
                        + "room. Needs System UI ticked in LSPosed; takes effect the next time "
                        + "the monitor's desktop starts.",
                Const.KEY_HIDE_MONITOR_STATUS_BAR, true);

        addHeader("App drawer");
        addSwitch("Use the stock taskbar drawer too",
                "Applies your folders, order and hidden apps to the launcher's own app drawer",
                Const.KEY_NATIVE_DRAWER, true);
        addSpinner("Default order", new String[]{
                        "Alphabetical",
                        "My own order",
                        "Alphabetical (recently used first is not available yet)"},
                Const.KEY_DRAWER_SORT, Const.SORT_CUSTOM);

        addHeader("Taskbar");
        addSwitch("Status tray in the taskbar",
                "Network, battery and a clock next to the navigation buttons, with Wi-Fi and "
                        + "Bluetooth switches behind them",
                Const.KEY_TASKBAR_TRAY, true);
        addSwitch("Show temperatures",
                "CPU, GPU and battery temperature in the tray, where the kernel lets the "
                        + "launcher read them",
                Const.KEY_TASKBAR_TEMPS, true);
        addSwitch("Taskbar menu",
                "Hold or right-click empty taskbar space for Task manager and settings",
                Const.KEY_TASKBAR_MENU, true);
        addSpinner("Taskbar text and icons", new String[]{
                        "Follow the background",
                        "Always black",
                        "Always white"},
                Const.KEY_TASKBAR_TEXT_MODE, Tone.MODE_AUTO);
        addSwitch("Glass taskbar",
                "Experimental. Replaces the taskbar's own bar with a translucent one. Whether it "
                        + "works depends on how your firmware paints that bar, so it starts off",
                Const.KEY_TASKBAR_GLASS, false);
        addSwitch("Only open apps in the taskbar",
                "The taskbar shows what is open on its screen, plus your pins - not the "
                        + "launcher's hotseat and recommendations",
                Const.KEY_TASKBAR_RUNNING_ONLY, false);
        addSwitch("Menu on a taskbar icon",
                "Hold an app in the taskbar for open, close, app info and the app's own "
                        + "shortcuts",
                Const.KEY_TASKBAR_APP_MENU, true);
        addSwitch("Keep the launcher's recents off the taskbar",
                "With \"Only open apps\" on, the launcher's own recent and recommended apps are "
                        + "never added to the taskbar - not even for a moment when an app opens "
                        + "or closes",
                Const.KEY_HIDE_RECENTS_FLASH, true);
        addSwitch("Navigation keys act on their own screen",
                "Back, home and recents on the monitor's taskbar act on the monitor - not on "
                        + "the tablet, which is where the launcher sends them on its own",
                Const.KEY_NAV_OWN_SCREEN, true);
        addSwitch("Recents on the screen you pressed",
                "The recents button opens recents on its own screen - tablet or monitor - full "
                        + "screen and in front of every app, on the first press",
                Const.KEY_RECENTS_ROUTE, true);
        addSwitch("Never kill apps on the monitor",
                "Apps open on the external screen are only closed when you close them. They go "
                        + "on ZUI's own never-kill lists, and root keeps battery limits off them; "
                        + "for the rest, also tick System Framework for this module in LSPosed "
                        + "and reboot once",
                Const.KEY_KEEP_ALIVE, true);
        addSwitch("Drawer button on the left",
                "Moves the launcher's own all-apps button to the left of the taskbar, beside the "
                        + "navigation keys, instead of leaving it in the middle of the icons",
                Const.KEY_START_LEFT, true);
        addSwitch("Android logo on the drawer button",
                "Shows the green Android head on the launcher's all-apps button, like a start "
                        + "button, instead of its own icon",
                Const.KEY_START_ROBOT, true);
        addSwitch("Mark the apps that are open",
                "A line under every taskbar icon whose app is running - including shortcuts and "
                        + "folders, which otherwise look the same open or closed",
                Const.KEY_RUNNING_MARKS, true);
        addSwitch("Drag apps out of the stock drawer",
                "Hold an app in the launcher's own drawer to drag it onto the desktop, or onto "
                        + "the taskbar to pin it there. Pins are this module's own and go away "
                        + "with it; the launcher's own hotseat is never written to",
                Const.KEY_DRAWER_DRAG, true);
        addSwitch("Open apps on the screen you tapped",
                "The launcher starts its own icons without naming a screen, so an app tapped on "
                        + "the external desktop can open on the tablet. This gives those launches "
                        + "the display the tap was on. Off by default - it changes where every app "
                        + "opens",
                Const.KEY_LAUNCH_DISPLAY, false);
        addSwitch("Glass app drawer",
                "Puts the same translucent pane behind the launcher's own app drawer, in whatever "
                        + "tone the drawer already uses so its labels stay readable",
                Const.KEY_DRAWER_GLASS, true);
        addSwitch("Use root",
                "Lets the quick settings switch Bluetooth, aeroplane mode, eye protection and "
                        + "take screenshots, which an ordinary launcher may not. Turn off and "
                        + "those buttons open the matching settings screen instead",
                Const.KEY_USE_ROOT, true);

        addHeader("Stock launcher unlocking (optional)");
        addSwitch("Try to unlock the stock launcher",
                "Flips the launcher's own \"editing disabled\" flags where we can name them",
                Const.KEY_UNLOCK_STOCK, true);
        addSwitch("Aggressive unlocking",
                "Also guesses by method name. Turn off if the launcher misbehaves",
                Const.KEY_UNLOCK_AGGRESSIVE, false);

        addHeader("Notifications");
        addSwitch("Notifications in the quick panel",
                "Shows what is in the shade, with a tap to open and a cross to dismiss",
                Const.KEY_NOTIFICATIONS, true);
        addSwitch("Pop-ups on the monitor",
                "A new notification that would pop up on the tablet shows above the tray on the "
                        + "external screen for a few seconds, with its buttons and a reply box. "
                        + "The launcher restarts to apply it",
                Const.KEY_NOTIFY_POPUPS, true);
        addButton("Grant notification access", () -> {
            try {
                // The launcher may not read notifications and cannot be granted permission to.
                // This module's own listener can, once it is switched on here.
                startActivity(new android.content.Intent(
                        "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Throwable t) {
                android.widget.Toast.makeText(this,
                        "Could not open notification access settings",
                        android.widget.Toast.LENGTH_LONG).show();
            }
        });
        addSummary("Find \"ZuxOS Desktop Plus\" in that list and switch it on. Without it the "
                + "panel simply shows no notifications; nothing else is affected.");
        addButton("Send test notifications", this::sendTestNotifications);
        addSummary("Three, a moment apart: a message with a picture, a button and a reply box; "
                + "a long one with two buttons; a plain one. With the monitor's desktop on, they "
                + "pop up above its tray. Replying updates the first without popping it up again.");

        addHeader("Diagnostics");
        addSwitch("Verbose log", "Writes details to the LSPosed log", Const.KEY_DEBUG, false);
        addSwitch("Dump launcher info on attach",
                "Writes the activity and view tree to the launcher's files dir",
                Const.KEY_PROBE, false);

        addButton("How to use this", this::showHelp);
    }

    // --- building blocks -------------------------------------------------

    private void addStatus() {
        boolean active = ModuleStatus.isActive();
        TextView tv = new TextView(this);
        tv.setText(active
                ? "Module is active"
                : "Module is NOT active - enable it in LSPosed and reboot, then scope it to your "
                        + "home app (com.zui.home / com.zui.launcher).");
        tv.setTextColor(Color.WHITE);
        tv.setBackground(Ui.roundRect(active ? 0xFF2E7D32 : 0xFFB3261E, Ui.dp(this, 14)));
        int p = Ui.dp(this, 16);
        tv.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = rowParams();
        lp.bottomMargin = Ui.dp(this, 12);
        mRoot.addView(tv, lp);
    }

    private void addHeader(String title) {
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextSize(13);
        tv.setAllCaps(true);
        tv.setAlpha(0.7f);
        LinearLayout.LayoutParams lp = rowParams();
        lp.topMargin = Ui.dp(this, 20);
        lp.bottomMargin = Ui.dp(this, 4);
        mRoot.addView(tv, lp);
    }

    @SuppressWarnings("deprecation")
    private void addSwitch(String title, String summary, String key, boolean def) {
        Switch sw = new Switch(this);
        sw.setText(title);
        sw.setTextSize(16);
        sw.setChecked(mPrefs.getBoolean(key, def));
        sw.setPadding(0, Ui.dp(this, 10), 0, summary == null ? Ui.dp(this, 10) : 0);
        sw.setOnCheckedChangeListener((v, checked) ->
                save(key, mPrefs.edit().putBoolean(key, checked)));
        mRoot.addView(sw, rowParams());
        if (summary != null) {
            addSummary(summary);
        }
    }

    /**
     * Saves a change. One the launcher reads only as it starts is written at once and the
     * launcher told, so it restarts with it - nobody has to force-stop it.
     */
    private void save(String key, SharedPreferences.Editor edit) {
        if (!Const.RESTART_KEYS.contains(key)) {
            edit.apply();
            return;
        }
        edit.commit();
        // To every launcher the module is in: each listens for itself.
        sendBroadcast(new android.content.Intent(Const.ACTION_SETTINGS_CHANGED));
    }

    private void addSummary(String summary) {
        TextView tv = new TextView(this);
        tv.setText(summary);
        tv.setTextSize(13);
        tv.setAlpha(0.7f);
        LinearLayout.LayoutParams lp = rowParams();
        lp.bottomMargin = Ui.dp(this, 8);
        mRoot.addView(tv, lp);
    }

    private void addSpinner(String title, String[] options, String key, int def) {
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextSize(16);
        tv.setPadding(0, Ui.dp(this, 10), 0, 0);
        mRoot.addView(tv, rowParams());

        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, options);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        int value = mPrefs.getInt(key, def);
        spinner.setSelection(Math.max(0, Math.min(value, options.length - 1)));
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                // Also called once as the spinner is set up, with what is already saved.
                if (position != mPrefs.getInt(key, def)) {
                    save(key, mPrefs.edit().putInt(key, position));
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        mRoot.addView(spinner, rowParams());
    }

    private void addSlider(String title, String unit, String key, int def, int min, int max) {
        int value = mPrefs.getInt(key, def);
        TextView tv = new TextView(this);
        tv.setText(title + ": " + value + " " + unit);
        tv.setTextSize(16);
        tv.setPadding(0, Ui.dp(this, 10), 0, 0);
        mRoot.addView(tv, rowParams());

        SeekBar bar = new SeekBar(this);
        bar.setMax(max - min);
        bar.setProgress(value - min);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int v = min + progress;
                tv.setText(title + ": " + v + " " + unit);
                mPrefs.edit().putInt(key, v).apply();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        mRoot.addView(bar, rowParams());
    }

    private void addText(String title, String summary, String key) {
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextSize(16);
        tv.setPadding(0, Ui.dp(this, 10), 0, 0);
        mRoot.addView(tv, rowParams());

        EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setHint("com.example.home");
        et.setText(mPrefs.getString(key, ""));
        et.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                mPrefs.edit().putString(key, s.toString()).apply();
            }
        });
        mRoot.addView(et, rowParams());
        if (summary != null) {
            addSummary(summary);
        }
    }

    private void sendTestNotifications() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_NOTIFY);
            return;
        }
        if (!TestNotifications.send(this)) {
            android.widget.Toast.makeText(this,
                    "Notifications are off for Desktop Plus - allow them in its app settings",
                    android.widget.Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
            int[] results) {
        if (requestCode == REQUEST_NOTIFY && results.length > 0
                && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            sendTestNotifications();
        }
    }

    private void addButton(String title, Runnable action) {
        Button b = new Button(this);
        b.setText(title);
        b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = rowParams();
        lp.topMargin = Ui.dp(this, 20);
        lp.gravity = Gravity.START;
        mRoot.addView(b, lp);
    }

    private LinearLayout.LayoutParams rowParams() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private void showHelp() {
        String text = "1. Enable the module in LSPosed and add your home app to its scope "
                + "(com.zui.home or com.zui.launcher - check LSPosed's app list), then reboot.\n\n"
                + "2. Connect the external display and switch to desktop mode.\n\n"
                + "3. On the desktop: right-click (or long-press) empty space for the menu - "
                + "add apps, widgets, shortcuts and folders from there.\n\n"
                + "4. Drag an icon to move it. Drop one icon on another to make a folder. "
                + "Drag to the Remove bar at the top to take it off the desktop.\n\n"
                + "5. The Apps button at the bottom-left opens the drawer: search, drag apps out "
                + "onto the desktop, drag them onto each other to make drawer folders, and use "
                + "the three-dot menu to keep your own order.\n\n"
                + "If the desktop surface does not appear, turn on 'Dump launcher info on attach' "
                + "and check the LSPosed log - it prints the activity and view tree it found.";
        new AlertDialog.Builder(this)
                .setTitle("How to use this")
                .setMessage(text)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }
}
