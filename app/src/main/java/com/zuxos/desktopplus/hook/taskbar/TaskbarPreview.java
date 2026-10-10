package com.zuxos.desktopplus.hook.taskbar;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Insets;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;
import com.zuxos.desktopplus.core.Ui;
import com.zuxos.desktopplus.core.glass.GlassSurface;
import com.zuxos.desktopplus.core.glass.LiquidGlass;
import com.zuxos.desktopplus.core.glass.ScreenBackdrop;
import com.zuxos.desktopplus.core.icons.PixelIcons;
import com.zuxos.desktopplus.core.motion.FrameRate;
import com.zuxos.desktopplus.core.motion.Hover;
import com.zuxos.desktopplus.core.motion.Motion;
import com.zuxos.desktopplus.core.theme.Bevel;
import com.zuxos.desktopplus.core.theme.Theme;
import com.zuxos.desktopplus.hook.HoverTile;
import com.zuxos.desktopplus.hook.Overlays;
import com.zuxos.desktopplus.hook.Tasks;
import com.zuxos.desktopplus.hook.recents.TaskOverview;
import com.zuxos.desktopplus.logic.BevelMath;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The taskbar under a mouse or a stylus, the way Windows does it: an icon lifts and jiggles the
 * moment the pointer reaches it, and if the pointer stays, a pane of glass rises above it with
 * every window that app has open on this screen - live while they are on screen - to switch to
 * or close.
 */
public final class TaskbarPreview {

    private static final long OPEN_AFTER_MS = 450L;
    private static final long LEAVE_GRACE_MS = 120L;
    private static final long CLOSE_GRACE_MS = 250L;
    /** How far from an icon still counts as on it - the gaps between icons included. */
    private static final int NEAR_DP = 12;
    private static final int TILE_DP = 240;
    private static final long FRAME_MS = 33L;
    private static final float FEED_SCALE = 0.4f;

    /** Room round a tile for its hover highlight. */
    private static final int TILE_PAD_DP = 6;
    /** Retro's window edge: the bevel's width, and the gap under its title bar. */
    private static final int RETRO_EDGE_DP = 3;
    /** Windows 98's title bar of a window not in front. */
    private static final int RETRO_INACTIVE = BevelMath.shadow(BevelMath.FACE);

