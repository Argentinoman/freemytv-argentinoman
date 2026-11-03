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

    /** Whether others on the network can reach ADB now; null without the firewall (or no answer). Not on the UI thread. */
    static Boolean open(Context c) {
        try (Adb adb = Adb.connect(c, 3000, null)) {
            String rules = adb.shell("iptables -S " + CHAIN + " 2>&1");
            if (!rules.contains("-A " + CHAIN)) return null;
            return rules.contains("-A " + CHAIN + " -j RETURN");
        } catch (IOException e) {
            AppLog.w("network adb: " + e.getMessage());
            return null;
        }
    }

    /** Opens or closes it (an unconditional RETURN ahead of the DROP). Not on the UI thread. */
    static void set(Context c, boolean open) {
        String remove = "while $t -D " + CHAIN + " -j RETURN 2>/dev/null; do :; done";
        String cmd = "for t in iptables ip6tables; do " + remove + (open ? "; $t -I " + CHAIN + " 1 -j RETURN" : "") + "; done";
        try (Adb adb = Adb.connect(c, 3000, null)) {
            adb.shell(cmd);
        } catch (IOException e) {
            AppLog.w("network adb: " + e.getMessage());
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
