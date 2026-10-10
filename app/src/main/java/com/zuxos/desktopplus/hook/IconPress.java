package com.zuxos.desktopplus.hook;

import android.view.View;

import com.zuxos.desktopplus.core.L;

/**
 * A press on one of ZUI's icons, let go as ZUI lets it go.
 *
 * <p>On touch down ZUI's icon holds itself pressed ({@code setStayPressed(true)}), which shrinks
 * its picture to 0.8 ({@code FastBitmapDrawable}); ZUI's own handlers let go of it - its click
 * handler with {@code setStayPressed(false)}, a hold that opens a popup without a drag with
 * {@code clearPressedBackground()} ({@code LauncherCustom.skipHotseatDrag}). A tap or hold we take
 * from ZUI skips those handlers, so the icon stayed pressed: small, and small again each time ZUI
 * rebuilt it, since a rebuilt icon starts at its view's pressed state (the 00:29 recording).
 * This makes the same calls ZUI's handlers make, nothing more.
 */
public final class IconPress {

    private static boolean sSaid;

    private IconPress() {
    }

    /** Lets go of the press, as ZUI does after a tap or a hold it handled itself. */
    public static void release(View icon) {
        if (icon == null) {
            return;
        }
        try {
            // ZUI's own: setPressed(false) and setStayPressed(false) (WorkspaceIconCompat).
            icon.getClass().getMethod("clearPressedBackground").invoke(icon);
            // And the picture's scale, as ZUI's click handler resets it after a launch.
            icon.getClass().getMethod("resetIconScale").invoke(icon);
            if (!sSaid) {
                sSaid = true;
                L.i("icon press: let go as ZUI does (" + icon.getClass().getSimpleName() + ")");
            }
        } catch (NoSuchMethodException notZuis) {
            // Not one of ZUI's icons: it holds no press of ZUI's.
        } catch (Throwable t) {
            L.d("icon press: not let go (" + t + ")");
        }
    }
}
