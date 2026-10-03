package com.zuxos.desktopplus.logic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.zuxos.desktopplus.model.Item;

import org.junit.Test;

/**
 * The payload that has to survive leaving its own window.
 *
 * <p>A drag from the stock drawer to the desktop crosses from one window to another, and what
 * arrives on the other side is only ever what was written here. A field lost in this encoding is
 * an icon that lands on the desktop and will not open.
 */
public class DragCodecTest {

    @Test
    public void anAppArrivesWhole() {
        Item app = Item.app("com.foo.bar", "com.foo.bar.Main", 11L, "Bar");

        Item back = DragCodec.decode(DragCodec.encode(app));

        assertNotNull(back);
        assertEquals(Item.TYPE_APP, back.type);
        assertEquals("com.foo.bar", back.pkg);
        assertEquals("com.foo.bar.Main", back.cls);
        assertEquals(11L, back.userSerial);
        assertEquals("Bar", back.label);
        assertEquals(app.key(), back.key());
    }

    @Test
    public void aDeepShortcutArrivesWhole() {
        Item shortcut = Item.shortcut("com.foo.bar", "new_message", 0L, "New message");

        Item back = DragCodec.decode(DragCodec.encode(shortcut));

        assertEquals("new_message", back.shortcutId);
        assertEquals(shortcut.key(), back.key());
    }

    @Test
    public void aPinnedIntentArrivesWhole() {
        Item pinned = Item.intentShortcut("intent://example.com#Intent;scheme=https;end", "Page");

        Item back = DragCodec.decode(DragCodec.encode(pinned));

        assertEquals("intent://example.com#Intent;scheme=https;end", back.intentUri);
    }

    @Test
    public void aFolderArrivesWithItsContents() {
        Item folder = Item.folder("Tools");
        folder.children.add(Item.app("com.a", "com.a.Main", 0L, "A"));
        folder.children.add(Item.app("com.b", "com.b.Main", 0L, "B"));

        Item back = DragCodec.decode(DragCodec.encode(folder));

        assertEquals(Item.TYPE_FOLDER, back.type);
        assertEquals(2, back.children.size());
        assertEquals("com.b", back.children.get(1).pkg);
    }

    /** Another app's drag, a text selection, a file. None of it may throw. */
    @Test
    public void anythingThatIsNotOursDecodesToNothing() {
        assertNull(DragCodec.decode(null));
        assertNull(DragCodec.decode(""));
        assertNull(DragCodec.decode("not json at all"));
        assertNull(DragCodec.decode("{}"));
        assertNull(DragCodec.decode("{\"type\":0}"));
        assertNull(DragCodec.decode("[1,2,3]"));
        assertNull(DragCodec.encode(null));
    }

    @Test
    public void theSourceRidesOnTheLabelBecauseTheDataIsNotReadableYet() {
        assertEquals(0, DragCodec.sourceFrom(DragCodec.labelFor(0)));
        assertEquals(1, DragCodec.sourceFrom(DragCodec.labelFor(1)));
        assertEquals(2, DragCodec.sourceFrom(DragCodec.labelFor(2)));
    }

    @Test
    public void someoneElsesDragIsNotMistakenForOurs() {
        assertEquals(DragCodec.UNKNOWN, DragCodec.sourceFrom(null));
        assertEquals(DragCodec.UNKNOWN, DragCodec.sourceFrom(""));
        assertEquals(DragCodec.UNKNOWN, DragCodec.sourceFrom("Chrome tab"));
        assertEquals(DragCodec.UNKNOWN, DragCodec.sourceFrom("zuxos-drag:"));
        assertEquals(DragCodec.UNKNOWN, DragCodec.sourceFrom("zuxos-drag:later"));
    }
}
