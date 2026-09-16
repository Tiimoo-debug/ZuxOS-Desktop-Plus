package com.zuxos.desktopplus.desktop;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.zuxos.desktopplus.core.Ui;
import com.zuxos.desktopplus.model.AppsRepo;
import com.zuxos.desktopplus.model.Item;

import java.util.ArrayList;
import java.util.List;

/** Icon + label for a single desktop / drawer / folder entry. */
public class ItemView extends LinearLayout {

    /**
     * Press handling, the way a launcher does it: hold and let go opens the item's menu, hold and
     * move picks the item up. One gesture, two outcomes, decided by whether the finger moved.
     */
    public interface Gestures {
        void onItemTap(ItemView view);

        void onItemMenu(ItemView view);

        void onItemPickUp(ItemView view);
    }

    private final ImageView mIcon;
    private final TextView mLabel;
    private final Paint mCheckPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mCheckMark = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int mTouchSlop;

    private Item mItem;
    private Gestures mGestures;
    private boolean mHeld;
    private boolean mPicked;
    private boolean mGestureHandled;
    private float mDownX;
    private float mDownY;

    private final Runnable mHoldTimeout = new Runnable() {
        @Override
        public void run() {
            mHeld = true;
            // Once the hold registers the gesture is ours: without this the drawer's scroller
            // steals the very next move and the icon can never be picked up.
            ViewParent parent = getParent();
            if (parent != null) {
                parent.requestDisallowInterceptTouchEvent(true);
            }
            animate().scaleX(1.08f).scaleY(1.08f).setDuration(90).start();
        }
    };

    public ItemView(Context ctx, int iconSizePx, boolean showLabel, boolean labelShadow) {
        super(ctx);
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = Ui.dp(ctx, 4);
        setPadding(pad, pad, pad, pad);
        setBackground(Ui.ripple(ctx, 0x00000000, Ui.dp(ctx, 12)));

        mIcon = new ImageView(ctx);
        LayoutParams ip = new LayoutParams(iconSizePx, iconSizePx);
        ip.gravity = Gravity.CENTER_HORIZONTAL;
        addView(mIcon, ip);

        mLabel = new TextView(ctx);
        Ui.styleLabel(mLabel, 12f, labelShadow);
        mLabel.setVisibility(showLabel ? VISIBLE : GONE);
        LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(ctx, 4);
        addView(mLabel, lp);

        setClickable(true);
        setLongClickable(true);
        setFocusable(true);
        mTouchSlop = ViewConfiguration.get(ctx).getScaledTouchSlop();
        mCheckPaint.setColor(Ui.COLOR_ACCENT);
        mCheckMark.setColor(0xFFFFFFFF);
        mCheckMark.setStyle(Paint.Style.STROKE);
        mCheckMark.setStrokeWidth(Ui.dp(ctx, 2));
        mCheckMark.setStrokeCap(Paint.Cap.ROUND);
        setWillNotDraw(false);
    }

    /** Installs the press handling. Views without this keep plain click listeners. */
    public void setGestures(Gestures gestures) {
        mGestures = gestures;
    }

    /** Ticked state, drawn as a badge over the icon while selecting several items. */
    public void setPicked(boolean picked) {
        if (mPicked != picked) {
            mPicked = picked;
            setAlpha(picked ? 0.85f : 1f);
            invalidate();
        }
    }

