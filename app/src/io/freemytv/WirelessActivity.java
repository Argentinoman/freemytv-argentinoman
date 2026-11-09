package io.freemytv;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.KeyEvent;
import android.view.View;

import java.io.IOException;

/**
 * The drawer's "Inalámbrica" (HY300): two cards, Android and Windows (the board's Miracast) and Apple devices
 * (AirPlay Server, open source, from F-Droid). Neither is on until chosen: the choice turns its app on and opens it,
 * and coming back here turns it off again, so nothing listens on the network while it isn't in use.
 * On the RK3326 the Android card is the board's QuickShare (Bozee: Miracast and casting), the only receiver it has; its
 * servers are blocked by the boot firewall, and it's off the rest of the time like the others.
 */
public class WirelessActivity extends Activity {
    static final String AIRPLAY = "io.github.jqssun.airplay";
    static final String MIRACAST = RemoteKeys.RK3326 ? "com.bozee.usbdisplay" : "com.droidlogic.miracast";
    private static final ComponentName MIRACAST_SCREEN = RemoteKeys.RK3326
            ? new ComponentName(MIRACAST, MIRACAST + ".StartupActivity")
            : new ComponentName(MIRACAST, MIRACAST + ".WiFiDirectMainActivity");

    private CardsView view;
    /** The receiver being opened, and the one that's running (turned off on the way back). */
    private String opening, running;
    private boolean busy;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Ui.init(this);
        view = new CardsView(this);
        setContentView(view);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (opening != null) {
            running = opening;
            opening = null;
        } else if (running == null) {
            finish();   // left with Home: next time it opens fresh, on the Apple card
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (running == null) return;
        final String pkg = running;
        running = null;
        busy = false;
        view.status = null;
        view.invalidate();
        new Thread(() -> shell("am force-stop " + pkg + "; pm disable-user --user 0 " + pkg), "wireless-off").start();
    }

