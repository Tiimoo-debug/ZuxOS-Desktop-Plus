package com.zuxos.desktopplus.logic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Light or dark.
 *
 * <p>These are the tests that fail if the polarity ever inverts again. It was inverted once in
 * review - {@code !isLight(glyph)} where {@code isLight(glyph)} was meant - and on a device that
 * reads as black glyphs on a black bar, which looks like the bar is simply broken.
 */
public class ToneMathTest {

    @Test
    public void whiteIsLightAndBlackIsNot() {
        assertTrue(ToneMath.isLight(0xFFFFFFFF));
        assertFalse(ToneMath.isLight(0xFF000000));
    }

    @Test
    public void theTaskbarsOwnColoursComeOutTheRightWayRound() {
        // Tone.DARK and Tone.LIGHT, the two the bar actually paints.
        assertFalse(ToneMath.isLight(0xFF14161A));
        assertTrue(ToneMath.isLight(0xFFEDEFF5));
    }

    @Test
    public void brightnessIsWeightedTheWayTheEyeSeesIt() {
        // Green reads brightest, blue darkest, at the same value in each channel.
        assertTrue(ToneMath.luminance(0xFF00FF00) > ToneMath.luminance(0xFFFF0000));
        assertTrue(ToneMath.luminance(0xFFFF0000) > ToneMath.luminance(0xFF0000FF));

        assertEquals(0.0, ToneMath.luminance(0xFF000000), 1e-9);
        assertEquals(1.0, ToneMath.luminance(0xFFFFFFFF), 1e-9);
    }

    /** A half-transparent white is still a light colour; what it is over is the caller's business. */
    @Test
    public void alphaIsNotBrightness() {
        assertEquals(ToneMath.luminance(0xFFFFFFFF), ToneMath.luminance(0x00FFFFFF), 1e-9);
        assertTrue(ToneMath.isLight(0x10FFFFFF));
    }

    @Test
    public void theTwoThresholdsAreWhatTheyClaimToBe() {
        assertEquals(0.5, ToneMath.GLYPH_THRESHOLD, 1e-9);
        assertEquals(0.55, ToneMath.WALLPAPER_THRESHOLD, 1e-9);

        // A mid grey: a glyph colour counts as light, the same colour as a wallpaper does not.
        int midGrey = 0xFF8C8C8C;  // luminance ~0.549
        assertTrue(ToneMath.luminance(midGrey) > ToneMath.GLYPH_THRESHOLD);
        assertTrue(ToneMath.luminance(midGrey) < ToneMath.WALLPAPER_THRESHOLD);
        assertTrue(ToneMath.isLight(midGrey, ToneMath.GLYPH_THRESHOLD));
        assertFalse(ToneMath.isLight(midGrey, ToneMath.WALLPAPER_THRESHOLD));
    }

    @Test
    public void exactlyOnTheThresholdIsNotLight() {
        // Strictly greater, so a colour sitting on the line falls to the darker, safer side.
        assertFalse(ToneMath.isLight(0xFF000000, 0.0));
    }
}
