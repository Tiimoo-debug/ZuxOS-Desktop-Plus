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
| Game Assistant | `com.zui.game.service` | 2.3.0.4834 | Game profiles, its thermal and game-mode switches (below). |
| SystemUI | `com.android.systemui` | 16 | Window decorations and menus, the WM shell, and work mode's own bar (below). Decompiled in full. |
| HomeSettings | `com.zui.homesettings` | 18.1.0.0054 | Fonts and app badges only - nothing about the desktop. |
| "Android System" | `android` (framework-res) | 16 | Resources only, no code. |
| System server | `services.jar` | 16 | The system's own code, with ZUI's additions: app killing, window placement, refresh-rate boosts (below). |
| Framework | `framework.jar` | 16 | The API side: `IActivityManager` with ZUI's whitelist calls, `com.lgsi.config.LgsiFeatures` (ZUI's feature switches), `OvFreeformManager`, `OVDesktopManager`. |

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
  overheat whitelist. The probe reads it.
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

## App killing inside the system (`services.jar`)

Each of these runs only when ZUI's switch for it (`com.lgsi.config.LgsiFeatures`) is on for this
firmware; the probe lists them.

- **Memory cleaner** (`com.android.server.am.ZuiMemoryCleaner`, switch `ZuiMemoryAcceleration`):
  on memory pressure reported by lmkd, it force-stops whole packages or kills processes, lowest
  importance first. A package is skipped if **any** of its processes is more important than the
  config's minimum adj (`/system/etc/ZuiMemCleanerConfig.xml`, picked by RAM size), so holding a
  monitor app's adj low - what the module's system keep-alive does - keeps it out. Also skipped:
  the permanent list (`/data/system/zui/zui_zmc_whitelist` plus the config's), Lenovo's
  performance center list (`content://com.lenovo.performancecenter.provider.querywhitelist/...`),
  and the most-used apps (`ZuiAppPersistenceRanking`, `/system/etc/zui_app_ranking_config.xml`).
  Its kills carry the reason `ZuiMemoryCleaner[...]`.
- **Kill whitelists, ZUI's own API:** `IActivityManager.addZmcLmkWhiteList(packages, type)`,
  `removeZmcLmkWhiteList`, `getZmcLmkWhiteList` - type `"1"` is lmkd's list
  (`/data/system/zui/zui_lmkd_whitelist`, switch `ZuiLmkWhiteList`), `"2"` the memory cleaner's
  (switch `ZuiMemoryAcceleration`). No permission check in the stub or the service. This is the
  disable-don't-fight route for #12: ask ZUI itself to spare the monitor's apps, and take them off
  again when their window closes. Read in full on 2026-10-09:
  - lmkd's add appends to its file and then tells lmkd (`ProcessList.updateLmkWhitelist`, lmkd
    command 101); remove rewrites the file without them and tells lmkd again. Add **deletes from
    the list it is given** the names already on file, so a caller passes a copy. Its get returns
    null, not an empty list, until the file exists.
  - The memory cleaner's add and remove change its permanent list in memory, which also holds the
    config's `PermanentPackageName` entries; its get shows only the file. So a caller must read
    the config too, or it may take one of ZUI's own off.
  - The module uses both from 1.0.165 (`hook/KeepAlive`): it adds only names on neither list,
    records what it added in the launcher's own `zux_keep_alive` preferences, and removes only
    those.
- **Fixed importance per app** (`ZuiAdjCustomize`, `/system/etc/adj_customize_config.xml`): the
  oom adjuster gives listed packages a computed adj.
- `ZuiDesktopKeepLiveUtil` is misnamed: it places new desktop windows so they do not cover each
  other, nothing to do with keeping apps alive.
- SystemUI's `ZuiDesktopModeKeepAlived` decides, on entering ZUI's desktop mode, which windows
  turn into floating ones and which tasks are killed (`ZuiMemoryCleaner_PcMode`).

## Refresh rate inside the system

With `ZuiAutoRefreshRate` on, `DisplayPolicy` asks the power HAL for a boost (ids 106, 107, 201)
on every touch-down and fling, and on touches of `DrawerLauncher` and a few listed apps.
`ZuiAutoRefreshRateForVideo` picks other boosts while video plays. What each boost does is the
vendor power HAL's, not in these jars.

## Maximize: what ZUI's own window button does (SystemUI's WM shell)

