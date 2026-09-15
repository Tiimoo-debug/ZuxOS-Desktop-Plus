package com.zuxos.desktopplus.desktop;

import android.app.Activity;
import android.app.AlertDialog;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.UserHandle;
import android.util.SizeF;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.zuxos.desktopplus.core.Const;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Ui;
import com.zuxos.desktopplus.model.Item;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Hosts home-screen widgets ourselves.
 *
 * <p>This is what makes widgets possible on a desktop-mode home that never had them: we run our
 * own {@link AppWidgetHost} inside the launcher process instead of trying to talk the stock
 * launcher into showing widgets it was built without.
 */
public class WidgetHostCtl {

    /** Called once a widget id is fully bound and configured. */
    public interface PlacementListener {
        void onWidgetReady(int widgetId, AppWidgetProviderInfo info);

        void onWidgetCancelled(int widgetId);
    }

    private final Activity mActivity;
    private final AppWidgetManager mManager;
    private final AppWidgetHost mHost;
    private final PlacementListener mListener;

    private int mPendingId = -1;
    private AppWidgetProviderInfo mPendingInfo;
    private boolean mListening;

    public WidgetHostCtl(Activity activity, PlacementListener listener) {
        mActivity = activity;
        mListener = listener;
        mManager = AppWidgetManager.getInstance(activity);
        mHost = new AppWidgetHost(activity, Const.WIDGET_HOST_ID);
    }

    public void start() {
        if (mListening) {
            return;
        }
        try {
            mHost.startListening();
            mListening = true;
        } catch (Throwable t) {
            // Thrown when the host has stale ids after an uninstall; safe to continue.
            L.e("widget host startListening failed", t);
        }
    }

    public void stop() {
        if (!mListening) {
            return;
        }
        try {
            mHost.stopListening();
        } catch (Throwable t) {
            L.e("widget host stopListening failed", t);
        }
        mListening = false;
    }

    public AppWidgetProviderInfo infoFor(int widgetId) {
        try {
            return mManager.getAppWidgetInfo(widgetId);
        } catch (Throwable t) {
            return null;
        }
    }

    public AppWidgetHostView createView(Item item) {
        AppWidgetProviderInfo info = infoFor(item.widgetId);
        if (info == null) {
            L.w("widget " + item.widgetId + " no longer exists");
            return null;
        }
        try {
            // The activity context, not the application one: it carries the external display's
            // configuration, which is what the widget's RemoteViews get inflated against.
            AppWidgetHostView view = mHost.createView(mActivity, item.widgetId, info);
            view.setAppWidget(item.widgetId, info);
            return view;
        } catch (Throwable t) {
            L.e("createView failed for widget " + item.widgetId, t);
            return null;
        }
    }

