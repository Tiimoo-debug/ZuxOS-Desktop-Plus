package com.zuxos.desktopplus.logic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Choosing the drawer's sheet.
 *
 * <p>The fixture is the real window from the device log - a full-window scrim, the slide-in view,
 * the container, the sheet itself as a FrameLayout with a gradient, a search box and a tab strip -
 * because that is the tree this got wrong three times running.
 */
public class GlassPickTest {

    /** The drawer's window on the tablet: 2560 x 1440. */
    private static final long WINDOW = 2560L * 1440L;

    private static final int OPAQUE_LIGHT = 0xFFF0EFEF;
    private static final int SCRIM = 0x99000000;

    private List<GlassPick.Pane> drawerWindow() {
        List<GlassPick.Pane> panes = new ArrayList<>();
        // 0: the scrim, the whole window.
        panes.add(new GlassPick.Pane(0, 2560, 1440, SCRIM));
        // 1: the sheet - what we are looking for.
        panes.add(new GlassPick.Pane(1, 1900, 1210, OPAQUE_LIGHT));
        // 2: the search box, 3: the tab strip. Both solid, both small.
        panes.add(new GlassPick.Pane(2, 1500, 90, OPAQUE_LIGHT));
        panes.add(new GlassPick.Pane(3, 800, 60, OPAQUE_LIGHT));
        return panes;
    }

    @Test
    public void theSheetIsFound() {
        GlassPick.Pane sheet = GlassPick.sheet(drawerWindow(), WINDOW);

        assertEquals(drawerWindow().get(1).area, sheet.area);
        assertEquals(1, sheet.order);
    }

    /** Glazing this would blur the whole screen - the one thing this module must never do. */
    @Test
    public void theFullWindowScrimIsNotTheSheet() {
        List<GlassPick.Pane> onlyScrim = Collections.singletonList(
                new GlassPick.Pane(0, 2560, 1440, 0xFF000000));

        assertNull(GlassPick.sheet(onlyScrim, WINDOW));
    }

    @Test
    public void aSearchBoxIsTooSmallToBeTheSheet() {
        List<GlassPick.Pane> boxes = Arrays.asList(
                new GlassPick.Pane(0, 1500, 90, OPAQUE_LIGHT),
                new GlassPick.Pane(1, 800, 60, OPAQUE_LIGHT));

        assertNull(GlassPick.sheet(boxes, WINDOW));
    }

    /** A background you can already see through is not what is hiding the blur. */
    @Test
    public void aSeeThroughPaneIsLeftAlone() {
        List<GlassPick.Pane> faint = Collections.singletonList(
                new GlassPick.Pane(0, 1900, 1210, 0x40FFFFFF));

        assertNull(GlassPick.sheet(faint, WINDOW));
    }

    @Test
    public void justOpaqueEnoughCounts() {
        assertTrue(new GlassPick.Pane(0, 10, 10, 0x80FFFFFF).opaque());
        assertTrue(!new GlassPick.Pane(0, 10, 10, 0x7FFFFFFF).opaque());
    }

    @Test
    public void theBiggestWins() {
        List<GlassPick.Pane> two = Arrays.asList(
                new GlassPick.Pane(0, 1200, 900, OPAQUE_LIGHT),
                new GlassPick.Pane(1, 1900, 1210, OPAQUE_LIGHT));

        assertEquals(1, GlassPick.sheet(two, WINDOW).order);
    }

    /** Same size, so the one drawn last is the one you can actually see. */
    @Test
    public void aTieGoesToWhateverIsDrawnLast() {
        List<GlassPick.Pane> two = Arrays.asList(
                new GlassPick.Pane(0, 1900, 1210, OPAQUE_LIGHT),
                new GlassPick.Pane(1, 1900, 1210, OPAQUE_LIGHT));

        assertEquals(1, GlassPick.sheet(two, WINDOW).order);
    }

    /**
     * Before a layout every view is nought by nought, and picking one then would glaze the wrong
     * thing for good - there is no second chance, the window is marked done.
     */
    @Test
    public void nothingIsChosenBeforeAnythingIsLaidOut() {
        List<GlassPick.Pane> unlaid = Arrays.asList(
                new GlassPick.Pane(0, 0, 0, OPAQUE_LIGHT),
                new GlassPick.Pane(1, 0, 0, OPAQUE_LIGHT));

        assertNull(GlassPick.sheet(unlaid, WINDOW));
        assertNull(GlassPick.sheet(drawerWindow(), 0));
        assertNull(GlassPick.sheet(null, WINDOW));
    }

    // --- what would paint over the glass ---------------------------------

    @Test
    public void aBigOpaqueLayerOnTopIsCleared() {
        List<GlassPick.Pane> panes = drawerWindow();
        // A list with its own opaque background, drawn after the sheet and filling it.
        panes.add(new GlassPick.Pane(4, 1900, 1100, OPAQUE_LIGHT));

        List<GlassPick.Pane> over = GlassPick.drawnOver(panes, panes.get(1), WINDOW);

        assertEquals(1, over.size());
        assertEquals(4, over.get(0).order);
    }

    /** The scrim is drawn before the sheet, so it is behind the glass and none of our business. */
    @Test
    public void whatIsDrawnUnderneathIsLeftAlone() {
        List<GlassPick.Pane> panes = drawerWindow();

        List<GlassPick.Pane> over = GlassPick.drawnOver(panes, panes.get(1), WINDOW);

        for (GlassPick.Pane pane : over) {
            assertTrue(pane.order > 1);
        }
    }

    @Test
    public void smallSolidThingsOnTopAreMeantToBeSolid() {
        List<GlassPick.Pane> panes = drawerWindow();

        // The search box and the tab strip are both drawn after the sheet, and both stay.
        assertTrue(GlassPick.drawnOver(panes, panes.get(1), WINDOW).isEmpty());
    }

    @Test
    public void nothingIsClearedWithoutASheet() {
        assertTrue(GlassPick.drawnOver(drawerWindow(), null, WINDOW).isEmpty());
    }
}
