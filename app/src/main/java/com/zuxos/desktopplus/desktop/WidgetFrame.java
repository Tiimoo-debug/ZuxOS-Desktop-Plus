package com.zuxos.desktopplus.desktop;

import android.content.Context;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

import com.zuxos.desktopplus.core.Ui;
import com.zuxos.desktopplus.model.Item;

/**
 * Wrapper around an {@code AppWidgetHostView}.
 *
 * <p>A widget swallows touches, so moving it needs the container to take the gesture. Hold it
 * anywhere and move: it comes off the desktop and can be dropped elsewhere. Hold and let go: the
 * resize frame. Right-click: its menu. Taps still reach the widget.
 *
 * <p>The hold is watched in {@code dispatchTouchEvent}, which sees every event. It used to be
 * {@code onInterceptTouchEvent}, which a widget with a list or a scrolling area switches off for
 * itself on the first touch - so a calendar could only be picked up by its edges.
 */
public class WidgetFrame extends FrameLayout {

    public interface Host {
        /** Held and let go: the resize frame. */
        void onWidgetLongPress(WidgetFrame frame);

        /** Held and moved: pick it up, grabbed at this point inside the frame. */
        void onWidgetPickUp(WidgetFrame frame, float grabX, float grabY);

        void onWidgetMenu(WidgetFrame frame, float rawX, float rawY);
    }

    private final Host mHost;
    private final Item mItem;
    private final int mTouchSlop;
    private final Runnable mLongPress = this::hold;

    private float mDownX;
    private float mDownY;
    private boolean mHeld;
    private boolean mPickedUp;

    public WidgetFrame(Context ctx, Item item, Host host) {
        super(ctx);
        mItem = item;
        mHost = host;
        mTouchSlop = ViewConfiguration.get(ctx).getScaledTouchSlop();
        int pad = Ui.dp(ctx, 2);
        setPadding(pad, pad, pad, pad);
    }

    public Item getItem() {
        return mItem;
    }

    public View widgetView() {
        return getChildCount() > 0 ? getChildAt(0) : null;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (isSecondaryButton(ev)) {
                    mHost.onWidgetMenu(this, ev.getRawX(), ev.getRawY());
                    return true;
                }
                mDownX = ev.getX();
                mDownY = ev.getY();
                mHeld = false;
                mPickedUp = false;
                postDelayed(mLongPress, ViewConfiguration.getLongPressTimeout());
                break;
            case MotionEvent.ACTION_MOVE: {
                boolean moved = Math.abs(ev.getX() - mDownX) > mTouchSlop
                        || Math.abs(ev.getY() - mDownY) > mTouchSlop;
                if (mHeld) {
                    if (moved && !mPickedUp) {
                        mPickedUp = true;
                        settle();
                        mHost.onWidgetPickUp(this, mDownX, mDownY);
                    }
                    return true;
                }
                if (moved) {
                    // A scroll or a swipe inside the widget, not a hold.
                    removeCallbacks(mLongPress);
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(mLongPress);
                if (mHeld) {
                    boolean release = ev.getActionMasked() == MotionEvent.ACTION_UP && !mPickedUp;
                    mHeld = false;
                    settle();
                    if (release) {
                        mHost.onWidgetLongPress(this);
                    }
                    return true;
                }
                break;
            default:
                break;
        }
        return mHeld || super.dispatchTouchEvent(ev);
    }

    /**
     * The hold has happened: from here the gesture is ours, so the widget is told its touch is
     * over - otherwise a button under the finger would fire when it lifts.
     */
    private void hold() {
        mHeld = true;
        long now = SystemClock.uptimeMillis();
        MotionEvent cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, mDownX,
                mDownY, 0);
        super.dispatchTouchEvent(cancel);
        cancel.recycle();
        if (getParent() != null) {
            getParent().requestDisallowInterceptTouchEvent(true);
        }
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        animate().scaleX(1.03f).scaleY(1.03f).setDuration(120).start();
    }

    private void settle() {
        animate().scaleX(1f).scaleY(1f).setDuration(120).start();
    }

    private static boolean isSecondaryButton(MotionEvent ev) {
        return (ev.getButtonState() & MotionEvent.BUTTON_SECONDARY) != 0;
    }
}
