package io.freemytv;

import android.animation.ValueAnimator;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.media.AudioManager;
import android.text.TextPaint;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;

/**
 * The volume, in the quick panel's look: a slim glass bar at the right edge, filling up from the bottom, with the level
 * on top; it fades away 1.5 s after the last change. On the HY300, in place of the board's big round dialog
 * (com.droidlogic/.tvvolume.VolumeService, turned off: the system starts it on every volume key). Drawn by
 * RemoteKeyService, which is always there and may draw over every app; it only reacts to volume changes.
 */
final class VolumeBar extends View {
    private static final String VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION",
            MUTE_CHANGED = "android.media.STREAM_MUTE_CHANGED_ACTION";
    private static final long SHOWN_MS = 1500;

    private static VolumeBar view;
    private static BroadcastReceiver receiver;

    private final AudioManager audio;
    private final float k;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint number;
    private final RectF r = new RectF();
    private float alpha, level;
    private boolean muted;
    private ValueAnimator fade, slide;
    private final Runnable hide = this::fadeOut;

    /** From RemoteKeyService.onServiceConnected: listens for volume changes (HY300 only). */
    static void start(Context c) {
        if (!RemoteKeys.HY300 || receiver != null) return;
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent i) {
                // Both name their stream the same way; only the media one (the remote's keys) shows.
                if (i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1) == AudioManager.STREAM_MUSIC) show(ctx);
            }
        };
        IntentFilter f = new IntentFilter(VOLUME_CHANGED);
        f.addAction(MUTE_CHANGED);
        c.registerReceiver(receiver, f);
    }

    static void stop(Context c) {
        if (receiver != null) {
            try {
                c.unregisterReceiver(receiver);
            } catch (RuntimeException ignored) {
            }
            receiver = null;
        }
        if (view != null) {
            try {
                ((WindowManager) c.getSystemService(Context.WINDOW_SERVICE)).removeView(view);
            } catch (RuntimeException ignored) {
            }
            view = null;
        }
    }

    private static void show(Context c) {
        if (view == null) {
            Ui.init(c);
            view = new VolumeBar(c);
            float k = view.k;
            WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                    Math.round(76 * k), Math.round(400 * k),
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT);
            p.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
            p.x = Math.round(48 * k);
            try {
                ((WindowManager) c.getSystemService(Context.WINDOW_SERVICE)).addView(view, p);
            } catch (RuntimeException e) {
                AppLog.w("volume bar: " + e.getMessage());
                view = null;
                return;
            }
        }
        view.update();
    }

    private VolumeBar(Context c) {
        super(c);
        audio = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        k = c.getResources().getDisplayMetrics().heightPixels / 1080f;
        number = new TextPaint(Ui.numbers(Ui.medium, 26 * k, 0xFFFFFFFF));
        number.setTextAlign(Paint.Align.CENTER);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(1.5f * k);
    }

    /** The level now: the bar glides to it, shows, and is set to fade. */
    private void update() {
        int max = Math.max(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        muted = audio.isStreamMute(AudioManager.STREAM_MUSIC);
        float target = muted ? 0 : audio.getStreamVolume(AudioManager.STREAM_MUSIC) / (float) max;
        if (slide != null) slide.cancel();
        if (alpha < 0.05f) {
            level = target;   // appearing: already at the level
        } else {
            slide = ValueAnimator.ofFloat(level, target);
            slide.setDuration(140);
            slide.setInterpolator(new DecelerateInterpolator());
            slide.addUpdateListener(a -> {
                level = (float) a.getAnimatedValue();
                invalidate();
            });
            slide.start();
        }
        animateAlpha(1, 160);
        removeCallbacks(hide);
        postDelayed(hide, SHOWN_MS);
        invalidate();
    }

    private void fadeOut() {
        animateAlpha(0, 320);
    }

    private void animateAlpha(float to, long ms) {
        if (fade != null) fade.cancel();
        fade = ValueAnimator.ofFloat(alpha, to);
        fade.setDuration(ms);
        fade.addUpdateListener(a -> {
            alpha = (float) a.getAnimatedValue();
            invalidate();
        });
        fade.start();
    }

    @Override
    protected void onDraw(Canvas c) {
        if (alpha <= 0.01f) return;
        float w = getWidth(), h = getHeight(), rad = w / 2;
        // The glass, as the quick panel's.
        r.set(0, 0, w, h);
        fill.setColor(Ui.alpha(0xF01B1B1E, alpha));
        c.drawRoundRect(r, rad, rad, fill);
        rim.setColor(Ui.alpha(0x1FFFFFFF, alpha));
        c.drawRoundRect(r, rad, rad, rim);
        // The level on top, the track below it, the speaker at the foot.
        int pct = Math.round(level * 100);
        number.setAlpha(Math.round(255 * alpha));
        c.drawText(muted ? "—" : String.valueOf(pct), w / 2, 46 * k, number);
        float tw = 14 * k, top = 70 * k, bottom = h - 74 * k;
        r.set(w / 2 - tw / 2, top, w / 2 + tw / 2, bottom);
        fill.setColor(Ui.alpha(0x26FFFFFF, alpha));
        c.drawRoundRect(r, tw / 2, tw / 2, fill);
        float filled = (bottom - top) * level;
        if (filled > 0.5f) {
            r.set(w / 2 - tw / 2, bottom - Math.max(tw, filled), w / 2 + tw / 2, bottom);
            fill.setColor(Ui.alpha(0xFFF5F5F7, alpha));
            c.drawRoundRect(r, tw / 2, tw / 2, fill);
        }
        Symbols.draw(getContext(), c, "speaker", w / 2, h - 38 * k, 26 * k, Ui.alpha(muted ? 0x66FFFFFF : 0xD9FFFFFF, alpha));
    }
}
