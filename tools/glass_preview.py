#!/usr/bin/env python3
"""
Offline preview of the module's liquid glass - the real shaders, not a re-implementation.

The AGSL and the material table are read straight out of core/LiquidGlass.java and run through
Skia, which is what AGSL is on Android, over real screenshots. Blurs are Skia's own, with the
radius-to-sigma conversion RenderEffect.createBlurEffect uses, and each layer is cropped to the
pane before it is blurred, exactly as a view's RenderEffect is. So what this draws is what the
tablet draws, and a look can be judged and tuned before any build exists.

    PYTHONPATH=<skia-python,numpy,pillow> python3 tools/glass_preview.py \\
        APPS.png DESKTOP.png OUT_DIR

Needs skia-python, numpy and Pillow (and libEGL). Nothing here ships in the APK.
"""

import os
import re
import sys

import numpy as np
import skia
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
JAVA = os.path.join(HERE, "..", "app", "src", "main", "java", "com", "zuxos", "desktopplus",
                    "core", "LiquidGlass.java")

# The external screen: 2560x1440, 1dp = 1.333px (the probe's 60px icons are 45dp).
DENSITY = 1.333


def load_java():
    src = open(JAVA, encoding="utf-8").read()
    blocks = re.findall(r'"""\n(.*?)"""', src, re.S)
    if len(blocks) != 4:
        sys.exit("expected 4 AGSL blocks in LiquidGlass.java, found %d" % len(blocks))
    shape, body, frost_main, lens_main = blocks
    materials = {}
    keys = ["blur", "sharp", "bevel", "depth", "ior", "dispersion", "sat", "lift",
            "tintAlpha", "fresnel", "spec", "shadow"]
    for m in re.finditer(r'new Material\("(\w+)",\s*([^)]*)\)', src):
        nums = [float(x.strip().rstrip("f")) for x in m.group(2).split(",")]
        materials[m.group(1)] = dict(zip(keys, nums))
    smooth = float(re.search(r"SMOOTH = ([\d.]+)f", src).group(1))
    lx = float(re.search(r"LIGHT_X = (-?[\d.]+)f", src).group(1))
    ly = float(re.search(r"LIGHT_Y = (-?[\d.]+)f", src).group(1))
    return shape + body + frost_main, shape + body + lens_main, materials, smooth, (lx, ly)


FROST, LENS, MATERIALS, SMOOTH, LIGHT = load_java()


def effect(src, name):
    eff = skia.RuntimeEffect.MakeForShader(src)
    if eff is None:
        sys.exit(name + " AGSL did not compile")
    return eff


FROST_FX = effect(FROST, "frost")
LENS_FX = effect(LENS, "lens")


def dp(v):
    return v * DENSITY


def sigma(radius):
    return 0.57735 * radius + 0.5


def to_image(arr):
    rgba = np.dstack([arr, np.ones(arr.shape[:2])])
    data = np.ascontiguousarray((np.clip(rgba, 0, 1) * 255 + 0.5).astype(np.uint8))
    return skia.Image.fromarray(data, colorType=skia.kRGBA_8888_ColorType)


def blurred(img, radius_px, w, h):
    surf = skia.Surface(w, h)
    paint = skia.Paint(ImageFilter=skia.ImageFilters.Blur(
        sigma(radius_px), sigma(radius_px), skia.TileMode.kClamp))
    surf.getCanvas().drawImage(img, 0, 0, skia.SamplingOptions(), paint)
    return surf.makeImageSnapshot()


def set_u(builder, name, values):
    """skia-python takes a bare float for a scalar uniform and a list for a vector."""
    builder.setUniform(name, values[0] if len(values) == 1 else values)


def uniforms(builder, w, h, radius, extend, m, tint):
    set_u(builder, "size", [float(w), float(h)])
    set_u(builder, "extendB", [float(extend)])
    set_u(builder, "radius", [float(min(radius, min(w, h + extend) / 2))])
    set_u(builder, "smoothN", [SMOOTH])
    set_u(builder, "sat", [m["sat"]])
    set_u(builder, "lift", [m["lift"]])
    set_u(builder, "tint", [tint[0], tint[1], tint[2], m["tintAlpha"]])


def pane(canvas, bg, rect, radius_dp, material, extend_px=0.0, dark=False):
    """Draws one pane onto `canvas` over `bg` (numpy HxWx3), exactly as the two views do."""
    m = MATERIALS[material]
    x, y, w, h = rect
    crop = to_image(bg[y:y + h, x:x + w])
    tint = (0.11, 0.11, 0.13) if dark else (1.0, 1.0, 1.0)
    radius = dp(radius_dp)
    linear = skia.SamplingOptions(skia.FilterMode.kLinear)

    fb = skia.RuntimeShaderBuilder(FROST_FX)
    uniforms(fb, w, h, radius, extend_px, m, tint)
    set_u(fb, "base", [0.0, 0.0, 0.0, 0.0])
    fb.setChild("content", blurred(crop, dp(m["blur"]), w, h).makeShader(linear))

    lb = skia.RuntimeShaderBuilder(LENS_FX)
    uniforms(lb, w, h, radius, extend_px, m, tint)
    for k in ("bevel", "depth"):
        set_u(lb, k, [dp(m[k])])
    for k in ("ior", "dispersion", "fresnel", "spec", "shadow"):
        set_u(lb, k, [m[k]])
    n = (LIGHT[0] ** 2 + LIGHT[1] ** 2) ** 0.5
    set_u(lb, "lightDir", [LIGHT[0] / n, LIGHT[1] / n])
    lb.setChild("content", blurred(crop, dp(m["sharp"]), w, h).makeShader(linear))

    canvas.save()
    canvas.translate(x, y)
    canvas.clipRect(skia.Rect(0, 0, w, h))
    canvas.drawPaint(skia.Paint(Shader=fb.makeShader()))
    canvas.drawPaint(skia.Paint(Shader=lb.makeShader()))
    canvas.restore()


