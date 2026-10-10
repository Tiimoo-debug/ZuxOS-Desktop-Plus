package com.zuxos.desktopplus.desktop;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.View;
import android.widget.FrameLayout;

import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Snapshot;
import com.zuxos.desktopplus.core.glass.Glass;
import com.zuxos.desktopplus.core.glass.GlassBackdrop;
import com.zuxos.desktopplus.core.glass.LiquidGlass;
import com.zuxos.desktopplus.core.glass.ScreenBackdrop;
import com.zuxos.desktopplus.core.theme.Bevel;
import com.zuxos.desktopplus.core.theme.Theme;

import java.util.ArrayList;
import java.util.List;

/**
 * A panel of liquid glass: whatever is behind it, frosted, with its rim bending the backdrop.
 *
 * <p>What is behind it comes in two parts. Everything on screen under the panel's window -
 * wallpaper, apps, other windows - is captured live ({@link ScreenBackdrop}). What is under the
 * panel inside its own window - the desktop's icons and widgets, a folder's scrim - is drawn once
 * when the panel opens ({@link Snapshot}), stopping at the panel so nothing in front of it shows
 * up inside it. {@link GlassBackdrop} lays the two together and runs the shaders.
 *
 * <p>Where the device will not capture the screen, the panel keeps the in-window part and its own
 * tint shows through where that has nothing - the wallpaper, mostly - which is how it looked
 * before. Without AGSL at all it asks the compositor for a plain blur.
 *
 * <p>Told it is in the Retro theme ({@link #theme}), it is no glass at all: a raised Windows 98
 * box, with nothing captured behind it.
 */
public class GlassPanel extends FrameLayout {

    /** The in-window picture's size against the panel's: it is frosted anyway. */
    private static final float CAPTURE_SCALE = 0.5f;

    private final GlassBackdrop mBackdrop;
    private final float mRadiusPx;
    private final int mTint;
    private final LiquidGlass.Material mMaterial;

    private final List<View> mSources = new ArrayList<>(2);
    private boolean mRealBlur;
    private boolean mRetro;
    private static boolean sSaidPath;

    public GlassPanel(Context ctx, float radiusPx, int tint) {
        this(ctx, radiusPx, tint, LiquidGlass.MENU);
    }

