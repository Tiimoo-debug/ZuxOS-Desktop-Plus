# ZuxOS Desktop Plus

An LSPosed module that gives the **external-display desktop mode** on ZuxOS/ZUI tablets a real
home screen: rearrangeable icons, folders, shortcuts and widgets — plus an app drawer you can
sort yourself and group into folders.

Built for and tested against the setup it was written for:

| | |
|---|---|
| Device | Lenovo Legion Tab Gen 5 (Snapdragon 8 Elite Gen 5) |
| OS | Android 16 / ZuxOS 2.0.10.026 |
| Target | the ZUI/Zux home app that draws the desktop-mode home screen |

## What it adds

On the external-display desktop:

- **Rearranging** — drag any icon to any grid cell. Drag it to the *Remove* bar at the top to take
  it off the desktop.
- **Folders** — drop one icon onto another to create a folder; open it to launch, rename it in
  place, drag items back out, or unpack it from the icon's menu.
- **Shortcuts** — pin an app's deep shortcuts ("New message", "New private tab", …) straight onto
  the desktop.
- **Widgets** — a real `AppWidgetHost` runs inside the launcher, so widgets work even though the
  stock desktop mode has no widget support at all. Long-press one for a resize frame: drag an edge
  to resize, the middle to move, and Remove / Done in the bar at the top.
- **Pages** — arrows at the edges and dots at the bottom. Drag an icon onto an arrow to send it to
  the next page; dragging past the last page starts a new one.
