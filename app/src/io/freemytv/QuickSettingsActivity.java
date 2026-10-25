package io.freemytv;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Quick settings, as Google TV's gear opens them (that panel lives in Google's launcher, which is off in Free My TV
 * mode): a glass panel on the right with the time, tiles for Wi-Fi, Bluetooth, the screensaver now, picture and sound,
 * inputs and all settings, and the latest notifications under them. Opened by the home screen's gear and the remote's
 * gear key, over any app; the gear again (or Back) closes it.
 */
public class QuickSettingsActivity extends Activity {
    private PanelView panel;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Ui.init(this);
        panel = new PanelView(this);
        setContentView(panel);
    }

    /** Already open (singleTask) and asked again: the gear works as a switch. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        panel.close();
    }

    @Override
    protected void onResume() {
        super.onResume();
        panel.refresh();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        return panel.key(e) || super.dispatchKeyEvent(e);
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(0, 0);
    }

    private static final class Tile {
        final String title, symbol;
        String sub = "";
        final Runnable action;
        /** Instead of `action`: something done in place, with the panel left open. */
        Runnable stay;
        /** Done when OK is held (then `stay` waits for the release, so a hold doesn't also do it). */
        Runnable hold;

        Tile(String title, String symbol, Runnable action) {
            this.title = title;
            this.symbol = symbol;
            this.action = action;
        }
    }

    private final class PanelView extends View {
        private final float k;
        private final List<Tile> tiles = new ArrayList<>();
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint clock, date, title, sub;
        private final RectF r = new RectF(), panelRect = new RectF(), arc = new RectF();
        private final Path path = new Path();
        private Tile wifi, bluetooth;
        /** The tiles first (two columns), then the notifications. */
        private int focus;
        private float slide;   // 0 off screen, 1 in place

        PanelView(Context c) {
            super(c);
            k = c.getResources().getDisplayMetrics().heightPixels / 1080f;
            clock = new TextPaint(Ui.text(Ui.light, 76 * k, 0xFFFFFFFF));
            date = new TextPaint(Ui.text(Ui.regular, 24 * k, 0xB3FFFFFF));
            title = new TextPaint(Ui.text(Ui.medium, 24 * k, 0xFFFFFFFF));
            sub = new TextPaint(Ui.text(Ui.regular, 19 * k, 0x99FFFFFF));
            rim.setStyle(Paint.Style.STROKE);
            rim.setStrokeWidth(1.5f * k);
            glyph.setStyle(Paint.Style.STROKE);
            glyph.setStrokeCap(Paint.Cap.ROUND);
            glyph.setStrokeJoin(Paint.Join.ROUND);
            buildTiles();
            ValueAnimator in = ValueAnimator.ofFloat(0, 1);
            in.setDuration(280);
            in.setInterpolator(new DecelerateInterpolator(2f));
            in.addUpdateListener(a -> {
                slide = (float) a.getAnimatedValue();
                invalidate();
            });
            in.start();
        }

        private void buildTiles() {
            Context c = getContext();
            tiles.add(wifi = new Tile("Wi-Fi", "wifi", () -> open(new Intent(Settings.ACTION_WIFI_SETTINGS))));
            tiles.add(bluetooth = new Tile("Bluetooth", "bluetooth", () -> open(bluetoothSettings())));
            Tile dream = new Tile("Protector", "bedtime", () -> slideOutThen(() -> {
                // Straight into the screensaver: the panel has slid away, so no closing animation of its own.
                try {
                    c.startActivity(screensaverNow().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (RuntimeException e) {
                    AppLog.w("screensaver: " + e.getMessage());
                }
                finish();
                overridePendingTransition(0, 0);
            }));
            dream.sub = "Activar ahora";
            tiles.add(dream);
            Tile picture = new Tile("Imagen y sonido", "picture", () -> open(RemoteKeys.quickSettings(c)));
            picture.sub = "Brillo y modos";
            tiles.add(picture);
            if (installed("com.tcl.suspension")) {
                Tile inputs = new Tile("Entradas", "hdmi", () -> {
                    finish();
                    try {
                        c.startService(new Intent("com.tcl.quickpanel.show.window")
                                .setComponent(new ComponentName("com.tcl.suspension", "com.tcl.suspension.DismissService")));
                    } catch (RuntimeException ignored) {
                    }
                });
                inputs.sub = "HDMI y TV";
                tiles.add(inputs);
            }
            Tile all = new Tile("Ajustes", "settings", () -> open(new Intent(Settings.ACTION_SETTINGS)));
            all.sub = "Todos los ajustes";
            tiles.add(all);
            // The home screen's own brightness: each OK dims it one step more, then back to full. Seen live behind the panel.
            final Tile dim = new Tile("Brillo del inicio", "sunny", null);
            dim.sub = dimText(Prefs.launcherDim(c));
            dim.stay = () -> {
                int now = Prefs.launcherDim(c), next = Prefs.DIM_LEVELS[0];
                for (int i = 0; i < Prefs.DIM_LEVELS.length; i++) {
                    if (Prefs.DIM_LEVELS[i] == now) next = Prefs.DIM_LEVELS[(i + 1) % Prefs.DIM_LEVELS.length];
                }
                Prefs.setLauncherDim(c, next);
                dim.sub = dimText(next);
                invalidate();
            };
            tiles.add(dim);
            Tile home = new Tile("Ajustes de inicio", "sparkles", () -> open(new Intent(c, SettingsActivity.class)));
            tiles.add(home);
            // Over Home with a picture behind it: the next picture, seen changing behind the panel.
            // Held: the next kind of picture (landscapes, art, favourites).
            if (HomeActivity.shown && !Prefs.homeBackgroundShown(c).equals("black")) {
                final Tile bg = new Tile("Cambiar fondo", "sync", null);
                bg.sub = kindName(Prefs.homeBackgroundShown(c));   // the kind of picture (hold OK to change it)
                bg.stay = () -> Prefs.skipHomeBackground(c);
                // While a seasonal theme is on, the pictures are the theme's: no other kind to turn to.
                if (!Gallery.photos(Prefs.seasonalTheme(c))) bg.hold = () -> {
                    List<String> kinds = new ArrayList<>(java.util.Arrays.asList(Gallery.KINDS));
                    if (Gallery.favorites(c).isEmpty()) kinds.remove(Gallery.FAVORITES);
                    int at = kinds.indexOf(Prefs.homeBackground(c));
                    String next = kinds.get((at + 1) % kinds.size());
                    Prefs.setHomeBackground(c, next);
                    bg.sub = kindName(next);
                    invalidate();
                };
                tiles.add(bg);
            }
        }

        /**
         * The system screensaver, started now: SystemUI's Somnambulator dreams with whatever screensaver is set (Free My
         * TV's), with no screen in between. Where it's missing, Free My TV's own preview.
         */
        private String kindName(String kind) {
            return Gallery.name(kind);
        }

        private String dimText(int percent) {
            return percent == 0 ? "Normal" : (100 - percent) + " %";
        }

        private Intent screensaverNow() {
            Intent dream = new Intent().setClassName("com.android.systemui", "com.android.systemui.Somnambulator");
            if (dream.resolveActivity(getContext().getPackageManager()) != null) return dream;
            return new Intent(getContext(), SettingsActivity.class).putExtra("screensaver", true);
        }

        /** Android TV has no BLUETOOTH_SETTINGS screen: its "Remote & accessories" page holds the switch and the devices. */
        private Intent bluetoothSettings() {
            Intent tv = new Intent("com.android.tv.settings.ACCESSORIES_CONNECTED_DEVICES");
            if (tv.resolveActivity(getContext().getPackageManager()) != null) return tv;
            return new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
        }

        private boolean installed(String pkg) {
            try {
                getContext().getPackageManager().getPackageInfo(pkg, 0);
                return true;
            } catch (PackageManager.NameNotFoundException e) {
                return false;
            }
        }

        void refresh() {
            ConnectivityManager cm = getContext().getSystemService(ConnectivityManager.class);
            NetworkCapabilities nc = cm == null || cm.getActiveNetwork() == null ? null : cm.getNetworkCapabilities(cm.getActiveNetwork());
            boolean online = nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
            boolean ethernet = nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
            wifi.sub = online ? (ethernet ? "Cable conectado" : "Conectado") : "Sin conexión";
            BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
            boolean btOn = false;
            try {
                btOn = bt != null && bt.isEnabled();
            } catch (SecurityException ignored) {
            }
            bluetooth.sub = btOn ? "Activado" : "Desactivado";
            invalidate();
        }

        /** The panel slides back out to the right (quickly), then `then` runs. */
        private void slideOutThen(Runnable then) {
            ValueAnimator out = ValueAnimator.ofFloat(slide, 0);
            out.setDuration(200);
            out.setInterpolator(new android.view.animation.AccelerateInterpolator(1.3f));
            out.addUpdateListener(a -> {
                slide = (float) a.getAnimatedValue();
                invalidate();
            });
            out.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator a) { then.run(); }
            });
            out.start();
        }

        private void open(Intent i) {
            finish();
            try {
                getContext().startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException e) {
                AppLog.w("quick settings: " + e.getMessage());
            }
        }

        private boolean closing, okPending, held;

        /** Back or the gear: the panel slides out the way it came (the scrim fades with it), then the screen goes. */
        void close() {
            if (closing) return;
            closing = true;
            slideOutThen(QuickSettingsActivity.this::finish);
        }

        boolean key(KeyEvent e) {
            if (closing) return true;   // on its way out
            if (e.getAction() != KeyEvent.ACTION_DOWN) {
                if (isOk(e.getKeyCode()) && okPending) {   // a tile with a hold: a short press acts on release
                    okPending = false;
                    if (!held) tiles.get(focus).stay.run();
                    held = false;
                }
                return e.getKeyCode() == KeyEvent.KEYCODE_BACK || isOk(e.getKeyCode());
            }
            int t = tiles.size();
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_BACK:
                    close();
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (t % 2 == 1 && focus == t - 1) focus = t - 2;   // the odd last tile sits on the right
                    else if (focus >= 2) focus -= 2;
                    break;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (focus + 2 < t) focus += 2;
                    else if (t % 2 == 1 && focus == t - 2) focus = t - 1;
                    break;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (focus % 2 == 1) focus--;
                    break;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (focus % 2 == 0 && focus + 1 < t) focus++;
                    break;
                default:
                    if (!isOk(e.getKeyCode())) return false;
                    Tile tile = tiles.get(focus);
                    if (tile.hold != null) {
                        if (e.getRepeatCount() == 0) {
                            okPending = true;
                            held = false;
                        } else if ((e.isLongPress() || e.getRepeatCount() >= 8) && !held) {
                            held = true;
                            tile.hold.run();
                        }
                        return true;
                    }
                    if (e.getRepeatCount() > 0) return true;
                    if (tile.stay != null) tile.stay.run();
                    else tile.action.run();
                    return true;
            }
            invalidate();
            return true;
        }

        private boolean isOk(int code) {
            return code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), pw = 600 * k;
            // The scrim over what's behind, darker towards the panel.
            fill.setShader(new LinearGradient(w - pw - 300 * k, 0, w - pw, 0, 0x00000000, Ui.alpha(0x99000000, slide), Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, fill);
            fill.setShader(null);
            float x0 = w - pw * slide;
            panelRect.set(x0, 0, x0 + pw + 60 * k, h);
            fill.setColor(0xF01B1B1E);
            c.drawRoundRect(panelRect, 44 * k, 44 * k, fill);
            rim.setColor(0x0FFFFFFF);   // a faint edge: the panel stands apart without a bright outline
            c.drawRoundRect(panelRect, 44 * k, 44 * k, rim);
            float x = x0 + 48 * k, right = x0 + pw - 48 * k;
            long now = System.currentTimeMillis();
            c.drawText(new SimpleDateFormat("HH:mm", Locale.US).format(new Date(now)), x, 140 * k, clock);   // as the home clock
            c.drawText(Ui.datePattern("EEEE d 'de' MMMM").format(now), x, 182 * k, date);
            drawWeather(c, right, x + date.measureText(Ui.datePattern("EEEE d 'de' MMMM").format(now)) + 24 * k);
            drawTiles(c, x, right, 226 * k);
            // The home screen's brightness applies to the panel too (only over it: the home behind is already dimmed).
            int dim = Prefs.launcherDim(getContext());
            if (dim > 0) {
                fill.setColor(Ui.alpha(0xFF000000, dim / 100f));
                c.drawRoundRect(panelRect, 44 * k, 44 * k, fill);
            }
        }

        private HomeData.Weather weather;
        private boolean weatherRead;
        private TextPaint temp;

        /** Beside the clock: the weather now (symbol and temperature) and, under it, its name. */
        private void drawWeather(Canvas c, float right, float dateEnd) {
            if (!weatherRead) {
                weatherRead = true;
                if (Prefs.weather()) weather = HomeData.saved(getContext());
            }
            HomeData.Weather wx = weather;
            if (wx == null || Double.isNaN(wx.temp) || wx.code < 0) return;
            if (temp == null) temp = new TextPaint(Ui.text(Ui.light, 52 * k, 0xFFFFFFFF));
            temp.setTextAlign(Paint.Align.RIGHT);
            String t = HomeData.degrees(wx.temp);
            c.drawText(t, right, 132 * k, temp);
            float tw = temp.measureText(t);
            Symbols.draw(getContext(), c, Symbols.weather(wx.code, wx.isDay), right - tw - 34 * k, 114 * k, 30 * k, 0xEBFFFFFF);
            String name = HomeData.condition(wx.code, wx.isDay);
            if (right - date.measureText(name) < dateEnd) return;   // no room beside the date: the symbol says it
            date.setTextAlign(Paint.Align.RIGHT);
            c.drawText(name, right, 182 * k, date);
            date.setTextAlign(Paint.Align.LEFT);
        }

        /** Two columns of tiles from `top`; returns where they end. */
        private float drawTiles(Canvas c, float x, float right, float top) {
            float gap = 14 * k, tw = (right - x - gap) / 2, th = 120 * k;
            for (int i = 0; i < tiles.size(); i++) {
                Tile t = tiles.get(i);
                // An odd last one (Change background) sits apart, at the panel's bottom right corner.
                boolean apart = tiles.size() % 2 == 1 && i == tiles.size() - 1;
                int col = apart ? 1 : i % 2;
                float tx = x + col * (tw + gap), ty = apart ? getHeight() - 56 * k - th : top + (i / 2) * (th + gap);
                r.set(tx, ty, tx + tw, ty + th);
                boolean on = focus == i;
                box(c, r, 26 * k, on);
                int ink = on ? 0xFF1C1C1E : 0xEBFFFFFF;
                drawGlyph(c, t.symbol, r.left + 38 * k, r.top + 38 * k, 15 * k, ink);
                title.setColor(on ? 0xFF1C1C1E : 0xFFFFFFFF);
                sub.setColor(on ? 0x991C1C1E : 0x99FFFFFF);
                c.drawText(fit(t.title, title, tw - 40 * k), r.left + 22 * k, r.bottom - 42 * k, title);
                c.drawText(fit(t.sub, sub, tw - 40 * k), r.left + 22 * k, r.bottom - 16 * k, sub);
            }
            int rows = (tiles.size() + 1) / 2;
            return top + rows * th + (rows - 1) * gap;
        }

        /** A tile: smoked glass with a faint rim; focused, white (the tvOS focus), slightly larger. */
        private void box(Canvas c, RectF rr, float rad, boolean on) {
            if (on) rr.inset(-6 * k, -4 * k);
            fill.setShader(null);
            fill.setColor(on ? 0xFFF5F5F7 : 0x1AFFFFFF);
            c.drawRoundRect(rr, rad, rad, fill);
            if (!on) {
                rim.setColor(0x1FFFFFFF);
                c.drawRoundRect(rr, rad, rad, rim);
            }
        }

        private String fit(String s, TextPaint p, float w) {
            return TextUtils.ellipsize(s, p, w, TextUtils.TruncateAt.END).toString();
        }

        /** Wi-Fi and Bluetooth drawn here; the rest from the Material symbols. */
        private void drawGlyph(Canvas c, String which, float cx, float cy, float u, int color) {
            if (which.equals("wifi")) {
                glyph.setColor(color);
                glyph.setStrokeWidth(2.6f * k);
                for (int i = 1; i <= 3; i++) {
                    float rr = u * 0.55f * i;
                    arc.set(cx - rr, cy + u * 0.8f - rr, cx + rr, cy + u * 0.8f + rr);
                    c.drawArc(arc, -135, 90, false, glyph);
                }
                fill.setColor(color);
                c.drawCircle(cx, cy + u * 0.8f, 2.4f * k, fill);
            } else if (which.equals("bluetooth")) {
                glyph.setColor(color);
                glyph.setStrokeWidth(2.6f * k);
                path.reset();
                path.moveTo(cx - u * 0.55f, cy - u * 0.5f);
                path.lineTo(cx + u * 0.5f, cy + u * 0.45f);
                path.lineTo(cx, cy + u);
                path.lineTo(cx, cy - u);
                path.lineTo(cx + u * 0.5f, cy - u * 0.45f);
                path.lineTo(cx - u * 0.55f, cy + u * 0.5f);
                c.drawPath(path, glyph);
            } else {
                Symbols.draw(getContext(), c, which, cx, cy, u * 1.9f, color);
            }
        }
    }
}
