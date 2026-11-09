package io.freemytv;

import android.content.Context;

/**
 * RK3326: its firewall (tools/proyector/agent-rk3326.sh, pushed to /data/local/agent.sh) run as root through the
 * projector's own ADB, once per boot. Nothing in that firmware runs a script of ours, and it doesn't hand apps the boot
 * broadcast either ("process is bad"), so it starts from RemoteKeyService, which the system binds at every boot. The
 * script leaves sys.fmtv.fw=1 (gone at the next restart): a service reconnecting later doesn't run it again, so ADB
 * opened by hand from the quick panel stays open.
 */
final class Firewall {
    private Firewall() {}

    private static volatile boolean started;

    static void onceThisBoot(Context c) {
        if (!RemoteKeys.RK3326 || started) return;
        started = true;
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            for (int attempt = 0; attempt < 10; attempt++) {
                try (Adb adb = Adb.connect(app, 5000, null)) {
                    String out = adb.shell("if [ \"$(getprop sys.fmtv.fw)\" = 1 ]; then echo fmtv-already;"
                            + " elif [ -f /data/local/agent.sh ]; then su 0 sh /data/local/agent.sh && echo fmtv-ok;"
                            + " else echo fmtv-none; fi");
                    AppLog.w("firewall: " + (out.contains("fmtv-ok") ? "on" : out.contains("fmtv-already") ? "already on"
                            : out.contains("fmtv-none") ? "not installed" : "failed: " + out.trim()));
                    return;
                } catch (java.io.IOException e) {
                    AppLog.w("firewall: " + e.getMessage());   // adbd may still be coming up
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException ignored) {
                        return;
                    }
                }
            }
        }, "firewall").start();
    }
}
