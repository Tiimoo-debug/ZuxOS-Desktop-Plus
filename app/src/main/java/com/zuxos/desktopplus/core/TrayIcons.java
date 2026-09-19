package com.zuxos.desktopplus.core;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * Status icons, drawn rather than loaded.
 *
 * <p>The module's own resources are not on the launcher's resource path, so anything the tray
 * shows inside the launcher process has to be built in code. These are small enough that drawing
 * them by hand costs less than shipping bitmaps would.
 *
 * <p>All of them draw into a 24x24 box scaled to the bounds, so a caller only sets a size.
 */
public final class TrayIcons {

    /** The box every icon is authored in. */
    private static final float BOX = 24f;

    private TrayIcons() {
    }

    /** Wi-Fi arcs; {@code level} is 0..4, and 0 draws the "connected to nothing" dot alone. */
    public static Drawable wifi(int level, int color) {
        return new BoxIcon(color) {
            @Override
            void drawBox(Canvas canvas, Paint fill, Paint stroke) {
                int bars = Math.max(0, Math.min(4, level));
                float cx = BOX / 2f;
                float cy = 18.5f;
                // Three arcs plus the dot: the dot is always lit, arcs light up with the level.
                for (int i = 0; i < 3; i++) {
                    float r = 5.5f + i * 4.2f;
                    stroke.setAlpha(i < bars - 1 ? 255 : 70);
                    canvas.drawArc(new RectF(cx - r, cy - r, cx + r, cy + r),
                            -135f, 90f, false, stroke);
                }
                stroke.setAlpha(255);
                fill.setAlpha(bars > 0 ? 255 : 70);
                canvas.drawCircle(cx, cy - 0.5f, 1.9f, fill);
                fill.setAlpha(255);
            }
        };
    }

    /** Wi-Fi with a slash through it. */
    public static Drawable wifiOff(int color) {
        return new BoxIcon(color) {
            @Override
            void drawBox(Canvas canvas, Paint fill, Paint stroke) {
                float cx = BOX / 2f;
                float cy = 18.5f;
                stroke.setAlpha(70);
                for (int i = 0; i < 3; i++) {
                    float r = 5.5f + i * 4.2f;
                    canvas.drawArc(new RectF(cx - r, cy - r, cx + r, cy + r),
                            -135f, 90f, false, stroke);
                }
                fill.setAlpha(70);
                canvas.drawCircle(cx, cy - 0.5f, 1.9f, fill);
                stroke.setAlpha(255);
                fill.setAlpha(255);
                canvas.drawLine(4.5f, 4.5f, 19.5f, 19.5f, stroke);
            }
        };
    }

    /** A network port with a cable running down into it. */
    public static Drawable ethernet(int color) {
        return new BoxIcon(color) {
            @Override
            void drawBox(Canvas canvas, Paint fill, Paint stroke) {
                canvas.drawRoundRect(new RectF(4f, 11f, 20f, 20f), 2f, 2f, stroke);
                // The contacts, which are what make a bare rectangle read as a socket.
                for (int i = 0; i < 4; i++) {
                    float x = 7f + i * 3.3f;
                    canvas.drawLine(x, 13.5f, x, 16.5f, stroke);
                }
                canvas.drawLine(BOX / 2f, 4f, BOX / 2f, 11f, stroke);
                canvas.drawRoundRect(new RectF(9.5f, 3f, 14.5f, 6f), 1f, 1f, fill);
            }
        };
    }

    /** Mobile data: the usual staircase of bars. */
    public static Drawable cellular(int level, int color) {
        return new BoxIcon(color) {
            @Override
            void drawBox(Canvas canvas, Paint fill, Paint stroke) {
                int bars = Math.max(0, Math.min(4, level));
                for (int i = 0; i < 4; i++) {
                    float h = 4f + i * 4f;
                    float x = 3.5f + i * 5f;
                    fill.setAlpha(i < bars ? 255 : 70);
                    canvas.drawRoundRect(new RectF(x, 20f - h, x + 3.4f, 20f), 1f, 1f, fill);
                }
                fill.setAlpha(255);
            }
        };
    }

