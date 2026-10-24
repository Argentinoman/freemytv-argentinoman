package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Material Symbols Rounded icons (Apache 2.0), rendered by tools/make_symbols.py as white PNGs in assets/symbols. Drawn
 * like text: `pointSize` is the size of the font next to the icon, and the icon is centered on that font's cap height.
 */
final class Symbols {
    private Symbols() {}

    /**
     * PNG pixels per em of `pointSize` (a 96 pt × 1.5 px/pt reference scale). tools/make_symbols.py renders a 172 px
     * em, so an icon's em is drawn at ~1.2 × `pointSize`.
     */
    private static final float PX_PER_POINT = 96 * 1.5f;
    private static final Map<String, Bitmap> CACHE = new HashMap<>();
    private static final Paint PAINT = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private static final RectF RECT = new RectF();

    static Bitmap get(Context c, String name) {
        synchronized (CACHE) {
            if (CACHE.containsKey(name)) return CACHE.get(name);
            Bitmap b = drawn(name);
            if (b != null) {
                CACHE.put(name, b);
                return b;
            }
            try (InputStream in = c.getAssets().open("symbols/" + name + ".png")) {
                b = BitmapFactory.decodeStream(in);
                if (b != null) {
                    b = b.extractAlpha();
                    b.setHasMipMap(true);
                }
            } catch (IOException ignored) {
            }
            CACHE.put(name, b);
            return b;
        }
    }

    /**
     * Line icons drawn here instead of Material's, in the same em and stroke weight as the PNGs: a plain TV on a stand
     * for Live TV, a plug seen from the side for the HDMI inputs, and a globe for the browser.
     */
    private static Bitmap drawn(String name) {
        if (name.equals("globe")) return globe();
        boolean picture = name.equals("picture");
        boolean tv = picture || name.equals("live_tv") || name.equals("tv"), hdmi = name.equals("hdmi");
        if (!tv && !hdmi) return null;
        float sw = 13f, h2 = sw / 2;
        Bitmap b = Bitmap.createBitmap(152, tv ? 138 : 64, Bitmap.Config.ALPHA_8);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(sw);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        if (tv) {
            c.drawRoundRect(new RectF(h2, h2, 152 - h2, 108 - h2), 18, 18, p);
            c.drawLine(46, 138 - h2, 106, 138 - h2, p);
            if (picture) {
                // Picture and sound: a contrast disc inside the screen, half filled.
                float r = 26;
                p.setStrokeWidth(10f);
                c.drawCircle(76, 54, r, p);
                p.setStyle(Paint.Style.FILL);
                c.drawArc(new RectF(76 - r, 54 - r, 76 + r, 54 + r), 90, 180, true, p);
            }
        } else {
            // A plug seen from the side: the cable, the connector's body, its narrower tip. Filled, like Material's.
            p.setStrokeWidth(12f);
            c.drawLine(6, 32, 58, 32, p);
            p.setStyle(Paint.Style.FILL);
            c.drawRoundRect(new RectF(54, 8, 122, 56), 10, 10, p);
            c.drawRoundRect(new RectF(116, 18, 150, 46), 5, 5, p);
        }
        b.setHasMipMap(true);
        return b;
    }

    /** The browser: a globe, its outline, one meridian and two parallels, in the PNGs' stroke weight. */
    private static Bitmap globe() {
        float sw = 13f, s = 140, r = (s - sw) / 2, cx = s / 2;
        Bitmap b = Bitmap.createBitmap((int) s, (int) s, Bitmap.Config.ALPHA_8);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(sw);
        p.setStrokeCap(Paint.Cap.ROUND);
        c.drawCircle(cx, cx, r, p);
        c.drawOval(new RectF(cx - r * 0.45f, cx - r, cx + r * 0.45f, cx + r), p);
        float y = r * 0.42f, half = (float) Math.sqrt(r * r - y * y) - sw * 0.2f;
        c.drawLine(cx - half, cx - y, cx + half, cx - y, p);
        c.drawLine(cx - half, cx + y, cx + half, cx + y, p);
        b.setHasMipMap(true);
        return b;
    }

    static float width(Context c, String name, float pointSize) {
        Bitmap b = get(c, name);
        return b == null ? 0 : b.getWidth() * pointSize / PX_PER_POINT;
    }

    /** Draws centered at (cx, cy) in the given color (lighter, translucent parts of the symbol stay lighter). */
    static void draw(Context c, Canvas canvas, String name, float cx, float cy, float pointSize, int color) {
        Bitmap b = get(c, name);
        if (b == null) return;
        synchronized (PAINT) {   // card loading (another thread) also draws symbols
            drawLocked(canvas, b, cx, cy, pointSize, color);
        }
    }

    private static void drawLocked(Canvas canvas, Bitmap b, float cx, float cy, float pointSize, int color) {
        float h = b.getHeight() * pointSize / PX_PER_POINT;
        // Drawn pixel for pixel from a copy filtered down to this size: the TV's GPU shrinking the ~150 px master five
        // times in one step left jagged edges. Even heights only, so a focus animation reuses a handful of copies.
        int th = Math.max(2, (Math.round(h) + 1) & ~1);
        int tw = Math.max(1, Math.round(th * b.getWidth() / (float) b.getHeight()));
        Bitmap sized = sized(b, tw, th);
        PAINT.setColor(color);
        canvas.drawBitmap(sized, Math.round(cx - tw / 2f), Math.round(cy - th / 2f), PAINT);
    }

    private static final android.util.LruCache<String, Bitmap> SIZED = new android.util.LruCache<>(64);

    static void trim() {
        SIZED.evictAll();
    }

    private static Bitmap sized(Bitmap master, int w, int h) {
        String key = System.identityHashCode(master) + "@" + w + "x" + h;
        Bitmap out = SIZED.get(key);
        if (out != null) return out;
        if (w >= master.getWidth()) {
            out = master;
        } else {
            Bitmap src = master.copy(Bitmap.Config.ARGB_8888, false);
            // Halve while it can (a 2x2 average, no aliasing), then finish at the exact size.
            while (src.getWidth() / 2 >= w && src.getHeight() / 2 >= h) {
                src = Bitmap.createScaledBitmap(src, src.getWidth() / 2, src.getHeight() / 2, true);
            }
            out = Bitmap.createScaledBitmap(src, w, h, true).extractAlpha();
        }
        SIZED.put(key, out);
        return out;
    }

    /** Weather symbol for an Open-Meteo WMO weather code. */
    static String weather(int code, boolean day) {
        if (code <= 1) return day ? "sunny" : "clear_night";
        if (code == 2) return day ? "partly_cloudy_day" : "partly_cloudy_night";
        if (code == 3) return "cloud";
        if (code == 45 || code == 48) return "foggy";
        if (code >= 51 && code <= 57) return "rainy";
        if (code == 65 || code == 67 || code == 82) return "rainy";
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return "snowflake";
        if (code >= 95) return "thunderstorm";
        return "rainy";
    }
}
