package com.zuxos.desktopplus.hook.recents;

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

import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;
import com.zuxos.desktopplus.core.Ui;
import com.zuxos.desktopplus.core.glass.Glass;
import com.zuxos.desktopplus.core.glass.GlassSurface;
import com.zuxos.desktopplus.core.glass.LiquidGlass;
import com.zuxos.desktopplus.core.glass.ScreenBackdrop;
import com.zuxos.desktopplus.core.motion.FrameRate;
import com.zuxos.desktopplus.core.motion.Motion;
import com.zuxos.desktopplus.hook.HoverTile;
import com.zuxos.desktopplus.hook.KeyShell;
import com.zuxos.desktopplus.hook.Overlays;
import com.zuxos.desktopplus.hook.taskbar.TaskbarMenu;
import com.zuxos.desktopplus.hook.taskbar.TaskbarTray;

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
public final class TaskOverview {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "zux-desktop-plus-overview");
        t.setDaemon(true);
        return t;
    });

    /** Cards on liquid glass; past this many, plain - each pane is a live capture of its own. */
    private static final int GLASS_CARDS = 64;

    private static FrameLayout sRoot;
    private static WindowManager sWm;
    private static int sDisplay = -1;
    private static final ArrayDeque<String> RECORD = new ArrayDeque<>();

    private TaskOverview() {
    }

    /** Opens it on the display of {@code anchor}, or closes it if it is already up there. */
    /** When a touch outside last closed it: the recents button's own press is one of those. */
    private static long sOutsideAt;

    public static void toggle(View anchor, int display) {
        if (sRoot != null) {
            close();
            return;
        }
        if (android.os.SystemClock.uptimeMillis() - sOutsideAt < 600L) {
            // The press on the recents button already closed it, as a touch outside.
            return;
        }
        open(anchor, display);
    }

    public static boolean isOpen() {
        return sRoot != null;
    }

    /** One task as shown: what it is and where its picture comes from. */
    private static final class Card {
        int taskId;
        /** Running now: its card is a live tile. */
        boolean running;
        /** On screen now, and where: its tile is cut straight out of a screen capture. */
        boolean visible;
        /** The app's uid: its tile is a capture of that app's layers alone. */
        int uid = -1;
        /** Its tile is being fed from its own capture right now (else the snapshot rotation). */
        volatile boolean screenFed;
        CropDrawable crop;
        final ArrayDeque<Bitmap> frames = new ArrayDeque<>();
        int fed;
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
        sCards = cards;
        try {
            FrameLayout root = new FrameLayout(ctx);
            root.setBackgroundColor(0x400A0A0E);
            // The whole background is one sheet of liquid glass: the screen behind frosted, and
            // bent at its rim all the way round - not only the cards.
            int sheetInset = Ui.dp(ctx, 12);
            GlassSurface sheet =
                    new GlassSurface(ctx, Ui.dp(ctx, 32),
                            0x8C0A0A0E, LiquidGlass.THICK);
            FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            slp.setMargins(sheetInset, sheetInset, sheetInset, sheetInset);
            root.addView(sheet, slp);
            root.setOnClickListener(v -> close());
            // A touch on the taskbar - the one part of the screen this does not cover - means
            // the user has moved on: opened a folder, an app, a menu. The recordings showed
            // recents left open over the app launched from there.
            root.setOnTouchListener((v, e) -> {
                if (e.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
                    sOutsideAt = android.os.SystemClock.uptimeMillis();
                    close();
                    return true;
                }
                return false;
            });
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
            LinearLayout titles = new LinearLayout(ctx);
            titles.setOrientation(LinearLayout.VERTICAL);
            titles.addView(title);
            TextView memory = new TextView(ctx);
            memory.setText(memoryLine(ctx));
            memory.setTextColor(0xB3FFFFFF);
            memory.setTextSize(13);
            memory.setPadding(0, Ui.dp(ctx, 4), 0, 0);
            titles.addView(memory);
            header.addView(titles, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (!cards.isEmpty()) {
                TextView clear = new TextView(ctx);
                clear.setText("Clear all");
                clear.setTextColor(0xFFFFFFFF);
                clear.setTextSize(15);
                int ph = Ui.dp(ctx, 18);
                int pv = Ui.dp(ctx, 9);
                clear.setPadding(ph, pv, ph, pv);
                clear.setBackground(null);
                clear.setOnClickListener(v -> clearAll(ctx, cards));
                GlassSurface pill =
                        new GlassSurface(ctx, Ui.dp(ctx, 20),
                                0x401C1C22, LiquidGlass.MENU);
                pill.addView(clear);
                header.addView(pill);
            }
            column.addView(header);

            // The cards: rows sized to this screen, the newest first.
            int gap = Ui.dp(ctx, 28);
            // Fewer apps, bigger cards: one or two open apps should not be postage stamps.
            float share = cards.size() <= 2 ? 0.34f : cards.size() <= 6 ? 0.27f : 0.22f;
            int cardW = Math.max(Ui.dp(ctx, 220), (int) (screenW * share));
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
                // The picture and a title bar of liquid glass over it; no pane round the whole
                // card - it showed as a band under the picture with a pointed corner.
                View view = cardView(ctx, card, cards, cardW, thumbH, i < GLASS_CARDS,
                        i * 25L);
                card.view = view;
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                        cardW, ViewGroup.LayoutParams.WRAP_CONTENT);
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
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                    PixelFormat.TRANSLUCENT);
            // Above the taskbar, never over it: its recents button is how this is closed.
            lp.gravity = Gravity.TOP;
            lp.setTitle("ZuxOS Desktop Plus recents");
            Glass.blurBehind(ctx, lp, Glass.BEHIND_BLUR_DP * 2);
            root.setAlpha(0f);
            // The monitor's fastest refresh rate while this is up: its motion at what the
            // screen can show.
            FrameRate.forWindow(lp, wm.getDefaultDisplay());
            FrameRate.forView(root);
            wm.addView(root, lp);
            root.animate().alpha(1f).setDuration(Motion.SHORT).setInterpolator(Motion.EASE)
                    .start();
            FrameRate.measure(root, "recents");
            root.requestFocus();
            sRoot = root;
            sWm = wm;
            sDisplay = display;
            loadThumbnails(cards);
            startLive(cards, root);
            record("opened on display " + display + " (" + cards.size() + " tasks)");
        } catch (Throwable t) {
            L.e("task overview: could not open", t);
        }
    }

    /** One card: icon and name over the app's last picture, with an X; tap, X or swipe up. */
    private static View cardView(Context ctx, Card card, List<Card> all, int width, int thumbH,
            boolean glass, long delay) {
        // Under a mouse or stylus: a soft light behind the card and its picture growing a
        // little, steady across its X and icon - the same as the taskbar's previews.
        HoverTile box = new HoverTile(ctx);
        box.setOrientation(LinearLayout.VERTICAL);

        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        int barV = Ui.dp(ctx, 6);
        header.setPadding(Ui.dp(ctx, 10), barV, Ui.dp(ctx, 6), barV);
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
        int xPx = Ui.dp(ctx, 28);
        header.addView(closeButton(ctx, xPx, glass, delay, () -> remove(ctx, card, all)),
                new LinearLayout.LayoutParams(xPx, xPx));
        int barRadius = Ui.dp(ctx, 18);
        if (glass) {
            // The title bar is a pane of liquid glass of its own, round at all four corners.
            GlassSurface bar =
                    new GlassSurface(ctx, barRadius, 0x401C1C22,
                            LiquidGlass.MENU);
            bar.addView(header, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            box.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            header.setBackground(Ui.roundRect(0x33FFFFFF, barRadius));
            box.addView(header);
        }

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
        box.mThumb = thumb;
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(width, thumbH);
        tlp.topMargin = Ui.dp(ctx, 8);
        box.addView(thumb, tlp);

        thumb.setOnClickListener(v -> launch(ctx, card));
        swipeToClose(thumb, card, box, () -> remove(ctx, card, all));
        // Held, or right-clicked: the same menu an app's icon has, with what it is using.
        View.OnLongClickListener menu = v -> {
            showMenu(ctx, v, card, all);
            return true;
        };
        thumb.setOnLongClickListener(menu);
        header.setOnLongClickListener(menu);
        thumb.setOnContextClickListener(v -> {
            showMenu(ctx, v, card, all);
            return true;
        });
        header.setOnContextClickListener(v -> {
            showMenu(ctx, v, card, all);
            return true;
        });
        return box;
    }

    /** Up and away closes the app, as in any recents; anything shorter springs back. */
    /**
     * The close button: a circle of white liquid glass with a wheel of colour turning in it, and
     * a black X. The wheel turns slowly all the time and fast while the X spins - in when the card
     * appears, a quarter turn under the mouse, half a turn when pressed, before the card closes.
     */
    private static View closeButton(Context ctx, int size, boolean glass, long delay,
            Runnable onClose) {
        FrameLayout circle;
        if (glass) {
            circle = new GlassSurface(ctx, size / 2f, 0x66F4F4F8,
                    LiquidGlass.MENU);
        } else {
            circle = new FrameLayout(ctx);
            circle.setBackground(Ui.roundRect(0x66F4F4F8, size / 2));
        }
        ColorWheel wheel = new ColorWheel(ctx);
        circle.addView(wheel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        TextView x = new TextView(ctx);
        x.setText("✕");
        x.setTextColor(0xFF1C1C1E);
        x.setTextSize(14);
        x.setGravity(Gravity.CENTER);
        circle.addView(x, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // The wheel follows the X at twice its turn, on top of its own slow one.
        android.animation.ValueAnimator idle = android.animation.ValueAnimator.ofFloat(0f, 360f);
        idle.setDuration(6000L);
        idle.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        idle.setInterpolator(new android.view.animation.LinearInterpolator());
        idle.addUpdateListener(a ->
                wheel.setRotation((Float) a.getAnimatedValue() + x.getRotation() * 2f));
        circle.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                idle.start();
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                idle.cancel();
            }
        });

        x.setRotation(-180f);
        x.animate().rotation(0f).setStartDelay(delay + 80L).setDuration(Motion.SPRING_MS * 2)
                .setInterpolator(Motion.SPRING).start();
        circle.setOnHoverListener((v, e) -> {
            int action = e.getActionMasked();
            if (action == MotionEvent.ACTION_HOVER_ENTER) {
                x.animate().rotation(90f).setStartDelay(0).setDuration(Motion.SPRING_MS)
                        .setInterpolator(Motion.SPRING).start();
            } else if (action == MotionEvent.ACTION_HOVER_EXIT) {
                x.animate().rotation(0f).setStartDelay(0).setDuration(Motion.SPRING_MS)
                        .setInterpolator(Motion.SPRING).start();
            }
            return false;
        });
        boolean[] closing = {false};
        circle.setOnClickListener(v -> {
            if (closing[0]) {
                return;
            }
            closing[0] = true;
            x.animate().rotation(x.getRotation() + 180f).setStartDelay(0)
                    .setDuration(Motion.SHORT + 100).setInterpolator(Motion.SNAPPY)
                    .withEndAction(onClose).start();
        });
        return circle;
    }

    /** A disc of every hue, round the centre, see-through enough to let the glass show. */
    private static final class ColorWheel extends View {
        private static final int[] HUES = {0x8CFF4FA3, 0x8CFF9F3A, 0x8CFFE14D, 0x8C4CD964,
                0x8C38D6F0, 0x8C8E6CFF, 0x8CFF4FA3};
        private final android.graphics.Paint mPaint =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        ColorWheel(Context ctx) {
            super(ctx);
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            mPaint.setShader(new android.graphics.SweepGradient(w / 2f, h / 2f, HUES, null));
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            float r = Math.min(getWidth(), getHeight()) / 2f;
            canvas.drawCircle(getWidth() / 2f, getHeight() / 2f, r, mPaint);
        }
    }

    private static void swipeToClose(View handle, Card owner, View box, Runnable onClose) {
        final float[] down = new float[2];
        final boolean[] dragging = new boolean[1];
        handle.setOnTouchListener((v, e) -> {
            // The whole card moves - its glass pane with it - not just what is inside it.
            View card = owner.view != null ? owner.view : box;
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
                        // A swipe is not a hold: the menu must not open under the finger.
                        v.cancelLongPress();
                        v.setPressed(false);
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
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            PackageManager pm = ctx.getPackageManager();
            // What is open on this screen, from the running list: the recent list left out
            // windows that were plainly on screen ("No recent apps" over an open Termux and an
            // open browser in the recordings). The running list is the one the probe proves
            // carries every task with its display.
            int here = 0;
            for (ActivityManager.RunningTaskInfo task : am.getRunningTasks(40)) {
                Object d = Reflect.field(task, "displayId");
                if (!(d instanceof Integer) || (Integer) d != display) {
                    continue;
                }
                Card card = cardFor(ctx, pm, task.taskId, task.baseIntent,
                        task.topActivity != null ? task.topActivity : task.baseActivity, true);
                if (card != null) {
                    card.visible = Boolean.TRUE.equals(Reflect.field(task, "isVisible"));
                }
                if (card != null && seen.add(card.taskId)) {
                    out.add(card);
                    here++;
                }
            }
            // Then recent apps that are not running anywhere any more, newest first.
            int idle = 0;
            int elsewhere = 0;
            for (ActivityManager.RecentTaskInfo task
                    : am.getRecentTasks(40, ActivityManager.RECENT_IGNORE_UNAVAILABLE)) {
                if (seen.contains(task.taskId)) {
                    continue;
                }
                Object d = Reflect.field(task, "displayId");
                int on = d instanceof Integer ? (Integer) d : -1;
                boolean running = Boolean.TRUE.equals(Reflect.field(task, "isRunning"));
                if (on >= 0 && running) {
                    elsewhere++;
                    continue;
                }
                Card card = cardFor(ctx, pm, task.taskId, task.baseIntent, task.baseActivity,
                        false);
                if (card != null && seen.add(card.taskId)) {
                    out.add(card);
                    idle++;
                }
            }
            record("tasks: " + here + " open here, " + idle + " not running, " + elsewhere
                    + " on other screens skipped");
        } catch (Throwable t) {
            L.d("task overview: could not list tasks (" + t + ")");
        }
        return out;
    }

    private static Card cardFor(Context ctx, PackageManager pm, int taskId, Intent base,
            ComponentName fallback, boolean running) {
        ComponentName c = base != null ? base.getComponent() : null;
        if (c == null) {
            c = fallback;
        }
        if (c == null || c.getPackageName().equals(ctx.getPackageName())
                || c.getPackageName().equals("com.zuxos.desktopplus")) {
            return null;
        }
        Card card = new Card();
        card.running = running;
        card.taskId = taskId;
        card.baseIntent = base;
        card.pkg = c.getPackageName();
        try {
            android.content.pm.ApplicationInfo info = pm.getApplicationInfo(card.pkg, 0);
            card.uid = info.uid;
            card.label = pm.getApplicationLabel(info);
            card.icon = pm.getApplicationIcon(card.pkg);
        } catch (Throwable missing) {
            card.label = card.pkg;
        }
        return card;
    }

    /** Each app's last picture, fetched off the main thread and dropped in as it arrives. */
    private static void loadThumbnails(List<Card> cards) {
        IO.execute(() -> {
            sFresh = 0;
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
                    if (thumb instanceof ImageView && !card.screenFed) {
                        // Whole, not cropped: a window's picture has the window's shape.
                        ((ImageView) thumb).setScaleType(ImageView.ScaleType.FIT_CENTER);
                        ((ImageView) thumb).setImageBitmap(b);
                    }
                });
            }
            record("thumbnails: got " + got + " of " + cards.size() + " (" + sFresh
                    + " taken fresh)");
        });
    }

    private static volatile int sFresh;

    /**
     * The task's picture: the one the system kept, or - for a window still on screen, which has
     * none kept yet (most cards had no picture in the log) - one taken now.
     */
    public static Bitmap snapshot(int taskId) {
        Bitmap kept = snapshot(taskId, "getTaskSnapshot");
        if (kept != null) {
            return kept;
        }
        Bitmap fresh = snapshot(taskId, "takeTaskSnapshot");
        if (fresh != null) {
            sFresh++;
        }
        return fresh;
    }

    private static Bitmap snapshot(int taskId, String name) {
        return snapshot(taskId, name, name.startsWith("take"));
    }

    private static Bitmap snapshot(int taskId, String name, boolean updateCache) {
        try {
            Object atm = activityTaskManager();
            for (Method m : atm.getClass().getMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (m.getName().equals(name) && p.length >= 2
                        && p[0] == int.class && p[1] == boolean.class) {
                    Object[] args = new Object[p.length];
                    args[0] = taskId;
                    // getTaskSnapshot(id, lowResolution=false); takeTaskSnapshot(id, updateCache)
                    args[1] = updateCache;
                    for (int i = 2; i < p.length; i++) {
                        args[i] = p[i] == boolean.class ? Boolean.FALSE
                                : p[i] == int.class ? Integer.valueOf(0) : null;
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
            record("no " + name + " for task " + taskId + " (" + cause + ")");
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

    /**
     * Brings a task forward on a display, the way recents does - for the taskbar's Minimize,
     * which shows the task that was under the minimised one.
     */
    public static boolean bringToFront(int taskId, int display) {
        try {
            ActivityOptions options = ActivityOptions.makeBasic();
            options.setLaunchDisplayId(display);
            Object atm = activityTaskManager();
            atm.getClass().getMethod("startActivityFromRecents", int.class,
                    android.os.Bundle.class).invoke(atm, taskId, options.toBundle());
            return true;
        } catch (Throwable t) {
            L.d("task overview: could not bring task " + taskId + " forward (" + t + ")");
            return false;
        }
    }

    /**
     * Something in the launcher is starting an app - the drawer, a taskbar icon, a menu. Recents
     * gets out of its way, or the app opens behind it: an overlay stays above every app.
     *
     * @param tapDisplay the screen the tap that caused it was on, -1 if not known
     */
    public static void onLaunch(int tapDisplay) {
        if (sRoot == null || (tapDisplay >= 0 && tapDisplay != sDisplay)) {
            return;
        }
        MAIN.post(() -> {
            if (sRoot != null) {
                record("closed: an app was launched from the launcher");
                closeForLaunch();
            }
        });
    }

    /** Closes as the app opens: fading out and growing a little, as if handing over to it. */
    private static void closeForLaunch() {
        FrameLayout root = sRoot;
        WindowManager wm = sWm;
        sRoot = null;
        sWm = null;
        if (root == null || wm == null) {
            return;
        }
        View content = root.getChildCount() > 0 ? root.getChildAt(root.getChildCount() - 1)
                : null;
        if (content != null) {
            content.animate().scaleX(1.04f).scaleY(1.04f).setDuration(Motion.MEDIUM)
                    .setInterpolator(Motion.EASE).start();
        }
        root.animate().alpha(0f).setDuration(Motion.MEDIUM).setInterpolator(Motion.EASE)
                .withEndAction(() -> {
                    try {
                        wm.removeViewImmediate(root);
                    } catch (Throwable ignored) {
                        // Already gone.
                    }
                }).start();
    }

    /** Closes the app's task, and takes its card away. */
    private static void remove(Context ctx, Card card, List<Card> all) {
        closeTask(card.taskId, card.pkg);
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

    /** Closes one window of an app - its task - or, if the system says no, the whole app. */
    public static void closeTask(int taskId, String pkg) {
        boolean removed = false;
        try {
            Object atm = activityTaskManager();
            Object r = atm.getClass().getMethod("removeTask", int.class).invoke(atm, taskId);
            removed = !Boolean.FALSE.equals(r);
            record("closed task " + taskId);
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            record("removeTask refused (" + cause + ") - force-stopping " + pkg);
        }
        if (!removed) {
            KeyShell.run("am force-stop " + pkg);
        }
    }

    /** Open, close, app info - and how much memory the app is using, read off the main thread. */
    private static void showMenu(Context ctx, View anchor, Card card, List<Card> all) {
        int display = sDisplay;
        IO.execute(() -> {
            String ram = appMemory(ctx, card.pkg);
            MAIN.post(() -> {
                if (sRoot == null) {
                    return;
                }
                List<TaskbarMenu.Entry> entries = new ArrayList<>();
                entries.add(new TaskbarMenu.Entry("Open", () -> launch(ctx, card)));
                entries.add(new TaskbarMenu.Entry("Close", () -> remove(ctx, card, all)));
                entries.add(new TaskbarMenu.Entry("App info", () -> appInfo(ctx, card)));
                entries.add(new TaskbarMenu.Entry(ram, () -> { }));
                int[] at = new int[2];
                anchor.getLocationOnScreen(at);
                TaskbarMenu.showEntries(anchor, display, at[0] + anchor.getWidth() / 2f,
                        at[1] + Ui.dp(ctx, 24), entries);
            });
        });
    }

    private static void appInfo(Context ctx, Card card) {
        int display = sDisplay;
        close();
        try {
            Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.fromParts("package", card.pkg, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ActivityOptions options = ActivityOptions.makeBasic();
            options.setLaunchDisplayId(display);
            ctx.startActivity(intent, options.toBundle());
        } catch (Throwable t) {
            record("could not open app info for " + card.pkg + " (" + t + ")");
        }
    }

    /** The app's memory: the proportional share of every process it is running. */
    private static String appMemory(Context ctx, String pkg) {
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            List<Integer> pids = new ArrayList<>();
            List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
            if (procs != null) {
                for (ActivityManager.RunningAppProcessInfo p : procs) {
                    if (p.processName != null && (p.processName.equals(pkg)
                            || p.processName.startsWith(pkg + ":"))) {
                        pids.add(p.pid);
                    }
                }
            }
            if (pids.isEmpty()) {
                return "Memory: not running";
            }
            int[] arr = new int[pids.size()];
            for (int i = 0; i < arr.length; i++) {
                arr[i] = pids.get(i);
            }
            long kb = 0;
            for (android.os.Debug.MemoryInfo info : am.getProcessMemoryInfo(arr)) {
                kb += info.getTotalPss();
            }
            return kb > 0 ? "Memory: " + formatKb(kb) : "Memory: not readable";
        } catch (Throwable t) {
            record("app memory for " + pkg + " unreadable (" + t + ")");
            return "Memory: not readable";
        }
    }

    /** RAM in use and ZRAM (compressed swap) in use, for the header. */
    private static String memoryLine(Context ctx) {
        StringBuilder sb = new StringBuilder();
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            long used = mi.totalMem - mi.availMem;
            sb.append("RAM ").append(Math.round(100f * used / Math.max(1, mi.totalMem)))
                    .append("% \u00b7 ").append(formatKb(used / 1024)).append(" of ")
                    .append(formatKb(mi.totalMem / 1024));
        } catch (Throwable ignored) {
            // No RAM line; the swap one may still read.
        }
        long swapTotal = -1;
        long swapFree = -1;
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.FileReader("/proc/meminfo"))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("SwapTotal:")) {
                    swapTotal = kbOf(line);
                } else if (line.startsWith("SwapFree:")) {
                    swapFree = kbOf(line);
                }
            }
        } catch (Throwable ignored) {
            // Not readable here; the line just has no ZRAM part.
        }
        if (swapTotal > 0 && swapFree >= 0) {
            long used = swapTotal - swapFree;
            if (sb.length() > 0) {
                sb.append("     ");
            }
            sb.append("ZRAM ").append(Math.round(100f * used / swapTotal)).append("% \u00b7 ")
                    .append(formatKb(used)).append(" of ").append(formatKb(swapTotal));
        }
        return sb.toString();
    }

    private static long kbOf(String line) {
        String digits = line.replaceAll("[^0-9]", "");
        return digits.isEmpty() ? -1 : Long.parseLong(digits);
    }

    private static String formatKb(long kb) {
        if (kb >= 1024L * 1024L) {
            return String.format(java.util.Locale.US, "%.1f GB", kb / (1024f * 1024f));
        }
        return (kb / 1024) + " MB";
    }

    private static void clearAll(Context ctx, List<Card> cards) {
        for (Card card : new ArrayList<>(cards)) {
            remove(ctx, card, cards);
        }
    }

    /** One live picture every this often, taking the running apps in turn. */
    private static final long LIVE_STEP_MS = 350L;

    /**
     * Live tiles: while recents is open, each running app's card is re-pictured in turn, so it
     * shows what the app is doing now rather than when it was last hidden. One picture at a time,
     * off the main thread, and it stops the moment recents closes.
     */
    private static void startLive(List<Card> cards, FrameLayout root) {
        final int[] next = {0};
        Runnable step = new Runnable() {
            @Override
            public void run() {
                if (sRoot != root) {
                    return;
                }
                List<Card> live = new ArrayList<>();
                for (Card c : cards) {
                    // Cards fed from their own capture are already live at frame rate.
                    if (c.running && c.view != null && !c.screenFed) {
                        live.add(c);
                    }
                }
                if (live.isEmpty()) {
                    // All live from their captures for now; one may stop being drawn later.
                    MAIN.postDelayed(this, LIVE_STEP_MS);
                    return;
                }
                Card card = live.get(next[0]++ % live.size());
                IO.execute(() -> {
                    // Not into the system's cache: these are for this view only.
                    Bitmap b = snapshot(card.taskId, "takeTaskSnapshot", false);
                    MAIN.post(() -> {
                        if (b == null) {
                            return;
                        }
                        View thumb = card.view.findViewWithTag("thumb");
                        if (sRoot != root || !(thumb instanceof ImageView) || card.screenFed) {
                            // Closed meanwhile, or fed from the screen now: never shown.
                            b.recycle();
                        } else {
                            Bitmap old = card.thumb;
                            card.thumb = b;
                            ((ImageView) thumb).setScaleType(ImageView.ScaleType.FIT_CENTER);
                            ((ImageView) thumb).setImageBitmap(b);
                            if (old != null && old != b) {
                                // Freed a frame later, once nothing is drawing it.
                                thumb.postOnAnimation(old::recycle);
                            }
                        }
                    });
                    MAIN.postDelayed(this, LIVE_STEP_MS);
                });
            }
        };
        MAIN.postDelayed(step, 600L);
        startScreenFeed(cards, root);
    }

    /** About 30 pictures a second for the tiles of apps that are on screen. */
    private static final long FRAME_MS = 33L;
    private static final float FEED_SCALE = 0.4f;
    /** How often, in frames, a tile looks again for where its window is in its picture. */
    private static final int BOX_EVERY = 15;

    /**
     * Live tiles at frame rate, each app captured on its own.
     *
     * <p>The capture is limited to the layers the app owns, so the picture holds that app and
     * nothing else. Cutting tiles out of one capture of the whole screen did not work: every task
     * on the monitor reports itself fullscreen and visible, so every tile was the whole screen -
     * bars, home screen, and whichever app was in front.
     *
     * <p>Where the window is inside its picture is read from the picture itself, every so often:
     * the opaque part. That is the window as drawn - a portrait app letterboxed in the middle, a
     * desktop window smaller than its reported bounds. A picture with nothing in it means the app
     * is not drawn (behind home, behind a fullscreen app, minimised); its tile goes back to its
     * snapshot and is looked at again a little later.
     */
    private static void startScreenFeed(List<Card> cards, FrameLayout root) {
        List<Card> fed = new ArrayList<>();
        java.util.Map<Integer, Integer> perUid = new java.util.HashMap<>();
        for (Card c : cards) {
            if (c.running && c.visible && c.uid >= 0) {
                perUid.merge(c.uid, 1, Integer::sum);
            }
        }
        int shared = 0;
        for (Card c : cards) {
            if (!(c.running && c.visible && c.uid >= 0)) {
                continue;
            }
            if (perUid.get(c.uid) > 1) {
                // Two windows of one app: one capture holds both, so neither is its own tile.
                shared++;
                continue;
            }
            fed.add(c);
        }
        if (fed.isEmpty() || ScreenBackdrop.refused()
                || Boolean.FALSE.equals(ScreenBackdrop.uidFilter())) {
            record("live tiles: snapshots only (" + (fed.isEmpty() ? "nothing on screen"
                    : "capture refused") + (shared > 0 ? ", " + shared + " sharing an app" : "")
                    + ")");
            return;
        }
        final int display = sDisplay;
        final DisplayMetrics dm = root.getContext().getResources().getDisplayMetrics();
        final android.graphics.Rect screen = new android.graphics.Rect(0, 0, dm.widthPixels,
                dm.heightPixels);
        final long startedAt = android.os.SystemClock.uptimeMillis();
        final int[] round = {0};
        final int[] delivered = {0};
        final boolean[] said = {false};
        final int sharedCount = shared;
        Runnable capture = new Runnable() {
            @Override
            public void run() {
                if (sRoot != root) {
                    return;
                }
                long t0 = android.os.SystemClock.uptimeMillis();
                android.view.SurfaceControl own =
                        ScreenBackdrop.surfaceOf(root);
                boolean probe = round[0]++ % BOX_EVERY == 0;
                for (Card c : fed) {
                    if (own == null || sRoot != root) {
                        break;
                    }
                    if (!c.screenFed && !probe) {
                        // Not drawn last time it was looked at: only look again now and then.
                        continue;
                    }
                    Object shot = ScreenBackdrop
                            .grab(display, screen, own, FEED_SCALE, c.uid);
                    Bitmap frame = shot == null ? null
                            : ScreenBackdrop.toBitmap(shot);
                    if (frame == null) {
                        if (Boolean.FALSE.equals(
                                ScreenBackdrop.uidFilter())) {
                            record("live tiles: this build cannot capture one app alone"
                                    + " - snapshots only");
                            MAIN.post(() -> stopFeeding(fed));
                            return;
                        }
                        continue;
                    }
                    android.graphics.Rect box = null;
                    if (!c.screenFed || c.fed++ % BOX_EVERY == 0) {
                        box = contentBox(frame);
                        if (box == null) {
                            // Could not read it: keep what the tile had.
                            box = c.crop != null ? null : new android.graphics.Rect(0, 0,
                                    frame.getWidth(), frame.getHeight());
                        }
                    }
                    final android.graphics.Rect where = box;
                    MAIN.post(() -> show(c, frame, where, root));
                    delivered[0]++;
                }
                long now = android.os.SystemClock.uptimeMillis();
                if (!said[0] && now - startedAt >= 2000L) {
                    said[0] = true;
                    int live = 0;
                    for (Card c : fed) {
                        live += c.screenFed ? 1 : 0;
                    }
                    int each = live == 0 ? 0
                            : Math.round(delivered[0] * 1000f / (now - startedAt) / live);
                    record("live tiles: " + live + " app(s) captured on their own at ~" + each
                            + " fps each, " + (cards.size() - live) + " from snapshots"
                            + (sharedCount > 0 ? " (" + sharedCount + " sharing an app)" : ""));
                }
                long wait = Math.max(0L, FRAME_MS - (now - t0));
                MAIN.postDelayed(() -> IO.execute(this), wait);
            }
        };
        IO.execute(capture);
    }

    /**
     * Puts a tile's new picture up. {@code box} is where the window is in it when it was looked
     * for this frame; empty means the app is not drawn and the tile goes back to its snapshot.
     */
    private static void show(Card c, Bitmap frame, android.graphics.Rect box, FrameLayout root) {
        View thumb = c.view == null ? null : c.view.findViewWithTag("thumb");
        if (sRoot != root || !(thumb instanceof ImageView)) {
            frame.recycle();
            return;
        }
        ImageView image = (ImageView) thumb;
        if (box != null && box.isEmpty()) {
            frame.recycle();
            if (c.screenFed) {
                c.screenFed = false;
                c.crop = null;
                image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                if (c.thumb != null && !c.thumb.isRecycled()) {
                    image.setImageBitmap(c.thumb);
                } else {
                    image.setImageDrawable(c.icon);
                }
            }
            return;
        }
        if (c.crop == null) {
            if (box == null) {
                frame.recycle();
                return;
            }
            c.crop = new CropDrawable(box);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setImageDrawable(c.crop);
        } else if (box != null && c.crop.setSource(box)) {
            // A new shape: the image view sizes the tile from the drawable's.
            image.setImageDrawable(null);
            image.setImageDrawable(c.crop);
        }
        c.screenFed = true;
        c.crop.setFrame(frame);
        c.frames.addLast(frame);
        while (c.frames.size() > 2) {
            c.frames.removeFirst().recycle();
        }
    }

    private static void stopFeeding(List<Card> fed) {
        for (Card c : fed) {
            if (!c.screenFed) {
                continue;
            }
            c.screenFed = false;
            c.crop = null;
            View thumb = c.view == null ? null : c.view.findViewWithTag("thumb");
            if (thumb instanceof ImageView) {
                if (c.thumb != null && !c.thumb.isRecycled()) {
                    ((ImageView) thumb).setImageBitmap(c.thumb);
                } else {
                    ((ImageView) thumb).setImageDrawable(c.icon);
                }
            }
        }
    }

    /**
     * Where something is drawn in an app-only capture: the box around its opaque pixels, empty if
     * there are none, null if the picture could not be read.
     */
    public static android.graphics.Rect contentBox(Bitmap frame) {
        Bitmap soft = null;
        try {
            soft = frame.copy(Bitmap.Config.ARGB_8888, false);
            if (soft == null) {
                return null;
            }
            int w = soft.getWidth();
            int h = soft.getHeight();
            int[] row = new int[w];
            int left = w;
            int top = h;
            int right = -1;
            int bottom = -1;
            final int step = 4;
            for (int y = 0; y < h; y += step) {
                soft.getPixels(row, 0, w, 0, y, w, 1);
                for (int x = 0; x < w; x += step) {
                    if ((row[x] >>> 24) > 16) {
                        if (x < left) {
                            left = x;
                        }
                        if (x > right) {
                            right = x;
                        }
                        if (y < top) {
                            top = y;
                        }
                        if (y > bottom) {
                            bottom = y;
                        }
                    }
                }
            }
            if (right < 0) {
                return new android.graphics.Rect();
            }
            // The sampling stepped over up to a few pixels at each edge.
            return new android.graphics.Rect(Math.max(0, left - step + 1),
                    Math.max(0, top - step + 1), Math.min(w, right + step),
                    Math.min(h, bottom + step));
        } catch (Throwable t) {
            return null;
        } finally {
            if (soft != null) {
                soft.recycle();
            }
        }
    }

    /** A tile: the part of its app's picture where the window is. */
    public static final class CropDrawable extends Drawable {
        private final android.graphics.Rect mSrc;
        private final android.graphics.Paint mPaint =
                new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
        private Bitmap mFrame;

        public CropDrawable(android.graphics.Rect src) {
            mSrc = new android.graphics.Rect(src);
        }

        /** True if the window moved or resized in the picture. */
        public boolean setSource(android.graphics.Rect src) {
            if (Math.abs(src.left - mSrc.left) <= 4 && Math.abs(src.top - mSrc.top) <= 4
                    && Math.abs(src.right - mSrc.right) <= 4
                    && Math.abs(src.bottom - mSrc.bottom) <= 4) {
                return false;
            }
            mSrc.set(src);
            return true;
        }

        public void setFrame(Bitmap frame) {
            mFrame = frame;
            invalidateSelf();
        }

        @Override
        public void draw(android.graphics.Canvas canvas) {
            Bitmap f = mFrame;
            if (f != null && !f.isRecycled()) {
                canvas.drawBitmap(f, mSrc, getBounds(), mPaint);
            }
        }

        @Override
        public int getIntrinsicWidth() {
            return Math.max(1, mSrc.width());
        }

        @Override
        public int getIntrinsicHeight() {
            return Math.max(1, mSrc.height());
        }

        @Override
        public void setAlpha(int alpha) {
            mPaint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter filter) {
            mPaint.setColorFilter(filter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    public static void close() {
        FrameLayout root = sRoot;
        WindowManager wm = sWm;
        List<Card> cards = sCards;
        sRoot = null;
        sWm = null;
        sCards = null;
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
                    recycle(cards);
                }).start();
    }

    /** The cards of the open overview, so their pictures can be freed when it closes. */
    private static List<Card> sCards;

    /** Every picture the cards held, once their window - the last thing drawing them - is gone. */
    private static void recycle(List<Card> cards) {
        if (cards == null) {
            return;
        }
        for (Card c : cards) {
            for (Bitmap b : c.frames) {
                b.recycle();
            }
            c.frames.clear();
            if (c.thumb != null) {
                c.thumb.recycle();
                c.thumb = null;
            }
        }
    }

    private static synchronized void record(String what) {
        RECORD.addLast(what);
        while (RECORD.size() > 10) {
            RECORD.removeFirst();
        }
        L.i("task overview: " + what);
    }

    public static synchronized String describe() {
        StringBuilder sb = new StringBuilder("\ntask overview\n  ")
                .append(sRoot != null ? "open on display " + sDisplay : "closed").append('\n');
        for (String s : RECORD) {
            sb.append("  - ").append(s).append('\n');
        }
        return sb.toString();
    }
}
