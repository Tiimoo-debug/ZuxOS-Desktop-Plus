package com.zuxos.desktopplus.hook;

import android.content.Context;

import com.zuxos.desktopplus.core.Const;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Storage;
import com.zuxos.desktopplus.logic.PinList;
import com.zuxos.desktopplus.model.Item;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The apps pinned to the taskbar - ours, in our own file.
 *
 * <p>Not the launcher's hotseat, on purpose. ZUI keeps that in a database of its own that nothing
 * here can read without guessing, and a bad write to it would damage the real launcher layout,
 * which is the one thing in this module that is not ours to risk. The cost of that decision is
 * stated plainly: these pins live beside the launcher's icons rather than among them, and they go
 * away with the module.
 *
 * <p>The file is the same shape as the drawer's, and the list rules it obeys - no duplicates, a
 * second drop moves rather than copies - are {@link PinList}, where they are tested.
 */
final class TaskbarPins {

    private static List<Item> sPins = new ArrayList<>();
    private static long sStamp = -1;

    private TaskbarPins() {
    }

    /** What is pinned, re-read whenever the file has changed underneath us. */
    static synchronized List<Item> pins(Context ctx) {
        if (ctx == null) {
            return Collections.emptyList();
        }
        try {
            File file = Storage.file(ctx, Const.FILE_TASKBAR_PINS);
            long stamp = file.lastModified();
            if (stamp == sStamp) {
                return sPins;
            }
            sStamp = stamp;
            sPins = read(file);
        } catch (Throwable t) {
            L.d("taskbar pins: could not be read (" + t + ")");
        }
        return sPins;
    }

    static synchronized void pin(Context ctx, Item item, int at) {
        if (ctx == null || item == null) {
            return;
        }
        write(ctx, PinList.add(pins(ctx), item, at));
        L.i("taskbar pins: pinned "
                + (item.pkg != null ? item.pkg : "folder \"" + item.label + "\""));
    }

    /** Moves a pin to a slot counted the way the bar shows it - see {@link PinList#move}. */
    static synchronized void move(Context ctx, String key, int at) {
        if (ctx == null || key == null) {
            return;
        }
        write(ctx, PinList.move(pins(ctx), key, at));
        L.i("taskbar pins: moved " + key + " to slot " + at);
    }

    static synchronized void unpin(Context ctx, String key) {
        if (ctx == null || key == null) {
            return;
        }
        write(ctx, PinList.remove(pins(ctx), key));
        L.i("taskbar pins: unpinned " + key);
    }

    private static List<Item> read(File file) {
        List<Item> out = new ArrayList<>();
        String raw = Storage.read(file);
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        try {
            JSONArray arr = new JSONObject(raw).optJSONArray("pins");
            for (int i = 0; arr != null && i < arr.length(); i++) {
                out.add(Item.fromJson(arr.getJSONObject(i)));
            }
        } catch (Throwable t) {
            // A file we cannot read is a taskbar with no pins, not a launcher that will not start.
            L.w("taskbar pins: the pin file will not parse, starting empty (" + t + ")");
            return new ArrayList<>();
        }
        return out;
    }

    private static void write(Context ctx, List<Item> pins) {
        sPins = pins;
        try {
            JSONArray arr = new JSONArray();
            for (Item pin : pins) {
                arr.put(pin.toJson());
            }
            File file = Storage.file(ctx, Const.FILE_TASKBAR_PINS);
            Storage.write(file, new JSONObject().put("pins", arr).toString());
            // Our own write, so the next read does not throw the list away and load it again.
            sStamp = file.lastModified();
        } catch (Throwable t) {
            L.e("taskbar pins: could not be saved", t);
        }
    }
}
