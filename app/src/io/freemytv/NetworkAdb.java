package io.freemytv;

import android.content.Context;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

/**
 * The HY300's ADB over the network answers as root and asks nobody, so a firewall keeps port 5555 to the projector
 * itself (the mode switch talks to 127.0.0.1). /data/local/agent.sh, which the firmware runs as root at every boot
 * (preinstall.rc), sets it up closed; the quick panel opens it until the next restart, to work on it from a computer.
 */
final class NetworkAdb {
    private NetworkAdb() {}

    private static final String CHAIN = "fmtv_adb";

    /**
     * The RK3326's ADB runs as shell, not root: the firewall's commands go through its su. The firmware there is a
     * userdebug build that grants WRITE_SECURE_SETTINGS to any app, so this app turns ADB itself on (wireless debugging
     * and the debugging switch), with no ADB needed first: what the HY300 ADB Opener app did.
     */
    private static String root(String cmd) {
        return RemoteKeys.RK3326 ? "su 0 sh -c '" + cmd + "'" : cmd;
    }

    /** Whether others on the network can reach ADB now; null without the firewall (or no answer). Not on the UI thread. */
    static Boolean open(Context c) {
        boolean wireless = RemoteKeys.RK3326 && android.provider.Settings.Global.getInt(c.getContentResolver(), "adb_wifi_enabled", 0) == 1;
        try (Adb adb = Adb.connect(c, 3000, null)) {
            String rules = adb.shell(root("iptables -S " + CHAIN + " 2>&1"));
            if (!rules.contains("-A " + CHAIN)) return RemoteKeys.RK3326 ? Boolean.valueOf(wireless) : null;
            return rules.contains("-A " + CHAIN + " -j RETURN");
        } catch (IOException e) {
            AppLog.w("network adb: " + e.getMessage());
            return RemoteKeys.RK3326 ? Boolean.valueOf(wireless) : null;
        }
    }

    /** Opens or closes it (an unconditional RETURN ahead of the DROP). Not on the UI thread. */
    static void set(Context c, boolean open) {
        if (RemoteKeys.RK3326) {
            try {
                android.content.ContentResolver cr = c.getContentResolver();
                if (open) android.provider.Settings.Global.putInt(cr, "adb_enabled", 1);
                android.provider.Settings.Global.putInt(cr, "adb_wifi_enabled", open ? 1 : 0);
            } catch (SecurityException e) {
                AppLog.w("network adb: no WRITE_SECURE_SETTINGS (" + e.getMessage() + ")");
            }
        }
        String remove = "while $t -D " + CHAIN + " -j RETURN 2>/dev/null; do :; done";
        String cmd = "for t in iptables ip6tables; do " + remove + (open ? "; $t -I " + CHAIN + " 1 -j RETURN" : "") + "; done";
        try (Adb adb = Adb.connect(c, 3000, null)) {
            adb.shell(root(cmd));
        } catch (IOException e) {
            AppLog.w("network adb: " + e.getMessage());
        }
    }

    /** RK3326: the wireless debugging port (random), for `adb connect`; "" when it's off. */
    static String wirelessPort() {
        if (!RemoteKeys.RK3326) return "";
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            String port = (String) sp.getMethod("get", String.class, String.class).invoke(null, "service.adb.tls.port", "");
            return port == null || port.equals("0") ? "" : port;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "";
        }
    }

    /** The projector's address on the network, for `adb connect`. */
    static String address() {
        try {
            for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (n.isLoopback() || !n.isUp()) continue;
                for (InetAddress a : Collections.list(n.getInetAddresses())) {
                    if (a instanceof Inet4Address) return a.getHostAddress();
                }
            }
        } catch (IOException | RuntimeException ignored) {
        }
        return "";
    }
}
