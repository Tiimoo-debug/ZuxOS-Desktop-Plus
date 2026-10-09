package com.zuxos.desktopplus.hook.panel;

import android.app.Activity;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Tone;
import com.zuxos.desktopplus.core.Ui;
import com.zuxos.desktopplus.core.glass.GlassSurface;
import com.zuxos.desktopplus.core.glass.LiquidGlass;
import com.zuxos.desktopplus.core.icons.TrayIcons;
import com.zuxos.desktopplus.core.motion.FrameRate;
import com.zuxos.desktopplus.core.motion.Hover;
import com.zuxos.desktopplus.core.motion.Motion;
import com.zuxos.desktopplus.desktop.DesktopHost;
import com.zuxos.desktopplus.hook.Overlays;
import com.zuxos.desktopplus.hook.taskbar.TaskbarRunning;
import com.zuxos.desktopplus.hook.taskbar.TaskbarTray;
import com.zuxos.desktopplus.notify.NotifyProvider;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A notification as it arrives, on the monitor: a card above the tray for a few seconds, the way
 * the tablet shows its own at the top of its screen - which SystemUI never does on the monitor,
 * where it has only a status bar.
 *
 * <p>Only what would pop up on the tablet pops up here. The module's listener picks those
 * (important, not ongoing, let through by Do not disturb) and says that one arrived; this asks
 * for it through the provider the panel reads, off the UI thread, once per arrival. Nothing runs
 * between arrivals, and with the switch off nothing is even listening.
 *
 * <p>Tap it to open it on the monitor. Its own buttons are under it, a reply is typed in place,
 * and a cross or a swipe to the right puts it away - it stays in the shade. A pointer over it
 * holds it; up to three stack, the newest nearest the bar.
 */
public final class NotifyPopup {

