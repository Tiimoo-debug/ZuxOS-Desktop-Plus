package com.zuxos.desktopplus.hook;

import android.app.ActivityOptions;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.zuxos.desktopplus.core.FrameRate;
import com.zuxos.desktopplus.core.GlassSurface;
import com.zuxos.desktopplus.core.Glyphs;
import com.zuxos.desktopplus.core.Hover;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.LiquidGlass;
import com.zuxos.desktopplus.core.Motion;
import com.zuxos.desktopplus.core.Reflect;
import com.zuxos.desktopplus.core.Ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The picture just taken, in the bottom-left corner of the screen it was taken on, for a few
 * seconds - the way Android shows its own screenshots, which it only ever does on the tablet.
 *
 * <p>Click the picture to open it; Share, Edit and Delete beside it. A pointer over it holds it
 * on screen; it slides away on its own once left alone.
 */
final class ShotPreview {

    /** On screen this long once nothing is over it, like Android's own. */
    private static final long SHOWN_MS = 6000L;
    private static final int THUMB_DP = 200;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static View sRoot;
    private static WindowManager sWm;
    private static Bitmap sThumb;
    /** The flying copy while it is up. */
    private static View sFly;
    private static final Runnable HIDE = ShotPreview::dismiss;

    private ShotPreview() {
    }

    /**
     * One screenshot: shown the moment it is captured, saved a moment later. What is asked of
     * it before the file exists waits for it.
     */
    static final class Shot {
        /** Set on the main thread once the file exists. */
        Uri uri;
        boolean failed;
        /** Deleted before it was saved: the saver drops it. Read from the saver's thread. */
        volatile boolean cancelled;
        /** What was asked for before it was saved. */
        Runnable waiting;
    }

    private static Shot sShot;

    /** The file is there: anything waiting on it runs now. Main thread. */
    static void saved(Shot shot, Uri uri) {
        shot.uri = uri;
        Runnable waiting = shot.waiting;
        shot.waiting = null;
        if (waiting != null) {
            waiting.run();
        }
    }

    /** It could not be saved: the card goes, and says so. Main thread. */
    static void failed(Context ctx, Shot shot) {
        shot.failed = true;
        shot.waiting = null;
        if (sShot == shot) {
            dismiss();
        }
        toast(ctx, "Could not save the screenshot");
    }

    /** Runs {@code action} with the file, now or once it is saved. */
    private static void whenSaved(Shot shot, java.util.function.Consumer<Uri> action) {
        if (shot.uri != null) {
            action.accept(shot.uri);
        } else if (!shot.failed) {
            shot.waiting = () -> action.accept(shot.uri);
        }
    }

