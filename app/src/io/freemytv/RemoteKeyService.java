package io.freemytv;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Sees the remote's keys before any app does, so the app buttons work from inside Netflix, Disney and the rest. The
 * system waits for it on every key (up to half a second), so it runs in a process of its own and does next to nothing.
 * It also draws the power-off animation (PowerOff), being always there and allowed to draw over everything.
 */
public class RemoteKeyService extends AccessibilityService {
    /**
     * The moment the power button is pressed: TCL sets sys.tcl.powerstatus to "suspend" right away (it then waits for
     * the CI slot, half a second, before going to sleep, and the screen goes dark half a second after that), and the
     * TV stops being "interactive" as it starts going to sleep. ACTION_SCREEN_OFF only arrives once the screen is dark.
     * Nothing announces either, so they're read every 40 ms, only while the screen is on.
     */
    private static final long POLL_MS = 40;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private PowerManager power;
    private boolean polling, awake;

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            // Only once the TV has been seen awake: the flag can still say "suspend" a moment after it wakes up.
            boolean suspending = "suspend".equals(powerStatus());
            if (!suspending && power.isInteractive()) awake = true;
            if (awake && (suspending || !power.isInteractive())) {
                polling = false;
                PowerOff.show(RemoteKeyService.this);
                return;
            }
            handler.postDelayed(this, POLL_MS);
        }
    };

    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_SCREEN_ON.equals(i.getAction())) {
                PowerOff.reveal(RemoteKeyService.this);
                startPolling();
            } else {
                stopPolling();
            }
        }
    };

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        power = (PowerManager) getSystemService(POWER_SERVICE);
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(screen, f);
        startPolling();
    }

    private static java.lang.reflect.Method getProp;

    private static String powerStatus() {
        try {
            if (getProp == null) getProp = Class.forName("android.os.SystemProperties").getMethod("get", String.class);
            return (String) getProp.invoke(null, "sys.tcl.powerstatus");
        } catch (Exception | LinkageError e) {
            return "";
        }
    }

    private void startPolling() {
        if (polling || power == null) return;
        polling = true;
        awake = false;
        handler.postDelayed(poll, POLL_MS);
    }

    private void stopPolling() {
        polling = false;
        handler.removeCallbacks(poll);
    }

    @Override public void onDestroy() {
        stopPolling();
        try {
            unregisterReceiver(screen);
        } catch (RuntimeException ignored) {
        }
        PowerOff.hide(this);
        super.onDestroy();
    }

    @Override protected boolean onKeyEvent(KeyEvent event) {
        return RemoteKeys.onKey(this, event);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override public void onInterrupt() {}
}
