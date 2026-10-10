package com.zuxos.desktopplus.hook.drawer;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.UserManager;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.zuxos.desktopplus.core.Const;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;
import com.zuxos.desktopplus.core.Storage;
import com.zuxos.desktopplus.core.Su;
import com.zuxos.desktopplus.core.Ui;
import com.zuxos.desktopplus.core.icons.Glyphs;
import com.zuxos.desktopplus.core.motion.Hover;
import com.zuxos.desktopplus.core.theme.Bevel;
import com.zuxos.desktopplus.core.theme.Theme;
import com.zuxos.desktopplus.desktop.Menus;
import com.zuxos.desktopplus.hook.taskbar.BarEdge;
import com.zuxos.desktopplus.hook.taskbar.TaskbarBridge;
import com.zuxos.desktopplus.hook.taskbar.TaskbarRunning;
import com.zuxos.desktopplus.hook.taskbar.TaskbarTray;
import com.zuxos.desktopplus.notify.NotifyProvider;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Your picture, your name and the power button, along the bottom of ZUI's app drawer - the way a
 * desktop's start menu has them.
 *
 * <p>On the desktop's screen only. The tablet's own drawer is ZUI's and stays exactly as it is:
 * every window is checked for its display before anything goes in.
 *
 * <p>The bar is added to the drawer's container, after ZUI's app list, so it draws over the list
 * and takes its own touches; it follows the sheet as the sheet slides and fades, and the list is
 * given bottom padding so its last row is never hidden behind it.
 */
public final class DrawerAccountBar {

    private static final String TAG = "zux-account-bar";
    private static final String NAME_FILE = "account_name.txt";

    private static final Map<View, Boolean> WATCHED = new WeakHashMap<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static boolean sSaidAttached;
    /** Android's name for this user as root read it; "" once asked. Main thread only. */
    private static String sRootName;

    private DrawerAccountBar() {
    }

    /** Every window the launcher opens comes through here; the drawer's is one of them. */
    public static void onWindowAdded(View root) {
        if (!(root instanceof ViewGroup) || WATCHED.containsKey(root)) {
            return;
        }
        String name = root.getClass().getSimpleName();
        if (!name.contains("Overlay") && !name.contains("AllApps")) {
            return;
        }
        WATCHED.put(root, Boolean.TRUE);
        root.post(() -> {
            if (TaskbarTray.displayIdOf(root) == Display.DEFAULT_DISPLAY) {
                // The tablet's drawer: never.
                return;
            }
            ViewTreeObserver.OnGlobalLayoutListener listener = () -> attach((ViewGroup) root);
            root.getViewTreeObserver().addOnGlobalLayoutListener(listener);
            attach((ViewGroup) root);
        });
    }