    public boolean isPicked() {
        return mPicked;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mGestures == null) {
            return super.onTouchEvent(event);
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDownX = event.getX();
                mDownY = event.getY();
                mHeld = false;
                mGestureHandled = false;
                setPressed(true);
                postDelayed(mHoldTimeout, ViewConfiguration.getLongPressTimeout());
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (mGestureHandled) {
                    return true;
                }
                boolean moved = Math.abs(event.getX() - mDownX) > mTouchSlop
                        || Math.abs(event.getY() - mDownY) > mTouchSlop;
                if (!moved) {
                    return true;
                }
                removeCallbacks(mHoldTimeout);
                if (mHeld) {
                    // Held, then moved: pick it up.
                    mGestureHandled = true;
                    releaseGesture();
                    mGestures.onItemPickUp(this);
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
                removeCallbacks(mHoldTimeout);
                releaseGesture();
                if (!mGestureHandled) {
                    mGestureHandled = true;
                    if (mHeld) {
                        mGestures.onItemMenu(this);
                    } else if (isInside(event)) {
                        // performClick keeps accessibility activation working; the listener list
                        // is empty, so it does not double up with the tap below.
                        performClick();
                        mGestures.onItemTap(this);
                    }
                }
                mHeld = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(mHoldTimeout);
                releaseGesture();
                mHeld = false;
                mGestureHandled = true;
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private void releaseGesture() {
        setPressed(false);
        animate().scaleX(1f).scaleY(1f).setDuration(90).start();
        ViewParent parent = getParent();
        if (parent != null) {
            parent.requestDisallowInterceptTouchEvent(false);
        }
    }

    private boolean isInside(MotionEvent event) {
        return event.getX() >= 0 && event.getY() >= 0
                && event.getX() <= getWidth() && event.getY() <= getHeight();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!mPicked) {
            return;
        }
        float r = Ui.dp(getContext(), 9);
        float cx = getWidth() - r - Ui.dp(getContext(), 2);
        float cy = r + Ui.dp(getContext(), 2);
        canvas.drawCircle(cx, cy, r, mCheckPaint);
        float s = r * 0.5f;
        canvas.drawLine(cx - s, cy, cx - s * 0.2f, cy + s * 0.6f, mCheckMark);
        canvas.drawLine(cx - s * 0.2f, cy + s * 0.6f, cx + s, cy - s * 0.6f, mCheckMark);
    }

    public Item getItem() {
        return mItem;
    }

    public ImageView iconView() {
        return mIcon;
    }

    public void bind(Item item, AppsRepo repo) {
        mItem = item;
        mLabel.setText(item.label != null ? item.label : "");
        if (item.type == Item.TYPE_FOLDER) {
            List<Drawable> previews = new ArrayList<>();
            for (Item child : item.children) {
                Drawable d = repo.iconFor(child);
                if (d != null) {
                    previews.add(d);
                }
                if (previews.size() == 4) {
                    break;
                }
            }
            mIcon.setImageDrawable(new FolderIconDrawable(previews, mIcon.getLayoutParams().width));
        } else {
            Drawable d = repo.iconFor(item);
            if (d != null) {
                mIcon.setImageDrawable(d);
            } else {
                mIcon.setImageDrawable(Ui.roundRect(0x55FFFFFF, Ui.dp(getContext(), 12)));
            }
        }
    }

    public void bindApp(AppsRepo.AppEntry entry, AppsRepo repo) {
        mItem = entry.toItem();
        mLabel.setText(entry.label);
        Drawable d = repo.iconFor(entry);
        mIcon.setImageDrawable(d != null ? d : Ui.roundRect(0x55FFFFFF, Ui.dp(getContext(), 12)));
    }

    /** Drag shadow that matches what the user grabbed, slightly enlarged like stock launchers. */
    public View.DragShadowBuilder shadow() {
        return new DragShadowBuilder(this) {
            @Override
            public void onProvideShadowMetrics(android.graphics.Point outShadowSize,
                    android.graphics.Point outShadowTouchPoint) {
                int w = (int) (getWidth() * 1.1f);
                int h = (int) (getHeight() * 1.1f);
                outShadowSize.set(Math.max(1, w), Math.max(1, h));
                outShadowTouchPoint.set(w / 2, h / 2);
            }

            @Override
            public void onDrawShadow(android.graphics.Canvas canvas) {
                canvas.scale(1.1f, 1.1f);
                getView().draw(canvas);
            }
        };
    }
}