    private boolean installed(String pkg) {
        try {
            getPackageManager().getApplicationInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** Turns the chosen receiver on (the projector's ADB, as the other switches) and opens it. */
    private void choose(int which) {
        if (busy) return;
        final String pkg = which == 0 ? MIRACAST : AIRPLAY;
        if (!installed(pkg)) {
            view.status = which == 0 ? "Este proyector no tiene Miracast." : "Falta instalar AirPlay Server.";
            view.invalidate();
            return;
        }
        busy = true;
        view.status = "Preparando…";
        view.invalidate();
        new Thread(() -> {
            boolean on = shell("pm enable " + pkg);
            runOnUiThread(() -> {
                Intent open = !on ? null : pkg.equals(MIRACAST) ? new Intent().setComponent(MIRACAST_SCREEN)
                        : getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
                if (open == null && on) open = getPackageManager().getLaunchIntentForPackage(pkg);
                try {
                    if (open == null) throw new IllegalStateException("no screen");
                    opening = pkg;
                    startActivity(open);
                } catch (RuntimeException e) {
                    AppLog.w("wireless: " + e.getMessage());
                    opening = null;
                    busy = false;
                    view.status = "No se pudo abrir. Probá de nuevo.";
                    view.invalidate();
                }
            });
        }, "wireless-on").start();
    }

    /** Back on Home: AirPlay or Miracast still on (left with Home, not Back) is closed and turned off. */
    static void offIfOn(Context c) {
        if (!RemoteKeys.PROJECTOR) return;
        final Context app = c.getApplicationContext();
        StringBuilder cmd = new StringBuilder();
        for (String pkg : new String[] {MIRACAST, AIRPLAY}) {
            int state;
            try {
                state = app.getPackageManager().getApplicationEnabledSetting(pkg);
            } catch (IllegalArgumentException notInstalled) {
                continue;
            }
            if (state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER) {
                cmd.append("am force-stop ").append(pkg).append("; pm disable-user --user 0 ").append(pkg).append("; ");
            }
        }
        if (cmd.length() == 0) return;
        new Thread(() -> {
            try (Adb adb = Adb.connect(app, 3000, null)) {
                adb.shell(cmd.toString());
            } catch (IOException e) {
                AppLog.w("wireless: " + e.getMessage());
            }
        }, "wireless-off").start();
    }

    private boolean shell(String cmd) {
        try (Adb adb = Adb.connect(this, 3000, null)) {
            adb.shell(cmd);
            return true;
        } catch (IOException e) {
            AppLog.w("wireless: " + e.getMessage());
            return false;
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER;
        if (code == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP) finish();
            return true;
        }
        if (!ok && code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_DPAD_RIGHT
                && code != KeyEvent.KEYCODE_DPAD_UP && code != KeyEvent.KEYCODE_DPAD_DOWN) {
            return super.dispatchKeyEvent(e);
        }
        if (e.getAction() != KeyEvent.ACTION_DOWN || e.getRepeatCount() > 0) return true;
        if (ok) {
            choose(view.focus);
        } else if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            int next = code == KeyEvent.KEYCODE_DPAD_LEFT ? 0 : 1;
            if (next != view.focus && !busy) {
                view.focus = next;
                view.status = null;
                view.invalidate();
            }
        }
        return true;
    }

    /** The two cards, in the launcher's look: the focused one white, the other glass. */
    private final class CardsView extends View {
        private final float k;
        private final TextPaint heading, sub, title, kind, body, hint;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final String[] titles = {"Android / Windows", "Dispositivos Apple"};
        private final String[] kinds = {"Miracast", "AirPlay"};
        private final String[] symbols = {"android", "apple"};
        private final String[] texts = {
            "En Android, desde Smart View o \"Transmitir pantalla\". En Windows, con Win + K, eligiendo el proyector.",
            "En el iPhone o iPad, desde el Centro de control: Duplicar pantalla. En la Mac, desde el ícono de pantalla.",
        };
        private final StaticLayout[] layouts = new StaticLayout[2];
        int focus = 1;   // Apple first: the one who'll use it has an iPhone
        String status;

        CardsView(Context c) {
            super(c);
            k = c.getResources().getDisplayMetrics().heightPixels / 1080f;
            heading = new TextPaint(Ui.text(Ui.semibold, 44 * k, 0xFFFFFFFF));
            sub = new TextPaint(Ui.text(Ui.regular, 24 * k, 0x99FFFFFF));
            title = new TextPaint(Ui.text(Ui.semibold, 38 * k, 0xFFFFFFFF));
            kind = new TextPaint(Ui.text(Ui.medium, 24 * k, 0x99FFFFFF));
            body = new TextPaint(Ui.text(Ui.regular, 25 * k, 0xB3FFFFFF));
            hint = new TextPaint(Ui.text(Ui.regular, 24 * k, 0xB3FFFFFF));
            rim.setStyle(Paint.Style.STROKE);
            rim.setStrokeWidth(1.5f * k);
            setBackgroundColor(0xFF0E0E10);
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            int width = Math.round(cardWidth(w) - 2 * 48 * k);
            for (int i = 0; i < 2; i++) {
                layouts[i] = StaticLayout.Builder.obtain(texts[i], 0, texts[i].length(), body, Math.max(1, width))
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(6 * k, 1).build();
            }
        }

        private float cardWidth(float w) {
            return Math.min(640 * k, (w - 2 * 120 * k - 48 * k) / 2);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), x = 120 * k;
            Symbols.draw(getContext(), c, "cast", x + 22 * k, 92 * k, 34 * k, 0xEBFFFFFF);
            c.drawText("Inalámbrica", x + 64 * k, 108 * k, heading);
            c.drawText("Elegí desde qué dispositivo vas a transmitir", x, 152 * k, sub);
            float cw = cardWidth(w), ch = 560 * k, gap = 48 * k, left = (w - 2 * cw - gap) / 2, top = 220 * k;
            for (int i = 0; i < 2; i++) {
                boolean on = i == focus;
                r.set(left + i * (cw + gap), top, left + i * (cw + gap) + cw, top + ch);
                if (on) r.inset(-8 * k, -8 * k);
                fill.setColor(on ? 0xFFF5F5F7 : 0x1AFFFFFF);
                c.drawRoundRect(r, 32 * k, 32 * k, fill);
                if (!on) {
                    rim.setColor(0x1FFFFFFF);
                    c.drawRoundRect(r, 32 * k, 32 * k, rim);
                }
                float px = r.left + 48 * k;
                Symbols.draw(getContext(), c, symbols[i], px + 64 * k, r.top + 120 * k, 88 * k, on ? 0xFF1C1C1E : 0xE6FFFFFF);
                title.setColor(on ? 0xFF1C1C1E : 0xFFFFFFFF);
                c.drawText(titles[i], px, r.top + 268 * k, title);
                kind.setColor(on ? 0xB31C1C1E : 0x99FFFFFF);
                c.drawText(kinds[i], px, r.top + 312 * k, kind);
                if (layouts[i] != null) {
                    body.setColor(on ? 0xCC1C1C1E : 0xB3FFFFFF);
                    c.save();
                    c.translate(px, r.top + 352 * k);
                    layouts[i].draw(c);
                    c.restore();
                }
                if (on && status != null) {
                    kind.setColor(0xFF1C1C1E);
                    c.drawText(status, px, r.bottom - 44 * k, kind);
                }
            }
            c.drawText("← → para elegir · OK activa · al volver acá se apaga solo", x, h - 56 * k, hint);
        }
    }
}
