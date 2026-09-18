package com.zuxos.desktopplus.hook;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
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
 * Volume, brightness, and volume per app.
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

    /** Adds the volume sliders, the per-app ones where the platform allows it, and brightness. */
    public static void addTo(Context ctx, LinearLayout body, int displayId) {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            body.addView(stream(ctx, am, AudioManager.STREAM_MUSIC, "Media",
                    TrayIcons.volume(Ui.COLOR_TEXT)));
            body.addView(stream(ctx, am, AudioManager.STREAM_RING, "Ringtone", null));
            body.addView(stream(ctx, am, AudioManager.STREAM_ALARM, "Alarm", null));
        }
        addBrightness(ctx, body, displayId);
        addPerApp(ctx, body);
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

    // --- brightness ------------------------------------------------------

    private static void addBrightness(Context ctx, LinearLayout body, int displayId) {
        if (!canWriteSettings(ctx)) {
            // Writing it needs WRITE_SETTINGS. Rather than show a slider that does nothing,
            // offer the screen that can.
            body.addView(link(ctx, TrayIcons.brightness(Ui.COLOR_TEXT), "Brightness",
                    () -> QuickTiles.open(ctx, Settings.ACTION_DISPLAY_SETTINGS, displayId)));
            return;
        }
        int now = QuickTiles.readSystem(ctx, Settings.System.SCREEN_BRIGHTNESS, 128);
        body.addView(slider(ctx, TrayIcons.brightness(Ui.COLOR_TEXT), "Brightness", 255,
                Math.max(1, now), value ->
                        QuickTiles.putSystem(ctx, Settings.System.SCREEN_BRIGHTNESS,
                                Math.max(1, value))));
    }

    private static boolean canWriteSettings(Context ctx) {
        try {
            return Settings.System.canWrite(ctx);
        } catch (Throwable t) {
            return false;
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
    private static void addPerApp(Context ctx, LinearLayout body) {
        List<MediaController> controllers = sessions(ctx);
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
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        column.addView(bar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private static View link(Context ctx, Drawable icon, String label, Runnable action) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Ui.dp(ctx, 10);
        row.setPadding(pad, pad, pad, pad);
        row.setBackground(Ui.ripple(ctx, 0x00000000, Ui.dp(ctx, 12)));
        row.addView(leading(ctx, icon));

        TextView caption = new TextView(ctx);
        caption.setText(label);
        caption.setTextColor(Ui.COLOR_TEXT);
        caption.setTextSize(14);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        clp.leftMargin = Ui.dp(ctx, 10);
        row.addView(caption, clp);

        row.setOnClickListener(v -> {
            QuickPanel.dismiss();
            action.run();
        });
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
