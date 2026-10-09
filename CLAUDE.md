# ZuxOS Desktop Plus - working notes

An LSPosed module for ZUX Home (`com.zui.launcher`) on the Lenovo Legion Tab Gen 5, ZuxOS 2.x,
Android 16. It aims to be the best desktop mode on Android.

## First, every session

Read [`docs/ROADMAP.md`](docs/ROADMAP.md) before doing anything else. It is the project's memory:
the rules, what is done, every bug fixed and whether the owner confirmed it, what is in progress,
and what is planned, in order.

Then [`docs/ZUI-NOTES.md`](docs/ZUI-NOTES.md): what ZUI's own apps do, read from their decompiled
code - which app runs which mode, the settings that switch them, ZUI's heat and app-killing
policy, and where each of our hooks lands. Read it before hooking anything of ZUI's.

## Rules agreed with the owner

- **Polish and stability before new features.**
- **Disable, don't fight ZUI.** Switch ZUI's own behaviour off where it starts: a setting, a
  decision, a resource that is never loaded. Never undo it after it happens, and never redo it
  every frame.
- **Evidence first, no guessing.** Fixes come from the owner's logs, probes, screenshots and
  videos. Where they cannot show something, such as what ZUI itself does, add a read-only trace,
  ship it, and wait for the owner's log before changing behaviour.
- **Three modes, all run by ZUX Home.** Check every change in all three:

  | Mode | Launcher activity | Taskbar |
  |------|-------------------|---------|
  | Tablet, regular | `DrawerLauncher` | ZUI's tablet bar |
  | Tablet, desktop mode | `CustomModeLauncher` | `TaskbarActivityContext`, with ZUI's search box |
  | Monitor desktop | `SecondaryDisplayLauncher` (display 2 or 3) | `TaskbarActivityContextDp`, the `...Dp` classes |

  ZUI says which mode is on in `Settings.System`: `zui_ov_desktop_mode` (tablet desktop mode) and
  `zui_dp_display_pc_mode` (the monitor's desktop). A fourth, **work mode** (`zui_pc_mode`), makes
  a different app the home - Work Launcher, `com.zui.desktoplauncher` - which the module does not
  load into. See `docs/ZUI-NOTES.md`.

- **The system process must never be able to crash** (`android`, system_server):
  - nothing runs at load time but hooks;
  - no service or data-directory calls;
  - every hook is in try/catch;
  - no thread that can throw.

  A crash there is a boot loop, and that has happened twice.
- **Every feature has its own off switch.** When a hook does not find what it expects, the feature
  falls back to stock behaviour and logs why, once.
- **Never break what already works.** Before pushing, re-read the diff for side effects in the
  other two modes.
- **Least CPU, zero latency.** When two ways are equally smooth and equally quick to respond, take
  the one that costs less CPU:
  - events, not polling;
  - nothing ticking while hidden;
  - no work repeated when nothing changed.

  Never trade latency or smoothness for it.
- **Structure and clean code.**
  - One package per area; each `package-info.java` says what belongs there.
  - Shared helpers live in `core/` or `hook/`. An area never borrows another area's internals:
    `hook/system` uses only `core` and `hook` basics.
  - Match the surrounding code: its comments, naming and import order.
- **No dead code.** When a feature is replaced, the old path goes in the same change. Run
  `python3 tools/deadcode.py` and delete what it finds, after checking each finding is not
  reached by name (reflection, Xposed, another tool). Its `KEEP` list holds the ones that are.
- **iOS-quality motion and design**, with the springs from `core/motion/Motion`.

## Workflow

- Work on the branch the session names. Commit with the trailers the session gives. Push with
  `git push -u origin <branch>`.
- Run `python3 tools/check.py` before every commit. It compiles against the real Android framework
  and runs the `logic` unit tests. A commit that touches only docs does not need it.
- Run `python3 tools/deadcode.py` before any commit that removes or replaces code.
- After pushing, wait for the CI workflow **Build module APK** to pass before telling the owner a
  build is ready. Its artifact is `zuxos-desktop-plus-1.0.<N>`.
- The version is `1.0.<commit count>` (`git rev-list --count HEAD`), so the next build is the
  current count plus one.
- No model names in commits, code or docs. No pull request unless the owner asks for one.

## Reading the owner's material

- **LSPosed log zip:**
  - Unzip it into the scratchpad.
  - Grep for `ZuxDesktopPlus`.
  - `log/` holds the current boot; `log.old/` holds the previous one.
  - Read the whole module log, not only the lines about the reported bug.
- **Probe (`probe-<date>_<time>.txt`):**
  - Tasks, with display, bounds, windowing mode and a `fit:` line.
  - The window tree of each launcher window.
  - The drawables on ZUI's home.
  - Which of the three taskbars it was taken from.
- **Videos:**
  - Extract frames with ffmpeg. If it is missing, run
    `pip install --target <scratchpad>/pyff imageio-ffmpeg` and use the binary under
    `imageio_ffmpeg/binaries/`.
  - Look at the frames around every tap.
- **Report every bug the material shows,** not only the one the owner named.

## Talking to the owner

- Keep answers short and plain.
- Say what was verified and what was not.
- A fix is "shipped, awaiting test" until the owner confirms it on the device. Never call it
  confirmed before then.
- When the owner confirms a fix or reopens a bug, update the **Fixed** table in
  `docs/ROADMAP.md`.
