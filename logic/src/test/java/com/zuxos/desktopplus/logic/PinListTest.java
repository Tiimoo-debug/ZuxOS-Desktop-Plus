package com.zuxos.desktopplus.logic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.zuxos.desktopplus.model.Item;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** What happens when apps are dropped on, and taken off, the taskbar. */
public class PinListTest {

    private static Item app(String pkg) {
        return Item.app(pkg, pkg + ".Main", 0L, pkg);
    }

    private static List<String> packages(List<Item> pins) {
        List<String> out = new ArrayList<>();
        for (Item pin : pins) {
            out.add(pin.pkg);
        }
        return out;
    }

    @Test
    public void aDroppedAppIsPinnedAtTheEnd() {
        List<Item> pins = PinList.add(Collections.<Item>emptyList(), app("com.a"));
        pins = PinList.add(pins, app("com.b"));

        assertEquals(Arrays.asList("com.a", "com.b"), packages(pins));
    }

    @Test
    public void aDroppedAppCanLandBetweenTwoOthers() {
        List<Item> pins = Arrays.asList(app("com.a"), app("com.b"), app("com.c"));

        assertEquals(Arrays.asList("com.new", "com.a", "com.b", "com.c"),
                packages(PinList.add(pins, app("com.new"), 0)));
        assertEquals(Arrays.asList("com.a", "com.new", "com.b", "com.c"),
                packages(PinList.add(pins, app("com.new"), 1)));
        assertEquals(Arrays.asList("com.a", "com.b", "com.c", "com.new"),
                packages(PinList.add(pins, app("com.new"), 99)));
    }

    /** Dropping an app that is already there moves it; it never appears twice. */
    @Test
    public void pinningTheSameAppAgainMovesItRatherThanDuplicatingIt() {
        List<Item> pins = Arrays.asList(app("com.a"), app("com.b"), app("com.c"));

        List<Item> after = PinList.add(pins, app("com.c"), 0);

        assertEquals(Arrays.asList("com.c", "com.a", "com.b"), packages(after));
    }

    @Test
    public void theSameAppUnderADifferentNameIsStillTheSamePin() {
        List<Item> pins = PinList.add(Collections.<Item>emptyList(), app("com.a"));

        Item renamed = Item.app("com.a", "com.a.Main", 0L, "Something else");

        assertEquals(1, PinList.add(pins, renamed).size());
        assertTrue(PinList.holds(pins, renamed.key()));
    }

    @Test
    public void theSameAppOnAWorkProfileIsADifferentPin() {
        List<Item> pins = PinList.add(Collections.<Item>emptyList(), app("com.a"));

        Item work = Item.app("com.a", "com.a.Main", 10L, "Work");

        assertEquals(2, PinList.add(pins, work).size());
    }

    @Test
    public void unpinningTakesOnlyThatOne() {
        List<Item> pins = Arrays.asList(app("com.a"), app("com.b"), app("com.c"));

        List<Item> after = PinList.remove(pins, app("com.b").key());

        assertEquals(Arrays.asList("com.a", "com.c"), packages(after));
    }

    @Test
    public void unpinningSomethingThatIsNotPinnedChangesNothing() {
        List<Item> pins = Arrays.asList(app("com.a"));

        assertEquals(1, PinList.remove(pins, app("com.z").key()).size());
        assertEquals(1, PinList.remove(pins, null).size());
    }

    @Test
    public void theLastPinCanBeTakenOff() {
        List<Item> pins = PinList.add(Collections.<Item>emptyList(), app("com.a"));

        assertTrue(PinList.remove(pins, app("com.a").key()).isEmpty());
    }

    @Test
    public void whatIsPassedInIsNotChanged() {
        List<Item> pins = new ArrayList<>(Arrays.asList(app("com.a")));

        PinList.add(pins, app("com.b"));
        PinList.remove(pins, app("com.a").key());

        assertEquals(1, pins.size());
    }

    @Test
    public void nothingIsPinnedToStartWith() {
        assertFalse(PinList.holds(null, "anything"));
        assertEquals(-1, PinList.indexOf(null, "anything"));
        assertTrue(PinList.add(null, app("com.a")).size() == 1);
        assertTrue(PinList.remove(null, "com.a").isEmpty());
    }

    /** A pinned folder stands for everything inside it, or its apps show up twice. */
    @Test
    public void aPinnedFolderSpeaksForTheAppsInIt() {
        Item folder = Item.folder("Tools");
        folder.children.add(app("com.inside"));
        folder.children.add(app("com.alsoInside"));

        List<String> packages = PinList.packagesOf(Arrays.asList(app("com.a"), folder));

        assertTrue(packages.contains("com.a"));
        assertTrue(packages.contains("com.inside"));
        assertTrue(packages.contains("com.alsoInside"));
    }

    @Test
    public void thePackageListHasNoRepeats() {
        Item folder = Item.folder("Tools");
        folder.children.add(app("com.a"));

        assertEquals(Collections.singletonList("com.a"),
                PinList.packagesOf(Arrays.asList(app("com.a"), folder)));
    }
}
