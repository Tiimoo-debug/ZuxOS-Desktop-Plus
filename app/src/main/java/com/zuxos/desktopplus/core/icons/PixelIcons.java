package com.zuxos.desktopplus.core.icons;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;

/**
 * Retro's icons: pixel art on a small grid, drawn square by square with no anti-aliasing, at the
 * largest whole cell that fits. Each is a picture in characters - {@code #} the ink, {@code o}
 * white, {@code .} nothing - so what one looks like can be read off the source.
 */
public final class PixelIcons {

    /** The Android robot's head, as on the start button: green, with white eyes. */
    private static final String[] ROBOT = {
            "...#........#...",
            "....#......#....",
            "....########....",
            "..############..",
            ".###oo####oo###.",
            ".###oo####oo###.",
            "################",
            "################",
    };
    /** The same, eyes shut: a blink. */
    private static final String[] ROBOT_BLINK = {
            "...#........#...",
            "....#......#....",
            "....########....",
            "..############..",
            ".##############.",
            ".###oo####oo###.",
            "################",
            "################",
    };
    private static final String[] BACK = {
            "......#",
            "....###",
            "..#####",
            "#######",
            "#######",
            "..#####",
            "....###",
            "......#",
    };
    private static final String[] HOME = {
            "..####..",
            ".#....#.",
            "#......#",
            "#......#",
            "#......#",
            "#......#",
            ".#....#.",
            "..####..",
    };
    /** Windows 98's close: the X on a window's title bar. */
    private static final String[] CLOSE = {
            "##....##",
            ".##..##.",
            "..####..",
            "...##...",
            "..####..",
            ".##..##.",
            "##....##",
    };
    private static final String[] RECENTS = {
            "########",
            "#......#",
            "#......#",
            "#......#",
            "#......#",
            "#......#",
            "#......#",
            "########",
    };

    /** The robot's green. */
    private static final int GREEN = 0xFF3DDC84;
    /** Between blinks, at random within this, as the glass robot does. */
    private static final long BLINK_GAP_MIN_MS = 3500L;
    private static final long BLINK_GAP_SPAN_MS = 3000L;
    private static final long BLINK_MS = 150L;

    private PixelIcons() {
    }

    public static Drawable back(int color) {
        return new Grid(BACK, color);
    }

    public static Drawable home(int color) {
        return new Grid(HOME, color);
    }

    public static Drawable recents(int color) {
        return new Grid(RECENTS, color);
    }

    public static Drawable close(int color) {
        return new Grid(CLOSE, color);
    }

    /**
     * The robot for the start button. It blinks now and then: one redraw with its eyes shut and
     * one to open them, scheduled ahead - nothing ticks between blinks.
     */
    public static Drawable robot() {
        return new Robot();
    }

    /** A picture in characters, scaled by whole cells and centred in its bounds. */
    private static class Grid extends Drawable {
        private final Paint mPaint = new Paint();
        private final int mInk;
        String[] mRows;

        Grid(String[] rows, int ink) {
            mRows = rows;
            mInk = ink;
            mPaint.setAntiAlias(false);
            mPaint.setStyle(Paint.Style.FILL);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            int cols = mRows[0].length();
            int cell = Math.max(1, Math.min(b.width() / cols, b.height() / mRows.length));
            int left = b.left + (b.width() - cell * cols) / 2;
            int top = b.top + (b.height() - cell * mRows.length) / 2;
            for (int y = 0; y < mRows.length; y++) {
                String row = mRows[y];
                for (int x = 0; x < cols; x++) {
                    char c = row.charAt(x);
                    if (c == '.') {
                        continue;
                    }
                    mPaint.setColor(c == 'o' ? 0xFFFFFFFF : mInk);
                    canvas.drawRect(left + x * cell, top + y * cell, left + (x + 1) * cell,
                            top + (y + 1) * cell, mPaint);
                }
            }
        }

        @Override
        public void setAlpha(int alpha) {
            mPaint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            // The ink is the theme's; a view's tint would blur the pixel colours together.
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    private static final class Robot extends Grid {
        private final Runnable mBlink = this::blink;
        private final Runnable mOpen = this::open;

        Robot() {
            super(ROBOT, GREEN);
        }

        @Override
        public boolean setVisible(boolean visible, boolean restart) {
            boolean changed = super.setVisible(visible, restart);
            unscheduleSelf(mBlink);
            unscheduleSelf(mOpen);
            if (visible) {
                next();
            } else {
                open();
            }
            return changed;
        }

        private void next() {
            long gap = BLINK_GAP_MIN_MS + (long) (Math.random() * BLINK_GAP_SPAN_MS);
            scheduleSelf(mBlink, SystemClock.uptimeMillis() + gap);
        }

        private void blink() {
            mRows = ROBOT_BLINK;
            invalidateSelf();
            scheduleSelf(mOpen, SystemClock.uptimeMillis() + BLINK_MS);
        }

        private void open() {
            if (mRows != ROBOT) {
                mRows = ROBOT;
                invalidateSelf();
            }
            if (isVisible() && getCallback() != null) {
                next();
            }
        }
    }
}
