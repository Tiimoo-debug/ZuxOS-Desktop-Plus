package com.zuxos.desktopplus.core;

import android.app.WallpaperManager;
import android.app.WallpaperColors;
import android.content.Context;
import android.graphics.Color;
import android.os.Build;

/**
 * Whether the taskbar should be drawing in black or in white.
 *
 * <p>One decision, asked by everything on the bar: the clock and the date, the battery and the
 * temperatures, the tray's own buttons, the navigation glyphs. They used to each carry their own
 * idea of it, which is how you end up with a white clock beside a black battery.
 *
 * <p>Automatic does not photograph the screen. It asks two things that are already known: whether
 * our own glass is under the bar - which is a dark pane, so light on it - and failing that, what
 * the system says about the wallpaper. {@code WallpaperColors} carries a hint for exactly this
 * question, set by the same code that decides whether the status bar's own icons go dark.
 */
public final class Tone {

    public static final int MODE_AUTO = 0;
    public static final int MODE_DARK = 1;
    public static final int MODE_LIGHT = 2;

    /** Near-black rather than black: on a light bar, pure black reads as a hole. */
    private static final int DARK = 0xFF14161A;
    private static final int DARK_DIM = 0xB314161A;

    /** Near-white, so it reads as an icon on glass rather than as a highlight. */
    private static final int LIGHT = 0xFFEDEFF5;
    private static final int LIGHT_DIM = 0xB3EDEFF5;

    /**
     * Whether any taskbar is currently wearing our glass.
     *
     * <p>Asked rather than counted. A count has to be kept balanced across a display being
     * unplugged, the launcher rebuilding its taskbar and every way applying the glass can fail -
     * and a count that drifts leaves the bar stuck in the wrong colour with nothing to correct
     * it. The glass already knows which taskbars have a live pane; this asks it.
     *
     * <p>It is still one decision for both displays, since the colour is asked for without
     * saying which bar is asking. That is a limit worth naming rather than hiding.
     */
    public interface Glazed {
        boolean any();
    }

    private static volatile Glazed sGlazed;

    /** The wallpaper's answer, held briefly: it is a call into another process. */
    private static final long CACHE_MS = 30_000L;
    private static volatile Boolean sWallpaperLight;
    private static volatile long sAskedAt;

    private Tone() {
    }

    /** Told once by {@code TaskbarGlass}, which is the thing that knows. */
    public static void glazedBy(Glazed source) {
        sGlazed = source;
    }

    private static boolean glazed() {
        Glazed source = sGlazed;
        try {
            return source != null && source.any();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * True when the bar's background is dark, so its contents should be light.
     *
     * <p>The wallpaper only gets a say once the bar is glass. Before that the bar is the
     * launcher's own, which is opaque and light on this firmware - so a dark wallpaper behind it
     * is not something you can see, and painting white text for it would put white on white.
     */
    public static boolean lightOnDark(Context ctx) {
        switch (Cfg.taskbarTextMode()) {
            case MODE_DARK:
                return false;
            case MODE_LIGHT:
                return true;
            default:
                return glazed() && ctx != null && !wallpaperIsLight(ctx);
        }
    }

    public static int text(Context ctx) {
        return lightOnDark(ctx) ? LIGHT : DARK;
    }

    public static int dimText(Context ctx) {
        return lightOnDark(ctx) ? LIGHT_DIM : DARK_DIM;
    }

    /** Ask the wallpaper again next time - after it has changed, or the glass has. */
    public static void forget() {
        sWallpaperLight = null;
        sAskedAt = 0;
    }

    /**
     * What the system thinks of the wallpaper behind the bar.
     *
     * <p>The hint is the reliable half: it is what the shade uses to decide whether its own icons
     * go dark. Where there is no hint - a live wallpaper that offers none - the primary colour's
     * luminance is the fallback, and a wallpaper that will not answer at all is treated as dark,
     * which is the safer guess under a taskbar that is usually dark itself.
     */
    private static boolean wallpaperIsLight(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
            return false;
        }
        long now = android.os.SystemClock.uptimeMillis();
        Boolean cached = sWallpaperLight;
        if (cached != null && now - sAskedAt < CACHE_MS) {
            return cached;
        }
        sAskedAt = now;
        boolean light = askWallpaper(ctx);
        sWallpaperLight = light;
        return light;
    }

    private static boolean askWallpaper(Context ctx) {
        try {
            WallpaperManager wm = (WallpaperManager) ctx.getSystemService(Context.WALLPAPER_SERVICE);
            if (wm == null) {
                return false;
            }
            WallpaperColors colors = wm.getWallpaperColors(WallpaperManager.FLAG_SYSTEM);
            if (colors == null) {
                return false;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                int hints = colors.getColorHints();
                if ((hints & WallpaperColors.HINT_SUPPORTS_DARK_TEXT) != 0) {
                    return true;
                }
                if (hints != 0) {
                    // It answered, and its answer was no.
                    return false;
                }
            }
            Color primary = colors.getPrimaryColor();
            if (primary == null) {
                return false;
            }
            int argb = primary.toArgb();
            double luminance = (0.299 * Color.red(argb) + 0.587 * Color.green(argb)
                    + 0.114 * Color.blue(argb)) / 255.0;
            return luminance > 0.55;
        } catch (Throwable t) {
            L.d("tone: the wallpaper will not say what colour it is (" + t + ")");
            return false;
        }
    }
}
