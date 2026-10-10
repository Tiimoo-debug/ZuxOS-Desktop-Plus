package com.zuxos.desktopplus.hook.drawer;

import com.zuxos.desktopplus.core.Health;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * What ZUI's folder code writes for our folders goes to our model, never ZUI's database.
 *
 * <p>ZUI's {@code Folder} saves through {@code ModelWriter}: positions when it is bound
 * ({@code moveItemsInDatabase}), its name ({@code updateItemInDatabase}), and moves, changes and
 * removals of what is in it. Our folders and their apps carry ids or containers from our own
 * range ({@link ZuiFolders#ID_BASE}), so those calls are told apart from ZUI's own: ours are not
 * passed on, and the name and order are kept in our model; every other call - ZUI's home screen,
 * its taskbar, its own folders - goes through untouched.
 */
final class ZuiFolderWrites {

    private static final String WRITER = "com.android.launcher3.model.ModelWriter";
    private static final String ITEM = "com.android.launcher3.model.data.ItemInfo";

    private static Field sId;
    private static Field sContainer;
    private static Field sTitle;
    private static final Set<String> SAID = new HashSet<>();

    private ZuiFolderWrites() {
    }

    static void install(ClassLoader loader) {
        try {
            Class<?> writer = Reflect.findClass(WRITER, loader);
            Class<?> item = Reflect.findClass(ITEM, loader);
            if (writer == null || item == null) {
                L.w("zui folder writes: ZUI's ModelWriter not found");
                return;
            }
            sId = item.getDeclaredField("id");
            sContainer = item.getDeclaredField("container");
            sTitle = item.getDeclaredField("title");
            XC_MethodHook gate = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (ours(param.method.getName(), param.args)) {
                            param.setResult(null);
                        }
                    } catch (Throwable t) {
                        L.d("zui folder writes: " + param.method.getName() + " (" + t + ")");
                    }
                }
            };
            int hooked = 0;
            for (String name : new String[]{"addItemToDatabase", "addOrMoveItemInDatabase",
                    "moveItemInDatabase", "moveItemsInDatabase", "modifyItemInDatabase",
                    "updateItemInDatabase", "deleteItemFromDatabase", "deleteItemsFromDatabase",
                    "deleteCollectionAndContentsFromDatabase"}) {
                hooked += XposedBridge.hookAllMethods(writer, name, gate).size();
            }
            Health.hooked("drawer: our folders' saves kept out of ZUI's database", hooked);
            L.i("zui folder writes: our folders' saves go to our model x" + hooked);
        } catch (Throwable t) {
            L.w("zui folder writes: not installed (" + t + ")");
        }
    }

    /** Whether a write is ours - and if so, what of it our model keeps. */
    private static boolean ours(String name, Object[] args) throws Exception {
        if (args.length == 0 || args[0] == null) {
            return false;
        }
        Object first = args[0];
        if (name.equals("moveItemsInDatabase") && args.length >= 2 && args[1] instanceof Integer) {
            if (!isOurs((Integer) args[1])) {
                return false;
            }
            ZuiFolders.reordered((Integer) args[1], (List<?>) first);
            return true;
        }
        if (name.equals("deleteItemsFromDatabase") && first instanceof Collection) {
            return withoutOurs(args);
        }
        if (!sId.getDeclaringClass().isInstance(first)) {
            // The other forms (a predicate, say) only reach what ZUI itself has loaded.
            return false;
        }
        int id = sId.getInt(first);
        int container = sContainer.getInt(first);
        boolean target = args.length >= 2 && args[1] instanceof Integer
                && isOurs((Integer) args[1]);
        if (!isOurs(id) && !isOurs(container) && !target) {
            return false;
        }
        if (name.equals("updateItemInDatabase") && isOurs(id)) {
            ZuiFolders.renamed(id, (CharSequence) sTitle.get(first));
        } else {
            say(name);
        }
        return true;
    }

    /** A batch of deletes: ours taken out of it, the rest left to ZUI. */
    private static boolean withoutOurs(Object[] args) throws Exception {
        List<Object> kept = new ArrayList<>();
        boolean any = false;
        for (Object item : (Collection<?>) args[0]) {
            if (sId.getDeclaringClass().isInstance(item)
                    && (isOurs(sId.getInt(item)) || isOurs(sContainer.getInt(item)))) {
                any = true;
            } else {
                kept.add(item);
            }
        }
        if (!any) {
            return false;
        }
        say("deleteItemsFromDatabase");
        if (kept.isEmpty()) {
            return true;
        }
        args[0] = kept;
        return false;
    }

    private static boolean isOurs(int idOrContainer) {
        return idOrContainer <= ZuiFolders.ID_BASE;
    }

    private static void say(String name) {
        if (SAID.add(name)) {
            L.i("zui folder writes: ZUI's " + name + " for our folder kept out of its database");
        }
    }
}
