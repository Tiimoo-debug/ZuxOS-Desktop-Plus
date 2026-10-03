package com.zuxos.desktopplus.core;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import java.util.Locale;

/**
 * Small line icons for the menus.
 *
 * <p>Drawn rather than shipped: the module has no resources of its own in the launcher's process
 * (it runs inside someone else's APK), and a handful of strokes on a 24-unit grid is all a menu
 * row needs. They take the colour of the text beside them, so they follow light and dark the way
 * the text already does.
 */
public final class Glyphs {

    public static final int OPEN = 1;
    public static final int CLOSE = 2;
    public static final int INFO = 3;
    public static final int PIN = 4;
    public static final int UNPIN = 5;
    public static final int FOLDER = 6;
    public static final int RENAME = 7;
    public static final int REMOVE = 8;
    public static final int HIDE = 9;
    public static final int SETTINGS = 10;
    public static final int ADD = 11;
    public static final int WIDGET = 12;
    public static final int SELECT = 13;
    public static final int APPS = 14;
    public static final int SORT = 15;
    public static final int EXPORT = 16;
    public static final int SHORTCUT = 17;
    public static final int RESIZE = 18;
    public static final int WALLPAPER = 19;

    private Glyphs() {
    }

    /** Whether this is one of ours, which takes the colour of its row rather than its own. */
    public static boolean isGlyph(Drawable drawable) {
        return drawable instanceof GlyphDrawable;
    }

    public static Drawable of(int glyph) {
        Path path = pathFor(glyph);
        return path == null ? null : new GlyphDrawable(path);
    }

    /**
     * A glyph for a menu entry, from its title.
     *
     * <p>The desktop builds dozens of entries in dozens of places; matching on the words they
     * already carry gives every one of them an icon without threading one through each call.
     * An entry nothing matches simply has none.
     */
    public static Drawable forTitle(String title) {
        if (title == null) {
            return null;
        }
        String t = title.toLowerCase(Locale.ROOT);
        int glyph;
        if (t.startsWith("unpin")) {
            glyph = UNPIN;
        } else if (t.startsWith("pin") || t.contains("to the taskbar")) {
            glyph = PIN;
        } else if (t.startsWith("close") || t.startsWith("force stop") || t.startsWith("stop")) {
            glyph = CLOSE;
        } else if (t.startsWith("rename")) {
            glyph = RENAME;
        } else if (t.startsWith("remove") || t.startsWith("delete") || t.startsWith("uninstall")
                || t.startsWith("unpack") || t.startsWith("break")) {
            glyph = REMOVE;
        } else if (t.startsWith("hide") || t.startsWith("un-hide") || t.startsWith("unhide")
                || t.startsWith("show hidden")) {
            glyph = HIDE;
        } else if (t.startsWith("open") || t.startsWith("launch")) {
            glyph = OPEN;
        } else if (t.contains("folder")) {
            glyph = FOLDER;
        } else if (t.contains("info") || t.startsWith("about")) {
            glyph = INFO;
        } else if (t.startsWith("resize")) {
            glyph = RESIZE;
        } else if (t.contains("widget")) {
            glyph = WIDGET;
        } else if (t.contains("setting") || t.contains("size")) {
            glyph = SETTINGS;
        } else if (t.startsWith("select")) {
            glyph = SELECT;
        } else if (t.startsWith("add") || t.startsWith("new")) {
            glyph = ADD;
        } else if (t.contains("sort") || t.contains("tidy") || t.contains("order")
                || t.contains("a-z") || t.contains("a\u2013z")) {
            glyph = SORT;
        } else if (t.startsWith("export") || t.contains("download")) {
            glyph = EXPORT;
        } else if (t.contains("shortcut")) {
            glyph = SHORTCUT;
        } else if (t.contains("wallpaper")) {
            glyph = WALLPAPER;
        } else if (t.contains("apps") || t.contains("drawer")) {
            glyph = APPS;
        } else {
            return null;
        }
        return of(glyph);
    }

