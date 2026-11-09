package io.freemytv;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.tv.TvContract;
import android.media.tv.TvInputManager;
import android.media.tv.TvView;
import android.os.Bundle;
import android.text.TextPaint;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;

/**
 * The HDMI input, full screen, in place of the board's live TV app (DroidLogic's, which also carries a British
 * Freeview client): a TvView tuned to the input, its name for a moment, and "Sin señal" while nothing comes in.
 * Back goes home; the other keys go on to the input (HDMI-CEC hands them to the device).
 * The RK3326 projector has no TV input: its HDMI receiver (an RK628 bridge) is camera 0, and its sound is turned on in
 * the audio HAL with "HDMIin_enable" (what Rockchip's own HDMI app does). There a SurfaceView shows the camera.
 */
public class HdmiActivity extends Activity {
    static final String EXTRA_INPUT = "input", EXTRA_LABEL = "label";
    /** RK3326: the input id that means the HDMI camera. */
    static final String CAMERA_INPUT = "camera:0";

    private TvView tv;
    private CameraFeed camera;
    private Overlay overlay;
    private String inputId, label;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Ui.init(this);
        inputId = getIntent().getStringExtra(EXTRA_INPUT);
        label = getIntent().getStringExtra(EXTRA_LABEL);
        if (label == null || label.isEmpty()) label = "HDMI";
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        overlay = new Overlay(this);
        FrameLayout.LayoutParams full = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        if (CAMERA_INPUT.equals(inputId)) {
            camera = new CameraFeed(this, overlay);
            root.addView(camera.view, new FrameLayout.LayoutParams(full));
            root.addView(overlay, full);
            setContentView(root);
            return;
        }
        tv = new TvView(this);
        root.addView(tv, new FrameLayout.LayoutParams(full));
        root.addView(overlay, full);
        setContentView(root);
        tv.setCallback(new TvView.TvInputCallback() {
            @Override public void onVideoAvailable(String id) { overlay.show(null); }
            @Override public void onVideoUnavailable(String id, int reason) {
                overlay.show(reason == TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING ? "Conectando…" : "Sin señal");
            }
            @Override public void onConnectionFailed(String id) { overlay.show("No se pudo abrir la entrada"); }
            @Override public void onDisconnected(String id) { overlay.show("Sin señal"); }
        });
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        String id = intent.getStringExtra(EXTRA_INPUT);
        if (camera != null) return;   // one input there
        if (id != null && !id.equals(inputId)) {
            inputId = id;
            String l = intent.getStringExtra(EXTRA_LABEL);
            label = l == null || l.isEmpty() ? "HDMI" : l;
            tune();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        tune();
    }

    private void tune() {
        if (camera != null) {
            overlay.show("Conectando…");
            if (checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                camera.start();
            } else {
                requestPermissions(new String[] {android.Manifest.permission.CAMERA}, 1);
            }
            return;
        }
        if (inputId == null) {
            overlay.show("No se pudo abrir la entrada");
            return;
        }
        overlay.show("Conectando…");
        tv.tune(inputId, TvContract.buildChannelUriForPassthroughInput(inputId));
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        if (camera == null) return;
        if (results.length > 0 && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) camera.start();
        else overlay.show("No se pudo abrir la entrada");
    }

    @Override
    protected void onStop() {
        // Let the input go: the picture and sound stop with the screen.
        if (camera != null) camera.stop();
        else tv.reset();
        super.onStop();
    }

    /**
     * RK3326: the HDMI camera on a SurfaceView, and its sound through the audio HAL. "Sin señal" until frames come; with
     * nothing plugged in, the camera errors or stays quiet, and it's opened again every few seconds.
     */
    private static final class CameraFeed implements android.view.SurfaceHolder.Callback {
        private static final String ID = "0";
        final android.view.SurfaceView view;
        private final Activity activity;
        private final Overlay overlay;
        private final android.os.HandlerThread thread = new android.os.HandlerThread("hdmi-camera");
        private final android.os.Handler handler;
        private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        private android.hardware.camera2.CameraDevice device;
        private android.hardware.camera2.CameraCaptureSession session;
        private android.view.Surface surface;
        private boolean wanted, sound, frames;

        CameraFeed(Activity a, Overlay o) {
            activity = a;
            overlay = o;
            view = new android.view.SurfaceView(a);
            view.getHolder().addCallback(this);
            thread.start();
            handler = new android.os.Handler(thread.getLooper());
        }

        void start() {
            wanted = true;
            handler.post(this::open);
        }

        void stop() {
            wanted = false;
            main.removeCallbacksAndMessages(null);
            handler.removeCallbacksAndMessages(null);
            handler.post(this::close);
        }

        @Override public void surfaceCreated(android.view.SurfaceHolder h) {
            handler.post(() -> {
                surface = h.getSurface();
                open();
            });
        }