    /**
     * @param tint ARGB shown where nothing is behind the panel at all; its lightness also picks
     *             light or dark glass
     */
    public GlassPanel(Context ctx, float radiusPx, int tint, LiquidGlass.Material material) {
        super(ctx);
        mRadiusPx = radiusPx;
        mTint = tint;
        mMaterial = material;
        mBackdrop = new GlassBackdrop(ctx, material, radiusPx, 0f,
                LiquidGlass.tintFor(isDark(tint)), tint, 16L);
        addView(mBackdrop, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        if (!LiquidGlass.isSupported()) {
            // No AGSL: the panel keeps the layered-translucency look.
            setBackground(Glass.panel(ctx, (int) radiusPx));
        }
    }

    /**
     * The theme to paint in; glass unless told otherwise. Set before the panel is shown - and
     * again on a panel kept between showings, which follows the setting both ways.
     */
    public GlassPanel theme(Theme theme) {
        boolean retro = theme.retro();
        if (retro == mRetro) {
            return this;
        }
        mRetro = retro;
        mBackdrop.setVisibility(retro ? GONE : VISIBLE);
        if (retro) {
            setBackground(Bevel.raised(getContext()));
        } else {
            setBackground(LiquidGlass.isSupported() ? null
                    : Glass.panel(getContext(), (int) mRadiusPx));
            mRealBlur = false;
            mPictured[2] = 0;
        }
        return this;
    }

    /**
     * The live part starts with the panel, not once it has settled: the opening animation is
     * exactly when the glass has to be there already. Only the in-window picture, which has to
     * be taken where the panel will rest, waits for {@link #refresh}.
     */
    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!mRetro && LiquidGlass.isSupported() && !ScreenBackdrop.refused()) {
            mBackdrop.setLive(true, () -> L.d("glass: panel stays on its in-window backdrop"));
        }
    }

    private static boolean isDark(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return (0.2126 * r + 0.7152 * g + 0.0722 * b) < 128;
    }

    /** The view to capture from - usually the activity's content root. */
    public void setSource(View source) {
        mSources.clear();
        addSource(source);
    }

    /** Adds another view to capture, drawn on top of the ones already added. */
    public void addSource(View source) {
        if (source != null && !mSources.contains(source)) {
            mSources.add(source);
        }
    }

    /** Where and how big the in-window picture was taken, so a second call can skip it. */
    private final int[] mPictured = new int[4];

    /**
     * Takes the in-window picture and starts the live part.
     *
     * <p>Taken as soon as the panel has a size, mid-animation or not: it is placed by where the
     * panel will rest, not where its zoom has got to. Waiting for the animation to end is what
     * left a folder or menu without the desktop behind it for its whole opening.
     */
    public void refresh() {
        if (mRetro || getWidth() <= 0 || getHeight() <= 0) {
            return;
        }
        if (!LiquidGlass.isSupported()) {
            useRealBlur();
            return;
        }
        boolean live = !ScreenBackdrop.refused();
        mBackdrop.setLive(live, () -> {
            // Refused: the in-window picture is all there is, with the tint for the rest.
            L.d("glass: panel stays on its in-window backdrop");
        });
        int[] origin = restingOrigin();
        if (mPictured[0] == origin[0] && mPictured[1] == origin[1]
                && mPictured[2] == getWidth() && mPictured[3] == getHeight()) {
            // Already taken for exactly this place - the end of the opening animation, mostly.
            return;
        }
        mPictured[0] = origin[0];
        mPictured[1] = origin[1];
        mPictured[2] = getWidth();
        mPictured[3] = getHeight();
        mBackdrop.setWindowLayer(windowPicture(live, origin));
        if (!sSaidPath) {
            sSaidPath = true;
            L.i("glass: panels use the liquid glass shaders, "
                    + (live ? "with the live screen behind them" : "over the in-window picture only"));
        }
    }

    /**
     * What is behind the panel inside its own window.
     *
     * <p>With the screen captured live, only this window's own views are drawn: another window
     * handed in as a source - the stock drawer behind a folder - is already in the capture, and
     * would show twice.
     */
    private Bitmap windowPicture(boolean live, int[] origin) {
        List<View> sources = new ArrayList<>();
        for (View source : mSources) {
            if (!live || source.getRootView() == getRootView()) {
                sources.add(source);
            }
        }
        if (sources.isEmpty()) {
            return null;
        }
        int width = Math.max(1, (int) (getWidth() * CAPTURE_SCALE));
        int height = Math.max(1, (int) (getHeight() * CAPTURE_SCALE));
        try {
            return Snapshot.capture(sources, this, origin, width, height, CAPTURE_SCALE);
        } catch (Throwable t) {
            L.d("glass: the in-window picture failed (" + t + ")");
            if (!live) {
                post(this::useRealBlur);
            }
            return null;
        }
    }

    /** The panel's top-left on screen once its own zoom has finished: its parent's, plus its place. */
    private int[] restingOrigin() {
        int[] origin = new int[2];
        if (getParent() instanceof View) {
            View parent = (View) getParent();
            parent.getLocationOnScreen(origin);
            // Translation and scale are the opening animation's, not the panel's place.
            origin[0] += getLeft() - parent.getScrollX();
            origin[1] += getTop() - parent.getScrollY();
        } else {
            getLocationOnScreen(origin);
        }
        return origin;
    }

    private void useRealBlur() {
        if (mRealBlur) {
            return;
        }
        mRealBlur = true;
        android.graphics.drawable.Drawable backdrop = GlassBackdrop.fallback(this, mMaterial,
                mRadiusPx, mTint);
        setBackground(backdrop != null ? backdrop : Glass.panel(getContext(), (int) mRadiusPx));
    }

    /**
     * Measures to the content, not to the backdrop.
     *
     * <p>A FrameLayout sizes itself to its largest child, and the backdrop asks to match the
     * parent - which against a wrap-content parent resolves to the whole available space. That is
     * how a folder popup ended up covering the screen. The backdrop is measured last, to whatever
     * the content decided.
     */
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int maxWidth = 0;
        int maxHeight = 0;
        int childState = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child == mBackdrop || child.getVisibility() == GONE) {
                continue;
            }
            measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, 0);
            MarginLayoutParams lp = (MarginLayoutParams) child.getLayoutParams();
            maxWidth = Math.max(maxWidth,
                    child.getMeasuredWidth() + lp.leftMargin + lp.rightMargin);
            maxHeight = Math.max(maxHeight,
                    child.getMeasuredHeight() + lp.topMargin + lp.bottomMargin);
            childState = combineMeasuredStates(childState, child.getMeasuredState());
        }
        maxWidth = Math.max(maxWidth + getPaddingLeft() + getPaddingRight(),
                getSuggestedMinimumWidth());
        maxHeight = Math.max(maxHeight + getPaddingTop() + getPaddingBottom(),
                getSuggestedMinimumHeight());
        setMeasuredDimension(
                resolveSizeAndState(maxWidth, widthMeasureSpec, childState),
                resolveSizeAndState(maxHeight, heightMeasureSpec,
                        childState << MEASURED_HEIGHT_STATE_SHIFT));

        // Children that asked to match the panel get the size the panel settled on.
        int innerW = getMeasuredWidth() - getPaddingLeft() - getPaddingRight();
        int innerH = getMeasuredHeight() - getPaddingTop() - getPaddingBottom();
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child == mBackdrop || child.getVisibility() == GONE) {
                continue;
            }
            MarginLayoutParams lp = (MarginLayoutParams) child.getLayoutParams();
            if (lp.width != LayoutParams.MATCH_PARENT && lp.height != LayoutParams.MATCH_PARENT) {
                continue;
            }
            int w = lp.width == LayoutParams.MATCH_PARENT
                    ? MeasureSpec.makeMeasureSpec(
                            Math.max(0, innerW - lp.leftMargin - lp.rightMargin),
                            MeasureSpec.EXACTLY)
                    : getChildMeasureSpec(widthMeasureSpec,
                            getPaddingLeft() + getPaddingRight() + lp.leftMargin + lp.rightMargin,
                            lp.width);
            int h = lp.height == LayoutParams.MATCH_PARENT
                    ? MeasureSpec.makeMeasureSpec(
                            Math.max(0, innerH - lp.topMargin - lp.bottomMargin),
                            MeasureSpec.EXACTLY)
                    : getChildMeasureSpec(heightMeasureSpec,
                            getPaddingTop() + getPaddingBottom() + lp.topMargin + lp.bottomMargin,
                            lp.height);
            child.measure(w, h);
        }
        mBackdrop.measure(
                MeasureSpec.makeMeasureSpec(getMeasuredWidth(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(getMeasuredHeight(), MeasureSpec.EXACTLY));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w != oldw || h != oldh) {
            post(this::refresh);
        }
    }
}
