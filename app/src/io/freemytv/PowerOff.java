package io.freemytv;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.PixelFormat;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

/**
 * Power off and on, like a Samsung TV: black closes in from the top and the bottom to the middle the moment the power
 * button is pressed (RemoteKeyService watches for it), over before the TV fades the screen out itself; and when the
 * screen comes back, the two halves open from the middle and go. An accessibility overlay (no extra permission).
 *
 * Two plain black views, drawn once and only moved while it plays: over the home screen the GPU has its hands full,
 * and repainting the whole screen every frame stuttered there.
 */
final class PowerOff extends FrameLayout {
    private static final long CLOSE_MS = 150, OPEN_MS = 260;   // quick: the TV starts its own fade soon after
    private static PowerOff shown;

    private final View top, bottom;

    private PowerOff(Context c) {
        super(c);
        top = new View(c);
        bottom = new View(c);
        top.setBackgroundColor(0xFF000000);
        bottom.setBackgroundColor(0xFF000000);
        addView(top);
        addView(bottom);
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l, h = b - t, half = h / 2 + 2;   // a little overlap: no seam once they meet
        top.layout(0, 0, w, half);
        bottom.layout(0, h - half, w, h);
        if (changed && top.getTranslationY() == 0) {   // start outside, then slide in
            top.setTranslationY(-half);
            bottom.setTranslationY(half);
        }
    }

    private void play() {
        top.animate().translationY(0).setDuration(CLOSE_MS).setInterpolator(new AccelerateInterpolator(1.3f)).start();
        bottom.animate().translationY(0).setDuration(CLOSE_MS).setInterpolator(new AccelerateInterpolator(1.3f)).start();
        // The middle goes dark meanwhile: should the TV cut in before the halves meet, it's black already, no band left.
        setBackgroundColor(0xFF000000);
        getBackground().setAlpha(0);
        android.animation.ValueAnimator dark = android.animation.ValueAnimator.ofInt(0, 255);
        dark.setDuration(CLOSE_MS);
        dark.addUpdateListener(a -> getBackground().setAlpha((int) a.getAnimatedValue()));
        dark.start();
    }

    /** The screen is back: the halves open from the middle, then the overlay goes. */
    private void open(Context c) {
        top.animate().cancel();
        bottom.animate().cancel();
        setBackground(null);
        top.setTranslationY(0);
        bottom.setTranslationY(0);
        top.animate().translationY(-top.getHeight()).setDuration(OPEN_MS).setInterpolator(new DecelerateInterpolator(1.6f)).start();
        bottom.animate().translationY(bottom.getHeight()).setDuration(OPEN_MS).setInterpolator(new DecelerateInterpolator(1.6f))
                .withEndAction(() -> remove(c, this)).start();
    }

    static void show(AccessibilityService s) {
        hide(s);
        AppLog.w("power-off animation at " + SystemClock.uptimeMillis());
        PowerOff v = new PowerOff(s);
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_FULLSCREEN
                        // A service's window draws in software unless asked.
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        p.windowAnimations = 0;
        try {
            ((WindowManager) s.getSystemService(Context.WINDOW_SERVICE)).addView(v, p);
            shown = v;
        } catch (RuntimeException e) {
            AppLog.w("power-off animation: " + e.getMessage());
            return;
        }
        v.post(v::play);   // from the first frame on screen: the window's set-up doesn't eat the animation
        v.watch(s);
    }

    /**
     * A safety net: should the screen-on news never come (or the TV not go to sleep after all), the black opens by
     * itself once the TV has been awake for a moment. The screen is never left covered.
     */
    private void watch(Context c) {
        final android.os.PowerManager pm = (android.os.PowerManager) c.getSystemService(Context.POWER_SERVICE);
        postDelayed(new Runnable() {
            int awakeChecks;
            @Override public void run() {
                if (shown != PowerOff.this) return;
                awakeChecks = pm.isInteractive() ? awakeChecks + 1 : 0;
                if (awakeChecks >= 3) reveal(c);
                else postDelayed(this, 1000);
            }
        }, 1000);
    }

    /** The screen is back: open up and go. */
    static void reveal(Context c) {
        PowerOff v = shown;
        shown = null;
        if (v != null) v.open(c);
    }

    /** Take it away at once (replaced, or the service stops). */
    static void hide(Context c) {
        PowerOff v = shown;
        shown = null;
        if (v != null) remove(c, v);
    }

    private static void remove(Context c, View v) {
        try {
            ((WindowManager) c.getSystemService(Context.WINDOW_SERVICE)).removeView(v);
        } catch (RuntimeException ignored) {
        }
    }
}
