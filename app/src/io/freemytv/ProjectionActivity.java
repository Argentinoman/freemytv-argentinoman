package io.freemytv;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Bundle;
import android.text.TextPaint;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * The projector's picture (HY300), in the quick panel's look: a panel on the right, so the picture behind it is seen
 * changing on the wall. Automatic keystone, the manual angles, zoom and projection mode; ← → change, live.
 * "Esquinas" hides the panel and frames the screen: the arrows choose a corner, OK grabs it and the arrows move it.
 */
public class ProjectionActivity extends Activity {
    private PanelView panel;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Ui.init(this);
        panel = new PanelView(this);
        setContentView(panel);
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

    private final class PanelView extends View {
        private static final int AUTO = 0, VERTICAL = 1, HORIZONTAL = 2, ZOOM = 3, MODE = 4, CORNERS = 5, RESET = 6, ROWS = 7;
        private final String[] CORNER_NAMES = {"Arriba a la izquierda", "Arriba a la derecha",
                "Abajo a la derecha", "Abajo a la izquierda"};
        private final float k;
        private final Projector.Setting s;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint heading, label, value, hint, guide;
        private final RectF r = new RectF(), panelRect = new RectF();
        private final LinearGradient[] shade = new LinearGradient[1];
        private int focus;
        private float slide;
        private boolean closing;
        /** Moving the corners by hand: which one (0 top-left … 3 bottom-left). */
        private boolean editing, grabbed;
        private int corner;
        private final android.graphics.Path chevron = new android.graphics.Path();

        PanelView(Context c) {
            super(c);
            k = c.getResources().getDisplayMetrics().heightPixels / 1080f;
            s = Projector.Setting.read(c);
            heading = new TextPaint(Ui.text(Ui.semibold, 44 * k, 0xFFFFFFFF));
            label = new TextPaint(Ui.text(Ui.medium, 28 * k, 0xFFFFFFFF));
            value = new TextPaint(Ui.text(Ui.regular, 26 * k, 0x99FFFFFF));
            value.setTextAlign(Paint.Align.RIGHT);
            hint = new TextPaint(Ui.text(Ui.regular, 21 * k, 0x80FFFFFF));
            guide = new TextPaint(Ui.text(Ui.medium, 34 * k, 0xFFFFFFFF));
            guide.setTextAlign(Paint.Align.CENTER);
            rim.setStyle(Paint.Style.STROKE);
            rim.setStrokeWidth(1.5f * k);
            ValueAnimator in = ValueAnimator.ofFloat(0, 1);
            in.setDuration(280);
            in.setInterpolator(new DecelerateInterpolator(2f));
            in.addUpdateListener(a -> {
                slide = (float) a.getAnimatedValue();
                invalidate();
            });
            in.start();
            if (s.auto) measure();
        }

        /** The tilt now, for the vertical row while it's automatic (read off the UI thread). */
        private void measure() {
            new Thread(() -> {
                Projector.measuredTilt(getContext());
                post(this::invalidate);
            }).start();
        }

        private String text(int row) {
            switch (row) {
                case AUTO: return s.auto ? "Sí" : "No";
                case VERTICAL: return s.auto ? "Automático · " + degrees(Projector.lastTilt) : degrees(s.vertical);
                case HORIZONTAL: return degrees(s.auto ? 0 : s.horizontal);
                case ZOOM: return s.zoom + " %";
                case MODE: return Projector.MODES[s.mode];
                case CORNERS: return s.cornersMoved() ? "Ajustadas" : "Sin mover";
                default: return "";
            }
        }

        private String name(int row) {
            switch (row) {
                case AUTO: return "Trapecio automático";
                case VERTICAL: return "Vertical";
                case HORIZONTAL: return "Horizontal";
                case ZOOM: return "Tamaño";
                case MODE: return "Modo de proyección";
                case CORNERS: return "Esquinas";
                default: return "Restablecer";
            }
        }

        private String degrees(int d) {
            return (d > 0 ? "+" : d < 0 ? "−" : "") + Math.abs(d) + "°";
        }

