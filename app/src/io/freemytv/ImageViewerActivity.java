package io.freemytv;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.view.KeyEvent;
import android.view.View;

import java.io.File;
import java.io.InputStream;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Free My TV's photo viewer, in place of the board's (Amlogic's NativeImagePlayer, which also asks for the internet
 * and system control): a picture full screen on black, upright (its EXIF rotation), fading into the next. ← → the
 * other pictures of its folder, OK the bar with its name and place, holding OK makes it the home background (as the
 * explorer does), Back goes back. Opened by the explorer with a file, or by other apps with a picture (ACTION_VIEW).
 */
public class ImageViewerActivity extends Activity {
    static final String EXTRA_PATH = "path";

    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private PhotoView view;
    /** The folder's pictures (just the one when another app sent it), and which is shown. */
    private final List<File> files = new ArrayList<>();
    private int index;
    private Uri single;   // a picture another app sent (content://), with no folder to go through
    private boolean swallowOk;
    private int generation;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Ui.init(this);
        view = new PhotoView(this);
        setContentView(view);
        open(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        open(intent);
    }

    @Override
    protected void onDestroy() {
        loader.shutdownNow();
        super.onDestroy();
    }

    private void open(Intent intent) {
        files.clear();
        single = null;
        String path = intent.getStringExtra(EXTRA_PATH);
        Uri data = intent.getData();
        if (path == null && data != null && "file".equals(data.getScheme())) path = data.getPath();
        // A content:// link that carries a readable file's path (our explorer's): then the whole folder goes through.
        if (path == null && data != null && data.getPath() != null && new File(data.getPath()).canRead()) path = data.getPath();
        if (path != null) {
            File f = new File(path);
            File[] all = f.getParentFile() == null ? null : f.getParentFile().listFiles();
            if (all != null) {
                for (File o : all) if (o.isFile() && !o.getName().startsWith(".") && isImage(o)) files.add(o);
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
        show(0);
    }

    static boolean isImage(File f) {
        String name = f.getName(), ext = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        String type = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return type != null && type.startsWith("image/");
    }

    /** Loads the picture at index + step off the UI thread, at the screen's size, and fades it in. */
    private void show(int step) {
        if (single == null && files.isEmpty()) return;
        if (single == null) index = (index + step + files.size()) % files.size();
        final int ticket = ++generation;
        final File file = single == null ? files.get(index) : null;
        final int w = getResources().getDisplayMetrics().widthPixels, h = getResources().getDisplayMetrics().heightPixels;
        view.loading = true;
        view.invalidate();
        loader.execute(() -> {
            Bitmap b = null;
            int rotation = 0;
            try {
                b = decode(file, w, h);
                rotation = rotation(file);
            } catch (Exception | OutOfMemoryError e) {
                AppLog.w("photo: " + e.getMessage());
            }
            final Bitmap ready = b;
            final int turn = rotation;
            runOnUiThread(() -> {
                if (ticket != generation) {
                    if (ready != null) ready.recycle();
                    return;
                }
                view.loading = false;
                view.set(ready, turn, file);
            });
        });
    }

    private InputStream stream(File file) throws java.io.IOException {
        return file != null ? new java.io.FileInputStream(file) : getContentResolver().openInputStream(single);
    }

    /** The picture shrunk by powers of two to at least the screen's size (a 12 MP photo would not fit in memory). */
    private Bitmap decode(File file, int w, int h) throws java.io.IOException {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = stream(file)) {
            BitmapFactory.decodeStream(in, null, o);
        }
        int sample = 1;
        while (o.outWidth / (sample * 2) >= w && o.outHeight / (sample * 2) >= h) sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        try (InputStream in = stream(file)) {
            return BitmapFactory.decodeStream(in, null, d);
        }
    }

    /** The turn the camera recorded (a photo taken upright), in degrees. */
    private int rotation(File file) {
        try (InputStream in = stream(file)) {
            int o = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            return o == ExifInterface.ORIENTATION_ROTATE_90 ? 90 : o == ExifInterface.ORIENTATION_ROTATE_180 ? 180
                    : o == ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /** Holding OK: this picture becomes the home background (copied into the app, as from the explorer). */
    private void useAsBackground() {
        if (single == null && files.isEmpty()) return;
        final File f = single == null ? files.get(index) : null;
        final Uri shared = single;
        view.toast("Guardando la foto…");
        loader.execute(() -> {
            String done;
            File copy = null;
            try {
                if (f == null) {   // another app's picture: a copy first, named as that app calls it
                    String seg = shared.getLastPathSegment();
                    String name = seg == null ? "foto" : seg.replaceAll("[^\\p{L}\\p{N}._ -]", "_");
                    copy = new File(getCacheDir(), name);
                    try (InputStream in = getContentResolver().openInputStream(shared);
                         java.io.FileOutputStream out = new java.io.FileOutputStream(copy)) {
                        byte[] buf = new byte[16384];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    }
                }
                Gallery.addOwn(this, f != null ? f : copy, true);
                done = "Listo: es el fondo del inicio, y quedó en tus favoritas.";
            } catch (Exception e) {
                done = "No se pudo usar como fondo.";
            } finally {
                //noinspection ResultOfMethodCallIgnored
                if (copy != null) copy.delete();
            }
            final String text = done;
            runOnUiThread(() -> view.toast(text));
        });
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER;
        if (ok) {
            // A tap shows or hides the bar on release; held, the picture becomes the background (release swallowed).
            if (down) {
                if (!swallowOk && e.getRepeatCount() > 0 && ((e.getFlags() & KeyEvent.FLAG_LONG_PRESS) != 0 || e.getRepeatCount() >= 10)) {
                    swallowOk = true;
                    useAsBackground();
                }
                return true;
            }
            if (swallowOk) swallowOk = false;
            else view.toggleBar();
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (down && e.getRepeatCount() % 4 == 0) show(code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    /** The picture, fitted and upright, crossfading from the last one; the bar and a short notice over it. */
    private final class PhotoView extends View {
        private final float k;
        private final Paint photo = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG),
                fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint name, detail, hint;
        private final Matrix m = new Matrix();
        private final RectF r = new RectF();
        private Bitmap current, before;
        private int turn, beforeTurn;
        private float fade = 1, bar;
        private boolean barShown, loading;
        private String nameText = "", detailText = "", notice;
        private ValueAnimator fading, barAnim;
        private final Runnable hideNotice = () -> {
            notice = null;
            invalidate();
        };

        PhotoView(Context c) {
            super(c);
            k = c.getResources().getDisplayMetrics().heightPixels / 1080f;
            name = new TextPaint(Ui.text(Ui.semibold, 34 * k, 0xFFFFFFFF));
            detail = new TextPaint(Ui.text(Ui.regular, 24 * k, 0xB3FFFFFF));
            hint = new TextPaint(Ui.text(Ui.regular, 22 * k, 0x99FFFFFF));
            hint.setTextAlign(Paint.Align.RIGHT);
            setBackgroundColor(0xFF000000);
        }

        void set(Bitmap b, int rotation, File file) {
            if (before != null && before != current) before.recycle();
            before = current;
            beforeTurn = turn;
            current = b;
            turn = rotation;
            DateFormat date = DateFormat.getDateInstance(DateFormat.MEDIUM, Ui.locale());
            nameText = file != null ? file.getName() : "Foto";
            detailText = file == null ? "" : (files.size() > 1 ? (index + 1) + " de " + files.size() + " · " : "")
                    + Formatter.formatShortFileSize(getContext(), file.length()) + " · " + date.format(new Date(file.lastModified()));
            if (fading != null) fading.cancel();
            fade = 0;
            fading = ValueAnimator.ofFloat(0, 1);
            fading.setDuration(260);
            fading.addUpdateListener(a -> {
                fade = (float) a.getAnimatedValue();
                invalidate();
            });
            fading.start();
            if (b == null) toast("No se pudo abrir esta foto.");
        }

        void toggleBar() {
            barShown = !barShown;
            if (barAnim != null) barAnim.cancel();
            barAnim = ValueAnimator.ofFloat(bar, barShown ? 1 : 0);
            barAnim.setDuration(200);
            barAnim.addUpdateListener(a -> {
                bar = (float) a.getAnimatedValue();
                invalidate();
            });
            barAnim.start();
        }

        void toast(String text) {
            notice = text;
            removeCallbacks(hideNotice);
            postDelayed(hideNotice, 2500);
            invalidate();
        }

        private void drawPhoto(Canvas c, Bitmap b, int rotation, int alpha) {
            if (b == null || b.isRecycled()) return;
            float w = getWidth(), h = getHeight();
            boolean sideways = rotation == 90 || rotation == 270;
            float bw = sideways ? b.getHeight() : b.getWidth(), bh = sideways ? b.getWidth() : b.getHeight();
            float s = Math.min(w / bw, h / bh);
            m.reset();
            m.postTranslate(-b.getWidth() / 2f, -b.getHeight() / 2f);
            m.postRotate(rotation);
            m.postScale(s, s);
            m.postTranslate(w / 2, h / 2);
            photo.setAlpha(alpha);
            c.drawBitmap(b, m, photo);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            if (fade < 1) drawPhoto(c, before, beforeTurn, Math.round(255 * (1 - fade)));
            drawPhoto(c, current, turn, Math.round(255 * fade));
            if (current == null && loading) {
                hint.setTextAlign(Paint.Align.CENTER);
                c.drawText("Cargando…", w / 2, h / 2, hint);
                hint.setTextAlign(Paint.Align.RIGHT);
            }
            if (bar > 0.01f) {   // a smoked bar along the bottom
                float bh = 120 * k, top = h - bh * bar;
                r.set(0, top, w, h);
                fill.setColor(Ui.alpha(0xB3000000, bar));
                c.drawRect(r, fill);
                name.setAlpha(Math.round(255 * bar));
                detail.setAlpha(Math.round(179 * bar));
                hint.setAlpha(Math.round(153 * bar));
                float x = 60 * k;
                c.drawText(TextUtils.ellipsize(nameText, name, w * 0.55f, TextUtils.TruncateAt.MIDDLE).toString(),
                        x, top + 52 * k, name);
                c.drawText(detailText, x, top + 92 * k, detail);
                String keys = (single == null && files.size() > 1 ? "← → otra foto · " : "")
                        + "mantené OK: usar de fondo · Atrás vuelve";
                c.drawText(keys, w - x, top + 72 * k, hint);
            }
            if (notice != null) {   // a pill at the top
                float tw = detail.measureText(notice) + 48 * k, ph = 56 * k;
                r.set((w - tw) / 2, 40 * k, (w + tw) / 2, 40 * k + ph);
                fill.setColor(0xE61B1B1E);
                c.drawRoundRect(r, ph / 2, ph / 2, fill);
                detail.setAlpha(255);
                detail.setColor(0xFFFFFFFF);
                c.drawText(notice, r.left + 24 * k, r.centerY() + 9 * k, detail);
                detail.setColor(0xB3FFFFFF);
            }
        }
    }
}
