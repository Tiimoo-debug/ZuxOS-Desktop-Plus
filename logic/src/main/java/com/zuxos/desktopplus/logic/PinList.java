package com.zuxos.desktopplus.logic;

import com.zuxos.desktopplus.model.Item;

import java.util.ArrayList;
import java.util.List;

/**
 * The apps pinned to the taskbar, as a list.
 *
 * <p>Pinning is the sort of thing that looks trivial until the same app is dropped twice, or an
 * app is dropped onto the place it already occupies, or the last one is unpinned. None of that
 * needs a taskbar to get wrong, so none of it is written where only a taskbar can check it.
 *
 * <p>Identity is {@link Item#key()} throughout - the same app under a different label, or dragged
 * from a different place, is the same pin.
 */
public final class PinList {

    /** Dropped at no particular place. */
    public static final int AT_THE_END = -1;

    private PinList() {
    }

    /**
     * The list with an item pinned at a position.
     *
     * <p>An app that is already pinned moves rather than appears twice. Returns a new list; the
     * one passed in is not touched.
     */
    public static List<Item> add(List<Item> pins, Item item, int at) {
        List<Item> out = new ArrayList<>();
        if (item == null) {
            return pins == null ? out : new ArrayList<>(pins);
        }
        String key = item.key();
        if (pins != null) {
            for (Item pin : pins) {
                if (!key.equals(pin.key())) {
                    out.add(pin);
                }
            }
        }
        int index = at == AT_THE_END || at > out.size() ? out.size() : Math.max(0, at);
        out.add(index, item);
        return out;
    }

    /**
     * The list with a pin moved to a slot, where the slot is counted in the list as it is on
     * screen - with the moved pin still in it.
     *
     * <p>That is the only index a drop can give: the bar is measured while the dragged icon is
     * still standing in its old place. So a pin moved to the right lands one slot earlier than the
     * raw index once it has left its own slot, or every move right would overshoot by one.
     */
    public static List<Item> move(List<Item> pins, String key, int at) {
        List<Item> out = pins == null ? new ArrayList<>() : new ArrayList<>(pins);
        int from = indexOf(out, key);
        if (from < 0) {
            return out;
        }
        Item moving = out.remove(from);
        if (at == AT_THE_END || at > out.size()) {
            out.add(moving);
            return out;
        }
        int to = Math.max(0, at);
        if (to > from) {
            to--;
        }
        out.add(to, moving);
        return out;
    }

    /** The list without whatever was pinned under this key. */
    public static List<Item> remove(List<Item> pins, String key) {
        List<Item> out = new ArrayList<>();
        if (pins == null) {
            return out;
        }
        for (Item pin : pins) {
            if (key == null || !key.equals(pin.key())) {
                out.add(pin);
            }
        }
        return out;
    }

    public static boolean holds(List<Item> pins, String key) {
        return indexOf(pins, key) >= 0;
    }

    public static int indexOf(List<Item> pins, String key) {
        if (pins == null || key == null) {
            return -1;
        }
        for (int i = 0; i < pins.size(); i++) {
            if (key.equals(pins.get(i).key())) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The packages pinned as apps in their own right, folders left out.
     *
     * <p>What decides whether an open app needs an icon of its own: an app inside a pinned folder
     * still does, because the folder says "something in here is open", not which app or that
     * you can switch to it.
     */
    public static List<String> directPackagesOf(List<Item> pins) {
        List<String> out = new ArrayList<>();
        if (pins == null) {
            return out;
        }
        for (Item pin : pins) {
            if (pin != null && pin.pkg != null && pin.children.isEmpty()
                    && !out.contains(pin.pkg)) {
                out.add(pin.pkg);
            }
        }
        return out;
    }
}
