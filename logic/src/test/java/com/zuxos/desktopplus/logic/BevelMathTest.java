package com.zuxos.desktopplus.logic;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * The Retro theme's edges. The classic grey must give the classic edges exactly: the look is
 * recognisable by them, and a bevel one shade off reads as wrong at once.
 */
public class BevelMathTest {

    @Test
    public void theClassicGreyGivesTheClassicEdges() {
        assertEquals(0xFFDFDFDF, BevelMath.light(BevelMath.FACE));
        assertEquals(0xFF808080, BevelMath.shadow(BevelMath.FACE));
    }

    @Test
    public void textIsBlackOnGreyAndWhiteOnNavy() {
        assertEquals(0xFF000000, BevelMath.textOn(BevelMath.FACE));
        assertEquals(0xFFFFFFFF, BevelMath.textOn(0xFF000080));
    }

    @Test
    public void edgesFollowAColouredFace() {
        // A teal face: the light edge lighter than it, the shadow darker, channel by channel.
        int face = 0xFF008080;
        assertEquals(0xFF7FBFBF, BevelMath.light(face));
        assertEquals(0xFF005656, BevelMath.shadow(face));
    }

    @Test
    public void mixingIgnoresTheFacesAlpha() {
        assertEquals(0xFFDFDFDF, BevelMath.light(0x80C0C0C0));
    }
}