        @Override public void surfaceChanged(android.view.SurfaceHolder h, int format, int w, int hgt) {}

        @Override public void surfaceDestroyed(android.view.SurfaceHolder h) {
            handler.post(() -> {
                close();
                surface = null;
            });
        }

        /** The camera's largest preview size up to 1080p (the bridge sends what the source does, scaled to this). */
        private android.util.Size size(android.hardware.camera2.CameraManager cm) throws android.hardware.camera2.CameraAccessException {
            android.hardware.camera2.params.StreamConfigurationMap map = cm.getCameraCharacteristics(ID)
                    .get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            android.util.Size best = new android.util.Size(1920, 1080);
            if (map == null) return best;
            long area = 0;
            for (android.util.Size s : map.getOutputSizes(android.view.SurfaceHolder.class)) {
                long a = (long) s.getWidth() * s.getHeight();
                if (s.getWidth() <= 1920 && s.getHeight() <= 1080 && a > area) {
                    area = a;
                    best = s;
                }
            }
            return best;
        }

        private void open() {
            if (!wanted || device != null || surface == null) return;
            android.hardware.camera2.CameraManager cm =
                    (android.hardware.camera2.CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
            try {
                final android.util.Size s = size(cm);
                main.post(() -> view.getHolder().setFixedSize(s.getWidth(), s.getHeight()));
                cm.openCamera(ID, new android.hardware.camera2.CameraDevice.StateCallback() {
                    @Override public void onOpened(android.hardware.camera2.CameraDevice d) {
                        device = d;
                        preview();
                    }
                    @Override public void onDisconnected(android.hardware.camera2.CameraDevice d) {
                        d.close();
                        device = null;
                        noSignal();
                    }
                    @Override public void onError(android.hardware.camera2.CameraDevice d, int error) {
                        d.close();
                        device = null;
                        noSignal();
                    }
                }, handler);
            } catch (android.hardware.camera2.CameraAccessException | RuntimeException e) {
                AppLog.w("hdmi camera: " + e.getMessage());
                noSignal();
            }
        }

        private void preview() {
            try {
                frames = false;
                device.createCaptureSession(java.util.Collections.singletonList(surface),
                        new android.hardware.camera2.CameraCaptureSession.StateCallback() {
                    @Override public void onConfigured(android.hardware.camera2.CameraCaptureSession s) {
                        if (device == null) return;
                        session = s;
                        try {
                            android.hardware.camera2.CaptureRequest.Builder b =
                                    device.createCaptureRequest(android.hardware.camera2.CameraDevice.TEMPLATE_PREVIEW);
                            b.addTarget(surface);
                            s.setRepeatingRequest(b.build(), new android.hardware.camera2.CameraCaptureSession.CaptureCallback() {
                                @Override public void onCaptureCompleted(android.hardware.camera2.CameraCaptureSession cs,
                                        android.hardware.camera2.CaptureRequest r, android.hardware.camera2.TotalCaptureResult t) {
                                    if (frames) return;
                                    frames = true;
                                    soundOn(true);
                                    main.post(() -> overlay.show(null));
                                }
                            }, handler);
                        } catch (android.hardware.camera2.CameraAccessException | RuntimeException e) {
                            AppLog.w("hdmi camera: " + e.getMessage());
                            noSignal();
                        }
                    }
                    @Override public void onConfigureFailed(android.hardware.camera2.CameraCaptureSession s) {
                        noSignal();
                    }
                }, handler);
            } catch (android.hardware.camera2.CameraAccessException | RuntimeException e) {
                AppLog.w("hdmi camera: " + e.getMessage());
                noSignal();
            }
            // Nothing in 3 s: no source sending (unless the camera was opened again meanwhile).
            final android.hardware.camera2.CameraDevice opened = device;
            handler.postDelayed(() -> {
                if (wanted && !frames && device == opened && opened != null) noSignal();
            }, 3000);
        }

        /** "Sin señal", the camera let go, and another try in a few seconds. */
        private void noSignal() {
            main.post(() -> overlay.show("Sin señal"));
            close();
            if (wanted) handler.postDelayed(this::open, 3000);
        }

        /**
         * The HDMI's sound, as Rockchip's own app plays it: media.audio.hdmiin turns the receiver's audio input on,
         * the HAL is told, and the sound is read from the camcorder input (where the HDMI's lands) and played on the
         * speaker, 48 kHz stereo, on its own thread. On the worker thread.
         */
        private Thread loop;

        private void soundOn(boolean on) {
            if (sound == on) return;
            sound = on;
            try {
                new ProcessBuilder("setprop", "media.audio.hdmiin", on ? "1" : "0").start().waitFor();
            } catch (java.io.IOException | InterruptedException e) {
                AppLog.w("hdmi sound: " + e.getMessage());
            }
            android.media.AudioManager am = (android.media.AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) am.setParameters("HDMIin_enable=" + on);
            if (on) {
                loop = new Thread(this::pipe, "hdmi-sound");
                loop.start();
            } else if (loop != null) {
                loop.interrupt();
                loop = null;
            }
        }

        private void pipe() {
            int rate = 48000, inCh = android.media.AudioFormat.CHANNEL_IN_STEREO, outCh = android.media.AudioFormat.CHANNEL_OUT_STEREO,
                    pcm = android.media.AudioFormat.ENCODING_PCM_16BIT;
            int size = Math.max(8192, Math.max(android.media.AudioRecord.getMinBufferSize(rate, inCh, pcm),
                    android.media.AudioTrack.getMinBufferSize(rate, outCh, pcm)));
            android.media.AudioRecord in = null;
            android.media.AudioTrack out = null;
            try {
                in = new android.media.AudioRecord(android.media.MediaRecorder.AudioSource.CAMCORDER, rate, inCh, pcm, size);
                out = new android.media.AudioTrack(android.media.AudioManager.STREAM_MUSIC, rate, outCh, pcm, size,
                        android.media.AudioTrack.MODE_STREAM);
                if (in.getState() != android.media.AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("no input");
                in.startRecording();
                out.play();
                byte[] buf = new byte[size / 2];
                while (!Thread.currentThread().isInterrupted()) {
                    int n = in.read(buf, 0, buf.length);
                    if (n > 0) out.write(buf, 0, n);
                    else if (n < 0) break;
                }
            } catch (RuntimeException e) {
                AppLog.w("hdmi sound: " + e.getMessage());
            } finally {
                if (in != null) {
                    try {
                        in.stop();
                    } catch (RuntimeException ignored) {
                    }
                    in.release();
                }
                if (out != null) {
                    try {
                        out.stop();
                    } catch (RuntimeException ignored) {
                    }
                    out.release();
                }
            }
        }

        private void close() {
            soundOn(false);
            if (session != null) {
                try {
                    session.close();
                } catch (RuntimeException ignored) {
                }
                session = null;
            }
            if (device != null) {
                device.close();
                device = null;
            }
            frames = false;
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP) finish();
            return true;
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN) overlay.show(overlay.message);   // the name again, on any key
        return super.dispatchKeyEvent(e);
    }

