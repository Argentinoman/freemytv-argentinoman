package io.freemytv;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import java.util.Scanner;

/**
 * RK3326: a notice (Snack) when a device is plugged into the HDMI or unplugged, over whatever is on screen. The board has no TV
 * input to announce it, so the HDMI receiver is asked every 4 s whether a cable brings power (the board's v4l2-ctl; its
 * node is open to all). Run by RemoteKeyService, which is always there.
 */
final class HdmiWatch {
    private HdmiWatch() {}

    private static final long EVERY_MS = 4000;
    private static HandlerThread thread;
    private static Handler worker;
    private static Boolean powered;

    static void start(Context c) {
        if (!RemoteKeys.RK3326 || thread != null) return;
        final Context app = c;   // the service itself: the notice is its overlay
        thread = new HandlerThread("hdmi-watch");
        thread.start();
        worker = new Handler(thread.getLooper());
        worker.post(new Runnable() {
            @Override public void run() {
                check(app);
                if (worker != null) worker.postDelayed(this, EVERY_MS);
            }
        });
    }

    static void stop() {
        if (thread == null) return;
        worker.removeCallbacksAndMessages(null);
        thread.quitSafely();
        thread = null;
        worker = null;
    }

    /** The first answer only sets the baseline; a change after it is told. */
    private static void check(Context app) {
        Boolean now = cable();
        if (now == null) return;
        Boolean before = powered;
        powered = now;
        if (before == null || before.equals(now)) return;
        AppLog.w("hdmi: cable " + (now ? "plugged in" : "out"));
        // The words of Home's own notice for a TV input (the HY300's, from the system's input state).
        final String text = now ? "Se detectó un equipo en HDMI" : "Se desconectó el equipo de HDMI";
        new Handler(Looper.getMainLooper()).post(() -> notice(app, text));
    }

    /** Asked of Home first: if it's in front it shows its own notice (frosted glass, as on the HY300); else Snack. */
    static final String NOTICE = "io.freemytv.HDMI_NOTICE", EXTRA_TEXT = "text";

    private static void notice(Context service, String text) {
        android.content.Intent i = new android.content.Intent(NOTICE).setPackage(service.getPackageName()).putExtra(EXTRA_TEXT, text);
        service.sendOrderedBroadcast(i, null, new android.content.BroadcastReceiver() {
            @Override public void onReceive(Context c, android.content.Intent r) {
                if (getResultCode() != android.app.Activity.RESULT_OK) Snack.show(service, text);
            }
        }, null, android.app.Activity.RESULT_CANCELED, null, null);
    }

    /** Whether the receiver sees power on the cable; null without an answer. */
    static Boolean cable() {
        try {
            Process p = new ProcessBuilder("v4l2-ctl", "-d", "/dev/v4l-subdev2", "--get-ctrl=power_present")
                    .redirectErrorStream(true).start();
            Scanner sc = new Scanner(p.getInputStream()).useDelimiter("\\A");
            String out = sc.hasNext() ? sc.next() : "";
            p.waitFor();
            if (out.contains("power_present: 1")) return true;
            if (out.contains("power_present: 0")) return false;
        } catch (java.io.IOException | InterruptedException | RuntimeException ignored) {
        }
        return null;
    }
}
