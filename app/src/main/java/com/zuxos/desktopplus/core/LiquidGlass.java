package com.zuxos.desktopplus.core;

import android.content.Context;
import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;

/**
 * Liquid glass, as two layers over one backdrop.
 *
 * <p><b>Frost</b> is the body of the pane: the backdrop heavily blurred, given iOS's vibrancy -
 * colour lifted where there is little of it, protected where there is a lot - a breath of
 * brightness and the material's tint, cut to a continuous-corner (superellipse) outline.
 *
 * <p><b>Lens</b> is the edge, drawn over the frost. Across a rim band the glass is modelled as a
 * real surface: a quarter-circle height profile, from which a true 3D normal, through which a ray
 * straight into the screen is refracted by Snell's law at glass's IOR - per colour channel, at
 * three slightly different IORs, which is where the fringing comes from. What it refracts is the
 * backdrop only lightly blurred, so the bending is visible: shapes behind the rim are magnified
 * and pulled round it, and fade into the frost as the rim flattens out. Fresnel brightens the rim
 * as it turns away, a two-lobe specular lights it from the top-left, and a faint shade sits inside
 * the far edge so the pane has depth.
 *
 * <p>Why two layers: in the flat interior the refraction offset is zero, so the frost needs no
 * bending, and only the rim needs the sharp backdrop. Splitting them lets each be one ordinary
 * blur plus one shader on the GPU, at any size, instead of a heavy blur inside a shader.
 *
 * <p>The AGSL below is also what {@code tools/glass_preview.py} runs, read straight out of this
 * file, so the previews are of exactly this code.
 */
public final class LiquidGlass {

    /** What a pane is made of. Lengths in dp. */
    public static final class Material {
        public final String name;
        public final float blur;
        public final float sharp;
        public final float bevel;
        public final float depth;
        public final float ior;
        public final float dispersion;
        public final float sat;
        public final float lift;
        public final float tintAlpha;
        public final float fresnel;
        public final float spec;
        public final float shadow;

        Material(String name, float blur, float sharp, float bevel, float depth, float ior,
                float dispersion, float sat, float lift, float tintAlpha, float fresnel,
                float spec, float shadow) {
            this.name = name;
            this.blur = blur;
            this.sharp = sharp;
            this.bevel = bevel;
            this.depth = depth;
            this.ior = ior;
            this.dispersion = dispersion;
            this.sat = sat;
            this.lift = lift;
            this.tintAlpha = tintAlpha;
            this.fresnel = fresnel;
            this.spec = spec;
            this.shadow = shadow;
        }
    }

    // The table tools/glass_preview.py reads. Keep one material per line.
    /** The bar and menus: frosted enough to read over anything, edges that clearly bend. */
    public static final Material REGULAR = new Material("regular", 24f, 1.5f, 20f, 26f, 1.5f, 0.06f, 1.35f, 0.015f, 0.12f, 0.16f, 0.8f, 0.12f);
    /** Sheets - the app drawer, the quick panel: thicker glass, much more blur. */
    public static final Material THICK = new Material("thick", 40f, 2f, 28f, 36f, 1.5f, 0.06f, 1.4f, 0.02f, 0.24f, 0.14f, 0.8f, 0.12f);
    /**
     * Menus and folders: a denser body than the bar. Over a dark, busy wallpaper a lightly tinted
     * frost is nearly the wallpaper itself, and a menu made of it reads as text with no
     * background at all - so, like iOS, menus carry far more of their own tint.
     */
    public static final Material MENU = new Material("menu", 30f, 1.5f, 18f, 22f, 1.5f, 0.06f, 1.4f, 0.04f, 0.58f, 0.16f, 0.8f, 0.12f);

    /** Superellipse exponent of the corners: 2 is a circle, iOS's continuous corner is about 4. */
    public static final float SMOOTH = 4f;

    /** Where the light comes from, as a direction in the screen: the top-left. */
    public static final float LIGHT_X = -0.55f;
    public static final float LIGHT_Y = -0.83f;

    private static final String SHAPE_AGSL = """
            uniform float2 size;
            uniform float extendB;
            uniform float extendX;
            uniform float radius;
            uniform float smoothN;

            // Continuous-corner rounded box. The shape may run past the bottom of the view by
            // extendB, which squares off the bottom corners of a pane sitting on a screen edge,
            // and past both sides by extendX, for a bar that spans the screen end to end.
            float sdShape(float2 p) {
                float2 halfS = float2(size.x + 2.0 * extendX, size.y + extendB) * 0.5;
                float2 centre = float2(size.x * 0.5, halfS.y);
                float2 q = abs(p - centre) - halfS + radius;
                float2 m = max(q, float2(0.0)) / max(radius, 0.001);
                float corner = radius * pow(pow(m.x, smoothN) + pow(m.y, smoothN) + 1e-9,
                        1.0 / smoothN);
                return corner + min(max(q.x, q.y), 0.0) - radius;
            }
            """;

