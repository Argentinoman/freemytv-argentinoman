package io.freemytv;

import android.content.Context;
import android.graphics.PixelFormat;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.widget.FrameLayout;

/**
 * The quick panel over the app in front, from the remote's Options button: an overlay window of RemoteKeyService, not
 * an activity, so the app behind stays the resumed one and keeps playing (an activity on top paused Netflix, YouTube...).
 * It takes the keys while open; Back, or Options again, closes it. Main thread, with the service as the context.
 */
final class QuickOverlay {
    private QuickOverlay() {}

    private static Host host;

    /** Opens it, or closes it if it's open (Options works as a switch, as with the activity). */
    static void toggle(Context service) {
        if (host != null) {
            host.panel.close();
            return;
        }
        Ui.init(service);
        host = new Host(service);
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        try {
            ((WindowManager) service.getSystemService(Context.WINDOW_SERVICE)).addView(host, p);
            host.requestFocus();
        } catch (RuntimeException e) {
            AppLog.w("quick panel overlay: " + e.getMessage());
            host = null;
        }
    }

    static void close(Context service) {
        if (host == null) return;
        Host h = host;
        host = null;
        try {
            ((WindowManager) service.getSystemService(Context.WINDOW_SERVICE)).removeView(h);
        } catch (RuntimeException ignored) {
        }
    }

    /** The window's content: the panel, and the keys handed to it. */
    private static final class Host extends FrameLayout {
        final QuickSettingsActivity.PanelView panel;

        Host(Context service) {
            super(service);
            setFocusable(true);
            setFocusableInTouchMode(true);
            panel = new QuickSettingsActivity.PanelView(service, () -> close(service));
            addView(panel, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            panel.refresh();
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent e) {
            return panel.key(e) || super.dispatchKeyEvent(e);
        }
    }
}
