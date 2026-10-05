package com.zuxos.desktopplus.hook;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.hardware.HardwareBuffer;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.zuxos.desktopplus.core.Glass;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Motion;
import com.zuxos.desktopplus.core.Reflect;
import com.zuxos.desktopplus.core.Ui;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Recents for the monitor, drawn by the module.
 *
 * <p>Neither recents the launcher has can serve the monitor. ZUI's own one there never became
 * visible ({@code openRecentsView - not visible}, every time), and quickstep's fallback is laid out
 * for the tablet's 3040x1904 screen - on the monitor its cards sat high and off to the left, the
 * first one out of frame - can open as a small window it cannot be taken out of, and is a single
 * activity shared with the tablet, so leaving it on the monitor broke recents there.
 *
 * <p>So the monitor gets its own: that screen's apps, newest first, in rows sized to that
 * screen, over the blurred, dimmed desktop. Tap to bring an app forward, X or a swipe up to close
 * it, clear all; tap anywhere else, back, or recents again to leave - nothing underneath is moved
 * by opening it, so leaving puts everything back exactly as it was.
 */
final class TaskOverview {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "zux-desktop-plus-overview");
        t.setDaemon(true);
        return t;
    });

    private static FrameLayout sRoot;
    private static WindowManager sWm;
    private static int sDisplay = -1;
    private static final ArrayDeque<String> RECORD = new ArrayDeque<>();

    private TaskOverview() {
    }

    /** Opens it on the display of {@code anchor}, or closes it if it is already up there. */
    static void toggle(View anchor, int display) {
        if (sRoot != null) {
            close();
            return;
        }
        open(anchor, display);
    }

    static boolean isOpen() {
        return sRoot != null;
    }

    /** One task as shown: what it is and where its picture comes from. */
    private static final class Card {
        int taskId;
        Intent baseIntent;
        String pkg;
        CharSequence label;
        Drawable icon;
        Bitmap thumb;
        View view;
    }

    private static void open(View anchor, int display) {
        final Context ctx = Overlays.windowContext(anchor.getContext());
        final List<Card> cards = tasksOn(ctx, display);
        try {
            FrameLayout root = new FrameLayout(ctx);
            root.setBackgroundColor(0xA60A0A0E);
            root.setOnClickListener(v -> close());
            root.setFocusableInTouchMode(true);
            root.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() == KeyEvent.ACTION_UP
                        && (keyCode == KeyEvent.KEYCODE_BACK
                        || keyCode == KeyEvent.KEYCODE_ESCAPE)) {
                    close();
                    return true;
                }
                return false;
            });

            DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            int screenW = dm.widthPixels;
            int screenH = dm.heightPixels;
            int inset = TaskbarTray.barInset(anchor);

            LinearLayout column = new LinearLayout(ctx);
            column.setOrientation(LinearLayout.VERTICAL);
            int pad = Ui.dp(ctx, 36);
            column.setPadding(pad, Ui.dp(ctx, 28), pad, Ui.dp(ctx, 28));

            // The header: what this is, and the one action on all of it.
            LinearLayout header = new LinearLayout(ctx);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);
            TextView title = new TextView(ctx);
            title.setText(cards.isEmpty() ? "No recent apps" : "Recent apps");
            title.setTextColor(0xFFFFFFFF);
            title.setTextSize(22);
            header.addView(title, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (!cards.isEmpty()) {
                TextView clear = new TextView(ctx);
                clear.setText("Clear all");
                clear.setTextColor(0xFFFFFFFF);
                clear.setTextSize(15);
                int ph = Ui.dp(ctx, 18);
                int pv = Ui.dp(ctx, 9);
                clear.setPadding(ph, pv, ph, pv);
                clear.setBackground(Ui.roundRect(0x33FFFFFF, Ui.dp(ctx, 20)));
                clear.setOnClickListener(v -> clearAll(ctx, cards));
                header.addView(clear);
            }
            column.addView(header);

            // The cards: rows sized to this screen, the newest first.
            int gap = Ui.dp(ctx, 28);
            int cardW = Math.max(Ui.dp(ctx, 220), (int) (screenW * 0.22f));
            int perRow = Math.max(1, (screenW - 2 * pad + gap) / (cardW + gap));
            int thumbH = Math.round(cardW * (float) screenH / screenW);
            LinearLayout rows = new LinearLayout(ctx);
            rows.setOrientation(LinearLayout.VERTICAL);
            LinearLayout row = null;
            for (int i = 0; i < cards.size(); i++) {
                if (i % perRow == 0) {
                    row = new LinearLayout(ctx);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_HORIZONTAL);
                    LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
                    rlp.topMargin = gap;
                    rows.addView(row, rlp);
                }
                Card card = cards.get(i);
                View view = cardView(ctx, card, cards, cardW, thumbH);
                card.view = view;
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(cardW,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                clp.leftMargin = gap / 2;
                clp.rightMargin = gap / 2;
                row.addView(view, clp);
                view.setAlpha(0f);
                view.setScaleX(0.92f);
                view.setScaleY(0.92f);
                view.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(i * 25L)
                        .setDuration(Motion.SPRING_MS).setInterpolator(Motion.SPRING).start();
            }
            ScrollView scroll = new ScrollView(ctx);
            scroll.setVerticalScrollBarEnabled(false);
            scroll.setFillViewport(true);
            // A tap between cards is a tap on the background: it closes.
            rows.setOnClickListener(v -> close());
            scroll.addView(rows, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            column.addView(scroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            root.addView(column, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            WindowManager wm = Overlays.windowManager(ctx);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    Math.max(1, screenH - inset),
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            // Above the taskbar, never over it: its recents button is how this is closed.
            lp.gravity = Gravity.TOP;
            lp.setTitle("ZuxOS Desktop Plus recents");
            Glass.blurBehind(ctx, lp, Glass.BEHIND_BLUR_DP * 2);
            root.setAlpha(0f);
            wm.addView(root, lp);
            root.animate().alpha(1f).setDuration(Motion.SHORT).setInterpolator(Motion.EASE)
                    .start();
            root.requestFocus();
            sRoot = root;
            sWm = wm;
            sDisplay = display;
            loadThumbnails(cards);
            record("opened on display " + display + " (" + cards.size() + " tasks)");
        } catch (Throwable t) {
            L.e("task overview: could not open", t);
        }
    }

    /** One card: icon and name over the app's last picture, with an X; tap, X or swipe up. */
    private static View cardView(Context ctx, Card card, List<Card> all, int width, int thumbH) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);

        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(ctx);
        icon.setImageDrawable(card.icon);
        int iconPx = Ui.dp(ctx, 26);
        header.addView(icon, new LinearLayout.LayoutParams(iconPx, iconPx));
        TextView name = new TextView(ctx);
        name.setText(card.label);
        name.setTextColor(0xFFFFFFFF);
        name.setTextSize(14);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setPadding(Ui.dp(ctx, 8), 0, Ui.dp(ctx, 8), 0);
        header.addView(name, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView x = new TextView(ctx);
        x.setText("✕");
        x.setTextColor(0xFFFFFFFF);
        x.setTextSize(14);
        x.setGravity(Gravity.CENTER);
        x.setBackground(Ui.roundRect(0x33FFFFFF, Ui.dp(ctx, 14)));
        int xPx = Ui.dp(ctx, 28);
        x.setOnClickListener(v -> remove(ctx, card, all));
        header.addView(x, new LinearLayout.LayoutParams(xPx, xPx));
        box.addView(header);

        ImageView thumb = new ImageView(ctx);
        thumb.setScaleType(ImageView.ScaleType.FIT_CENTER);
        thumb.setBackground(Ui.roundRect(0x40FFFFFF, Ui.dp(ctx, 16)));
        thumb.setImageDrawable(card.icon);
        int radius = Ui.dp(ctx, 16);
        thumb.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        thumb.setClipToOutline(true);
        thumb.setTag("thumb");
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(width, thumbH);
        tlp.topMargin = Ui.dp(ctx, 8);
        box.addView(thumb, tlp);

        thumb.setOnClickListener(v -> launch(ctx, card));
        swipeToClose(thumb, box, () -> remove(ctx, card, all));
        return box;
    }

    /** Up and away closes the app, as in any recents; anything shorter springs back. */
    private static void swipeToClose(View handle, View card, Runnable onClose) {
        final float[] down = new float[2];
        final boolean[] dragging = new boolean[1];
        handle.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down[0] = e.getRawX();
                    down[1] = e.getRawY();
                    dragging[0] = false;
                    return false;
                case MotionEvent.ACTION_MOVE:
                    float dy = e.getRawY() - down[1];
                    if (!dragging[0] && dy < -Ui.dp(v.getContext(), 12)
                            && Math.abs(dy) > Math.abs(e.getRawX() - down[0])) {
                        dragging[0] = true;
                        v.getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    if (dragging[0]) {
                        card.setTranslationY(Math.min(0f, dy));
                        card.setAlpha(1f + Math.min(0f, dy) / (card.getHeight() * 1.2f));
                        return true;
                    }
                    return false;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (!dragging[0]) {
                        return false;
                    }
                    if (card.getTranslationY() < -card.getHeight() / 3f) {
                        card.animate().translationY(-card.getHeight()).alpha(0f)
                                .setDuration(Motion.SHORT).setInterpolator(Motion.EXIT)
                                .withEndAction(onClose).start();
                    } else {
                        card.animate().translationY(0f).alpha(1f).setDuration(Motion.MEDIUM)
                                .setInterpolator(Motion.SPRING_FIRM).start();
                    }
                    return true;
                default:
                    return false;
            }
        });
    }

    // --- what is shown -----------------------------------------------------------------------

    /** This display's tasks, newest first - not the launcher's own, not ours. */
    private static List<Card> tasksOn(Context ctx, int display) {
        List<Card> out = new ArrayList<>();
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            PackageManager pm = ctx.getPackageManager();
            for (ActivityManager.RecentTaskInfo task
                    : am.getRecentTasks(40, ActivityManager.RECENT_IGNORE_UNAVAILABLE)) {
                Object d = Reflect.field(task, "displayId");
                if (!(d instanceof Integer) || (Integer) d != display) {
                    continue;
                }
                Intent base = task.baseIntent;
                ComponentName c = base != null ? base.getComponent() : null;
                if (c == null && task.baseActivity != null) {
                    c = task.baseActivity;
                }
                if (c == null || c.getPackageName().equals(ctx.getPackageName())
                        || c.getPackageName().equals("com.zuxos.desktopplus")) {
                    continue;
                }
                Card card = new Card();
                card.taskId = task.taskId;
                card.baseIntent = base;
                card.pkg = c.getPackageName();
                try {
                    card.label = pm.getApplicationLabel(pm.getApplicationInfo(card.pkg, 0));
                    card.icon = pm.getApplicationIcon(card.pkg);
                } catch (Throwable missing) {
                    card.label = card.pkg;
                }
                out.add(card);
            }
        } catch (Throwable t) {
            L.d("task overview: could not list tasks (" + t + ")");
        }
        return out;
    }

    /** Each app's last picture, fetched off the main thread and dropped in as it arrives. */
    private static void loadThumbnails(List<Card> cards) {
        IO.execute(() -> {
            int got = 0;
            for (Card card : cards) {
                Bitmap b = snapshot(card.taskId);
                if (b == null) {
                    continue;
                }
                got++;
                card.thumb = b;
                MAIN.post(() -> {
                    View view = card.view;
                    View thumb = view == null ? null : view.findViewWithTag("thumb");
                    if (thumb instanceof ImageView) {
                        // Whole, not cropped: a window's picture has the window's shape.
                        ((ImageView) thumb).setScaleType(ImageView.ScaleType.FIT_CENTER);
                        ((ImageView) thumb).setImageBitmap(b);
                    }
                });
            }
            record("thumbnails: " + got + " of " + cards.size());
        });
    }

    private static Bitmap snapshot(int taskId) {
        try {
            Object atm = activityTaskManager();
            for (Method m : atm.getClass().getMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (m.getName().equals("getTaskSnapshot") && p.length >= 2
                        && p[0] == int.class && p[1] == boolean.class) {
                    Object[] args = new Object[p.length];
                    args[0] = taskId;
                    args[1] = false;
                    for (int i = 2; i < p.length; i++) {
                        args[i] = p[i] == boolean.class ? Boolean.FALSE : null;
                    }
                    Object snap = m.invoke(atm, args);
                    if (snap == null) {
                        return null;
                    }
                    HardwareBuffer buffer = (HardwareBuffer) snap.getClass()
                            .getMethod("getHardwareBuffer").invoke(snap);
                    ColorSpace space = (ColorSpace) snap.getClass()
                            .getMethod("getColorSpace").invoke(snap);
                    if (buffer == null) {
                        return null;
                    }
                    return Bitmap.wrapHardwareBuffer(buffer,
                            space != null ? space : ColorSpace.get(ColorSpace.Named.SRGB));
                }
            }
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            record("no snapshot for task " + taskId + " (" + cause + ")");
        }
        return null;
    }

    private static Object activityTaskManager() throws Exception {
        return Class.forName("android.app.ActivityTaskManager").getMethod("getService")
                .invoke(null);
    }

    // --- what it does ------------------------------------------------------------------------

    /** Brings the task forward on this display, as recents does. */
    private static void launch(Context ctx, Card card) {
        int display = sDisplay;
        close();
        ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(display);
        try {
            Object atm = activityTaskManager();
            Method m = atm.getClass().getMethod("startActivityFromRecents", int.class,
                    android.os.Bundle.class);
            m.invoke(atm, card.taskId, options.toBundle());
            record("opened " + card.pkg + " from recents");
            return;
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            record("from recents refused (" + cause + ") - starting it instead");
        }
        try {
            Intent intent = card.baseIntent != null ? new Intent(card.baseIntent)
                    : ctx.getPackageManager().getLaunchIntentForPackage(card.pkg);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent, options.toBundle());
            record("opened " + card.pkg + " by its intent");
        } catch (Throwable t) {
            record("could not open " + card.pkg + " (" + t + ")");
        }
    }

    /** Closes the app's task, and takes its card away. */
    private static void remove(Context ctx, Card card, List<Card> all) {
        boolean removed = false;
        try {
            Object atm = activityTaskManager();
            Object r = atm.getClass().getMethod("removeTask", int.class).invoke(atm, card.taskId);
            removed = !Boolean.FALSE.equals(r);
            record("closed task " + card.taskId);
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            record("removeTask refused (" + cause + ") - force-stopping " + card.pkg);
        }
        if (!removed) {
            KeyShell.run("am force-stop " + card.pkg);
        }
        all.remove(card);
        View view = card.view;
        if (view != null && view.getParent() instanceof ViewGroup) {
            ViewGroup row = (ViewGroup) view.getParent();
            view.animate().alpha(0f).scaleX(0.9f).scaleY(0.9f).setDuration(Motion.SHORT)
                    .withEndAction(() -> row.removeView(view)).start();
        }
        if (all.isEmpty()) {
            MAIN.postDelayed(TaskOverview::close, Motion.SHORT);
        }
    }

    private static void clearAll(Context ctx, List<Card> cards) {
        for (Card card : new ArrayList<>(cards)) {
            remove(ctx, card, cards);
        }
    }

    static void close() {
        FrameLayout root = sRoot;
        WindowManager wm = sWm;
        sRoot = null;
        sWm = null;
        if (root == null || wm == null) {
            return;
        }
        root.animate().alpha(0f).setDuration(Motion.SHORT).setInterpolator(Motion.EXIT)
                .withEndAction(() -> {
                    try {
                        wm.removeViewImmediate(root);
                    } catch (Throwable ignored) {
                        // Already gone.
                    }
                }).start();
    }

    private static synchronized void record(String what) {
        RECORD.addLast(what);
        while (RECORD.size() > 10) {
            RECORD.removeFirst();
        }
        L.i("task overview: " + what);
    }

    static synchronized String describe() {
        StringBuilder sb = new StringBuilder("\ntask overview\n  ")
                .append(sRoot != null ? "open on display " + sDisplay : "closed").append('\n');
        for (String s : RECORD) {
            sb.append("  - ").append(s).append('\n');
        }
        return sb.toString();
    }
}
