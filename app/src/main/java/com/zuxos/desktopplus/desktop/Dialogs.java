package com.zuxos.desktopplus.desktop;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.pm.ShortcutInfo;
import android.os.UserHandle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;

import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Ui;
import com.zuxos.desktopplus.model.AppsRepo;
import com.zuxos.desktopplus.model.Item;

import java.util.ArrayList;
import java.util.List;

/** Dialogs used from the desktop, all built in code (no module resources in the host process). */
public final class Dialogs {

    public interface TextCallback {
        void onText(String text);
    }

    public interface AppCallback {
        void onApp(AppsRepo.AppEntry entry);
    }

    public interface ShortcutCallback {
        void onShortcut(ShortcutInfo info);
    }

    public interface SpanCallback {
        void onSpans(int spanX, int spanY);
    }

    public interface IntCallback {
        void onValue(int value);
    }

    private Dialogs() {
    }

    public static void prompt(Activity a, String title, String initial, TextCallback cb) {
        EditText input = new EditText(a);
        input.setSingleLine(true);
        input.setText(initial != null ? initial : "");
        input.setSelection(input.getText().length());
        int pad = Ui.dp(a, 20);
        LinearLayout wrap = new LinearLayout(a);
        wrap.setPadding(pad, pad / 2, pad, 0);
        wrap.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        show(new AlertDialog.Builder(a)
                .setTitle(title)
                .setView(wrap)
                .setPositiveButton(android.R.string.ok,
                        (d, w) -> cb.onText(input.getText().toString().trim()))
                .setNegativeButton(android.R.string.cancel, null)
                .create());
    }