    /** Battery body with a fill proportional to {@code percent}, and a bolt while charging. */
    public static Drawable battery(int percent, boolean charging, int color) {
        return new BoxIcon(color) {
            @Override
            void drawBox(Canvas canvas, Paint fill, Paint stroke) {
                RectF body = new RectF(2.5f, 7f, 19f, 17f);
                canvas.drawRoundRect(body, 2.5f, 2.5f, stroke);
                canvas.drawRoundRect(new RectF(20f, 10f, 22f, 14f), 1f, 1f, fill);

                float pad = 1.8f;
                float inner = body.width() - pad * 2;
                float level = Math.max(0f, Math.min(100, percent)) / 100f;
                if (level > 0f) {
                    canvas.drawRoundRect(new RectF(body.left + pad, body.top + pad,
                                    body.left + pad + inner * level, body.bottom - pad),
                            1.2f, 1.2f, fill);
                }
                if (charging) {
                    // Punched out of the fill so it stays readable at any level.
                    Path bolt = new Path();
                    bolt.moveTo(12.6f, 7.6f);
                    bolt.lineTo(8.4f, 12.6f);
                    bolt.lineTo(11.2f, 12.6f);
                    bolt.lineTo(10.4f, 16.4f);
                    bolt.lineTo(14.6f, 11.2f);
                    bolt.lineTo(11.8f, 11.2f);
                    bolt.close();
                    Paint cut = new Paint(Paint.ANTI_ALIAS_FLAG);
                    cut.setStyle(Paint.Style.STROKE);
                    cut.setStrokeWidth(1.4f);
                    cut.setColor(0xFF000000);
                    cut.setXfermode(new android.graphics.PorterDuffXfermode(
                            android.graphics.PorterDuff.Mode.CLEAR));
                    canvas.drawPath(bolt, cut);
                    canvas.drawPath(bolt, fill);
                }
            }
        };
    }

    /** The Bluetooth rune. */
    public static Drawable bluetooth(int color) {
        return new BoxIcon(color) {
            @Override
            void drawBox(Canvas canvas, Paint fill, Paint stroke) {
                Path p = new Path();
                p.moveTo(7f, 8f);
                p.lineTo(16.5f, 16f);
                p.lineTo(12f, 20f);
                p.lineTo(12f, 4f);
                p.lineTo(16.5f, 8f);
                p.lineTo(7f, 16f);
                canvas.drawPath(p, stroke);
            }
        };
    }

    /** A torch with a beam. */
    public static Drawable torch(int color) {
        return new BoxIcon(color) {
            @Override
            void drawBox(Canvas canvas, Paint fill, Paint stroke) {
                Path body = new Path();
                body.moveTo(8.5f, 4f);
                body.lineTo(15.5f, 4f);
                body.lineTo(14.5f, 9f);
                body.lineTo(14.5f, 20f);
                body.lineTo(9.5f, 20f);
                body.lineTo(9.5f, 9f);
                body.close();
                canvas.drawPath(body, stroke);
                canvas.drawLine(9.5f, 9f, 14.5f, 9f, stroke);
            }
        };
    }

    /** The auto-rotate glyph: a phone with an arc turning around it. */
    public static Drawable rotate(int color) {
        return new BoxIcon(color) {
            @Override
            void drawBox(Canvas canvas, Paint fill, Paint stroke) {
                canvas.drawRoundRect(new RectF(8.5f, 6f, 15.5f, 18f), 1.8f, 1.8f, stroke);
                canvas.drawArc(new RectF(3.5f, 3.5f, 20.5f, 20.5f), 120f, 80f, false, stroke);
                Path tip = new Path();
                tip.moveTo(5.2f, 15.6f);
                tip.lineTo(4.4f, 19.2f);
                tip.lineTo(8f, 18.4f);
                tip.close();
                canvas.drawPath(tip, fill);
            }
        };
    }

    /**
     * The aeroplane, as AOSP draws it: a symmetrical airliner seen from above, nose up.
     *
     * <p>The first version of this was a paper plane - the "send" glyph - which is a different
     * symbol altogether and read as one.
     */
    public static Drawable flight(int color) {
        return new BoxIcon(color) {
            @Override
            void drawBox(Canvas canvas, Paint fill, Paint stroke) {
                Path p = new Path();
                // Nose, then out along the leading edge of the starboard wing and back in.
                p.moveTo(12f, 2.2f);
                p.cubicTo(12.9f, 2.2f, 13.5f, 3.1f, 13.5f, 4.4f);
                p.lineTo(13.5f, 9.2f);
                p.lineTo(21.5f, 14f);
                p.lineTo(21.5f, 16f);
                p.lineTo(13.5f, 13.6f);
                p.lineTo(13.5f, 18.4f);
                p.lineTo(16f, 20.2f);
                p.lineTo(16f, 21.6f);
                p.lineTo(12f, 20.6f);
                // Mirrored back down the port side.
                p.lineTo(8f, 21.6f);
                p.lineTo(8f, 20.2f);
                p.lineTo(10.5f, 18.4f);
                p.lineTo(10.5f, 13.6f);
                p.lineTo(2.5f, 16f);
                p.lineTo(2.5f, 14f);
                p.lineTo(10.5f, 9.2f);
                p.lineTo(10.5f, 4.4f);
                p.cubicTo(10.5f, 3.1f, 11.1f, 2.2f, 12f, 2.2f);
                p.close();
                canvas.drawPath(p, fill);
            }
        };
    }

