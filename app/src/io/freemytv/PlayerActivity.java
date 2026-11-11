package io.freemytv;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.MimeTypeMap;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.VideoView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Free My TV's video and music player, in place of the board's media centre (Rockchip's, turned off with the switch):
 * the file full screen on black, Android's own decoders (VideoView). OK pauses, ← → go back and forward 10 s (held,
 * faster), ↑ ↓ the previous and next file of its folder, Back leaves. The bar (name, progress, time) shows for a few
 * seconds on any key and stays while paused; a song keeps its name and a speaker on screen. Opened by the explorer with
 * a file, by other apps with a video or a song (ACTION_VIEW), or by {@link Dlna} with a link from a phone or a computer
 * (then also a photo, and it reports what it does so the controller can follow).
 */
public class PlayerActivity extends Activity {
    static final String EXTRA_PATH = "path";

    private VideoView video;
    private ImageView photo;
    private Bar bar;
    private final Handler main = new Handler(Looper.getMainLooper());
    /** The folder's videos and songs (just the one when another app sent it), and which is playing. */
    private final List<File> files = new ArrayList<>();
    private int index;
    private Uri single;
    private String title = "";
    private boolean audioOnly, failed, image;
    /** The picture's own size (the file's, not the screen's), shown by the remote's Info key until `infoUntil`. */
    private int videoW, videoH;
    private long infoUntil;
    /** The DLNA controller's file number (0: not from DLNA). */
    private int session;
    private int loading;   // which photo download is current
    /**
     * A live stream from a server that starts from any second asked (DLNA time seek, e.g. TV Caster's transcode): no
     * bytes to jump to, so a jump opens it again from that second. `offset` is where the open stream starts, `known`
     * the length the controller said, `pending` the point chosen while ← → are pressed (-1: none).
     */
    private boolean timeSeek;
    private int offset, known, pending = -1;
    private final Runnable jump = () -> {
        if (pending >= 0) reopenAt(pending);
    };