    /** On screen this long once nothing is over it, like the tablet's own. */
    private static final long SHOWN_MS = 6000L;
    private static final int MAX_CARDS = 3;
    private static final int WIDTH_DP = 360;
    private static final int GAP_DP = 8;
    private static final int MARGIN_DP = 12;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "zux-desktop-plus-notify-pop");
        t.setDaemon(true);
        return t;
    });

    private static boolean sStarted;
    /** The newest arrival asked for, as a post time: each is shown once. On the IO thread. */
    private static long sSince;

    /** The window the cards stack in, while any is up. */
    private static LinearLayout sStack;
    private static WindowManager sWm;
    private static WindowManager.LayoutParams sLp;
    private static int sDisplay = -1;
    private static final Map<String, Card> CARDS = new HashMap<>();

    private NotifyPopup() {
    }

    /**
     * Starts listening, once per launcher process, when the monitor's desktop first comes up;
     * not at all with the switch off.
     */
    public static synchronized void start(Context ctx) {
        if (sStarted || ctx == null || !Cfg.notifyPopups()) {
            return;
        }
        sStarted = true;
        Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        // Nothing from before: only what arrives from now on pops up.
        sSince = System.currentTimeMillis();
        try {
            app.getContentResolver().registerContentObserver(NotifyProvider.POSTED, false,
                    new ContentObserver(MAIN) {
                        @Override
                        public void onChange(boolean selfChange) {
                            arrived(app);
                        }
                    });
            L.i("notification pop-ups: listening for the monitor");
        } catch (Throwable t) {
            L.w("notification pop-ups: could not listen (" + t + ")");
        }
    }

    private static void arrived(Context app) {
        try {
            int display = DesktopHost.externalDisplay();
            Activity host = display >= 0 ? DesktopHost.activityOn(display) : null;
            if (host == null || !Cfg.notifyPopups() || NotifyPanel.current() != null) {
                // Not now - no desktop on the monitor, switched off, or the open panel already
                // shows it where the card would cover it - and not later either: what arrived
                // until now is passed over, so it does not pop up stale with the next one.
                long now = System.currentTimeMillis();
                IO.execute(() -> sSince = Math.max(sSince, now));
                return;
            }
            IO.execute(() -> {
                List<Bundle> pops = Notifications.pops(app, sSince);
                for (Bundle pop : pops) {
                    sSince = Math.max(sSince, pop.getLong(NotifyProvider.POP_WHEN));
                }
                if (!pops.isEmpty()) {
                    MAIN.post(() -> {
                        for (Bundle pop : pops) {
                            show(host, display, pop);
                        }
                    });
                }
            });
        } catch (Throwable t) {
            L.d("notification pop-ups: " + t);
        }
    }

    /** The window's root while any card is up, else null - for a screenshot to leave out. */
    public static View current() {
        return sStack;
    }

    /** All cards away at once: the monitor's desktop has gone. */
    public static void dismissAll() {
        for (Card card : new ArrayList<>(CARDS.values())) {
            MAIN.removeCallbacks(card.hide);
        }
        CARDS.clear();
        closeWindow();
    }

    private static void show(Activity host, int display, Bundle pop) {
        try {
            String key = pop.getString(NotifyProvider.POP_KEY);
            if (key == null) {
                return;
            }
            if (sStack != null && sDisplay != display) {
                dismissAll();
            }
            Card existing = CARDS.get(key);
            if (existing != null && !existing.leaving) {
                // An update to one already up: the same card, told again - unless a reply is
                // being typed into it, which is kept.
                if (!existing.replying) {
                    existing.fill(pop);
                    restartClock(existing);
                }
                return;
            }
            settle();
            if (sStack == null && !openWindow(host, display)) {
                return;
            }
            Context ctx = sStack.getContext();
            while (sStack.getChildCount() >= MAX_CARDS) {
                // The oldest, farthest from the bar, goes: nothing under it moves.
                Card oldest = (Card) sStack.getChildAt(0);
                remove(oldest);
            }
            Card card = new Card(ctx, display, pop);
            int width = Ui.dp(ctx, WIDTH_DP);
            card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            int step = card.getMeasuredHeight() + Ui.dp(ctx, GAP_DP);
            // The window grows upwards by the new card: those already up are drawn where they
            // were, then rise to make room while it slides in.
            for (int i = 0; i < sStack.getChildCount(); i++) {
                View above = sStack.getChildAt(i);
                above.setTranslationY(step);
                above.animate().translationY(0f).setDuration(Motion.IOS_MS)
                        .setInterpolator(Motion.IOS).start();
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(width,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Ui.dp(ctx, GAP_DP);
            sStack.addView(card, lp);
            CARDS.put(key, card);
            card.setTranslationX(width + Ui.dp(ctx, MARGIN_DP));
            card.setAlpha(0f);
            card.animate().translationX(0f).alpha(1f).setDuration(Motion.IOS_MS)
                    .setInterpolator(Motion.IOS).start();
            restartClock(card);
            L.i("notification pop-up: " + card.pkg + " on display " + display);
        } catch (Throwable t) {
            L.d("notification pop-up: could not show (" + t + ")");
        }
    }

    private static boolean openWindow(Activity host, int display) {
        Context ctx = Overlays.windowContext(host);
        WindowManager wm = Overlays.windowManager(ctx);
        LinearLayout stack = new LinearLayout(ctx);
        stack.setOrientation(LinearLayout.VERTICAL);
        stack.setClipChildren(false);
        // To the screen's edge, so a card swiped away leaves at the edge, not short of it.
        stack.setPadding(0, 0, Ui.dp(ctx, MARGIN_DP), 0);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        // From the screen's bottom-right corner, no insets: above the bar, under the tray.
        lp.gravity = Gravity.BOTTOM | Gravity.END;
        lp.setFitInsetsTypes(0);
        lp.x = 0;
        lp.y = aboveBar(ctx, display);
        lp.setTitle("ZuxOS Desktop Plus notification");
        FrameRate.forWindow(lp, wm.getDefaultDisplay());
        FrameRate.forView(stack);
        try {
            wm.addView(stack, lp);
        } catch (Throwable t) {
            L.d("notification pop-up: no window (" + t + ")");
            return false;
        }
        sStack = stack;
        sWm = wm;
        sLp = lp;
        sDisplay = display;
        return true;
    }

    /** How far up from the screen's bottom the cards start: over the bar, with a margin. */
    private static int aboveBar(Context ctx, int display) {
        int margin = Ui.dp(ctx, MARGIN_DP);
        int barTop = TaskbarTray.barTopOnScreen(display);
        int height = TaskbarTray.displayHeight(ctx);
        if (barTop > 0 && height > barTop) {
            return height - barTop + margin;
        }
        return Ui.dp(ctx, 56) + margin;
    }

    private static void closeWindow() {
        LinearLayout stack = sStack;
        WindowManager wm = sWm;
        sStack = null;
        sWm = null;
        sLp = null;
        sDisplay = -1;
        if (stack != null && wm != null) {
            try {
                wm.removeViewImmediate(stack);
            } catch (Throwable ignored) {
                // Already gone with its display.
            }
        }
    }

    private static void restartClock(Card card) {
        MAIN.removeCallbacks(card.hide);
        if (!card.replying) {
            MAIN.postDelayed(card.hide, SHOWN_MS);
        }
    }

    /**
     * Slides a card out to the right; those above it come down into its place as it goes.
     */
    private static void close(Card card) {
        if (card.leaving || card.getParent() != sStack || sStack == null) {
            return;
        }
        card.leaving = true;
        MAIN.removeCallbacks(card.hide);
        if (card.replying) {
            card.replying = false;
            focusable(false);
        }
        int index = sStack.indexOfChild(card);
        int step = card.getHeight() + Ui.dp(card.getContext(), GAP_DP);
        for (int i = 0; i < index; i++) {
            sStack.getChildAt(i).animate().translationY(step).setDuration(260L)
                    .setInterpolator(Motion.EASE).start();
        }
        card.animate().translationX(card.getWidth() + Ui.dp(card.getContext(), MARGIN_DP))
                .alpha(0.6f).setDuration(260L).setInterpolator(Motion.EXIT)
                .withEndAction(() -> remove(card)).start();
    }

    /** Takes the card out now; those above it are put back where the layout has them. */
    private static void remove(Card card) {
        MAIN.removeCallbacks(card.hide);
        if (CARDS.get(card.key) == card) {
            CARDS.remove(card.key);
        }
        LinearLayout stack = sStack;
        if (stack == null || card.getParent() != stack) {
            return;
        }
        int index = stack.indexOfChild(card);
        card.animate().cancel();
        stack.removeView(card);
        for (int i = 0; i < index && i < stack.getChildCount(); i++) {
            View above = stack.getChildAt(i);
            above.animate().cancel();
            above.setTranslationY(0f);
        }
        if (stack.getChildCount() == 0) {
            closeWindow();
        }
    }

    /** Ends what is moving, so a new card starts from where everything really is. */
    private static void settle() {
        for (Card card : new ArrayList<>(CARDS.values())) {
            if (card.leaving) {
                remove(card);
            }
        }
        if (sStack == null) {
            return;
        }
        for (int i = 0; i < sStack.getChildCount(); i++) {
            View card = sStack.getChildAt(i);
            card.animate().cancel();
            card.setTranslationY(0f);
            card.setTranslationX(0f);
            card.setAlpha(1f);
        }
    }

    /** The window takes the keyboard only while a reply is being typed. */
    private static void focusable(boolean on) {
        if (sStack == null || sWm == null || sLp == null) {
            return;
        }
        if (on) {
            sLp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        } else {
            sLp.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        }
        try {
            sWm.updateViewLayout(sStack, sLp);
        } catch (Throwable t) {
            L.d("notification pop-up: focus not changed (" + t + ")");
        }
    }

    /**
     * One card: the app, what it says, its buttons and a reply box. Holds itself while a pointer
     * is over it, and follows a finger or pen to the right to be put away.
     */
    private static final class Card extends FrameLayout {
        final String key;
        final String pkg;
        final int display;
        final Runnable hide = () -> close(this);
        boolean leaving;
        boolean replying;

        private final LinearLayout mColumn;
        private final int mSlop;
        private final float mFling;
        private float mDownX;
        private boolean mDragging;
        private VelocityTracker mVelocity;

        Card(Context ctx, int display, Bundle pop) {
            super(ctx);
            this.key = pop.getString(NotifyProvider.POP_KEY);
            this.pkg = pop.getString(NotifyProvider.POP_PACKAGE);
            this.display = display;
            mSlop = ViewConfiguration.get(ctx).getScaledTouchSlop();
            mFling = Ui.dp(ctx, 600);
            GlassSurface pane = new GlassSurface(ctx, Ui.dp(ctx, 18), Tone.panelTint(ctx),
                    LiquidGlass.MENU);
            mColumn = new LinearLayout(ctx);
            mColumn.setOrientation(LinearLayout.VERTICAL);
            int pad = Ui.dp(ctx, 12);
            mColumn.setPadding(pad, pad, pad, pad);
            pane.addView(mColumn, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            addView(pane, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            fill(pop);
        }

        /** What it shows, from the notification as it is now. */
        void fill(Bundle pop) {
            Context ctx = getContext();
            mColumn.removeAllViews();
            String title = pop.getString(NotifyProvider.POP_TITLE, "");
            String text = pop.getString(NotifyProvider.POP_TEXT, "");
            String app = Notifications.appName(ctx.getPackageManager(), pkg);

            LinearLayout top = new LinearLayout(ctx);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);
            top.setBackground(Ui.ripple(ctx, 0x00000000, Ui.dp(ctx, 12)));
            top.setOnClickListener(v -> {
                Notifications.open(ctx, key, pkg, display);
                close(this);
            });
            mColumn.addView(top);

            ImageView icon = new ImageView(ctx);
            Bitmap picture = pop.getParcelable(NotifyProvider.POP_PICTURE);
            if (picture != null) {
                icon.setImageBitmap(picture);
                icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
                icon.setOutlineProvider(new ViewOutlineProvider() {
                    @Override
                    public void getOutline(View view, Outline outline) {
                        outline.setOval(0, 0, view.getWidth(), view.getHeight());
                    }
                });
                icon.setClipToOutline(true);
            } else {
                icon.setImageDrawable(Notifications.appIcon(ctx.getPackageManager(), pkg));
            }
            int size = Ui.dp(ctx, 36);
            top.addView(icon, new LinearLayout.LayoutParams(size, size));

            LinearLayout words = new LinearLayout(ctx);
            words.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            wlp.leftMargin = Ui.dp(ctx, 10);
            top.addView(words, wlp);
            words.addView(label(ctx, app, 11, Ui.COLOR_TEXT_DIM, 1, false));
            if (!title.isEmpty()) {
                words.addView(label(ctx, title, 14, Ui.COLOR_TEXT, 1, true));
            }
            if (!text.isEmpty()) {
                words.addView(label(ctx, text, 13, Ui.COLOR_TEXT_DIM, 3, false));
            }

            ImageView cross = new ImageView(ctx);
            cross.setImageDrawable(TrayIcons.close(Ui.COLOR_TEXT_DIM));
            int button = Ui.dp(ctx, 26);
            int inset = Ui.dp(ctx, 6);
            cross.setPadding(inset, inset, inset, inset);
            cross.setBackground(Ui.ripple(ctx, 0x00000000, button / 2));
            cross.setContentDescription("Close");
            cross.setOnClickListener(v -> close(this));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(button, button);
            clp.gravity = Gravity.TOP;
            top.addView(cross, clp);

            List<Bundle> actions = pop.getParcelableArrayList(NotifyProvider.POP_ACTIONS);
            if (actions == null || actions.isEmpty()) {
                return;
            }
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.topMargin = Ui.dp(ctx, 10);
            mColumn.addView(row, rlp);
            for (Bundle action : actions) {
                TextView b = pill(ctx, action.getString(NotifyProvider.ACTION_TITLE, ""), false);
                b.setOnClickListener(v -> act(row, action));
                LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                blp.rightMargin = Ui.dp(ctx, 6);
                row.addView(b, blp);
            }
        }

        /** A button: sent as it is, or, for a reply, a box to type it in first. */
        private void act(LinearLayout row, Bundle action) {
            Context ctx = getContext();
            PendingIntent intent = action.getParcelable(NotifyProvider.ACTION_INTENT);
            RemoteInput input = action.getParcelable(NotifyProvider.ACTION_INPUT);
            if (intent == null) {
                return;
            }
            if (input == null) {
                boolean sent = Notifications.send(ctx, intent, null, display);
                L.i("notification pop-up: " + pkg + "'s button " + (sent ? "sent" : "gone"));
                close(this);
                return;
            }
            row.setVisibility(View.GONE);
            LinearLayout box = new LinearLayout(ctx);
            box.setOrientation(LinearLayout.HORIZONTAL);
            box.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            blp.topMargin = Ui.dp(ctx, 10);
            mColumn.addView(box, blp);

            EditText field = new EditText(ctx);
            field.setSingleLine(true);
            field.setImeOptions(EditorInfo.IME_ACTION_SEND);
            field.setTextColor(Ui.COLOR_TEXT);
            field.setHintTextColor(Ui.COLOR_TEXT_DIM);
            field.setTextSize(13);
            CharSequence hint = input.getLabel();
            field.setHint(hint != null && hint.length() > 0 ? hint : "Reply");
            int fpad = Ui.dp(ctx, 10);
            field.setPadding(fpad, Ui.dp(ctx, 7), fpad, Ui.dp(ctx, 7));
            field.setBackground(Ui.roundRect(0x1FFFFFFF, Ui.dp(ctx, 10)));
            box.addView(field, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView send = pill(ctx, "Send", true);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.leftMargin = Ui.dp(ctx, 6);
            box.addView(send, slp);

            Runnable reply = () -> reply(intent, input, field.getText());
            send.setOnClickListener(v -> reply.run());
            field.setOnEditorActionListener((v, id, event) -> {
                if (id == EditorInfo.IME_ACTION_SEND || (event != null
                        && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                        && event.getAction() == KeyEvent.ACTION_UP)) {
                    reply.run();
                    return true;
                }
                return false;
            });
            field.setOnKeyListener((v, code, event) -> {
                if (event.getAction() == KeyEvent.ACTION_UP
                        && (code == KeyEvent.KEYCODE_ESCAPE || code == KeyEvent.KEYCODE_BACK)) {
                    close(this);
                    return true;
                }
                return false;
            });

            replying = true;
            MAIN.removeCallbacks(hide);
            focusable(true);
            field.requestFocus();
            field.post(() -> {
                InputMethodManager imm = ctx.getSystemService(InputMethodManager.class);
                if (imm != null) {
                    imm.showSoftInput(field, 0);
                }
            });
        }

        private void reply(PendingIntent intent, RemoteInput input, CharSequence text) {
            if (text == null || text.toString().trim().isEmpty()) {
                return;
            }
            Intent fill = new Intent();
            Bundle results = new Bundle();
            results.putCharSequence(input.getResultKey(), text.toString());
            RemoteInput.addResultsToIntent(new RemoteInput[]{input}, fill, results);
            RemoteInput.setResultsSource(fill, RemoteInput.SOURCE_FREE_FORM_INPUT);
            boolean sent = Notifications.send(getContext(), intent, fill, display);
            L.i("notification pop-up: reply to " + pkg + " " + (sent ? "sent" : "refused"));
            close(this);
        }

        /**
         * Read where every hover event passes: the card alone is told it was left the moment
         * the pointer moves onto one of its own buttons.
         */
        @Override
        public boolean dispatchHoverEvent(MotionEvent e) {
            boolean inside = e.getX() >= 0 && e.getY() >= 0 && e.getX() < getWidth()
                    && e.getY() < getHeight();
            if (e.getActionMasked() == MotionEvent.ACTION_HOVER_EXIT && !inside) {
                restartClock(this);
            } else if (inside) {
                MAIN.removeCallbacks(hide);
            }
            return super.dispatchHoverEvent(e);
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent e) {
            track(e);
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                mDownX = e.getRawX();
                mDragging = false;
            } else if (e.getActionMasked() == MotionEvent.ACTION_MOVE && !mDragging
                    && e.getRawX() - mDownX > mSlop) {
                mDragging = true;
                MAIN.removeCallbacks(hide);
            }
            return mDragging;
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            track(e);
            float dx = e.getRawX() - mDownX;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    mDownX = e.getRawX();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!mDragging && dx > mSlop) {
                        mDragging = true;
                        MAIN.removeCallbacks(hide);
                    }
                    if (mDragging) {
                        // To the right it follows; to the left it only gives a little.
                        setTranslationX(dx > 0 ? dx : dx * 0.15f);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    float vx = 0f;
                    if (mVelocity != null) {
                        mVelocity.computeCurrentVelocity(1000);
                        vx = mVelocity.getXVelocity();
                        mVelocity.recycle();
                        mVelocity = null;
                    }
                    if (mDragging && (dx > getWidth() / 3f || vx > mFling)) {
                        close(this);
                    } else if (mDragging) {
                        animate().translationX(0f).setDuration(Motion.IOS_MS)
                                .setInterpolator(Motion.IOS).start();
                        restartClock(this);
                    }
                    mDragging = false;
                    return true;
                default:
                    return true;
            }
        }

        private void track(MotionEvent e) {
            if (mVelocity == null) {
                mVelocity = VelocityTracker.obtain();
            }
            // Raw coordinates: the card moves under the finger while it is dragged.
            MotionEvent raw = MotionEvent.obtain(e);
            raw.setLocation(e.getRawX(), e.getRawY());
            mVelocity.addMovement(raw);
            raw.recycle();
        }
    }

    private static TextView label(Context ctx, String text, float sp, int color, int lines,
            boolean bold) {
        TextView v = new TextView(ctx);
        v.setText(text);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setMaxLines(lines);
        v.setEllipsize(TextUtils.TruncateAt.END);
        if (bold) {
            v.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return v;
    }

    /** A button under the card: glass-white, or the accent for the one that sends. */
    private static TextView pill(Context ctx, String text, boolean accent) {
        TextView b = new TextView(ctx);
        b.setText(text);
        b.setTextSize(13);
        b.setTextColor(Ui.COLOR_TEXT);
        b.setGravity(Gravity.CENTER);
        b.setSingleLine(true);
        b.setEllipsize(TextUtils.TruncateAt.END);
        int h = Ui.dp(ctx, 12);
        int v = Ui.dp(ctx, 7);
        b.setPadding(h, v, h, v);
        b.setBackground(Ui.roundRect(accent ? Ui.COLOR_ACCENT : 0x1FFFFFFF, Ui.dp(ctx, 10)));
        b.setOnTouchListener(new TaskbarRunning.Press());
        b.setOnHoverListener((view, e) -> {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_HOVER_ENTER) {
                Hover.lift(view);
            } else if (a == MotionEvent.ACTION_HOVER_EXIT) {
                Hover.drop(view);
            }
            return false;
        });
        return b;
    }
}
