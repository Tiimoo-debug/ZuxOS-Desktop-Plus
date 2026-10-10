package com.zuxos.desktopplus.hook;

import android.content.Context;
import android.graphics.drawable.Drawable;

/**
 * ZUI's own popup look, from ZUX Home's resources: what its app menus are drawn with, light or
 * dark as ZUI itself is. Where a resource is missing, a plain value close to ZUI's.
 */
public final class ZuiLook {

    private ZuiLook() {
    }

    /** ZUI's popup panel ({@code ic_popupcontainer_bg_new}): its fill, hairline and corners. */
    public static Drawable popupBackground(Context ctx) {
        int id = ctx.getResources().getIdentifier("ic_popupcontainer_bg_new", "drawable",
                ctx.getPackageName());
        return id != 0 ? ctx.getDrawable(id) : null;
    }

    /** The text of ZUI's popup rows ({@code popup_container_text_color}). */
    public static int popupText(Context ctx) {
        return colour(ctx, "popup_container_text_color", 0xFF1A1A1A);
    }

    /** The colour ZUI draws its shortcut icons in ({@code ic_system_shortcut_solid}). */
    public static int shortcutIcon(Context ctx) {
        return colour(ctx, "ic_system_shortcut_solid", 0xFF666666);
    }

    private static int colour(Context ctx, String name, int fallback) {
        int id = ctx.getResources().getIdentifier(name, "color", ctx.getPackageName());
        return id != 0 ? ctx.getColor(id) : fallback;
    }
}