    /** A camera shutter, for the screenshot button. */
    public static Drawable screenshot(int color) {
        return new BoxIcon(color) {
            @Override
            void drawBox(Canvas canvas, Paint fill, Paint stroke) {
                // A frame with the corners drawn and the sides left open, plus a lens.
                float in = 4f;
                float out = 20f;
                float arm = 4.5f;
                canvas.drawLine(in, in + arm, in, in, stroke);
                canvas.drawLine(in, in, in + arm, in, stroke);
                canvas.drawLine(out - arm, in, out, in, stroke);
                canvas.drawLine(out, in, out, in + arm, stroke);
                canvas.drawLine(out, out - arm, out, out, stroke);
                canvas.drawLine(out, out, out - arm, out, stroke);
                canvas.drawLine(in + arm, out, in, out, stroke);
                canvas.drawLine(in, out, in, out - arm, stroke);
                canvas.drawCircle(12f, 12f, 3f, stroke);
            }
        };
    }

    /** A chevron pointing up, for the button that opens the quick-settings panel. */
    public static Drawable panelChevron(int color) {
        return new BoxIcon(color) {
            @Override
            void drawBox(Canvas canvas, Paint fill, Paint stroke) {
                canvas.drawLine(5.5f, 14.5f, 12f, 8f, stroke);
                canvas.drawLine(12f, 8f, 18.5f, 14.5f, stroke);
            }
        };
    }

    /** Chevrons, for stepping between things - deliberately not the media skip glyphs. */
    public static Drawable chevronLeft(int color) {
        return new BoxIcon(color) {
            @Override
            void drawBox(Canvas canvas, Paint fill, Paint stroke) {
                canvas.drawLine(14.5f, 5.5f, 8f, 12f, stroke);
                canvas.drawLine(8f, 12f, 14.5f, 18.5f, stroke);
            }
        };
    }

    public static Drawable chevronRight(int color) {
        return new BoxIcon(color) {
            @Override
            void drawBox(Canvas canvas, Paint fill, Paint stroke) {
                canvas.drawLine(9.5f, 5.5f, 16f, 12f, stroke);
                canvas.drawLine(16f, 12f, 9.5f, 18.5f, stroke);
            }
        };
    }

