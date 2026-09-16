# Tuning the module to your ZuxOS build

Everything here is only needed when something does not work out of the box. The desktop surface
itself does not depend on any of it.

## 1. Get a probe dump

Settings → Diagnostics → **Dump launcher info on attach**, then go back to the desktop mode.
The dump lands in two places:

- the LSPosed log (Manager → Logs), and
- `/sdcard/Android/data/<home package>/files/zux_desktop_plus/probe.txt`

You can also trigger it any time from the desktop: right-click empty space →
**Export layout + launcher info**.

It contains:

```
package : com.zui.home
activity: com.zui.home.desktop.SomeDesktopLauncher
display : id=2 name=... flags=0x...        <- id != 0 means external display
home act: true
widgets : 37 providers visible
view tree
  com.android.internal.policy.DecorView [1920x1200]
    android.widget.FrameLayout #content
      com.zui.home.desktop.DesktopDragLayer #drag_layer
        com.zui.home.desktop.DesktopWorkspace #workspace_grid   <- the stock icon grid
        ...
boolean methods on the activity class (candidates for rules.json)
  isEditModeDisabled(0 args)
  ...
```

## 2. The surface never appears

Check the dump for the activity name and the display id.

- **No dump at all** → the module was never loaded into the home app. Check the LSPosed scope and
  that the process really is the package you scoped.
- **Dump exists but nothing attached** → the activity was not recognised as a home screen. Turn on
  **Attach to any activity**. If the package itself is not in the known list, add it under
  **Extra launcher packages**.
- **Display id is 0 while you are on the external screen** → the desktop-mode home renders on the
  internal display id; switch **Which desktop mode** to "Both".

## 3. You see every icon twice

The stock icon grid was not recognised. Either:

- set **Stock home content** to "Hide everything the stock home draws", or
- tell me the `#id` of the stock grid container from the view tree so it can be added to the
  built-in list in `OemBridge.GRID_ID_HINTS`.

## 4. The launcher is obfuscated - what that means

ZuxOS ships a minified Launcher3. Class names survive (`com.android.launcher3.allapps.
AlphabeticalAppsList` is really called that), but fields and many methods are renamed to `a`,
`b`, `c`... A probe line like this is the giveaway:

```
fields: PRIVATE_SPACE_PACKAGE TAG a b c d e f g h i j k l m n o p q
```

So the stock-drawer integration never looks anything up by name. It finds the app list by
looking for the `List` whose entries carry a `ComponentName`, learns which field holds the label
by matching it against the label the system reports for that same app, and intercepts clicks on
`View.performClick`, which is framework code the obfuscator cannot rename. If a firmware update
reshuffles things, the log says which of those steps failed.

## 5. rules.json — unlocking the stock launcher's own features

Some ZuxOS builds implement rearranging/folders/widgets already and merely *disable* them in
desktop mode. When the probe dump shows a promising boolean (`isEditModeDisabled`,
`isSupportDrag`, `canAddWidget`, …), you can flip it without recompiling anything.

Create `/data/data/<home package>/files/zux_desktop_plus/rules.json` (root needed — the same
directory the module writes its layout to):

```json
{
  "rules": [
    { "class": "com.zui.home.desktop.DesktopLauncher", "method": "isEditModeDisabled", "returns": false },
    { "class": "com.zui.home.desktop.DesktopWorkspace", "methodContains": "candrag", "returns": true },
    { "class": "com.zui.home.desktop.DesktopWorkspace", "method": "isWidgetSupported", "returns": true }
  ]
}
```

- `class` — fully qualified, exactly as printed in the dump.
- `method` — exact name, or `methodContains` for a case-insensitive substring match.
- `returns` — the constant the method should return. Only `boolean` methods are patched.

Rules are applied when the desktop surface attaches, so a restart of the home app is enough
(no reboot). Applied and failed rules are both reported in the LSPosed log.

**Aggressive unlocking** (a settings switch) additionally guesses from method names alone — it
patches `canX`/`supportX`/`allowX` to `true` and `isXDisabled`/`isXLocked` to `false` for names
mentioning drag/edit/folder/widget/reorder/move. It is off by default because a wrong guess
misbehaves in ways that are hard to attribute.

## 6. "nowhere to attach yet"

The desktop home on ZuxOS is `com.zui.launcher.secondarydisplay.SecondaryDisplayLauncher`, and it
has no window to attach to at the moment it resumes - it installs its own layout later, once the
launcher model has loaded. The module handles that by retrying and by attaching the instant the
launcher calls `setContentView`, so a single one of these lines followed by
`desktop surface attached to ...` is normal.

If you only ever see the warning, it prints why: whether the window, the decor view or both are
missing, whether the activity is finishing, and whether the launcher may draw overlay windows.
Send that line - it names the remaining fallback to use.

## 7. Dumping the taskbar

The taskbar and its drawer are separate windows, so they are not in the activity's view tree.
With the taskbar on screen, use **Export layout + launcher info** from the desktop's right-click
menu: it walks every root view in the launcher process and prints each window's tree. That dump is
what any work inside the stock taskbar - status info, toggles, restyling - has to be built from.

## 8. Where the data lives

Inside the home app's private data dir, `files/zux_desktop_plus/`:

| File | Contents |
|---|---|
| `desktop.json` | the external-display desktop layout, including which page each item is on |
| `desktop-internal.json` | the tablet-screen layout (when attached there) |
| `drawer.json` | drawer order, drawer folders, hidden apps |
| `overrides.json` | icon/cell size changed from the desktop menu |
| `rules.json` | your unlock rules (you create this) |
| `probe.txt` | the last diagnostic dump |

Deleting a file resets that part. Uninstalling the module leaves them behind; clearing the home
app's data removes them.
