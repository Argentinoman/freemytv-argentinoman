package io.freemytv;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.tv.TvContract;
import android.media.tv.TvInputManager;
import android.media.tv.TvView;
import android.os.Bundle;
import android.text.TextPaint;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;

/**
 * The HDMI input, full screen, in place of the board's live TV app (DroidLogic's, which also carries a British
 * Freeview client): a TvView tuned to the input, its name for a moment, and "Sin señal" while nothing comes in.
 * Back goes home; the other keys go on to the input (HDMI-CEC hands them to the device).
 */
public class HdmiActivity extends Activity {
    static final String EXTRA_INPUT = "input", EXTRA_LABEL = "label";

    private TvView tv;
    private Overlay overlay;
    private String inputId, label;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Ui.init(this);
        inputId = getIntent().getStringExtra(EXTRA_INPUT);
        label = getIntent().getStringExtra(EXTRA_LABEL);
        if (label == null || label.isEmpty()) label = "HDMI";
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        tv = new TvView(this);
        overlay = new Overlay(this);
        root.addView(tv, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(overlay, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
        tv.setCallback(new TvView.TvInputCallback() {
            @Override public void onVideoAvailable(String id) { overlay.show(null); }
            @Override public void onVideoUnavailable(String id, int reason) {
                overlay.show(reason == TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING ? "Conectando…" : "Sin señal");
            }
            @Override public void onConnectionFailed(String id) { overlay.show("No se pudo abrir la entrada"); }
            @Override public void onDisconnected(String id) { overlay.show("Sin señal"); }
        });
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        String id = intent.getStringExtra(EXTRA_INPUT);
        if (id != null && !id.equals(inputId)) {
            inputId = id;
            String l = intent.getStringExtra(EXTRA_LABEL);
            label = l == null || l.isEmpty() ? "HDMI" : l;
            tune();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        tune();
    }

    private void tune() {
        if (inputId == null) {
            overlay.show("No se pudo abrir la entrada");
            return;
        }
        overlay.show("Conectando…");
        tv.tune(inputId, TvContract.buildChannelUriForPassthroughInput(inputId));
    }

    @Override
    protected void onStop() {
        tv.reset();   // let the input go: the picture and sound stop with the screen
        super.onStop();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP) finish();
            return true;
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN) overlay.show(overlay.message);   // the name again, on any key
        return super.dispatchKeyEvent(e);
    }

    /** The input's name in a pill at the top for a few seconds, and a message in the middle while there's no picture. */
    private final class Overlay extends View {
        private final float k;
        private final TextPaint name, big, small;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF pill = new RectF();
        String message;
        private float pillAlpha;
        private ValueAnimator fade;

        Overlay(Context c) {
            super(c);
            k = c.getResources().getDisplayMetrics().heightPixels / 1080f;
            name = new TextPaint(Ui.text(Ui.medium, 30 * k, 0xFFFFFFFF));
            big = new TextPaint(Ui.text(Ui.light, 56 * k, 0xFFFFFFFF));
            big.setTextAlign(Paint.Align.CENTER);
            small = new TextPaint(Ui.text(Ui.regular, 26 * k, 0x99FFFFFF));
            small.setTextAlign(Paint.Align.CENTER);
        }

        void show(String msg) {
            message = msg;
            if (fade != null) fade.cancel();
            pillAlpha = 1;
            fade = ValueAnimator.ofFloat(1, 0);
            fade.setStartDelay(3000);
            fade.setDuration(600);
            fade.addUpdateListener(a -> {
                pillAlpha = (float) a.getAnimatedValue();
                invalidate();
            });
            fade.start();
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            if (message != null) {
                // Opaque: the board's input service draws its own blue "No Signal" under it, which this covers.
                c.drawColor(0xFF0E0E10);
                Symbols.draw(getContext(), c, "hdmi", w / 2, h / 2 - 90 * k, 60 * k, 0x99FFFFFF);
                c.drawText(message, w / 2, h / 2 + 10 * k, big);
                c.drawText(message.equals("Sin señal") ? "Revisá que el equipo esté prendido y el cable bien enchufado."
                        : label, w / 2, h / 2 + 60 * k, small);
            }
            if (pillAlpha <= 0.01f) return;
            float tw = name.measureText(label);
            pill.set(60 * k, 56 * k, 60 * k + tw + 120 * k, 56 * k + 72 * k);
            fill.setColor(Ui.alpha(0xD91B1B1E, pillAlpha));
            c.drawRoundRect(pill, 36 * k, 36 * k, fill);
            Symbols.draw(getContext(), c, "hdmi", pill.left + 48 * k, pill.centerY(), 26 * k, Ui.alpha(0xEBFFFFFF, pillAlpha));
            name.setAlpha(Math.round(255 * pillAlpha));
            c.drawText(label, pill.left + 84 * k, pill.centerY() + 10 * k, name);
        }
    }
}