    private static final String BODY_AGSL = """
            uniform float sat;
            uniform float lift;
            uniform float4 tint;

            // Vibrancy, brightness and tint: the glass's own body.
            float3 body(float3 c) {
                float lum = dot(c, float3(0.2126, 0.7152, 0.0722));
                float satNow = max(c.r, max(c.g, c.b)) - min(c.r, min(c.g, c.b));
                float room = 1.0 - smoothstep(0.1, 0.6, satNow);
                float hl = 1.0 - smoothstep(0.75, 0.98, lum);
                float amount = 1.0 + (sat - 1.0) * (0.15 + 0.85 * room * hl);
                c = clamp(mix(float3(lum), c, amount), float3(0.0), float3(1.0));
                c = clamp(c + float3(lift), float3(0.0), float3(1.0));
                return mix(c, tint.rgb, tint.a);
            }
            """;

    /** The frost: content is the heavily blurred backdrop. */
    static final String FROST_AGSL = SHAPE_AGSL + BODY_AGSL + """
            uniform shader content;
            uniform float4 base;

            half4 main(float2 p) {
                float cov = clamp(0.5 - sdShape(p), 0.0, 1.0);
                if (cov <= 0.0) {
                    return half4(0.0);
                }
                half4 s = content.eval(p);
                float a = float(s.a);
                float3 c = a > 0.001 ? float3(s.rgb) / a : float3(0.0);
                c = body(c);
                // Where nothing was captured the base colour carries the pane.
                float outA = a + base.a * (1.0 - a);
                float3 pm = c * a + base.rgb * base.a * (1.0 - a);
                return half4(half3(pm * cov), half(outA * cov));
            }
            """;

    /** The lens: content is the lightly blurred backdrop; drawn over the frost. */
    static final String LENS_AGSL = SHAPE_AGSL + BODY_AGSL + """
            uniform shader content;
            uniform float bevel;
            uniform float depth;
            uniform float ior;
            uniform float dispersion;
            uniform float fresnel;
            uniform float spec;
            uniform float shadow;
            uniform float2 lightDir;

            // GLSL refract() of a ray straight into the screen, returned as T.xy / -T.z.
            float2 refr(float3 n, float eta) {
                float k = max(1.0 - eta * eta * (1.0 - n.z * n.z), 0.0);
                float a = eta * n.z - sqrt(k);
                float tz = min(-eta + a * n.z, -0.15);
                return float2(a * n.x, a * n.y) / -tz;
            }

            float3 sampleAt(float2 p) {
                half4 s = content.eval(p);
                float a = float(s.a);
                return a > 0.001 ? float3(s.rgb) / a : float3(0.0);
            }

            half4 main(float2 p) {
                float d = sdShape(p);
                float cov = clamp(0.5 - d, 0.0, 1.0);
                float s = -d;
                if (cov <= 0.0 || s > bevel * 3.0) {
                    // Past the rim's reach nothing here adds anything: the frost shows alone.
                    return half4(0.0);
                }
                float2 g = float2(sdShape(p + float2(1.0, 0.0)) - sdShape(p - float2(1.0, 0.0)),
                        sdShape(p + float2(0.0, 1.0)) - sdShape(p - float2(0.0, 1.0)));
                float gl = length(g);
                float2 n2 = gl > 0.0001 ? g / gl : float2(0.0, -1.0);

                // A quarter-circle across the rim: flat inside, vertical at the very edge.
                float t = clamp(s / bevel, 0.0, 1.0);
                float u = 1.0 - t;
                float hgt = sqrt(max(1.0 - u * u, 0.0));
                float slope = clamp(u / max(hgt, 0.05), 0.0, 20.0);
                float3 n = normalize(float3(n2 * slope, 1.0));

                // Snell per channel; the IOR spread is the dispersion.
                float2 oR = refr(n, 1.0 / (ior * (1.0 - dispersion))) * depth;
                float2 oG = refr(n, 1.0 / ior) * depth;
                float2 oB = refr(n, 1.0 / (ior * (1.0 + dispersion))) * depth;
                float3 c = float3(sampleAt(p + oR).r, sampleAt(p + oG).g, sampleAt(p + oB).b);
                c = body(c);

                // How much of the bent, sharper backdrop shows over the frost.
                // Scaled by how much backdrop there is: nothing captured yet, nothing to bend.
                float rim = pow(u, 1.6) * float(content.eval(p + oG).a);

                // Fresnel, the specular rim and the inner shade.
                float fres = 0.04 + 0.96 * pow(1.0 - n.z, 5.0);
                float facing = dot(n2, -lightDir);
                float band = exp(-max(s, 0.0) / (bevel * 0.16));
                float lit = pow(max(facing, 0.0), 2.0);
                float back = 0.3 * pow(max(-facing, 0.0), 2.0);
                float hair = clamp(1.0 - abs(s - 0.75), 0.0, 1.0) * 0.22;
                float light = fres * fresnel + (band * (lit + back) * 0.55 + hair) * spec;
                float shade = exp(-max(s, 0.0) / (bevel * 0.5))
                        * pow(max(-facing, 0.0), 1.5) * shadow;

                // Over the frost F this gives (c * rim + F * (1 - rim)) * (1 - shade) + light.
                float3 pm = c * rim * (1.0 - shade) + float3(light);
                float outA = clamp(rim + shade * (1.0 - rim), 0.0, 1.0);
                return half4(half3(pm * cov), half(outA * cov));
            }
            """;

