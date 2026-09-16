package com.zuxos.desktopplus.hook;

import android.content.Context;
import android.graphics.PixelFormat;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.zuxos.desktopplus.core.Glass;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Ui;
import com.zuxos.desktopplus.desktop.GlassPanel;
import com.zuxos.desktopplus.desktop.ItemView;
import com.zuxos.desktopplus.model.AppsRepo;
import com.zuxos.desktopplus.model.Item;

/**
 * Folder contents shown over the stock app drawer.
 *
 * <p>The taskbar drawer is a window the launcher owns, not a view tree we can inject into
 * safely, so an opened folder gets a window of its own on the same display.
 */
public final class DrawerFolderWindow {

    private static View sCurrent;
    private static WindowManager sWm;

    private DrawerFolderWindow() {
    }

    public static void show(Context ctx, Item folder, AppsRepo repo, int displayId,
            int iconSizePx) {
        dismiss();
        if (!canShow(ctx)) {
            toast(ctx, "Allow \"display over other apps\" for the launcher to open drawer folders");
            return;
        }
        try {
            FrameLayout root = new FrameLayout(ctx);
            root.setBackgroundColor(Ui.COLOR_SCRIM);

            LinearLayout panel = new LinearLayout(ctx);
            panel.setOrientation(LinearLayout.VERTICAL);
            GlassPanel glass = new GlassPanel(ctx, Ui.dp(ctx, 24), 0xB0202024);
            glass.addView(panel, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT));
            int pad = Ui.dp(ctx, 20);
            panel.setPadding(pad, pad, pad, pad);

            TextView title = new TextView(ctx);
            title.setText(folder.label != null ? folder.label : "Folder");
            title.setTextColor(Ui.COLOR_TEXT);
            title.setTextSize(18);
            panel.addView(title);

            GridLayout grid = new GridLayout(ctx);
            grid.setColumnCount(Math.max(1, Math.min(5, folder.children.size())));
            LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            glp.topMargin = Ui.dp(ctx, 12);
            panel.addView(grid, glp);

            for (final Item child : folder.children) {
                ItemView iv = new ItemView(ctx, iconSizePx, true, false);
                iv.bind(child, repo);
                iv.setOnClickListener(v -> {
                    repo.launch(child, v, displayId);
                    dismiss();
                });
                GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
                lp.width = iconSizePx + Ui.dp(ctx, 44);
                lp.setMargins(Ui.dp(ctx, 6), Ui.dp(ctx, 6), Ui.dp(ctx, 6), Ui.dp(ctx, 6));
                grid.addView(iv, lp);
            }

            FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
            plp.gravity = Gravity.CENTER;
            root.addView(glass, plp);
            glass.setSource(root);
            glass.post(glass::refresh);
            root.setOnTouchListener((v, event) -> {
                if (event.getAction() == MotionEvent.ACTION_OUTSIDE
                        || event.getAction() == MotionEvent.ACTION_DOWN) {
                    // A tap anywhere outside the panel closes the folder.
                    float x = event.getX();
                    float y = event.getY();
                    boolean insidePanel = x >= glass.getLeft() && x <= glass.getRight()
                            && y >= glass.getTop() && y <= glass.getBottom();
                    if (!insidePanel) {
                        dismiss();
                        return true;
                    }
                }
                return false;
            });

            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                    PixelFormat.TRANSLUCENT);
            lp.setTitle("ZuxOS Desktop Plus folder");
            Glass.blurBehind(ctx, lp, 40);
            wm.addView(root, lp);
            sCurrent = root;
            sWm = wm;
        } catch (Throwable t) {
            L.e("could not open drawer folder", t);
        }
    }

    public static void dismiss() {
        View current = sCurrent;
        WindowManager wm = sWm;
        sCurrent = null;
        sWm = null;
        if (current == null || wm == null) {
            return;
        }
        try {
            wm.removeViewImmediate(current);
        } catch (Throwable t) {
            L.d("folder window already gone: " + t);
        }
    }

    private static boolean canShow(Context ctx) {
        try {
            return Settings.canDrawOverlays(ctx);
        } catch (Throwable t) {
            return false;
        }
    }

    private static void toast(Context ctx, String msg) {
        try {
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
            L.w(msg);
        }
    }
}
