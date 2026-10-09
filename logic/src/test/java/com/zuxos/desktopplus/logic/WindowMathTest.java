package com.zuxos.desktopplus.logic;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * ZUI's maximise, on the monitor's own numbers: 2560 by 1440, a 48 pixel bar along the bottom.
 */
public class WindowMathTest {

    private static final int[] AREA = {0, 0, 2560, 1392};

    @Test
    public void aWindowThatTakesAnySizeFillsTheArea() {
        int[] out = WindowMath.maximized(AREA, true, 2f, false, 40);
        assertArrayEquals(AREA, out);
        // A copy: the caller's area is not handed out to be changed.
        out[0] = 5;
        assertEquals(0, AREA[0]);
    }

    @Test
    public void anUprightAppKeepsItsShapeCentred() {
        // A phone app, 9 by 19.5, with a 40 pixel title bar above it.
        float aspect = WindowMath.aspect(900, 1950);
        int[] out = WindowMath.maximized(AREA, false, aspect, true, 40);
        int appHeight = 1392 - 40;
        int width = (int) Math.ceil(appHeight / aspect);
        assertEquals(width, out[2] - out[0]);
        assertEquals(1392, out[3] - out[1]);
        assertEquals((2560 - width) / 2, out[0]);
        assertEquals(0, out[1]);
    }

    @Test
    public void aWideAppTooWideForTheAreaIsLimitedByItsWidth() {
        // 4 to 1: as tall as the area would be 5408 wide, so it takes the width instead.
        int[] out = WindowMath.maximized(AREA, false, 4f, false, 0);
        assertEquals(2560, out[2] - out[0]);
        assertEquals(640, out[3] - out[1]);
        assertEquals((1392 - 640) / 2, out[1]);
    }

    @Test
    public void anUprightAppTooWideIsLimitedByTheWidthToo() {
        // A narrow area: as tall as it is, the app would be wider than the area.
        int[] narrow = {100, 0, 600, 1392};
        int[] out = WindowMath.maximized(narrow, false, 2f, true, 0);
        assertEquals(500, out[2] - out[0]);
        assertEquals(1000, out[3] - out[1]);
        assertEquals(100, out[0]);
    }

    @Test
    public void maximisedIsExactForAnyTheRestByAFullSide() {
        assertTrue(WindowMath.isMaximized(AREA.clone(), AREA, true));
        assertFalse(WindowMath.isMaximized(new int[]{0, 0, 2560, 1391}, AREA, true));
        // One that keeps its shape: the full height is enough, wherever it sits.
        assertTrue(WindowMath.isMaximized(new int[]{900, 0, 1560, 1392}, AREA, false));
        assertTrue(WindowMath.isMaximized(new int[]{0, 376, 2560, 1016}, AREA, false));
        assertFalse(WindowMath.isMaximized(new int[]{900, 10, 1560, 1300}, AREA, false));
    }

    @Test
    public void whatZuiMaximisesItCallsMaximised() {
        float aspect = WindowMath.aspect(900, 1950);
        assertTrue(WindowMath.isMaximized(WindowMath.maximized(AREA, false, aspect, true, 40),
                AREA, false));
        assertTrue(WindowMath.isMaximized(WindowMath.maximized(AREA, true, aspect, true, 40),
                AREA, true));
    }

    @Test
    public void theDefaultRestoreIsThreeQuartersCentred() {
        assertArrayEquals(new int[]{320, 180, 2240, 1260},
                WindowMath.restoredDefault(2560, 1440, 0.75f));
    }

    @Test
    public void anEmptySizeHasNoShape() {
        assertEquals(1f, WindowMath.aspect(0, 100), 0f);
        assertEquals(2f, WindowMath.aspect(200, 100), 0f);
        assertEquals(2f, WindowMath.aspect(100, 200), 0f);
    }

    @Test
    public void onlyTheUprightOrientationsStandUpright() {
        assertTrue(WindowMath.fixedPortrait(1));
        assertTrue(WindowMath.fixedPortrait(7));
        assertTrue(WindowMath.fixedPortrait(9));
        assertTrue(WindowMath.fixedPortrait(12));
        assertFalse(WindowMath.fixedPortrait(-1));
        assertFalse(WindowMath.fixedPortrait(0));
        assertFalse(WindowMath.fixedPortrait(2));
    }
}
