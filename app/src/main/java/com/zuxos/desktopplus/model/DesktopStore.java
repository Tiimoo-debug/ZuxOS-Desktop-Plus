package com.zuxos.desktopplus.model;

import android.content.Context;

import com.zuxos.desktopplus.core.Const;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Storage;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Persists the desktop layout (one file per display kind) as JSON. */
public final class DesktopStore {

    private final Context mCtx;
    private final String mFileName;
    private final List<Item> mItems = new ArrayList<>();

    public DesktopStore(Context ctx, boolean external) {
        mCtx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        mFileName = external ? Const.FILE_DESKTOP : "desktop-internal.json";
    }

    public List<Item> items() {
        return mItems;
    }

    public synchronized void load() {
        mItems.clear();
        File f = Storage.file(mCtx, mFileName);
        String raw = Storage.read(f);
        if (raw == null) {
            L.d("no saved desktop layout at " + f);
            return;
        }
        try {
            JSONObject root = new JSONObject(raw);
            JSONArray arr = root.optJSONArray("items");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    mItems.add(Item.fromJson(arr.getJSONObject(i)));
                }
            }
            L.d("loaded " + mItems.size() + " desktop items");
        } catch (Throwable t) {
            L.e("desktop layout is corrupt, starting empty", t);
            mItems.clear();
        }
    }

    /** Serialises here and writes on the IO thread; safe to call from a drag. */
    public synchronized void save() {
        Storage.writeAsync(Storage.file(mCtx, mFileName), exportJson());
    }

    /** Blocking write, for when the desktop is going away. */
    public synchronized void saveNow() {
        Storage.write(Storage.file(mCtx, mFileName), exportJson());
    }

    public synchronized String exportJson() {
        try {
            JSONArray arr = new JSONArray();
            for (Item i : mItems) {
                arr.put(i.toJson());
            }
            JSONObject root = new JSONObject();
            root.put("version", 1);
            root.put("items", arr);
            return root.toString(2);
        } catch (Throwable t) {
            L.e("export failed", t);
            return "{}";
        }
    }

    public synchronized void add(Item item) {
        mItems.add(item);
    }

    /** Removes a top-level item. Folder contents are managed by the folder itself. */
    public synchronized void remove(Item item) {
        mItems.remove(item);
    }

    /** Removes an item from whichever folder holds it. */
    public synchronized void removeFromFolders(Item item) {
        for (Item i : mItems) {
            i.children.remove(item);
        }
    }

    /** Pages in use, always at least one. */
    public int pageCount() {
        int max = 0;
        for (Item i : mItems) {
            max = Math.max(max, i.page);
        }
        return max + 1;
    }

    public boolean isPageEmpty(int page) {
        for (Item i : mItems) {
            if (i.page == page) {
                return false;
            }
        }
        return true;
    }

    /** Closes the gap left by an emptied page so pages stay consecutive. */
    public synchronized void removePage(int page) {
        for (Item i : mItems) {
            if (i.page > page) {
                i.page--;
            }
        }
    }

    public Item findById(String id) {
        for (Item i : mItems) {
            if (i.id.equals(id)) {
                return i;
            }
            for (Item c : i.children) {
                if (c.id.equals(id)) {
                    return c;
                }
            }
        }
        return null;
    }

    /** The folder holding {@code item}, or null when it sits directly on the desktop. */
    public Item parentOf(Item item) {
        for (Item i : mItems) {
            if (i.children.contains(item)) {
                return i;
            }
        }
        return null;
    }
}
