package com.zuxos.desktopplus.logic;

/**
 * Where a maximised window goes, and whether one is maximised, by ZUI's own rule.
 *
 * <p>Read from ZUI's window shell on the owner's firmware ({@code DesktopModeUtils}, in
 * SystemUI): a window that can take any size fills the screen less its bars; one that cannot
 * keeps its shape, as large as fits, centred. Its title bar is not part of that shape. Our menu's
 * Maximize did the first to every window, and stretched the ones that keep their shape.
 *
 * <p>Rectangles are {@code {left, top, right, bottom}}: there is no {@code android.graphics}
 * here, so this runs, and is tested, on a plain JDK.
 */
public final class WindowMath {

    /** {@code ActivityInfo}'s orientations that stand upright for good. */
    private static final int PORTRAIT = 1;
    private static final int SENSOR_PORTRAIT = 7;
    private static final int REVERSE_PORTRAIT = 9;
    private static final int USER_PORTRAIT = 12;

    private WindowMath() {
    }

    /**
     * ZUI's maximised bounds ({@code calculateMaximizeBounds}).
     *
     * @param area      where a maximised window may go: the screen less its bars
     * @param resizable whether the app takes any size
     * @param aspect    the app's long side over its short side, see {@link #aspect}
     * @param portrait  whether the app stands upright
     * @param caption   how much of the window's height is its title bar, above the app
     */
    public static int[] maximized(int[] area, boolean resizable, float aspect, boolean portrait,
            int caption) {
        if (resizable) {
            return area.clone();
        }
        int width = area[2] - area[0];
        int height = area[3] - area[1];
        int[] size = fit(width, height, aspect, portrait, caption);
        int left = area[0] + (width - size[0]) / 2;
        int top = area[1] + (height - size[1]) / 2;
        return new int[]{left, top, left + size[0], top + size[1]};
    }

    /**
     * The largest window of the app's shape in {@code width} by {@code height}, title bar
     * included ({@code maximizeSizeGivenAspectRatio}): as tall as fits, unless that is too wide.
     */
    static int[] fit(int width, int height, float aspect, boolean portrait, int caption) {
        int appHeight = height - caption;
        int outWidth;
        int outHeight;
        int tall = (int) Math.ceil(portrait ? appHeight / aspect : appHeight * aspect);
        if (tall <= width) {
            outWidth = tall;
            outHeight = appHeight;
        } else {
            outWidth = width;
            outHeight = (int) Math.ceil(portrait ? width * aspect : width / aspect);
        }
        return new int[]{outWidth, outHeight + caption};
    }

    /** Long side over short side, 1 or more; 1 for a size with nothing in it. */
    public static float aspect(int width, int height) {
        int shorter = Math.min(width, height);
        return shorter <= 0 ? 1f : (float) Math.max(width, height) / shorter;
    }

    /**
     * ZUI's test of a maximised window ({@code isTaskMaximized}): exactly the area for one that
     * takes any size; for one that keeps its shape, the area's full width or full height.
     */
    public static boolean isMaximized(int[] bounds, int[] area, boolean resizable) {
        if (resizable) {
            return bounds[0] == area[0] && bounds[1] == area[1] && bounds[2] == area[2]
                    && bounds[3] == area[3];
        }
        return bounds[2] - bounds[0] == area[2] - area[0]
                || bounds[3] - bounds[1] == area[3] - area[1];
    }

    /**
     * Where ZUI restores a window whose size before it never saw
     * ({@code calculateDefaultDesktopTaskBounds}): {@code scale} of the screen each way, centred.
     */
    public static int[] restoredDefault(int screenWidth, int screenHeight, float scale) {
        int width = (int) (screenWidth * scale);
        int height = (int) (screenHeight * scale);
        int left = (screenWidth - width) / 2;
        int top = (screenHeight - height) / 2;
        return new int[]{left, top, left + width, top + height};
    }

    /** {@code ActivityInfo.isFixedOrientationPortrait}: an orientation that stands upright. */
    public static boolean fixedPortrait(int orientation) {
        return orientation == PORTRAIT || orientation == SENSOR_PORTRAIT
                || orientation == REVERSE_PORTRAIT || orientation == USER_PORTRAIT;
    }
}