    /**
     * Main thread. {@code thumb} - the capture itself, straight from the screen - becomes this
     * preview's, and is freed with it.
     */
    static void show(Context source, int display, Bitmap thumb, Shot shot) {
        dismissNow();
        sShot = shot;
        Context ctx = Overlays.windowContext(source);
        WindowManager wm = Overlays.windowManager(ctx);
        int pad = Ui.dp(ctx, 8);
        int thumbW = Ui.dp(ctx, THUMB_DP);
        int thumbH = Math.round(thumbW * thumb.getHeight() / (float) Math.max(1,
                thumb.getWidth()));

        GlassSurface pane = new GlassSurface(ctx, Ui.dp(ctx, 16), 0x401C1C22, LiquidGlass.MENU);
        LinearLayout column = new LinearLayout(ctx);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(pad, pad, pad, pad);
        pane.addView(column, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        ImageView picture = new ImageView(ctx);
        picture.setImageBitmap(thumb);
        picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
        float corner = Ui.dp(ctx, 10);
        picture.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), corner);
            }
        });
        picture.setClipToOutline(true);
        // The thin white edge iOS gives a screenshot, so it reads as a picture on the glass.
        GradientDrawable edge = new GradientDrawable();
        edge.setCornerRadius(corner);
        edge.setStroke(Math.max(1, Ui.dp(ctx, 1.5f)), 0xD9FFFFFF);
        picture.setForeground(edge);
        picture.setContentDescription("Open the screenshot");
        picture.setOnClickListener(v -> whenSaved(shot, uri -> {
            dismiss();
            launch(ctx, display, view(uri), true, "open");
        }));
        picture.setOnTouchListener(new TaskbarRunning.Press());
        column.addView(picture, new LinearLayout.LayoutParams(thumbW, thumbH));

        LinearLayout actions = new LinearLayout(ctx);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = Ui.dp(ctx, 6);
        column.addView(actions, alp);
        View[] card = new View[1];
        View shareButton = button(ctx, Glyphs.SHARE, "Share", null);
        shareButton.setOnClickListener(v -> sheet(ctx, display, shot, card[0], v, false));
        View editButton = button(ctx, Glyphs.RENAME, "Edit", null);
        editButton.setOnClickListener(v -> sheet(ctx, display, shot, card[0], v, true));
        actions.addView(shareButton);
        actions.addView(editButton);
        actions.addView(button(ctx, Glyphs.REMOVE, "Delete", () -> delete(ctx, shot)));
        actions.addView(button(ctx, Glyphs.CLOSE, "Close", ShotPreview::dismiss));

        FrameLayout root = new Root(ctx, pane);
        card[0] = pane;

        root.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int margin = Ui.dp(ctx, 16);
        int barTop = TaskbarTray.barTopOnScreen(display);
        if (barTop <= 0) {
            barTop = TaskbarTray.displayHeight(ctx) - Ui.dp(ctx, 56);
        }
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        // Absolute screen pixels from the top, no insets: the same placement as the preview.
        lp.gravity = Gravity.TOP | Gravity.START;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            lp.setFitInsetsTypes(0);
        }
        lp.x = margin;
        lp.y = Math.max(margin, barTop - root.getMeasuredHeight() - margin);
        lp.setTitle("ZuxOS Desktop Plus screenshot");
        FrameRate.forWindow(lp, wm.getDefaultDisplay());
        FrameRate.forView(root);
        try {
            wm.addView(root, lp);
        } catch (Throwable t) {
            L.d("screenshot preview: could not show (" + t + ")");
            thumb.recycle();
            return;
        }
        sRoot = root;
        sWm = wm;
        sThumb = thumb;

        // The iOS way: a flash, then the whole screen shrinks into the corner on a spring and
        // the glass card forms around it. Without the flying copy, in from the left edge.
        if (!fly(ctx, wm, thumb, lp.x + pad, lp.y + pad, thumbW, thumbH, corner, pane)) {
            pane.setTranslationX(-(root.getMeasuredWidth() + margin));
            pane.animate().translationX(0f).setDuration(Motion.IOS_MS)
                    .setInterpolator(Motion.IOS).withLayer().start();
        }
        MAIN.postDelayed(HIDE, SHOWN_MS);
    }

    /**
     * The screen, as just taken, shrinking from full size into the thumbnail's place - in a
     * window of its own over everything, which takes no touches and is gone once it lands.
     *
     * @return false when it could not be shown; the card then comes in by itself
     */
    private static boolean fly(Context ctx, WindowManager wm, Bitmap shot, int toX, int toY,
            int toW, int toH, float corner, View card) {
        android.graphics.Rect screen;
        try {
            screen = wm.getCurrentWindowMetrics().getBounds();
        } catch (Throwable t) {
            return false;
        }
        int w = screen.width();
        int h = screen.height();
        if (w <= 0 || h <= 0) {
            return false;
        }
        FrameLayout layer = new FrameLayout(ctx);
        View flash = new View(ctx);
        flash.setBackgroundColor(0xFFFFFFFF);
        flash.setAlpha(0f);
        layer.addView(flash, new FrameLayout.LayoutParams(w, h));
        ImageView copy = new ImageView(ctx);
        copy.setImageBitmap(shot);
        copy.setScaleType(ImageView.ScaleType.CENTER_CROP);
        float[] radius = {0f};
        copy.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius[0]);
            }
        });
        copy.setClipToOutline(true);
        copy.setPivotX(0f);
        copy.setPivotY(0f);
        layer.addView(copy, new FrameLayout.LayoutParams(w, h));

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(w, h,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.setFitInsetsTypes(0);
        lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        lp.setTitle("ZuxOS Desktop Plus screenshot flash");
        FrameRate.forWindow(lp, wm.getDefaultDisplay());
        try {
            wm.addView(layer, lp);
        } catch (Throwable t) {
            L.d("screenshot preview: no flash (" + t + ")");
            return false;
        }
        sFly = layer;
        card.setAlpha(0f);
        card.setScaleX(0.96f);
        card.setScaleY(0.96f);

        // The shutter: up fast, away slower.
        flash.animate().alpha(0.55f).setDuration(70L).setInterpolator(Motion.EASE)
                .withEndAction(() -> flash.animate().alpha(0f).setDuration(240L)
                        .setInterpolator(Motion.EASE).start()).start();

        float endScale = toW / (float) w;
        // Corners in the copy's own pixels: it is drawn scaled, so they are divided back out.
        android.animation.ValueAnimator shrink = android.animation.ValueAnimator.ofFloat(0f, 1f);
        shrink.setDuration(Motion.SMOOTH_MS);
        shrink.setInterpolator(Motion.SMOOTH);
        shrink.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            float scale = 1f + (endScale - 1f) * f;
            copy.setScaleX(scale);
            copy.setScaleY(scale);
            copy.setTranslationX(toX * f);
            copy.setTranslationY(toY * f + (toH - h * endScale) / 2f * f);
            radius[0] = corner * f / Math.max(scale, 0.01f);
            copy.invalidateOutline();
        });
        shrink.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                // The card takes over where the copy landed, then the copy goes.
                card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(Motion.IOS_MS)
                        .setInterpolator(Motion.IOS).withLayer().start();
                copy.animate().alpha(0f).setDuration(140L).setStartDelay(60L)
                        .withEndAction(() -> removeFly(wm, layer)).start();
            }
        });
        shrink.start();
        return true;
    }

    private static void removeFly(WindowManager wm, View layer) {
        if (sFly == layer) {
            sFly = null;
        }
        try {
            wm.removeViewImmediate(layer);
        } catch (Throwable ignored) {
            // Already gone.
        }
    }

    /**
     * The card's window root: holds it while a pointer is anywhere over it, and lets it be
     * swiped away to the left, following the finger or pen, the way iOS's is.
     */
    private static final class Root extends FrameLayout {
        private final View mPane;
        private final int mSlop;
        private final float mFling;
        private float mDownX;
        private boolean mDragging;
        private android.view.VelocityTracker mVelocity;

        Root(Context ctx, View pane) {
            super(ctx);
            mPane = pane;
            mSlop = android.view.ViewConfiguration.get(ctx).getScaledTouchSlop();
            mFling = Ui.dp(ctx, 600);
            addView(pane, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        /**
         * Read where every hover event passes: the root alone is told it was left the moment
         * the pointer moves onto one of its own buttons.
         */
        @Override
        public boolean dispatchHoverEvent(MotionEvent e) {
            boolean inside = e.getX() >= 0 && e.getY() >= 0 && e.getX() < getWidth()
                    && e.getY() < getHeight();
            if (e.getActionMasked() == MotionEvent.ACTION_HOVER_EXIT && !inside
                    && !ShotTargets.showing()) {
                // Into the share sheet is not leaving: it restarts the clock when it closes.
                MAIN.removeCallbacks(HIDE);
                MAIN.postDelayed(HIDE, SHOWN_MS);
            } else if (inside) {
                MAIN.removeCallbacks(HIDE);
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
                    && mDownX - e.getRawX() > mSlop) {
                mDragging = true;
                MAIN.removeCallbacks(HIDE);
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
                    if (!mDragging && -dx > mSlop) {
                        mDragging = true;
                        MAIN.removeCallbacks(HIDE);
                    }
                    if (mDragging) {
                        // To the left it follows; to the right it only gives a little.
                        mPane.setTranslationX(dx < 0 ? dx : dx * 0.15f);
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
                    if (mDragging && (dx < -getWidth() / 3f || vx < -mFling)) {
                        dismiss();
                    } else if (mDragging) {
                        mPane.animate().translationX(0f).setDuration(Motion.IOS_MS)
                                .setInterpolator(Motion.IOS).start();
                        MAIN.postDelayed(HIDE, SHOWN_MS);
                    }
                    mDragging = false;
                    return true;
                default:
                    return true;
            }
        }

        private void track(MotionEvent e) {
            if (mVelocity == null) {
                mVelocity = android.view.VelocityTracker.obtain();
            }
            // Raw coordinates: the card moves under the finger while it is dragged.
            MotionEvent raw = MotionEvent.obtain(e);
            raw.setLocation(e.getRawX(), e.getRawY());
            mVelocity.addMovement(raw);
            raw.recycle();
        }
    }

    private static View button(Context ctx, int glyph, String description, Runnable action) {
        ImageView b = new ImageView(ctx);
        Drawable icon = Glyphs.of(glyph);
        if (icon != null) {
            icon.setTint(0xF2FFFFFF);
        }
        b.setImageDrawable(icon);
        int inset = Ui.dp(ctx, 8);
        b.setPadding(inset, inset, inset, inset);
        GradientDrawable round = new GradientDrawable();
        round.setShape(GradientDrawable.OVAL);
        round.setColor(0x1FFFFFFF);
        b.setBackground(round);
        b.setContentDescription(description);
        if (action != null) {
            b.setOnClickListener(v -> {
                try {
                    action.run();
                } catch (Throwable t) {
                    L.d("screenshot preview: " + description + " failed (" + t + ")");
                }
            });
        }
        b.setOnTouchListener(new TaskbarRunning.Press());
        b.setOnHoverListener((v, e) -> {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_HOVER_ENTER) {
                Hover.lift(v);
            } else if (a == MotionEvent.ACTION_HOVER_EXIT) {
                Hover.drop(v);
            }
            return false;
        });
        int size = Ui.dp(ctx, 36);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.leftMargin = lp.rightMargin = Ui.dp(ctx, 4);
        b.setLayoutParams(lp);
        return b;
    }

    private static Intent view(Uri uri) {
        return new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/png")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }

    private static Intent share(Uri uri) {
        Intent send = new Intent(Intent.ACTION_SEND).setType("image/png")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (uri != null) {
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.setClipData(ClipData.newRawUri("", uri));
        }
        return send;
    }

    /** Before the file exists the apps are found by a stand-in of the same kind. */
    private static final Uri ANY_IMAGE = Uri.parse("content://media/external/images/media/0");

    private static Intent edit(Uri uri) {
        return new Intent(Intent.ACTION_EDIT)
                .setDataAndType(uri != null ? uri : ANY_IMAGE, "image/png")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    }

    /**
     * Share or Edit: our own glass sheet of the apps that can, instead of Android's chooser.
     * The sheet opens at once; the app picked gets the file once it is saved.
     */
    private static void sheet(Context ctx, int display, Shot shot, View card, View from,
            boolean editing) {
        Intent probe = editing ? edit(shot.uri) : share(shot.uri);
        List<ResolveInfo> targets;
        try {
            targets = ctx.getPackageManager().queryIntentActivities(probe, 0);
        } catch (Throwable t) {
            targets = new ArrayList<>();
        }
        if (targets.isEmpty()) {
            toast(ctx, editing ? "No app can edit pictures" : "No app can share pictures");
            return;
        }
        Collections.sort(targets, (a, b) -> String.valueOf(a.loadLabel(ctx.getPackageManager()))
                .compareToIgnoreCase(String.valueOf(b.loadLabel(ctx.getPackageManager()))));
        MAIN.removeCallbacks(HIDE);
        ShotTargets.show(ctx, sWm, card, from, editing ? "Edit with" : "Share",
                editing ? "edit" : "share", targets,
                target -> whenSaved(shot, uri -> {
                    dismiss();
                    Intent intent = editing ? edit(uri) : share(uri);
                    intent.setComponent(new ComponentName(target.activityInfo.packageName,
                            target.activityInfo.name));
                    // An editor full screen, as on iOS - and ZUI's refuses to run as a window.
                    // A share target opens as the window it is used to being.
                    launch(ctx, display, intent, editing, editing ? "edit" : "share");
                }),
                () -> {
                    if (sShot == shot) {
                        MAIN.removeCallbacks(HIDE);
                        MAIN.postDelayed(HIDE, SHOWN_MS);
                    }
                });
    }

    /**
     * Starts it on the screen the picture was taken on.
     *
     * <p>{@code fullscreen}: ZUI's desktop makes every new task a floating window, and its photo
     * editor closes itself at once in one ("not support split screen", 17:38 log). Asked for full
     * screen, it opens. The mode the task really got is logged half a second later.
     */
    private static void launch(Context ctx, int display, Intent intent, boolean fullscreen,
            String what) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ActivityOptions options = ActivityOptions.makeBasic().setLaunchDisplayId(display);
            if (fullscreen) {
                Reflect.call(options, "setLaunchWindowingMode", WINDOWING_MODE_FULLSCREEN);
            }
            ctx.startActivity(intent, options.toBundle());
        } catch (Throwable t) {
            L.d("screenshot preview: could not " + what + " (" + t + ")");
            toast(ctx, "No app can open this");
            return;
        }
        String pkg = intent.getComponent() != null ? intent.getComponent().getPackageName()
                : null;
        MAIN.postDelayed(() -> L.i("screenshot preview: " + what + " -> "
                + (pkg != null ? pkg : "default app") + " " + modeOf(ctx, pkg, display)), 500L);
    }

    private static final int WINDOWING_MODE_FULLSCREEN = 1;

    /** The windowing mode the app's task on that screen ended up in, for the log. */
    private static String modeOf(Context ctx, String pkg, int display) {
        if (pkg == null) {
            return "";
        }
        try {
            android.app.ActivityManager am = (android.app.ActivityManager)
                    ctx.getSystemService(Context.ACTIVITY_SERVICE);
            for (android.app.ActivityManager.RunningTaskInfo task : am.getRunningTasks(20)) {
                Object d = Reflect.field(task, "displayId");
                if (pkg.equals(TaskbarApps.packageOf(task))
                        && d instanceof Integer && (Integer) d == display) {
                    return "in mode " + Probe.windowingMode(task);
                }
            }
            return "not running (it closed itself)";
        } catch (Throwable t) {
            return "";
        }
    }

    /** Deleted before it was saved: never written. After: removed from the media store. */
    private static void delete(Context ctx, Shot shot) {
        dismiss();
        if (shot.uri == null) {
            shot.cancelled = true;
            shot.waiting = null;
            toast(ctx, "Screenshot deleted");
            return;
        }
        Uri uri = shot.uri;
        new Thread(() -> {
            boolean gone;
            try {
                // The launcher filed it, so it is the launcher's to delete.
                gone = ctx.getContentResolver().delete(uri, null, null) > 0;
            } catch (Throwable t) {
                gone = false;
            }
            boolean deleted = gone;
            MAIN.post(() -> toast(ctx, deleted ? "Screenshot deleted"
                    : "Could not delete the screenshot"));
        }, "zux-desktop-plus-shot-delete").start();
    }

    private static void toast(Context ctx, String message) {
        try {
            Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
            L.w(message);
        }
    }

    /** Slides back out to the left. */
    static void dismiss() {
        if (ShotTargets.showing()) {
            ShotTargets.dismiss();
        }
        View root = sRoot;
        WindowManager wm = sWm;
        Bitmap thumb = sThumb;
        if (sFly != null && wm != null) {
            // The flying copy draws the same picture, which is freed with the card.
            removeFly(wm, sFly);
        }
        clear();
        if (root == null || wm == null) {
            return;
        }
        View pane = root instanceof ViewGroup && ((ViewGroup) root).getChildCount() > 0
                ? ((ViewGroup) root).getChildAt(0) : root;
        pane.animate().translationX(-(root.getWidth() + Ui.dp(root.getContext(), 16)))
                .alpha(0.6f).setDuration(260L).setInterpolator(Motion.EXIT).withLayer()
                .withEndAction(() -> remove(wm, root, thumb)).start();
    }

    private static void dismissNow() {
        if (ShotTargets.showing()) {
            ShotTargets.dismiss();
        }
        View root = sRoot;
        WindowManager wm = sWm;
        Bitmap thumb = sThumb;
        if (sFly != null && wm != null) {
            removeFly(wm, sFly);
        }
        clear();
        if (root != null && wm != null) {
            remove(wm, root, thumb);
        }
    }

    private static void clear() {
        MAIN.removeCallbacks(HIDE);
        sShot = null;
        sRoot = null;
        sWm = null;
        sThumb = null;
    }

    private static void remove(WindowManager wm, View root, Bitmap thumb) {
        try {
            wm.removeViewImmediate(root);
        } catch (Throwable ignored) {
            // Already gone.
        }
        if (thumb != null) {
            // After the window is gone, so nothing is still drawing it.
            MAIN.post(thumb::recycle);
        }
    }

    /** Its window's root while it is up, else null - for a screenshot to leave out. */
    static View current() {
        return sRoot;
    }
}
