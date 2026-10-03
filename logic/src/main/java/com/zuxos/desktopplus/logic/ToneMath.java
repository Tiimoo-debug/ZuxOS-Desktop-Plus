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

    /**
     * Relative luminance as WCAG defines it: linear light, 0 for black to 1 for white.
     *
     * <p>Not {@link #luminance}, which is a quick perceptual weighting on gamma-encoded values and
     * good enough to call a colour light or dark. Contrast has to be measured in linear light, or
     * mid-greys come out far more legible than they are.
     */
    public static double relativeLuminance(int rgb) {
        return 0.2126 * linear((rgb >> 16) & 0xFF) + 0.7152 * linear((rgb >> 8) & 0xFF)
                + 0.0722 * linear(rgb & 0xFF);
    }

    private static double linear(int channel) {
        double c = channel / 255.0;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    /** The WCAG contrast ratio between two opaque colours: 1 (none) to 21 (black on white). */
    public static double contrast(int a, int b) {
        double la = relativeLuminance(a);
        double lb = relativeLuminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    /** {@code scrim} at {@code alpha} (0-255) laid over an opaque {@code back}, as an opaque colour. */
    public static int over(int scrim, int alpha, int back) {
        double a = alpha / 255.0;
        int r = (int) Math.round(((scrim >> 16) & 0xFF) * a + ((back >> 16) & 0xFF) * (1 - a));
        int g = (int) Math.round(((scrim >> 8) & 0xFF) * a + ((back >> 8) & 0xFF) * (1 - a));
        int b = (int) Math.round((scrim & 0xFF) * a + (back & 0xFF) * (1 - a));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /**
     * The lightest scrim that keeps a glyph legible whatever is behind the bar.
     *
     * <p>The taskbar is glass, so what is behind it is some app nobody can predict - white, black
     * or anything between. This finds the smallest alpha at which {@code scrim} lifts the glyph to
     * {@code minContrast} against both extremes, which bounds everything in between. A scrim that
     * cannot do it at all - the glyph's own colour, say - comes back fully opaque.
     */
    public static int scrimAlphaFor(int glyph, int scrim, double minContrast) {
        for (int alpha = 0; alpha <= 255; alpha++) {
            if (contrast(glyph, over(scrim, alpha, 0xFFFFFFFF)) >= minContrast
                    && contrast(glyph, over(scrim, alpha, 0xFF000000)) >= minContrast) {
                return alpha;
            }
        }
        return 255;
    }
}
