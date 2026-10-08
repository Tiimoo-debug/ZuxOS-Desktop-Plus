package com.zuxos.desktopplus.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/**
 * The desktop layout, through JSON and back.
 *
 * <p>This is the user's desktop on disk. A field dropped here is an icon that vanishes after a
 * reboot, and an exception thrown on an unfamiliar file is a desktop that comes back empty - which
 * is the worst thing this module can do, because nothing on the device says what happened.
 */
public class ItemJsonTest {

    private static Item roundTrip(Item original) throws Exception {
        JSONObject written = original.toJson();
        // Through a string on purpose: that is what the file holds, not the object.
        return Item.fromJson(new JSONObject(written.toString()));
    }

    @Test
    public void anAppSurvives() throws Exception {
        Item app = Item.app("com.foo.bar", "com.foo.bar.Main", 7L, "Bar");
        app.x = 3;
        app.y = 4;
        app.page = 2;

        Item back = roundTrip(app);

        assertEquals(Item.TYPE_APP, back.type);
        assertEquals("com.foo.bar", back.pkg);
        assertEquals("com.foo.bar.Main", back.cls);
        assertEquals(7L, back.userSerial);
        assertEquals("Bar", back.label);
        assertEquals(3, back.x);
        assertEquals(4, back.y);
        assertEquals(2, back.page);
        assertEquals(app.id, back.id);
        assertEquals(app.key(), back.key());
    }

    @Test
    public void aDeepShortcutSurvives() throws Exception {
        Item shortcut = Item.shortcut("com.foo.bar", "new_message", 0L, "New message");

        Item back = roundTrip(shortcut);

        assertEquals(Item.TYPE_SHORTCUT, back.type);
        assertEquals("new_message", back.shortcutId);
        assertEquals("com.foo.bar", back.pkg);
        assertEquals(shortcut.key(), back.key());
    }

    @Test
    public void anIntentShortcutSurvives() throws Exception {
        Item pinned = Item.intentShortcut("intent://example.com#Intent;scheme=https;end", "A page");

        Item back = roundTrip(pinned);

        assertEquals(Item.TYPE_SHORTCUT, back.type);
        assertEquals("intent://example.com#Intent;scheme=https;end", back.intentUri);
        assertNull(back.pkg);
        assertEquals(pinned.key(), back.key());
    }

    @Test
    public void aWidgetKeepsItsIdAndSpans() throws Exception {
        Item widget = Item.widget(42, "com.foo.bar", "com.foo.bar.Widget", 3, 2, "Clock");

        Item back = roundTrip(widget);

        assertEquals(Item.TYPE_WIDGET, back.type);
        assertEquals(42, back.widgetId);
        assertEquals(3, back.spanX);
        assertEquals(2, back.spanY);
        assertEquals(widget.key(), back.key());
    }

    @Test
    public void aFolderKeepsItsChildrenAndTheirOrder() throws Exception {
        Item folder = Item.folder("Tools");
        folder.children.add(Item.app("com.a", "com.a.Main", 0L, "A"));
        folder.children.add(Item.app("com.b", "com.b.Main", 0L, "B"));
        folder.children.add(Item.shortcut("com.c", "sc", 0L, "C"));

        Item back = roundTrip(folder);

        assertEquals(Item.TYPE_FOLDER, back.type);
        assertEquals(3, back.children.size());
        assertEquals("com.a", back.children.get(0).pkg);
        assertEquals("com.b", back.children.get(1).pkg);
        assertEquals("sc", back.children.get(2).shortcutId);
    }

    @Test
    public void foldersNestWithoutLosingAnything() throws Exception {
        Item outer = Item.folder("Outer");
        Item inner = Item.folder("Inner");
        inner.children.add(Item.app("com.deep", "com.deep.Main", 0L, "Deep"));
        outer.children.add(inner);

        Item back = roundTrip(outer);

        assertEquals(1, back.children.size());
        assertEquals("Inner", back.children.get(0).label);
        assertEquals("com.deep", back.children.get(0).children.get(0).pkg);
    }

    @Test
    public void anEmptyFolderWritesNoChildrenAndReadsBackEmpty() throws Exception {
        Item folder = Item.folder("Empty");

        assertTrue(folder.toJson().isNull("children"));
        assertTrue(roundTrip(folder).children.isEmpty());
    }

    /**
     * A file written by an older build, or a hand-edited one. Nothing here may throw: the store
     * reads the whole layout in one pass, so one bad entry would take the desktop with it.
     */
    @Test
    public void aFileMissingEverythingStillParses() throws Exception {
        Item back = Item.fromJson(new JSONObject("{}"));

        assertNotNull(back.id);
        assertEquals(Item.TYPE_APP, back.type);
        assertEquals(-1, back.x);
        assertEquals(-1, back.y);
        assertEquals(1, back.spanX);
        assertEquals(1, back.spanY);
        assertEquals(0, back.page);
        assertEquals(-1, back.widgetId);
        assertNull(back.pkg);
        assertNull(back.label);
        assertTrue(back.children.isEmpty());
    }

    @Test
    public void fieldsWeDoNotKnowAreIgnoredRatherThanFatal() throws Exception {
        JSONObject written = Item.app("com.foo", "com.foo.Main", 0L, "Foo").toJson();
        written.put("somethingFromALaterVersion", "whatever");
        written.put("anotherOne", 17);

        assertEquals("com.foo", Item.fromJson(written).pkg);
    }

    @Test
    public void nonsenseSpansAreCorrectedRatherThanKept() throws Exception {
        JSONObject written = Item.widget(1, "com.foo", "com.foo.W", 2, 2, "W").toJson();
        written.put("spanX", 0);
        written.put("spanY", -5);
        written.put("page", -3);

        Item back = Item.fromJson(written);

        // A zero-span widget occupies no cells and cannot be grabbed again.
        assertEquals(1, back.spanX);
        assertEquals(1, back.spanY);
        assertEquals(0, back.page);
    }

    @Test
    public void theKeyTellsTheTypesApart() {
        Item app = Item.app("com.foo", "com.foo.Main", 0L, "Foo");
        Item otherUser = Item.app("com.foo", "com.foo.Main", 10L, "Foo");
        Item shortcut = Item.shortcut("com.foo", "sc", 0L, "Shortcut");
        Item widget = Item.widget(5, "com.foo", "com.foo.W", 1, 1, "W");

        assertEquals(app.key(), Item.app("com.foo", "com.foo.Main", 0L, "Renamed").key());
        assertTrue(!app.key().equals(otherUser.key()));
        assertTrue(!app.key().equals(shortcut.key()));
        assertTrue(!app.key().equals(widget.key()));
        assertTrue(Item.folder("A").key().startsWith("folder:"));
    }
}
