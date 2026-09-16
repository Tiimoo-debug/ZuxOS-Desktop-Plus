package com.zuxos.desktopplus.desktop;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RenderEffect;
import android.view.View;
import android.widget.FrameLayout;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.Glass;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.LiquidGlass;
import com.zuxos.desktopplus.core.Ui;

import java.lang.reflect.Method;

/**
 * A panel whose background is the view behind it, bent through the liquid-glass lens.
 *
 * <p>The backdrop is captured into a bitmap when the panel opens rather than re-sampled every
 * frame: what is behind these panels does not move while they are open, so a per-frame capture
 * would burn a lot of GPU for an identical picture.
 *
 * <p>The wallpaper is not part of any view tree, so it cannot be captured and is not refracted -
 * the panel stays translucent where nothing was captured and the wallpaper shows through as
 * itself. Windows the module owns additionally ask the system for real blur behind them.
 */
public class GlassPanel extends FrameLayout {

    /** Quarter resolution: the capture is blurred before it is ever seen. */
    private static final float CAPTURE_SCALE = 0.25f;

    private final BackdropView mBackdrop;
    private final float mRadiusPx;
    private final int mTint;

    private View mSource;
    private int mEffectWidth;
    private int mEffectHeight;

    public GlassPanel(Context ctx, float radiusPx, int tint) {
        super(ctx);
        mRadiusPx = radiusPx;
        mTint = tint;
        mBackdrop = new BackdropView(ctx);
        addView(mBackdrop, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        if (!LiquidGlass.isSupported()) {
            // No AGSL: the panel keeps the layered-translucency look.
            setBackground(Glass.panel(ctx, (int) radiusPx));
        }
    }

    /** The view to capture from - usually the activity's content root. */
    public void setSource(View source) {
        mSource = source;
    }

    /** Captures what is behind the panel and points the lens at it. */
    public void refresh() {
        if (!LiquidGlass.isSupported() || getWidth() <= 0 || getHeight() <= 0) {
            return;
        }
        applyEffect();
        mBackdrop.capture();
    }

    private void applyEffect() {
        if (mEffectWidth == getWidth() && mEffectHeight == getHeight()) {
            return;
        }
        RenderEffect effect = LiquidGlass.lens(getWidth(), getHeight(), mRadiusPx,
                Ui.dp(getContext(), 18), mTint);
        if (effect == null) {
            effect = LiquidGlass.blurOnly(Ui.dp(getContext(), 18));
            if (effect == null) {
                setBackground(Glass.panel(getContext(), (int) mRadiusPx));
                return;
            }
        }
        try {
            mBackdrop.setRenderEffect(effect);
            mEffectWidth = getWidth();
            mEffectHeight = getHeight();
        } catch (Throwable t) {
            L.e("could not apply the glass effect", t);
            setBackground(Glass.panel(getContext(), (int) mRadiusPx));
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w != oldw || h != oldh) {
            mEffectWidth = 0;
            post(this::refresh);
        }
    }

    /** Draws the captured backdrop; the lens effect is attached to this view alone. */
    private final class BackdropView extends View {

        private final Paint mPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        private Bitmap mCapture;

        BackdropView(Context ctx) {
            super(ctx);
        }

        void capture() {
            View source = mSource;
            if (source == null || source.getWidth() == 0) {
                return;
            }
            int width = Math.max(1, (int) (GlassPanel.this.getWidth() * CAPTURE_SCALE));
            int height = Math.max(1, (int) (GlassPanel.this.getHeight() * CAPTURE_SCALE));
            Bitmap bitmap;
            try {
                bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            } catch (Throwable t) {
                L.d("backdrop capture skipped: " + t);
                return;
            }

            int[] mine = new int[2];
            int[] src = new int[2];
            GlassPanel.this.getLocationOnScreen(mine);
            source.getLocationOnScreen(src);

            Canvas canvas = new Canvas(bitmap);
            canvas.scale(CAPTURE_SCALE, CAPTURE_SCALE);
            canvas.translate(src[0] - mine[0], src[1] - mine[1]);

            // Hide the panel for the duration, or it would capture itself.
            int previous = GlassPanel.this.getVisibility();
            setPanelVisibility(INVISIBLE);
            try {
                source.draw(canvas);
            } catch (Throwable t) {
                L.d("backdrop capture failed: " + t);
                bitmap.recycle();
                return;
            } finally {
                setPanelVisibility(previous);
            }

            Bitmap old = mCapture;
            mCapture = bitmap;
            if (old != null) {
                old.recycle();
            }
            invalidate();
        }

        /**
         * Uses the framework's transition visibility where it is reachable: unlike
         * {@code setVisibility} it does not invalidate, so hiding and re-showing the panel inside
         * one capture cannot cause a redraw storm.
         */
        private void setPanelVisibility(int value) {
            try {
                Method m = View.class.getMethod("setTransitionVisibility", int.class);
                m.invoke(GlassPanel.this, value);
            } catch (Throwable t) {
                GlassPanel.this.setVisibility(value);
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            Bitmap capture = mCapture;
            if (capture == null || capture.isRecycled()) {
                return;
            }
            canvas.drawBitmap(capture, new Rect(0, 0, capture.getWidth(), capture.getHeight()),
                    new Rect(0, 0, getWidth(), getHeight()), mPaint);
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            if (mCapture != null) {
                mCapture.recycle();
                mCapture = null;
            }
        }
    }

    /** True when the panel is drawing real glass rather than the fallback. */
    public boolean isLiquid() {
        return LiquidGlass.isSupported() && Cfg.glass();
    }
}
