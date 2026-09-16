package com.zuxos.desktopplus.core;

import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;

/**
 * The liquid-glass lens: an AGSL shader that refracts the backdrop through a rounded-rect lens.
 *
 * <p>The optical model is ported from LiquidGlass for Android by pandadog (MIT licensed,
 * https://github.com/QWEA0/Liquid-Glass-Android): a signed-distance field gives the shape and,
 * through its gradient, a surface normal; a bevel profile turns distance-from-edge into a slope;
 * the backdrop is sampled along that normal, per colour channel, so the rim compresses what is
 * behind it and fringes it with dispersion; a two-lobe specular term lights the rim.
 *
 * <p>Two things differ from the original, both forced by where this runs. The backdrop is captured
 * once per panel rather than re-sampled every frame - a launcher overlay is static while it is
 * open, and this costs nothing per frame. And the sampled alpha is carried through instead of
 * being treated as opaque: the live wallpaper is not part of any view tree, so a panel has to stay
 * genuinely translucent where nothing was captured, and a base tint is composited underneath.
 */
public final class LiquidGlass {

    private static final String LENS_AGSL = """
            uniform shader content;
            uniform float2 viewSize;
            uniform float2 halfSize;
            uniform float radius;
            uniform float bevel;
            uniform float refractPx;
            uniform float falloff;
            uniform float dispersion;
            uniform float2 lightDir;
            uniform float specStrength;
            uniform float4 baseTint;
            uniform float satFactor;

            float sdRoundedBox(float2 p, float2 b, float r) {
                float2 q = abs(p) - b + r;
                return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - r;
            }

            float shape(float2 p) {
                return sdRoundedBox(p - halfSize, halfSize, radius);
            }

            half4 main(float2 coord) {
                float2 p = coord;
                if (p.x < 0.0 || p.y < 0.0 || p.x > viewSize.x || p.y > viewSize.y) {
                    return half4(0.0);
                }
                float d = shape(p);

                // Coverage with a 1.5px feather, so the lens has a clean antialiased edge.
                float cov = clamp(0.5 - d / 1.5, 0.0, 1.0);
                if (cov <= 0.004) {
                    return half4(0.0);
                }

                // Screen-space outward normal, from the numeric gradient of the field.
                float2 n = float2(
                    shape(p + float2(1.0, 0.0)) - shape(p - float2(1.0, 0.0)),
                    shape(p + float2(0.0, 1.0)) - shape(p - float2(0.0, 1.0)));
                float nLen = length(n);
                n = nLen > 0.0001 ? n / nLen : float2(0.0, -1.0);

                // Thickness profile: 1 across the flat interior, 0 at the rim.
                float t = clamp(-d / max(bevel, 1.0), 0.0, 1.0);
                float edge = 1.0 - t;
                float slope;
                if (falloff > 0.001) {
                    // Inverse-power falloff: nearly all of the bending happens in the last few
                    // pixels, which is what makes the rim read as thick glass rather than a bevel.
                    float gB = pow(5.0, -falloff);
                    slope = (pow(1.0 + 4.0 * t, -falloff) - gB) / (1.0 - gB);
                } else {
                    slope = edge * edge;
                }

                // Sample inward along the normal: the rim shows a compressed mirror of the
                // interior, so nothing outside the captured area is ever needed.
                float2 offset = n * (-slope * refractPx);
                float2 lo = float2(0.0, 0.0);
                float2 hi = viewSize;
                float2 cR = clamp(coord + offset * (1.0 - dispersion * slope), lo, hi);
                float2 cG = clamp(coord + offset, lo, hi);
                float2 cB = clamp(coord + offset * (1.0 + dispersion * slope), lo, hi);

                half4 sR = content.eval(cR);
                half4 sG = content.eval(cG);
                half4 sB = content.eval(cB);
                float aR = sR.a;
                float aG = sG.a;
                float aB = sB.a;
                float3 col = float3(
                    aR > 0.001 ? sR.r / aR : 0.0,
                    aG > 0.001 ? sG.g / aG : 0.0,
                    aB > 0.001 ? sB.b / aB : 0.0);
                float a = (aR + aG + aB) / 3.0;

                // Vibrancy rather than a linear saturation lift: low-saturation pixels gain most,
                // already-saturated and very bright ones are protected from blowing out.
                float lum = dot(col, float3(0.2126, 0.7152, 0.0722));
                if (satFactor <= 1.0) {
                    col = mix(float3(lum), col, satFactor);
                } else {
                    float satNow = max(col.r, max(col.g, col.b)) - min(col.r, min(col.g, col.b));
                    float room = 1.0 - smoothstep(0.2, 0.85, satNow);
                    float hl = 1.0 - smoothstep(0.75, 0.98, lum);
                    float amount = 1.0 + (satFactor - 1.0) * mix(0.3, 1.0, room * hl);
                    col = clamp(mix(float3(lum), col, amount), float3(0.0), float3(1.0));
                }

                // Composite over the base tint. Where nothing was captured this is all there is,
                // and its alpha is what lets the wallpaper through.
                float baseA = baseTint.a * (1.0 - a);
                col = col * a + baseTint.rgb * baseA;
                float outA = a + baseA;
                if (outA > 0.001) {
                    col = col / outA;
                }

                // Rim light: one hairline at the edge plus an inward glow on the lit side, both
                // driven by the same normal field, so there is no direction-independent outline.
                float facing = dot(n, -lightDir);
                float lobeF = pow(max(facing, 0.0), 4.5);
                float lobeB = pow(max(-facing, 0.0), 4.5);
                float bandW = clamp(bevel * 0.3, 2.0, 6.0);
                float glowIn = clamp((-d - 1.0) / 2.0, 0.0, 1.0);
                float glow = glowIn * pow(clamp(1.0 - (-d - 3.0) / bandW, 0.0, 1.0), 1.5);
                float hair = clamp(1.0 - abs(d + 1.0) / 2.0, 0.0, 1.0);
                float spec = (hair * 0.70 * (lobeF + lobeB) + glow * 0.10 * lobeF) * specStrength;
                col = clamp(col + float3(spec), float3(0.0), float3(1.0));
                outA = clamp(outA + spec, 0.0, 1.0);

                return half4(half3(col * outA * cov), half(outA * cov));
            }
            """;

