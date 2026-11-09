package io.freemytv;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The HY300's picture, in place of its vendor app (com.softwinner.tcorrection, which also reported to a Chinese server
 * and holds the switches of the firmware's root installers). The video composer draws the screen into four corners
 * read from persist.vendor.hwc.keystone (x,y of top-left, top-right, bottom-right, bottom-left on a 1920×1080 frame);
 * the projection mode flips the panel. Both need root, so they go through the projector's own ADB, as the switch does.
 * The RK3326 "HY300" (in place of the vendor's settings app, which did it through its BridgeService): SurfaceFlinger
 * warps the screen with four corner offsets, each from its own corner, in screen pixels (persist.sys.keystone.lt, rt,
 * rb, lb as "x,y"), and reads them again when persist.sys.keystone.update changes value. The projection mode is the
 * same warp with the corners swapped (the vendor app's mirrors). Plain setprop: ADB's shell may set them there.
 */
final class Projector {
    private Projector() {}

    static final boolean SUPPORTED = RemoteKeys.PROJECTOR;
    static final String[] MODES = {"Frontal", "Trasera", "Techo", "Techo trasera"};
    static final int MAX_ANGLE = 40, MIN_ZOOM = 60;

    private static final String CORNERS = "persist.vendor.hwc.keystone";
    private static final String FLIP = "/sys/class/aml_lcd/lcd0/paneL_filp";
    private static final float W = 1920, H = 1080;
    private static final String RK_KEYSTONE = "persist.sys.keystone.", RK_UPDATE = RK_KEYSTONE + "update";
    /** RK3326: the corner props in our corner order (top-left, top-right, bottom-right, bottom-left). */
    private static final String[] RK_CORNERS = {"lt", "rt", "rb", "lb"};
    /**
     * Half the lens's vertical spread. Fitted to the vendor app's own result: tilted 26.3° up, it narrowed the far edge
     * to 84.5 % of the width and the picture to 85.6 % of the height.
     */
    private static final double HALF_SPREAD = Math.toRadians(9.65);

    /**
     * What's set: automatic keystone, the manual angles (degrees), the zoom (%), the projection mode, and each corner
     * moved by hand (x,y on the 1920×1080 frame, top-left, top-right, bottom-right, bottom-left), on top of the rest.
     */
    static final class Setting {
        boolean auto = true;
        int vertical, horizontal, zoom = 100, mode;
        final int[] corners = new int[8];

        static Setting read(Context c) {
            Setting s = new Setting();
            String[] v = Prefs.projector(c).split(",");
            if (v.length == 5 || v.length == 13) {
                try {
                    s.auto = v[0].equals("1");
                    s.vertical = clamp(Integer.parseInt(v[1]), -MAX_ANGLE, MAX_ANGLE);
                    s.horizontal = clamp(Integer.parseInt(v[2]), -MAX_ANGLE, MAX_ANGLE);
                    s.zoom = clamp(Integer.parseInt(v[3]), MIN_ZOOM, 100);
                    s.mode = clamp(Integer.parseInt(v[4]), 0, MODES.length - 1);
                    for (int i = 0; v.length == 13 && i < 8; i++) s.moveCorner(i / 2, i % 2 == 0 ? Integer.parseInt(v[5 + i]) : 0,
                            i % 2 == 1 ? Integer.parseInt(v[5 + i]) : 0);
                } catch (NumberFormatException ignored) {
                }
            }
            return s;
        }

        void save(Context c) {
            StringBuilder sb = new StringBuilder((auto ? "1" : "0") + "," + vertical + "," + horizontal + "," + zoom + "," + mode);
            for (int v : corners) sb.append(',').append(v);
            Prefs.setProjector(c, sb.toString());
        }

        /** Moves corner i (0 top-left … 3 bottom-left) by dx,dy, kept between its own corner and a third of the way in. */
        void moveCorner(int i, int dx, int dy) {
            int inX = i == 0 || i == 3 ? 1 : -1, inY = i < 2 ? 1 : -1;   // the way inwards from that corner
            corners[2 * i] = inX * clamp(inX * (corners[2 * i] + dx), 0, (int) W / 3);
            corners[2 * i + 1] = inY * clamp(inY * (corners[2 * i + 1] + dy), 0, (int) H / 3);
        }

        boolean cornersMoved() {
            for (int v : corners) if (v != 0) return true;
            return false;
        }

        void resetCorners() {
            java.util.Arrays.fill(corners, 0);
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** How much the far edge must shrink for a tilt: the near and far edges are this far apart in width on the wall. */
    private static double narrowing(int degrees) {
        double t = Math.toRadians(Math.abs(degrees));
        return Math.cos(t + HALF_SPREAD) / Math.cos(t - HALF_SPREAD);
    }

    /**
     * The four corners for these angles, this zoom and the corners moved by hand, as the composer reads them. Its
     * frame is the panel's, upside down on the wall: a corner moved on the wall moves the panel's opposite one, the
     * other way up and down. (Not measured on an HY300 yet.)
     */
    static String corners(int vertical, int horizontal, int zoom, int[] moved) {
        float[] p = points(vertical, horizontal, zoom);
        for (int wall = 0; wall < 4; wall++) {
            int i = 3 - wall;   // top-left ↔ bottom-left, top-right ↔ bottom-right
            p[2 * i] += moved[2 * wall];
            p[2 * i + 1] -= moved[2 * wall + 1];
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            if (i > 0) sb.append(',');
            sb.append(String.format(Locale.US, "%.1f,%.1f", p[2 * i], p[2 * i + 1]));
        }
        return sb.toString();
    }

    /**
     * RK3326: the setprop commands for these angles, zoom and mode on a w×h screen. The warp is drawn on the screen
     * itself, not on the panel behind the lens, so a tilt up narrows the top edge (the HY300's corners are the other
     * way up). Each mode moves the corners to the opposite side: rear mirrors left-right, ceiling turns it upside
     * down, ceiling-rear mirrors top-bottom. The corners moved by hand are where they end up on the screen.
     */
    static String rkCommands(int vertical, int horizontal, int zoom, int mode, int[] moved, int w, int h) {
        float[] p = points(-vertical, horizontal, zoom);
        boolean mirrorX = mode == 1 || mode == 2, mirrorY = mode == 2 || mode == 3;
        float[] baseX = {0, w, w, 0}, baseY = {0, 0, h, h};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            float x = p[2 * i] * w / W, y = p[2 * i + 1] * h / H;
            if (mirrorX) x = w - x;
            if (mirrorY) y = h - y;
            // Where this corner landed (top-left, top-right, bottom-right, bottom-left): that one's hand move.
            int at = i;
            if (mirrorX) at = new int[] {1, 0, 3, 2}[at];
            if (mirrorY) at = new int[] {3, 2, 1, 0}[at];
            x += moved[2 * at] * w / W;
            y += moved[2 * at + 1] * h / H;
            sb.append("setprop ").append(RK_KEYSTONE).append(RK_CORNERS[i]).append(' ')
                    .append(Math.round(x - baseX[i])).append(',').append(Math.round(y - baseY[i])).append(';');
        }
        // Read again only on a change of value: flip it.
        sb.append("if [ \"$(getprop ").append(RK_UPDATE).append(")\" = 1 ]; then setprop ").append(RK_UPDATE)
                .append(" 0; else setprop ").append(RK_UPDATE).append(" 1; fi");
        return sb.toString();
    }

    /** The corners as x,y pairs on the 1920×1080 frame (top-left, top-right, bottom-right, bottom-left). */
    private static float[] points(int vertical, int horizontal, int zoom) {
        float[] x = {0, W, W, 0}, y = {0, 0, H, H};   // top-left, top-right, bottom-right, bottom-left
        if (vertical != 0) {
            // Tilted up, the picture's far (top) edge on the wall is the panel's bottom one: the lens flips it.
            double r = narrowing(vertical);
            float inset = (float) (W * (1 - r) / 2), tall = (float) (H * (1 - r) * 0.93);
            int a = vertical > 0 ? 2 : 0, b = vertical > 0 ? 3 : 1;
            x[a] += a == 2 ? -inset : inset;
            x[b] += b == 3 ? inset : -inset;
            for (int i = 0; i < 4; i++) y[i] = vertical > 0 ? y[i] * (H - tall) / H : tall + y[i] * (H - tall) / H;
        }
        if (horizontal != 0) {
            double r = narrowing(horizontal);
            float inset = (float) (H * (1 - r) / 2), wide = (float) (W * (1 - r) * 0.93);
            int a = horizontal > 0 ? 1 : 0, b = horizontal > 0 ? 2 : 3;
            y[a] += inset;
            y[b] -= inset;
            for (int i = 0; i < 4; i++) x[i] = horizontal > 0 ? x[i] * (W - wide) / W : wide + x[i] * (W - wide) / W;
        }
        float z = zoom / 100f;
        float[] p = new float[8];
        for (int i = 0; i < 4; i++) {
            p[2 * i] = W / 2 + (x[i] - W / 2) * z;
            p[2 * i + 1] = H / 2 + (y[i] - H / 2) * z;
        }
        return p;
    }

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static String sentCorners, sentMode;
    private static final java.util.concurrent.atomic.AtomicInteger LATEST = new java.util.concurrent.atomic.AtomicInteger();

    /** Puts the setting on screen (automatic: with the tilt measured now). Off the UI thread; only what changed is sent. */
    static void apply(Context c, Setting s) {
        final Context app = c.getApplicationContext();
        final boolean auto = s.auto;
        final int v = s.vertical, hz = s.horizontal, zoom = s.zoom, mode = s.mode;
        final int[] moved = s.corners.clone();
        final int ticket = LATEST.incrementAndGet();
        WORKER.execute(() -> {
            if (ticket != LATEST.get()) return;   // a newer change is queued: holding an arrow sends only the last
            int vertical = auto ? measuredTilt(app) : v;
            String corners, m = String.valueOf(mode);
            StringBuilder cmd = new StringBuilder();
            if (RemoteKeys.RK3326) {
                android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
                ((android.view.WindowManager) app.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(dm);
                int w = Math.max(dm.widthPixels, dm.heightPixels), h = Math.min(dm.widthPixels, dm.heightPixels);
                corners = rkCommands(vertical, auto ? 0 : hz, zoom, mode, moved, w, h);
                if (!corners.equals(sentCorners)) cmd.append(corners);
                sentMode = m;   // the mode is part of the corners there
            } else {
                corners = corners(vertical, auto ? 0 : hz, zoom, moved);
                if (!corners.equals(sentCorners)) cmd.append("setprop ").append(CORNERS).append(' ').append(corners).append(';');
                if (!m.equals(sentMode)) cmd.append("echo ").append(m).append(" > ").append(FLIP).append(';');
            }
            if (cmd.length() == 0) return;
            try (Adb adb = Adb.connect(app, 3000, null)) {
                adb.shell(cmd.toString());
                sentCorners = corners;
                sentMode = m;
            } catch (IOException e) {
                AppLog.w("projector: " + e.getMessage());
            }
        });
    }

    /** The automatic angle, on Home's start: the projector may have been moved while off. */
    static void applySaved(Context c) {
        if (SUPPORTED) apply(c, Setting.read(c));
    }

    static volatile int lastTilt;

    /** The projector's tilt up (+) or down (−) in whole degrees, from the accelerometer; 0 without an answer. */
    static int measuredTilt(Context c) {
        SensorManager sm = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
        Sensor accel = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (accel == null) return 0;
        final float[] sum = new float[3];
        final int[] count = {0};
        final CountDownLatch enough = new CountDownLatch(1);
        SensorEventListener listener = new SensorEventListener() {
            @Override public void onSensorChanged(SensorEvent e) {
                synchronized (sum) {
                    for (int i = 0; i < 3; i++) sum[i] += e.values[i];
                    if (++count[0] >= 8) enough.countDown();   // a few readings, averaged: the fan shakes it
                }
            }
            @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}
        };
        android.os.HandlerThread thread = new android.os.HandlerThread("tilt");
        thread.start();
        sm.registerListener(listener, accel, SensorManager.SENSOR_DELAY_GAME, new android.os.Handler(thread.getLooper()));
        try {
            enough.await(1500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        }
        sm.unregisterListener(listener);
        thread.quitSafely();
        synchronized (sum) {
            if (count[0] == 0) return lastTilt;
            lastTilt = (int) Math.round(Math.toDegrees(Math.atan2(sum[1], sum[2])));
        }
        AppLog.w("projector: tilt " + lastTilt + "°");
        return lastTilt;
    }
}
