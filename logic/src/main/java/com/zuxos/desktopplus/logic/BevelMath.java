package com.zuxos.desktopplus.logic;

/**
 * The colours of a Windows 98 bevel, from the colour of its face.
 *
 * <p>Every raised or sunken box in the Retro theme is its face and four edge colours: a light
 * edge and a lighter one outside it where the light falls (top and left), a shadow and a darker
 * one where it does not (bottom and right). Windows 98 used fixed greys; deriving the inner two
 * from the face keeps the same look for a face of any colour, which is what the taskbar colours
 * of the user's choosing (#15) will need. For the classic grey, {@code #C0C0C0}, the maths gives
 * exactly the classic edges: {@code #FFFFFF}, {@code #DFDFDF}, {@code #808080}, {@code #000000}.
 */
public final class BevelMath {

    /** The classic face. */
    public static final int FACE = 0xFFC0C0C0;
    /** The outer light edge. */
    public static final int HIGHLIGHT = 0xFFFFFFFF;
    /** The outer shadow edge. */
    public static final int DARK_SHADOW = 0xFF000000;

    private BevelMath() {
    }

    /** The inner light edge: half way from the face to white. */
    public static int light(int face) {
        return mix(face, HIGHLIGHT, 1, 2);
    }

    /** The inner shadow edge: a third of the way from the face to black. */
    public static int shadow(int face) {
        return mix(face, DARK_SHADOW, 1, 3);
    }

    /** Black or white, whichever reads better on the face. */
    public static int textOn(int face) {
        return ToneMath.contrast(face, 0xFF000000) >= ToneMath.contrast(face, 0xFFFFFFFF)
                ? 0xFF000000 : 0xFFFFFFFF;
    }

    /**
     * {@code a} moved {@code num/den} of the way to {@code b}, channel by channel, opaque. In
     * whole numbers, rounded towards {@code a}: the classic greys land on their exact values.
     */
    static int mix(int a, int b, int num, int den) {
        int r = channel(a >> 16, b >> 16, num, den);
        int g = channel(a >> 8, b >> 8, num, den);
        int bl = channel(a, b, num, den);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    private static int channel(int a, int b, int num, int den) {
        int from = a & 0xFF;
        int to = b & 0xFF;
        return from + (to - from) * num / den;
    }
}
