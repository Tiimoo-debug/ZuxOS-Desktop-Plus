package com.zuxos.desktopplus.core;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Settings-app side of the preferences.
 *
 * <p>LSPosed makes {@code MODE_WORLD_READABLE} work again so hooked processes can read the
 * file through {@code XSharedPreferences}. Plain Android throws for that mode, which is how
 * we detect that the module is not activated.
 */
public final class Prefs {

    private Prefs() {
    }

    public static SharedPreferences get(Context ctx) {
        try {
            @SuppressWarnings("deprecation")
            SharedPreferences p = ctx.getSharedPreferences(Const.PREFS, Context.MODE_WORLD_READABLE);
            return p;
        } catch (SecurityException e) {
            // Module not enabled in LSPosed (or LSPosed absent): keep the UI usable.
            return ctx.getSharedPreferences(Const.PREFS, Context.MODE_PRIVATE);
        }
    }
}
