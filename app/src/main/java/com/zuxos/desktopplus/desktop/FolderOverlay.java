package com.zuxos.desktopplus.desktop;

import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.zuxos.desktopplus.core.Anim;
import com.zuxos.desktopplus.core.Glass;
import com.zuxos.desktopplus.core.Ui;
import com.zuxos.desktopplus.model.AppsRepo;
import com.zuxos.desktopplus.model.Item;

/** The panel shown when a folder is opened: rename field plus the folder's contents. */
public class FolderOverlay extends FrameLayout {

    public interface Listener {
        void onOpenChild(Item folder, Item child, View source);

        void onChildDragOut(Item folder, Item child, View source);

        void onRenamed(Item folder, String name);

        void onClosed(Item folder);
    }

    private final Listener mListener;
    private final AppsRepo mRepo;
    private final GridLayout mGrid;
    private final EditText mName;
    private final TextView mEmpty;
    private LinearLayout mPanel;
    private Item mFolder;

    public FolderOverlay(Context ctx, AppsRepo repo, Listener listener) {
        super(ctx);
        mRepo = repo;
        mListener = listener;
        setBackgroundColor(Ui.COLOR_SCRIM);
        setClickable(true);
        setVisibility(GONE);
        setOnClickListener(v -> close());

        final LinearLayout panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(Glass.panel(ctx, Ui.dp(ctx, 24)));
        int pad = Ui.dp(ctx, 20);
        panel.setPadding(pad, pad, pad, pad);
        // Swallow clicks so tapping the panel itself does not close the folder.
        panel.setClickable(true);

        mName = new EditText(ctx);
        mName.setSingleLine(true);
        mName.setTextColor(Ui.COLOR_TEXT);
        mName.setTextSize(18);
        mName.setBackground(null);
        mName.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (mFolder != null) {
                    mListener.onRenamed(mFolder, s.toString());
                }
            }
        });
        panel.addView(mName, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        mGrid = new GridLayout(ctx);
        mGrid.setColumnCount(4);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        glp.topMargin = Ui.dp(ctx, 12);
        panel.addView(mGrid, glp);

        mEmpty = new TextView(ctx);
        mEmpty.setText("Empty - drag apps in from the desktop or the app drawer");
        mEmpty.setTextColor(Ui.COLOR_TEXT_DIM);
        mEmpty.setVisibility(GONE);
        panel.addView(mEmpty);

        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        plp.gravity = Gravity.CENTER;
        addView(panel, plp);
        mPanel = panel;
    }

    public Item getFolder() {
        return mFolder;
    }

    public boolean isOpen() {
        return getVisibility() == VISIBLE;
    }

    public void open(Item folder, int iconSizePx, boolean showLabels, boolean labelShadow) {
        mFolder = folder;
        mName.setText(folder.label != null ? folder.label : "Folder");
        rebuild(iconSizePx, showLabels, labelShadow);
        bringToFront();
        Anim.popIn(this, mPanel);
    }

    public void rebuild(int iconSizePx, boolean showLabels, boolean labelShadow) {
        mGrid.removeAllViews();
        if (mFolder == null) {
            return;
        }
        int columns = Math.max(1, Math.min(4, mFolder.children.size()));
        mGrid.setColumnCount(columns);
        mEmpty.setVisibility(mFolder.children.isEmpty() ? VISIBLE : GONE);

        int cell = iconSizePx + Ui.dp(getContext(), showLabels ? 44 : 16);
        for (final Item child : mFolder.children) {
            ItemView iv = new ItemView(getContext(), iconSizePx, showLabels, labelShadow);
            iv.bind(child, mRepo);
            iv.setOnClickListener(v -> mListener.onOpenChild(mFolder, child, v));
            iv.setOnLongClickListener(v -> {
                mListener.onChildDragOut(mFolder, child, v);
                return true;
            });
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = cell;
            lp.height = GridLayout.LayoutParams.WRAP_CONTENT;
            lp.setMargins(Ui.dp(getContext(), 4), Ui.dp(getContext(), 4),
                    Ui.dp(getContext(), 4), Ui.dp(getContext(), 4));
            mGrid.addView(iv, lp);
        }
    }

    public void close() {
        if (getVisibility() != VISIBLE) {
            return;
        }
        final Item folder = mFolder;
        mFolder = null;
        Anim.fadeOut(this, () -> {
            if (folder != null) {
                mListener.onClosed(folder);
            }
        });
    }
}
