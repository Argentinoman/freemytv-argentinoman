package io.freemytv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A DLNA / UPnP media renderer: the TV (or projector) shows up as a screen to "play on" in BubbleUPnP, Windows' "Cast to
 * device", VLC, foobar2000 and the like. The phone or computer hands over a link (video, song or photo) and the remote
 * controls from there: play, pause, seek, stop and volume. What it plays goes to {@link PlayerActivity}.
 *
 * Lives in {@link RemoteKeyService}'s process (:keys), which the system keeps running: SSDP (multicast on 1900) so it
 * can be found, and a small HTTP server on {@link #PORT} for the description, the SOAP actions and the GENA events.
 * Talks to nobody outside the local network. Turned on or off from the quick panel ({@link #setEnabled}).
 */
final class Dlna {

    static final int PORT = 49494;
    static final String CMD = "io.freemytv.DLNA_CMD", STATE = "io.freemytv.DLNA_STATE", TOGGLE = "io.freemytv.DLNA_TOGGLE";
    /** Signature-level: only Free My TV's own processes send or receive the player's commands and reports. */
    static final String PERMISSION = "io.freemytv.permission.DLNA";
    static final String EXTRA_SESSION = "dlna_session", EXTRA_TITLE = "dlna_title", EXTRA_KIND = "dlna_kind",
            EXTRA_DURATION = "dlna_duration", EXTRA_TIME_SEEK = "dlna_time_seek";
    static final String KIND_VIDEO = "video", KIND_AUDIO = "audio", KIND_IMAGE = "image";
    private static final String OFF_FILE = "dlna-off";

    private static final String GROUP = "239.255.255.250";
    private static final int SSDP_PORT = 1900, MAX_AGE = 1800;
    private static final long ALIVE_EVERY_MS = 5 * 60_000;
    private static final String AVT = "urn:schemas-upnp-org:service:AVTransport:1",
            RCS = "urn:schemas-upnp-org:service:RenderingControl:1",
            CMS = "urn:schemas-upnp-org:service:ConnectionManager:1",
            RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1";

    /** What it says it can play (GetProtocolInfo): Android's decoders take more, but controllers check this list. */
    private static final String SINK;
    static {
        String[] types = {"video/mp4", "video/x-matroska", "video/mpeg", "video/MP2T", "video/vnd.dlna.mpeg-tts",
                "video/quicktime", "video/x-msvideo", "video/avi", "video/webm", "video/3gpp", "video/x-flv",
                "video/x-ms-wmv", "video/x-ms-asf", "audio/mpeg", "audio/mp3", "audio/mp4", "audio/x-m4a", "audio/aac",
                "audio/flac", "audio/x-flac", "audio/wav", "audio/x-wav", "audio/ogg", "audio/vorbis", "audio/opus",
                "audio/L16;rate=44100;channels=2", "audio/L16;rate=48000;channels=2", "audio/x-ms-wma",
                "image/jpeg", "image/png", "image/gif", "image/webp", "image/bmp", "application/vnd.apple.mpegurl",
                "application/x-mpegURL"};
        StringBuilder b = new StringBuilder();
        for (String t : types) b.append(b.length() == 0 ? "" : ",").append("http-get:*:").append(t).append(":*");
        SINK = b.toString();
    }

    private static Dlna running;

    static boolean enabled(Context c) {
        return !new File(c.getFilesDir(), OFF_FILE).exists();
    }

    /** From the quick panel (any process): saves the choice and tells :keys to start or stop. */
    static void setEnabled(Context c, boolean on) {
        File f = new File(c.getFilesDir(), OFF_FILE);
        try {
            if (on) f.delete();
            else f.createNewFile();
        } catch (IOException e) {
            AppLog.w("dlna: " + e.getMessage());
        }
        c.sendBroadcast(new Intent(TOGGLE).setPackage(c.getPackageName()), PERMISSION);
    }

    /** RemoteKeyService: once connected, and again when the screen comes on. */
    static synchronized void start(Context c) {
        if (running != null || !enabled(c)) return;
        running = new Dlna(c.getApplicationContext());
        running.begin();
    }

    /** RemoteKeyService: screen off (the device leaves the network list) or destroyed. */
    static synchronized void stop() {
        if (running == null) return;
        running.end();
        running = null;
    }

    /** The toggle's receiver, registered by RemoteKeyService for as long as it lives. */
    static BroadcastReceiver toggleReceiver() {
        return new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                if (enabled(c)) start(c);
                else stop();
            }
        };
    }

    // ---------------------------------------------------------------------------------------------------- the state

    private final Context app;
    private final AudioManager audio;
    private final String uuid, name;
    private volatile boolean alive;
    private MulticastSocket ssdp;
    private ServerSocket http;
    private WifiManager.MulticastLock lock;
    /** Requests, and the relays (one thread each for as long as a film plays, so a few at once). */
    private final ExecutorService pool = new java.util.concurrent.ThreadPoolExecutor(0, 12, 30, java.util.concurrent.TimeUnit.SECONDS,
            new java.util.concurrent.SynchronousQueue<>());
    private final ExecutorService events = Executors.newSingleThreadExecutor();

    // Guarded by `this`.
    private String uri = "", meta = "", title = "", kind = KIND_VIDEO, transport = "NO_MEDIA_PRESENT", status = "OK";
    private int posMs, durMs, session;
    private long posAt;
    private boolean playerOpen, timeSeek;
    private final Map<String, Sub> subs = new HashMap<>();

    private static final class Sub {
        final String sid, service;
        final List<String> callbacks;
        long expires;
        int seq;

        Sub(String sid, String service, List<String> callbacks) {
            this.sid = sid;
            this.service = service;
            this.callbacks = callbacks;
        }
    }

    private final BroadcastReceiver player = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            reported(i.getIntExtra(EXTRA_SESSION, 0), i.getStringExtra("state"), i.getIntExtra("pos", 0),
                    i.getIntExtra("dur", 0), i.getBooleanExtra("open", false), i.getBooleanExtra("error", false));
        }
    };

    private final BroadcastReceiver volume = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1) == AudioManager.STREAM_MUSIC) Dlna.this.publish(RCS);
        }
    };

    private Dlna(Context app) {
        this.app = app;
        audio = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        String id = Settings.Secure.getString(app.getContentResolver(), Settings.Secure.ANDROID_ID);
        uuid = UUID.nameUUIDFromBytes(("freemytv-dlna-" + id).getBytes(StandardCharsets.UTF_8)).toString();
        String n = Settings.Global.getString(app.getContentResolver(), "device_name");
        name = n == null || n.trim().isEmpty() ? Build.MODEL : n.trim();
    }

    private void begin() {
        alive = true;
        app.registerReceiver(player, new IntentFilter(STATE), PERMISSION, null);
        app.registerReceiver(volume, new IntentFilter("android.media.VOLUME_CHANGED_ACTION"));
        try {
            WifiManager wifi = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
            lock = wifi.createMulticastLock("freemytv:dlna");
            lock.setReferenceCounted(false);
            lock.acquire();   // without it many Wi-Fi chips drop the M-SEARCH queries
        } catch (RuntimeException e) {
            AppLog.w("dlna: multicast lock " + e.getMessage());
        }
        Thread h = new Thread(this::serveHttp, "dlna-http");
        h.setDaemon(true);
        h.start();
        Thread s = new Thread(this::serveSsdp, "dlna-ssdp");
        s.setDaemon(true);
        s.start();
        AppLog.w("dlna: on as \"" + name + "\"");
    }

    private void end() {
        alive = false;
        final MulticastSocket s = ssdp;
        final Local at = local();
        if (s != null && at != null) {
            new Thread(() -> {   // goodbye, so it leaves the controllers' lists now and not in half an hour
                try {
                    for (String[] t : targets()) send(s, at, notify(at, t[0], t[1], "ssdp:byebye"));
                } catch (IOException ignored) {
                }
                s.close();
            }, "dlna-bye").start();
        } else if (s != null) {
            s.close();
        }
        try {
            if (http != null) http.close();
        } catch (IOException ignored) {
        }
        try {
            app.unregisterReceiver(player);
            app.unregisterReceiver(volume);
        } catch (RuntimeException ignored) {
        }
        if (lock != null && lock.isHeld()) lock.release();
        pool.shutdownNow();
        events.shutdown();
        AppLog.w("dlna: off");
    }

    // ---------------------------------------------------------------------------------------------------- SSDP

    /** This device's address on the local network, and its interface. */
    private static final class Local {
        final Inet4Address ip;
        final NetworkInterface iface;

        Local(Inet4Address ip, NetworkInterface iface) {
            this.ip = ip;
            this.iface = iface;
        }

        String location() {
            return "http://" + ip.getHostAddress() + ":" + PORT + "/desc.xml";
        }
    }

    /** Ethernet or Wi-Fi first; never Wi-Fi Direct, mobile data or VPN tunnels. */
    private static Local local() {
        Local best = null;
        try {
            for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                String nm = n.getName();
                if (!n.isUp() || n.isLoopback() || nm.startsWith("p2p") || nm.startsWith("rmnet") || nm.startsWith("tun")
                        || nm.startsWith("dummy")) continue;
                for (InetAddress a : Collections.list(n.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || !a.isSiteLocalAddress()) continue;
                    Local l = new Local((Inet4Address) a, n);
                    if (nm.startsWith("eth") || nm.startsWith("wlan")) return l;
                    if (best == null) best = l;
                }
            }
        } catch (IOException | RuntimeException ignored) {
        }
        return best;
    }

    /** NT/ST and USN pairs it answers to. */
    private List<String[]> targets() {
        List<String[]> t = new ArrayList<>();
        String u = "uuid:" + uuid;
        t.add(new String[]{"upnp:rootdevice", u + "::upnp:rootdevice"});
        t.add(new String[]{u, u});
        for (String type : new String[]{RENDERER, AVT, RCS, CMS}) t.add(new String[]{type, u + "::" + type});
        return t;
    }

    private static String server() {
        return "Android/" + Build.VERSION.RELEASE + " UPnP/1.0 FreeMyTV/1.0";
    }

    private String notify(Local at, String nt, String usn, String nts) {
        StringBuilder b = new StringBuilder("NOTIFY * HTTP/1.1\r\nHOST: " + GROUP + ":" + SSDP_PORT + "\r\n");
        if (nts.equals("ssdp:alive")) {
            b.append("CACHE-CONTROL: max-age=").append(MAX_AGE).append("\r\nLOCATION: ").append(at.location())
                    .append("\r\nSERVER: ").append(server()).append("\r\n");
        }
        return b.append("NT: ").append(nt).append("\r\nNTS: ").append(nts).append("\r\nUSN: ").append(usn)
                .append("\r\n\r\n").toString();
    }

    private static void send(MulticastSocket s, Local at, String msg) throws IOException {
        byte[] b = msg.getBytes(StandardCharsets.UTF_8);
        s.setNetworkInterface(at.iface);
        s.send(new DatagramPacket(b, b.length, InetAddress.getByName(GROUP), SSDP_PORT));
    }

    private void serveSsdp() {
        byte[] buf = new byte[2048];
        Local joined = null;
        long nextAlive = 0;
        while (alive) {
            try {
                Local at = local();
                if (at == null) {   // no network yet
                    SystemClock.sleep(5000);
                    continue;
                }
                if (ssdp == null || joined == null || !joined.ip.equals(at.ip)) {   // first time, or a new address
                    if (ssdp != null) ssdp.close();
                    ssdp = new MulticastSocket(SSDP_PORT);
                    ssdp.setTimeToLive(4);
                    ssdp.setSoTimeout(5000);
                    ssdp.joinGroup(new InetSocketAddress(GROUP, SSDP_PORT), at.iface);
                    joined = at;
                    nextAlive = 0;
                }
                long now = SystemClock.elapsedRealtime();
                if (now >= nextAlive) {
                    for (int twice = 0; twice < 2; twice++) {   // UDP: each announcement goes out twice
                        for (String[] t : targets()) send(ssdp, at, notify(at, t[0], t[1], "ssdp:alive"));
                    }
                    nextAlive = now + ALIVE_EVERY_MS;
                }
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    ssdp.receive(p);
                } catch (SocketTimeoutException e) {
                    continue;
                }
                String msg = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                if (!msg.startsWith("M-SEARCH")) continue;
                Map<String, String> h = headers(msg.split("\r\n"));
                String st = h.get("st");
                if (st == null || !"\"ssdp:discover\"".equals(h.get("man"))) continue;
                for (String[] t : targets()) {
                    if (!st.equals("ssdp:all") && !st.equals(t[0])) continue;
                    String answer = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=" + MAX_AGE + "\r\nDATE: " + httpDate()
                            + "\r\nEXT:\r\nLOCATION: " + at.location() + "\r\nSERVER: " + server() + "\r\nST: " + t[0]
                            + "\r\nUSN: " + t[1] + "\r\n\r\n";
                    byte[] b = answer.getBytes(StandardCharsets.UTF_8);
                    ssdp.send(new DatagramPacket(b, b.length, p.getSocketAddress()));
                }
            } catch (IOException | RuntimeException e) {
                if (!alive) break;
                AppLog.w("dlna ssdp: " + e.getMessage());
                if (ssdp != null) ssdp.close();
                ssdp = null;
                SystemClock.sleep(5000);
            }
        }
    }

    private static Map<String, String> headers(String[] lines) {
        Map<String, String> h = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) h.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT), lines[i].substring(colon + 1).trim());
        }
        return h;
    }

    private static String httpDate() {
        SimpleDateFormat f = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("GMT"));
        return f.format(new Date());
    }

    // ---------------------------------------------------------------------------------------------------- HTTP

    private void serveHttp() {
        while (alive) {
            try {
                ServerSocket s = new ServerSocket();
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(PORT));
                http = s;
                while (alive) {
                    final Socket c = s.accept();
                    try {
                        pool.execute(() -> handle(c));
                    } catch (java.util.concurrent.RejectedExecutionException e) {
                        c.close();   // too many at once
                    }
                }
            } catch (IOException | RuntimeException e) {
                if (!alive) break;
                AppLog.w("dlna http: " + e.getMessage());
                SystemClock.sleep(5000);
            }
        }
    }

    private void handle(Socket c) {
        try (Socket sock = c) {
            InetAddress from = sock.getInetAddress();
            if (!from.isSiteLocalAddress() && !from.isLoopbackAddress() && !from.isLinkLocalAddress()) return;   // LAN only
            sock.setSoTimeout(8000);
            InputStream in = sock.getInputStream();
            String first = line(in);
            if (first == null) return;
            String[] req = first.split(" ");
            if (req.length < 2) return;
            List<String> lines = new ArrayList<>();
            lines.add(first);
            for (String l; (l = line(in)) != null && !l.isEmpty(); ) lines.add(l);
            Map<String, String> h = headers(lines.toArray(new String[0]));
            String body = body(in, h);
            String method = req[0], path = req[1];
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);
            OutputStream out = sock.getOutputStream();
            switch (method) {
                case "GET":
                case "HEAD":
                    if (path.startsWith("/relay/")) {
                        if (!from.isLoopbackAddress()) return;   // only the player, on this device
                        String target = null;
                        synchronized (this) {
                            if (path.equals("/relay/" + session)) target = uri;
                        }
                        if (target == null) {
                            respond(out, "404 Not Found", null, "");
                            return;
                        }
                        // ?t=ms: from that second on, asked of the server as a DLNA time seek (live streams have
                        // no bytes to jump to).
                        Matcher t = Pattern.compile("[?&]t=(\\d+)").matcher(req[1]);
                        if (t.find()) {
                            h.remove("range");
                            h.put("timeseekrange.dlna.org", String.format(Locale.US, "npt=%.3f-", Long.parseLong(t.group(1)) / 1000.0));
                        }
                        relay(sock, out, method, h, target, 0);
                        return;
                    }
                    String xml = path.equals("/desc.xml") ? description()
                            : path.equals("/avt.xml") ? Scpd.AVT : path.equals("/rcs.xml") ? Scpd.RCS
                            : path.equals("/cms.xml") ? Scpd.CMS : null;
                    if (xml == null) respond(out, "404 Not Found", null, "");
                    else respond(out, "200 OK", null, method.equals("HEAD") ? null : xml);
                    break;
                case "POST":
                    control(out, path, h.get("soapaction"), body);
                    break;
                case "SUBSCRIBE":
                    subscribe(out, path, h);
                    break;
                case "UNSUBSCRIBE":
                    synchronized (this) {
                        subs.remove(h.get("sid"));
                    }
                    respond(out, "200 OK", null, "");
                    break;
                default:
                    respond(out, "405 Method Not Allowed", null, "");
            }
        } catch (IOException | RuntimeException e) {
            if (alive) AppLog.w("dlna request: " + e.getMessage());
        }
    }

    /** The request headers the player's own server may need passed on (ranges, DLNA time seeks). */
    private static final String[] RELAYED = {"Range", "User-Agent", "Accept", "Icy-MetaData", "TimeSeekRange.dlna.org",
            "getcontentFeatures.dlna.org", "transferMode.dlna.org", "If-Range"};

    /**
     * The current link, fetched with a plain socket and passed to the player byte for byte (status, headers and body as
     * the server sent them). Redirects to plain http are followed here; to https, handed to the player.
     */
    private void relay(Socket client, OutputStream out, String method, Map<String, String> h, String target, int hops) throws IOException {
        Uri u = Uri.parse(target);
        String host = u.getHost();
        if (host == null || hops > 5) {
            respond(out, "502 Bad Gateway", null, "");
            return;
        }
        int port = u.getPort() < 0 ? 80 : u.getPort();
        String path = u.getEncodedPath() == null || u.getEncodedPath().isEmpty() ? "/" : u.getEncodedPath();
        if (u.getEncodedQuery() != null) path += "?" + u.getEncodedQuery();
        try (Socket up = new Socket()) {
            up.connect(new InetSocketAddress(host, port), 8000);
            up.setSoTimeout(30_000);
            client.setSoTimeout(0);
            StringBuilder req = new StringBuilder(method).append(' ').append(path).append(" HTTP/1.1\r\nHost: ")
                    .append(host).append(port == 80 ? "" : ":" + port).append("\r\n");
            for (String name : RELAYED) {
                String v = h.get(name.toLowerCase(Locale.ROOT));
                if (v != null) req.append(name).append(": ").append(v).append("\r\n");
            }
            req.append("Connection: close\r\n\r\n");
            OutputStream o = up.getOutputStream();
            o.write(req.toString().getBytes(StandardCharsets.ISO_8859_1));
            o.flush();
            InputStream in = up.getInputStream();
            String status = line(in);
            if (status == null) throw new IOException("no answer");
            List<String> head = new ArrayList<>();
            head.add(status);
            for (String l; (l = line(in)) != null && !l.isEmpty(); ) head.add(l);
            String[] parts = status.split(" ");
            int code = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 0;
            String location = headers(head.toArray(new String[0])).get("location");
            if (code >= 300 && code < 400 && location != null) {
                String next = target;
                next = new java.net.URI(target).resolve(location).toString();
                if (next.startsWith("http:")) {
                    relay(client, out, method, h, next, hops + 1);
                } else {
                    Map<String, String> loc = new HashMap<>();
                    loc.put("Location", next);
                    respond(out, "302 Found", loc, "");
                }
                return;
            }
            StringBuilder back = new StringBuilder();
            for (String l : head) {
                if (l.toLowerCase(Locale.ROOT).startsWith("connection:")) continue;
                back.append(l).append("\r\n");
            }
            back.append("Connection: close\r\n\r\n");
            out.write(back.toString().getBytes(StandardCharsets.ISO_8859_1));
            byte[] buf = new byte[32768];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            out.flush();
        } catch (java.net.URISyntaxException | NumberFormatException e) {
            respond(out, "502 Bad Gateway", null, "");
        } catch (IOException e) {
            // The player closed it (seek, stop) or the server went away: nothing to answer.
        }
    }

    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 8192) throw new IOException("header too long");
        }
        if (c == -1 && b.size() == 0) return null;
        return b.toString("ISO-8859-1");
    }

    private static String body(InputStream in, Map<String, String> h) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        if ("chunked".equalsIgnoreCase(h.get("transfer-encoding"))) {
            for (String size; (size = line(in)) != null; ) {
                int semi = size.indexOf(';');
                int n = Integer.parseInt((semi < 0 ? size : size.substring(0, semi)).trim(), 16);
                if (n == 0) break;
                copy(in, b, n);
                line(in);
            }
        } else {
            String len = h.get("content-length");
            if (len != null) copy(in, b, Integer.parseInt(len.trim()));
        }
        return b.toString("UTF-8");
    }

    private static void copy(InputStream in, ByteArrayOutputStream to, int n) throws IOException {
        if (to.size() + n > 1 << 20) throw new IOException("body too long");
        byte[] buf = new byte[Math.min(n, 8192)];
        while (n > 0) {
            int r = in.read(buf, 0, Math.min(n, buf.length));
            if (r < 0) throw new IOException("short body");
            to.write(buf, 0, r);
            n -= r;
        }
    }

    private static void respond(OutputStream out, String status, Map<String, String> extra, String xml) throws IOException {
        byte[] b = xml == null ? new byte[0] : xml.getBytes(StandardCharsets.UTF_8);
        StringBuilder head = new StringBuilder("HTTP/1.1 ").append(status).append("\r\n");
        if (xml != null && !xml.isEmpty()) head.append("Content-Type: text/xml; charset=\"utf-8\"\r\n");
        head.append("Content-Length: ").append(b.length).append("\r\nServer: ").append(server())
                .append("\r\nDate: ").append(httpDate()).append("\r\nConnection: close\r\n");
        if (extra != null) for (Map.Entry<String, String> e : extra.entrySet()) head.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
        head.append("\r\n");
        out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.write(b);
        out.flush();
    }

    private String description() {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<root xmlns=\"urn:schemas-upnp-org:device-1-0\" xmlns:dlna=\"urn:schemas-dlna-org:device-1-0\">"
                + "<specVersion><major>1</major><minor>0</minor></specVersion><device>"
                + "<deviceType>" + RENDERER + "</deviceType><friendlyName>" + esc(name) + "</friendlyName>"
                + "<manufacturer>Free My TV</manufacturer><modelName>Free My TV</modelName>"
                + "<modelDescription>Free My TV media renderer</modelDescription><modelNumber>1</modelNumber>"
                + "<UDN>uuid:" + uuid + "</UDN><dlna:X_DLNADOC>DMR-1.50</dlna:X_DLNADOC><serviceList>"
                + service(AVT, "AVTransport", "avt") + service(RCS, "RenderingControl", "rcs")
                + service(CMS, "ConnectionManager", "cms") + "</serviceList></device></root>";
    }

    private static String service(String type, String id, String path) {
        return "<service><serviceType>" + type + "</serviceType><serviceId>urn:upnp-org:serviceId:" + id + "</serviceId>"
                + "<SCPDURL>/" + path + ".xml</SCPDURL><controlURL>/" + path + "/control</controlURL>"
                + "<eventSubURL>/" + path + "/event</eventSubURL></service>";
    }

    // ---------------------------------------------------------------------------------------------------- SOAP

    private static final class Fault extends Exception {
        final int code;

        Fault(int code, String what) {
            super(what);
            this.code = code;
        }
    }

    private void control(OutputStream out, String path, String soapAction, String body) throws IOException {
        String service = path.startsWith("/avt") ? AVT : path.startsWith("/rcs") ? RCS : path.startsWith("/cms") ? CMS : null;
        String action = soapAction == null ? "" : soapAction.replace("\"", "");
        int hash = action.indexOf('#');
        action = hash < 0 ? "" : action.substring(hash + 1).trim();
        if (service == null || action.isEmpty()) {
            respond(out, "404 Not Found", null, "");
            return;
        }
        try {
            Map<String, String> res = service.equals(AVT) ? avt(action, body) : service.equals(RCS) ? rcs(action, body) : cms(action);
            StringBuilder b = new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                    + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:" + action + "Response xmlns:u=\"" + service + "\">");
            for (Map.Entry<String, String> e : res.entrySet()) b.append('<').append(e.getKey()).append('>').append(esc(e.getValue())).append("</").append(e.getKey()).append('>');
            b.append("</u:").append(action).append("Response></s:Body></s:Envelope>");
            respond(out, "200 OK", null, b.toString());
        } catch (Fault f) {
            AppLog.w("dlna: " + action + " → " + f.code + " " + f.getMessage());
            respond(out, "500 Internal Server Error", null, "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                    + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
                    + "<s:Body><s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail>"
                    + "<UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\"><errorCode>" + f.code + "</errorCode>"
                    + "<errorDescription>" + esc(f.getMessage()) + "</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>");
        }
    }

    private static Map<String, String> out(String... kv) {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    private Map<String, String> avt(String action, String body) throws Fault {
        switch (action) {
            case "SetAVTransportURI": {
                String u = arg(body, "CurrentURI").trim(), m = arg(body, "CurrentURIMetaData");
                if (u.isEmpty()) throw new Fault(714, "Illegal MIME-type");
                Uri parsed = Uri.parse(u);
                if (!"http".equals(parsed.getScheme()) && !"https".equals(parsed.getScheme())) throw new Fault(716, "Resource not found");
                synchronized (this) {
                    uri = u;
                    meta = m;
                    title = didl(m, "dc:title");
                    if (title.isEmpty()) title = fileName(parsed);
                    kind = kindOf(m, u);
                    posMs = 0;
                    durMs = parseTime(attr(m, "res", "duration"));
                    // DLNA.ORG_OP=1x: the server starts from any second asked (TimeSeekRange), e.g. a live transcode.
                    Matcher op = Pattern.compile("DLNA\\.ORG_OP=([01])([01])").matcher(attr(m, "res", "protocolInfo"));
                    timeSeek = op.find() && op.group(1).equals("1");
                    status = "OK";
                    if (!playerOpen) transport = "STOPPED";
                }
                AppLog.w("dlna: uri " + kind + " \"" + title + "\"");
                publish(AVT);
                return out();
            }
            case "SetNextAVTransportURI":
            case "SetPlayMode":
            case "Next":
            case "Previous":
                return out();
            case "Play": {
                boolean resume;
                synchronized (this) {
                    if (uri.isEmpty()) throw new Fault(701, "Transition not available");
                    resume = playerOpen && transport.equals("PAUSED_PLAYBACK");
                    if (!resume) {
                        session++;
                        transport = "TRANSITIONING";
                        posMs = 0;
                        playerOpen = true;
                    } else {
                        transport = "PLAYING";
                        posAt = SystemClock.elapsedRealtime();
                    }
                }
                if (resume) command("play", 0);
                else launch();
                publish(AVT);
                return out();
            }
            case "Pause":
                synchronized (this) {
                    if (!playerOpen) throw new Fault(701, "Transition not available");
                    posMs = position();
                    transport = "PAUSED_PLAYBACK";
                }
                command("pause", 0);
                publish(AVT);
                return out();
            case "Stop":
                command("stop", 0);
                synchronized (this) {
                    playerOpen = false;
                    posMs = 0;
                    if (!uri.isEmpty()) transport = "STOPPED";
                }
                publish(AVT);
                return out();
            case "Seek": {
                String unit = arg(body, "Unit"), target = arg(body, "Target");
                if (!unit.equals("REL_TIME") && !unit.equals("ABS_TIME")) throw new Fault(710, "Seek mode not supported");
                int ms = parseTime(target);
                synchronized (this) {
                    if (!playerOpen) throw new Fault(701, "Transition not available");
                    posMs = ms;
                    posAt = SystemClock.elapsedRealtime();
                }
                command("seek", ms);
                publish(AVT);
                return out();
            }
            case "GetTransportInfo":
                synchronized (this) {
                    return out("CurrentTransportState", transport, "CurrentTransportStatus", status, "CurrentSpeed", "1");
                }
            case "GetPositionInfo":
                synchronized (this) {
                    String d = time(durMs), p = time(position());
                    return out("Track", uri.isEmpty() ? "0" : "1", "TrackDuration", d, "TrackMetaData", meta,
                            "TrackURI", uri, "RelTime", p, "AbsTime", p, "RelCount", "2147483647", "AbsCount", "2147483647");
                }
            case "GetMediaInfo":
                synchronized (this) {
                    return out("NrTracks", uri.isEmpty() ? "0" : "1", "MediaDuration", time(durMs), "CurrentURI", uri,
                            "CurrentURIMetaData", meta, "NextURI", "", "NextURIMetaData", "", "PlayMedium", "NETWORK",
                            "RecordMedium", "NOT_IMPLEMENTED", "WriteStatus", "NOT_IMPLEMENTED");
                }
            case "GetDeviceCapabilities":
                return out("PlayMedia", "NETWORK", "RecMedia", "NOT_IMPLEMENTED", "RecQualityModes", "NOT_IMPLEMENTED");
            case "GetTransportSettings":
                return out("PlayMode", "NORMAL", "RecQualityMode", "NOT_IMPLEMENTED");
            case "GetCurrentTransportActions":
                synchronized (this) {
                    return out("Actions", actions());
                }
            default:
                throw new Fault(401, "Invalid Action");
        }
    }

    private Map<String, String> rcs(String action, String body) throws Fault {
        switch (action) {
            case "GetVolume":
                return out("CurrentVolume", String.valueOf(volumePercent()));
            case "SetVolume": {
                int want;
                try {
                    want = Math.max(0, Math.min(100, Integer.parseInt(arg(body, "DesiredVolume").trim())));
                } catch (NumberFormatException e) {
                    throw new Fault(402, "Invalid Args");
                }
                int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(want * max / 100f), 0);
                publish(RCS);
                return out();
            }
            case "GetMute":
                return out("CurrentMute", muted() ? "1" : "0");
            case "SetMute": {
                String m = arg(body, "DesiredMute").trim();
                boolean on = m.equals("1") || m.equalsIgnoreCase("true") || m.equalsIgnoreCase("yes");
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, on ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_UNMUTE, 0);
                publish(RCS);
                return out();
            }
            case "ListPresets":
                return out("CurrentPresetNameList", "FactoryDefaults");
            case "SelectPreset":
                return out();
            default:
                throw new Fault(401, "Invalid Action");
        }
    }

    private Map<String, String> cms(String action) throws Fault {
        switch (action) {
            case "GetProtocolInfo":
                return out("Source", "", "Sink", SINK);
            case "GetCurrentConnectionIDs":
                return out("ConnectionIDs", "0");
            case "GetCurrentConnectionInfo":
                return out("RcsID", "0", "AVTransportID", "0", "ProtocolInfo", "", "PeerConnectionManager", "",
                        "PeerConnectionID", "-1", "Direction", "Input", "Status", "OK");
            default:
                throw new Fault(401, "Invalid Action");
        }
    }

    private int volumePercent() {
        int max = Math.max(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        return Math.round(audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max);
    }

    private boolean muted() {
        return audio.isStreamMute(AudioManager.STREAM_MUSIC);
    }

    /** Guarded by `this`. Where it is now: the last report, run on by the clock while playing. */
    private int position() {
        if (!transport.equals("PLAYING")) return posMs;
        long p = posMs + SystemClock.elapsedRealtime() - posAt;
        return (int) (durMs > 0 ? Math.min(p, durMs) : p);
    }

    /** Guarded by `this`. */
    private String actions() {
        if (uri.isEmpty()) return "";
        if (kind.equals(KIND_IMAGE)) return transport.equals("PLAYING") ? "Stop" : "Play";
        switch (transport) {
            case "PLAYING": return "Pause,Stop,Seek";
            case "PAUSED_PLAYBACK": return "Play,Stop,Seek";
            case "TRANSITIONING": return "Stop";
            default: return "Play";
        }
    }

    // ---------------------------------------------------------------------------------------------------- the player

    private void launch() {
        Intent i;
        synchronized (this) {
            // Plain http goes through relay(): the app allows no cleartext except to this device itself.
            Uri data = uri.startsWith("http:") ? Uri.parse("http://127.0.0.1:" + PORT + "/relay/" + session) : Uri.parse(uri);
            i = new Intent(app, PlayerActivity.class).setData(data).putExtra(EXTRA_SESSION, session)
                    .putExtra(EXTRA_TITLE, title).putExtra(EXTRA_KIND, kind).putExtra(EXTRA_DURATION, durMs)
                    .putExtra(EXTRA_TIME_SEEK, timeSeek && data.getHost() != null && data.getHost().equals("127.0.0.1"));
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            app.startActivity(i);
        } catch (RuntimeException e) {
            AppLog.w("dlna: player " + e.getMessage());
            synchronized (this) {
                playerOpen = false;
                transport = "STOPPED";
                status = "ERROR_OCCURRED";
            }
        }
    }

    private void command(String what, int ms) {
        int s;
        synchronized (this) {
            s = session;
        }
        app.sendBroadcast(new Intent(CMD).setPackage(app.getPackageName()).putExtra(EXTRA_SESSION, s)
                .putExtra("cmd", what).putExtra("ms", ms), PERMISSION);
    }

    /** The player's report: what it is doing now, where it is and how long the file is. */
    private void reported(int s, String state, int pos, int dur, boolean open, boolean error) {
        if (state == null) return;
        synchronized (this) {
            if (s != session) return;   // an older file's last words
            transport = state;
            posMs = pos;
            posAt = SystemClock.elapsedRealtime();
            if (dur > 0) durMs = dur;
            playerOpen = open;
            status = error ? "ERROR_OCCURRED" : "OK";
            if (!open && !error) posMs = 0;
        }
        publish(AVT);
    }

    // ---------------------------------------------------------------------------------------------------- GENA

    private void subscribe(OutputStream out, String path, Map<String, String> h) throws IOException {
        String service = path.startsWith("/avt") ? AVT : path.startsWith("/rcs") ? RCS : path.startsWith("/cms") ? CMS : null;
        if (service == null) {
            respond(out, "404 Not Found", null, "");
            return;
        }
        int seconds = 1800;
        String t = h.get("timeout");
        if (t != null && t.toLowerCase(Locale.ROOT).startsWith("second-")) {
            try {
                seconds = Math.max(60, Math.min(7200, Integer.parseInt(t.substring(7).trim())));
            } catch (NumberFormatException ignored) {
            }
        }
        Sub sub;
        String sid = h.get("sid");
        synchronized (this) {
            long now = SystemClock.elapsedRealtime();
            for (Iterator<Sub> it = subs.values().iterator(); it.hasNext(); ) if (it.next().expires < now) it.remove();
            if (sid != null) {   // a renewal
                sub = subs.get(sid);
                if (sub == null) {
                    respond(out, "412 Precondition Failed", null, "");
                    return;
                }
            } else {
                List<String> callbacks = new ArrayList<>();
                Matcher m = Pattern.compile("<([^>]+)>").matcher(h.get("callback") == null ? "" : h.get("callback"));
                while (m.find()) {
                    Uri u = Uri.parse(m.group(1));
                    if (!"http".equals(u.getScheme()) || u.getHost() == null) continue;
                    try {
                        InetAddress a = InetAddress.getByName(u.getHost());
                        if (a.isSiteLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress()) callbacks.add(m.group(1));
                    } catch (IOException ignored) {
                    }
                }
                if (callbacks.isEmpty() || subs.size() > 30) {
                    respond(out, "412 Precondition Failed", null, "");
                    return;
                }
                sub = new Sub("uuid:" + UUID.randomUUID(), service, callbacks);
                subs.put(sub.sid, sub);
            }
            sub.expires = now + seconds * 1000L;
        }
        respond(out, "200 OK", out("SID", sub.sid, "TIMEOUT", "Second-" + seconds), "");
        if (sid == null) {
            final Sub first = sub;
            events.execute(() -> deliver(first, eventBody(first.service)));   // the initial event, after the answer
        }
    }

    private void publish(final String service) {
        final List<Sub> to = new ArrayList<>();
        synchronized (this) {
            for (Sub s : subs.values()) if (s.service.equals(service)) to.add(s);
        }
        if (to.isEmpty()) return;
        final String body = eventBody(service);
        try {
            events.execute(() -> {
                for (Sub s : to) deliver(s, body);
            });
        } catch (RuntimeException ignored) {   // shutting down
        }
    }

    private String eventBody(String service) {
        String inner;
        if (service.equals(CMS)) {
            return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\">"
                    + "<e:property><SourceProtocolInfo></SourceProtocolInfo></e:property>"
                    + "<e:property><SinkProtocolInfo>" + esc(SINK) + "</SinkProtocolInfo></e:property>"
                    + "<e:property><CurrentConnectionIDs>0</CurrentConnectionIDs></e:property></e:propertyset>";
        } else if (service.equals(RCS)) {
            inner = "<Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/RCS/\"><InstanceID val=\"0\">"
                    + "<Volume channel=\"Master\" val=\"" + volumePercent() + "\"/>"
                    + "<Mute channel=\"Master\" val=\"" + (muted() ? 1 : 0) + "\"/>"
                    + "<PresetNameList val=\"FactoryDefaults\"/></InstanceID></Event>";
        } else {
            synchronized (this) {
                inner = "<Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/AVT/\"><InstanceID val=\"0\">"
                        + v("TransportState", transport) + v("TransportStatus", status) + v("TransportPlaySpeed", "1")
                        + v("PlaybackStorageMedium", "NETWORK") + v("CurrentPlayMode", "NORMAL")
                        + v("NumberOfTracks", uri.isEmpty() ? "0" : "1") + v("CurrentTrack", uri.isEmpty() ? "0" : "1")
                        + v("CurrentTrackDuration", time(durMs)) + v("CurrentMediaDuration", time(durMs))
                        + v("CurrentTrackURI", uri) + v("AVTransportURI", uri) + v("CurrentTrackMetaData", meta)
                        + v("AVTransportURIMetaData", meta) + v("CurrentTransportActions", actions())
                        + "</InstanceID></Event>";
            }
        }
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\">"
                + "<e:property><LastChange>" + esc(inner) + "</LastChange></e:property></e:propertyset>";
    }

    private static String v(String name, String value) {
        return "<" + name + " val=\"" + esc(value) + "\"/>";
    }

    private void deliver(Sub s, String body) {
        int seq;
        synchronized (this) {
            if (!subs.containsKey(s.sid)) return;
            seq = s.seq;
            s.seq = s.seq == Integer.MAX_VALUE ? 1 : s.seq + 1;
        }
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        for (String cb : s.callbacks) {
            Uri u = Uri.parse(cb);
            int port = u.getPort() < 0 ? 80 : u.getPort();
            String path = u.getEncodedPath() == null || u.getEncodedPath().isEmpty() ? "/" : u.getEncodedPath();
            if (u.getEncodedQuery() != null) path += "?" + u.getEncodedQuery();
            try (Socket sock = new Socket()) {
                sock.connect(new InetSocketAddress(u.getHost(), port), 2000);
                sock.setSoTimeout(3000);
                String head = "NOTIFY " + path + " HTTP/1.1\r\nHOST: " + u.getHost() + ":" + port
                        + "\r\nCONTENT-TYPE: text/xml; charset=\"utf-8\"\r\nNT: upnp:event\r\nNTS: upnp:propchange\r\nSID: "
                        + s.sid + "\r\nSEQ: " + seq + "\r\nCONTENT-LENGTH: " + b.length + "\r\nConnection: close\r\n\r\n";
                OutputStream o = sock.getOutputStream();
                o.write(head.getBytes(StandardCharsets.ISO_8859_1));
                o.write(b);
                o.flush();
                InputStream in = sock.getInputStream();
                line(in);   // its answer, so it doesn't see a reset
                return;     // delivered to the first callback that takes it
            } catch (IOException | RuntimeException ignored) {
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------- helpers

    /** An argument of a SOAP action, unescaped ("" when absent). */
    private static String arg(String body, String name) {
        Matcher m = Pattern.compile("<(?:\\w+:)?" + name + "(?:\\s[^>]*)?>(.*?)</(?:\\w+:)?" + name + ">", Pattern.DOTALL).matcher(body);
        return m.find() ? unesc(m.group(1)) : "";
    }

    /** A field of the DIDL-Lite metadata ("" when absent). */
    private static String didl(String meta, String tag) {
        if (meta == null) return "";
        Matcher m = Pattern.compile("<" + Pattern.quote(tag) + "(?:\\s[^>]*)?>(.*?)</" + Pattern.quote(tag) + ">", Pattern.DOTALL).matcher(meta);
        return m.find() ? unesc(m.group(1)).trim() : "";
    }

    private static String attr(String meta, String tag, String attribute) {
        if (meta == null) return "";
        Matcher m = Pattern.compile("<" + tag + "\\s[^>]*?\\b" + attribute + "=\"([^\"]*)\"").matcher(meta);
        return m.find() ? unesc(m.group(1)) : "";
    }

    /** Video, song or photo: the metadata's class or MIME type, else the link's extension. */
    static String kindOf(String meta, String url) {
        String cls = didl(meta, "upnp:class").toLowerCase(Locale.ROOT);
        if (cls.contains("imageitem")) return KIND_IMAGE;
        if (cls.contains("audioitem")) return KIND_AUDIO;
        if (cls.contains("videoitem")) return KIND_VIDEO;
        String info = attr(meta, "res", "protocolInfo").toLowerCase(Locale.ROOT);
        if (info.contains(":image/")) return KIND_IMAGE;
        if (info.contains(":audio/")) return KIND_AUDIO;
        if (info.contains(":video/")) return KIND_VIDEO;
        String path = Uri.parse(url).getPath();
        String type = PlayerActivity.mime(path == null ? "" : path);
        if (type.startsWith("image/")) return KIND_IMAGE;
        if (type.startsWith("audio/")) return KIND_AUDIO;
        return KIND_VIDEO;
    }

    private static String fileName(Uri u) {
        String last = u.getLastPathSegment();
        if (last == null) return "";
        int dot = last.lastIndexOf('.');
        return dot > 0 ? last.substring(0, dot) : last;
    }

    /** "H:MM:SS[.mmm]" → ms (0 when it can't be read). */
    static int parseTime(String t) {
        if (t == null || t.trim().isEmpty()) return 0;
        try {
            String[] p = t.trim().split(":");
            double s = 0;
            for (String part : p) s = s * 60 + Double.parseDouble(part);
            return (int) Math.round(s * 1000);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static String time(int ms) {
        int s = Math.max(0, ms / 1000);
        return String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60);
    }

    static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': b.append("&amp;"); break;
                case '<': b.append("&lt;"); break;
                case '>': b.append("&gt;"); break;
                case '"': b.append("&quot;"); break;
                case '\'': b.append("&apos;"); break;
                default: b.append(c);
            }
        }
        return b.toString();
    }

    private static final Pattern ENTITY = Pattern.compile("&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos);");

    static String unesc(String s) {
        if (s.indexOf('&') < 0) return s;
        Matcher m = ENTITY.matcher(s);
        StringBuffer b = new StringBuffer();
        while (m.find()) {
            String e = m.group(1), r;
            switch (e) {
                case "amp": r = "&"; break;
                case "lt": r = "<"; break;
                case "gt": r = ">"; break;
                case "quot": r = "\""; break;
                case "apos": r = "'"; break;
                default:
                    int cp = e.startsWith("#x") ? Integer.parseInt(e.substring(2), 16) : Integer.parseInt(e.substring(1));
                    r = new String(Character.toChars(cp));
            }
            m.appendReplacement(b, Matcher.quoteReplacement(r));
        }
        m.appendTail(b);
        return b.toString();
    }

    // ---------------------------------------------------------------------------------------------------- SCPDs

    /** The three services' descriptions, written out from compact lists. */
    private static final class Scpd {
        static final String AVT = build(new String[]{
                "SetAVTransportURI InstanceID:A_ARG_TYPE_InstanceID CurrentURI:AVTransportURI CurrentURIMetaData:AVTransportURIMetaData",
                "SetNextAVTransportURI InstanceID:A_ARG_TYPE_InstanceID NextURI:NextAVTransportURI NextURIMetaData:NextAVTransportURIMetaData",
                "GetMediaInfo InstanceID:A_ARG_TYPE_InstanceID > NrTracks:NumberOfTracks MediaDuration:CurrentMediaDuration CurrentURI:AVTransportURI CurrentURIMetaData:AVTransportURIMetaData NextURI:NextAVTransportURI NextURIMetaData:NextAVTransportURIMetaData PlayMedium:PlaybackStorageMedium RecordMedium:RecordStorageMedium WriteStatus:RecordMediumWriteStatus",
                "GetTransportInfo InstanceID:A_ARG_TYPE_InstanceID > CurrentTransportState:TransportState CurrentTransportStatus:TransportStatus CurrentSpeed:TransportPlaySpeed",
                "GetPositionInfo InstanceID:A_ARG_TYPE_InstanceID > Track:CurrentTrack TrackDuration:CurrentTrackDuration TrackMetaData:CurrentTrackMetaData TrackURI:CurrentTrackURI RelTime:RelativeTimePosition AbsTime:AbsoluteTimePosition RelCount:RelativeCounterPosition AbsCount:AbsoluteCounterPosition",
                "GetDeviceCapabilities InstanceID:A_ARG_TYPE_InstanceID > PlayMedia:PossiblePlaybackStorageMedia RecMedia:PossibleRecordStorageMedia RecQualityModes:PossibleRecordQualityModes",
                "GetTransportSettings InstanceID:A_ARG_TYPE_InstanceID > PlayMode:CurrentPlayMode RecQualityMode:CurrentRecordQualityMode",
                "GetCurrentTransportActions InstanceID:A_ARG_TYPE_InstanceID > Actions:CurrentTransportActions",
                "Stop InstanceID:A_ARG_TYPE_InstanceID",
                "Play InstanceID:A_ARG_TYPE_InstanceID Speed:TransportPlaySpeed",
                "Pause InstanceID:A_ARG_TYPE_InstanceID",
                "Seek InstanceID:A_ARG_TYPE_InstanceID Unit:A_ARG_TYPE_SeekMode Target:A_ARG_TYPE_SeekTarget",
                "Next InstanceID:A_ARG_TYPE_InstanceID",
                "Previous InstanceID:A_ARG_TYPE_InstanceID",
                "SetPlayMode InstanceID:A_ARG_TYPE_InstanceID NewPlayMode:CurrentPlayMode",
        }, new String[]{
                "*LastChange string", "A_ARG_TYPE_InstanceID ui4",
                "TransportState string STOPPED,PLAYING,PAUSED_PLAYBACK,TRANSITIONING,NO_MEDIA_PRESENT",
                "TransportStatus string OK,ERROR_OCCURRED", "TransportPlaySpeed string 1",
                "PlaybackStorageMedium string NETWORK,NONE", "RecordStorageMedium string NOT_IMPLEMENTED",
                "PossiblePlaybackStorageMedia string", "PossibleRecordStorageMedia string",
                "PossibleRecordQualityModes string", "RecordMediumWriteStatus string NOT_IMPLEMENTED",
                "CurrentPlayMode string NORMAL", "CurrentRecordQualityMode string NOT_IMPLEMENTED",
                "NumberOfTracks ui4", "CurrentTrack ui4", "CurrentTrackDuration string", "CurrentMediaDuration string",
                "CurrentTrackMetaData string", "CurrentTrackURI string", "AVTransportURI string",
                "AVTransportURIMetaData string", "NextAVTransportURI string", "NextAVTransportURIMetaData string",
                "RelativeTimePosition string", "AbsoluteTimePosition string", "RelativeCounterPosition i4",
                "AbsoluteCounterPosition i4", "CurrentTransportActions string",
                "A_ARG_TYPE_SeekMode string REL_TIME,ABS_TIME,TRACK_NR", "A_ARG_TYPE_SeekTarget string",
        });
        static final String RCS = build(new String[]{
                "ListPresets InstanceID:A_ARG_TYPE_InstanceID > CurrentPresetNameList:PresetNameList",
                "SelectPreset InstanceID:A_ARG_TYPE_InstanceID PresetName:A_ARG_TYPE_PresetName",
                "GetMute InstanceID:A_ARG_TYPE_InstanceID Channel:A_ARG_TYPE_Channel > CurrentMute:Mute",
                "SetMute InstanceID:A_ARG_TYPE_InstanceID Channel:A_ARG_TYPE_Channel DesiredMute:Mute",
                "GetVolume InstanceID:A_ARG_TYPE_InstanceID Channel:A_ARG_TYPE_Channel > CurrentVolume:Volume",
                "SetVolume InstanceID:A_ARG_TYPE_InstanceID Channel:A_ARG_TYPE_Channel DesiredVolume:Volume",
        }, new String[]{
                "*LastChange string", "A_ARG_TYPE_InstanceID ui4", "A_ARG_TYPE_Channel string Master",
                "A_ARG_TYPE_PresetName string FactoryDefaults", "PresetNameList string", "Mute boolean", "Volume ui2 0..100",
        });
        static final String CMS = build(new String[]{
                "GetProtocolInfo > Source:SourceProtocolInfo Sink:SinkProtocolInfo",
                "GetCurrentConnectionIDs > ConnectionIDs:CurrentConnectionIDs",
                "GetCurrentConnectionInfo ConnectionID:A_ARG_TYPE_ConnectionID > RcsID:A_ARG_TYPE_RcsID AVTransportID:A_ARG_TYPE_AVTransportID ProtocolInfo:A_ARG_TYPE_ProtocolInfo PeerConnectionManager:A_ARG_TYPE_ConnectionManager PeerConnectionID:A_ARG_TYPE_ConnectionID Direction:A_ARG_TYPE_Direction Status:A_ARG_TYPE_ConnectionStatus",
        }, new String[]{
                "*SourceProtocolInfo string", "*SinkProtocolInfo string", "*CurrentConnectionIDs string",
                "A_ARG_TYPE_ConnectionStatus string OK,ContentFormatMismatch,InsufficientBandwidth,UnreliableChannel,Unknown",
                "A_ARG_TYPE_ConnectionManager string", "A_ARG_TYPE_Direction string Input,Output",
                "A_ARG_TYPE_ProtocolInfo string", "A_ARG_TYPE_ConnectionID i4", "A_ARG_TYPE_AVTransportID i4",
                "A_ARG_TYPE_RcsID i4",
        });

        /** Actions: "Name in:Variable … > out:Variable …". Variables: "[*]Name type [a,b,c | min..max]" (* = evented). */
        private static String build(String[] actions, String[] vars) {
            StringBuilder b = new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">"
                    + "<specVersion><major>1</major><minor>0</minor></specVersion><actionList>");
            for (String a : actions) {
                String[] p = a.split(" ");
                b.append("<action><name>").append(p[0]).append("</name><argumentList>");
                String dir = "in";
                for (int i = 1; i < p.length; i++) {
                    if (p[i].equals(">")) {
                        dir = "out";
                        continue;
                    }
                    String[] nv = p[i].split(":");
                    b.append("<argument><name>").append(nv[0]).append("</name><direction>").append(dir)
                            .append("</direction><relatedStateVariable>").append(nv[1]).append("</relatedStateVariable></argument>");
                }
                b.append("</argumentList></action>");
            }
            b.append("</actionList><serviceStateTable>");
            for (String v : vars) {
                String[] p = v.split(" ");
                boolean evented = p[0].startsWith("*");
                b.append("<stateVariable sendEvents=\"").append(evented ? "yes" : "no").append("\"><name>")
                        .append(evented ? p[0].substring(1) : p[0]).append("</name><dataType>").append(p[1]).append("</dataType>");
                if (p.length > 2 && p[2].contains("..")) {
                    String[] r = p[2].split("\\.\\.");
                    b.append("<allowedValueRange><minimum>").append(r[0]).append("</minimum><maximum>").append(r[1])
                            .append("</maximum><step>1</step></allowedValueRange>");
                } else if (p.length > 2) {
                    b.append("<allowedValueList>");
                    for (String o : p[2].split(",")) b.append("<allowedValue>").append(o).append("</allowedValue>");
                    b.append("</allowedValueList>");
                }
                b.append("</stateVariable>");
            }
            return b.append("</serviceStateTable></scpd>").toString();
        }
    }
}