    /** Paths on a 24x24 grid, stroked. */
    private static Path pathFor(int glyph) {
        Path p = new Path();
        switch (glyph) {
            case OPEN:
                // A window with an arrow leaving its top-right corner.
                p.moveTo(11, 4);
                p.lineTo(5, 4);
                p.lineTo(5, 19);
                p.lineTo(20, 19);
                p.lineTo(20, 13);
                p.moveTo(14, 4);
                p.lineTo(20, 4);
                p.lineTo(20, 10);
                p.moveTo(20, 4);
                p.lineTo(11, 13);
                return p;
            case CLOSE:
                p.moveTo(6, 6);
                p.lineTo(18, 18);
                p.moveTo(18, 6);
                p.lineTo(6, 18);
                return p;
            case INFO:
                p.addCircle(12, 12, 8.5f, Path.Direction.CW);
                p.moveTo(12, 11);
                p.lineTo(12, 16.5f);
                p.moveTo(12, 7.6f);
                p.lineTo(12, 8.2f);
                return p;
            case PIN:
            case UNPIN:
                // A pushpin: head, body, point.
                p.moveTo(9, 4);
                p.lineTo(15, 4);
                p.moveTo(10, 4);
                p.lineTo(10, 10);
                p.lineTo(7, 14);
                p.lineTo(17, 14);
                p.lineTo(14, 10);
                p.lineTo(14, 4);
                p.moveTo(12, 14);
                p.lineTo(12, 20);
                if (glyph == UNPIN) {
                    p.moveTo(4, 4);
                    p.lineTo(20, 20);
                }
                return p;
            case FOLDER:
                p.moveTo(3.5f, 7);
                p.lineTo(3.5f, 18.5f);
                p.lineTo(20.5f, 18.5f);
                p.lineTo(20.5f, 8.5f);
                p.lineTo(11.5f, 8.5f);
                p.lineTo(9.5f, 5.5f);
                p.lineTo(3.5f, 5.5f);
                p.close();
                return p;
            case RENAME:
                // A pencil.
                p.moveTo(5, 19);
                p.lineTo(5.5f, 15.5f);
                p.lineTo(15.5f, 5.5f);
                p.lineTo(18.5f, 8.5f);
                p.lineTo(8.5f, 18.5f);
                p.close();
                p.moveTo(13.5f, 7.5f);
                p.lineTo(16.5f, 10.5f);
                return p;
            case REMOVE:
                // A bin.
                p.moveTo(4.5f, 7);
                p.lineTo(19.5f, 7);
                p.moveTo(9.5f, 7);
                p.lineTo(10, 4.5f);
                p.lineTo(14, 4.5f);
                p.lineTo(14.5f, 7);
                p.moveTo(6.5f, 7);
                p.lineTo(7.5f, 19.5f);
                p.lineTo(16.5f, 19.5f);
                p.lineTo(17.5f, 7);
                p.moveTo(10.5f, 10.5f);
                p.lineTo(10.5f, 16);
                p.moveTo(13.5f, 10.5f);
                p.lineTo(13.5f, 16);
                return p;
            case HIDE:
                // An eye, struck through.
                p.moveTo(3, 12);
                p.cubicTo(6, 6.5f, 18, 6.5f, 21, 12);
                p.cubicTo(18, 17.5f, 6, 17.5f, 3, 12);
                p.addCircle(12, 12, 2.6f, Path.Direction.CW);
                p.moveTo(4.5f, 4.5f);
                p.lineTo(19.5f, 19.5f);
                return p;
            case SETTINGS:
                // Three sliders.
                p.moveTo(4, 7);
                p.lineTo(20, 7);
                p.moveTo(4, 12);
                p.lineTo(20, 12);
                p.moveTo(4, 17);
                p.lineTo(20, 17);
                p.addCircle(9, 7, 1.8f, Path.Direction.CW);
                p.addCircle(15, 12, 1.8f, Path.Direction.CW);
                p.addCircle(8, 17, 1.8f, Path.Direction.CW);
                return p;
            case ADD:
                p.moveTo(12, 5);
                p.lineTo(12, 19);
                p.moveTo(5, 12);
                p.lineTo(19, 12);
                return p;
            case WIDGET:
                p.addRoundRect(new RectF(4, 4, 11, 11), 1.5f, 1.5f, Path.Direction.CW);
                p.addRoundRect(new RectF(13, 4, 20, 11), 1.5f, 1.5f, Path.Direction.CW);
                p.addRoundRect(new RectF(4, 13, 11, 20), 1.5f, 1.5f, Path.Direction.CW);
                p.addRoundRect(new RectF(13, 13, 20, 20), 1.5f, 1.5f, Path.Direction.CW);
                return p;
            case SELECT:
                p.addRoundRect(new RectF(4, 4, 20, 20), 3, 3, Path.Direction.CW);
                p.moveTo(8, 12.5f);
                p.lineTo(11, 15.5f);
                p.lineTo(16.5f, 9);
                return p;
            case APPS:
                for (int row = 0; row < 3; row++) {
                    for (int col = 0; col < 3; col++) {
                        p.addCircle(6 + col * 6, 6 + row * 6, 1.2f, Path.Direction.CW);
                    }
                }
                return p;
            case SORT:
                p.moveTo(4, 7);
                p.lineTo(20, 7);
                p.moveTo(4, 12);
                p.lineTo(15, 12);
                p.moveTo(4, 17);
                p.lineTo(10, 17);
                return p;
            case EXPORT:
                p.moveTo(12, 4);
                p.lineTo(12, 15);
                p.moveTo(7.5f, 10.5f);
                p.lineTo(12, 15);
                p.lineTo(16.5f, 10.5f);
                p.moveTo(5, 19.5f);
                p.lineTo(19, 19.5f);
                return p;
            case SHORTCUT:
                // An arrow curving out to the right.
                p.moveTo(5, 18);
                p.cubicTo(5, 11, 9, 8.5f, 17, 8.5f);
                p.moveTo(13.5f, 5);
                p.lineTo(17, 8.5f);
                p.lineTo(13.5f, 12);
                return p;
            case RESIZE:
                p.moveTo(13, 5);
                p.lineTo(19, 5);
                p.lineTo(19, 11);
                p.moveTo(11, 19);
                p.lineTo(5, 19);
                p.lineTo(5, 13);
                p.moveTo(19, 5);
                p.lineTo(5, 19);
                return p;
            case WALLPAPER:
                p.addRoundRect(new RectF(4, 5, 20, 19), 2, 2, Path.Direction.CW);
                p.moveTo(4.5f, 16.5f);
                p.lineTo(9.5f, 11.5f);
                p.lineTo(13, 15);
                p.lineTo(15, 13);
                p.lineTo(19.5f, 17.5f);
                p.addCircle(15.5f, 9, 1.4f, Path.Direction.CW);
                return p;
            default:
                return null;
        }
    }

