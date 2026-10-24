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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The launcher's own history of what played on this TV in YouTube and Spotify: YouTube writes no Watch Next and its
 * history isn't open to other apps. Kept from their media sessions by NowPlayingListener's process (it stays alive
 * while another app is on screen): something that plays 10 s is saved, with a small copy of its picture.
 * YouTube publishes title, channel and a 16:9 picture, but no video id: its cards open YouTube. Spotify publishes the
 * artist's address (spotify:artist:…), which opens the artist.
 * Kept in files/media-history.json (20 per app at most), pictures in files/media-history/.
 */
final class MediaHistory implements MediaSessionManager.OnActiveSessionsChangedListener {
    private static final long PLAYED_MS = 10_000;
    private static final int KEEP = 20;
    private static final String[] APPS = {WatchNext.YOUTUBE, WatchNext.SPOTIFY, WatchNext.PRIME};

    private static MediaHistory instance;
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final File file, pictures;
    private final Map<String, List<Item>> items = new HashMap<>();
    private final Map<MediaController, MediaController.Callback> watched = new HashMap<>();
    private MediaSessionManager msm;
    private Runnable listener;
    /** What's playing now and waiting its 10 s: its controller and the key it had. */
    private MediaController pendingFrom;
    private String pendingKey;

    private static final class Item {
        String key, title, sub, uri, picture;
        long at;
    }

    static MediaHistory get(Context c) {
        if (instance == null) instance = new MediaHistory(c.getApplicationContext());
        return instance;
    }

    private MediaHistory(Context c) {
        context = c;
        file = new File(c.getFilesDir(), "media-history.json");
        pictures = new File(c.getFilesDir(), "media-history");
        read();
    }

    /** Home is told when something new is saved. */
    void setListener(Runnable r) {
        listener = r;
    }

