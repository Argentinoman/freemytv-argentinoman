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
 */
final class Projector {
    private Projector() {}

    static final boolean SUPPORTED = RemoteKeys.HY300;
    static final String[] MODES = {"Frontal", "Trasera", "Techo", "Techo trasera"};
    static final int MAX_ANGLE = 40, MIN_ZOOM = 60;

    private static final String CORNERS = "persist.vendor.hwc.keystone";
    private static final String FLIP = "/sys/class/aml_lcd/lcd0/paneL_filp";
    private static final float W = 1920, H = 1080;
    /**
     * Half the lens's vertical spread. Fitted to the vendor app's own result: tilted 26.3° up, it narrowed the far edge
     * to 84.5 % of the width and the picture to 85.6 % of the height.
     */
    private static final double HALF_SPREAD = Math.toRadians(9.65);

    /** What's set: automatic keystone, the manual angles (degrees), the zoom (%) and the projection mode. */
    static final class Setting {
        boolean auto = true;
        int vertical, horizontal, zoom = 100, mode;

        static Setting read(Context c) {
            Setting s = new Setting();
            String[] v = Prefs.projector(c).split(",");
            if (v.length == 5) {
                try {
                    s.auto = v[0].equals("1");
                    s.vertical = clamp(Integer.parseInt(v[1]), -MAX_ANGLE, MAX_ANGLE);
                    s.horizontal = clamp(Integer.parseInt(v[2]), -MAX_ANGLE, MAX_ANGLE);
                    s.zoom = clamp(Integer.parseInt(v[3]), MIN_ZOOM, 100);
                    s.mode = clamp(Integer.parseInt(v[4]), 0, MODES.length - 1);
                } catch (NumberFormatException ignored) {
                }
            }
            return s;
        }

        void save(Context c) {
            Prefs.setProjector(c, (auto ? "1" : "0") + "," + vertical + "," + horizontal + "," + zoom + "," + mode);
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

    /** The four corners for these angles and this zoom, as the composer reads them. */
    static String corners(int vertical, int horizontal, int zoom) {
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
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            if (i > 0) sb.append(',');
            sb.append(String.format(Locale.US, "%.1f,%.1f", W / 2 + (x[i] - W / 2) * z, H / 2 + (y[i] - H / 2) * z));
        }
        return sb.toString();
    }

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static String sentCorners, sentMode;
    private static final java.util.concurrent.atomic.AtomicInteger LATEST = new java.util.concurrent.atomic.AtomicInteger();

    /** Puts the setting on screen (automatic: with the tilt measured now). Off the UI thread; only what changed is sent. */
    static void apply(Context c, Setting s) {
        final Context app = c.getApplicationContext();
        final boolean auto = s.auto;
        final int v = s.vertical, hz = s.horizontal, zoom = s.zoom, mode = s.mode;
        final int ticket = LATEST.incrementAndGet();
        WORKER.execute(() -> {
            if (ticket != LATEST.get()) return;   // a newer change is queued: holding an arrow sends only the last
            int vertical = auto ? measuredTilt(app) : v;
            String corners = corners(vertical, auto ? 0 : hz, zoom), m = String.valueOf(mode);
            StringBuilder cmd = new StringBuilder();
            if (!corners.equals(sentCorners)) cmd.append("setprop ").append(CORNERS).append(' ').append(corners).append(';');
            if (!m.equals(sentMode)) cmd.append("echo ").append(m).append(" > ").append(FLIP).append(';');
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