    private static void attach(ViewGroup window) {
        try {
            if (window.findViewWithTag(TAG) != null) {
                return;
            }
            List<View> sheets = Reflect.findByIdNames(window, "bottom_sheet_background");
            if (sheets.isEmpty() || !(sheets.get(0).getParent() instanceof ViewGroup)) {
                return;
            }
            View sheet = sheets.get(0);
            ViewGroup container = (ViewGroup) sheet.getParent();
            List<View> lists = Reflect.findByIdNames(window, "apps_list_view");
            View list = lists.isEmpty() ? null : lists.get(0);
            Bar bar = new Bar(window.getContext(), window, TaskbarTray.displayIdOf(window));
            int height = Ui.dp(window.getContext(), Bar.HEIGHT_DP);
            // Beside the sheet, after the list: above both, so it shows and takes its own taps.
            // (Inside the sheet it sat under the list and down behind the taskbar.)
            container.addView(bar, new ViewGroup.LayoutParams(1, height));
            // The window's own pre-draw: the last step before every frame, after the drawer's
            // slide and scroll have moved the sheet, so the bar lands in the same frame.
            window.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    if (!bar.isAttachedToWindow()) {
                        if (window.getViewTreeObserver().isAlive()) {
                            window.getViewTreeObserver().removeOnPreDrawListener(this);
                        }
                        return true;
                    }
                    bar.follow(sheet, list, window);
                    return true;
                }
            });
            if (!sSaidAttached) {
                sSaidAttached = true;
                L.i("account bar: on the drawer of display " + TaskbarTray.displayIdOf(window)
                        + " (in " + container.getClass().getSimpleName() + ")");
            }
        } catch (Throwable t) {
            L.d("account bar: not added (" + t + ")");
        }
    }

    /** The strip itself. */
    private static final class Bar extends LinearLayout {

        static final int HEIGHT_DP = 60;
        private static final int MARGIN_DP = 14;

        private final ViewGroup mWindow;
        private final int mDisplay;
        private final boolean mDark;
        /** Windows 98's start menu: square, bevelled, black, and nothing moving. */
        private final boolean mRetro;
        private final ImageView mAvatar;
        private final TextView mName;
        private final EditText mEdit;
        private final ImageView mPower;
        private ContentObserver mObserver;
        private int mListPadBase = -1;
        private float mLastTop = Float.NaN;

        Bar(Context ctx, ViewGroup window, int display) {
            super(ctx);
            setTag(TAG);
            mWindow = window;
            mDisplay = display;
            mRetro = Theme.of(display).retro();
            // Retro's start menu is grey with black text, whatever the system's mode.
            mDark = !mRetro && (ctx.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            int pad = Ui.dp(ctx, 10);
            setPadding(pad, 0, pad, 0);
            // No strip behind them: the picture, the name and the button sit on the drawer's glass.

            int avatar = Ui.dp(ctx, 40);
            mAvatar = new ImageView(ctx);
            mAvatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
            if (mRetro) {
                int frame = Ui.dp(ctx, 2);
                mAvatar.setBackground(Bevel.sunken(ctx));
                mAvatar.setPadding(frame, frame, frame, frame);
            } else {
                mAvatar.setOutlineProvider(new ViewOutlineProvider() {
                    @Override
                    public void getOutline(View view, Outline outline) {
                        outline.setOval(0, 0, view.getWidth(), view.getHeight());
                    }
                });
                mAvatar.setClipToOutline(true);
            }
            mAvatar.setContentDescription("Change your picture");
            mAvatar.setOnClickListener(v -> pickPicture());
            addView(mAvatar, new LayoutParams(avatar, avatar));

            FrameLayout nameBox = new FrameLayout(ctx);
            mName = new TextView(ctx);
            mName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            mName.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                    android.graphics.Typeface.NORMAL));
            mName.setTextColor(mDark ? 0xF2FFFFFF : 0xE6000000);
            mName.setSingleLine(true);
            mName.setEllipsize(TextUtils.TruncateAt.END);
            mName.setOnLongClickListener(v -> {
                startEditing();
                return true;
            });
            mEdit = new EditText(ctx);
            mEdit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            mEdit.setTextColor(mName.getCurrentTextColor());
            mEdit.setSingleLine(true);
            mEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
            mEdit.setImeOptions(EditorInfo.IME_ACTION_DONE);
            mEdit.setBackground(null);
            mEdit.setPadding(0, 0, 0, 0);
            mEdit.setVisibility(GONE);
            mEdit.setOnEditorActionListener((v, action, event) -> {
                finishEditing();
                return true;
            });
            mEdit.setOnFocusChangeListener((v, focused) -> {
                if (!focused && mEdit.getVisibility() == VISIBLE) {
                    finishEditing();
                }
            });
            nameBox.addView(mName, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER_VERTICAL));
            nameBox.addView(mEdit, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER_VERTICAL));
            LayoutParams nameLp = new LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            nameLp.leftMargin = Ui.dp(ctx, 12);
            addView(nameBox, nameLp);

            int button = Ui.dp(ctx, 40);
            mPower = new ImageView(ctx);
            Drawable power = Glyphs.of(Glyphs.POWER);
            if (power != null) {
                power.setTint(mDark ? 0xF2FFFFFF : 0xD9000000);
            }
            mPower.setImageDrawable(power);
            int inset = Ui.dp(ctx, 9);
            mPower.setPadding(inset, inset, inset, inset);
            if (mRetro) {
                mPower.setBackground(Bevel.button(ctx));
            } else {
                GradientDrawable round = new GradientDrawable();
                round.setShape(GradientDrawable.OVAL);
                round.setColor(mDark ? 0x14FFFFFF : 0x0D000000);
                mPower.setBackground(round);
            }
            mPower.setContentDescription("Power");
            mPower.setOnClickListener(v -> powerMenu());
            addView(mPower, new LayoutParams(button, button));

            for (View v : new View[]{mAvatar, mPower}) {
                v.setOnTouchListener(new TaskbarRunning.Press());
                // Under a mouse or a stylus: a little bigger, with a soft light behind.
                v.setOnHoverListener((h, e) -> {
                    int action = e.getActionMasked();
                    if (action == android.view.MotionEvent.ACTION_HOVER_ENTER && !mRetro) {
                        Hover.lift(h);
                    } else if (action == android.view.MotionEvent.ACTION_HOVER_EXIT) {
                        Hover.drop(h);
                    }
                    return false;
                });
            }
            if (mRetro) {
                mName.setTextColor(Theme.RETRO.text());
                mEdit.setTextColor(Theme.RETRO.text());
                Theme.RETRO.applyFont(this);
            }
            String stored = storedName();
            mName.setText(stored);
            if (TextUtils.isEmpty(stored)) {
                defaultName();
            }
            loadPicture();
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            mObserver = new ContentObserver(MAIN) {
                @Override
                public void onChange(boolean selfChange) {
                    loadPicture();
                }
            };
            try {
                getContext().getContentResolver().registerContentObserver(NotifyProvider.AVATAR,
                        false, mObserver);
            } catch (Throwable t) {
                mObserver = null;
            }
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            if (mObserver != null) {
                getContext().getContentResolver().unregisterContentObserver(mObserver);
                mObserver = null;
            }
        }

        // --- where it sits ----------------------------------------------------

        /**
         * Along the bottom of the sheet's visible part - the drawer is cut off at the taskbar -
         * inset from its edges, moving and fading with it.
         */
        void follow(View sheet, View list, ViewGroup window) {
            Context ctx = getContext();
            View container = (View) sheet.getParent();
            int margin = Ui.dp(ctx, MARGIN_DP);
            int width = Math.max(1, sheet.getWidth() - 2 * margin);
            if (getLayoutParams().width != width) {
                getLayoutParams().width = width;
                requestLayout();
            }
            // The sheet's foot, or the first thing that hides it: the window's clip, or the top
            // of the taskbar - the drawer runs on under the bar.
            float bottom = sheet.getY() + sheet.getHeight();
            Rect clip = window.getClipBounds();
            if (clip != null) {
                int[] inWindow = new int[2];
                container.getLocationInWindow(inWindow);
                bottom = Math.min(bottom, clip.bottom - inWindow[1]);
            }
            int[] onScreen = new int[2];
            container.getLocationOnScreen(onScreen);
            int barTop = BarEdge.bottomBarTop(mDisplay);
            if (barTop > 0) {
                bottom = Math.min(bottom, barTop - onScreen[1]);
            }
            int height = getHeight() > 0 ? getHeight() : getLayoutParams().height;
            float top = bottom - margin - height;
            setX(sheet.getX() + margin);
            setY(top);
            setAlpha(sheet.getAlpha());
            setVisibility(sheet.getVisibility() == VISIBLE && sheet.isShown() ? VISIBLE
                    : INVISIBLE);
            // The list is re-padded only once the drawer is still: during a slide the bar is
            // held at the taskbar while the list moves, and re-laying the list out every frame
            // would stutter it.
            float barTopOnScreen = onScreen[1] + top;
            if (barTopOnScreen == mLastTop) {
                padList(list, barTopOnScreen);
            } else {
                // One more frame after the last move, so a drawer that stops still gets padded.
                postInvalidateOnAnimation();
            }
            mLastTop = barTopOnScreen;
        }

        /** Room under the list's last row, down to where the bar starts and a little more. */
        private void padList(View list, float barTopOnScreen) {
            Context ctx = getContext();
            if (list == null || list.getHeight() <= 0) {
                return;
            }
            if (mListPadBase < 0) {
                mListPadBase = list.getPaddingBottom();
            }
            int[] at = new int[2];
            list.getLocationOnScreen(at);
            // Screen pixels, so it holds whatever the list sits in; scale is 1 in the drawer.
            float listBottom = at[1] + list.getHeight();
            int want = mListPadBase + Math.max(0,
                    Math.round(listBottom - barTopOnScreen) + Ui.dp(ctx, 8));
            if (list.getPaddingBottom() != want) {
                list.setPadding(list.getPaddingLeft(), list.getPaddingTop(),
                        list.getPaddingRight(), want);
            }
        }

        // --- the name ----------------------------------------------------------

        private String storedName() {
            String text = Storage.read(Storage.file(getContext(), NAME_FILE));
            return text == null ? "" : text.trim();
        }

        /**
         * Android's own name for this user, the way the Users settings show it. The launcher may
         * not be allowed to ask; root then reads it off {@code pm list users}.
         */
        private void defaultName() {
            String name = null;
            try {
                UserManager um = (UserManager) getContext().getSystemService(Context.USER_SERVICE);
                name = um != null ? um.getUserName() : null;
            } catch (Throwable ignored) {
                // Not allowed; root below.
            }
            if (!TextUtils.isEmpty(name)) {
                mName.setText(name);
                return;
            }
            if (sRootName != null) {
                mName.setText(sRootName.isEmpty() ? "User" : sRootName);
                return;
            }
            mName.setText("User");
            // Asked once per process, not once per drawer: every root request can bring
            // Magisk's own window up for a moment.
            sRootName = "";
            int me = android.os.Process.myUid() / 100000;
            Su.read((outcome, text) -> {
                if (!outcome.ok() || text == null) {
                    if (outcome == Su.Outcome.BUSY) {
                        // Root was busy, not refused: the next drawer asks again.
                        MAIN.post(() -> sRootName = null);
                    }
                    return;
                }
                Matcher m = Pattern.compile("UserInfo\\{(\\d+):([^:}]+)").matcher(text);
                while (m.find()) {
                    if (Integer.parseInt(m.group(1)) == me) {
                        String found = m.group(2).trim();
                        MAIN.post(() -> {
                            sRootName = found;
                            if (TextUtils.isEmpty(storedName())) {
                                mName.setText(found);
                            }
                        });
                        return;
                    }
                }
            }, "pm list users");
        }

        private void startEditing() {
            mEdit.setText(mName.getText());
            mEdit.setSelection(mEdit.getText().length());
            mName.setVisibility(INVISIBLE);
            mEdit.setVisibility(VISIBLE);
            mEdit.requestFocus();
            InputMethodManager imm = (InputMethodManager) getContext()
                    .getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(mEdit, InputMethodManager.SHOW_IMPLICIT);
            }
        }

        private void finishEditing() {
            String text = mEdit.getText().toString().trim();
            mEdit.setVisibility(GONE);
            mName.setVisibility(VISIBLE);
            InputMethodManager imm = (InputMethodManager) getContext()
                    .getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(mEdit.getWindowToken(), 0);
            }
            if (text.isEmpty()) {
                // Empty means "back to Android's name".
                Storage.writeAsync(Storage.file(getContext(), NAME_FILE), "");
                defaultName();
                return;
            }
            mName.setText(text);
            Storage.writeAsync(Storage.file(getContext(), NAME_FILE), text);
            L.i("account bar: name set");
        }

        // --- the picture -------------------------------------------------------

        private void loadPicture() {
            Context ctx = getContext();
            new Thread(() -> {
                Bitmap picture = null;
                try (InputStream in = ctx.getContentResolver()
                        .openInputStream(NotifyProvider.AVATAR)) {
                    picture = in != null ? BitmapFactory.decodeStream(in) : null;
                } catch (Throwable ignored) {
                    // None chosen yet, or the module's provider is not reachable: the initial.
                }
                Bitmap found = picture;
                MAIN.post(() -> {
                    if (found != null) {
                        mAvatar.setImageBitmap(found);
                    } else {
                        mAvatar.setImageDrawable(new Initial(mName.getText()));
                    }
                });
            }, "zux-account-picture").start();
        }

        private void pickPicture() {
            try {
                TaskbarBridge.closeStockDrawer(mDisplay);
                Intent intent = new Intent()
                        .setClassName(Const.MODULE_PKG, Const.MODULE_PKG + ".ui.AvatarPicker")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ActivityOptions options = ActivityOptions.makeBasic().setLaunchDisplayId(mDisplay);
                getContext().startActivity(intent, options.toBundle());
            } catch (Throwable t) {
                L.d("account bar: could not open the picker (" + t + ")");
                Toast.makeText(getContext(), "Could not open the photo picker",
                        Toast.LENGTH_SHORT).show();
            }
        }

        // --- power ------------------------------------------------------------

        private void powerMenu() {
            if (!(mWindow instanceof FrameLayout)) {
                return;
            }
            List<Menus.Entry> entries = Menus.list();
            entries.add(new Menus.Entry("Sleep", () -> power("sleep", "input keyevent 223")));
            entries.add(new Menus.Entry("Restart", () -> power("restart", "svc power reboot")));
            entries.add(new Menus.Entry("Power off",
                    () -> power("power off", "svc power shutdown")));
            int[] at = new int[2];
            int[] root = new int[2];
            mPower.getLocationInWindow(at);
            mWindow.getLocationInWindow(root);
            // Opening up and to the left of the button, its corner just above the button's own:
            // below it is the taskbar, where the drawer is cut off.
            Menus.showAbove(getContext(), (FrameLayout) mWindow,
                    at[0] - root[0] + mPower.getWidth(),
                    at[1] - root[1] - Ui.dp(getContext(), 8), entries);
        }

        private void power(String what, String command) {
            TaskbarBridge.closeStockDrawer(mDisplay);
            Su.run(outcome -> {
                L.i("account bar: " + what + " -> " + outcome);
                if (!outcome.ok()) {
                    MAIN.post(() -> Toast.makeText(getContext(),
                            "Could not " + what + " - root is needed", Toast.LENGTH_SHORT).show());
                }
            }, command);
        }
    }

    /** No picture yet: the name's initial on a soft gradient disc. */
    private static final class Initial extends Drawable {
        private final String mLetter;
        private final Paint mDisc = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mText = new Paint(Paint.ANTI_ALIAS_FLAG);

        Initial(CharSequence name) {
            String trimmed = name == null ? "" : name.toString().trim();
            mLetter = trimmed.isEmpty() ? "?" : trimmed.substring(0, 1).toUpperCase();
            mText.setColor(0xFFFFFFFF);
            mText.setTextAlign(Paint.Align.CENTER);
            mText.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                    android.graphics.Typeface.NORMAL));
        }

        @Override
        protected void onBoundsChange(Rect bounds) {
            mDisc.setShader(new LinearGradient(bounds.left, bounds.top, bounds.right,
                    bounds.bottom, 0xFF5E8BFF, 0xFFB46CFF, Shader.TileMode.CLAMP));
            mText.setTextSize(bounds.height() * 0.46f);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            canvas.drawOval(b.left, b.top, b.right, b.bottom, mDisc);
            Paint.FontMetrics fm = mText.getFontMetrics();
            canvas.drawText(mLetter, b.exactCenterX(),
                    b.exactCenterY() - (fm.ascent + fm.descent) / 2f, mText);
        }

        @Override
        public void setAlpha(int alpha) {
            mDisc.setAlpha(alpha);
            mText.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter filter) {
            // Fixed colours.
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
