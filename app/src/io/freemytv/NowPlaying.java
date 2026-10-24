package io.freemytv;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.List;

/**
 * What is playing now (Spotify, YouTube Music or any app with a media session): title, artist, cover and position,
 * for the widget at the top right of the home screen. Needs NowPlayingListener's notification access; without it,
 * nothing shows. Runs on the UI thread: Android delivers the callbacks there, and they are rare.
 */
final class NowPlaying implements MediaSessionManager.OnActiveSessionsChangedListener {
    interface Listener { void onNowPlaying(); }

    /** Video apps: their videos aren't songs, so the widget (music) leaves them out. */
    private static final java.util.Set<String> VIDEO_APPS = new java.util.HashSet<>(java.util.Arrays.asList(
            "com.google.android.youtube.tv", "com.netflix.ninja", "com.amazon.amazonvideo.livingroom",
            "com.disney.disneyplus", "com.wbd.stream", "com.hbo.hbonow", "com.tcl.tv", "com.google.android.videos",
            "com.apple.atve.androidtv.appletv", "com.crunchyroll.crunchyroid", "tv.pluto.android"));

    /** Also this TV's own video apps (LocalApps). */
    private static boolean isVideo(String pkg) {
        return VIDEO_APPS.contains(pkg) || LocalApps.video(pkg);
    }

    /** A paused song stays on screen this long, then the widget goes. */
    private static final long PAUSED_SHOWN = 20 * 60_000L;

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaSessionManager msm;
    private MediaController controller;

    String title = "", artist = "", pkg = "";
    Bitmap art;
    long duration, position, positionAt;   // positionAt: SystemClock.elapsedRealtime() of `position`
    float speed;
    boolean playing;
    long pausedAt;
    /** The session's PlaybackState actions: what this app lets others do (shuffle and repeat are optional). */
    long actions;
    boolean shuffleOn;
    int repeatMode;   // 0 off, 1 one, 2 all (as MediaSessionCompat)
    /** Bumped on every change: the screen re-measures its text only then. */
    int version;

    NowPlaying(Context c, Listener l) {
        context = c;
        listener = l;
    }