    private static final int TAG_PKG = 0x7A000201;
    private static final int TAG_DISPLAY = 0x7A000202;
    private static final int TAG_TASK = 0x7A000203;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "zux-preview");
        t.setDaemon(true);
        return t;
    });

    /** The icon under the pointer, if any. */
    private static View sIcon;
    private static Runnable sOpen;
    private static Runnable sLeave;
    private static Runnable sClose;
    private static boolean sOverPane;
    private static FrameLayout sRoot;

    /** Its window's root while it is up, else null - for a screenshot to leave out. */
    public static View current() {
        return sRoot;
    }
    private static WindowManager sWm;
    /** The icon whose window the preview shows. */
    private static View sShown;
    /** The look of the preview up now, for its going. */
    private static Theme sTheme = Theme.GLASS;

    private TaskbarPreview() {
    }

    /** Only the lift and jiggle - for what has no windows of its own, a folder. */
    static void attachHover(View icon) {
        icon.setOnHoverListener((v, e) -> {
            int action = e.getActionMasked();
            if (action == MotionEvent.ACTION_HOVER_ENTER) {
                if (sIcon != null && sIcon != v) {
                    Hover.exit(sIcon);
                    sIcon = null;
                }
                dismiss();
                lift(v);
            } else if (action == MotionEvent.ACTION_HOVER_EXIT) {
                Hover.exit(v);
            }
            return false;
        });
    }

    /** Gives an icon its hover: the lift and jiggle, and the preview of its app's windows. */
    static void attach(View icon, String pkg, int displayId) {
        attach(icon, pkg, displayId, -1);
    }

    /**
     * The same for one window's icon: its preview is that window alone. An app's own icon, when
     * the app has several windows, previews its front one - each of the others has an icon of its
     * own beside it.
     */
    static void attach(View icon, String pkg, int displayId, int taskId) {
        icon.setTag(TAG_PKG, pkg);
        icon.setTag(TAG_DISPLAY, displayId);
        icon.setTag(TAG_TASK, taskId);
        icon.setOnHoverListener((v, e) -> {
            int action = e.getActionMasked();
            if (action == MotionEvent.ACTION_HOVER_ENTER) {
                on(v);
            } else if (action == MotionEvent.ACTION_HOVER_EXIT) {
                off(v);
            }
            return false;
        });
    }

    /**
     * The pointer is over the bar's row but between icons: the nearest icon within reach counts
     * as hovered, so sliding along the bar never drops out of it.
     */
    static void rowHover(ViewGroup row, MotionEvent e) {
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_HOVER_EXIT) {
            // Off the row - or onto one of its icons, which takes the hover from the row. Looked
            // at once that has settled: an icon the pointer is now on keeps its preview; one
            // only counted as hovered from the gap beside it lets it go. Ignoring this left the
            // preview open, and the icon lifted, after the pointer had gone up into the app.
            row.post(() -> leaveRow(row));
            return;
        }
        int near = Ui.dp(row.getContext(), NEAR_DP);
        View best = null;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE || child.getTag(TAG_PKG) == null) {
                continue;
            }
            float d = Math.max(0f, Math.max(child.getLeft() - e.getX(),
                    e.getX() - child.getRight()));
            if (d <= near && d < bestDistance) {
                best = child;
                bestDistance = d;
            }
        }
        if (best != null) {
            on(best);
        } else {
            leaveRow(row);
        }
    }

    /** The row's hovered icon goes, unless the pointer is on it itself. */
    private static void leaveRow(ViewGroup row) {
        View icon = sIcon;
        if (icon != null && icon.getParent() == row && !icon.isHovered()) {
            off(icon);
        }
    }

    /** The lift and jiggle under the pointer - not on a Retro bar, where nothing moves. */
    private static void lift(View icon) {
        if (!Theme.of(icon).retro()) {
            Hover.enter(icon);
        }
    }

    /** A press on the icon: no preview over what is being opened, and the icon at rest. */
    static void pressed(View icon) {
        cancel(sOpen);
        sOpen = null;
        icon.setRotation(0f);
        dismiss();
    }

    private static void on(View icon) {
        cancel(sLeave);
        sLeave = null;
        cancel(sClose);
        sClose = null;
        if (icon == sIcon) {
            return;
        }
        if (sIcon != null) {
            Hover.exit(sIcon);
        }
        sIcon = icon;
        lift(icon);
        String pkg = (String) icon.getTag(TAG_PKG);
        cancel(sOpen);
        if (sRoot != null) {
            // A preview is up: moving along the bar swaps it straight away, as Windows does.
            if (icon != sShown) {
                show(icon);
            }
            return;
        }
        sOpen = () -> {
            sOpen = null;
            if (sIcon == icon) {
                show(icon);
            }
        };
        MAIN.postDelayed(sOpen, OPEN_AFTER_MS);
    }

    private static void off(View icon) {
        cancel(sLeave);
        sLeave = () -> {
            sLeave = null;
            if (sIcon != icon) {
                return;
            }
            Hover.exit(icon);
            sIcon = null;
            cancel(sOpen);
            sOpen = null;
            closeSoon();
        };
        MAIN.postDelayed(sLeave, LEAVE_GRACE_MS);
    }

    private static void closeSoon() {
        cancel(sClose);
        sClose = () -> {
            sClose = null;
            if (sIcon == null && !sOverPane) {
                dismiss();
            }
        };
        MAIN.postDelayed(sClose, CLOSE_GRACE_MS);
    }

    private static void cancel(Runnable r) {
        if (r != null) {
            MAIN.removeCallbacks(r);
        }
    }

    /** One window of the app, as a tile. */
    private static final class Tile {
        int taskId;
        boolean visible;
        View view;
        ImageView thumb;
        TaskOverview.CropDrawable crop;
        final ArrayDeque<Bitmap> frames = new ArrayDeque<>();
        int fed;
    }

    /**
     * The window an icon stands for: its own, or its app's first when the app has several; -1
     * when the app has one or none here.
     */
    static int windowOf(View icon, int display) {
        Object tag = icon.getTag(TAG_TASK);
        int taskId = tag instanceof Integer ? (Integer) tag : -1;
        Object pkg = icon.getTag(TAG_PKG);
        return taskId < 0 && pkg instanceof String
                ? TaskbarRunning.firstWindow((String) pkg, display) : taskId;
    }

    private static void show(View icon) {
        String pkg = (String) icon.getTag(TAG_PKG);
        Object d = icon.getTag(TAG_DISPLAY);
        int display = d instanceof Integer ? (Integer) d : -1;
        if (pkg == null || display < 0) {
            return;
        }
        Context ctx = Overlays.windowContext(icon.getContext());
        int taskId = windowOf(icon, display);
        List<ActivityManager.RunningTaskInfo> windows = new ArrayList<>();
        for (ActivityManager.RunningTaskInfo task : TaskbarApps.tasksOn(ctx, display)) {
            if (!pkg.equals(Tasks.packageOf(task))) {
                continue;
            }
            // One icon, one window: this icon's own task, or the app's only one.
            if (taskId < 0 ? windows.isEmpty() : task.taskId == taskId) {
                windows.add(task);
            }
        }
        dismissNow();
        if (windows.isEmpty()) {
            // Pinned, not open: the lift is all it gets.
            return;
        }
        try {
            build(ctx, icon, pkg, display, windows);
        } catch (Throwable t) {
            L.d("taskbar preview: could not show (" + t + ")");
        }
    }

    private static void build(Context ctx, View icon, String pkg, int display,
            List<ActivityManager.RunningTaskInfo> windows) {
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        int tileW = Ui.dp(ctx, TILE_DP);
        int tileH = Math.round(tileW * (float) dm.heightPixels / Math.max(1, dm.widthPixels));
        int pad = Ui.dp(ctx, 12);
        int gap = Ui.dp(ctx, 10);
        CharSequence label = pkg;
        Drawable appIcon = null;
        int uid = -1;
        try {
            android.content.pm.ApplicationInfo info =
                    ctx.getPackageManager().getApplicationInfo(pkg, 0);
            label = ctx.getPackageManager().getApplicationLabel(info);
            appIcon = ctx.getPackageManager().getApplicationIcon(info);
            uid = info.uid;
        } catch (Throwable ignored) {
            // Shown by its package name.
        }

        Theme theme = Theme.of(display);
        sTheme = theme;
        GlassSurface pane = new GlassSurface(ctx, Ui.dp(ctx, 18), 0x401C1C22,
                LiquidGlass.MENU).theme(theme);
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(pad, pad, pad, pad);
        pane.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        List<Tile> tiles = new ArrayList<>();
        int visibleCount = 0;
        for (ActivityManager.RunningTaskInfo task : windows) {
            Tile tile = new Tile();
            tile.taskId = task.taskId;
            tile.visible = Boolean.TRUE.equals(Reflect.field(task, "isVisible"));
            visibleCount += tile.visible ? 1 : 0;
            tile.view = tileView(ctx, theme, tile, pkg, label, appIcon, display, tileW, tileH,
                    tiles);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    tileW + 2 * Ui.dp(ctx, TILE_PAD_DP), ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = tiles.isEmpty() ? 0 : gap;
            row.addView(tile.view, lp);
            tiles.add(tile);
        }

        FrameLayout root = new FrameLayout(ctx);
        root.addView(pane, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.setOnHoverListener((v, e) -> {
            int action = e.getActionMasked();
            if (action == MotionEvent.ACTION_HOVER_ENTER
                    || action == MotionEvent.ACTION_HOVER_MOVE) {
                sOverPane = true;
                cancel(sClose);
                sClose = null;
            } else if (action == MotionEvent.ACTION_HOVER_EXIT) {
                sOverPane = false;
                closeSoon();
            }
            return false;
        });
        root.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
                dismiss();
                return true;
            }
            return false;
        });

        // Sized before it goes up, so it can be centred over the icon and kept on screen.
        root.measure(View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.AT_MOST));
        int w = root.getMeasuredWidth();
        int[] at = new int[2];
        icon.getLocationOnScreen(at);
        int edge = Ui.dp(ctx, 8);
        int x = at[0] + icon.getWidth() / 2 - w / 2;
        x = Math.max(edge, Math.min(x, dm.widthPixels - w - edge));

        WindowManager wm = Overlays.windowManager(ctx);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        // Placed in absolute screen pixels from the top. Measured from the bottom, an overlay
        // starts above the taskbar's inset already, so adding the bar's height again left a gap.
        int screenH = TaskbarTray.displayHeight(icon.getContext());
        if (screenH <= 0) {
            screenH = dm.heightPixels;
        }
        Insets bar = BarEdge.reserved(icon);
        boolean below = bar.top > 0;
        lp.gravity = Gravity.TOP | Gravity.START;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            lp.setFitInsetsTypes(0);
        }
        lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        lp.x = x;
        // Off the icon, away from the bar: up from one at the bottom, down from one at the top.
        lp.y = below ? bar.top + Ui.dp(ctx, 8)
                : Math.max(edge, screenH - bar.bottom - root.getMeasuredHeight() - Ui.dp(ctx, 8));
        lp.setTitle("ZuxOS Desktop Plus window preview");
        // The monitor's fastest refresh rate while this is up: its motion at what the
        // screen can show.
        FrameRate.forWindow(lp, wm.getDefaultDisplay());
        FrameRate.forView(root);
        wm.addView(root, lp);
        sRoot = root;
        sWm = wm;
        sShown = icon;
        sOverPane = false;
        theme.applyFont(root);

        if (theme.retro()) {
            // Retro's preview is simply there.
            startPictures(tiles, root, display, uid, visibleCount, dm);
            return;
        }
        // Out of the icon on iOS's spring: a little small and nearer the bar, then in place.
        pane.setPivotX(w / 2f);
        pane.setPivotY(below ? 0f : root.getMeasuredHeight());
        pane.setAlpha(0f);
        pane.setScaleX(0.9f);
        pane.setScaleY(0.9f);
        pane.setTranslationY(below ? -Ui.dp(ctx, 8) : Ui.dp(ctx, 8));
        pane.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                .setDuration(Motion.IOS_MS).setInterpolator(Motion.IOS).withLayer().start();
        FrameRate.measure(root, "window preview");
        startPictures(tiles, root, display, uid, visibleCount, dm);
    }

    /**
     * Each window's last picture, and the window on screen live when it is the only one: from
     * that app's own layers. Two on screen would share the one capture, so those keep their
     * pictures.
     */
    private static void startPictures(List<Tile> tiles, FrameLayout root, int display, int uid,
            int visibleCount, DisplayMetrics dm) {
        for (Tile tile : tiles) {
            loadSnapshot(tile, root);
        }
        if (uid >= 0 && visibleCount == 1) {
            for (Tile tile : tiles) {
                if (tile.visible) {
                    feed(tile, root, display, uid, dm);
                }
            }
        }
    }

    /**
     * One window: its app's icon and name over its picture, with an X. In Retro it is a Windows
     * 98 window - a raised box, a title bar that turns navy under the pointer as the active
     * window's did and grey off it, a bevelled close button, the picture sunk into a field.
     */
    private static View tileView(Context ctx, Theme theme, Tile tile, String pkg,
            CharSequence label, Drawable appIcon, int display, int tileW, int tileH,
            List<Tile> tiles) {
        boolean retro = theme.retro();
        HoverTile box = new HoverTile(ctx, theme);
        box.setOrientation(LinearLayout.VERTICAL);
        int boxPad = Ui.dp(ctx, retro ? RETRO_EDGE_DP : TILE_PAD_DP);
        box.setPadding(boxPad, boxPad, boxPad, boxPad);
        if (retro) {
            box.setBackground(Bevel.raised(ctx));
        }

        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(ctx);
        icon.setImageDrawable(appIcon);
        int iconPx = Ui.dp(ctx, retro ? 16 : 20);
        header.addView(icon, new LinearLayout.LayoutParams(iconPx, iconPx));
        TextView name = new TextView(ctx);
        name.setText(label);
        name.setTextColor(0xFFFFFFFF);
        name.setTextSize(13);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setPadding(Ui.dp(ctx, retro ? 4 : 8), 0, Ui.dp(ctx, retro ? 4 : 8), 0);
        header.addView(name, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        View x = retro ? retroClose(ctx) : glassClose(ctx);
        x.setOnClickListener(v -> {
            TaskOverview.closeTask(tile.taskId, pkg);
            tiles.remove(tile);
            Runnable gone = () -> {
                if (box.getParent() instanceof ViewGroup) {
                    ((ViewGroup) box.getParent()).removeView(box);
                }
                if (tiles.isEmpty()) {
                    dismiss();
                }
            };
            if (retro) {
                gone.run();
                return;
            }
            box.animate().alpha(0f).scaleX(0.9f).scaleY(0.9f).setDuration(Motion.SHORT)
                    .withEndAction(gone).start();
        });
        int xPx = Ui.dp(ctx, retro ? 16 : 22);
        header.addView(x, new LinearLayout.LayoutParams(xPx, xPx));
        if (retro) {
            int bar = Ui.dp(ctx, 2);
            header.setPadding(bar, bar, bar, bar);
            header.setBackgroundColor(RETRO_INACTIVE);
            box.setOnHover(on -> {
                header.setBackgroundColor(on ? Theme.NAVY : RETRO_INACTIVE);
                name.setTextColor(on ? 0xFFFFFFFF : BevelMath.FACE);
            });
            name.setTextColor(BevelMath.FACE);
        }
        box.addView(header);

        ImageView thumb = new ImageView(ctx);
        thumb.setScaleType(ImageView.ScaleType.FIT_CENTER);
        thumb.setImageDrawable(appIcon);
        if (retro) {
            int field = Ui.dp(ctx, 2);
            thumb.setBackground(Bevel.sunken(ctx));
            thumb.setPadding(field, field, field, field);
        } else {
            thumb.setBackground(Ui.roundRect(0x33FFFFFF, Ui.dp(ctx, 12)));
            int radius = Ui.dp(ctx, 12);
            thumb.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            thumb.setClipToOutline(true);
        }
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, tileH);
        tlp.topMargin = Ui.dp(ctx, retro ? RETRO_EDGE_DP : 8);
        box.addView(thumb, tlp);
        tile.thumb = thumb;

        box.setOnClickListener(v -> {
            dismiss();
            TaskOverview.bringToFront(tile.taskId, display);
        });
        box.mThumb = thumb;
        return box;
    }

    /** Glass's close: a round light X that turns as the pointer reaches it. */
    private static View glassClose(Context ctx) {
        TextView x = new TextView(ctx);
        x.setText("✕");
        x.setTextColor(0xFF1C1C1E);
        x.setTextSize(11);
        x.setGravity(Gravity.CENTER);
        int xPx = Ui.dp(ctx, 22);
        android.graphics.drawable.GradientDrawable xBack = Ui.roundRect(0xCCF4F4F8, xPx / 2);
        x.setBackground(xBack);
        x.setOnHoverListener((v, e) -> {
            int action = e.getActionMasked();
            if (action == MotionEvent.ACTION_HOVER_ENTER) {
                xBack.setColor(0xFFFFFFFF);
                v.animate().rotation(90f).setDuration(Motion.IOS_MS)
                        .setInterpolator(Motion.SNAPPY).start();
            } else if (action == MotionEvent.ACTION_HOVER_EXIT) {
                xBack.setColor(0xCCF4F4F8);
                v.animate().rotation(0f).setDuration(Motion.IOS_MS)
                        .setInterpolator(Motion.SNAPPY).start();
            }
            return false;
        });
        return x;
    }

    /** Windows 98's close: a small raised button with a black X, pressed in under a press. */
    private static View retroClose(Context ctx) {
        ImageView x = new ImageView(ctx);
        x.setImageDrawable(PixelIcons.close(Theme.RETRO.text()));
        int inset = Ui.dp(ctx, 3);
        x.setPadding(inset, inset, inset, inset);
        x.setBackground(Bevel.button(ctx));
        x.setContentDescription("Close");
        return x;
    }

    private static void loadSnapshot(Tile tile, FrameLayout root) {
        IO.execute(() -> {
            Bitmap b = TaskOverview.snapshot(tile.taskId);
            if (b == null) {
                return;
            }
            MAIN.post(() -> {
                if (sRoot == root && tile.crop == null) {
                    tile.thumb.setImageBitmap(b);
                }
            });
        });
    }

    /** The window live: that app's layers alone, about thirty times a second. */
    private static void feed(Tile tile, FrameLayout root, int display, int uid,
            DisplayMetrics dm) {
        Rect screen = new Rect(0, 0, dm.widthPixels, dm.heightPixels);
        Runnable capture = new Runnable() {
            @Override
            public void run() {
                if (sRoot != root) {
                    return;
                }
                long t0 = android.os.SystemClock.uptimeMillis();
                android.view.SurfaceControl own = ScreenBackdrop.surfaceOf(root);
                Object shot = own == null ? null
                        : ScreenBackdrop.grab(display, screen, own, FEED_SCALE, uid);
                Bitmap frame = shot == null ? null : ScreenBackdrop.toBitmap(shot);
                if (frame != null) {
                    Rect box = tile.fed++ % 15 == 0 ? TaskOverview.contentBox(frame) : null;
                    MAIN.post(() -> put(tile, frame, box, root));
                }
                if (Boolean.FALSE.equals(ScreenBackdrop.uidFilter())) {
                    return;
                }
                long wait = Math.max(0L,
                        FRAME_MS - (android.os.SystemClock.uptimeMillis() - t0));
                MAIN.postDelayed(() -> IO.execute(this), wait);
            }
        };
        IO.execute(capture);
    }

    private static void put(Tile tile, Bitmap frame, Rect box, FrameLayout root) {
        if (sRoot != root || (box != null && box.isEmpty())) {
            frame.recycle();
            return;
        }
        if (tile.crop == null) {
            if (box == null) {
                frame.recycle();
                return;
            }
            tile.crop = new TaskOverview.CropDrawable(box);
            tile.thumb.setImageDrawable(tile.crop);
        } else if (box != null && tile.crop.setSource(box)) {
            tile.thumb.setImageDrawable(null);
            tile.thumb.setImageDrawable(tile.crop);
        }
        tile.crop.setFrame(frame);
        tile.frames.addLast(frame);
        while (tile.frames.size() > 2) {
            tile.frames.removeFirst().recycle();
        }
    }

    /** Closes it the iOS way: a quick fade as it settles back a touch. Retro's just goes. */
    public static void dismiss() {
        FrameLayout root = sRoot;
        WindowManager wm = sWm;
        sRoot = null;
        sWm = null;
        sShown = null;
        sOverPane = false;
        if (root == null || wm == null) {
            return;
        }
        if (sTheme.retro()) {
            remove(wm, root);
            return;
        }
        View pane = root.getChildCount() > 0 ? root.getChildAt(0) : root;
        pane.animate().alpha(0f).scaleX(0.96f).scaleY(0.96f).setDuration(200L)
                .setInterpolator(Motion.EXIT).withLayer().withEndAction(() -> remove(wm, root)).start();
    }

    private static void dismissNow() {
        FrameLayout root = sRoot;
        WindowManager wm = sWm;
        sRoot = null;
        sWm = null;
        sShown = null;
        if (root != null && wm != null) {
            remove(wm, root);
        }
    }

    private static void remove(WindowManager wm, View root) {
        try {
            wm.removeViewImmediate(root);
        } catch (Throwable ignored) {
            // Already gone.
        }
    }
}