    /** Set once a device fails to compile the shader, so it is not retried every frame. */
    private static boolean sBroken;

    private LiquidGlass() {
    }

    public static boolean isSupported() {
        return !sBroken && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && Cfg.glass();
    }

    /**
     * The lens effect for a panel of this size, or null when the device cannot run it.
     *
     * @param tint premultiplied-free ARGB shown where the backdrop captured nothing
     */
    public static RenderEffect lens(int width, int height, float radiusPx, float blurPx, int tint) {
        if (!isSupported() || width <= 0 || height <= 0) {
            return null;
        }
        try {
            RuntimeShader shader = new RuntimeShader(LENS_AGSL);
            shader.setFloatUniform("viewSize", width, height);
            shader.setFloatUniform("halfSize", width / 2f, height / 2f);
            shader.setFloatUniform("radius", radiusPx);
            // A rim about a fifth of the shorter side reads as glass without eating the panel.
            float bevel = Math.max(12f, Math.min(Math.min(width, height) * 0.18f, radiusPx * 2.2f));
            shader.setFloatUniform("bevel", bevel);
            shader.setFloatUniform("refractPx", bevel * 0.85f);
            shader.setFloatUniform("falloff", 2.0f);
            shader.setFloatUniform("dispersion", 0.06f);
            // Light from the top-left, matching where Android draws its own material highlights.
            shader.setFloatUniform("lightDir", -0.55f, -0.83f);
            shader.setFloatUniform("specStrength", 0.9f);
            shader.setFloatUniform("satFactor", 1.25f);
            shader.setFloatUniform("baseTint",
                    ((tint >> 16) & 0xFF) / 255f,
                    ((tint >> 8) & 0xFF) / 255f,
                    (tint & 0xFF) / 255f,
                    ((tint >>> 24) & 0xFF) / 255f);

            RenderEffect lens = RenderEffect.createRuntimeShaderEffect(shader, "content");
            if (blurPx <= 0f) {
                return lens;
            }
            RenderEffect blur = RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP);
            // Blur first, then bend the blurred backdrop through the lens.
            return RenderEffect.createChainEffect(lens, blur);
        } catch (Throwable t) {
            sBroken = true;
            L.e("liquid glass unavailable, falling back to layered translucency", t);
            return null;
        }
    }

    /** Plain backdrop blur, for devices without AGSL. */
    public static RenderEffect blurOnly(float blurPx) {
        if (sBroken || Build.VERSION.SDK_INT < Build.VERSION_CODES.S || blurPx <= 0f) {
            return null;
        }
        try {
            return RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP);
        } catch (Throwable t) {
            return null;
        }
    }
}
