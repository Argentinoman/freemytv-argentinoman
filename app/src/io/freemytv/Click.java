package io.freemytv;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The home screen's sounds, in the spirit of an Apple TV's (made here, not copied): moving the focus is a tiny dry
 * "tick", a breath of filtered noise with a bright ping under it; opening is a short round "tock", like a tap on wood. The
 * app synthesizes them, writes them once as WAVs in the cache and plays them quietly through SoundPool. Only on the
 * home screen, never inside apps.
 */
final class Click {
    private static final int RATE = 48_000;
    private SoundPool pool;
    private int move, open;

    Click(Context c) {
        try {
            pool = new SoundPool.Builder().setMaxStreams(3).setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
            move = pool.load(wav(c, "tick-v2.wav", false), 1);
            open = pool.load(wav(c, "tock-v3.wav", true), 1);
        } catch (IOException | RuntimeException e) {
            AppLog.w("click sound unavailable: " + e.getMessage());
            release();
        }
    }

    void move() {
        if (pool != null && Prefs.sounds()) pool.play(move, 0.10f, 0.10f, 1, 0, 1f);
    }

    void open() {
        if (pool != null && Prefs.sounds()) pool.play(open, 0.19f, 0.19f, 1, 0, 1f);
    }

    void release() {
        if (pool != null) pool.release();
        pool = null;
    }

    /**
     * The tick: 25 ms, noise through a resonant band-pass around 3.2 kHz (the dry "t") over a 2.4 kHz ping, both gone
     * in a few milliseconds. The tock: 45 ms, a woody knock: noise through a narrow band around 1.1 kHz over a 660 Hz
     * tone and its fifth, all decaying fast, no change of pitch. 1 ms attacks: no pops of their own.
     */
    private static String wav(Context c, String name, boolean pop) throws IOException {
        File f = new File(c.getCacheDir(), name);
        if (f.exists() && f.length() > 44) return f.getPath();
        int n = Math.round(RATE * (pop ? 0.045f : 0.025f));
        ByteBuffer b = ByteBuffer.allocate(44 + n * 2).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes("US-ASCII")).putInt(36 + n * 2).put("WAVE".getBytes("US-ASCII"));
        b.put("fmt ".getBytes("US-ASCII")).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(RATE).putInt(RATE * 2).putShort((short) 2).putShort((short) 16);
        b.put("data".getBytes("US-ASCII")).putInt(n * 2);
        java.util.Random rnd = new java.util.Random(7);   // the same sound every time
        // Band-pass (biquad) for the tick's noise.
        double w0 = 2 * Math.PI * (pop ? 1100 : 3200) / RATE, q = pop ? 6 : 2.2, alpha = Math.sin(w0) / (2 * q), a0 = 1 + alpha;
        double b0 = alpha / a0, b2 = -alpha / a0, a1 = -2 * Math.cos(w0) / a0, a2 = (1 - alpha) / a0;
        double x1 = 0, x2 = 0, y1 = 0, y2 = 0;
        for (int i = 0; i < n; i++) {
            double t = (double) i / RATE, attack = Math.min(1, t / 0.001), s;
            if (pop) {
                double x = rnd.nextDouble() * 2 - 1;
                double y = b0 * x + b2 * x2 - a1 * y1 - a2 * y2;
                x2 = x1; x1 = x; y2 = y1; y1 = y;
                double tone = Math.sin(2 * Math.PI * 660 * t) + 0.35 * Math.sin(2 * Math.PI * 990 * t);
                s = attack * (3.0 * y * Math.exp(-t / 0.006) + 0.55 * tone * Math.exp(-t / 0.011));
            } else {
                double x = rnd.nextDouble() * 2 - 1;
                double y = b0 * x + b2 * x2 - a1 * y1 - a2 * y2;
                x2 = x1; x1 = x; y2 = y1; y1 = y;
                double ping = Math.sin(2 * Math.PI * 2400 * t);
                s = attack * (2.4 * y * Math.exp(-t / 0.0028) + 0.35 * ping * Math.exp(-t / 0.0045));
            }
            b.putShort((short) Math.round(Math.max(-1, Math.min(1, s * 0.75)) * Short.MAX_VALUE));
        }
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(b.array());
        }
        return f.getPath();
    }
}