    /** A cog. */
    public static Drawable gear(int color) {
        return new BoxIcon(color) {
            @Override
            void drawBox(Canvas canvas, Paint fill, Paint stroke) {
                float cx = 12f;
                float cy = 12f;
                canvas.drawCircle(cx, cy, 3.2f, stroke);
                // Eight teeth, each a short spoke from the rim outwards.
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI * i / 4.0;
                    float sin = (float) Math.sin(a);
                    float cos = (float) Math.cos(a);
                    canvas.drawLine(cx + cos * 5.6f, cy + sin * 5.6f,
                            cx + cos * 8.4f, cy + sin * 8.4f, stroke);
                }
                canvas.drawCircle(cx, cy, 5.6f, stroke);
            }
        };
    }

    /** A speaker, for the volume rows. */
    public static Drawable volume(int color) {
        return new BoxIcon(color) {
            @Override
            void drawBox(Canvas canvas, Paint fill, Paint stroke) {
                Path cone = new Path();
                cone.moveTo(4f, 9.5f);
                cone.lineTo(7.5f, 9.5f);
                cone.lineTo(12f, 5f);
                cone.lineTo(12f, 19f);
                cone.lineTo(7.5f, 14.5f);
                cone.lineTo(4f, 14.5f);
                cone.close();
                canvas.drawPath(cone, fill);
                canvas.drawArc(new RectF(10.5f, 8f, 17.5f, 16f), -60f, 120f, false, stroke);
                canvas.drawArc(new RectF(12f, 5.5f, 21f, 18.5f), -55f, 110f, false, stroke);
            }
        };
    }

    /** A cross, for dismissing a notification. */
    public static Drawable close(int color) {
        return new BoxIcon(color) {
            @Override
            void drawBox(Canvas canvas, Paint fill, Paint stroke) {
                canvas.drawLine(7f, 7f, 17f, 17f, stroke);
                canvas.drawLine(17f, 7f, 7f, 17f, stroke);
            }
        };
    }

    /** An eye, for the eye-protection toggle. */
    public static Drawable eye(int color) {
        return new BoxIcon(color) {
            @Override
            void drawBox(Canvas canvas, Paint fill, Paint stroke) {
                Path lens = new Path();
                lens.moveTo(2.6f, 12f);
                lens.quadTo(12f, 4.6f, 21.4f, 12f);
                lens.quadTo(12f, 19.4f, 2.6f, 12f);
                lens.close();
                canvas.drawPath(lens, stroke);
                canvas.drawCircle(12f, 12f, 2.7f, fill);
            }
        };
    }

    /** Play. */
    public static Drawable mediaPlay(int color) {
        return new BoxIcon(color) {
            @Override
            void drawBox(Canvas canvas, Paint fill, Paint stroke) {
                Path p = new Path();
                p.moveTo(7.5f, 4.5f);
                p.lineTo(19f, 12f);
                p.lineTo(7.5f, 19.5f);
                p.close();
                canvas.drawPath(p, fill);
            }
        };
    }

    /** Pause. */
    public static Drawable mediaPause(int color) {
        return new BoxIcon(color) {
            @Override
            void drawBox(Canvas canvas, Paint fill, Paint stroke) {
                canvas.drawRoundRect(new RectF(6.5f, 5f, 10.2f, 19f), 1.2f, 1.2f, fill);
                canvas.drawRoundRect(new RectF(13.8f, 5f, 17.5f, 19f), 1.2f, 1.2f, fill);
            }
        };
    }

    /** Skip to the next track. */
    public static Drawable mediaNext(int color) {
        return new BoxIcon(color) {
            @Override
            void drawBox(Canvas canvas, Paint fill, Paint stroke) {
                Path p = new Path();
                p.moveTo(6f, 5f);
                p.lineTo(15.5f, 12f);
                p.lineTo(6f, 19f);
                p.close();
                canvas.drawPath(p, fill);
                canvas.drawRoundRect(new RectF(16.5f, 5f, 19f, 19f), 1.2f, 1.2f, fill);
            }
        };
    }

    /** Back to the previous track. */
    public static Drawable mediaPrevious(int color) {
        return new BoxIcon(color) {
            @Override
            void drawBox(Canvas canvas, Paint fill, Paint stroke) {
                Path p = new Path();
                p.moveTo(18f, 5f);
                p.lineTo(8.5f, 12f);
                p.lineTo(18f, 19f);
                p.close();
                canvas.drawPath(p, fill);
                canvas.drawRoundRect(new RectF(5f, 5f, 7.5f, 19f), 1.2f, 1.2f, fill);
            }
        };
    }

    /** A bell, with a dot when something is waiting. */
    public static Drawable bell(boolean marked, int color) {
        return new BoxIcon(color) {
            @Override
            void drawBox(Canvas canvas, Paint fill, Paint stroke) {
                Path body = new Path();
                body.moveTo(5.5f, 16f);
                body.cubicTo(7.2f, 14.2f, 6.6f, 13f, 6.8f, 10.6f);
                body.cubicTo(7.1f, 6.9f, 9.3f, 5.2f, 12f, 5.2f);
                body.cubicTo(14.7f, 5.2f, 16.9f, 6.9f, 17.2f, 10.6f);
                body.cubicTo(17.4f, 13f, 16.8f, 14.2f, 18.5f, 16f);
                body.close();
                canvas.drawPath(body, stroke);
                canvas.drawLine(10f, 18.2f, 14f, 18.2f, stroke);
                canvas.drawCircle(12f, 4.2f, 1.1f, fill);
                if (marked) {
                    canvas.drawCircle(18.2f, 6.4f, 3.1f, fill);
                }
            }
        };
    }

    /** Scales a 24x24 drawing into whatever bounds it is given. */
    private abstract static class BoxIcon extends Drawable {

        private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mStroke = new Paint(Paint.ANTI_ALIAS_FLAG);

        BoxIcon(int color) {
            mFill.setStyle(Paint.Style.FILL);
            mFill.setColor(color);
            mStroke.setStyle(Paint.Style.STROKE);
            mStroke.setStrokeCap(Paint.Cap.ROUND);
            mStroke.setStrokeJoin(Paint.Join.ROUND);
            mStroke.setStrokeWidth(1.9f);
            mStroke.setColor(color);
        }

        abstract void drawBox(Canvas canvas, Paint fill, Paint stroke);

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            if (b.width() <= 0 || b.height() <= 0) {
                return;
            }
            int saved = canvas.saveLayer(b.left, b.top, b.right, b.bottom, null);
            canvas.translate(b.left, b.top);
            canvas.scale(b.width() / BOX, b.height() / BOX);
            try {
                drawBox(canvas, mFill, mStroke);
            } finally {
                canvas.restoreToCount(saved);
            }
        }

        @Override
        public void setAlpha(int alpha) {
            mFill.setAlpha(alpha);
            mStroke.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(ColorFilter filter) {
            mFill.setColorFilter(filter);
            mStroke.setColorFilter(filter);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        @Override
        public int getIntrinsicWidth() {
            return (int) BOX;
        }

        @Override
        public int getIntrinsicHeight() {
            return (int) BOX;
        }
    }
}
