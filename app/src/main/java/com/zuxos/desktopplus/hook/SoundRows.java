package com.zuxos.desktopplus.hook;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.TrayIcons;
import com.zuxos.desktopplus.core.Ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Volume, the media transport, and volume per app.
 *
 * <p>There is deliberately no brightness slider. {@code Settings.System.SCREEN_BRIGHTNESS} is the
 * built-in panel's backlight; on an external display it changes a screen you are not looking at,
 * and a control that appears to work and does not is worse than none.
 *
 * <p>The stream sliders are plain {@link AudioManager} and work anywhere. Per-app volume is more
 * awkward than it sounds. Listing the controllers needs {@code MEDIA_CONTENT_CONTROL} or an
 * enabled notification listener - this launcher ships a media widget so it may hold the former,
 * and the attempt is made and any refusal logged precisely. But even with the list in hand, only
 * a session playing to a <em>remote</em> route carries a volume of its own. A local session's
 * volume is the media stream, shared by everything, so those get no slider: Android simply has
 * no per-app volume for audio coming out of the device's own speakers.
 */
public final class SoundRows {

    private SoundRows() {
    }

    /**
     * Adds the volume sliders, the media cards and the per-app sliders the platform allows.
     *
     * @return the sessions these rows were built from, so the caller can follow them
     */
    public static List<MediaController> addTo(Context ctx, LinearLayout body, int displayId) {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            body.addView(stream(ctx, am, AudioManager.STREAM_MUSIC, "Media",
                    TrayIcons.volume(Ui.COLOR_TEXT)));
            body.addView(stream(ctx, am, AudioManager.STREAM_RING, "Ringtone", null));
            body.addView(stream(ctx, am, AudioManager.STREAM_ALARM, "Alarm", null));
        }
        List<MediaController> controllers = sessions(ctx);
        addMedia(ctx, body, controllers);
        addPerApp(ctx, body, controllers);
        return controllers;
    }

    /**
     * A transport card per app that is playing: what it is, and the three buttons.
     *
     * <p>This is what fills the gap left by per-app volume, which Android does not have. The
     * session list is already in hand and the controls cost one call each.
     */
    private static void addMedia(Context ctx, LinearLayout body, List<MediaController> sessions) {
        PackageManager pm = ctx.getPackageManager();
        boolean headed = false;
        for (MediaController controller : sessions) {
            MediaMetadata meta;
            PlaybackState playback;
            try {
                meta = controller.getMetadata();
                playback = controller.getPlaybackState();
            } catch (Throwable t) {
                continue;
            }
            if (!isLive(playback)) {
                // A session outlives its playback. Stopped and errored ones would get a card with
                // buttons that do nothing, under a heading claiming something is playing.
                continue;
            }
            if (!headed) {
                body.addView(QuickPanel.sectionLabel(ctx, "Media"));
                headed = true;
            }
            body.addView(mediaCard(ctx, controller, pm, meta, playback));
        }
    }

    /** Playing, paused or on its way there - anything a transport button could act on. */
    private static boolean isLive(PlaybackState playback) {
        if (playback == null) {
            return false;
        }
        switch (playback.getState()) {
            case PlaybackState.STATE_PLAYING:
            case PlaybackState.STATE_PAUSED:
            case PlaybackState.STATE_BUFFERING:
            case PlaybackState.STATE_CONNECTING:
                return true;
            default:
                return false;
        }
    }

    /**
     * One playing app, in the shape the system's own media card uses: the artwork fills the card,
     * the track sits over it, and the transport is on the right.
     *
     * <p>The artwork is the background rather than a thumbnail beside the text, which is what
     * makes it read as the same object you see in the shade. Where a track has no artwork the card
     * falls back to the panel's own translucency and keeps its shape.
     */
    private static View mediaCard(Context ctx, MediaController controller, PackageManager pm,
            MediaMetadata meta, PlaybackState playback) {
        int radius = Ui.dp(ctx, 16);
        FrameLayout card = new FrameLayout(ctx);
        card.setBackground(Ui.roundRect(0x1AFFFFFF, radius));
        // The outline comes from that background, so the artwork is clipped to the same corners.
        card.setClipToOutline(true);

        Bitmap art = artBitmap(meta);
        if (art != null) {
            ImageView cover = new ImageView(ctx);
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cover.setImageBitmap(art);
            card.addView(cover, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));

            // Without this the text lands on whatever the album cover happens to be.
            View scrim = new View(ctx);
            scrim.setBackground(new GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[]{0xE6101014, 0xB3101014, 0x66101014}));
            card.addView(scrim, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
        }

        LinearLayout content = new LinearLayout(ctx);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(ctx, 12);
        content.setPadding(pad, pad, pad, pad);
        card.addView(content, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        content.addView(appPill(ctx, pm, controller));

        View filler = new View(ctx);
        content.addView(filler, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bottom = new LinearLayout(ctx);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        content.addView(bottom, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout text = new LinearLayout(ctx);
        text.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        bottom.addView(text, tlp);

        String title = meta != null ? meta.getString(MediaMetadata.METADATA_KEY_TITLE) : null;
        TextView line = new TextView(ctx);
        line.setText(title != null && !title.isEmpty() ? title
                : appName(pm, controller.getPackageName()));
        line.setTextColor(Ui.COLOR_TEXT);
        line.setTextSize(15);
        line.setSingleLine(true);
        line.setEllipsize(android.text.TextUtils.TruncateAt.END);
        line.setTypeface(line.getTypeface(), android.graphics.Typeface.BOLD);
        text.addView(line);

        String artist = meta != null ? meta.getString(MediaMetadata.METADATA_KEY_ARTIST) : null;
        if (artist != null && !artist.isEmpty()) {
            TextView sub = new TextView(ctx);
            sub.setText(artist);
            sub.setTextColor(Ui.COLOR_TEXT_DIM);
            sub.setTextSize(12);
            sub.setSingleLine(true);
            sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
            text.addView(sub);
        }

        boolean playing = playback.getState() == PlaybackState.STATE_PLAYING;
        bottom.addView(transport(ctx, TrayIcons.mediaPrevious(Ui.COLOR_TEXT), 32, false,
                () -> controller.getTransportControls().skipToPrevious()));
        bottom.addView(transport(ctx, playing ? TrayIcons.mediaPause(Ui.COLOR_TEXT)
                        : TrayIcons.mediaPlay(Ui.COLOR_TEXT), 44, true,
                () -> {
                    // Read now, not when this card was drawn: after the first press the card is
                    // out of date, and a captured flag would pause a second time instead of
                    // resuming.
                    PlaybackState live = controller.getPlaybackState();
                    boolean nowPlaying = live != null
                            && live.getState() == PlaybackState.STATE_PLAYING;
                    if (nowPlaying) {
                        controller.getTransportControls().pause();
                    } else {
                        controller.getTransportControls().play();
                    }
                }));
        bottom.addView(transport(ctx, TrayIcons.mediaNext(Ui.COLOR_TEXT), 32, false,
                () -> controller.getTransportControls().skipToNext()));

        long duration = meta != null
                ? meta.getLong(MediaMetadata.METADATA_KEY_DURATION) : 0L;
        if (duration > 0) {
            SeekLine seek = new SeekLine(ctx, controller, duration);
            FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 3));
            slp.gravity = Gravity.BOTTOM;
            slp.leftMargin = pad;
            slp.rightMargin = pad;
            slp.bottomMargin = Ui.dp(ctx, 6);
            card.addView(seek, slp);
        }

        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 108));
        clp.topMargin = Ui.dp(ctx, 6);
        card.setLayoutParams(clp);
        return card;
    }

    /**
     * The track's own artwork, or null when it has none.
     *
     * <p>Three places to look, in the order the platform itself prefers them. The bitmap is used
     * as it is: an ImageView draws it hardware-accelerated, so even a HARDWARE-backed one - which
     * a software canvas could not have touched - is fine here.
     */
    private static Bitmap artBitmap(MediaMetadata meta) {
        if (meta == null) {
            return null;
        }
        try {
            Bitmap art = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (art == null) {
                art = meta.getBitmap(MediaMetadata.METADATA_KEY_ART);
            }
            if (art == null) {
                // What the notification shade falls back to, and some apps set only this.
                art = meta.getDescription() != null
                        ? meta.getDescription().getIconBitmap() : null;
            }
            return art != null && !art.isRecycled() ? art : null;
        } catch (Throwable t) {
            L.d("sound: no readable album art (" + t + ")");
            return null;
        }
    }

    /** Which app this is, small, in the corner - the card's own label. */
    private static View appPill(Context ctx, PackageManager pm, MediaController controller) {
        LinearLayout pill = new LinearLayout(ctx);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        int padH = Ui.dp(ctx, 8);
        int padV = Ui.dp(ctx, 3);
        pill.setPadding(padH, padV, padH, padV);
        pill.setBackground(Ui.roundRect(0x33FFFFFF, Ui.dp(ctx, 12)));

        ImageView icon = new ImageView(ctx);
        try {
            icon.setImageDrawable(pm.getApplicationIcon(controller.getPackageName()));
        } catch (Throwable ignored) {
            icon.setImageDrawable(TrayIcons.volume(Ui.COLOR_TEXT));
        }
        int size = Ui.dp(ctx, 14);
        pill.addView(icon, new LinearLayout.LayoutParams(size, size));

        TextView label = new TextView(ctx);
        label.setText(appName(pm, controller.getPackageName()));
        label.setTextColor(Ui.COLOR_TEXT);
        label.setTextSize(10);
        label.setSingleLine(true);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        llp.leftMargin = Ui.dp(ctx, 5);
        pill.addView(label, llp);

        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        pill.setLayoutParams(plp);
        return pill;
    }

    /**
     * One transport button.
     *
     * <p>It does not repaint itself afterwards. The card is rebuilt when the app reports its new
     * state, which is the only moment the button can be redrawn honestly - an earlier version
     * waited a guessed 250ms and redrew whatever it found, which was usually the old state.
     */
    private static View transport(Context ctx, Drawable icon, int sizeDp, boolean filled,
            Runnable action) {
        ImageView button = new ImageView(ctx);
        button.setImageDrawable(icon);
        int size = Ui.dp(ctx, sizeDp);
        int inset = Ui.dp(ctx, filled ? 11 : 6);
        button.setPadding(inset, inset, inset, inset);
        button.setBackground(filled
                ? Ui.ripple(ctx, 0x40FFFFFF, size / 2)
                : Ui.ripple(ctx, 0x00000000, size / 2));
        button.setOnClickListener(v -> {
            try {
                action.run();
            } catch (Throwable t) {
                L.d("sound: transport control refused (" + t + ")");
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.leftMargin = Ui.dp(ctx, 4);
        button.setLayoutParams(lp);
        return button;
    }

    /**
     * The position line along the bottom of a card.
     *
     * <p>A playback state carries where the track was at a moment in the past, not where it is
     * now, so the position is worked forward from that moment at the reported speed. It repaints
     * once a second and only while it is on screen.
     */
    private static final class SeekLine extends View {

        private final MediaController mController;
        private final long mDuration;
        private final Paint mTrack = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Runnable mTick = new Runnable() {
            @Override
            public void run() {
                invalidate();
                postDelayed(this, 1000L);
            }
        };

        SeekLine(Context ctx, MediaController controller, long duration) {
            super(ctx);
            mController = controller;
            mDuration = duration;
            mTrack.setColor(0x40FFFFFF);
            mFill.setColor(0xCCFFFFFF);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            mTick.run();
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            removeCallbacks(mTick);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) {
                return;
            }
            float r = h / 2f;
            canvas.drawRoundRect(new RectF(0, 0, w, h), r, r, mTrack);
            float fraction = fraction();
            if (fraction > 0) {
                canvas.drawRoundRect(new RectF(0, 0, Math.max(h, w * fraction), h), r, r, mFill);
            }
        }

        private float fraction() {
            try {
                PlaybackState state = mController.getPlaybackState();
                if (state == null) {
                    return 0f;
                }
                long position = state.getPosition();
                if (state.getState() == PlaybackState.STATE_PLAYING) {
                    long since = SystemClock.elapsedRealtime() - state.getLastPositionUpdateTime();
                    position += (long) (since * state.getPlaybackSpeed());
                }
                return Math.max(0f, Math.min(1f, position / (float) mDuration));
            } catch (Throwable t) {
                return 0f;
            }
        }
    }

    // --- streams ---------------------------------------------------------

    private static View stream(Context ctx, AudioManager am, int stream, String label,
            Drawable icon) {
        int max = safeMax(am, stream);
        int now = safeVolume(am, stream);
        return slider(ctx, icon, label, max, now, value -> {
            try {
                am.setStreamVolume(stream, value, 0);
            } catch (SecurityException e) {
                // Do Not Disturb locks the ringer stream for apps without notification policy
                // access; nothing is broken, that stream just cannot move right now.
                L.d("sound: " + label + " is locked by Do Not Disturb");
            } catch (Throwable t) {
                L.d("sound: could not set " + label + " (" + t + ")");
            }
        });
    }

    private static int safeMax(AudioManager am, int stream) {
        try {
            return Math.max(1, am.getStreamMaxVolume(stream));
        } catch (Throwable t) {
            return 1;
        }
    }

    private static int safeVolume(AudioManager am, int stream) {
        try {
            return am.getStreamVolume(stream);
        } catch (Throwable t) {
            return 0;
        }
    }

    // --- per app ---------------------------------------------------------

    /**
     * A slider per app that is currently playing, where the platform will list them.
     *
     * <p>{@code getActiveSessions(null)} is the privileged form. It throws {@link
     * SecurityException} unless the caller holds {@code MEDIA_CONTENT_CONTROL}, and there is no
     * way to ask in advance, so the refusal is the test.
     */
    private static void addPerApp(Context ctx, LinearLayout body,
            List<MediaController> controllers) {
        if (controllers.isEmpty()) {
            return;
        }
        PackageManager pm = ctx.getPackageManager();
        boolean headed = false;
        int local = 0;
        for (MediaController controller : controllers) {
            MediaController.PlaybackInfo info;
            try {
                info = controller.getPlaybackInfo();
            } catch (Throwable t) {
                continue;
            }
            if (info == null || info.getMaxVolume() <= 0) {
                continue;
            }
            if (info.getPlaybackType() != MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE) {
                // A local session's "volume" is the media stream itself. A slider here would
                // look per-app and silently move every app at once, which is worse than not
                // offering one - Android has no per-app volume for local playback.
                local++;
                continue;
            }
            if (!headed) {
                body.addView(QuickPanel.sectionLabel(ctx, "App volume"));
                headed = true;
            }
            body.addView(slider(ctx, null, appName(pm, controller.getPackageName()),
                    info.getMaxVolume(), info.getCurrentVolume(), value -> {
                        try {
                            controller.setVolumeTo(value, 0);
                        } catch (Throwable t) {
                            L.d("sound: " + controller.getPackageName() + " refused a volume ("
                                    + t + ")");
                        }
                    }));
        }
        if (local > 0 && !headed) {
            L.i("sound: " + local + " app(s) playing locally - Android gives local playback no "
                    + "volume of its own, so only the Media slider moves them");
        }
    }

    private static List<MediaController> sessions(Context ctx) {
        try {
            MediaSessionManager msm =
                    (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (msm == null) {
                return new ArrayList<>();
            }
            List<MediaController> found = msm.getActiveSessions(null);
            if (found == null) {
                return new ArrayList<>();
            }
            L.i("sound: " + found.size() + " media session(s) readable");
            return found;
        } catch (SecurityException e) {
            L.i("sound: per-app volume unavailable - the launcher may not list media sessions "
                    + "(needs MEDIA_CONTENT_CONTROL or a notification listener). Global sliders "
                    + "only.");
            return new ArrayList<>();
        } catch (Throwable t) {
            L.d("sound: media sessions unreadable (" + t + ")");
            return new ArrayList<>();
        }
    }

    private static String appName(PackageManager pm, String pkg) {
        if (pkg == null) {
            return "App";
        }
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Throwable t) {
            return pkg;
        }
    }

    // --- widgets ---------------------------------------------------------

    private interface OnValue {
        void set(int value);
    }

    private static View slider(Context ctx, Drawable icon, String label, int max, int value,
            OnValue onValue) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Ui.dp(ctx, 6);
        row.setPadding(pad, pad, pad, pad);

        row.addView(leading(ctx, icon));

        LinearLayout column = new LinearLayout(ctx);
        column.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        clp.leftMargin = Ui.dp(ctx, 10);
        row.addView(column, clp);

        TextView caption = new TextView(ctx);
        caption.setText(label);
        caption.setTextColor(Ui.COLOR_TEXT_DIM);
        caption.setTextSize(11);
        column.addView(caption);

        SeekBar bar = new SeekBar(ctx);
        bar.setMax(max);
        bar.setProgress(Math.max(0, Math.min(max, value)));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    onValue.set(progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                // A rebuild while this is being dragged would replace the slider under the
                // finger holding it.
                QuickPanel.setInteracting(true);
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                QuickPanel.setInteracting(false);
            }
        });
        column.addView(bar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return row;
    }

    /** The icon slot, kept even when empty so every row's text starts on the same line. */
    private static View leading(Context ctx, Drawable icon) {
        int size = Ui.dp(ctx, 20);
        if (icon == null) {
            View spacer = new View(ctx);
            spacer.setLayoutParams(new LinearLayout.LayoutParams(size, size));
            return spacer;
        }
        ImageView iv = new ImageView(ctx);
        iv.setImageDrawable(icon);
        iv.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        return iv;
    }
}