- **Tablet desktop mode on** (the system sets it from `zui_ov_desktop_mode`, also
  `persist.sys.zui.ovdesktop`; SystemUI holds it in one flag for every display): the button turns
  a floating window into a **full-screen task** (`DesktopTasksController.moveToFullscreen`), or
  shows a toast when the app does not allow it; on a full-screen task it goes back to floating.
  The module's Maximize on the tablet does the same.
- **On the monitor, as the device does it** (1.0.166's system trace, 2026-10-10; the monitor's
  desktop on, `zui_dp_display_pc_mode=1`, tablet desktop mode off): the button also makes the
  window **full screen**. SystemUI starts a transition of type **1106** (Android's "leave desktop
  mode by the window's button") that sets windowing mode full screen, empty bounds, not always on
  top, and moves it in front; the window then covers the whole display (0,0-2560,1440). Restore
  starts type **1101** ("enter desktop mode by the window's button"): windowing mode undefined,
  which a desktop display makes floating again, at the bounds it had, in front.
- **What the code also has, but the device did not run:** a toggle that keeps the window
  floating at the display's **stable bounds** (`DesktopModeUtils.calculateMaximizeBounds`), with
  the old bounds kept for restore. Stable bounds are the display minus the cutout, minus the
  status bar at the top, and minus max(navigation-bar inset, a fixed bar height) at the bottom.
  An app that cannot resize keeps its aspect ratio, as large as fits, centred, its title bar not
  counted in the ratio (`maximizeSizeGivenAspectRatio`; jadx's plain output of it is wrong, the
  raw instructions match Android's source). A window counts as maximised when its bounds are the
  stable bounds, or for an app that cannot resize, when it has their full width or height
  (`isTaskMaximized`); restore puts back the size it remembered, or, for a window it never saw
  before, 3/4 of the display each way, centred (`calculateDefaultDesktopTaskBounds`). 1.0.165
  shipped this (`logic/WindowMath`); from 1.0.167 it is only the fallback when the system refuses
  the transition.
- The launcher's desktop interface (`IDesktopMode`) has no maximize or full-screen call (it has
  `moveTaskToDesktop` and launch and show calls). So from 1.0.167 the module's Maximize on the
  monitor starts the same two transitions itself (`WindowOrganizer.startNewTransition`); its
  menus call the way back "Floating" (1.0.169). SystemUI
  follows them like its own: its request handler (`DesktopTasksController.handleRequest`, read
  from the raw instructions) only steps in for "open" and "to front" transitions and lets others
  pass as they are. 1.0.133 instead applied the change directly (`applyTransaction`), which
  SystemUI did not follow, and its window menu then opened on the tablet.
- **The window menu ("…")** is `OvHandleMenu`. It opens on the display of the window
  decoration's own copy of the task (`mTaskInfo.getDisplayId()`), not where the task is now. A
  menu on the wrong screen means SystemUI's copy had the wrong display.
- SystemUI logs each decoration relayout with that display - tag `OVC`, "WindowDecoration
  pre_relayout taskId:… displayId:…" (`android.util.OvcLog.i`) - **only while its debug switch
  is on**: `OvcLog.isDebuggable()`, which is `Log.isLoggable("OVC", DEBUG)`, so the property
  `log.tag.OVC`. It is off on this device, which is why the probe's section came back empty on
  1.0.166. `setprop log.tag.OVC D` (root, until reboot) turns it on.

## The module's hooks against ZUX Home 18.2.0.0375

Every class and method the module hooks by name is present in this version:

- `com.android.launcher3.taskbar.TaskbarView` - `updateItems`, `updateHotseatItems`
- `com.android.launcher3.taskbar.TaskbarModelCallbacks` - `bindRecentUsedApps`
- `com.android.launcher3.taskbar.NavbarButtonsViewController` - `setNavButtonContainerGravity`
- `com.android.launcher3.taskbar.TaskbarBackgroundRenderer` - `draw`, `getBackgroundHeight`
- `com.android.launcher3.taskbar.TaskbarActivityContext` - `createDefaultWindowLayoutParams`
  (the monitor's bar is the window titled `Taskbar_dp`); `TaskbarActivityContextDp` -
  `getDefaultTaskbarWindowSize`; `TaskbarInsetsController` - its two `Insets` answers for a side,
  found by shape (`e(int, int)`, `f(int, int, int)` here). Only with the bar set to the top.
- `com.android.launcher3.views.ScrimView` and the drawable `drag_handle_indicator`
- `com.zui.launcher.uiextend.ZuiHotseat` - `dispatchDraw`, `onLayout`
- `com.zui.launcher.taskbar.ZuiTaskbarSearchContainer`, `com.zui.launcher.taskbar.TaskbarActivityContextDp`
- `com.zui.launcher.dpmode.secondarydisplaydp.RecentsDragLayerDp`
- `com.zui.launcher.secondarydisplay.SecondaryDisplayLauncher`

Class names like these survive ZUI's R8 pass; fields and most private methods are renamed in
every build, which is why the module finds those by type and shape, not by name.

Checked again on 2026-10-09, against every class and method name in the module's launcher-side
hooks:
- All found, except the alternative names some lookups try as fallbacks. Examples are the
  stock-unlock list's `com.zui.launcher.Launcher` and `com.zui.launcher.Workspace`, and Launcher3's
  `taskbar.customization.TaskbarBackgroundRenderer`. Those lookups move on to the next name.
- `IActivityTaskManager.setTaskWindowingMode`, which recents used to make a windowed recents full
  screen, is not in this firmware. The owner's logs said "no setTaskWindowingMode on this build".
  That fallback is gone; the launch options keep recents full screen.

## The module's hooks inside the system, against `services.jar` 16

Checked 2026-10-09. Every hook below matches by name and parameters, unless marked missing:

| Feature | Hook | In this firmware |
|---------|------|------------------|
| Keep-alive | `ProcessStateRecord.setCurAdj(int)` | found |
| Keep-alive | `ProcessList.setOomAdj(int, int, int)` (static) | found |
| Keep-alive | `ProcessRecord.killLocked(String, ...)` | 5 overloads, all found. The owner's log says "kills x5" |
| Full screen | `LaunchParamsController.calculate(...)`, fields `LaunchParams.mWindowingMode`, `mBounds`, `ActivityRecord.intent` | found. ZUI calls it with phase 10 in its desktop mode, 3 otherwise |
| Full screen | `setWindowingMode(int)` on `Task` and on `ConfigurationContainer`; field `Task.intent` | found |
| New window | `ActivityStarter.setInitialState(...)`, fields `mIntent`, `mLaunchMode`, `mLaunchFlags` | found |
| New window | `ActivityStarter.getReusableTask` | **missing**: it is `resolveReusableTask(boolean)` here. The owner's log says "task reuse x0". The hook is gone; `setInitialState` alone does the job, confirmed on 1.0.91 |
| Drag | `DragState.isValidDropTarget(WindowState, boolean, boolean)`, fields `mFlags`, `mUid`; `WindowState.getOwningUid()` | found |
| Trace | `WindowOrganizerController.applyTransaction`, `applySyncTransaction`, `startTransition` | found. `startLegacyTransition` is not, which only drops one traced name. From 1.0.165 the overloads taking an `ActionChain` are left out: they are the system's own step inside a call, under its own uid. The shell's `startNewTransition` goes through `startTransition(int, IBinder, …)`, so its type is in the line |

## The roadmap rows against ZUI's code

Read 2026-10-09 from the decompiled apps and jars. These are facts for planning, not tests on the
device.

- **#1 Unplug and replug.** ZUX Home learns that the monitor's desktop is on from
  `Settings.System zui_dp_display_pc_mode` (`DpModeManager`, through `SettingsCache`). On 0, or
  when that display is removed, it drops its monitor context. The system writes 0 itself
  (`OVDesktopController`). The module can follow the same switch with an observer instead of
  inferring from windows. The display timeline now records it, together with
  `zui_ov_desktop_mode` and `zui_pc_mode`.
- **#3 Other apps' quick-settings tiles.** Binding a tile needs `BIND_QUICK_SETTINGS_TILE`
  (signature|recents). ZUX Home holds the recents role (`config_recentsComponentName` is its
  `RecentsActivity`), but its manifest does not ask for that permission, so it cannot bind them.
  Possible only from SystemUI, which hosts the tiles, or by a system-side grant. The module's own
  tiles are unaffected.
- **#5, #6 Notifications on the monitor.** SystemUI has a status bar on the monitor in DP mode
  (`dpmode/ExtendStatusBarController`, its own notification icons). Whether heads-up pop-ups appear
  there is not clear from the code. The probe now lists SystemUI's windows per display. Android 16
  Live Updates arrive through the module's listener like any notification.
- **#20 The monitor's status bar** (read 2026-10-10). Built by SystemUI's
  `dpmode/ZuiDpModeManager`, in one place: its `createStatusBar(Display)` (compiled as the static
  `-$$Nest$mcreateStatusBar`), called when `zui_dp_display_pc_mode` turns on and when the external
  screen is added in that mode. It is a `DpStatusBarWindowControllerImpl` window, type 2000,
  titled `StatusBar<display id>`, keeping `statusBars` and `tappableElement` clear at the top. No
  setting of ZUI's turns it off. Everything else in the manager allows for it not being there
  (`removeStatusBar`, the colour updates in `onSystemBarAttributesChanged`); the screen width it
  stores on the way (`XSystemUtil.mDpModeScreenWidth`) is read only by the status bar's own view.
- **#9 Boot animation.** Chosen by the native boot animation, not in these jars. The probe lists
  the files present.
- **#10, #16 Window frames.** SystemUI draws them from resources:
  - **Work mode:** `pcmode_window_decor`.
  - **Tablet desktop mode:** `zui_desktop_window_decor` and `..._freeform`.
  - **Elsewhere:** Android's `desktop_mode_app_header`.
  - **Styling:** button drawables `zui_desktopmode_*`, corner radius
    `ovc_wd_freeform_task_corner_radius_pad` and the system's `ov_*_corner_radius`, caption height
    `freeform_decor_caption_height`, and title colours.
  - **The disable-don't-fight route:** restyle frames with a resource overlay (fabricated overlays
    for sizes and colours, an overlay package for layouts and pictures), with no hook in SystemUI.
    The probe reads the current values and overlays.
- **#11 Window animations.** Fixed in SystemUI's code, with no resource or setting besides
  Android's global animation scales:
  - floating window open and minimise: 400 ms (`FreeformTaskTransitionHandler`);
  - close: 300 ms, standard-accelerate (`CloseDesktopTaskTransitionHandler`);
  - maximise and restore: 300 ms (`ToggleResizeDesktopTaskTransitionHandler`).

  Custom animations mean hooking those handlers in SystemUI: high risk, as planned.
- **#13 Adapting to updates.** The two tables above are the check; the probe's health section
  shows the same on the device.
- **#14 Taskbar on another edge.** Read in full for the top (2026-10-10, 1.0.170):
  - The window's parameters come from `createDefaultWindowLayoutParams(type, title)`, gravity
    BOTTOM, for the bar and each of its four rotations (`o0()` here), titled `Taskbar_dp` on the
    monitor only. The same method makes "Taskbar Nav Buttons" and a voice-interaction window, so
    the title is what tells the bar apart.
  - Its height is `getDefaultTaskbarWindowSize()`: on the monitor the bar (`taskbarHeight`, 80 px)
    plus a corner radius and tooltip room above it - the 162 px the probe shows.
    `setTaskbarWindowFullscreen` stretches it to the screen's height during drags, folders and
    menus.
  - The space kept clear for apps: `TaskbarInsetsController`'s two `Insets` answers handle a
    gravity of bottom, start and end; any other lands on the right.
  - The background renderer paints at the canvas's foot (`b(Canvas)` translates by the canvas's
    height less the bar's). The rows in `taskbar.xml` (`taskbar_view`, `navbuttons_view`) are
    `layout_gravity="bottom"`, and no code sets their gravity again (only `end_nav_buttons`'
    horizontal gravity).
  - The renderer's fields are renamed in this build (`context` is `a`, the heights `f` and `n`),
    so its bar is found by type.

  Earlier note: Launcher3's bar already builds window parameters per rotation,
  with a side gravity and a width for 90° and 270° (`TaskbarActivityContext`, by shape: a void
  method taking a rotation and the window's layout params). No setting picks the edge. Moving the
  monitor's bar means changing those parameters in `TaskbarActivityContextDp` only, plus the space
  it reserves (its provided insets). Launcher side, medium risk, as planned.
- **#17, #18 Game Assistant.**
  - **Thermal policy:** it switches the device's thermal policy through Lenovo's security centre
    (`content://com.lenovo.safecenter.power.utils`, value `userThermal`: 103 for its high-performance
    mode, 0 for normal).
  - **Game mode:** it sets ZUX Performance Service's game mode (`Settings.Global
    game_helper_game_mode` on Android 16), which `com.zui.pp` treats as a game.
  - **For #18:** a cooler profile, if the security centre has one, is a switch ZUI already offers.
    That app (`com.lenovo.safecenter`) is not read yet.
- **#2, #4, #8, #15** are drawn entirely by the module: nothing of ZUI's to check.

## Still to read

- SystemUI's window menus (#10).
- Lenovo's security centre (`com.lenovo.safecenter`): its thermal profiles (#18).