    public void updateSize(AppWidgetHostView view, int widthPx, int heightPx) {
        if (view == null) {
            return;
        }
        float density = mActivity.getResources().getDisplayMetrics().density;
        int wDp = (int) (widthPx / density);
        int hDp = (int) (heightPx / density);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                view.updateAppWidgetSize(new Bundle(),
                        Collections.singletonList(new SizeF(wDp, hDp)));
            } else {
                view.updateAppWidgetSize(new Bundle(), wDp, hDp, wDp, hDp);
            }
        } catch (Throwable t) {
            L.d("updateAppWidgetSize failed: " + t);
        }
    }

    public void deleteWidget(int widgetId) {
        try {
            mHost.deleteAppWidgetId(widgetId);
        } catch (Throwable t) {
            L.d("deleteAppWidgetId failed: " + t);
        }
    }

    /** Minimum span in cells a provider needs, so a freshly added widget is not clipped. */
    public int[] minSpans(AppWidgetProviderInfo info, int cellWidthPx, int cellHeightPx) {
        int spanX = Math.max(1, (int) Math.ceil(info.minWidth / (float) Math.max(1, cellWidthPx)));
        int spanY = Math.max(1, (int) Math.ceil(info.minHeight / (float) Math.max(1, cellHeightPx)));
        return new int[]{spanX, spanY};
    }

    // --- picking / binding -----------------------------------------------

    public void showPicker() {
        List<AppWidgetProviderInfo> providers = new ArrayList<>();
        try {
            providers.addAll(mManager.getInstalledProviders());
        } catch (Throwable t) {
            L.e("could not list widget providers", t);
        }
        if (providers.isEmpty()) {
            toast("No widgets available");
            return;
        }
        final PackageManager pm = mActivity.getPackageManager();
        Collections.sort(providers, (a, b) -> label(a, pm).compareToIgnoreCase(label(b, pm)));

        ListView list = new ListView(mActivity);
        WidgetAdapter adapter = new WidgetAdapter(mActivity, providers);
        list.setAdapter(adapter);

        AlertDialog dialog = new AlertDialog.Builder(mActivity)
                .setTitle("Add widget")
                .setView(list)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        list.setOnItemClickListener((parent, view, position, id) -> {
            dialog.dismiss();
            addWidget(providers.get(position));
        });
        show(dialog);
    }

    public void addWidget(AppWidgetProviderInfo info) {
        int widgetId;
        try {
            widgetId = mHost.allocateAppWidgetId();
        } catch (Throwable t) {
            L.e("allocateAppWidgetId failed", t);
            toast("Could not allocate a widget id");
            return;
        }
        mPendingId = widgetId;
        mPendingInfo = info;

        UserHandle user = info.getProfile() != null ? info.getProfile()
                : android.os.Process.myUserHandle();
        boolean bound = false;
        try {
            bound = mManager.bindAppWidgetIdIfAllowed(widgetId, user, info.provider, new Bundle());
        } catch (Throwable t) {
            L.d("bindAppWidgetIdIfAllowed threw: " + t);
        }
        if (bound) {
            configureOrFinish(widgetId, info);
            return;
        }
        // Not privileged to bind silently - ask the user through the system dialog.
        try {
            Intent intent = new Intent(AppWidgetManager.ACTION_APPWIDGET_BIND);
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider);
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, user);
            mActivity.startActivityForResult(intent, Const.REQ_BIND_WIDGET);
        } catch (Throwable t) {
            L.e("widget bind request failed", t);
            cancelPending();
            toast("This launcher is not allowed to bind widgets");
        }
    }

    private void configureOrFinish(int widgetId, AppWidgetProviderInfo info) {
        if (info.configure != null) {
            try {
                mHost.startAppWidgetConfigureActivityForResult(mActivity, widgetId, 0,
                        Const.REQ_CONFIGURE_WIDGET, null);
                return;
            } catch (Throwable t) {
                L.d("configure activity failed, placing unconfigured: " + t);
            }
        }
        finishPending(widgetId, info);
    }

    private void finishPending(int widgetId, AppWidgetProviderInfo info) {
        mPendingId = -1;
        mPendingInfo = null;
        if (mListener != null) {
            mListener.onWidgetReady(widgetId, info);
        }
    }

    private void cancelPending() {
        int id = mPendingId;
        mPendingId = -1;
        mPendingInfo = null;
        if (id != -1) {
            deleteWidget(id);
            if (mListener != null) {
                mListener.onWidgetCancelled(id);
            }
        }
    }

    public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != Const.REQ_BIND_WIDGET && requestCode != Const.REQ_CONFIGURE_WIDGET) {
            return false;
        }
        int widgetId = mPendingId;
        if (data != null) {
            widgetId = data.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
        }
        if (resultCode != Activity.RESULT_OK || widgetId == -1) {
            cancelPending();
            return true;
        }
        AppWidgetProviderInfo info = mPendingInfo != null ? mPendingInfo : infoFor(widgetId);
        if (requestCode == Const.REQ_BIND_WIDGET) {
            configureOrFinish(widgetId, info);
        } else {
            finishPending(widgetId, info);
        }
        return true;
    }

    static String label(AppWidgetProviderInfo info, PackageManager pm) {
        CharSequence l = null;
        try {
            l = info.loadLabel(pm);
        } catch (Throwable t) {
            L.d("loadLabel failed for " + info.provider + ": " + t);
        }
        if (l == null || l.length() == 0) {
            l = info.provider != null ? info.provider.getShortClassName() : "widget";
        }
        return String.valueOf(l);
    }

    /** Provider label resolved against this host's package manager. */
    public String labelOf(AppWidgetProviderInfo info) {
        return label(info, mActivity.getPackageManager());
    }

    private void toast(String msg) {
        try {
            Toast.makeText(mActivity, msg, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
            L.w(msg);
        }
    }

    private void show(AlertDialog dialog) {
        try {
            dialog.show();
        } catch (Throwable t) {
            L.e("could not show dialog", t);
        }
    }

    /** Provider list rows: preview image + label + size, built without any module resources. */
    private static final class WidgetAdapter extends BaseAdapter {
        private final Context mCtx;
        private final List<AppWidgetProviderInfo> mItems;

        WidgetAdapter(Context ctx, List<AppWidgetProviderInfo> items) {
            mCtx = ctx;
            mItems = items;
        }

        @Override
        public int getCount() {
            return mItems.size();
        }

        @Override
        public Object getItem(int position) {
            return mItems.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row;
            if (convertView instanceof LinearLayout) {
                row = (LinearLayout) convertView;
                row.removeAllViews();
            } else {
                row = new LinearLayout(mCtx);
            }
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int pad = Ui.dp(mCtx, 12);
            row.setPadding(pad, pad, pad, pad);

            AppWidgetProviderInfo info = mItems.get(position);
            ImageView icon = new ImageView(mCtx);
            int iconSize = Ui.dp(mCtx, 40);
            Drawable preview = null;
            try {
                preview = info.loadIcon(mCtx, mCtx.getResources().getDisplayMetrics().densityDpi);
            } catch (Throwable ignored) {
                // Some providers ship broken previews; the row still works without one.
            }
            icon.setImageDrawable(preview);
            row.addView(icon, new LinearLayout.LayoutParams(iconSize, iconSize));

            LinearLayout texts = new LinearLayout(mCtx);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView title = new TextView(mCtx);
            title.setText(label(info, mCtx.getPackageManager()));
            title.setTextSize(16);
            TextView sub = new TextView(mCtx);
            String pkg = info.provider != null ? info.provider.getPackageName() : "";
            sub.setText(pkg);
            sub.setTextSize(12);
            sub.setAlpha(0.7f);
            texts.addView(title);
            texts.addView(sub);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            tlp.leftMargin = Ui.dp(mCtx, 12);
            row.addView(texts, tlp);
            return row;
        }
    }

    /** Component of the pending provider, for logging. */
    public ComponentName pendingProvider() {
        return mPendingInfo != null ? mPendingInfo.provider : null;
    }
}
