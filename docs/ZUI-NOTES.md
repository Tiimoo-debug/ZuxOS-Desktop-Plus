# ZUI notes

What ZUI's own apps do, read from the owner's firmware (decompiled 2026-10-09). Read this before
hooking anything of ZUI's. It records facts - class roles, settings keys, behaviour - never ZUI's
code; the decompiled sources stay outside the repo.

## The apps

| APK | Package | Version | What it is |
|-----|---------|---------|------------|
| ZUX Home | `com.zui.launcher` | 18.2.0.0375 | `NormalLauncher`, `DrawerLauncher` (tablet), `CustomModeLauncher` (tablet desktop mode), `SecondaryDisplayLauncher` + the `...Dp` classes (monitor), `tianjiao.LearningLauncher`, quickstep recents. Every probe so far was taken here. |
| Work Launcher | `com.zui.desktoplauncher` | 18.0.0.0178 | A second, complete Launcher3 fork in `/system/priv-app/ZuiLauncherPC`: `workmode.WorkLauncher`, its own `DrawerLauncher` / `NormalLauncher` / `SecondaryDisplayLauncher`, `workmode.pcallapps.PcAllAppsActivity`, `WorkSearchService`, its own quickstep (`com.zui.quickstep.*`). Always running. **The module does not load into it.** |
| ZUX Performance Service | `com.zui.pp` | 3.1.4.4 | Runs as the system uid. ZUI's heat, refresh-rate and app-killing policy (below). |
| Game Assistant | `com.zui.game.service` | 2.3.0.4834 | Game profiles. Not read yet. |
| SystemUI | `com.android.systemui` | 16 | Window decorations and menus, the WM shell, and work mode's own bar (below). Strings read, code not yet. |
| HomeSettings | `com.zui.homesettings` | 18.1.0.0054 | Fonts and app badges only - nothing about the desktop. |
| "Android System" | `android` (framework-res) | 16 | Resources only, no code. The system's code is in `services.jar` and `framework.jar` - **still to get**. |

## Which mode is on: three settings, exact

All three are `Settings.System` integers, 1 for on. ZUI reads them itself; they are the exact
answer to "which mode is this", where the module has been inferring it from views.

| Key | On means | Read by |
|-----|----------|---------|
| `zui_ov_desktop_mode` | The tablet's desktop mode: ZUX Home's `CustomModeLauncher`, the bar with the search box | `com.zui.launcher.utils.ZuiDesktopModeManager` (observes it), `utils.TaskbarUtilities` |
| `zui_dp_display_pc_mode` | The desktop on the external display ("DP mode"): ZUX Home's `SecondaryDisplayLauncher` and `...Dp` classes | `com.zui.launcher.dpmode.DpModeManager` (`ZUI_DP_MODE_NAME`), `Utilities.isInDpMode` |
| `zui_pc_mode` | **Work mode** (ZUI calls it PC mode): Work Launcher's `com.zui.desktoplauncher/.workmode.WorkLauncher` becomes the home | Work Launcher's `Utilities`, `PcAllAppsManager`; ZUX Home's `Utilities.SYSTEM_WORK_MODE` and `OverviewComponentObserver` |

- ZUX Home's `OverviewComponentObserver` knows Work Launcher's `WorkLauncher` as a possible
  default home and stops treating itself as the recents target when it is.
- Work Launcher returns to its normal home when both `zui_pc_mode` and `zui_dp_display_pc_mode`
  are 0.
- Also seen in the code: `force_desktop_mode_on_external_displays`,
  `freeform_active_in_desktop_mode`, `desktop_mode_visible_tasks`, `close_button_from_pcmode`.

**For the module:** `TaskbarScope` tells the tablet's desktop mode from its bar by the search box;
`zui_ov_desktop_mode` says it exactly. Work mode is a fourth mode the module does not cover.

## Work mode is ZUI's PC mode

Read from the strings in SystemUI's code and in framework-res; SystemUI's code itself is not read
yet.

- **What it is:** a full-screen mode on the tablet's own screen. framework-res names its home
  (`config_pcmode_launcher_package` / `config_pcmode_launcher_class`): Work Launcher's
  `WorkLauncher`. SystemUI replaces the bar with its own (`pcmode.nav.WorkNavLayout`,
  `NavigationBarViewPcMode`): pinned apps plus Wi-Fi, volume, notification and search icons.
  Window decorations are SystemUI's own too (`OvPcMode`, the `decor_*_pcmode` buttons). None of
  it is ZUX Home, so none of the module's hooks apply.
