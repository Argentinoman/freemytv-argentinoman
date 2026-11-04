package io.freemytv;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Bundle;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Settings → Fondos → Mis favoritas: every favourite background in a grid. OK hides one (still a favourite, but the
 * background and the screensaver skip it) or shows it again; holding OK offers to move it (the order the background
 * follows) or take it out of the favourites.
 */
public class FavoritesActivity extends Activity {
    private static final int COLUMNS = 4;

    private GridView view;
    private List<Gallery.Item> items;
    private int focus;
    /** The favourite waiting for "OK otra vez" to be taken out (-1: none). */
    private int armed = -1;
    private boolean swallowOk;
    /** Holding OK: the options for the focused picture are open, and the one marked. */
    private static final String[] OPTIONS = {"Cambiar de lugar", "Quitar de favoritas", "Cancelar"};
    private boolean menu;
    private int choice;
    /** Changing its place: the arrows carry the focused picture through the grid. */
    private boolean moving;
    private final Map<String, Bitmap> thumbs = new HashMap<>();
    private final ExecutorService loader = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Ui.init(this);
        items = Gallery.favorites(this);
        view = new GridView(this);
        setContentView(view);
        for (Gallery.Item it : items) load(it);
    }

    @Override
    protected void onDestroy() {
        loader.shutdownNow();
        super.onDestroy();
    }

    /** The picture, small, off the UI thread. */
    private void load(Gallery.Item it) {
        final int tw = Math.round(view.tileW());
        loader.execute(() -> {
            try {
                File f = Gallery.image(this, it);
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(f.getPath(), o);
                int sample = 1;
                while (o.outWidth / (sample * 2) >= tw) sample *= 2;
                o = new BitmapFactory.Options();
                o.inSampleSize = sample;
                Bitmap b = BitmapFactory.decodeFile(f.getPath(), o);
                if (b != null) runOnUiThread(() -> {
                    thumbs.put(it.url, b);
                    view.invalidate();
                });
            } catch (Exception | OutOfMemoryError e) {
                AppLog.w("favorites thumb: " + e.getMessage());
            }
        });
    }

    /** A change that touches the picture on Home right now moves Home on to another one. */
    private void moveHomeIfShowing(Gallery.Item it) {
        Gallery.Item now = Gallery.current(this);
        if (now != null && now.url.equals(it.url) && Prefs.homeBackground(this).equals(Gallery.FAVORITES)) {
            Prefs.skipHomeBackground(this);
        }
    }

    private void togglePause() {
        Gallery.Item it = items.get(focus);
        it.paused = !it.paused;
        Gallery.setPaused(this, it.url, it.paused);
        if (it.paused) moveHomeIfShowing(it);
        view.invalidate();
    }

    private void remove() {
        Gallery.Item it = items.get(armed);
        Gallery.toggleFavorite(this, it);   // it is one: this takes it out
        moveHomeIfShowing(it);
        items.remove(armed);
        thumbs.remove(it.url);
        armed = -1;
        focus = Math.max(0, Math.min(focus, items.size() - 1));
        view.invalidate();
    }

    /** Done changing its place: the new order is saved. */
    private void endMove() {
        moving = false;
        Gallery.setOrder(this, items);
        view.invalidate();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER;
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (code == KeyEvent.KEYCODE_BACK) {
            if (!down) {
                if (menu) menu = false;
                else if (moving) endMove();
                else if (armed >= 0) armed = -1;
                else finish();
                view.invalidate();
            }
            return true;
        }
        if (ok) {
            if (items.isEmpty()) return true;
            // A tap acts on release; held, the options open (the release is swallowed).
            if (down) {
                if (!swallowOk && !menu && !moving && e.getRepeatCount() > 0
                        && ((e.getFlags() & KeyEvent.FLAG_LONG_PRESS) != 0 || e.getRepeatCount() >= 10)) {
                    swallowOk = true;
                    menu = true;
                    choice = 0;
                    armed = -1;
                    view.invalidate();
                }
                return true;
            }
            if (swallowOk) {
                swallowOk = false;
                return true;
            }
            if (menu) {
                menu = false;
                if (choice == 0) moving = true;
                else if (choice == 1) armed = focus;
                view.invalidate();
            } else if (moving) {
                endMove();
            } else if (armed == focus) {
                remove();
            } else {
                togglePause();
            }
            return true;
        }
        int dx = code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : 0;
        int dy = code == KeyEvent.KEYCODE_DPAD_UP ? -1 : code == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : 0;
        if (dx == 0 && dy == 0) return super.dispatchKeyEvent(e);
        if (!down || items.isEmpty()) return true;
        if (menu) {
            if (dy != 0) choice = Math.max(0, Math.min(OPTIONS.length - 1, choice + dy));
            view.invalidate();
            return true;
        }
        int next = focus + dx + dy * COLUMNS;
        if (dx != 0 && next / COLUMNS != focus / COLUMNS) return true;   // not off the row's ends
        if (next >= 0 && next < items.size()) {
            if (moving) {   // the picture goes along: it takes the next place, the others close up
                Gallery.Item it = items.remove(focus);
                items.add(next, it);
            }
            focus = next;
            armed = -1;
            view.invalidate();
        }
        return true;
    }

    /** The grid, in the launcher's look: the focused picture lifted with a white ring, paused ones dimmed. */
    private final class GridView extends View {
        private final float k;
        private final TextPaint heading, sub, name, badge, hint;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG),
                photo = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final RectF r = new RectF(), pill = new RectF();
        private final Matrix m = new Matrix();
        private int first;   // the top row on screen

        GridView(Context c) {
            super(c);
            k = c.getResources().getDisplayMetrics().heightPixels / 1080f;
            heading = new TextPaint(Ui.text(Ui.semibold, 44 * k, 0xFFFFFFFF));
            sub = new TextPaint(Ui.text(Ui.regular, 24 * k, 0x99FFFFFF));
            name = new TextPaint(Ui.text(Ui.medium, 24 * k, 0xE6FFFFFF));
            badge = new TextPaint(Ui.text(Ui.semibold, 22 * k, 0xFF1C1C1E));
            hint = new TextPaint(Ui.text(Ui.regular, 24 * k, 0xB3FFFFFF));
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(5 * k);
            setBackgroundColor(0xFF0E0E10);
        }

        float tileW() {
            return 380 * getResources().getDisplayMetrics().heightPixels / 1080f;
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), x = 120 * k;
            Symbols.draw(getContext(), c, "image", x + 22 * k, 92 * k, 34 * k, 0xEBFFFFFF);
            c.drawText("Mis favoritas", x + 64 * k, 108 * k, heading);
            int paused = 0;
            for (Gallery.Item it : items) if (it.paused) paused++;
            c.drawText(items.isEmpty() ? "Todavía no hay ninguna" : items.size() + (items.size() == 1 ? " foto" : " fotos")
                    + (paused > 0 ? " · " + paused + (paused == 1 ? " oculta" : " ocultas") : ""), x, 152 * k, sub);
            if (items.isEmpty()) {
                c.drawText("Desde el explorador: mantené OK en una foto y elegí «Sumar a fondos favoritos».", x, 240 * k, hint);
                return;
            }
            float tw = tileW(), th = tw * 9 / 16, gap = 32 * k, rowH = th + 72 * k;
            float left = (w - COLUMNS * tw - (COLUMNS - 1) * gap) / 2, top = 210 * k;
            int rows = Math.max(1, (int) ((h - top - 100 * k) / rowH));
            int row = focus / COLUMNS;
            if (row < first) first = row;
            if (row >= first + rows) first = row - rows + 1;
            for (int i = first * COLUMNS; i < Math.min(items.size(), (first + rows) * COLUMNS); i++) {
                Gallery.Item it = items.get(i);
                float tx = left + (i % COLUMNS) * (tw + gap), ty = top + (i / COLUMNS - first) * rowH;
                boolean on = i == focus;
                r.set(tx, ty, tx + tw, ty + th);
                if (on) r.inset(-8 * k, -6 * k);
                Bitmap b = thumbs.get(it.url);
                if (b != null) {
                    // Centre-cropped into the tile, rounded.
                    float s = Math.max(r.width() / b.getWidth(), r.height() / b.getHeight());
                    m.setScale(s, s);
                    m.postTranslate(r.centerX() - b.getWidth() * s / 2, r.centerY() - b.getHeight() * s / 2);
                    BitmapShader shader = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                    shader.setLocalMatrix(m);
                    photo.setShader(shader);
                    photo.setAlpha(it.paused ? 90 : 255);
                    c.drawRoundRect(r, 18 * k, 18 * k, photo);
                } else {
                    fill.setColor(0x1AFFFFFF);
                    c.drawRoundRect(r, 18 * k, 18 * k, fill);
                }
                boolean carried = on && moving;
                if (on) {
                    ring.setColor(i == armed ? 0xFFFF6961 : carried ? 0xFFFFC266 : 0xFFF5F5F7);
                    c.drawRoundRect(r, 18 * k, 18 * k, ring);
                }
                if (it.paused || i == armed || carried) {
                    String label = i == armed ? "¿Quitar? OK confirma" : carried ? "Moviendo" : "Oculta";
                    float bw = badge.measureText(label) + 32 * k;
                    pill.set(r.left + 14 * k, r.top + 14 * k, r.left + 14 * k + bw, r.top + 14 * k + 40 * k);
                    fill.setColor(i == armed ? 0xFFFF6961 : carried ? 0xFFFFC266 : 0xE6F5F5F7);
                    c.drawRoundRect(pill, 20 * k, 20 * k, fill);
                    c.drawText(label, pill.left + 16 * k, pill.centerY() + 8 * k, badge);
                }
                String title = it.title == null || it.title.isEmpty() ? "Sin nombre" : it.title;
                name.setColor(on ? 0xFFFFFFFF : it.paused ? 0x80FFFFFF : 0xCCFFFFFF);
                c.drawText(TextUtils.ellipsize(title, name, tw, TextUtils.TruncateAt.END).toString(), tx, ty + th + 42 * k, name);
            }
            String foot = menu ? "↑ ↓ elegí · OK confirma · Atrás cancela"
                    : moving ? "Flechas: llevala a su lugar · OK o Atrás: dejarla ahí"
                    : armed >= 0 ? "OK la quita de favoritas · Atrás cancela"
                    : items.get(focus).paused ? "OK: volver a mostrarla de fondo · Mantené OK: más opciones"
                    : "OK: no mostrarla de fondo · Mantené OK: más opciones";
            c.drawText(foot, x, h - 56 * k, hint);
            if (menu) drawMenu(c, w, h);
        }

        /** The options for the focused picture, in a dark panel over the dimmed grid. */
        private void drawMenu(Canvas c, float w, float h) {
            c.drawColor(0x99000000);
            float rowH = 76 * k, pw = 560 * k, ph = OPTIONS.length * rowH + 40 * k;
            r.set((w - pw) / 2, (h - ph) / 2, (w + pw) / 2, (h + ph) / 2);
            fill.setColor(0xFF2C2C2E);
            c.drawRoundRect(r, 28 * k, 28 * k, fill);
            for (int i = 0; i < OPTIONS.length; i++) {
                float top = r.top + 20 * k + i * rowH;
                if (i == choice) {
                    pill.set(r.left + 16 * k, top, r.right - 16 * k, top + rowH);
                    fill.setColor(0xFFF5F5F7);
                    c.drawRoundRect(pill, 18 * k, 18 * k, fill);
                }
                name.setColor(i == choice ? 0xFF1C1C1E : i == 1 ? 0xFFFF8A80 : 0xE6FFFFFF);
                c.drawText(OPTIONS[i], r.left + 44 * k, top + rowH / 2 + 9 * k, name);
            }
        }
    }
}
