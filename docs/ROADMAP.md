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
  classes, a screen of its own). Every change is checked in all three.
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
| 1.0.134 | Window "…" menu opening on the tablet instead of the monitor (caused by 1.0.133's full screen on the monitor; 1.0.135 keeps full screen off the monitor) |

### Open

| Since | Bug | Next step |
|-------|-----|-----------|
| 2026-10-07 | Maximize on the monitor does not fill the screen like ZUI's own window menu does | The owner maximises one window from ZUI's own window menu on the monitor and sends the log. Its `system trace:` lines show what ZUI does; Maximize then copies that |

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
| 1 | **Seamless unplug and replug** of the external display | In the launcher: taskbar rebuild, the desktop, glass sessions, hooks re-attaching. Start from an LSPosed log of one unplug + replug. | Low-medium | Planned |
| 2 | **Customise our own UI** - taskbar, app drawer, folders, menus, panels: colour, shape, transparency | Settings with live preview: colour, transparency, corner shape, glass strength (`LiquidGlass.Material` numbers), icon and bar size. Shareable presets. | Low | Planned |
| 3 | **Quick panel tile editing**, like Android's own | Edit mode to reorder, add and remove tiles (`hook/panel/QuickTiles`, `hook/panel/QuickPanel`); other apps' quick-settings tiles if the launcher may bind them; tiles per row, shape, labels, pages. | Low-medium | Planned |
| 4 | **Animations for every control** - quick panel toggles, the drawer's power button, and so on | Shared motion: toggle springs with colour flow and icon morphs, press feedback, liquid sliders, power menu blooming out of its button, account bar, taskbar buttons. | Low | Planned |
| 5 | **Notification pop-ups** on the external display, and **live notifications** like Android's | Glass overlay fed by `notify/NotifyService` (every notification already arrives): pop-ups with actions and inline reply; ongoing progress (deliveries, timers, media, Android 16 Live Updates) as a pill on the taskbar. | Low-medium | Planned |
| 6 | **Customise the notification pop-ups** | Position, size, duration, style, per-app on/off, do-not-disturb. | Low | Planned |
| 7 | **Full-screen / maximise button** | First in the taskbar and window menus: change the task's windowing mode from the launcher, or root if ZUI blocks it. On the title bar with #10. | Medium | Planned |
| 8 | **Desktop Mode+ start animation** - logo with iOS-like motion when the display connects | Ours entirely; also covers the rebuild after a replug (#1). | Low | Planned |
| 9 | **Phone boot animation** | A small Magisk module replacing `bootanimation.zip` systemlessly; the motion rendered into frames. | Low | Planned |
| 10 | **Window looks** - colour, corner shape, title bars, full-screen button on the title bar | Drawn by SystemUI (maybe system_server), not the launcher: second LSPosed scope, a probe there first, a safe off switch. | High | Planned |
| 11 | **Window animations, Linux style, and a window manager editor** | Easy set: scale, fade, slide, zoom from the icon, springs (the window's layer). Hard set: wobbly windows, magic lamp - bend a picture of the window over a mesh while the real one is hidden. Linux code cannot be ported as-is; effects are rebuilt one by one. The editor picks effect, curve and speed per event. | High | Planned |
| 13 | **Adapts to ZUI updates on its own** | ZUI renames its internals with each update. Find every hook by what it is - its signature, its fields, the resources and strings it uses - not by its scrambled name. Each feature checks its hooks at start and, if one is not found, turns itself off and says so. A health screen in settings lists every feature: working, not found on this firmware, or off. | Medium | Planned (after stabilising) |
| 14 | **Taskbar position on the monitor** - bottom (default), top, left, right | The monitor's bar is ZUI's window; ours lay out along it. Top/side means rotating our row, tray and start button, and the bar reporting its new edge to the system so maximised windows stop short of it. Monitor's desktop only. | Medium | Planned |
| 15 | **Taskbar colours of the user's choosing** | Part of #2: colour picker for the bar, drawer and menus, with transparency; presets. Monitor's desktop only to begin with. | Low | Planned |
| 16 | **Retro theme - the battery saver that is also beautiful** (monitor's desktop only; named "Retro theme" in the app) | A classic 90s desktop look: flat grey bevels, pixel-crisp edges, the classic palette, colours customisable. **Pixel-style throughout**: a pixel font (an openly licensed one, bundled) and pixel-drawn navigation keys. **Every monitor feature takes the theme**, nothing left in glass: taskbar, tray and clock, start menu (drawer) and its account and power bars, folders, every menu, the quick panel and its tiles, notification pop-ups, the monitor's recents, the desktop and its widgets' frames, and window frames last. The start button keeps the Android robot and its animations, redrawn pixel-style, in a raised box that says "Start". Saves battery for real by turning off what costs it: live glass captures, blur, refraction and spring physics give way to flat paint and short steps. Built on #2's theming, so each part asks one place how to paint itself. Window frames are drawn by the system, not the launcher (#10), so they come last and carry #10's risk. | Low (launcher parts) / High (window frames) | Planned |
| 12 | **Apps on the external display are never closed until the user closes them** - frozen or suspended is fine | The low-memory killer and ZUI's own background killer close cached apps. Probe ZUI's killer; first step root + battery-optimisation exemption + lock in recents; proper fix a system_server scope keeping desktop tasks "perceptible" so they are frozen, not killed. Under real memory pressure something must go - never a desktop app first. | High | Built, testing: root exemptions always; memory protection with System Framework ticked in LSPosed |
| 17 | **Less CPU and GPU where it is still spent** (2026-10-08 audit; the owner sees the GPU at 103 °C with the Chill live wallpaper on both homes, sometimes only 60 °C) | **Measure first**: the probe from the taskbar menu and the desktop's export now carry a power section - each display's refresh rate, the rates our windows ask for, the wallpaper, each glass pane's captures in the last 10 s, temperatures, CPU per launcher thread over 2 s, and through root the busiest processes and the GPU's load. Prime suspect: the always-on bars' live glass capturing ~30 times a second behind an animated wallpaper. The owner keeps the glass fully live, so a fix makes each capture cheaper (no probe when every probe sees a change, no bitmap copies, a smaller capture) rather than fewer. Also still to look at: the system keep-alive's 3 s task read, and the clock laying out the bar again every second. | Low (glass) / High (system process) | Measuring |
| 18 | **The tablet's own heat management** (owner's request, 2026-10-09; the tablet ran hot before the module existed) | Evidence first: the probe's root part reads the policy the device runs - each CPU cluster's governor and limits, the GPU's, what thermal limits hold back right now, the vendor's thermal and perf configs and properties, Android's thermal service - and whether the kernel offers any voltage control at all. Then ZUI's decompiled power/game app. Then, owner-controlled and reversible: a Magisk module with tuned thermal and perf settings applied at boot. Undervolting only if the kernel offers control, and never by trial and error on the device; otherwise small clock caps, which lower the voltage with them. Never a custom kernel. | High | Measuring |
