package com.zuxos.desktopplus.core;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RenderEffect;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.FrameLayout;

/**
 * The glass behind a pane: two views over the same backdrop, frost below and lens above.
 *
 * <p>The backdrop is up to two pictures, drawn one over the other and scaled to the pane: what
 * is on screen behind the pane's window ({@link ScreenBackdrop}, live), and what is behind the
 * pane inside its own window ({@link Snapshot}, taken when the pane opens). Panes in a window of
 * their own only have the first; panes on the desktop have both - the wallpaper and apps from the
 * capture, the desktop's own icons and widgets from the snapshot.
 *
 * <p>Add it as the pane's first child, matching the pane. Everything else the pane holds draws
 * over it.
 */
public class GlassBackdrop extends FrameLayout {

    /** Tag the owners use to find it again. */
    public static final String TAG = "zux-glass-backdrop";

    private final LiquidGlass.Material mMaterial;
    private final float mRadius;
    private final float mExtend;
    private int mTintRgb;
    private final int mBase;
    private final Layer mFrost;
    private final Layer mLens;
    private int mEffectW;
    private int mEffectH;

    private Bitmap mScreen;
    private Bitmap mWindow;

    private ScreenBackdrop.Session mSession;
    private final float mScale;
    private final long mIntervalMs;
    private boolean mLive;
    private Runnable mOnRefused;

    /**
     * @param radiusPx   the pane's corner radius
     * @param extendPx   how far the shape runs past the bottom (a pane on the screen edge)
     * @param tintRgb    the glass's colour; {@link LiquidGlass#tintFor}
     * @param base       shown where neither picture has anything, ARGB; 0 for nothing
     * @param intervalMs fastest refresh of the live part; see {@link ScreenBackdrop.Session}
     */
    public GlassBackdrop(Context ctx, LiquidGlass.Material material, float radiusPx,
            float extendPx, int tintRgb, int base, long intervalMs) {
        super(ctx);
        setTag(TAG);
        mMaterial = material;
        mRadius = radiusPx;
        mExtend = extendPx;
        mTintRgb = tintRgb;
        mBase = base;
        mScale = 0.5f;
        mIntervalMs = intervalMs;
        mFrost = new Layer(ctx);
        mLens = new Layer(ctx);
        addView(mFrost, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        addView(mLens, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        setClickable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** Whether the device can do this at all: AGSL, the glass setting, and capture not refused. */
    public static boolean possible() {
        return LiquidGlass.isSupported() && !ScreenBackdrop.refused();
    }

    /** Keeps what is behind the pane's window coming in while the pane is on screen. */
    public void setLive(boolean live, Runnable onRefused) {
        mLive = live;
        mOnRefused = onRefused;
        if (isAttachedToWindow()) {
            updateSession();
        }
    }

    /** What is behind the pane inside its own window; null for nothing. Drawn over the screen. */
    public void setWindowLayer(Bitmap window) {
        Bitmap old = mWindow;
        mWindow = window;
        if (old != null && old != window) {
            old.recycle();
        }
        redraw();
    }

    public void setTint(int tintRgb) {
        if (tintRgb != mTintRgb) {
            mTintRgb = tintRgb;
            mEffectW = 0;
            applyEffects();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateSession();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mSession != null) {
            mSession.stop();
            mSession = null;
        }
        mScreen = null;
        if (mWindow != null) {
            mWindow.recycle();
            mWindow = null;
        }
    }

    private void updateSession() {
        if (mLive && mSession == null) {
            mSession = new ScreenBackdrop.Session(this, new ScreenBackdrop.Sink() {
                @Override
                public void onFrame(Bitmap frame) {
                    mScreen = frame;
                    redraw();
                }

                @Override
                public void onRefused() {
                    mSession = null;
                    mLive = false;
                    if (mOnRefused != null) {
                        mOnRefused.run();
                    }
                }
            }, mScale, mIntervalMs);
            mSession.start();
        } else if (!mLive && mSession != null) {
            mSession.stop();
            mSession = null;
            mScreen = null;
        }
    }

    /**
     * Never asks for room of its own.
     *
     * <p>It is the background of a pane that sizes itself to its content. Asked "how big can you
     * be" it answered "as big as you let me", and a wrap-content pane grew to the whole window -
     * menus and panels running off to the screen's edges. Given an exact size it takes it; any
     * other time it is nothing, and the pane, once it knows its own size, measures it again to
     * match.
     */
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY
                ? MeasureSpec.getSize(widthMeasureSpec) : 0;
        int h = MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY
                ? MeasureSpec.getSize(heightMeasureSpec) : 0;
        setMeasuredDimension(w, h);
        int cw = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY);
        int ch = MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY);
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).measure(cw, ch);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        applyEffects();
    }

    private void applyEffects() {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0 || (w == mEffectW && h == mEffectH)) {
            return;
        }
        RenderEffect frost = LiquidGlass.frost(getContext(), mMaterial, w, h, mRadius, mExtend,
                mTintRgb, mBase);
        RenderEffect lens = LiquidGlass.lens(getContext(), mMaterial, w, h, mRadius, mExtend,
                mTintRgb);
        if (frost == null || lens == null) {
            return;
        }
        mFrost.setRenderEffect(frost);
        mLens.setRenderEffect(lens);
        mEffectW = w;
        mEffectH = h;
    }

    private void redraw() {
        mFrost.invalidate();
        mLens.invalidate();
    }

    /** One of the two: draws the backdrop; its RenderEffect makes it frost or lens. */
    private final class Layer extends View {
        private final Paint mPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        private final Rect mDst = new Rect();

        Layer(Context ctx) {
            super(ctx);
            setWillNotDraw(false);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            mDst.set(0, 0, getWidth(), getHeight());
            Bitmap screen = mScreen;
            if (screen != null && !screen.isRecycled()) {
                canvas.drawBitmap(screen, null, mDst, mPaint);
            }
            Bitmap window = mWindow;
            if (window != null && !window.isRecycled()) {
                canvas.drawBitmap(window, null, mDst, mPaint);
            }
        }
    }

    /** The system blur, for a pane whose device will not capture: same look, minus the lens. */
    public static Drawable fallback(View host, LiquidGlass.Material m, float radiusPx,
            int tintArgb) {
        return Blur.backdrop(host, Ui.dp(host.getContext(), m.blur * 1.5f), radiusPx, tintArgb);
    }
}
