package com.zuxos.desktopplus.core.theme;

import android.content.Context;
import android.graphics.Typeface;

import com.zuxos.desktopplus.core.Const;
import com.zuxos.desktopplus.core.L;

/**
 * Retro's pixel font, Pixelify Sans (SIL Open Font License; see NOTICE.md), bundled in the
 * module's own APK.
 *
 * <p>Everything else the module shows inside the launcher is drawn in code, because the module's
 * resources are not on the launcher's path. A font cannot be drawn by hand, so it is read from
 * the module's APK through a context of the module's package - once, and kept. If that fails,
 * monospace stands in, and the log says why, once.
 */
final class PixelFont {

    private static final String ASSET = "fonts/PixelifySans.ttf";

    private static Typeface sFont;
    private static boolean sTried;

    private PixelFont() {
    }

    static synchronized Typeface get(Context ctx) {
        if (!sTried) {
            sTried = true;
            try {
                Context module = ctx.createPackageContext(Const.MODULE_PKG, 0);
                sFont = Typeface.createFromAsset(module.getAssets(), ASSET);
                L.i("retro: pixel font loaded");
            } catch (Throwable t) {
                L.w("retro: no pixel font (" + t + "), monospace instead");
            }
        }
        return sFont != null ? sFont : Typeface.MONOSPACE;
    }
}
