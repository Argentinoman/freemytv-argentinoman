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
 * "Esquinas" hides the panel and frames the screen: OK picks the next corner, the arrows move it, Back returns.
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
        private boolean editing;
        private int corner;

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
                    editing = true;   // ← → on the row also open the editor
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

        /** The corner editor's keys: OK the next corner, the arrows move it (held, faster), Back the panel again. */
        private boolean editKey(KeyEvent e, int code, boolean ok) {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
            int step = e.getRepeatCount() < 3 ? 4 : e.getRepeatCount() < 15 ? 12 : 24;   // on the 1920×1080 frame
            switch (code) {
                case KeyEvent.KEYCODE_BACK:
                case KeyEvent.KEYCODE_MENU:
                    editing = false;
                    break;
                case KeyEvent.KEYCODE_DPAD_LEFT: s.moveCorner(corner, -step, 0); moved(); break;
                case KeyEvent.KEYCODE_DPAD_RIGHT: s.moveCorner(corner, step, 0); moved(); break;
                case KeyEvent.KEYCODE_DPAD_UP: s.moveCorner(corner, 0, -step); moved(); break;
                case KeyEvent.KEYCODE_DPAD_DOWN: s.moveCorner(corner, 0, step); moved(); break;
                default:
                    if (!ok) return false;
                    if (e.getRepeatCount() == 0) corner = (corner + 1) % 4;
            }
            invalidate();
            return true;
        }

        private void moved() {
            s.save(getContext());
            Projector.apply(getContext(), s);
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
            c.drawColor(0xB3000000);
            rim.setColor(0xFFFFFFFF);
            rim.setStrokeWidth(4 * k);
            float m = 3 * k;
            c.drawRect(m, m, w - m, h - m, rim);
            rim.setStrokeWidth(1.5f * k);
            float[] cx = {0, w, w, 0}, cy = {0, 0, h, h};
            for (int i = 0; i < 4; i++) {
                float x = cx[i] == 0 ? 40 * k : w - 40 * k, y = cy[i] == 0 ? 40 * k : h - 40 * k;
                fill.setColor(i == corner ? 0xFFFFFFFF : 0x80FFFFFF);
                c.drawCircle(x, y, (i == corner ? 18 : 9) * k, fill);
                if (i == corner) {
                    rim.setColor(0xFFFFFFFF);
                    rim.setStrokeWidth(3 * k);
                    c.drawCircle(x, y, 30 * k, rim);
                    rim.setStrokeWidth(1.5f * k);
                }
            }
            c.drawText(CORNER_NAMES[corner], w / 2, h / 2 - 20 * k, guide);
            hint.setTextAlign(Paint.Align.CENTER);
            c.drawText("Flechas: mover la esquina · OK: la siguiente · Atrás: listo", w / 2, h / 2 + 30 * k, hint);
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
