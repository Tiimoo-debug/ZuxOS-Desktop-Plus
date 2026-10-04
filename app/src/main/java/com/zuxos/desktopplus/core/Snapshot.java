package com.zuxos.desktopplus.core;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorSpace;
import android.graphics.HardwareRenderer;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.graphics.RecordingCanvas;
import android.graphics.RenderNode;
import android.graphics.drawable.Drawable;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageReader;
import android.view.View;
import android.view.ViewGroup;

import java.util.List;

/**
 * A picture of views, taken through the GPU.
 *
 * <p>The glass panels used to draw what is behind them into a software bitmap. That fails the
 * moment the views contain anything that only exists on the GPU - a pane with a real blur, a
 * widget with a hardware layer - with {@code Software rendering doesn't support drawRenderNode}.
 * Here the views are recorded into a {@link RenderNode} instead, which takes those as they are,
 * and that node is rendered by a renderer of our own into an image we can keep.
 *
 * <p>One view can be left out of the picture: the panel asking for it. It is usually inside the
 * very tree being captured, and a pane of glass that shows a picture of itself shows nothing.
 */
public final class Snapshot {

    private Snapshot() {
    }

    /**
     * @param sources  drawn in order, each where it is on screen
     * @param exclude  left out, with everything inside it; may be null
     * @param origin   the screen point that becomes the picture's top-left corner
     * @param width    picture size, after {@code scale}
     * @param scale    how much smaller than the screen the picture is
     * @return the picture, or null if this device would not render it
     */
    public static Bitmap capture(List<View> sources, View exclude, int[] origin, int width,
            int height, float scale) {
        RenderNode node = new RenderNode("zux-desktop-plus-snapshot");
        ImageReader reader = null;
        HardwareRenderer renderer = null;
        Image image = null;
        try {
            node.setPosition(0, 0, width, height);
            RecordingCanvas canvas = node.beginRecording(width, height);
            try {
                canvas.scale(scale, scale);
                int[] at = new int[2];
                for (View source : sources) {
                    if (source.getWidth() == 0 || source.getHeight() == 0
                            || source.getVisibility() != View.VISIBLE) {
                        continue;
                    }
                    source.getLocationOnScreen(at);
                    int saved = canvas.save();
                    canvas.translate(at[0] - origin[0], at[1] - origin[1]);
                    boolean reached = drawWithout(canvas, source, exclude);
                    canvas.restoreToCount(saved);
                    if (reached) {
                        break;
                    }
                }
            } finally {
                node.endRecording();
            }

            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 1,
                    HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE
                            | HardwareBuffer.USAGE_GPU_COLOR_OUTPUT);
            renderer = new HardwareRenderer();
            renderer.setSurface(reader.getSurface());
            renderer.setContentRoot(node);
            renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw();
            image = reader.acquireNextImage();
            if (image == null) {
                return null;
            }
            HardwareBuffer buffer = image.getHardwareBuffer();
            if (buffer == null) {
                return null;
            }
            Bitmap gpu = Bitmap.wrapHardwareBuffer(buffer, ColorSpace.get(ColorSpace.Named.SRGB));
            buffer.close();
            if (gpu == null) {
                return null;
            }
            // A copy we own outright, so nothing here outlives the reader it came from.
            Bitmap copy = gpu.copy(Bitmap.Config.ARGB_8888, false);
            gpu.recycle();
            return copy;
        } finally {
            if (image != null) {
                image.close();
            }
            if (renderer != null) {
                renderer.destroy();
            }
            if (reader != null) {
                reader.close();
            }
            node.discardDisplayList();
        }
    }

    /**
     * Draws a view, leaving one descendant out - and everything drawn after it.
     *
     * <p>A view that does not contain the excluded one draws itself whole. One that does is
     * opened up: its background, then each child in place - carrying the child's own offset and
     * transform, which the parent would normally apply - recursing only down the branch that
     * leads to the excluded view.
     *
     * <p>Only what is behind the excluded view belongs in its backdrop. Anything drawn after it
     * is in front of it: a list of icons over a sheet of glass would otherwise show up a second
     * time, blurred, inside the glass.
     *
     * @return true once the excluded view has been reached, so the caller stops there too
     */
    private static boolean drawWithout(Canvas canvas, View view, View exclude) {
        if (view == exclude) {
            return true;
        }
        if (exclude == null || !(view instanceof ViewGroup) || !contains(view, exclude)) {
            view.draw(canvas);
            return false;
        }
        Drawable background = view.getBackground();
        if (background != null) {
            background.draw(canvas);
        }
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE || child.getAlpha() <= 0f) {
                continue;
            }
            int saved = canvas.save();
            canvas.translate(child.getLeft() - group.getScrollX(),
                    child.getTop() - group.getScrollY());
            Matrix matrix = child.getMatrix();
            if (!matrix.isIdentity()) {
                canvas.concat(matrix);
            }
            boolean reached = drawWithout(canvas, child, exclude);
            canvas.restoreToCount(saved);
            if (reached) {
                return true;
            }
        }
        return true;
    }

    private static boolean contains(View ancestor, View view) {
        for (View v = view; v != null; ) {
            if (v == ancestor) {
                return true;
            }
            v = v.getParent() instanceof View ? (View) v.getParent() : null;
        }
        return false;
    }
}