- **Pinned shortcuts land here** — a web page or file pinned from any app (a browser's "add to
  home screen") normally goes to the tablet's home screen and never appears in desktop mode. The
  module catches the request and puts it on the desktop too.
- **Multi-select** — build a folder by ticking a list of apps rather than one drag at a time:
  right-click the desktop for *New folder with apps*, or the drawer's three-dot menu for
  *New folder with apps*; an existing folder's menu has *Add apps to folder*.
- **Liquid glass** — the drawer and folder panels refract what is behind them through a
  signed-distance lens: the rim compresses the backdrop, fringes it with chromatic dispersion and
  carries a two-lobe specular highlight. The optical model is ported from
  [LiquidGlass for Android](https://github.com/QWEA0/Liquid-Glass-Android) (MIT) - see
  [NOTICE.md](NOTICE.md). Needs Android 13+; older versions fall back to blur, then to layered
  translucency.

In the app drawer - both the module's own (the **Apps** button, or the All-Apps / Search key) and,
where the launcher's list can be reached, **the stock drawer the taskbar opens**:

- **Your own order** — drag apps around; the order is remembered. Or keep A–Z.
- **Drawer folders** — drop one app onto another to group them; apps inside a folder stop
  cluttering the flat list.
- **Search**, **hide apps**, and drag any app straight out of the drawer onto the desktop.

Folders, ordering and hidden apps are one shared set of data, so whatever you arrange in the
module's drawer shows up in the stock one as well. Tapping a folder there opens it in a window of
its own. Turn this off with **Use the stock taskbar drawer too** if you would rather leave the
launcher's drawer untouched.

## How it works (and why it works on an OEM launcher nobody can read)

The module does **not** try to talk the stock launcher into features it was built without. It
layers its own surface into the desktop-mode home activity and implements the behaviour itself
with public Android APIs (`LauncherApps`, `AppWidgetHost`, the drag-and-drop framework). The only
OEM-specific things it needs are:

1. *Which activity is the desktop home* — detected from the display id plus the system's own list
   of home activities, with a name-based fallback. On ZuxOS 2.0.10.026 this is
   `com.zui.launcher.secondarydisplay.SecondaryDisplayLauncher`, an AOSP-derived secondary-display
   launcher which installs its layout only after the launcher model loads, so the module retries
   and also attaches the moment `setContentView` runs.
2. *Which stock view draws the old icon grid* — hidden so you do not see every app twice. If the
   detection misses, switch **Stock home content** to "Hide everything the stock home draws".

Because the tablet has **two** desktop modes (the one on the tablet screen and the one on an
external display), the module asks which one to attach to. The default is external-display only.

There is also an optional second front — *Stock launcher unlocking* — which flips the launcher's
own "editing disabled" booleans where they can be named. It is a bonus, not a dependency:
everything above works with it turned off. See [docs/TUNING.md](docs/TUNING.md).

## Requirements

- Root with **LSPosed** (Zygisk on Magisk, or KernelSU/APatch equivalents) working on Android 16.
- The module enabled in LSPosed Manager, **scoped to your home app** — `com.zui.home`,
  `com.zui.launcher` or whatever your build uses. The module's default scope lists the names ZUI
  has used; if yours differs, tick it in LSPosed and add the package name in the module settings.
- A reboot (or a restart of the home app) after enabling.

## Building

Nothing exotic — it is a normal Gradle/AGP project with no third-party dependencies (the Xposed
API is vendored as compile-only stubs in `xposed-api/`, so the build never depends on the
frequently-offline Xposed maven repo).

```bash
./gradlew assembleRelease     # app/build/outputs/apk/release/app-release.apk
```

Or open the project in Android Studio, or grab the APK from the **Build module APK** GitHub
Actions run for any pushed commit.

Both the release and debug APKs are signed with the key committed in `keystore/`, and the version
code climbs with the commit count. That means **every build installs over the previous one** - no
uninstall, no lost settings. The key is deliberately not a secret; it exists so updates are
painless. Swap in your own signing config if you ever distribute this.

## Using it

1. Install the APK, enable the module in LSPosed, scope it to the home app, reboot.
   (Updates after that are a plain install over the top - same key, higher version code.)
2. Open the settings app once ("ZuxOS Desktop Plus"). The banner at the top says whether LSPosed
   really has the module loaded.
3. Connect the external display and enter desktop mode.
4. **Right-click** (or long-press) empty desktop space for the main menu: add apps, widgets,
   shortcuts, folders, change icon/cell size, tidy up icons, export diagnostics.
5. **Right-click** an icon or widget for its own menu. **Drag** to move. **Drop on another icon**
   to make a folder.
6. Click **Apps** (bottom-left) for the drawer. Three-dot menu → sorting, resetting order,
   un-hiding apps.

Everything you arrange is stored inside the launcher's own data directory
(`files/zux_desktop_plus/`), so it survives launcher restarts and reboots.

## Settings worth knowing

| Setting | Why you would change it |
|---|---|
| **Which desktop mode** | The tablet has two. Default: external display only. |
| **Stock home content** | How much of the stock home to hide. Start with "Hide the stock icon grid"; switch to "Hide everything" if you still see duplicated icons. |
| **Attach to any activity** | Last resort if the desktop home is not detected. |
| **Extra launcher packages** | If your ZuxOS build ships the home app under a package the module does not know. |
| **Dump launcher info on attach** | Writes the activity name, display info and full view tree to the LSPosed log and to `Android/data/<home app>/files/zux_desktop_plus/probe.txt`. This is the file to look at (or attach to a bug report) when something does not appear. |
| **Desktop pages / Glass style / Animations** | All three are on by default and can be turned off individually. |
| **Show the Apps button** | Off by default, because the stock taskbar sits on top of it. The drawer still opens from the desktop menu or the All-apps key. |

## Not done yet

These were asked for and are honestly not built:

- **Anything inside the stock taskbar** — notifications, network info, quick toggles, or restyling
  it as glass. The taskbar is a separate window owned by the launcher, and injecting views into it
  needs its view tree identified first (`Export layout + launcher info` now dumps every window in
  the process, which is the missing input).
- **Notifications** — needs a `NotificationListenerService` in the settings app plus an explicit
  grant from the user; a real feature in its own right rather than a tweak.
- **Dragging out of the *stock* drawer** onto the desktop — that is a cross-window drag. The
  module's own drawer already supports dragging apps straight out onto the desktop.
- **Glass on the stock drawer** — same dependency as the taskbar. The module's own panels are
  real liquid glass; the stock drawer is the launcher's own view tree and restyling it needs the
  taskbar dump above.
- **The wallpaper is not refracted.** It is drawn by the system behind the window, not by any
  view, so it cannot be captured and bent. Panels stay honestly translucent over it and refract
  what they *can* see - the desktop's own icons and widgets. Windows the module owns ask the
  system for real blur behind them, which does cover the wallpaper.

## Known limits — read before filing a bug

- **This has not been run on a device by its author.** It compiles against the Android framework
  and the logic is defensive throughout, but the one thing nobody can do without your tablet in
  hand is confirm which activity and which stock view ZuxOS 2.0.10.026 actually uses. If the
  surface does not appear, the probe dump (above) has everything needed to fix it, and
  `docs/TUNING.md` explains what to do with it.
- The **stock drawer** integration works by rewriting Launcher3's own app list, which ZuxOS uses
  under its original class names. If a firmware update renames those classes the module says so in
  the log and leaves the stock drawer alone; the module's own drawer keeps working either way.
- **Widgets** need the home app to be allowed to bind them. System launchers normally hold
  `BIND_APPWIDGET`; if yours does not, Android's own "allow this widget?" dialog appears instead.
- **Deep shortcuts** require the home app to be the default launcher (it normally is).
- The desktop is a single page — no horizontal paging.
- Widgets resize through a size dialog rather than drag handles (deliberate: it is far easier
  with a mouse).
- The layout is stored per *kind* of display (external vs tablet), not per individual monitor.

## Layout of the repo

```
app/src/main/java/com/zuxos/desktopplus/
  core/      config, logging, storage, reflection helpers
  hook/      LSPosed entry point, activity watcher, home detection, stock-launcher unlocking, probe
  model/     items, desktop/drawer persistence, app + icon repository
  desktop/   the desktop surface: grid, icons, folders, widgets, menus, dialogs
  drawer/    the app drawer
  ui/        the settings app
xposed-api/  compile-only Xposed API stubs (never packaged)
```
