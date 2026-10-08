# Roadmap

Where ZuxOS Desktop Plus is going, as agreed with the owner. Read this before starting new work
in a fresh session: it is the project's memory.

**Goal:** the best desktop mode on Android, for the Lenovo Legion Tab Gen 5 on ZuxOS 2.x.

## Rules

- **Polish and stability before new features.** A desktop people never have to think about beats
  one with more features.
- **Every feature has its own off switch**, and falls back to stock behaviour when a hook cannot
  find what it expects - logging why, once.
- **One motion language**: iOS-quality springs from `core/Motion`, used everywhere.
- **Evidence first**: fixes come from LSPosed logs, probes, screenshots and recordings. Where
  they cannot show something (what ZUI itself does), trace it first - never guess.
- **Disable, don't fight ZUI**: switch ZUI's own behaviour off where it starts (a setting, a
  decision, a resource), never undo it afterwards or redo it every frame.
- **Three modes, all run by ZUX Home**: the tablet (`DrawerLauncher`), the tablet's desktop mode
  (`CustomModeLauncher`, the bar with ZUI's search box) and the monitor's desktop (`...Dp`
  classes, a screen of its own). Every change is checked in all three.
- **The system process must never be able to crash**: nothing there at load time but hooks,
  every hook in try/catch, no thread that can throw.
- **Device test list after every build**: app drawer open/close, folders (drawer, taskbar,
  desktop), every menu (taskbar, drawer app hold, desktop, power), quick panel, notifications,
  screenshot button, rotation, unplug and replug the display.

## Done

| Since | What |
|-------|------|
| 2026-10 | Live liquid glass (blur + real refraction) on everything translucent, CPU-tuned, there from the first frame |
| 2026-10 | iOS folder morph: folders grow out of their icon and back, icon hidden while open, one material closed and open |

## In progress

| Since | What | Status |
|-------|------|--------|
| 2026-10-05 | **Navigation keys that never drop a press and never freeze the launcher.** The 2026-10-05 log proved: presses lost to our one-at-a-time root (`BUSY`); back sent to the desktop with no focused window → 5 s ANR → launcher killed; recents key to the monitor does nothing. Now: one persistent key shell, back/home handled in-process when the desktop is in front, ZUI's own recents button traced and backed up. | Built, testing |
| 2026-10-05 | **Launcher crash on replug** - a recents drag layer (ZUI's `RecentsDragLayerDp`, quickstep's `fallback.RecentsDragLayer`) losing a child during teardown. Caught by `hook/DetachGuard`, launcher survives. | Guarded |
| 2026-10-05 | **The monitor's own recents** (`hook/TaskOverview`): ZUI's monitor recents never became visible and quickstep's fallback is laid out for the tablet, so the monitor gets a recents drawn by the module - that screen's apps, newest first, snapshots, tap/X/swipe/clear all. Tablet recents kept off the monitor's home. | Built, testing |
| 2026-10-05 | **Recents opens on the screen whose button was pressed, full screen, in front of every app, on the first press.** The tablet's home is Lawnchair, so recents is quickstep's fallback `RecentsActivity`; `hook/RecentsRoute` steers its launch options, corrects it when it appears wrong, and clears stale copies. | Built, testing |

## Planned, in order

Risk is about where the code has to run: our launcher views are low risk; the system interface
(SystemUI) and the system server can take the whole UI down with a bug.

| # | Idea (added 2026-10-04) | How | Risk | Status |
|---|------|-----|------|--------|
| 1 | **Seamless unplug and replug** of the external display | In the launcher: taskbar rebuild, the desktop, glass sessions, hooks re-attaching. Start from an LSPosed log of one unplug + replug. | Low-medium | Planned |
| 2 | **Customise our own UI** - taskbar, app drawer, folders, menus, panels: colour, shape, transparency | Settings with live preview: colour, transparency, corner shape, glass strength (`LiquidGlass.Material` numbers), icon and bar size. Shareable presets. | Low | Planned |
| 3 | **Quick panel tile editing**, like Android's own | Edit mode to reorder, add and remove tiles (`hook/QuickTiles`, `hook/QuickPanel`); other apps' quick-settings tiles if the launcher may bind them; tiles per row, shape, labels, pages. | Low-medium | Planned |
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
