package com.zuxos.desktopplus.logic;

/**
 * Light or dark, from a colour.
 *
 * <p>Everything the taskbar paints hangs off this one answer, and it has already been wrong once:
 * an inverted test here would have put black glyphs on a dark bar and white ones on a light bar,
 * which is not a subtle fault but is invisible until the thing is on a screen. It is three
 * multiplications, so there is no reason for it to be anywhere a test cannot reach.
 *
 * <p>It also keeps the two thresholds in one place. They were two copies of the same sum with
 * different numbers on the end, in two files, which is how they drift apart.
 */
public final class ToneMath {

    /**
     * For a colour the launcher chose - a glyph, an icon tint.
     *
     * <p>Half way: it was picked deliberately against its own background, so there is no reason to
     * lean either way.
     */
    public static final double GLYPH_THRESHOLD = 0.5;

    /**
     * For a wallpaper.
     *
     * <p>Leans dark on purpose. A wallpaper is a photograph averaged down to one colour, and a bar
     * that guesses "light" over a busy picture is far harder to read than one that guesses "dark".
     */
    public static final double WALLPAPER_THRESHOLD = 0.55;

    private ToneMath() {
    }

    /**
     * Perceived brightness, 0 to 1.
     *
     * <p>The usual weighting: the eye takes most of its brightness from green and least from blue.
     * Alpha is ignored - a half-transparent white is still a light colour, and what it is over is
     * the caller's business.
     */
    public static double luminance(int argb) {
        int red = (argb >> 16) & 0xFF;
        int green = (argb >> 8) & 0xFF;
        int blue = argb & 0xFF;
        return (0.299 * red + 0.587 * green + 0.114 * blue) / 255.0;
    }

    /** Whether a colour the launcher chose is a light one. */
    public static boolean isLight(int argb) {
        return isLight(argb, GLYPH_THRESHOLD);
    }

    public static boolean isLight(int argb, double threshold) {
        return luminance(argb) > threshold;
    }
}
