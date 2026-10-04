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
- **Evidence first**: fixes come from LSPosed logs, probes, screenshots and recordings.
- **Device test list after every build**: app drawer open/close, folders (drawer, taskbar,
  desktop), every menu (taskbar, drawer app hold, desktop, power), quick panel, notifications,
  screenshot button, rotation, unplug and replug the display.

## Done

| Since | What |
|-------|------|
| 2026-10 | Live liquid glass (blur + real refraction) on everything translucent, CPU-tuned, there from the first frame |
| 2026-10 | iOS folder morph: folders grow out of their icon and back, icon hidden while open, one material closed and open |

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
| 12 | **Apps on the external display are never closed until the user closes them** - frozen or suspended is fine | The low-memory killer and ZUI's own background killer close cached apps. Probe ZUI's killer; first step root + battery-optimisation exemption + lock in recents; proper fix a system_server scope keeping desktop tasks "perceptible" so they are frozen, not killed. Under real memory pressure something must go - never a desktop app first. | High | Planned |