- **Ways in:** a PC-mode quick-settings tile (`qPCModeTileProvider`), a Settings page
  (`com.zui.settings.PC_MODE_SETTINGS`), attaching a keyboard (`enter_work_mode_from_keyboard`,
  `startPcModeByInputKeyguard`), and the broadcast `com.zui.intent.action.SWITCH_PC_MODE`.
- **What turns it off:** `zui_pcmode_not_support`, `persist.sys.csdk.disallowSetPcMode`, and
  ZUI's "OV" desktop features all disabled ("pcMode can not use"). Other keys:
  `zui_pc_mode_switch`, `zui_pc_mode_trigger`, `zui_pcmode_cannot_switch`, `zui_test_pcmode`,
  `zux_pcmode_ui_style`, `current_pc_mode_state`, `persist.sys.zui.pcmode`.
- **Old or current:** "PC mode" is ZUI's name from before ZUX, but Work Launcher is version 18.0
  next to ZUX Home's 18.2, so it is maintained. Whether this tablet offers it is open: the probe
  reads the keys above.
- **Not PC mode:** SystemUI's `WorkModeTile` is AOSP's work-profile tile.

## Heat and app killing: ZUX Performance Service (`com.zui.pp`)

- **Overheat cleaning** (`com.zui.power.overheat.OverHeatCleanService`): when the temperature
  passes the config's start temperature, apps on its clean list are **force-stopped**
  (`ActivityManager.forceStopPackage`), some at once and some after a notification. Spared: the
  one "top" package (`SystemInterface.TOP_PACKAGE`, ZUX Home by default, then the foreground
  app), apps with a foreground service, apps used within the last `bgTime`, games under game mode,
  and the overheat whitelist.
- **Stubborn cleaning** (`StubbornCleanService`): the same force-stop for apps it marks as
  stubborn (high background power), with the same exemptions.
- **Config**: `system/etc/zuipp_powercfg.xml` (`SystemInterface.SYS_CONFIG_POWER_CFG`) holds
  `startCleanTemp`, `cancelCleanTemp`, `timeInterval`, `bgTime`, `cycle`, `bigPower` and the
  overheat whitelist. The probe's next version reads it.
- **For #12 (apps on the monitor are never closed):** an app on the monitor is not the "top"
  package, so overheat and stubborn cleaning can force-stop it. The disable-don't-fight route is
  to put the monitor's apps into what these cleaners already spare - decided once the config is
  read.
- **Refresh rate** (`com.zui.performance.refreshratecenter.RefreshRateManager`): rates are
  votes with priorities - settings, app requests, games, game power-save, battery, and a
  full-screen state (`Settings.Global key_top_full_screen_state`) that votes 60 Hz - kept in
  `pp.refreshrate.db`, alongside `peak_refresh_rate`.
- **Thermal** (`com.zui.performance.thermalcenter`): policies as code, through the thermal HAL,
  through perf locks and through the vendor thermal engine.

## The module's hooks against ZUX Home 18.2.0.0375

Every class and method the module hooks by name is present in this version:

- `com.android.launcher3.taskbar.TaskbarView` - `updateItems`, `updateHotseatItems`
- `com.android.launcher3.taskbar.TaskbarModelCallbacks` - `bindRecentUsedApps`
- `com.android.launcher3.taskbar.NavbarButtonsViewController` - `setNavButtonContainerGravity`
- `com.android.launcher3.taskbar.TaskbarBackgroundRenderer` - `draw`
- `com.android.launcher3.views.ScrimView` and the drawable `drag_handle_indicator`
- `com.zui.launcher.uiextend.ZuiHotseat` - `dispatchDraw`, `onLayout`
- `com.zui.launcher.taskbar.ZuiTaskbarSearchContainer`, `com.zui.launcher.taskbar.TaskbarActivityContextDp`
- `com.zui.launcher.dpmode.secondarydisplaydp.RecentsDragLayerDp`
- `com.zui.launcher.secondarydisplay.SecondaryDisplayLauncher`

Class names like these survive ZUI's R8 pass; fields and most private methods are renamed in
every build, which is why the module finds those by type and shape, not by name.

## Still to read

- `services.jar` and `framework.jar` (with their oat/vdex if the jars hold no code): Maximize
  (#7), the system's refresh-rate decisions, how tasks are killed.
- SystemUI's code: window decorations and their menus (#10, #11).
- Game Assistant (#18).
