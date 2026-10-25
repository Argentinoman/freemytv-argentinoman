package io.freemytv;

import android.service.dreams.DreamService;

/**
 * The Free My TV screensaver (enabled in Free My TV mode via `settings put secure screensaver_components`). The system
 * still decides when it starts and when the TV turns off (e.g. after 10 min idle, then 2 h later), as before: the app
 * doesn't change those timings. With photos, the arrows open its little menus (DreamView.key); any other key exits.
 */
public class Screensaver extends DreamService {
    private DreamView view;

    /**
     * Sent by the remote's app buttons (RemoteKeys, another process): they take the key before the screensaver sees
     * it, so without this it stayed over the app they opened.
     */
    static final String END = "io.freemytv.END_SCREENSAVER";

    private final android.content.BroadcastReceiver end = new android.content.BroadcastReceiver() {
        @Override public void onReceive(android.content.Context c, android.content.Intent i) {
            finish();
        }
    };

    /** A flag file every process can see: the screensaver is showing (RemoteKeys ends it first). */
    static java.io.File dreamingFlag(android.content.Context c) {
        return new java.io.File(c.getFilesDir(), "screensaver-on");
    }

    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        Watchdog.install(this);
        Ui.init(this);
        setInteractive(true);   // to see the → key; every other key ends it, as before
        setFullscreen(true);
        setScreenBright(true);
        view = new DreamView(this);
        setContentView(view);
        registerReceiver(end, new android.content.IntentFilter(END));
    }

    @Override public boolean dispatchKeyEvent(android.view.KeyEvent e) {
        if (view != null && view.key(e)) return true;
        finish();
        return true;
    }

    @Override public void onDreamingStarted() {
        super.onDreamingStarted();
        try {
            //noinspection ResultOfMethodCallIgnored
            dreamingFlag(this).createNewFile();
        } catch (java.io.IOException ignored) {
        }
        if (view != null) view.start();
    }

    @Override public void onDreamingStopped() {
        //noinspection ResultOfMethodCallIgnored
        dreamingFlag(this).delete();
        if (view != null) view.stop();
        super.onDreamingStopped();
    }

    @Override public void onDetachedFromWindow() {
        //noinspection ResultOfMethodCallIgnored
        dreamingFlag(this).delete();
        try {
            unregisterReceiver(end);
        } catch (IllegalArgumentException ignored) {
        }
        if (view != null) view.stop();
        super.onDetachedFromWindow();
    }
}
