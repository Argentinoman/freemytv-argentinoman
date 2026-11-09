package io.freemytv;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.VideoView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Free My TV's video and music player, in place of the board's media centre (Rockchip's, turned off with the switch):
 * the file full screen on black, Android's own decoders (VideoView). OK pauses, ← → go back and forward 10 s (held,
 * faster), ↑ ↓ the previous and next file of its folder, Back leaves. The bar (name, progress, time) shows for a few
 * seconds on any key and stays while paused; a song keeps its name and a speaker on screen. Opened by the explorer with
 * a file, or by other apps with a video or a song (ACTION_VIEW).
 */
public class PlayerActivity extends Activity {
    static final String EXTRA_PATH = "path";

    private VideoView video;
    private Bar bar;
    private final Handler main = new Handler(Looper.getMainLooper());
    /** The folder's videos and songs (just the one when another app sent it), and which is playing. */
    private final List<File> files = new ArrayList<>();
    private int index;
    private Uri single;
    private String title = "";
    private boolean audioOnly, failed;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Ui.init(this);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        video = new VideoView(this);
        video.setKeepScreenOn(true);   // no screensaver or sleep in the middle of a film
        bar = new Bar(this);
        root.addView(video, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        root.addView(bar, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
        video.setOnPreparedListener(mp -> {
            audioOnly = mp.getVideoWidth() == 0 || mp.getVideoHeight() == 0;
            failed = false;
            video.start();
            bar.show();
        });
        video.setOnCompletionListener(mp -> {
            if (single == null && index < files.size() - 1) play(1);   // on to the next file of the folder
            else finish();
        });
        video.setOnErrorListener((mp, what, extra) -> {
            AppLog.w("player: error " + what + "/" + extra + " on " + title);
            failed = true;
            bar.show();
            return true;   // no system dialog: our message instead
        });
        open(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        open(intent);
    }

    @Override
    protected void onStop() {
        super.onStop();
        video.stopPlayback();   // the decoder and the memory go with the screen (1 GB boards)
        finish();
    }

    private void open(Intent intent) {
        files.clear();
        single = null;
        String path = intent.getStringExtra(EXTRA_PATH);
        Uri data = intent.getData();
        if (path == null && data != null && "file".equals(data.getScheme())) path = data.getPath();
        // A content:// link that carries a readable file's path (our explorer's): then the whole folder plays on.
        if (path == null && data != null && "content".equals(data.getScheme()) && data.getPath() != null
                && new File(data.getPath()).canRead()) path = data.getPath();
        if (path != null) {
            File f = new File(path);
            File[] all = f.getParentFile() == null ? null : f.getParentFile().listFiles();
            if (all != null) {
                for (File o : all) if (o.isFile() && !o.getName().startsWith(".") && isMedia(o)) files.add(o);
                Collections.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            }
            index = Math.max(0, files.indexOf(f));
            if (files.isEmpty()) files.add(f);
        } else if (data != null) {
            single = data;
        } else {
            finish();
            return;
        }
        play(0);
    }

    /** A video or a song, by its extension (what the explorer sends here). */
    static boolean isMedia(File f) {
        String type = FilesProvider.type(f);
        return type.startsWith("video/") || type.startsWith("audio/");
    }

    private void play(int step) {
        if (single == null) {
            if (files.isEmpty()) return;
            index = Math.max(0, Math.min(files.size() - 1, index + step));
            File f = files.get(index);
            title = f.getName();
            int dot = title.lastIndexOf('.');
            if (dot > 0) title = title.substring(0, dot);
            video.setVideoPath(f.getPath());
        } else {
            String last = single.getLastPathSegment();
            title = last == null ? "" : Uri.decode(last);
            video.setVideoURI(single);
        }
        audioOnly = false;
        failed = false;
        bar.show();
    }

    /** ← → by 10 s; held, the steps grow (30 s, then a minute). */
    private void seek(boolean forward, int repeat) {
        if (failed || !video.canSeekForward() && forward || !video.canSeekBackward() && !forward) return;
        int step = repeat < 5 ? 10_000 : repeat < 20 ? 30_000 : 60_000;
        int to = video.getCurrentPosition() + (forward ? step : -step);
        int length = video.getDuration();
        if (length > 0) to = Math.min(to, length - 1000);
        video.seekTo(Math.max(0, to));
        bar.show();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP) finish();
            return true;
        }
        if (e.getAction() != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(e);
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_SPACE:
                if (e.getRepeatCount() > 0) return true;
                if (failed) return true;
                if (video.isPlaying()) video.pause();
                else video.start();
                bar.show();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                video.start();
                bar.show();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                video.pause();
                bar.show();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                seek(false, e.getRepeatCount());
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                seek(true, e.getRepeatCount());
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                if (e.getRepeatCount() == 0 && single == null && index > 0) play(-1);
                else bar.show();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                if (e.getRepeatCount() == 0 && single == null && index < files.size() - 1) play(1);
                else bar.show();
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    private static String time(int ms) {
        int s = Math.max(0, ms / 1000);
        return s >= 3600 ? String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                : String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    /**
     * The bar at the bottom: name, the place in the folder, a progress line and the time, over a dark fade. It fades
     * out 3 s after the last key while playing; paused, failed or a song, it stays.
     */
    private final class Bar extends View {
        private final float k;
        private final TextPaint name, small, smallCenter, big;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), shade = new Paint();
        private float shadeTop;
        private final RectF r = new RectF();
        private float alpha = 1;
        private ValueAnimator fade;
        private final Runnable tick = new Runnable() {
            @Override public void run() {
                invalidate();
                main.postDelayed(this, 500);
            }
        };

        Bar(Context c) {
            super(c);
            k = c.getResources().getDisplayMetrics().heightPixels / 1080f;
            name = new TextPaint(Ui.text(Ui.medium, 40 * k, 0xFFFFFFFF));
            small = new TextPaint(Ui.text(Ui.regular, 26 * k, 0xB3FFFFFF));
            big = new TextPaint(Ui.text(Ui.light, 52 * k, 0xFFFFFFFF));
            big.setTextAlign(Paint.Align.CENTER);
            smallCenter = new TextPaint(small);
            smallCenter.setTextAlign(Paint.Align.CENTER);
        }

        @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
            shadeTop = h - 260 * k;
            shade.setShader(new android.graphics.LinearGradient(0, shadeTop, 0, h, 0x00000000, 0xD9000000,
                    android.graphics.Shader.TileMode.CLAMP));
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            main.post(tick);
        }

        @Override protected void onDetachedFromWindow() {
            main.removeCallbacks(tick);
            super.onDetachedFromWindow();
        }

        void show() {
            if (fade != null) fade.cancel();
            alpha = 1;
            invalidate();
            if (failed || audioOnly || !video.isPlaying()) return;
            fade = ValueAnimator.ofFloat(1, 0);
            fade.setStartDelay(3000);
            fade.setDuration(500);
            fade.addUpdateListener(a -> {
                alpha = (float) a.getAnimatedValue();
                invalidate();
            });
            fade.start();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            if (failed) {
                Symbols.draw(getContext(), c, "play", w / 2, h / 2 - 90 * k, 60 * k, 0x66FFFFFF);
                c.drawText("No se puede reproducir este archivo", w / 2, h / 2 + 10 * k, big);
                c.drawText(TextUtils.ellipsize(title, small, w * 0.8f, TextUtils.TruncateAt.MIDDLE).toString(),
                        w / 2, h / 2 + 60 * k, smallCenter);
                return;
            }
            if (audioOnly) {   // a song: its name and a speaker in the middle, always
                Symbols.draw(getContext(), c, "speaker", w / 2, h / 2 - 110 * k, 90 * k, 0x99FFFFFF);
                c.drawText(TextUtils.ellipsize(title, big, w * 0.8f, TextUtils.TruncateAt.END).toString(), w / 2, h / 2 + 10 * k, big);
            }
            if (alpha <= 0.01f) return;
            // A dark fade under the bar, so white text reads over a bright picture.
            shade.setAlpha(Math.round(255 * alpha));
            c.drawRect(0, shadeTop, w, h, shade);
            float x = 80 * k, right = w - 80 * k, base = h - 150 * k;
            int a = Math.round(255 * alpha);
            // Play or pause, then the name.
            boolean playing = video.isPlaying();
            fill.setColor(Ui.alpha(0xFFFFFFFF, alpha));
            if (playing) {
                Symbols.draw(getContext(), c, "play", x + 18 * k, base - 14 * k, 30 * k, Ui.alpha(0xFFFFFFFF, alpha));
            } else {
                r.set(x + 4 * k, base - 36 * k, x + 14 * k, base + 6 * k);
                c.drawRoundRect(r, 3 * k, 3 * k, fill);
                r.offset(18 * k, 0);
                c.drawRoundRect(r, 3 * k, 3 * k, fill);
            }
            name.setAlpha(a);
            float nx = x + 56 * k;
            String place = single == null && files.size() > 1 ? (index + 1) + " de " + files.size() : "";
            float placeW = place.isEmpty() ? 0 : small.measureText(place) + 30 * k;
            c.drawText(TextUtils.ellipsize(title, name, right - nx - placeW, TextUtils.TruncateAt.END).toString(), nx, base, name);
            small.setAlpha(Math.round(0xB3 * alpha));
            if (!place.isEmpty()) {
                small.setTextAlign(Paint.Align.RIGHT);
                c.drawText(place, right, base, small);
                small.setTextAlign(Paint.Align.LEFT);
            }
            // The progress line and the time under it.
            int pos = video.getCurrentPosition(), length = video.getDuration();
            float ly = base + 44 * k, lh = 6 * k;
            r.set(x, ly, right, ly + lh);
            fill.setColor(Ui.alpha(0x40FFFFFF, alpha));
            c.drawRoundRect(r, lh / 2, lh / 2, fill);
            if (length > 0) {
                r.right = x + (right - x) * Math.min(1f, pos / (float) length);
                fill.setColor(Ui.alpha(0xFFFFFFFF, alpha));
                c.drawRoundRect(r, lh / 2, lh / 2, fill);
            }
            c.drawText(time(pos), x, ly + 50 * k, small);
            if (length > 0) {
                small.setTextAlign(Paint.Align.RIGHT);
                c.drawText(time(length), right, ly + 50 * k, small);
                small.setTextAlign(Paint.Align.LEFT);
            }
        }
    }
}
