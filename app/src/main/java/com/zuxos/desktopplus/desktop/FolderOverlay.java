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
import com.zuxos.desktopplus.core.Ui;
import com.zuxos.desktopplus.model.AppsRepo;
import com.zuxos.desktopplus.desktop.DragPayload;
import com.zuxos.desktopplus.model.Item;

/** The panel shown when a folder is opened: rename field plus the folder's contents. */
public class FolderOverlay extends FrameLayout {

    public interface Listener {
        void onOpenChild(Item folder, Item child, View source);

        void onChildDragOut(Item folder, Item child, View source);

        void onChildrenReordered(Item folder);

        void onChildMenu(Item folder, Item child, View source);

        void onRenamed(Item folder, String name);

        void onClosed(Item folder);
    }

    private final Listener mListener;
    private final AppsRepo mRepo;
    private final GridLayout mGrid;
    private final EditText mName;
    private final TextView mEmpty;
    private GlassPanel mPanel;
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
        // Dropping inside the folder rearranges it; dropping outside falls through to the
        // desktop, which takes the item out of the folder.
        mGrid.setOnDragListener((v, event) -> {
            Object local = event.getLocalState();
            if (!(local instanceof DragPayload)) {
                return false;
            }
            DragPayload payload = (DragPayload) local;
            if (payload.source != DragPayload.SRC_FOLDER || payload.folder != mFolder) {
                return false;
            }
            switch (event.getAction()) {
                case android.view.DragEvent.ACTION_DRAG_STARTED:
                case android.view.DragEvent.ACTION_DRAG_LOCATION:
                    return true;
                case android.view.DragEvent.ACTION_DROP:
                    reorder(payload.item, indexAt(event.getX(), event.getY()));
                    return true;
                default:
                    return true;
            }
        });
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        glp.topMargin = Ui.dp(ctx, 12);
        panel.addView(mGrid, glp);

        mEmpty = new TextView(ctx);
        mEmpty.setText("Empty - drag apps in from the desktop or the app drawer");
        mEmpty.setTextColor(Ui.COLOR_TEXT_DIM);
        mEmpty.setVisibility(GONE);
        panel.addView(mEmpty);

        GlassPanel glass = new GlassPanel(ctx, Ui.dp(ctx, 26), 0x4D1C1C22);
        glass.addView(panel, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        plp.gravity = Gravity.CENTER;
        addView(glass, plp);
        mPanel = glass;
    }

    /** The view the folder's glass samples. */
    public void setBackdropSource(View source) {
        mPanel.setSource(source);
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
        post(mPanel::refresh);
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
            iv.setGestures(new ItemView.Gestures() {
                @Override
                public void onItemTap(ItemView view) {
                    mListener.onOpenChild(mFolder, child, view);
                }

                @Override
                public void onItemMenu(ItemView view) {
                    mListener.onChildMenu(mFolder, child, view);
                }

                @Override
                public void onItemPickUp(ItemView view) {
                    mListener.onChildDragOut(mFolder, child, view);
                }
            });
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = cell;
            lp.height = GridLayout.LayoutParams.WRAP_CONTENT;
            lp.setMargins(Ui.dp(getContext(), 4), Ui.dp(getContext(), 4),
                    Ui.dp(getContext(), 4), Ui.dp(getContext(), 4));
            mGrid.addView(iv, lp);
        }
    }

    /** Slot the point falls in, so a dropped icon lands where it was pointed. */
    private int indexAt(float x, float y) {
        int best = mGrid.getChildCount();
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < mGrid.getChildCount(); i++) {
            View child = mGrid.getChildAt(i);
            double dx = x - (child.getLeft() + child.getWidth() / 2f);
            double dy = y - (child.getTop() + child.getHeight() / 2f);
            double distance = dx * dx + dy * dy;
            if (distance < bestDistance) {
                bestDistance = distance;
                // Land before or after the nearest icon depending on which side was dropped on.
                best = dx < 0 ? i : i + 1;
            }
        }
        return best;
    }

    private void reorder(Item child, int index) {
        if (mFolder == null) {
            return;
        }
        int from = mFolder.children.indexOf(child);
        if (from < 0) {
            return;
        }
        mFolder.children.remove(from);
        if (index > from) {
            index--;
        }
        index = Math.max(0, Math.min(index, mFolder.children.size()));
        mFolder.children.add(index, child);
        mListener.onChildrenReordered(mFolder);
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
