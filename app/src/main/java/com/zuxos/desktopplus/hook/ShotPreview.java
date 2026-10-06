package com.zuxos.desktopplus.hook;

import android.app.ActivityOptions;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
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
import com.zuxos.desktopplus.core.Ui;

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
    private static final Runnable HIDE = ShotPreview::dismiss;

    private ShotPreview() {
    }

    /** Main thread. {@code thumb} becomes this preview's, and is freed with it. */
    static void show(Context source, int display, Bitmap thumb, Uri uri) {
        dismissNow();
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
        picture.setContentDescription("Open the screenshot");
        picture.setOnClickListener(v -> act(ctx, display, view(uri)));
        picture.setOnTouchListener(new TaskbarRunning.Press());
        column.addView(picture, new LinearLayout.LayoutParams(thumbW, thumbH));

        LinearLayout actions = new LinearLayout(ctx);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = Ui.dp(ctx, 6);
        column.addView(actions, alp);
        actions.addView(button(ctx, Glyphs.EXPORT, "Share",
                () -> act(ctx, display, Intent.createChooser(share(uri), "Share screenshot"))));
        actions.addView(button(ctx, Glyphs.RENAME, "Edit",
                () -> act(ctx, display, Intent.createChooser(edit(uri), "Edit screenshot"))));
        actions.addView(button(ctx, Glyphs.REMOVE, "Delete", () -> delete(ctx, uri)));
        actions.addView(button(ctx, Glyphs.CLOSE, "Close", ShotPreview::dismiss));

        // A pointer anywhere over it - its buttons included - keeps it; leaving starts the
        // clock again. Read where every hover event passes, because the root alone is told it
        // was left the moment the pointer moves onto one of its own buttons.
        FrameLayout root = new FrameLayout(ctx) {
            @Override
            public boolean dispatchHoverEvent(MotionEvent e) {
                boolean inside = e.getX() >= 0 && e.getY() >= 0 && e.getX() < getWidth()
                        && e.getY() < getHeight();
                if (e.getActionMasked() == MotionEvent.ACTION_HOVER_EXIT && !inside) {
                    MAIN.removeCallbacks(HIDE);
                    MAIN.postDelayed(HIDE, SHOWN_MS);
                } else if (inside) {
                    MAIN.removeCallbacks(HIDE);
                }
                return super.dispatchHoverEvent(e);
            }
        };
        root.addView(pane, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

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

        // In from the left edge on iOS's spring.
        pane.setTranslationX(-(root.getMeasuredWidth() + margin));
        pane.animate().translationX(0f).setDuration(Motion.IOS_MS)
                .setInterpolator(Motion.IOS).withLayer().start();
        MAIN.postDelayed(HIDE, SHOWN_MS);
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
        b.setOnClickListener(v -> {
            try {
                action.run();
            } catch (Throwable t) {
                L.d("screenshot preview: " + description + " failed (" + t + ")");
            }
        });
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
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        send.setClipData(ClipData.newRawUri("", uri));
        return send;
    }

    private static Intent edit(Uri uri) {
        return new Intent(Intent.ACTION_EDIT).setDataAndType(uri, "image/png")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    }

    /** Opens on the screen the picture was taken on, and the preview steps aside. */
    private static void act(Context ctx, int display, Intent intent) {
        dismiss();
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ActivityOptions options = ActivityOptions.makeBasic().setLaunchDisplayId(display);
            ctx.startActivity(intent, options.toBundle());
        } catch (Throwable t) {
            L.d("screenshot preview: nothing to open it with (" + t + ")");
            toast(ctx, "No app can open this");
        }
    }

    private static void delete(Context ctx, Uri uri) {
        dismiss();
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
        View root = sRoot;
        WindowManager wm = sWm;
        Bitmap thumb = sThumb;
        clear();
        if (root == null || wm == null) {
            return;
        }
        View pane = root instanceof ViewGroup && ((ViewGroup) root).getChildCount() > 0
                ? ((ViewGroup) root).getChildAt(0) : root;
        pane.animate().translationX(-(root.getWidth() + Ui.dp(root.getContext(), 16)))
                .setDuration(260L).setInterpolator(Motion.EXIT).withLayer()
                .withEndAction(() -> remove(wm, root, thumb)).start();
    }

    private static void dismissNow() {
        View root = sRoot;
        WindowManager wm = sWm;
        Bitmap thumb = sThumb;
        clear();
        if (root != null && wm != null) {
            remove(wm, root, thumb);
        }
    }

    private static void clear() {
        MAIN.removeCallbacks(HIDE);
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