    public static void confirm(Activity a, String title, String message, Runnable onYes) {
        show(new AlertDialog.Builder(a)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, (d, w) -> onYes.run())
                .setNegativeButton(android.R.string.cancel, null)
                .create());
    }

    public static void message(Activity a, String title, String message) {
        show(new AlertDialog.Builder(a)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .create());
    }

    /** App picker with a search field - used by "Add app" and by drawer folder editing. */
    public static void pickApp(Activity a, AppsRepo repo, String title, AppCallback cb) {
        final List<AppsRepo.AppEntry> all = new ArrayList<>(repo.apps());
        final List<AppsRepo.AppEntry> shown = new ArrayList<>(all);

        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        EditText search = new EditText(a);
        search.setHint("Search");
        search.setSingleLine(true);
        int pad = Ui.dp(a, 16);
        search.setPadding(pad, pad / 2, pad, pad / 2);
        root.addView(search, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        ListView list = new ListView(a);
        AppAdapter adapter = new AppAdapter(a, repo, shown);
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(a, 420)));

        AlertDialog dialog = new AlertDialog.Builder(a)
                .setTitle(title)
                .setView(root)
                .setNegativeButton(android.R.string.cancel, null)
                .create();

        search.addTextChangedListener(new SimpleWatcher(text -> {
            shown.clear();
            String q = text.toLowerCase();
            for (AppsRepo.AppEntry e : all) {
                if (q.isEmpty() || e.label.toLowerCase().contains(q)) {
                    shown.add(e);
                }
            }
            adapter.notifyDataSetChanged();
        }));
        list.setOnItemClickListener((parent, view, position, id) -> {
            dialog.dismiss();
            cb.onApp(shown.get(position));
        });
        show(dialog);
    }

    /** Deep shortcuts published by an app, so users can pin "New message", "New tab", ... */
    public static void pickShortcut(Activity a, AppsRepo repo, String pkg, UserHandle user,
            ShortcutCallback cb) {
        final List<ShortcutInfo> shortcuts = repo.shortcutsFor(pkg, user);
        if (shortcuts.isEmpty()) {
            message(a, "No shortcuts",
                    "This app publishes no shortcuts, or this launcher is not the default home app.");
            return;
        }
        final List<String> labels = new ArrayList<>();
        for (ShortcutInfo s : shortcuts) {
            CharSequence l = s.getShortLabel() != null ? s.getShortLabel() : s.getLongLabel();
            labels.add(l != null ? l.toString() : s.getId());
        }
        show(new AlertDialog.Builder(a)
                .setTitle("Add shortcut")
                .setItems(labels.toArray(new String[0]),
                        (d, which) -> cb.onShortcut(shortcuts.get(which)))
                .setNegativeButton(android.R.string.cancel, null)
                .create());
    }

    /** Widget resize: plain +/- steppers, which beat drag handles on a mouse-driven desktop. */
    public static void resize(Activity a, Item item, int maxCols, int maxRows, SpanCallback cb) {
        final int[] spans = new int[]{item.spanX, item.spanY};
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(a, 20);
        root.setPadding(pad, pad, pad, 0);

        TextView summary = new TextView(a);
        summary.setText(spans[0] + " x " + spans[1] + " cells");
        root.addView(summary);

        root.addView(stepper(a, "Width", spans[0], maxCols, value -> {
            spans[0] = value;
            summary.setText(spans[0] + " x " + spans[1] + " cells");
        }));
        root.addView(stepper(a, "Height", spans[1], maxRows, value -> {
            spans[1] = value;
            summary.setText(spans[0] + " x " + spans[1] + " cells");
        }));

        show(new AlertDialog.Builder(a)
                .setTitle("Resize widget")
                .setView(root)
                .setPositiveButton("Apply", (d, w) -> cb.onSpans(spans[0], spans[1]))
                .setNegativeButton(android.R.string.cancel, null)
                .create());
    }

    private static View stepper(Context ctx, String label, int initial, int max, IntCallback cb) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView tv = new TextView(ctx);
        tv.setText(label);
        row.addView(tv, new LinearLayout.LayoutParams(Ui.dp(ctx, 72),
                LinearLayout.LayoutParams.WRAP_CONTENT));
        SeekBar bar = new SeekBar(ctx);
        bar.setMax(Math.max(1, max - 1));
        bar.setProgress(Math.max(0, initial - 1));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                cb.onValue(progress + 1);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        row.addView(bar, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    /** Single-value slider dialog, used for cell and icon size. */
    public static void slider(Activity a, String title, String unit, int value, int min, int max,
            IntCallback cb) {
        final int[] current = new int[]{value};
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(a, 20);
        root.setPadding(pad, pad, pad, 0);
        TextView tv = new TextView(a);
        tv.setText(value + " " + unit);
        root.addView(tv);
        SeekBar bar = new SeekBar(a);
        bar.setMax(max - min);
        bar.setProgress(value - min);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                current[0] = min + progress;
                tv.setText(current[0] + " " + unit);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        root.addView(bar);
        show(new AlertDialog.Builder(a)
                .setTitle(title)
                .setView(root)
                .setPositiveButton("Apply", (d, w) -> cb.onValue(current[0]))
                .setNegativeButton(android.R.string.cancel, null)
                .create());
    }

    private static void show(AlertDialog dialog) {
        try {
            dialog.show();
        } catch (Throwable t) {
            L.e("could not show dialog", t);
        }
    }

    /** Rows for {@link #pickApp}. */
    private static final class AppAdapter extends BaseAdapter {
        private final Context mCtx;
        private final AppsRepo mRepo;
        private final List<AppsRepo.AppEntry> mItems;

        AppAdapter(Context ctx, AppsRepo repo, List<AppsRepo.AppEntry> items) {
            mCtx = ctx;
            mRepo = repo;
            mItems = items;
        }

        @Override
        public int getCount() {
            return mItems.size();
        }

        @Override
        public Object getItem(int position) {
            return mItems.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row = new LinearLayout(mCtx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int pad = Ui.dp(mCtx, 10);
            row.setPadding(pad, pad, pad, pad);
            AppsRepo.AppEntry e = mItems.get(position);
            ImageView icon = new ImageView(mCtx);
            icon.setImageDrawable(mRepo.iconFor(e));
            int size = Ui.dp(mCtx, 36);
            row.addView(icon, new LinearLayout.LayoutParams(size, size));
            TextView tv = new TextView(mCtx);
            tv.setText(e.label);
            tv.setTextSize(16);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.leftMargin = Ui.dp(mCtx, 12);
            row.addView(tv, lp);
            return row;
        }
    }

    /** {@code TextWatcher} without the two callbacks nobody uses. */
    public static final class SimpleWatcher implements android.text.TextWatcher {
        private final TextCallback mCb;

        public SimpleWatcher(TextCallback cb) {
            mCb = cb;
        }

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
        }

        @Override
        public void afterTextChanged(android.text.Editable s) {
            mCb.onText(s.toString());
        }
    }
}