    void start() {
        try {
            msm = (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
            ComponentName me = new ComponentName(context, NowPlayingListener.class);
            msm.addOnActiveSessionsChangedListener(this, me, main);
            onActiveSessionsChanged(msm.getActiveSessions(me));
        } catch (SecurityException | NullPointerException e) {
            AppLog.w("now playing: no notification access (" + e.getMessage() + ")");
            msm = null;
        }
    }

    void stop() {
        if (msm != null) msm.removeOnActiveSessionsChangedListener(this);
        msm = null;
        watch(null);
    }

    /**
     * Shuffle and repeat: MediaSessionCompat's ACTION_SET_SHUFFLE_MODE (1 << 21) and ACTION_SET_REPEAT_MODE (1 << 18),
     * sent as its custom actions. Spotify takes both while a playlist or album plays (not a single song); the buttons
     * show only while the app offers them.
     */
    boolean canShuffle() { return (actions & (1L << 21)) != 0; }
    boolean canRepeat() { return (actions & (1L << 18)) != 0; }

    void shuffle() {
        shuffleOn = !shuffleOn;
        custom("SET_SHUFFLE_MODE", "ARGUMENT_SHUFFLE_MODE", shuffleOn ? 1 : 0);
    }

    void repeat() {
        repeatMode = repeatMode == 0 ? 2 : repeatMode == 2 ? 1 : 0;   // off -> all -> one -> off
        custom("SET_REPEAT_MODE", "ARGUMENT_REPEAT_MODE", repeatMode);
    }

    private void custom(String action, String argument, int value) {
        MediaController c = controller;
        if (c == null) return;
        android.os.Bundle b = new android.os.Bundle();
        b.putInt("android.support.v4.media.session.action." + argument, value);
        try {
            c.getTransportControls().sendCustomAction("android.support.v4.media.session.action." + action, b);
        } catch (RuntimeException ignored) {
        }
        listener.onNowPlaying();
    }

    void previous() {
        MediaController c = controller;
        if (c != null) try { c.getTransportControls().skipToPrevious(); } catch (RuntimeException ignored) {}
    }

    void next() {
        MediaController c = controller;
        if (c != null) try { c.getTransportControls().skipToNext(); } catch (RuntimeException ignored) {}
    }

    /** Play/pause: pause what plays, play what's paused. */
    void toggle() {
        MediaController c = controller;
        if (c == null) return;
        try {
            if (playing) c.getTransportControls().pause();
            else c.getTransportControls().play();
        } catch (RuntimeException e) {
            AppLog.w("now playing: " + e.getMessage());
        }
    }

    /**
     * Stopped from the widget: hidden until something plays again. Spotify only pauses, so its song would come back
     * (in the screensaver, which has its own NowPlaying, or after Home was closed): the stopped song is kept in a file
     * every process reads, and dropped once anything plays.
     */
    private boolean dismissed;
    private static final String STOPPED = "now-playing-stopped.txt";

    /** The stop button: the app stops (pausing where it can't), and the widget goes. */
    void stopPlayback() {
        MediaController c = controller;
        if (c == null) return;
        try {
            if ((actions & PlaybackState.ACTION_STOP) != 0) c.getTransportControls().stop();
            else c.getTransportControls().pause();
        } catch (RuntimeException ignored) {
        }
        dismissed = true;
        try {
            java.nio.file.Files.write(stoppedFile().toPath(), (pkg + "\n" + title).getBytes("UTF-8"));
        } catch (Exception e) {
            AppLog.w("now playing: " + e.getMessage());
        }
        listener.onNowPlaying();
    }

    private java.io.File stoppedFile() {
        return new java.io.File(context.getFilesDir(), STOPPED);
    }

    /** The song stopped from the widget (in any process), or "" for none. */
    private String stopped() {
        try {
            return new String(java.nio.file.Files.readAllBytes(stoppedFile().toPath()), "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    boolean visible() {
        if (title.isEmpty() || dismissed) return false;
        return playing || SystemClock.elapsedRealtime() - pausedAt < PAUSED_SHOWN;
    }

    /** 0 to 1, moving on its own while playing. */
    float progress() {
        if (duration <= 0) return 0;
        long pos = position;
        if (playing) pos += (long) ((SystemClock.elapsedRealtime() - positionAt) * speed);
        return Math.max(0f, Math.min(1f, pos / (float) duration));
    }

    @Override
    public void onActiveSessionsChanged(List<MediaController> list) {
        MediaController pick = null;
        if (list != null) {
            // The one playing; else the first that has a song loaded (a paused Spotify).
            for (MediaController m : list) {
                if (isVideo(m.getPackageName())) continue;
                PlaybackState s = m.getPlaybackState();
                if (s != null && playingState(s.getState())) { pick = m; break; }
            }
            if (pick == null) {
                for (MediaController m : list) {
                    if (!isVideo(m.getPackageName()) && m.getMetadata() != null) { pick = m; break; }
                }
            }
        }
        watch(pick);
    }

    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override public void onMetadataChanged(MediaMetadata m) { read(); }
        @Override public void onPlaybackStateChanged(PlaybackState s) { read(); }
        @Override public void onSessionDestroyed() { watch(null); }
    };

    private void watch(MediaController c) {
        if (controller != null) controller.unregisterCallback(callback);
        controller = c;
        if (c != null) c.registerCallback(callback, main);
        read();
    }

    private void read() {
        MediaController c = controller;
        MediaMetadata m = c == null ? null : c.getMetadata();
        PlaybackState s = c == null ? null : c.getPlaybackState();
        String t = "", a = "";
        Bitmap b = null;
        long d = 0;
        if (m != null) {
            t = first(m, MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
            a = first(m, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,
                    MediaMetadata.METADATA_KEY_ALBUM_ARTIST, MediaMetadata.METADATA_KEY_ALBUM);
            b = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (b == null) b = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (b == null) b = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            d = m.getLong(MediaMetadata.METADATA_KEY_DURATION);
        }
        boolean wasPlaying = playing;
        playing = s != null && playingState(s.getState());
        if (wasPlaying && !playing) pausedAt = SystemClock.elapsedRealtime();
        if (!playing && pausedAt == 0) pausedAt = SystemClock.elapsedRealtime();
        if (s != null && playingState(s.getState())) {   // playing again: back on screen, everywhere
            dismissed = false;
            if (stoppedFile().exists()) stoppedFile().delete();
        }
        actions = s == null ? 0 : s.getActions();
        position = s == null ? 0 : s.getPosition();
        positionAt = s == null ? SystemClock.elapsedRealtime() : s.getLastPositionUpdateTime();
        if (positionAt <= 0) positionAt = SystemClock.elapsedRealtime();
        speed = s == null ? 0 : (s.getPlaybackSpeed() == 0 ? 1 : s.getPlaybackSpeed());
        duration = d;
        pkg = c == null ? "" : c.getPackageName();
        if (!t.equals(title) || !a.equals(artist) || b != art) {
            title = t;
            artist = a;
            art = b == null ? null : square(b, 160);
        }
        if (!playing && !dismissed && !title.isEmpty()) dismissed = stopped().equals(pkg + "\n" + title);
        version++;
        listener.onNowPlaying();
    }

    /** Between songs Spotify reports buffering or skipping for a moment: that still counts as playing. */
    private static boolean playingState(int st) {
        switch (st) {
            case PlaybackState.STATE_PLAYING:
            case PlaybackState.STATE_BUFFERING:
            case PlaybackState.STATE_CONNECTING:
            case PlaybackState.STATE_SKIPPING_TO_NEXT:
            case PlaybackState.STATE_SKIPPING_TO_PREVIOUS:
            case PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM:
            case PlaybackState.STATE_FAST_FORWARDING:
            case PlaybackState.STATE_REWINDING:
                return true;
            default:
                return false;
        }
    }

    private static String first(MediaMetadata m, String... keys) {
        for (String k : keys) {
            CharSequence v = m.getText(k);
            if (v != null && v.length() > 0) return v.toString();
        }
        return "";
    }

    /** The cover, center-cropped to a square of `size` px: drawn at that size, it stays sharp and cheap. */
    private static Bitmap square(Bitmap b, int size) {
        int s = Math.min(b.getWidth(), b.getHeight());
        Bitmap crop = Bitmap.createBitmap(b, (b.getWidth() - s) / 2, (b.getHeight() - s) / 2, s, s);
        return s == size ? crop : Bitmap.createScaledBitmap(crop, size, size, true);
    }
}