        /** ← or → on a row: the change, saved and on the wall at once. */
        private void change(int dir) {
            switch (focus) {
                case AUTO:
                    s.auto = !s.auto;
                    if (!s.auto) s.vertical = Projector.lastTilt;   // manual starts where automatic was
                    else measure();
                    break;
                case VERTICAL:
                    if (s.auto) {
                        s.auto = false;
                        s.vertical = Projector.lastTilt;
                    }
                    s.vertical = Math.max(-Projector.MAX_ANGLE, Math.min(Projector.MAX_ANGLE, s.vertical + dir));
                    break;
                case HORIZONTAL:
                    if (s.auto) {
                        s.auto = false;
                        s.vertical = Projector.lastTilt;
                    }
                    s.horizontal = Math.max(-Projector.MAX_ANGLE, Math.min(Projector.MAX_ANGLE, s.horizontal + dir));
                    break;
                case ZOOM:
                    s.zoom = Math.max(Projector.MIN_ZOOM, Math.min(100, s.zoom + 2 * dir));
                    break;
                case MODE:
                    s.mode = (s.mode + dir + Projector.MODES.length) % Projector.MODES.length;
                    break;
                case CORNERS:
                    // ← → on the row also open the editor. The automatic tilt goes off there: it measured the sensor
                    // at every step and moved the corners on its own.
                    if (s.auto) {
                        s.auto = false;
                        s.vertical = Projector.lastTilt;
                        s.save(getContext());
                    }
                    editing = true;
                    grabbed = false;
                    invalidate();
                    return;
                default:
                    return;
            }
            s.save(getContext());
            Projector.apply(getContext(), s);
            invalidate();
        }

        private void reset() {
            s.auto = true;
            s.vertical = 0;
            s.horizontal = 0;
            s.zoom = 100;
            s.resetCorners();
            s.save(getContext());   // the projection mode stays: it's where the projector hangs
            Projector.apply(getContext(), s);
            measure();
            invalidate();
        }