    /** The input's name in a pill at the top for a few seconds, and a message in the middle while there's no picture. */
    private final class Overlay extends View {
        private final float k;
        private final TextPaint name, big, small;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF pill = new RectF();
        String message;
        private float pillAlpha;
        private ValueAnimator fade;

        Overlay(Context c) {
            super(c);
            k = c.getResources().getDisplayMetrics().heightPixels / 1080f;
            name = new TextPaint(Ui.text(Ui.medium, 30 * k, 0xFFFFFFFF));
            big = new TextPaint(Ui.text(Ui.light, 56 * k, 0xFFFFFFFF));
            big.setTextAlign(Paint.Align.CENTER);
            small = new TextPaint(Ui.text(Ui.regular, 26 * k, 0x99FFFFFF));
            small.setTextAlign(Paint.Align.CENTER);
        }

        void show(String msg) {
            message = msg;
            if (fade != null) fade.cancel();
            pillAlpha = 1;
            fade = ValueAnimator.ofFloat(1, 0);
            fade.setStartDelay(3000);
            fade.setDuration(600);
            fade.addUpdateListener(a -> {
                pillAlpha = (float) a.getAnimatedValue();
                invalidate();
            });
            fade.start();
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            if (message != null) {
                // Opaque: the board's input service draws its own blue "No Signal" under it, which this covers.
                c.drawColor(0xFF0E0E10);
                Symbols.draw(getContext(), c, "hdmi", w / 2, h / 2 - 90 * k, 60 * k, 0x99FFFFFF);
                c.drawText(message, w / 2, h / 2 + 10 * k, big);
                c.drawText(message.equals("Sin señal") ? "Revisá que el equipo esté prendido y el cable bien enchufado."
                        : label, w / 2, h / 2 + 60 * k, small);
            }
            if (pillAlpha <= 0.01f) return;
            float tw = name.measureText(label);
            pill.set(60 * k, 56 * k, 60 * k + tw + 120 * k, 56 * k + 72 * k);
            fill.setColor(Ui.alpha(0xD91B1B1E, pillAlpha));
            c.drawRoundRect(pill, 36 * k, 36 * k, fill);
            Symbols.draw(getContext(), c, "hdmi", pill.left + 48 * k, pill.centerY(), 26 * k, Ui.alpha(0xEBFFFFFF, pillAlpha));
            name.setAlpha(Math.round(255 * pillAlpha));
            c.drawText(label, pill.left + 84 * k, pill.centerY() + 10 * k, name);
        }
    }
}
