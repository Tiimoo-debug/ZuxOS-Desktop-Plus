package com.zuxos.desktopplus.desktop;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

import com.zuxos.desktopplus.core.Ui;
import com.zuxos.desktopplus.model.Item;

/**
 * Wrapper around an {@code AppWidgetHostView}.
 *
 * <p>A widget swallows touches, so moving it needs the container to steal the gesture: we
 * intercept a long press (or a right-click, which is how people actually use an external
 * display) and hand it to the host, while normal taps still reach the widget.
 */
public class WidgetFrame extends FrameLayout {

    public interface Host {
        void onWidgetLongPress(WidgetFrame frame);

        void onWidgetMenu(WidgetFrame frame, float rawX, float rawY);
    }

    private final Host mHost;
    private final Item mItem;
    private final int mTouchSlop;
    private final Runnable mLongPress = new Runnable() {
        @Override
        public void run() {
            mLongPressFired = true;
            mHost.onWidgetLongPress(WidgetFrame.this);
        }
    };

    private float mDownX;
    private float mDownY;
    private boolean mLongPressFired;

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
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (isSecondaryButton(ev)) {
                    mHost.onWidgetMenu(this, ev.getRawX(), ev.getRawY());
                    return true;
                }
                mDownX = ev.getX();
                mDownY = ev.getY();
                mLongPressFired = false;
                postDelayed(mLongPress, ViewConfiguration.getLongPressTimeout());
                break;
            case MotionEvent.ACTION_MOVE:
                if (Math.abs(ev.getX() - mDownX) > mTouchSlop
                        || Math.abs(ev.getY() - mDownY) > mTouchSlop) {
                    removeCallbacks(mLongPress);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(mLongPress);
                break;
            default:
                break;
        }
        return mLongPressFired;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_UP
                || ev.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            removeCallbacks(mLongPress);
            mLongPressFired = false;
        }
        return true;
    }

    private static boolean isSecondaryButton(MotionEvent ev) {
        return (ev.getButtonState() & MotionEvent.BUTTON_SECONDARY) != 0;
    }
}