    /** A 24-unit path, scaled to the bounds and stroked in the current colour. */
    private static final class GlyphDrawable extends Drawable {

        private final Path mSource;
        private final Path mScaled = new Path();
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        GlyphDrawable(Path source) {
            mSource = source;
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeCap(Paint.Cap.ROUND);
            mPaint.setStrokeJoin(Paint.Join.ROUND);
            mPaint.setColor(0xFFFFFFFF);
        }

        @Override
        protected void onBoundsChange(android.graphics.Rect bounds) {
            float scale = Math.min(bounds.width(), bounds.height()) / 24f;
            Matrix matrix = new Matrix();
            matrix.setScale(scale, scale);
            matrix.postTranslate(bounds.left, bounds.top);
            mSource.transform(matrix, mScaled);
            mPaint.setStrokeWidth(Math.max(1f, 1.7f * scale));
        }

        @Override
        public void draw(Canvas canvas) {
            canvas.drawPath(mScaled, mPaint);
        }

        @Override
        public void setTint(int tint) {
            mPaint.setColor(tint);
            invalidateSelf();
        }

        @Override
        public void setAlpha(int alpha) {
            mPaint.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(ColorFilter filter) {
            mPaint.setColorFilter(filter);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        @Override
        public int getIntrinsicWidth() {
            return -1;
        }

        @Override
        public int getIntrinsicHeight() {
            return -1;
        }
    }
}
