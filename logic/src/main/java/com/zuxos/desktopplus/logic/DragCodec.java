package com.zuxos.desktopplus.logic;

import com.zuxos.desktopplus.model.Item;

import org.json.JSONObject;

/**
 * An item, written down so it can cross a window.
 *
 * <p>A drag inside one window carries its payload as a plain object, which costs nothing and keeps
 * every reference intact. That stops working the moment the drag leaves the window it started in:
 * Android hands the local state only to views in the activity that began the drag, and the desktop
 * here is in the launcher's activity while the taskbar and the stock drawer are windows of the
 * taskbar's own. Between them the object arrives as null.
 *
 * <p>So anything that crosses is written into the drag's ClipData instead, which is what ClipData
 * is for. This is that encoding, and it is here rather than beside the views because getting it
 * wrong means an icon that silently refuses to be dropped - a fault that costs a flash to see and
 * a second to test.
 */
public final class DragCodec {

    /** The type on the clip, so a drop target can recognise its own drag before it can read it. */
    public static final String MIME = "application/vnd.zuxos.desktopplus.item";

    /** What {@link #sourceFrom} answers for a label that is not one of ours. */
    public static final int UNKNOWN = -1;

    private static final String LABEL = "zuxos-drag:";

    private DragCodec() {
    }

    /**
     * The clip's label, which carries where the drag came from.
     *
     * <p>In the label on purpose: a drop target has to decide whether to accept a drag when it
     * starts, and at that moment Android gives it the description but not the data. The data -
     * which item - arrives only on the drop.
     */
    public static String labelFor(int source) {
        return LABEL + source;
    }

    /** Where a drag came from, or {@link #UNKNOWN} if this is not one of ours. */
    public static int sourceFrom(CharSequence label) {
        if (label == null) {
            return UNKNOWN;
        }
        String text = label.toString();
        if (!text.startsWith(LABEL)) {
            return UNKNOWN;
        }
        try {
            return Integer.parseInt(text.substring(LABEL.length()).trim());
        } catch (NumberFormatException notOurs) {
            return UNKNOWN;
        }
    }

    /** The item as text, or null if there is nothing to write. */
    public static String encode(Item item) {
        if (item == null) {
            return null;
        }
        try {
            return item.toJson().toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * The item back, or null for anything that is not one.
     *
     * <p>Null rather than an exception: this parses whatever another window chose to put on the
     * clipboard, and a drop that does nothing is better than one that takes the launcher down.
     */
    public static Item decode(CharSequence text) {
        if (text == null || text.length() == 0) {
            return null;
        }
        try {
            Item item = Item.fromJson(new JSONObject(text.toString()));
            // An item with nothing to launch is not an item. It would land on the desktop as an
            // icon that cannot be opened and cannot easily be told apart from a bug.
            boolean launchable = item.pkg != null || item.intentUri != null
                    || item.type == Item.TYPE_FOLDER;
            return launchable ? item : null;
        } catch (Throwable notOurs) {
            return null;
        }
    }
}