        void close() {
            if (closing) return;
            closing = true;
            ValueAnimator out = ValueAnimator.ofFloat(slide, 0);
            out.setDuration(200);
            out.addUpdateListener(a -> {
                slide = (float) a.getAnimatedValue();
                invalidate();
            });
            out.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator a) { finish(); }
            });
            out.start();
        }

        /**
         * The corner editor's keys, in two steps. Choosing: the arrows go to the corner on that side, OK grabs it, Back
         * goes back to the panel. Grabbed: the arrows move it (held, faster), OK or Back let it go.
         */
        private boolean editKey(KeyEvent e, int code, boolean ok) {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
            boolean left = code == KeyEvent.KEYCODE_DPAD_LEFT, right = code == KeyEvent.KEYCODE_DPAD_RIGHT,
                    up = code == KeyEvent.KEYCODE_DPAD_UP, down = code == KeyEvent.KEYCODE_DPAD_DOWN;
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU) {
                if (grabbed) grabbed = false;
                else editing = false;
            } else if (ok) {
                if (e.getRepeatCount() == 0) grabbed = !grabbed;
            } else if (!(left || right || up || down)) {
                return false;
            } else if (grabbed) {
                // On the 1920×1080 frame: ~5 px on this screen a press, then faster while held.
                int n = e.getRepeatCount(), step = n < 4 ? 8 : n < 16 ? 20 : 40;
                s.moveCorner(corner, left ? -step : right ? step : 0, up ? -step : down ? step : 0);
                s.save(getContext());
                Projector.apply(getContext(), s);
            } else if (e.getRepeatCount() == 0) {
                boolean top = corner < 2, west = corner == 0 || corner == 3;
                if (left) west = true;
                if (right) west = false;
                if (up) top = true;
                if (down) top = false;
                corner = top ? (west ? 0 : 1) : (west ? 3 : 2);
            }
            invalidate();
            return true;
        }

        boolean key(KeyEvent e) {
            if (closing) return true;
            int code = e.getKeyCode();
            boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER;
            if (editing) return editKey(e, code, ok);
            if (e.getAction() != KeyEvent.ACTION_DOWN) return code == KeyEvent.KEYCODE_BACK || ok;
            switch (code) {
                case KeyEvent.KEYCODE_BACK: close(); return true;
                case KeyEvent.KEYCODE_DPAD_UP: focus = Math.max(0, focus - 1); break;
                case KeyEvent.KEYCODE_DPAD_DOWN: focus = Math.min(ROWS - 1, focus + 1); break;
                case KeyEvent.KEYCODE_DPAD_LEFT: change(-1); return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT: change(1); return true;
                default:
                    if (!ok) return false;
                    if (e.getRepeatCount() > 0) return true;
                    if (focus == RESET) reset();
                    else if (focus == AUTO || focus == MODE || focus == CORNERS) change(1);
                    return true;
            }
            invalidate();
            return true;
        }

        /**
         * The corner editor: a white frame on the screen's edges (warped like the picture, so the wall shows the shape),
         * a ring on the chosen corner, dots on the others, and what the keys do in the middle.
         */
        private void drawEditor(Canvas c, float w, float h) {
            c.drawColor(0x73000000);
            rim.setColor(0xFFFFFFFF);
            rim.setStrokeWidth(6 * k);
            float m = 4 * k;
            c.drawRect(m, m, w - m, h - m, rim);
            float[] cx = {0, w, w, 0}, cy = {0, 0, h, h};
            for (int i = 0; i < 4; i++) {
                float x = cx[i] == 0 ? 120 * k : w - 120 * k, y = cy[i] == 0 ? 120 * k : h - 120 * k;
                boolean on = i == corner;
                fill.setColor(on ? 0xFFFFFFFF : 0x99FFFFFF);
                c.drawCircle(x, y, (on ? (grabbed ? 26 : 20) : 12) * k, fill);
                if (!on) continue;
                rim.setStrokeWidth(4 * k);
                c.drawCircle(x, y, 40 * k, rim);
                if (grabbed) {   // four arrows around it: it moves now
                    for (int d = 0; d < 4; d++) {
                        float dx = d == 0 ? -1 : d == 1 ? 1 : 0, dy = d == 2 ? -1 : d == 3 ? 1 : 0;
                        float tx = x + dx * 66 * k, ty = y + dy * 66 * k, s1 = 14 * k;
                        chevron.reset();
                        chevron.moveTo(tx + dx * s1, ty + dy * s1);
                        chevron.lineTo(tx - dx * s1 + dy * s1, ty - dy * s1 + dx * s1);
                        chevron.lineTo(tx - dx * s1 - dy * s1, ty - dy * s1 - dx * s1);
                        chevron.close();
                        c.drawPath(chevron, fill);
                    }
                }
            }
            rim.setStrokeWidth(1.5f * k);
            // The instructions in a pill in the middle, readable over any picture.
            String what = grabbed ? "Mové la esquina con las flechas" : "Elegí una esquina con las flechas";
            String keys = grabbed ? "OK la suelta" : "OK la agarra para moverla · Atrás vuelve";
            float pw = Math.max(guide.measureText(what), hint.measureText(keys)) + 80 * k;
            r.set(w / 2 - pw / 2, h / 2 - 90 * k, w / 2 + pw / 2, h / 2 + 70 * k);
            fill.setColor(0xE61B1B1E);
            c.drawRoundRect(r, 32 * k, 32 * k, fill);
            value.setTextAlign(Paint.Align.CENTER);
            value.setColor(0x99FFFFFF);
            c.drawText(CORNER_NAMES[corner], w / 2, h / 2 - 44 * k, value);
            value.setTextAlign(Paint.Align.RIGHT);
            c.drawText(what, w / 2, h / 2 + 4 * k, guide);
            hint.setTextAlign(Paint.Align.CENTER);
            c.drawText(keys, w / 2, h / 2 + 44 * k, hint);
            hint.setTextAlign(Paint.Align.LEFT);
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldW, int oldH) {
            float pw = 600 * k;
            shade[0] = new LinearGradient(w - pw - 300 * k, 0, w - pw, 0, 0x00000000, 0x99000000, Shader.TileMode.CLAMP);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), pw = 600 * k;
            if (editing) {
                drawEditor(c, w, h);
                return;
            }
            fill.setShader(shade[0]);
            fill.setAlpha(Math.round(255 * slide));
            c.drawRect(0, 0, w, h, fill);
            fill.setShader(null);
            fill.setAlpha(255);
            float x0 = w - pw * slide;
            panelRect.set(x0, 0, x0 + pw + 60 * k, h);
            fill.setColor(0xF01B1B1E);
            c.drawRoundRect(panelRect, 44 * k, 44 * k, fill);
            rim.setColor(0x0FFFFFFF);
            c.drawRoundRect(panelRect, 44 * k, 44 * k, rim);
            float x = x0 + 48 * k, right = x0 + pw - 48 * k;
            Symbols.draw(getContext(), c, "keystone", x + 22 * k, 116 * k, 34 * k, 0xEBFFFFFF);
            c.drawText("Proyección", x + 64 * k, 132 * k, heading);
            float top = 196 * k, rh = 88 * k, gap = 12 * k;
            for (int i = 0; i < ROWS; i++) {
                r.set(x, top + i * (rh + gap), right, top + i * (rh + gap) + rh);
                boolean on = focus == i;
                if (on) r.inset(-6 * k, -4 * k);
                fill.setColor(on ? 0xFFF5F5F7 : 0x1AFFFFFF);
                c.drawRoundRect(r, 24 * k, 24 * k, fill);
                if (!on) {
                    rim.setColor(0x1FFFFFFF);
                    c.drawRoundRect(r, 24 * k, 24 * k, rim);
                }
                float base = r.centerY() + 10 * k;
                label.setColor(on ? 0xFF1C1C1E : 0xFFFFFFFF);
                c.drawText(name(i), r.left + 26 * k, base, label);
                String v = text(i);
                if (!v.isEmpty()) {
                    value.setColor(on ? 0xB31C1C1E : 0x99FFFFFF);
                    boolean arrows = on && i != RESET;
                    c.drawText(arrows ? "‹  " + v + "  ›" : v, r.right - 26 * k, base, value);
                }
            }
            c.drawText("← → ajustan en vivo. El foco es la rueda del costado.", x, h - 56 * k, hint);
        }
    }
}
