package com.zuxos.desktopplus.hook;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.provider.Settings;
import android.service.quicksettings.TileService;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

import com.zuxos.desktopplus.core.Const;
import com.zuxos.desktopplus.core.Health;
import com.zuxos.desktopplus.core.Reflect;
import com.zuxos.desktopplus.hook.panel.Notifications;
import com.zuxos.desktopplus.hook.taskbar.TaskbarScope;
import com.zuxos.desktopplus.hook.taskbar.TaskbarTray;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What each planned feature needs to know about this device, gathered with every probe.
 *
 * <p>Evidence first: before a roadmap feature is built, the device has already said what it is
 * made of. Each section names the roadmap rows it serves. Read-only, like the rest of the probe;
 * features drawn entirely by the module (#2, #4, #6) need nothing from the device, and Maximize
 * (#7) and keeping apps alive (#12) have their own traces already.
 */
public final class RoadmapProbe {

    /** Names that say "where on screen" in a taskbar's code, for a bar on any edge (#14). */
    private static final Pattern POSITION = Pattern.compile(
            "(?i).*(vertical|position|gravity|side|edge|orientation|rotat|landscape|seascape).*");
    private static final int MAX_TILES = 40;
    private static final int MAX_TEXTS = 15;
    private static final int MAX_NAMES = 60;
    /** ZUI's own system features the roadmap depends on, by the names its code checks. */
    private static final String[] ZUI_FEATURES = {"ZuiMemoryAcceleration", "ZuiLmkWhiteList",
            "ZuiAdjCustomize", "ZuiAppPersistenceRanking", "ZuiExtendReclaim", "ZuiAutorunManager",
            "ZuiPerformancePolicy", "ZuiAutoRefreshRate", "ZuiAutoRefreshRateForVideo",
            "ZuiPcMode", "ZuiOVExtDisplay", "ZuiDpOut"};
    /** What ZUI's window frames are styled with, by name: SystemUI's, then the system's. */
    private static final String[] DECOR_DIMENS = {"freeform_decor_caption_height",
            "ovc_wd_freeform_task_corner_radius_pad", "ov_window_decor_caption_bar_width_pad",
            "freeform_decor_shadow_focused_thickness", "freeform_decor_shadow_unfocused_thickness",
            "pcmode_task_corner_radius", "pcmode_dcv_caption_bar_height"};
    private static final String[] DECOR_SYSTEM_DIMENS = {"ov_pc_mode_task_corner_radius",
            "ov_freeform_task_corner_radius_pad", "ovc_dcv_caption_bar_height_4_pad"};
    private static final String[] DECOR_COLORS = {"zuipcmode_apptitle_zui_light_color",
            "zuipcmode_apptitle_zui_dark_color"};
    private static final String[] DECOR_LAYOUTS = {"zui_desktop_window_decor",
            "zui_desktop_window_decor_freeform", "pcmode_window_decor", "desktop_mode_app_header"};

    /**
     * Read through root, and only read. Where ZUI's and Lenovo's apps are (#10, #11, #18 - and
     * which files to copy for decompiling), the settings that speak of the taskbar, desktop mode,
     * windows and animation (#10, #11, #14), the window manager's feature flags, work mode's keys
     * and whether Work Launcher is the home, running or showing a window (#19), SystemUI's windows
     * on each display (#5), the display SystemUI last gave each window decoration - its window
     * menu opens there - the resource overlays on SystemUI (#10, #16), Game Assistant's mode
     * (#18), the window manager's shell as SystemUI reports it (#10 window looks, #11 window
     * animations), and the boot animation and the root solution a boot-animation module would sit
     * on (#9).
     */
    public static final String ROOT_READ = """
            echo
            echo "=== roadmap"
            echo "--- ZUI and Lenovo packages, with their files (#10, #11, #18)"
            pm list packages -f 2>/dev/null | grep -i -E 'zui|lenovo|zuk|legion' | head -60
            pm list packages -f 2>/dev/null | grep -i -E 'game|perf|thermal|powerkeeper' | head -20
            pm list packages -f com.android.systemui 2>/dev/null
            ls -l /system/framework/services.jar /system/framework/oat/arm64/services.* 2>/dev/null
            echo "--- settings about the taskbar, desktop mode, windows and animation (#10, #11, #14)"
            for ns in secure system global; do
              settings list $ns 2>/dev/null | grep -i -E 'taskbar|navbar|nav_bar|navigation|desktop|freeform|resiz|caption|window_animation|transition_animation|animator_duration|zui' | sed "s/^/$ns: /"
            done | head -80
            echo "--- window manager flags (#10, #11)"
            device_config list window_manager 2>/dev/null | grep -i -E 'desktop|freeform|caption|decor' | head -30
            getprop | grep -i -E 'desktop_mode|freeform|caption' | head -30
            echo "--- work mode, ZUI's PC mode (#19)"
            for ns in secure system global; do
              settings list $ns 2>/dev/null | grep -i -E 'pc_?mode|work_?mode' | sed "s/^/$ns: /"
            done | head -30
            getprop | grep -i -E 'pc_?mode|ovdesktop' | head -10
            echo "home: $(cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME 2>/dev/null | tail -1)"
            echo "Work Launcher pid: $(pidof com.zui.desktoplauncher)"
            dumpsys activity processes com.zui.desktoplauncher 2>/dev/null | grep -E 'ProcessRecord|oom:|curProcState|lastPss' | head -8
            dumpsys window windows 2>/dev/null | grep -E 'Window[{].*com[.]zui[.]desktoplauncher' | head -10
            echo "--- SystemUI's windows on each display (#5 notifications on the monitor)"
            dumpsys window windows 2>/dev/null | awk '/^  Window #/ { w = $0 } /mDisplayId=/ { if (w ~ /StatusBar|Notification|HeadsUp|Shade|systemui/) { match($0, /mDisplayId=[0-9]+/); print substr($0, RSTART, RLENGTH) " " w } }' | head -30
            echo "--- SystemUI's window decorations, as it logged them (window menu on the wrong screen)"
            logcat -d -b main 2>/dev/null | grep -E 'OVC *: *(WindowDecoration|OvHandleMenu)' | tail -40
            echo "--- resource overlays on SystemUI (#10 window looks, #16)"
            cmd overlay list com.android.systemui 2>/dev/null | head -30
            echo "fabricated overlays offered: $(cmd overlay help 2>/dev/null | grep -c fabricate)"
            echo "--- Game Assistant's game mode (#18)"
            echo "game_helper_game_mode = $(settings get global game_helper_game_mode 2>/dev/null)"
            echo "--- the window manager shell, as SystemUI reports it (#10, #11)"
            dumpsys activity service com.android.systemui/.SystemUIService WMShell 2>/dev/null | head -150
            echo "--- boot animation (#9)"
            for f in /product/media/bootanimation.zip /system/media/bootanimation.zip /system_ext/media/bootanimation.zip /oem/media/bootanimation.zip /vendor/media/bootanimation.zip; do
              if [ -f "$f" ]; then
                ls -l "$f"
                (unzip -p "$f" desc.txt 2>/dev/null || /data/adb/magisk/busybox unzip -p "$f" desc.txt 2>/dev/null) | head -6
              fi
            done
            echo "magisk $(magisk -v 2>/dev/null) $(magisk -V 2>/dev/null), kernelsu $([ -d /data/adb/ksu ] && echo yes || echo no)"
            echo "modules: $(ls /data/adb/modules 2>/dev/null | tr '\\n' ' ')"
            true
            """;

    private RoadmapProbe() {
    }

    /** The parts read in this process. Main thread: it reads views. */
    public static String now(Context ctx) {
        StringBuilder sb = new StringBuilder("\n=== roadmap: what each planned feature needs\n");
        section(sb, () -> versions(ctx));
        section(sb, Health::describe);
        section(sb, DisplayTimeline::describe);
        section(sb, () -> tiles(ctx));
        section(sb, RoadmapProbe::zuiFeatures);
        section(sb, () -> windowFrames(ctx));
        section(sb, () -> Notifications.describe(ctx));
        section(sb, RoadmapProbe::taskbars);
        return sb.toString();
    }

    private interface Part {
        String read() throws Exception;
    }

    /** One part failing leaves the others standing. */
    private static void section(StringBuilder sb, Part part) {
        try {
            sb.append(part.read());
        } catch (Throwable t) {
            sb.append("\n  (a section could not be read: ").append(t).append(")\n");
        }
    }

    /** What ZUI updates change (#13): the firmware, the apps this module hooks, the home. */
    private static String versions(Context ctx) {
        StringBuilder sb = new StringBuilder("\nversions (#13 adapting to ZUI updates)\n");
        sb.append("  firmware: ").append(Build.DISPLAY).append('\n')
                .append("  fingerprint: ").append(Build.FINGERPRINT).append('\n')
                .append("  android ").append(Build.VERSION.RELEASE).append(" (sdk ")
                .append(Build.VERSION.SDK_INT).append("), security patch ")
                .append(Build.VERSION.SECURITY_PATCH).append('\n');
        PackageManager pm = ctx.getPackageManager();
        for (String pkg : new String[]{ctx.getPackageName(), "com.android.systemui",
                Const.MODULE_PKG}) {
            try {
                PackageInfo info = pm.getPackageInfo(pkg, 0);
                sb.append("  ").append(pkg).append(' ').append(info.versionName)
                        .append(" (").append(info.getLongVersionCode()).append("), updated ")
                        .append(String.format(Locale.ROOT, "%tF", info.lastUpdateTime))
                        .append('\n');
            } catch (Throwable t) {
                sb.append("  ").append(pkg).append(": not readable\n");
            }
        }
        ResolveInfo home = pm.resolveActivity(new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
        sb.append("  default home: ").append(home != null && home.activityInfo != null
                ? home.activityInfo.packageName : "none chosen").append('\n');
        return sb.toString();
    }

    /** What editing the quick panel's tiles would work with (#3). */
    private static String tiles(Context ctx) {
        StringBuilder sb = new StringBuilder("\nquick settings tiles (#3 tile editing)\n");
        sb.append("  system tile list: ")
                .append(Settings.Secure.getString(ctx.getContentResolver(), "sysui_qs_tiles"))
                .append('\n');
        sb.append("  this process may bind tiles: ").append(ctx.checkSelfPermission(
                "android.permission.BIND_QUICK_SETTINGS_TILE")
                == PackageManager.PERMISSION_GRANTED).append('\n');
        List<ResolveInfo> services = ctx.getPackageManager().queryIntentServices(
                new Intent(TileService.ACTION_QS_TILE), 0);
        sb.append("  tiles other apps offer: ").append(services.size()).append('\n');
        for (int i = 0; i < Math.min(MAX_TILES, services.size()); i++) {
            sb.append("    ").append(services.get(i).serviceInfo.packageName).append('/')
                    .append(services.get(i).serviceInfo.name).append('\n');
        }
        return sb.toString();
    }

    /**
     * Which of ZUI's own system features this firmware has switched on: its memory cleaner and
     * kill whitelists (#12), its touch boosts named for the refresh rate (#17, #18), PC mode and
     * the monitor's desktop (#19). The system's code asks the same question before doing any of
     * it.
     */
    private static String zuiFeatures() throws Exception {
        StringBuilder sb = new StringBuilder("\nZUI system features (#12, #17, #18, #19)\n ");
        Method enabled = Class.forName("com.lgsi.config.LgsiFeatures")
                .getMethod("enabled", String.class);
        for (String name : ZUI_FEATURES) {
            sb.append(' ').append(name).append('=').append(enabled.invoke(null, name));
        }
        return sb.append('\n').toString();
    }

    /**
     * What ZUI's window frames are made of on this firmware (#10, #16): SystemUI draws them from
     * these layouts, sizes and colours, so a resource overlay could restyle them with no hook in
     * SystemUI at all. Which layout a frame uses depends on the mode: work mode, the tablet's
     * desktop mode, or Android's own header elsewhere.
     */
    private static String windowFrames(Context ctx) throws Exception {
        StringBuilder sb = new StringBuilder("\nwindow frames as this firmware styles them"
                + " (#10, #16)\n");
        String pkg = "com.android.systemui";
        android.content.res.Resources ui = ctx.getPackageManager()
                .getResourcesForApplication(pkg);
        for (String name : DECOR_DIMENS) {
            int id = ui.getIdentifier(name, "dimen", pkg);
            sb.append("  ").append(name).append(": ")
                    .append(id == 0 ? "absent" : ui.getDimensionPixelSize(id) + " px").append('\n');
        }
        android.content.res.Resources system = android.content.res.Resources.getSystem();
        for (String name : DECOR_SYSTEM_DIMENS) {
            int id = system.getIdentifier(name, "dimen", "android");
            sb.append("  android:").append(name).append(": ")
                    .append(id == 0 ? "absent" : system.getDimensionPixelSize(id) + " px")
                    .append('\n');
        }
        for (String name : DECOR_COLORS) {
            int id = ui.getIdentifier(name, "color", pkg);
            sb.append("  ").append(name).append(": ").append(id == 0 ? "absent"
                    : String.format(Locale.ROOT, "#%08X", ui.getColor(id, null))).append('\n');
        }
        sb.append("  layouts:");
        for (String name : DECOR_LAYOUTS) {
            sb.append(' ').append(name).append(ui.getIdentifier(name, "layout", pkg) == 0
                    ? " (absent)" : "");
        }
        return sb.append('\n').toString();
    }

    /**
     * Each taskbar window: where and how big it is, what it tells the system to keep clear, what
     * it is painted with, its navigation keys and their pictures, its fonts, and what in its code
     * speaks of position - what a bar on another edge (#14), colours of its own (#15) and the
     * Retro theme's pixel keys and font (#16) have to work with.
     */
    private static String taskbars() {
        StringBuilder sb = new StringBuilder(
                "\ntaskbars (#14 position, #15 colours, #16 Retro theme)\n");
        for (View root : Windows.roots()) {
            ViewGroup layer = TaskbarTray.dragLayerOf(root);
            if (layer == null || !layer.getContext().getClass().getName().contains("Taskbar")) {
                continue;
            }
            sb.append("  --- ").append(TaskbarScope.label(layer)).append('\n');
            if (root.getLayoutParams() instanceof WindowManager.LayoutParams) {
                WindowManager.LayoutParams lp = (WindowManager.LayoutParams) root.getLayoutParams();
                sb.append("  window: type ").append(lp.type)
                        .append(", gravity 0x").append(Integer.toHexString(lp.gravity))
                        .append(", ").append(lp.width).append('x').append(lp.height)
                        .append(" at ").append(lp.x).append(',').append(lp.y)
                        .append(", flags 0x").append(Integer.toHexString(lp.flags)).append('\n');
                Object insets = Reflect.field(lp, "providedInsets");
                if (insets instanceof Object[]) {
                    for (Object provider : (Object[]) insets) {
                        sb.append("    keeps clear: ").append(provider).append('\n');
                    }
                }
            }
            sb.append("  painted with: window ").append(paint(root.getBackground()))
                    .append(", bar ").append(paint(layer.getBackground())).append('\n');
            for (View keys : Reflect.findByIdNames(layer, "end_nav_buttons",
                    "start_contextual_buttons", "end_contextual_buttons", "navbuttons_view")) {
                sb.append("  keys #").append(Reflect.idName(keys)).append(":\n");
                if (keys instanceof ViewGroup) {
                    ViewGroup group = (ViewGroup) keys;
                    for (int i = 0; i < group.getChildCount(); i++) {
                        View key = group.getChildAt(i);
                        sb.append("    ").append(key.getClass().getSimpleName())
                                .append(" #").append(Reflect.idName(key))
                                .append(key instanceof ImageView
                                        ? " drawn by " + paint(((ImageView) key).getDrawable())
                                        : "")
                                .append(' ').append(key.getWidth()).append('x')
                                .append(key.getHeight()).append('\n');
                    }
                }
            }
            fonts(sb, layer);
            positionNames(sb, layer.getContext());
        }
        return sb.toString();
    }

    private static String paint(Drawable d) {
        if (d == null) {
            return "nothing";
        }
        return d instanceof ColorDrawable
                ? String.format(Locale.ROOT, "colour #%08X", ((ColorDrawable) d).getColor())
                : d.getClass().getName();
    }

    /** The text on the bar and the faces it is set in. */
    private static void fonts(StringBuilder sb, ViewGroup layer) {
        List<TextView> texts = new java.util.ArrayList<>();
        collectTexts(layer, texts);
        for (TextView tv : texts) {
            Typeface face = tv.getTypeface();
            String family = null;
            if (face != null && Build.VERSION.SDK_INT >= 34) {
                family = face.getSystemFontFamilyName();
            }
            sb.append("  text #").append(Reflect.idName(tv)).append(": ")
                    .append(family != null ? family : face == null ? "default face"
                            : face.isBold() ? "bold face" : "regular face")
                    .append(String.format(Locale.ROOT, ", %.0f px", tv.getTextSize()))
                    .append('\n');
        }
    }

    private static void collectTexts(View v, List<TextView> out) {
        if (out.size() >= MAX_TEXTS) {
            return;
        }
        if (v instanceof TextView) {
            out.add((TextView) v);
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collectTexts(g.getChildAt(i), out);
            }
        }
    }

    /**
     * Method and field names about position in the bar's context and its device profile, where
     * ZUI's code keeps them readable - a bar on another edge may already be half there.
     */
    private static void positionNames(StringBuilder sb, Context barContext) {
        Set<String> names = new LinkedHashSet<>();
        Object profile = null;
        for (Class<?> c = barContext.getClass(); c != null && c != Object.class;
                c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (POSITION.matcher(m.getName()).matches()) {
                    names.add(c.getSimpleName() + "." + m.getName() + "()");
                }
            }
            for (Field f : c.getDeclaredFields()) {
                if (profile == null && f.getType().getName().endsWith("DeviceProfile")) {
                    try {
                        f.setAccessible(true);
                        profile = f.get(barContext);
                    } catch (Throwable ignored) {
                        // Unreadable: the methods still say something.
                    }
                }
            }
        }
        if (profile != null) {
            for (Field f : profile.getClass().getDeclaredFields()) {
                if (f.getType() == boolean.class && POSITION.matcher(f.getName()).matches()) {
                    try {
                        f.setAccessible(true);
                        names.add("DeviceProfile." + f.getName() + " = " + f.get(profile));
                    } catch (Throwable ignored) {
                        // Next one.
                    }
                }
            }
        }
        sb.append("  about position in its code: ").append(names.isEmpty() ? "nothing readable"
                : "").append('\n');
        int shown = 0;
        for (String name : names) {
            if (shown++ >= MAX_NAMES) {
                break;
            }
            sb.append("    ").append(name).append('\n');
        }
    }
}