    /** Set once a device fails to compile the shaders, so they are not retried every frame. */
    private static boolean sBroken;

    private LiquidGlass() {
    }

    public static boolean isSupported() {
        return !sBroken && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && Cfg.glass();
    }

    /** The glass's tint for the current tone: white over light glass, near-black over dark. */
    public static int tintFor(boolean dark) {
        return dark ? 0xFF2C2C33 : 0xFFF7F7FA;
    }

    /**
     * The body of a pane of this size.
     *
     * <p>Every length - size, corner, blur - is given at {@code pxScale} of the pane's size, for
     * a frost layer drawn small and scaled up: the heavy blur runs over a quarter of the pixels
     * at half size and looks the same once scaled.
     *
     * @param extendBottomPx how far the shape runs past the view's bottom edge (0 for a free
     *                       pane; the corner radius for one sitting on the screen edge)
     * @param extendSidesPx  how far it runs past both sides: a bar from one end of the screen to
     *                       the other, whose rim would otherwise curve back in at both ends
     * @param tintRgb        the material's colour; its alpha comes from the material
     * @param base           shown where the backdrop has nothing, as ARGB; 0 for none
     */
    public static RenderEffect frost(Context ctx, Material m, int width, int height,
            float radiusPx, float extendBottomPx, float extendSidesPx, int tintRgb, int base,
            float pxScale) {
        if (!isSupported() || width <= 0 || height <= 0) {
            return null;
        }
        try {
            RuntimeShader shader = new RuntimeShader(FROST_AGSL);
            shape(shader, width, height, radiusPx, extendBottomPx, extendSidesPx);
            body(shader, m, tintRgb);
            shader.setFloatUniform("base", channel(base, 16), channel(base, 8), channel(base, 0),
                    channel(base, 24));
            return RenderEffect.createChainEffect(
                    RenderEffect.createRuntimeShaderEffect(shader, "content"),
                    blur(Ui.dp(ctx, m.blur) * pxScale));
        } catch (Throwable t) {
            broken(t);
            return null;
        }
    }

    /** The rim of the same pane, drawn over its {@link #frost}. */
    public static RenderEffect lens(Context ctx, Material m, int width, int height,
            float radiusPx, float extendBottomPx, float extendSidesPx, int tintRgb) {
        if (!isSupported() || width <= 0 || height <= 0) {
            return null;
        }
        try {
            RuntimeShader shader = new RuntimeShader(LENS_AGSL);
            shape(shader, width, height, radiusPx, extendBottomPx, extendSidesPx);
            body(shader, m, tintRgb);
            shader.setFloatUniform("bevel", Ui.dp(ctx, m.bevel));
            shader.setFloatUniform("depth", Ui.dp(ctx, m.depth));
            shader.setFloatUniform("ior", m.ior);
            shader.setFloatUniform("dispersion", m.dispersion);
            shader.setFloatUniform("fresnel", m.fresnel);
            shader.setFloatUniform("spec", m.spec);
            shader.setFloatUniform("shadow", m.shadow);
            float len = (float) Math.hypot(LIGHT_X, LIGHT_Y);
            shader.setFloatUniform("lightDir", LIGHT_X / len, LIGHT_Y / len);
            return RenderEffect.createChainEffect(
                    RenderEffect.createRuntimeShaderEffect(shader, "content"),
                    blur(Ui.dp(ctx, m.sharp)));
        } catch (Throwable t) {
            broken(t);
            return null;
        }
    }

    private static void shape(RuntimeShader shader, int width, int height, float radius,
            float extend, float sides) {
        shader.setFloatUniform("size", width, height);
        shader.setFloatUniform("extendB", extend);
        shader.setFloatUniform("extendX", sides);
        shader.setFloatUniform("radius", Math.max(0f, Math.min(radius,
                Math.min(width + 2f * sides, height + extend) / 2f)));
        shader.setFloatUniform("smoothN", SMOOTH);
    }

    private static void body(RuntimeShader shader, Material m, int tintRgb) {
        shader.setFloatUniform("sat", m.sat);
        shader.setFloatUniform("lift", m.lift);
        shader.setFloatUniform("tint", channel(tintRgb, 16), channel(tintRgb, 8),
                channel(tintRgb, 0), m.tintAlpha);
    }

    private static RenderEffect blur(float px) {
        float r = Math.max(0.5f, px);
        return RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP);
    }

    private static float channel(int argb, int shift) {
        return ((argb >>> shift) & 0xFF) / 255f;
    }

    private static void broken(Throwable t) {
        if (!sBroken) {
            sBroken = true;
            L.e("liquid glass: the shaders would not build on this device - plain blur instead", t);
        }
    }
}
