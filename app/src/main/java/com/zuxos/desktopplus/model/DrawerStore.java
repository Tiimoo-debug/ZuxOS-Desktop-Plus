package com.zuxos.desktopplus.model;

import android.content.Context;

import com.zuxos.desktopplus.core.Const;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Storage;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Drawer state: a custom app order, drawer folders, and hidden apps.
 *
 * <p>Apps that live inside a drawer folder are not repeated in the flat list, which is what
 * makes folders feel like folders rather than duplicates.
 */
public final class DrawerStore {

    private final Context mCtx;
    private final List<String> mOrder = new ArrayList<>();
    private final List<Item> mFolders = new ArrayList<>();
    private final Set<String> mHidden = new HashSet<>();

    public DrawerStore(Context ctx) {
        mCtx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
    }

    public List<String> order() {
        return mOrder;
    }

    public List<Item> folders() {
        return mFolders;
    }

    public Set<String> hidden() {
        return mHidden;
    }

    public Item folderContaining(String key) {
        for (Item f : mFolders) {
            for (Item c : f.children) {
                if (c.key().equals(key)) {
                    return f;
                }
            }
        }
        return null;
    }

    public Set<String> keysInFolders() {
        Set<String> keys = new LinkedHashSet<>();
        for (Item f : mFolders) {
            for (Item c : f.children) {
                keys.add(c.key());
            }
        }
        return keys;
    }

    public synchronized void load() {
        mOrder.clear();
        mFolders.clear();
        mHidden.clear();
        String raw = Storage.read(Storage.file(mCtx, Const.FILE_DRAWER));
        if (raw == null) {
            return;
        }
        try {
            JSONObject root = new JSONObject(raw);
            JSONArray order = root.optJSONArray("order");
            if (order != null) {
                for (int i = 0; i < order.length(); i++) {
                    mOrder.add(order.getString(i));
                }
            }
            JSONArray folders = root.optJSONArray("folders");
            if (folders != null) {
                for (int i = 0; i < folders.length(); i++) {
                    mFolders.add(Item.fromJson(folders.getJSONObject(i)));
                }
            }
            JSONArray hidden = root.optJSONArray("hidden");
            if (hidden != null) {
                for (int i = 0; i < hidden.length(); i++) {
                    mHidden.add(hidden.getString(i));
                }
            }
        } catch (Throwable t) {
            L.e("drawer state is corrupt, starting empty", t);
        }
    }

    public synchronized void save() {
        try {
            JSONObject root = new JSONObject();
            root.put("version", 1);
            root.put("order", new JSONArray(mOrder));
            JSONArray folders = new JSONArray();
            for (Item f : mFolders) {
                folders.put(f.toJson());
            }
            root.put("folders", folders);
            root.put("hidden", new JSONArray(new ArrayList<>(mHidden)));
            Storage.write(Storage.file(mCtx, Const.FILE_DRAWER), root.toString(2));
        } catch (Throwable t) {
            L.e("could not save drawer state", t);
        }
    }
}