def old_pane(canvas, bg, rect, radius_dp, blur_dp, tint_argb):
    """Today's glass, for the side-by-side: the compositor's blur and a flat tint."""
    x, y, w, h = rect
    img = blurred(to_image(bg[y:y + h, x:x + w]), dp(blur_dp), w, h)
    canvas.save()
    canvas.translate(x, y)
    path = skia.Path()
    path.addRRect(skia.RRect.MakeRectXY(skia.Rect(0, 0, w, h), dp(radius_dp), dp(radius_dp)))
    canvas.clipPath(path, doAntiAlias=True)
    canvas.drawImage(img, 0, 0)
    a, r, g, b = [(tint_argb >> s) & 0xFF for s in (24, 16, 8, 0)]
    canvas.drawColor(skia.Color(r, g, b, a))
    canvas.restore()


def render(bg, draw, dim=0.0):
    h, w = bg.shape[:2]
    surf = skia.Surface(w, h)
    c = surf.getCanvas()
    c.drawImage(to_image(bg * (1 - dim)), 0, 0)
    draw(c)
    return surf.makeImageSnapshot().toarray(colorType=skia.kRGBA_8888_ColorType)[..., :3].astype(np.float64) / 255.0


def save_pair(before, after, path, crop=None, scale=0.5):
    if crop:
        x, y, w, h = crop
        before, after = before[y:y + h, x:x + w], after[y:y + h, x:x + w]
        scale = 1
    gap = np.ones((before.shape[0], 12, 3))
    img = Image.fromarray((np.clip(np.concatenate([before, gap, after], 1), 0, 1) * 255)
                          .astype(np.uint8))
    if scale != 1:
        img = img.resize((int(img.width * scale), int(img.height * scale)), Image.LANCZOS)
    img.save(path)


def load(path):
    return np.asarray(Image.open(path).convert("RGB"), dtype=np.float64) / 255.0


def main():
    if len(sys.argv) != 4:
        print(__doc__)
        sys.exit(1)
    apps, desk, out = load(sys.argv[1]), load(sys.argv[2]), sys.argv[3]
    os.makedirs(out, exist_ok=True)

    # 1. ZUI's drawer sheet: 1920 wide, ending at the taskbar, ZUI's scrim dimming the rest.
    #    The glass sees what is under the drawer's window - the apps, not the scrim.
    sheet = (320, 150, 1920, 1210)
    before = render(apps, lambda c: old_pane(c, apps * 0.5, sheet, 24, 40, 0x73F2F3F7), dim=0.5)
    after = render(apps, lambda c: pane(c, apps, sheet, 24, "thick"), dim=0.5)
    save_pair(before, after, os.path.join(out, "1_drawer.png"))
    save_pair(before, after, os.path.join(out, "1_drawer_edge.png"), crop=(220, 60, 700, 520))

    # 2. The taskbar over an app. The recording has the old bar in it; the live capture leaves
    #    the bar's own window out, so app content from higher up stands in for what is under it.
    under = apps.copy()
    under[1280:1440] = apps[1000:1160]
    bar = (0, 1360, 2560, 80)
    before = render(under, lambda c: old_pane(c, under, (0, 1360, 2560, 104), 18, 40,
                                              0x1AFFFFFF))
    after = render(under, lambda c: pane(c, under, bar, 18, "regular", extend_px=dp(18)))
    save_pair(before, after, os.path.join(out, "2_taskbar.png"), crop=(0, 1250, 1300, 190))

    # 3. A menu and a folder over the desktop.
    menu, folder = (330, 800, 330, 520), (1140, 556, 274, 328)
    before = render(desk, lambda c: (old_pane(c, desk, menu, 16, 40, 0x33FFFFFF),
                                     old_pane(c, desk, folder, 24, 40, 0x33FFFFFF)))
    after = render(desk, lambda c: (pane(c, desk, menu, 16, "regular"),
                                    pane(c, desk, folder, 24, "regular")))
    save_pair(before, after, os.path.join(out, "3_menu_folder.png"),
              crop=(250, 480, 1300, 960))

    # 4. The same drawer in dark glass, for a dark theme.
    light = after_drawer = render(apps, lambda c: pane(c, apps, sheet, 24, "thick"), dim=0.5)
    dark = render(apps, lambda c: pane(c, apps, sheet, 24, "thick", dark=True), dim=0.5)
    save_pair(light, dark, os.path.join(out, "4_drawer_light_dark.png"))
    print("written to", out)


if __name__ == "__main__":
    main()
