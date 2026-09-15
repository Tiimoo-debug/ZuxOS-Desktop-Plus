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
import com.zuxos.desktopplus.core.Ui;

/** Settings for the module, plus a short explanation of what to expect on the device. */
public class MainActivity extends Activity {

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
        addSwitch("Show the Apps button", "Bottom-left button that opens the app drawer",
                Const.KEY_DRAWER_BUTTON, true);
        addSlider("Grid cell size", "dp", Const.KEY_CELL_SIZE, 104, 72, 180);
        addSlider("Icon size", "dp", Const.KEY_ICON_SIZE, 52, 32, 96);

        addHeader("App drawer");
        addSpinner("Default order", new String[]{
                        "Alphabetical",
                        "My own order",
                        "Alphabetical (recently used first is not available yet)"},
                Const.KEY_DRAWER_SORT, Const.SORT_CUSTOM);

        addHeader("Stock launcher unlocking (optional)");
        addSwitch("Try to unlock the stock launcher",
                "Flips the launcher's own \"editing disabled\" flags where we can name them",
                Const.KEY_UNLOCK_STOCK, true);
        addSwitch("Aggressive unlocking",
                "Also guesses by method name. Turn off if the launcher misbehaves",
                Const.KEY_UNLOCK_AGGRESSIVE, false);

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
                mPrefs.edit().putBoolean(key, checked).apply());
        mRoot.addView(sw, rowParams());
        if (summary != null) {
            addSummary(summary);
        }
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
                mPrefs.edit().putInt(key, position).apply();
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
