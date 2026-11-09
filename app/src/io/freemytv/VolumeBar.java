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
 * The volume, as on Google TV: a slim smoked pane at the right edge with the level on top, a hairline filling up
 * from the bottom and the speaker at its foot; it fades away 1.5 s after the last change. On the HY300, in place of
 * the board's big round dialog (com.droidlogic/.tvvolume.VolumeService, turned off: the system starts it on every
 * volume key). Drawn by RemoteKeyService, which is always there and may draw over every app; it only reacts to
 * volume changes.
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
    private final android.graphics.Path speaker = new android.graphics.Path();
    private float alpha, level;
    /** The level drawn on the last press, and when, so its broadcast doesn't redraw it. */
    private float expected = -1;
    private long expectedAt;
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

    /**
     * A volume key held down (its nth repeat): steps on top of the system's one, more the longer it's held, so
     * 0 to 100 takes a couple of seconds instead of a hundred presses.
     */
    static void boost(Context c, boolean up, int repeat) {
        int extra = Math.min(5, 1 + repeat / 2);
        if (extra == 0) return;
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        int now = am.getStreamVolume(AudioManager.STREAM_MUSIC), max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int to = Math.max(0, Math.min(max, now + (up ? extra : -extra)));
        if (to != now) am.setStreamVolume(AudioManager.STREAM_MUSIC, to, 0);   // the change shows the bar
    }

    static void show(Context c) {
        show(c, 0);
    }

    /**
     * Shows the bar `step` volume units away from now: the remote's key reaches us before the system applies it, and
     * the change's broadcast came a beat later still, so the bar lagged a step behind. Now it moves on the press itself
     * and the broadcast only confirms.
     */
    static void show(Context c, int step) {
        if (view == null) {
            Ui.init(c);
            view = new VolumeBar(c);
            float k = view.k;
            WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                    Math.round(96 * k), Math.round(500 * k),
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT);
            p.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
            p.x = Math.round(64 * k);
            try {
                ((WindowManager) c.getSystemService(Context.WINDOW_SERVICE)).addView(view, p);
            } catch (RuntimeException e) {
                AppLog.w("volume bar: " + e.getMessage());
                view = null;
                return;
            }
        }
        view.update(step);
    }

    private VolumeBar(Context c) {
        super(c);
        audio = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        k = c.getResources().getDisplayMetrics().heightPixels / 1080f;
        number = new TextPaint(Ui.numbers(Ui.semibold, 34 * k, 0xFFFFFFFF));
        number.setTextAlign(Paint.Align.CENTER);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(1.5f * k);
    }

    /** The level now: the bar glides to it, shows, and is set to fade. */
    private void update(int step) {
        int max = Math.max(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        muted = audio.isStreamMute(AudioManager.STREAM_MUSIC) && step <= 0;   // a press up unmutes
        int now = Math.max(0, Math.min(max, audio.getStreamVolume(AudioManager.STREAM_MUSIC) + step));
        float target = muted ? 0 : now / (float) max;
        if (step == 0 && expected >= 0 && android.os.SystemClock.uptimeMillis() - expectedAt < 400
                && Math.abs(target - expected) < 0.5f / max) {
            return;   // the broadcast of the change already drawn on the press
        }
        if (step != 0) {
            expected = target;
            expectedAt = android.os.SystemClock.uptimeMillis();
        }
        if (slide != null) slide.cancel();
        if (alpha < 0.05f) {
            level = target;   // appearing: already at the level
        } else {
            slide = ValueAnimator.ofFloat(level, target);
            slide.setDuration(80);
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
        float w = getWidth(), h = getHeight(), rad = 12 * k;
        // A smoked pane with softly rounded corners, the picture showing through it.
        r.set(0, 0, w, h);
        fill.setColor(Ui.alpha(0xA6141416, alpha));
        c.drawRoundRect(r, rad, rad, fill);
        rim.setColor(Ui.alpha(0x14FFFFFF, alpha));
        c.drawRoundRect(r, rad, rad, rim);
        // The level on top, a hairline track below it, the speaker at the foot.
        int pct = Math.round(level * 100);
        boolean silent = muted || pct == 0;   // grey number, the speaker crossed out
        number.setColor(Ui.alpha(silent ? 0x99FFFFFF : 0xFFFFFFFF, alpha));
        c.drawText(silent ? "0" : String.valueOf(pct), w / 2, 62 * k, number);
        float tw = 6 * k, top = 104 * k, bottom = h - 104 * k;
        r.set(w / 2 - tw / 2, top, w / 2 + tw / 2, bottom);
        fill.setColor(Ui.alpha(0x40FFFFFF, alpha));
        c.drawRoundRect(r, tw / 2, tw / 2, fill);
        float filled = (bottom - top) * level;
        if (filled > 0.5f) {
            r.set(w / 2 - tw / 2, bottom - Math.max(tw, filled), w / 2 + tw / 2, bottom);
            fill.setColor(Ui.alpha(0xFFFFFFFF, alpha));
            c.drawRoundRect(r, tw / 2, tw / 2, fill);
        }
        drawSpeaker(c, w / 2, h - 54 * k, Ui.alpha(silent ? 0xB3FFFFFF : 0xF2FFFFFF, alpha), silent);
    }

    /** The speaker: a box and its cone, filled, with one sound wave, or a small cross when silent. */
    private void drawSpeaker(Canvas c, float cx, float cy, int color, boolean silent) {
        float s = 15 * k;
        speaker.reset();
        speaker.moveTo(cx - s, cy - s * 0.42f);
        speaker.lineTo(cx - s * 0.45f, cy - s * 0.42f);
        speaker.lineTo(cx + s * 0.15f, cy - s);
        speaker.lineTo(cx + s * 0.15f, cy + s);
        speaker.lineTo(cx - s * 0.45f, cy + s * 0.42f);
        speaker.lineTo(cx - s, cy + s * 0.42f);
        speaker.close();
        fill.setColor(color);
        c.drawPath(speaker, fill);
        rim.setColor(color);
        rim.setStrokeWidth(3 * k);
        if (silent) {
            float x = cx + s * 0.75f, d = s * 0.38f;
            c.drawLine(x - d, cy - d, x + d, cy + d, rim);
            c.drawLine(x - d, cy + d, x + d, cy - d, rim);
        } else {
            r.set(cx - s * 0.35f, cy - s * 0.6f, cx + s * 0.85f, cy + s * 0.6f);
            c.drawArc(r, -55, 110, false, rim);
        }
        rim.setStrokeWidth(1.5f * k);
    }
}
