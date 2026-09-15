package com.zuxos.desktopplus.desktop;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
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

    private final ImageView mIcon;
    private final TextView mLabel;
    private Item mItem;

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