    private final BroadcastReceiver commands = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (session == 0 || i.getIntExtra(Dlna.EXTRA_SESSION, -1) != session) return;
            String cmd = String.valueOf(i.getStringExtra("cmd"));
            switch (cmd) {
                case "play":
                    if (!image && !failed) video.start();
                    break;
                case "pause":
                    if (!image) video.pause();
                    break;
                case "seek":
                    if (image) break;
                    if (byTime()) reopenAt(Math.max(0, i.getIntExtra("ms", 0)));
                    else if (!failed) video.seekTo(Math.max(0, i.getIntExtra("ms", 0)));
                    break;
                case "stop":
                    report("STOPPED", false, false);
                    session = 0;
                    finish();
                    return;
                default:
                    return;
            }
            bar.show();
            reportNow();
        }
    };

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Ui.init(this);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        video = new VideoView(this);
        video.setKeepScreenOn(true);   // no screensaver or sleep in the middle of a film
        photo = new ImageView(this);
        photo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        photo.setKeepScreenOn(true);
        photo.setVisibility(View.GONE);
        bar = new Bar(this);
        root.addView(video, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        root.addView(photo, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(bar, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
        video.setOnPreparedListener(mp -> {
            audioOnly = mp.getVideoWidth() == 0 || mp.getVideoHeight() == 0;
            videoW = mp.getVideoWidth();
            videoH = mp.getVideoHeight();
            mp.setOnVideoSizeChangedListener((m, vw, vh) -> {   // adaptive streams change it on the way
                videoW = vw;
                videoH = vh;
            });
            failed = false;
            video.start();
            bar.show();
            reportNow();
        });
        video.setOnCompletionListener(mp -> {
            if (single == null && index < files.size() - 1) {
                play(1);   // on to the next file of the folder
                return;
            }
            report("STOPPED", false, false);   // the controller sends the next one, if it has a list
            session = 0;
            finish();
        });
        video.setOnErrorListener((mp, what, extra) -> {
            AppLog.w("player: error " + what + "/" + extra + " on " + title);
            failed = true;
            bar.show();
            report("STOPPED", true, true);
            return true;   // no system dialog: our message instead
        });
        registerReceiver(commands, new IntentFilter(Dlna.CMD), Dlna.PERMISSION, null);
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
        main.removeCallbacks(jump);
        video.stopPlayback();   // the decoder and the memory go with the screen (1 GB boards)
        report("STOPPED", false, false);
        session = 0;
        finish();
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(commands);
        } catch (RuntimeException ignored) {
        }
        photo.setImageDrawable(null);
        super.onDestroy();
    }

    /** Tells Dlna what it's doing now (only for a file a controller sent). */
    private void report(String state, boolean open, boolean error) {
        if (session == 0) return;
        int pos = image || failed ? 0 : position();
        int dur = image || failed ? 0 : length();
        sendBroadcast(new Intent(Dlna.STATE).setPackage(getPackageName()).putExtra(Dlna.EXTRA_SESSION, session)
                .putExtra("state", state).putExtra("pos", pos).putExtra("dur", dur).putExtra("open", open)
                .putExtra("error", error), Dlna.PERMISSION);
    }

    private void reportNow() {
        if (failed) return;
        report(image || video.isPlaying() ? "PLAYING" : "PAUSED_PLAYBACK", true, false);
    }

    private void open(Intent intent) {
        int next = intent.getIntExtra(Dlna.EXTRA_SESSION, 0);
        if (session != 0 && session != next) report("STOPPED", false, false);   // the controller's file gave way
        session = next;
        files.clear();
        single = null;
        image = false;
        main.removeCallbacks(jump);
        timeSeek = session != 0 && intent.getBooleanExtra(Dlna.EXTRA_TIME_SEEK, false);
        known = session != 0 ? intent.getIntExtra(Dlna.EXTRA_DURATION, 0) : 0;
        offset = 0;
        pending = -1;
        if (session != 0 && intent.getData() != null) {
            single = intent.getData();
            String kind = intent.getStringExtra(Dlna.EXTRA_KIND);
            image = Dlna.KIND_IMAGE.equals(kind);
            String t = intent.getStringExtra(Dlna.EXTRA_TITLE);
            if (image) showPhoto(single, t == null ? "" : t);
            else play(0, t);
            return;
        }
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
        String type = mime(f.getName());
        return type.startsWith("video/") || type.startsWith("audio/");
    }

    /** A file's MIME type by its extension ("*" + "/*" when unknown). */
    static String mime(String name) {
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        String t = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        if (t == null && (ext.equals("mkv") || ext.equals("ts") || ext.equals("m2ts"))) t = "video/x-matroska";
        return t == null ? "*/*" : t;
    }

    /**
     * A photo from DLNA: downloaded to the cache, decoded at the screen's size (a phone's 12 MP photo whole would take
     * 48 MB) and turned upright by its EXIF.
     */
    private void showPhoto(Uri uri, String name) {
        video.stopPlayback();
        video.setVisibility(View.GONE);
        photo.setVisibility(View.VISIBLE);
        title = name;
        failed = false;
        audioOnly = false;
        bar.show();
        final int mine = ++loading;
        final int w = getResources().getDisplayMetrics().widthPixels, h = getResources().getDisplayMetrics().heightPixels;
        final File tmp = new File(getCacheDir(), "dlna-photo");
        final int[] size = new int[2];
        videoW = videoH = 0;
        new Thread(() -> {
            Bitmap b = null;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(uri.toString()).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(15000);
                try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[16384];
                    long total = 0;
                    for (int n; (n = in.read(buf)) > 0; ) {
                        out.write(buf, 0, n);
                        if ((total += n) > 64L << 20) throw new IOException("photo too big");
                    }
                } finally {
                    c.disconnect();
                }
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(tmp.getPath(), o);
                size[0] = o.outWidth;
                size[1] = o.outHeight;
                int sample = 1;
                while (o.outWidth / (sample * 2) >= w && o.outHeight / (sample * 2) >= h) sample *= 2;
                o.inJustDecodeBounds = false;
                o.inSampleSize = sample;
                b = BitmapFactory.decodeFile(tmp.getPath(), o);
                int turn = 0;
                switch (new ExifInterface(tmp.getPath()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    case ExifInterface.ORIENTATION_ROTATE_90: turn = 90; break;
                    case ExifInterface.ORIENTATION_ROTATE_180: turn = 180; break;
                    case ExifInterface.ORIENTATION_ROTATE_270: turn = 270; break;
                }
                if (turn == 90 || turn == 270) {   // upright, as it's shown
                    int t = size[0];
                    size[0] = size[1];
                    size[1] = t;
                }
                if (b != null && turn != 0) {
                    Matrix m = new Matrix();
                    m.postRotate(turn);
                    Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
                    if (r != b) b.recycle();
                    b = r;
                }
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                AppLog.w("player: photo " + e);
            } finally {
                tmp.delete();
            }
            final Bitmap shown = b;
            main.post(() -> {
                if (mine != loading || isFinishing()) return;
                if (shown == null) {
                    failed = true;
                    bar.show();
                    report("STOPPED", true, true);
                    return;
                }
                videoW = size[0];
                videoH = size[1];
                photo.setImageBitmap(shown);
                bar.show();
                reportNow();
            });
        }, "player-photo").start();
    }

    private void play(int step) {
        play(step, null);
    }

    private void play(int step, String name) {
        loading++;
        videoW = videoH = 0;
        photo.setVisibility(View.GONE);
        photo.setImageDrawable(null);
        video.setVisibility(View.VISIBLE);
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
            title = name != null && !name.isEmpty() ? name : last == null ? "" : Uri.decode(last);
            video.setVideoURI(single);
        }
        audioOnly = false;
        failed = false;
        bar.show();
    }

    /** Where the film is: the open stream's start plus how far it has played. */
    private int position() {
        return offset + Math.max(0, video.getCurrentPosition());
    }

    /** How long it is: the decoder's length, else the one the controller said. */
    private int length() {
        int d = video.getDuration();
        return d > 0 && !byTime() ? d : known;
    }

    /** Jumps go by opening the stream again (a live stream with a known length whose decoder can't seek). */
    private boolean byTime() {
        return timeSeek && known > 0 && video.getDuration() <= 0;
    }

    private void reopenAt(int ms) {
        main.removeCallbacks(jump);
        pending = -1;
        offset = Math.max(0, Math.min(ms, known - 5000));
        failed = false;
        video.setVideoURI(single.buildUpon().clearQuery().appendQueryParameter("t", String.valueOf(offset)).build());
        bar.show();
        report("TRANSITIONING", true, false);
    }

    /** ← → by 10 s; held, the steps grow (30 s, then a minute). */
    private void seek(boolean forward, int repeat) {
        int step = repeat < 5 ? 10_000 : repeat < 20 ? 30_000 : 60_000;
        if (!image && byTime()) {
            // Choose the point first (shown on the bar), open it once the keys rest: each opening is a new transcode.
            int from = pending >= 0 ? pending : position();
            pending = Math.max(0, Math.min(from + (forward ? step : -step), known - 5000));
            bar.show();
            main.removeCallbacks(jump);
            main.postDelayed(jump, 800);
            return;
        }
        if (failed || image || !video.canSeekForward() && forward || !video.canSeekBackward() && !forward) return;
        int to = video.getCurrentPosition() + (forward ? step : -step);
        int length = video.getDuration();
        if (length > 0) to = Math.min(to, length - 1000);
        video.seekTo(Math.max(0, to));
        bar.show();
        reportNow();
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
            case KeyEvent.KEYCODE_INFO:   // the picture's resolution, for a few seconds
                infoUntil = android.os.SystemClock.uptimeMillis() + 5000;
                bar.invalidate();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_SPACE:
                if (e.getRepeatCount() > 0) return true;
                if (failed || image) return true;
                if (video.isPlaying()) video.pause();
                else video.start();
                bar.show();
                reportNow();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                if (image) return true;
                video.start();
                bar.show();
                reportNow();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (image) return true;
                video.pause();
                bar.show();
                reportNow();
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
            if (failed || audioOnly || !image && !video.isPlaying()) return;
            fade = ValueAnimator.ofFloat(1, 0);
            fade.setStartDelay(3000);
            fade.setDuration(500);
            fade.addUpdateListener(a -> {
                alpha = (float) a.getAnimatedValue();
                invalidate();
            });
            fade.start();
        }

        /** "1920 × 1080 · 1080p", or what it is when there's no picture size. */
        private String resolution() {
            if (audioOnly) return "Solo audio";
            if (videoW <= 0 || videoH <= 0) return "Cargando…";
            int lines = Math.min(videoW, videoH);
            String grade = lines >= 2160 ? "4K" : lines >= 1440 ? "1440p" : lines >= 1080 ? "1080p" : lines >= 720 ? "720p"
                    : lines + "p";
            return videoW + " × " + videoH + " · " + grade;
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            if (android.os.SystemClock.uptimeMillis() < infoUntil && !failed) {
                // Top right, on a dark pill so it reads over any picture.
                String s = resolution();
                float tw = name.measureText(s), pad = 28 * k, top = 60 * k, bh = 72 * k;
                r.set(w - 80 * k - tw - 2 * pad, top, w - 80 * k, top + bh);
                fill.setColor(0xB3000000);
                c.drawRoundRect(r, bh / 2, bh / 2, fill);
                name.setAlpha(255);
                c.drawText(s, r.left + pad, top + bh / 2 + 14 * k, name);
            }
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
            if (image) {   // a photo: just its name, for a moment
                name.setAlpha(a);
                c.drawText(TextUtils.ellipsize(title, name, right - x, TextUtils.TruncateAt.END).toString(), x, base + 50 * k, name);
                return;
            }
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
            int pos = pending >= 0 ? pending : position(), length = length();
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
