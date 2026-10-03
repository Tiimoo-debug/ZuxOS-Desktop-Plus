package com.zuxos.desktopplus.logic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** The taskbar's row of open apps: what is in it, and in what order. */
public class RunningOrderTest {

    private static Set<String> open(String... pkgs) {
        // A linked set, because that is what the caller passes: the activity manager's order.
        return new LinkedHashSet<>(Arrays.asList(pkgs));
    }

    /**
     * The fault that prompted all of this: the task list is most-recent-first, so the row was
     * rebuilt in a different order every three seconds and every icon moved under the finger.
     */
    @Test
    public void theOrderDoesNotFollowTheTaskList() {
        List<String> shown = Arrays.asList("com.a", "com.b", "com.c");

        List<String> after = RunningOrder.inOrder(shown, open("com.c", "com.a", "com.b"));

        assertEquals(Arrays.asList("com.a", "com.b", "com.c"), after);
    }

    @Test
    public void aNewlyOpenedAppJoinsAtTheEnd() {
        List<String> shown = Arrays.asList("com.a", "com.b");

        List<String> after = RunningOrder.inOrder(shown, open("com.new", "com.b", "com.a"));

        assertEquals(Arrays.asList("com.a", "com.b", "com.new"), after);
    }

    @Test
    public void aClosedAppLeavesAndTheRestKeepTheirPlaces() {
        List<String> shown = Arrays.asList("com.a", "com.b", "com.c");

        List<String> after = RunningOrder.inOrder(shown, open("com.c", "com.a"));

        assertEquals(Arrays.asList("com.a", "com.c"), after);
    }

    @Test
    public void anEmptyRowTakesTheOrderItIsGiven() {
        List<String> after = RunningOrder.inOrder(
                Collections.<String>emptyList(), open("com.a", "com.b"));

        assertEquals(Arrays.asList("com.a", "com.b"), after);
    }

    @Test
    public void nothingOpenMeansNothingShown() {
        assertTrue(RunningOrder.inOrder(Arrays.asList("com.a"), open()).isEmpty());
    }

    @Test
    public void theRowIsNeverAskedToShowAnAppTwice() {
        List<String> shown = Arrays.asList("com.a", "com.a", "com.b");

        List<String> after = RunningOrder.inOrder(shown, open("com.b", "com.a"));

        assertEquals(Arrays.asList("com.a", "com.b"), after);
    }

    @Test
    public void whatIsPassedInIsNotChanged() {
        List<String> shown = new ArrayList<>(Arrays.asList("com.a", "com.b"));
        Set<String> running = open("com.a");

        RunningOrder.trimToFit(RunningOrder.inOrder(shown, running), 100, 60, 4);

        assertEquals(Arrays.asList("com.a", "com.b"), shown);
        assertEquals(open("com.a"), running);
    }

    // --- the gap between icons -------------------------------------------

    /**
     * The fault this exists for: our row was built with 66px between icons because the gap was
     * measured across the slot of an icon we had hidden - 4px of real gap plus a 60px icon.
     */
    @Test
    public void aGapMeasuredAcrossAHiddenIconIsNotTheGap() {
        assertEquals(4, RunningOrder.spacing(Arrays.asList(4, 66, 4), 200, 8));
        assertEquals(4, RunningOrder.spacing(Arrays.asList(66, 4), 200, 8));
    }

    @Test
    public void oneGapIsTheGap() {
        assertEquals(12, RunningOrder.spacing(Collections.singletonList(12), 200, 8));
    }

    @Test
    public void nothingMeasurableFallsBack() {
        assertEquals(8, RunningOrder.spacing(null, 200, 8));
        assertEquals(8, RunningOrder.spacing(Collections.<Integer>emptyList(), 200, 8));
        assertEquals(8, RunningOrder.spacing(Arrays.asList(0, -4), 200, 8));
    }

    @Test
    public void everyGapBeingAHoleFallsBackRatherThanCopyingOne() {
        // A row where every visible icon has a hidden one beside it: better the default than a
        // row of icons spread a whole icon apart.
        assertEquals(8, RunningOrder.spacing(Arrays.asList(220, 300), 200, 8));
    }

    // --- how many fit ---------------------------------------------------

    @Test
    public void iconsAreCountedWithTheGapsBetweenThemOnly() {
        // Three 60px icons with 4px between them come to 188, not 192: two gaps, not three.
        assertEquals(3, RunningOrder.fits(188, 60, 4));
        assertEquals(2, RunningOrder.fits(187, 60, 4));
    }

    @Test
    public void oneIconAlwaysFitsRatherThanNone() {
        // Room for half an icon still shows one: an empty row says the feature is broken, and a
        // single icon overlapping the clock by a hair does not.
        assertEquals(1, RunningOrder.fits(30, 60, 4));
    }

    @Test
    public void nothingMeasuredYetIsNotTheSameAsNoRoom() {
        assertEquals(RunningOrder.UNKNOWN, RunningOrder.fits(0, 60, 4));
        assertEquals(RunningOrder.UNKNOWN, RunningOrder.fits(-120, 60, 4));
        assertEquals(RunningOrder.UNKNOWN, RunningOrder.fits(500, 0, 4));
    }

    @Test
    public void theRowIsCutFromTheEnd() {
        List<String> wanted = Arrays.asList("com.a", "com.b", "com.c", "com.d");

        // Room for two.
        List<String> after = RunningOrder.trimToFit(wanted, 124, 60, 4);

        assertEquals(Arrays.asList("com.a", "com.b"), after);
    }

    @Test
    public void nothingIsCutBeforeAnythingIsMeasured() {
        List<String> wanted = Arrays.asList("com.a", "com.b", "com.c");

        assertEquals(wanted, RunningOrder.trimToFit(wanted, 0, 60, 4));
    }

    @Test
    public void aRowThatFitsIsLeftAlone() {
        List<String> wanted = Arrays.asList("com.a", "com.b");

        assertEquals(wanted, RunningOrder.trimToFit(wanted, 2000, 60, 4));
    }
}
