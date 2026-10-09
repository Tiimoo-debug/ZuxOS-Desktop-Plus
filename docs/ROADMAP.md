# Roadmap

Where ZuxOS Desktop Plus is going, as agreed with the owner. Read this before starting new work
in a fresh session: it is the project's memory.

**Goal:** the best desktop mode on Android, for the Lenovo Legion Tab Gen 5 on ZuxOS 2.x.

## Rules

- **Polish and stability before new features.** A desktop people never have to think about beats
  one with more features.
- **Every feature has its own off switch**, and falls back to stock behaviour when a hook cannot
  find what it expects - logging why, once.
- **One motion language**: iOS-quality springs from `core/motion/Motion`, used everywhere.
- **Evidence first**: fixes come from LSPosed logs, probes, screenshots and recordings. Where
  they cannot show something (what ZUI itself does), trace it first - never guess.
- **Disable, don't fight ZUI**: switch ZUI's own behaviour off where it starts (a setting, a
  decision, a resource), never undo it afterwards or redo it every frame.
- **Three modes, all run by ZUX Home**: the tablet (`DrawerLauncher`), the tablet's desktop mode
  (`CustomModeLauncher`, the bar with ZUI's search box) and the monitor's desktop (`...Dp`
  classes, a screen of its own). Every change is checked in all three. ZUI's settings say which is
  on, and a fourth - work mode, a separate app - exists: `docs/ZUI-NOTES.md`.
- **Read ZUI's code before hooking it**: `docs/ZUI-NOTES.md` holds what the decompiled apps say;
  add to it whenever the code answers a question. ZUI's code itself never goes in the repo.
- **The system process must never be able to crash**: nothing there at load time but hooks,
  every hook in try/catch, no thread that can throw.
- **Least CPU, zero latency**: when two ways are equally smooth and quick, the one that costs
  less CPU - events not polling, nothing ticking while hidden, no work repeated for nothing.
- **Structure, clean code, no dead code**: one package per area, shared helpers in `core/` and
  `hook/`, the old path removed with the feature that replaced it; `tools/deadcode.py` finds the
  rest.
- **Device test list after every build**: app drawer open/close, folders (drawer, taskbar,
  desktop), every menu (taskbar, drawer app hold, desktop, power), quick panel, notifications,
  screenshot button, rotation, unplug and replug the display.

## Done

| Since | What |
|-------|------|
| 2026-10 | Live liquid glass (blur + real refraction) on everything translucent, CPU-tuned, there from the first frame |
| 2026-10 | iOS folder morph: folders grow out of their icon and back, icon hidden while open, one material closed and open |

## Fixed

Every bug fixed since the start, from the owner's own messages. **Confirmed** means the owner said
so on the device. Update this section whenever the owner confirms a fix or reopens a bug. Build is
the build that fixed it; "by" means the fix was in the build the owner confirmed it on. Early
rows give only the date the owner confirmed.

### Confirmed by the owner

| Confirmed | Build | Bug |
|-----------|-------|-----|
| 2026-09-15 | - | Apps, folders and shortcuts could not be put on the monitor's home screen |
| 2026-09-16 | - | Widgets: could not be added, resized (the spinning CD widget left its frame) or removed |
| 2026-09-16 | - | ZUI's own drawer did not show folders |
| 2026-09-16 | - | Folder names missing when closed, or changing to an app's name; no home screen pages |
| 2026-09-16 | - | No way to add several apps to a folder at once, on the home screen or in the drawer |
| 2026-09-17 | - | No App info in the menu of an app inside a folder |
| 2026-09-17 | - | Apps and App info opening behind the drawer, which stayed open |
| 2026-09-17 | - | Taskbar tray text unreadable; CPU reading wrong and not updating |
| 2026-09-18 | - | Home screen and drawer stopped responding to touches (the screen-wide blur) |
| 2026-09-18 | - | Holding the taskbar brought no menu |
| 2026-09-18 | - | Wi-Fi and torch toggles; volume control |
| 2026-09-18 | - | Taskbar menu and quick panel did not close when tapping outside |
| 2026-09-19 | - | Notifications did not open their app |
| 2026-09-19 | - | The screenshot button took the tablet's screen instead of the monitor's |
| 2026-09-19 | - | Media card: one card per playing app; play and pause buttons |
| 2026-10-03 | - | No glass on the drawer |
| 2026-10-03 | - | Could not drag apps from the drawer to the taskbar and home screen, or apps and folders from the home screen to the taskbar |
| 2026-10-03 | - | No menu when holding an open app on the taskbar |
| 2026-10-03 | - | Menus without glass or icons; pins could not be rearranged |
| 2026-10-04 | - | Open apps out of order and running into the taskbar's buttons |
| 2026-10-04 | - | The line under open apps lagging behind the scroll |
| 2026-10-04 | - | Start button: pressing it with the drawer open reopened the drawer instead of closing it |
| 2026-10-04 | - | Glass: blurred background behind the drawer, no real refraction |
| 2026-10-04 | - | Folders opened as a duplicate beside their icon (now they open out of their icon) |
| 2026-10-05 | - | Home and back keys on the monitor pressed but did nothing |
| 2026-10-05 | - | Recents on the monitor: never shown, opened as a small window, or opened behind apps. The monitor now has its own recents with live tiles |
| 2026-10-06 | 1.0.87 | Taskbar preview: X button and icon falling out of place while zooming |
| 2026-10-06 | 1.0.89 | After boot on the monitor: no start button, apps opening on the tablet, recents dead |
| 2026-10-06 | 1.0.94 | Screenshot preview shown on the tablet instead of the screen it was taken on |
| 2026-10-07 | 1.0.91 | New window: refused for some apps, or slow to open |
| 2026-10-07 | by 1.0.107 | Two rows of apps on the tablet's taskbar |
| 2026-10-07 | 1.0.106 | Hover wiggle making icons leave their frame |
| 2026-10-07 | 1.0.97 | Screenshot edit did not open (ZUI's photo editor in split screen) |
| 2026-10-07 | 1.0.108 | Home screen menus going under the taskbar |
| 2026-10-07 | 1.0.114 | **Boot loop**: display service called from the system process at load time |
| 2026-10-07 | 1.0.120 | Our home screen overlay drawn over ZUI's home on the tablet |
| 2026-10-07 | 1.0.121 | Tablet: taskbar disappearing in Recents (Lawnchair as home); nav keys now always at the right |
| 2026-10-07 | 1.0.125 | Bright pill on ZUI's dock, on the home screen and in Recents |
| 2026-10-07 | 1.0.128 | **Boot loop**: settings copy written from the system process |
| 2026-10-07 | 1.0.131 | Swipe-up arrow on ZUI's home. Its image is now never loaded |
| 2026-10-07 | 1.0.133 | Maximize on the tablet, in regular and desktop mode. 1.0.135 keeps this exact path on the tablet only |

| 2026-10-09 | 1.0.143 | Tray CPU work: the clock still ticks and the temperatures still show, now that the clock only ticks while the tray is visible and temperatures are only read while shown |

### Shipped, awaiting the owner's test

| Build | Bug |
|-------|-----|
| 1.0.108-1.0.109 | Two windows of one app: its icon switching between them, and Close closing both or the wrong one |
| 1.0.109 | Drag and drop out of ZUI's drawer (apps, folders, apps inside folders) |
| 1.0.109 | Drawer folder smaller after opening and closing it (came back once) |
| 1.0.117 | Taskbar covering the keyboard's bottom row |
| 1.0.118 | Home and Recents not responding with desktop mode set to "Both" and ZUX Home as the default home |
| 1.0.122 | Monitor taskbar losing its pins when no app is open |
| 1.0.127-1.0.128 | ZUI's recent and suggested apps flashing on the taskbar after boot (came back once) |
| 1.0.134 | Dark pill left on ZUI's home: the dock's background blur switched off |
| 1.0.161 | Probe without its root part, and root switched off in the launcher until it restarted (broken in 1.0.159: a zero byte in the probe's script; root also never answered readers when it was off or busy) |
| 1.0.161 | The monitor's desktop covering ZUX Home's settings when they opened on the monitor ("ZuiLauncherSettings" has "launcher" in its name; ZUX Home's screens now attach only if declared a home) |
| 1.0.165 | Maximize on the monitor stretching apps that keep their shape: now ZUI's own rule, and Restore in the same menus |

### Open

| Since | Bug | Next step |
|-------|-----|-----------|
| 2026-10-10 | Window "…" menu opening on the tablet instead of the monitor - first after 1.0.133's full screen on the monitor (1.0.135 kept full screen off it), reported again 2026-10-10 | The log sent was from a build between 1.0.131 and 1.0.143, not the 1.0.151 installed: the update had not loaded. ZUI's menu opens on the display SystemUI's own copy of the window says (`docs/ZUI-NOTES.md`), so SystemUI had the window on the tablet. From 1.0.157 the probe carries SystemUI's own log of each window's display (`OVC`). 2026-10-10: the owner finds it random and thinks it is ZUI's own. Next: a probe from the monitor's bar right after it happens |
| 2026-10-07 | Maximize on the monitor does not fill the screen like ZUI's own window menu does | 1.0.165 uses ZUI's rule (`docs/ZUI-NOTES.md`) and adds Restore. One thing is still the module's own reading: the stable bounds, taken as the display less the status bar and max(navigation inset, our bar). The system trace now also writes size-only changes. Next: the owner maximises one window with ZUI's own button on the monitor and the same app with ours, and sends the log: ZUI's `system trace: startTransition type … size only` line against ours, `taskbar apps: task … maximised to` |

### Not caused by the module

| Seen | What |
|------|------|
| 2026-10-07 | ZUI's sidebar freezing during drags: ZUI waits on its own AI service |
| 2026-10-07 | LSPosed manager crashing |
| 2026-10-07 | The ZTool module logging every ~7 ms on the launcher's main thread |
| 2026-10-03 | File apps stuck, storage access lost. The owner fixed it on 10-03; it came back on 10-04 and was gone after a reboot and a build. The module was never shown to cause it |

## In progress

| Since | What | Status |
|-------|------|--------|
| 2026-10-05 | **Navigation keys that never drop a press and never freeze the launcher.** The 2026-10-05 log proved: presses lost to our one-at-a-time root (`BUSY`); back sent to the desktop with no focused window → 5 s ANR → launcher killed; recents key to the monitor does nothing. Now: one persistent key shell, back/home handled in-process when the desktop is in front, ZUI's own recents button traced and backed up. | Built, testing |
| 2026-10-05 | **Launcher crash on replug** - a recents drag layer (ZUI's `RecentsDragLayerDp`, quickstep's `fallback.RecentsDragLayer`) losing a child during teardown. Caught by `hook/DetachGuard`, launcher survives. | Guarded |
| 2026-10-05 | **The monitor's own recents** (`hook/recents/TaskOverview`): ZUI's monitor recents never became visible and quickstep's fallback is laid out for the tablet, so the monitor gets a recents drawn by the module - that screen's apps, newest first, snapshots, tap/X/swipe/clear all. Tablet recents kept off the monitor's home. | Built, testing |
| 2026-10-05 | **Recents opens on the screen whose button was pressed, full screen, in front of every app, on the first press.** The tablet's home is Lawnchair, so recents is quickstep's fallback `RecentsActivity`; `hook/recents/RecentsRoute` steers its launch options, corrects it when it appears wrong, and clears stale copies. | Built, testing |

## Planned, in order

Risk is about where the code has to run: our launcher views are low risk; the system interface
(SystemUI) and the system server can take the whole UI down with a bug.

**Every probe already gathers what these rows need** (`hook/RoadmapProbe`, since 1.0.146), each
section named by its row: versions and which hooks found their targets (#13), a timeline of the
screens and our bars (#1, #8), the quick-settings tiles (#3), what kinds of notification are up
(#5, never their text), each taskbar window's place, insets, paint, keys and fonts (#14, #15,
#16), and through root ZUI's packages and their files, the settings and flags about the taskbar,
desktop mode and window animation, the window manager shell's own report (#10, #11), and the
boot animation and root setup (#9). Read the probe before starting a row.

| # | Idea (added 2026-10-04) | How | Risk | Status |
|---|------|-----|------|--------|
| 1 | **Seamless unplug and replug** of the external display | In the launcher: taskbar rebuild, the desktop, glass sessions, hooks re-attaching. Start from an LSPosed log of one unplug + replug. ZUI's own switch is `zui_dp_display_pc_mode` (ZUX Home and the system follow it); the display timeline records it with the displays. | Low-medium | Planned |
| 2 | **Customise our own UI** - taskbar, app drawer, folders, menus, panels: colour, shape, transparency | Settings with live preview: colour, transparency, corner shape, glass strength (`LiquidGlass.Material` numbers), icon and bar size. Shareable presets. | Low | Planned |
| 3 | **Quick panel tile editing**, like Android's own | Edit mode to reorder, add and remove tiles (`hook/panel/QuickTiles`, `hook/panel/QuickPanel`); other apps' quick-settings tiles only from SystemUI or with a system-side grant: ZUX Home cannot bind them (`docs/ZUI-NOTES.md`); tiles per row, shape, labels, pages. | Low-medium | Planned |
| 4 | **Animations for every control** - quick panel toggles, the drawer's power button, and so on | Shared motion: toggle springs with colour flow and icon morphs, press feedback, liquid sliders, power menu blooming out of its button, account bar, taskbar buttons. | Low | Planned |
| 5 | **Notification pop-ups** on the external display, and **live notifications** like Android's | Glass overlay fed by `notify/NotifyService` (every notification already arrives): pop-ups with actions and inline reply; ongoing progress (deliveries, timers, media, Android 16 Live Updates) as a pill on the taskbar. SystemUI already has a status bar on the monitor; the probe shows whether its pop-ups appear there. | Low-medium | Planned |
| 6 | **Customise the notification pop-ups** | Position, size, duration, style, per-app on/off, do-not-disturb. | Low | Planned |
| 7 | **Full-screen / maximise button** | First in the taskbar and window menus: change the task's windowing mode from the launcher, or root if ZUI blocks it. On the title bar with #10. | Medium | Menus built: full screen on the tablet (1.0.133); on the monitor ZUI's own maximise and restore (1.0.165). Title bar with #10 |
| 8 | **Desktop Mode+ start animation** - logo with iOS-like motion when the display connects | Ours entirely; also covers the rebuild after a replug (#1). | Low | Planned |
| 9 | **Phone boot animation** | A small Magisk module replacing `bootanimation.zip` systemlessly; the motion rendered into frames. | Low | Planned |
| 10 | **Window looks** - colour, corner shape, title bars, full-screen button on the title bar | Drawn by SystemUI from its resources (layouts, button pictures, corner radius, caption height, title colours), so first a resource overlay - no hook in SystemUI at all; a second LSPosed scope only for what resources cannot reach. The probe reads the current values (`docs/ZUI-NOTES.md`). | High | Planned |
| 11 | **Window animations, Linux style, and a window manager editor** | Easy set: scale, fade, slide, zoom from the icon, springs (the window's layer). Hard set: wobbly windows, magic lamp - bend a picture of the window over a mesh while the real one is hidden. Linux code cannot be ported as-is; effects are rebuilt one by one. The editor picks effect, curve and speed per event. ZUI's window animations are fixed in SystemUI's code (open 400 ms, close 300 ms, maximise 300 ms), with no setting but Android's global scales: this needs the SystemUI scope. | High | Planned |
| 13 | **Adapts to ZUI updates on its own** | ZUI renames its internals with each update. Find every hook by what it is - its signature, its fields, the resources and strings it uses - not by its scrambled name. Each feature checks its hooks at start and, if one is not found, turns itself off and says so. A health screen in settings lists every feature: working, not found on this firmware, or off. | Medium | Planned (after stabilising) |
| 14 | **Taskbar position on the monitor** - bottom (default), top, left, right | The monitor's bar is ZUI's window; ours lay out along it. Top/side means rotating our row, tray and start button, and the bar reporting its new edge to the system so maximised windows stop short of it. Monitor's desktop only. Launcher3's bar already has per-rotation side placement; no setting picks the edge, so it is the monitor bar's window parameters and reserved space (`docs/ZUI-NOTES.md`). | Medium | Planned |
| 15 | **Taskbar colours of the user's choosing** | Part of #2: colour picker for the bar, drawer and menus, with transparency; presets. Monitor's desktop only to begin with. | Low | Planned |
| 16 | **Retro theme - the battery saver that is also beautiful** (monitor's desktop only; named "Retro theme" in the app) | A classic 90s desktop look: flat grey bevels, pixel-crisp edges, the classic palette, colours customisable. **Pixel-style throughout**: a pixel font (an openly licensed one, bundled) and pixel-drawn navigation keys. **Every monitor feature takes the theme**, nothing left in glass: taskbar, tray and clock, start menu (drawer) and its account and power bars, folders, every menu, the quick panel and its tiles, notification pop-ups, the monitor's recents, the desktop and its widgets' frames, and window frames last. The start button keeps the Android robot and its animations, redrawn pixel-style, in a raised box that says "Start". Saves battery for real by turning off what costs it: live glass captures, blur, refraction and spring physics give way to flat paint and short steps. Built on #2's theming, so each part asks one place how to paint itself. Window frames are drawn by the system, not the launcher (#10), so they come last and carry #10's risk. | Low (launcher parts) / High (window frames) | Planned |
| 12 | **Apps on the external display are never closed until the user closes them** - frozen or suspended is fine | The low-memory killer and ZUI's own background killer close cached apps. Probe ZUI's killer; first step root + battery-optimisation exemption + lock in recents; proper fix a system_server scope keeping desktop tasks "perceptible" so they are frozen, not killed. Under real memory pressure something must go - never a desktop app first. ZUI's own overheat and stubborn-app cleaning (`com.zui.pp`) force-stops apps that are not its single "top" app - apps on the monitor among them; the clean way is to put them into what those cleaners already spare (see `docs/ZUI-NOTES.md`). The system's own memory cleaner spares any package with an important process - what the system keep-alive's held adj already gives - and ZUI has its own kill-whitelist call (`addZmcLmkWhiteList`) that needs no system hook; which to use is decided once the probe shows the cleaner's threshold and which of ZUI's switches are on. Both switches are on (probe, 2026-10-10), so from 1.0.165 the monitor's apps also go on ZUI's two lists, and come off when they leave the monitor or the switch goes off; the probe's keep-alive section shows the lists. | High | Built, testing: root exemptions always; ZUI's own kill lists (1.0.165); memory protection with System Framework ticked in LSPosed |
| 17 | **Less CPU and GPU where it is still spent** (2026-10-08 audit; the owner sees the GPU at 103 °C with the Chill live wallpaper on both homes, sometimes only 60 °C) | **Measure first**: the probe from the taskbar menu and the desktop's export now carry a power section - each display's refresh rate, the rates our windows ask for, the wallpaper, each glass pane's captures in the last 10 s, temperatures, CPU per launcher thread over 2 s, and through root the busiest processes and the GPU's load. Prime suspect: the always-on bars' live glass capturing ~30 times a second behind an animated wallpaper. The owner keeps the glass fully live, so a fix makes each capture cheaper (no probe when every probe sees a change, no bitmap copies, a smaller capture) rather than fewer. Also still to look at: the system keep-alive's 3 s task read, and the clock laying out the bar again every second. **Measured 2026-10-10 (1.0.157, hot):** the launcher used 10% of one core in all, and the bar's glass captured nothing in 10 s on an unchanging screen; the live wallpaper's process 26% CPU and SurfaceFlinger 22%, with the tablet at 165 Hz and the monitor at 144 Hz; GPU 83% busy at about 100 °C. | Low (glass) / High (system process) | Measuring |
| 18 | **The tablet's own heat management** (owner's request, 2026-10-09; the tablet ran hot before the module existed) | Evidence first: the probe's root part reads the policy the device runs - each CPU cluster's governor and limits, the GPU's, what thermal limits hold back right now, the vendor's thermal and perf configs and properties, Android's thermal service - and whether the kernel offers any voltage control at all. Then ZUI's decompiled power app: `com.zui.pp` holds the overheat and stubborn-app cleaning, the refresh-rate votes and the thermal policies, configured by `system/etc/zuipp_powercfg.xml` (`docs/ZUI-NOTES.md`). Game Assistant switches the thermal policy through Lenovo's security centre (`userThermal`: 103 for its high-performance mode, 0 normal); a cooler profile there, if one exists, is ZUI's own switch - that app is still to read. Then, owner-controlled and reversible: a Magisk module with tuned thermal and perf settings applied at boot. Undervolting only if the kernel offers control, and never by trial and error on the device; otherwise small clock caps, which lower the voltage with them. Never a custom kernel. **Measured 2026-10-10 (hot, both homes idle, after the owner had closed every app with a task killer to get a clean state):** every CPU cluster's minimum frequency was held at or near its top (cores 0-5: minimum = maximum = 3.63 GHz; cores 6-7: minimum 4.19 GHz), so the CPU could not slow down while 589% of 800% sat idle; cores at 98-108 °C, several 'severe' in the thermal service. Not ZUI's game mode on its own (`game_helper_game_mode` = 1 counts only while a game runs, in `com.zui.pp`). Scene (`com.omarea.vtools`) has its scheduler running and can set these limits; the next probe reads the clocks twice and lists the apps holding a root shell. No voltage control found: the regulators only report, and debugfs has no voltage entries. **Then, the owner's Scene screenshot (2026-10-10, 01:02):** with Scene's mode changed, Scene shows every cluster locked minimum = maximum again, now at 1996 MHz (cores 0-5) and 2880 MHz (cores 6-7) - Scene sets these limits - and the CPU fell to 54-57 °C. The GPU stayed at about 92 °C, 95% busy at 1050 MHz, with the Chill live wallpaper at 48% CPU and SurfaceFlinger at 23%: the GPU's load is the live wallpaper drawn on both screens at 144 Hz. | High | Measuring |
| 19 | **Work mode** (ZUI's PC mode, `zui_pc_mode`): a separate launcher, Work Launcher (`com.zui.desktoplauncher`), becomes the home | Found 2026-10-09 by decompiling. A full-screen mode on the tablet's own screen, entered from a quick-settings tile, Settings or a keyboard; its bar and window decorations are SystemUI's, not a launcher's. The module does not load into it, so none of its features exist there. Whether to support it - and how far - is the owner's call once the probe shows when the owner's tablet uses it. | Medium | Owner's call |
