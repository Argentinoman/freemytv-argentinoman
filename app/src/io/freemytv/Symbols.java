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
        if (name.equals("folder")) return folder();
        if (name.equals("keystone")) return keystone();
        if (name.equals("usb")) return usb();
        if (name.equals("store")) return store();
        if (name.equals("lock")) return lock();
        if (name.equals("file")) return file();
        if (name.equals("image")) return image();
        if (name.equals("cast")) return cast();
        if (name.equals("apple")) return devices(true);
        if (name.equals("android")) return devices(false);
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

    /** A folder with its tab, for the file explorer. */
    private static Bitmap folder() {
        float sw = 13f, h2 = sw / 2, w = 152, h = 120;
        Bitmap b = Bitmap.createBitmap((int) w, (int) h, Bitmap.Config.ALPHA_8);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(sw);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        android.graphics.Path f = new android.graphics.Path();
        f.moveTo(h2, h - h2 - 14);
        f.lineTo(h2, h2 + 14);
        f.quadTo(h2, h2, h2 + 14, h2);
        f.lineTo(52, h2);
        f.lineTo(66, 24);
        f.lineTo(w - h2 - 14, 24);
        f.quadTo(w - h2, 24, w - h2, 38);
        f.lineTo(w - h2, h - h2 - 14);
        f.quadTo(w - h2, h - h2, w - h2 - 14, h - h2);
        f.lineTo(h2 + 14, h - h2);
        f.quadTo(h2, h - h2, h2, h - h2 - 14);
        f.close();
        c.drawPath(f, p);
        c.drawLine(h2 + 4, 44, w - h2 - 4, 44, p);
        b.setHasMipMap(true);
        return b;
    }

    /** A file, for the explorer: a page with its top-right corner folded. */
    private static Bitmap file() {
        float sw = 13f, h2 = sw / 2, w = 112, h = 140, fold = 36;
        Bitmap b = Bitmap.createBitmap((int) w, (int) h, Bitmap.Config.ALPHA_8);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(sw);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        android.graphics.Path f = new android.graphics.Path();
        f.moveTo(w - h2 - fold, h2);
        f.lineTo(h2 + 12, h2);
        f.quadTo(h2, h2, h2, h2 + 12);
        f.lineTo(h2, h - h2 - 12);
        f.quadTo(h2, h - h2, h2 + 12, h - h2);
        f.lineTo(w - h2 - 12, h - h2);
        f.quadTo(w - h2, h - h2, w - h2, h - h2 - 12);
        f.lineTo(w - h2, h2 + fold);
        f.close();
        f.moveTo(w - h2 - fold, h2);
        f.lineTo(w - h2 - fold, h2 + fold);
        f.lineTo(w - h2, h2 + fold);
        c.drawPath(f, p);
        b.setHasMipMap(true);
        return b;
    }

    /** Wireless input: a screen whose bottom-left corner sends three arcs, the casting sign. */
    private static Bitmap cast() {
        float sw = 13f, h2 = sw / 2, w = 152, h = 120;
        Bitmap b = Bitmap.createBitmap((int) w, (int) h, Bitmap.Config.ALPHA_8);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(sw);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        android.graphics.Path frame = new android.graphics.Path();
        frame.moveTo(h2, 40);
        frame.lineTo(h2, h2 + 12);
        frame.quadTo(h2, h2, h2 + 12, h2);
        frame.lineTo(w - h2 - 12, h2);
        frame.quadTo(w - h2, h2, w - h2, h2 + 12);
        frame.lineTo(w - h2, h - h2 - 12);
        frame.quadTo(w - h2, h - h2, w - h2 - 12, h - h2);
        frame.lineTo(76, h - h2);
        c.drawPath(frame, p);
        float ox = h2, oy = h - h2;
        for (float r : new float[] {28, 52}) c.drawArc(new RectF(ox - r, oy - r, ox + r, oy + r), 270, 90, false, p);
        p.setStyle(Paint.Style.FILL);
        c.drawCircle(ox + 3, oy - 3, 9, p);
        b.setHasMipMap(true);
        return b;
    }

    /**
     * The wireless screen's two choices: a phone beside a laptop (Android and Windows), or a phone beside a tablet with
     * the round home button of Apple's devices.
     */
    private static Bitmap devices(boolean apple) {
        float sw = 11f, h2 = sw / 2, w = 160, h = 120;
        Bitmap b = Bitmap.createBitmap((int) w, (int) h, Bitmap.Config.ALPHA_8);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(sw);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        RectF phone = apple ? new RectF(h2, 34, 58, h - h2) : new RectF(h2, 44, 50, h - h2);
        if (apple) {
            c.drawRoundRect(new RectF(48, h2, w - h2, h - h2), 12, 12, p);     // the tablet
        } else {
            c.drawRoundRect(new RectF(40, h2, w - 18, 86), 8, 8, p);           // the laptop's screen
            c.drawLine(24, h - h2 - 10, w - h2, h - h2 - 10, p);               // and its base
        }
        // The phone in front: what's behind it is cleared first, so the lines don't cross it.
        Paint clear = new Paint(Paint.ANTI_ALIAS_FLAG);
        clear.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR));
        c.drawRoundRect(new RectF(phone.left - sw, phone.top - sw, phone.right + sw, phone.bottom + sw), 14, 14, clear);
        c.drawRoundRect(phone, 10, 10, p);
        p.setStyle(Paint.Style.FILL);
        c.drawCircle(phone.centerX(), h - 22, 5, p);
        b.setHasMipMap(true);
        return b;
    }

    /** A photo, for the explorer: a frame with a sun and two hills. */
    private static Bitmap image() {
        float sw = 13f, h2 = sw / 2, w = 152, h = 120;
        Bitmap b = Bitmap.createBitmap((int) w, (int) h, Bitmap.Config.ALPHA_8);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(sw);
        p.setStrokeJoin(Paint.Join.ROUND);
        c.drawRoundRect(new RectF(h2, h2, w - h2, h - h2), 16, 16, p);
        p.setStyle(Paint.Style.FILL);
        c.drawCircle(104, 40, 11, p);
        android.graphics.Path hills = new android.graphics.Path();
        hills.moveTo(h2 + 6, h - h2 - 6);
        hills.lineTo(52, 54);
        hills.lineTo(84, 86);
        hills.lineTo(104, 68);
        hills.lineTo(w - h2 - 6, h - h2 - 6);
        hills.close();
        c.drawPath(hills, p);
        b.setHasMipMap(true);
        return b;
    }

    /** The projection screen: a keystoned picture, a trapezoid wider at the top, with a square inside it. */
    private static Bitmap keystone() {
        float sw = 13f, h2 = sw / 2, w = 152, h = 120;
        Bitmap b = Bitmap.createBitmap((int) w, (int) h, Bitmap.Config.ALPHA_8);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(sw);
        p.setStrokeJoin(Paint.Join.ROUND);
        android.graphics.Path t = new android.graphics.Path();
        t.moveTo(h2, h2);
        t.lineTo(w - h2, h2);
        t.lineTo(w - 30, h - h2);
        t.lineTo(30, h - h2);
        t.close();
        c.drawPath(t, p);
        p.setStrokeWidth(10f);
        c.drawRect(new RectF(50, 32, w - 50, h - 32), p);
        b.setHasMipMap(true);
        return b;
    }

    /** A pen drive standing up: its body, and the metal plug on top with its two holes. */
    private static Bitmap usb() {
        float sw = 13f, h2 = sw / 2, w = 84, h = 140;
        Bitmap b = Bitmap.createBitmap((int) w, (int) h, Bitmap.Config.ALPHA_8);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(sw);
        p.setStrokeJoin(Paint.Join.ROUND);
        c.drawRoundRect(new RectF(h2, 52, w - h2, h - h2), 16, 16, p);
        c.drawRect(new RectF(18, h2, w - 18, 52), p);
        p.setStyle(Paint.Style.FILL);
        c.drawRect(new RectF(30, 20, 38, 30), p);
        c.drawRect(new RectF(w - 38, 20, w - 30, 30), p);
        b.setHasMipMap(true);
        return b;
    }

    /** The Play Store: a shopping bag, its handle, and a play triangle inside. */
    private static Bitmap store() {
        float sw = 13f, h2 = sw / 2, w = 136, h = 140;
        Bitmap b = Bitmap.createBitmap((int) w, (int) h, Bitmap.Config.ALPHA_8);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(sw);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        c.drawRoundRect(new RectF(h2, 38, w - h2, h - h2), 16, 16, p);
        c.drawArc(new RectF(40, h2, w - 40, 70), 180, 180, false, p);
        p.setStyle(Paint.Style.FILL);
        android.graphics.Path t = new android.graphics.Path();
        t.moveTo(54, 66);
        t.lineTo(92, 89);
        t.lineTo(54, 112);
        t.close();
        c.drawPath(t, p);
        b.setHasMipMap(true);
        return b;
    }

    /** A padlock: its shackle and its body, for network ADB. */
    private static Bitmap lock() {
        float sw = 13f, h2 = sw / 2, w = 112, h = 140;
        Bitmap b = Bitmap.createBitmap((int) w, (int) h, Bitmap.Config.ALPHA_8);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(sw);
        p.setStrokeCap(Paint.Cap.ROUND);
        c.drawArc(new RectF(26, h2, w - 26, 84), 180, 180, false, p);
        c.drawLine(26, 45, 26, 62, p);
        c.drawLine(w - 26, 45, w - 26, 62, p);
        p.setStyle(Paint.Style.FILL);
        c.drawRoundRect(new RectF(0, 60, w, h), 16, 16, p);
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