    void start() {
        if (msm != null) return;
        try {
            msm = (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
            ComponentName me = new ComponentName(context, NowPlayingListener.class);
            msm.addOnActiveSessionsChangedListener(this, me, main);
            onActiveSessionsChanged(msm.getActiveSessions(me));
            AppLog.w("media history: watching");
        } catch (RuntimeException e) {
            AppLog.w("media history: " + e.getMessage());
            msm = null;
        }
    }

    void stop() {
        if (msm != null) msm.removeOnActiveSessionsChangedListener(this);
        msm = null;
        for (Map.Entry<MediaController, MediaController.Callback> e : watched.entrySet()) e.getKey().unregisterCallback(e.getValue());
        watched.clear();
        main.removeCallbacks(commit);
    }

    @Override public void onActiveSessionsChanged(List<MediaController> list) {
        for (Map.Entry<MediaController, MediaController.Callback> e : watched.entrySet()) e.getKey().unregisterCallback(e.getValue());
        watched.clear();
        if (list == null) return;
        for (final MediaController m : list) {
            if (!isTracked(m.getPackageName())) continue;
            MediaController.Callback cb = new MediaController.Callback() {
                @Override public void onMetadataChanged(MediaMetadata md) { check(m); }
                @Override public void onPlaybackStateChanged(android.media.session.PlaybackState s) { check(m); }
            };
            m.registerCallback(cb, main);
            watched.put(m, cb);
            check(m);
        }
    }

    private static boolean isTracked(String pkg) {
        for (String a : APPS) if (a.equals(pkg)) return true;
        return false;
    }

    /** Playing something new: it's saved if it's still the same thing, still playing, 10 s later. */
    private void check(MediaController m) {
        PlaybackState s = m.getPlaybackState();
        boolean playing = s != null && s.getState() == PlaybackState.STATE_PLAYING;
        String key = keyOf(m);
        if (m == pendingFrom && (!playing || key == null || !key.equals(pendingKey))) {
            main.removeCallbacks(commit);
            pendingFrom = null;
        }
        if (!playing || key == null || (m == pendingFrom && key.equals(pendingKey))) return;
        List<Item> l = items.get(m.getPackageName());
        if (l != null && !l.isEmpty() && l.get(0).key.equals(key)) {   // already the latest: its picture, if it lacked one
            Item top = l.get(0);
            if ((top.picture == null || (top.picture.startsWith("/") && !new File(top.picture).exists()))
                    && savePicture(top, m.getMetadata(), WatchNext.SPOTIFY.equals(m.getPackageName()))) write();
            return;
        }
        pendingFrom = m;
        pendingKey = key;
        main.removeCallbacks(commit);
        main.postDelayed(commit, PLAYED_MS);
    }

    private final Runnable commit = () -> {
        MediaController m = pendingFrom;
        pendingFrom = null;
        if (m == null || pendingKey == null || !pendingKey.equals(keyOf(m))) return;
        save(m);
    };

    /** YouTube: the video (by title); Spotify: the artist. */
    private static String keyOf(MediaController m) {
        MediaMetadata md = m.getMetadata();
        if (md == null) return null;
        String title = text(md, MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        // Prime Video titles everything "PrimeVideo" (seen on a TCL, 2026): the app's name, not what's playing.
        if (WatchNext.PRIME.equals(m.getPackageName()) && title.replace(" ", "").equalsIgnoreCase("PrimeVideo")) return null;
        if (title.isEmpty()) return null;
        if (!WatchNext.SPOTIFY.equals(m.getPackageName())) return title;
        String uri = text(md, "com.spotify.music.extra.ARTIST_URI");
        return !uri.isEmpty() ? uri : artist(md);
    }

    private static String artist(MediaMetadata md) {
        String a = text(md, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,
                MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
        int comma = a.indexOf(", ");   // "Kapanga, La Vela Puerca": the first one
        return comma > 0 ? a.substring(0, comma) : a;
    }

    private void save(MediaController m) {
        MediaMetadata md = m.getMetadata();
        if (md == null) return;
        String pkg = m.getPackageName();
        boolean music = WatchNext.SPOTIFY.equals(pkg);
        Item it = new Item();
        it.key = keyOf(m);
        String title = text(md, MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        if (music) {
            it.title = artist(md);
            it.sub = title;
            String uri = text(md, "com.spotify.music.extra.ARTIST_URI");
            it.uri = uri.startsWith("spotify:") ? uri : null;
        } else {
            it.title = title;
            it.sub = text(md, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
        }
        if (it.title.isEmpty()) return;
        it.at = System.currentTimeMillis();
        boolean pictured = savePicture(it, md, music);
        List<Item> l = items.get(pkg);
        if (l == null) items.put(pkg, l = new ArrayList<>());
        for (int i = l.size() - 1; i >= 0; i--) if (l.get(i).key.equals(it.key)) l.remove(i);
        l.add(0, it);
        while (l.size() > KEEP) l.remove(l.size() - 1);
        write();
        AppLog.w("media history: saved one from " + pkg + (it.picture == null ? " (no picture)" : ""));
        if (!pictured) changed();
    }

    /**
     * A small copy of the session's picture on disk (the app may send it a moment after the title); without a
     * bitmap, its https address. True if a file is being written (`changed` follows once it's there).
     */
    private boolean savePicture(Item it, MediaMetadata md, boolean music) {
        if (md == null) return false;
        Bitmap art = md.getBitmap(music ? MediaMetadata.METADATA_KEY_ALBUM_ART : MediaMetadata.METADATA_KEY_ART);
        if (art == null) art = md.getBitmap(music ? MediaMetadata.METADATA_KEY_ART : MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        if (art == null) {
            String uri = text(md, MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
                    MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI);
            if (uri.startsWith("https://") || uri.startsWith("http://")) it.picture = uri;
            return false;
        }
        final Bitmap small = music ? WatchNext.crop(art, 200, 200) : WatchNext.crop(art, 320, 180);
        final File out = new File(pictures, Integer.toHexString(it.key.hashCode()) + "-" + System.currentTimeMillis() + ".jpg");
        it.picture = out.getAbsolutePath();
        new Thread(() -> {
            pictures.mkdirs();
            try (FileOutputStream o = new FileOutputStream(out)) {
                small.compress(Bitmap.CompressFormat.JPEG, 88, o);
            } catch (java.io.IOException e) {
                AppLog.w("media history picture: " + e.getMessage());
            }
            main.post(this::changed);
        }, "media-history").start();
        return true;
    }

    private void changed() {
        if (listener != null) listener.run();
    }

    /** The last n different ones, newest first, as the widget's entries. */
    List<WatchNext.Entry> recent(String pkg, int n) {
        List<WatchNext.Entry> out = new ArrayList<>();
        List<Item> l = items.get(pkg);
        if (l == null) return out;
        for (Item it : l) {
            if (out.size() >= n) break;
            WatchNext.Entry e = new WatchNext.Entry();
            e.pkg = pkg;
            e.title = it.title;
            e.sub = it.sub == null ? "" : it.sub;
            e.uri = it.uri;
            e.image = it.picture;
            e.at = it.at;
            out.add(e);
        }
        return out;
    }

    private void read() {
        if (!file.exists()) return;
        try {
            byte[] b = new byte[(int) file.length()];
            try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
                int off = 0, n;
                while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
            }
            JSONObject root = new JSONObject(new String(b, "UTF-8"));
            for (String pkg : APPS) {
                JSONArray a = root.optJSONArray(pkg);
                if (a == null) continue;
                List<Item> l = new ArrayList<>();
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.getJSONObject(i);
                    Item it = new Item();
                    it.key = o.getString("key");
                    it.title = o.getString("title");
                    it.sub = o.optString("sub", "");
                    it.uri = o.has("uri") ? o.getString("uri") : null;
                    it.picture = o.has("picture") ? o.getString("picture") : null;
                    it.at = o.optLong("at");
                    l.add(it);
                }
                items.put(pkg, l);
            }
        } catch (Exception e) {
            AppLog.w("media history: " + e.getMessage());
        }
    }

    private void write() {
        try {
            JSONObject root = new JSONObject();
            Set<String> keep = new HashSet<>();
            for (Map.Entry<String, List<Item>> e : items.entrySet()) {
                JSONArray a = new JSONArray();
                for (Item it : e.getValue()) {
                    JSONObject o = new JSONObject().put("key", it.key).put("title", it.title).put("sub", it.sub).put("at", it.at);
                    if (it.uri != null) o.put("uri", it.uri);
                    if (it.picture != null) {
                        o.put("picture", it.picture);
                        keep.add(new File(it.picture).getName());
                    }
                    a.put(o);
                }
                root.put(e.getKey(), a);
            }
            final String json = root.toString();
            new Thread(() -> {
                try (FileOutputStream o = new FileOutputStream(file)) {
                    o.write(json.getBytes("UTF-8"));
                } catch (java.io.IOException ex) {
                    AppLog.w("media history: " + ex.getMessage());
                }
                // Pictures of entries that fell off the end.
                File[] all = pictures.listFiles();
                if (all != null) for (File f : all) if (!keep.contains(f.getName()) && System.currentTimeMillis() - f.lastModified() > 60_000) f.delete();
            }, "media-history").start();
        } catch (org.json.JSONException e) {
            AppLog.w("media history: " + e.getMessage());
        }
    }

    private static String text(MediaMetadata m, String... keys) {
        for (String k : keys) {
            CharSequence v = m.getText(k);
            if (v == null) {
                try {
                    String s = m.getString(k);
                    if (s != null) v = s;
                } catch (RuntimeException ignored) {
                }
            }
            if (v != null && v.length() > 0) return v.toString();
        }
        return "";
    }
}
