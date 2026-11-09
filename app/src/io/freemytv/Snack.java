package io.freemytv;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.text.TextPaint;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/**
 * A short notice at the top middle, over whatever is on screen: Free My TV's dark smoked pill with white text, in
 * place of Android's toast (which carries the app's icon and its own colour). Drawn by RemoteKeyService, the only one
 * of ours that may draw over other apps (an accessibility overlay); call it on the main thread with the service.
 */
final class Snack extends View {
    private static final long SHOWN_MS = 2000;
    private static Snack view;

    private final float k;
    private final TextPaint text;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private String message = "";
    private float alpha;
    private ValueAnimator fade;
    private final Runnable hide = () -> animateTo(0, 300);

    static void show(Context service, String message) {
        if (view == null) {
            Ui.init(service);
            view = new Snack(service);
            WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, Math.round(200 * view.k),
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT);
            p.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            p.y = 0;
            try {
                ((WindowManager) service.getSystemService(Context.WINDOW_SERVICE)).addView(view, p);
            } catch (RuntimeException e) {
                AppLog.w("snack: " + e.getMessage());
                view = null;
                return;
            }
        }
        view.message = message;
        view.animateTo(1, 180);
        view.removeCallbacks(view.hide);
        view.postDelayed(view.hide, SHOWN_MS);
        view.invalidate();
    }

    static void stop(Context service) {
        if (view == null) return;
        try {
            ((WindowManager) service.getSystemService(Context.WINDOW_SERVICE)).removeView(view);
        } catch (RuntimeException ignored) {
        }
        view = null;
    }

    private Snack(Context c) {
        super(c);
        k = c.getResources().getDisplayMetrics().heightPixels / 1080f;
        text = new TextPaint(Ui.text(Ui.medium, 26 * k, 0xF2FFFFFF));   // Home's notice: same type
        text.setTextAlign(Paint.Align.CENTER);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(1.5f * k);
    }

    private void animateTo(float to, long ms) {
        if (fade != null) fade.cancel();
        fade = ValueAnimator.ofFloat(alpha, to);
        fade.setDuration(ms);
        fade.addUpdateListener(a -> {
            alpha = (float) a.getAnimatedValue();
            invalidate();
        });
        fade.start();
    }

    @Override
    protected void onDraw(Canvas c) {
        if (alpha <= 0.01f || message.isEmpty()) return;
        float w = getWidth(), padX = 28 * k, ph = Ui.halfCap(text) * 2 + 2 * 18 * k + 14 * k;
        float tw = text.measureText(message);
        float drop = (1 - alpha) * 14 * k;   // slides down a little as it appears
        r.set(w / 2 - tw / 2 - padX, 64 * k - drop, w / 2 + tw / 2 + padX, 64 * k + ph - drop);   // where Home's sits
        fill.setColor(Ui.alpha(0xE6111113, alpha));   // Home's smoked glass, without its blur (an overlay can't read what's behind)
        c.drawRoundRect(r, Math.min(ph / 2, 32 * k), Math.min(ph / 2, 32 * k), fill);
        rim.setColor(Ui.alpha(0x1FFFFFFF, alpha));
        c.drawRoundRect(r, Math.min(ph / 2, 32 * k), Math.min(ph / 2, 32 * k), rim);
        text.setAlpha(Math.round(255 * alpha));
        c.drawText(message, w / 2, r.centerY() + Ui.halfCap(text), text);
    }
}
