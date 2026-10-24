package io.freemytv;

import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * What the floating "Continuar viendo" widget shows for the focused app, from the TV Provider (needs READ_TV_LISTINGS,
 * granted over ADB) and MediaHistory:
 *  - Watch Next ("Continuar viendo", with progress): Disney+ and Netflix publish it; Prime Video and HBO Max don't;
 *  - YouTube and Spotify: what played on this TV (MediaHistory);
 *  - otherwise the app's preview channel (the rows of Android TV's old home: Prime Video's featured titles, YouTube's
 *    trending videos), labelled as such, so it isn't taken for something to resume.
 * The provider takes no selection or order (a SecurityException otherwise): the whole table is read and filtered here.
 * Read on a background thread, kept per app, and read again when the provider changes.
 */
final class WatchNext {
    static final int ROW_WIDE = 1, ROW_SQUARE = 2, ROW_ROUND = 3;
    static final String YOUTUBE = "com.google.android.youtube.tv", SPOTIFY = "com.spotify.tv.android",
            PRIME = "com.amazon.amazonvideo.livingroom";
    /** Rows that are never what someone watched: the TCL browser's ads. */
    private static final Set<String> IGNORED = new HashSet<>(Collections.singletonList("com.tcl.browser"));
    /** Almost finished (more than 95% seen): not offered again. */
    private static final float DONE = 0.95f;

    static final class Entry {
        String pkg, title = "", sub = "", image, uri;
        long position, duration, at;
        /** Set on the UI thread once the picture is loaded at the size it's drawn. */
        Bitmap bitmap;
    }

    static final class Content {
        String pkg, header;
        int variant;
        final List<Entry> items = new ArrayList<>();

        String key() {
            StringBuilder s = new StringBuilder(pkg).append('|').append(header).append('|').append(variant);
            for (Entry e : items) s.append('|').append(e.title).append('/').append(e.sub).append('/').append(e.position / 60_000);
            return s.toString();
        }
    }

    interface Listener { void onWatchNext(); }

    private static WatchNext instance;
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();
    private static final ExecutorService IMAGES = Executors.newSingleThreadExecutor();

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Listener listener;
    private boolean observing;
    private Map<String, List<Entry>> watchNext = new HashMap<>();
    /** Per app: its preview channel's name and programs (the preferred channel only). */
    private Map<String, List<Entry>> preview = new HashMap<>();
    private Map<String, String> channelName = new HashMap<>();
    private final LruCache<String, Bitmap> images = new LruCache<String, Bitmap>(5 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap b) { return b.getByteCount(); }
    };
    private final Set<String> loading = new HashSet<>();
    /**
     * Spotify: the top three of Top 50 Argentina, read from Spotify's public player page for that playlist (its list
     * in order, no account), with each song's cover from Spotify's open oEmbed. Read again every few hours.
     */
    private static final String TOP_ARGENTINA = "37i9dQZEVXbMMy2roB9myp";
    private static final long TOP_EVERY_MS = 4 * 3600_000L;
    private long spotifyAt;
    private List<Entry> spotifyPicks;

    static WatchNext get(Context c) {
        if (instance == null) instance = new WatchNext(c.getApplicationContext());
        return instance;
    }

    private WatchNext(Context c) {
        context = c;
    }

    private final Runnable reload = this::load;

    private final ContentObserver observer = new ContentObserver(main) {
        @Override public void onChange(boolean self) {
            main.removeCallbacks(reload);
            main.postDelayed(reload, 1500);   // apps write many rows in a burst
        }
    };

    /** While Home is on screen: read now and on every change. */
    void start(Listener l) {
        listener = l;
        if (!observing) {
            try {
                context.getContentResolver().registerContentObserver(TvContract.WatchNextPrograms.CONTENT_URI, true, observer);
                context.getContentResolver().registerContentObserver(TvContract.PreviewPrograms.CONTENT_URI, true, observer);
                observing = true;
            } catch (RuntimeException e) {
                AppLog.w("watch next: " + e.getMessage());
            }
        }
        load();
    }

    void stop() {
        listener = null;
        main.removeCallbacks(reload);
        if (observing) context.getContentResolver().unregisterContentObserver(observer);
        observing = false;
    }

    private void load() {
        LOADER.execute(() -> {
            Map<String, List<Entry>> wn = readWatchNext();
            Map<String, String> names = new HashMap<>();
            Map<String, List<Entry>> pv = readPreview(names);
            boolean fresh = spotifyPicks != null && !spotifyPicks.isEmpty() && System.currentTimeMillis() - spotifyAt < TOP_EVERY_MS;
            final List<Entry> picks = fresh ? spotifyPicks : readTopArgentina();
            if (!fresh && !picks.isEmpty()) spotifyAt = System.currentTimeMillis();
            main.post(() -> {
                if (!picks.isEmpty() || spotifyPicks == null) spotifyPicks = picks;
                watchNext = wn;
                preview = pv;
                channelName = names;
                if (listener != null) listener.onWatchNext();
            });
        });
    }

    private static List<Entry> readTopArgentina() {
        List<Entry> out = new ArrayList<>();
        try {
            String page = new String(get("https://open.spotify.com/embed/playlist/" + TOP_ARGENTINA), "UTF-8");
            int at = page.indexOf("<script id=\"__NEXT_DATA__\" type=\"application/json\">");
            if (at < 0) return out;
            at = page.indexOf('>', at) + 1;
            org.json.JSONObject data = new org.json.JSONObject(page.substring(at, page.indexOf("</script>", at)));
            org.json.JSONArray tracks = data.getJSONObject("props").getJSONObject("pageProps").getJSONObject("state")
                    .getJSONObject("data").getJSONObject("entity").getJSONArray("trackList");
            for (int i = 0; i < Math.min(3, tracks.length()); i++) {
                org.json.JSONObject t = tracks.getJSONObject(i);
                Entry e = new Entry();
                e.pkg = SPOTIFY;
                e.title = (i + 1) + ". " + t.optString("title");
                e.sub = t.optString("subtitle").replace('\u00a0', ' ');
                e.uri = t.optString("uri", null);
                if (e.uri != null && e.uri.startsWith("spotify:track:")) {
                    try {
                        org.json.JSONObject o = new org.json.JSONObject(new String(get(
                                "https://open.spotify.com/oembed?url=https://open.spotify.com/track/" + e.uri.substring(14)), "UTF-8"));
                        e.image = o.optString("thumbnail_url", null);
                    } catch (Exception ex) {
                        AppLog.w("spotify cover: " + ex.getMessage());
                    }
                }
                out.add(e);
            }
        } catch (Exception ex) {
            AppLog.w("top argentina: " + ex.getMessage());
        }
        return out;
    }

    private static byte[] get(String url) throws java.io.IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(10000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 11) TVdeMati/1.0");
        try (InputStream in = conn.getInputStream()) {
            return readAll(in);
        } finally {
            conn.disconnect();
        }
    }

    private Map<String, List<Entry>> readWatchNext() {
        Map<String, List<Entry>> out = new HashMap<>();
        try (Cursor c = context.getContentResolver().query(TvContract.WatchNextPrograms.CONTENT_URI, null, null, null, null)) {
            if (c == null) return out;
            while (c.moveToNext()) {
                String pkg = str(c, "package_name");
                if (pkg == null || IGNORED.contains(pkg)) continue;
                if (num(c, "watch_next_type") == TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_WATCHLIST) continue;
                Entry e = new Entry();
                e.pkg = pkg;
                e.title = nonNull(str(c, "title"));
                e.position = num(c, "last_playback_position_millis");
                e.duration = num(c, "duration_millis");
                e.at = num(c, "last_engagement_time_utc_millis");
                if (e.title.isEmpty() || (e.duration > 0 && e.position > DONE * e.duration)) continue;
                String season = str(c, "season_display_number"), episode = str(c, "episode_display_number");
                String name = nonNull(str(c, "episode_title"));
                StringBuilder sub = new StringBuilder();
                if (season != null && !season.isEmpty()) sub.append('T').append(season);
                if (episode != null && !episode.isEmpty()) sub.append(sub.length() > 0 ? " · " : "").append('E').append(episode);
                if (!name.isEmpty()) sub.append(sub.length() > 0 ? " · " : "").append(name);
                e.sub = sub.toString();
                String thumb = str(c, "thumbnail_uri");
                e.image = thumb != null ? thumb : str(c, "poster_art_uri");
                e.uri = str(c, "intent_uri");
                List<Entry> l = out.get(pkg);
                if (l == null) out.put(pkg, l = new ArrayList<>());
                l.add(e);
            }
        } catch (RuntimeException e) {
            AppLog.w("watch next: " + e.getMessage());   // no permission: the widget stays hidden
        }
        for (List<Entry> l : out.values()) Collections.sort(l, (a, b) -> Long.compare(b.at, a.at));
        return out;
    }

    private Map<String, List<Entry>> readPreview(Map<String, String> names) {
        Map<Long, String[]> channels = new HashMap<>();   // id -> package, name
        try (Cursor c = context.getContentResolver().query(TvContract.Channels.CONTENT_URI, null, null, null, null)) {
            if (c != null) {
                while (c.moveToNext()) channels.put(num(c, "_id"), new String[] {str(c, "package_name"), nonNull(str(c, "display_name"))});
            }
        } catch (RuntimeException e) {
            AppLog.w("preview channels: " + e.getMessage());
        }
        Map<Long, List<Entry>> byChannel = new HashMap<>();
        try (Cursor c = context.getContentResolver().query(TvContract.PreviewPrograms.CONTENT_URI, null, null, null, null)) {
            if (c == null) return new HashMap<>();
            while (c.moveToNext()) {
                String pkg = str(c, "package_name");
                if (pkg == null || IGNORED.contains(pkg)) continue;
                Entry e = new Entry();
                e.pkg = pkg;
                e.title = nonNull(str(c, "title")).trim();
                if (e.title.isEmpty()) continue;
                String poster = str(c, "poster_art_uri");
                e.image = poster != null ? poster : str(c, "thumbnail_uri");
                e.uri = str(c, "intent_uri");
                long type = num(c, "type");
                // Music (tracks, albums, artists, playlists, stations) has square art.
                e.duration = type >= TvContract.PreviewPrograms.TYPE_TRACK && type <= TvContract.PreviewPrograms.TYPE_STATION ? -1 : 0;
                long ch = num(c, "channel_id");
                List<Entry> l = byChannel.get(ch);
                if (l == null) byChannel.put(ch, l = new ArrayList<>());
                l.add(e);
            }
        } catch (RuntimeException e) {
            AppLog.w("preview programs: " + e.getMessage());
        }
        // One channel per app: trending first (YouTube's recommendations follow whoever's account it is), else the
        // fullest one.
        Map<String, List<Entry>> out = new HashMap<>();
        Map<String, Integer> score = new HashMap<>();
        for (Map.Entry<Long, List<Entry>> e : byChannel.entrySet()) {
            List<Entry> l = e.getValue();
            String pkg = l.get(0).pkg;
            String[] ch = channels.get(e.getKey());
            String name = ch == null ? "" : ch[1];
            String low = name.toLowerCase(Locale.ROOT);
            int s = l.size() + (low.contains("tendencia") || low.contains("trending") ? 1000 : 0);
            Integer best = score.get(pkg);
            if (best != null && best >= s) continue;
            score.put(pkg, s);
            out.put(pkg, l);
            names.put(pkg, name);
        }
        return out;
    }

    /** What the widget shows for this app (label: the app's name), or null for nothing. */
    Content content(String pkg, String label) {
        if (pkg == null) return null;
        Content c = new Content();
        c.pkg = pkg;
        List<Entry> wn = watchNext.get(pkg);
        if (wn != null && !wn.isEmpty()) {   // the latest three to resume
            c.variant = ROW_WIDE;
            c.header = "CONTINUAR VIENDO";
            for (int i = 0; i < Math.min(3, wn.size()); i++) c.items.add(wn.get(i));
            return c;
        }
        if (pkg.equals(SPOTIFY)) {   // always the top three in Argentina
            if (spotifyPicks == null || spotifyPicks.isEmpty()) return null;
            c.variant = ROW_SQUARE;
            c.header = "TOP 3 ARGENTINA";
            c.items.addAll(spotifyPicks);
            return c;
        }
        if (pkg.equals(YOUTUBE)) {
            List<Entry> h = MediaHistory.get(context).recent(pkg, 3);
            if (!h.isEmpty()) {
                c.variant = ROW_WIDE;
                c.items.addAll(h);
                // Fewer than three seen here yet: trending videos fill the rest.
                List<Entry> trending = preview.get(pkg);
                if (trending != null) for (Entry e : trending) if (c.items.size() < 3) c.items.add(e);
                c.header = c.items.size() > h.size() ? "VISTOS Y TENDENCIAS" : "VISTOS RECIENTEMENTE";
                return c;
            }
        }
        if (pkg.equals(PRIME)) {   // Prime writes no Watch Next: what played here (its cards open the app), then featured
            List<Entry> h = MediaHistory.get(context).recent(pkg, 3);
            if (!h.isEmpty()) {
                c.variant = ROW_WIDE;
                c.items.addAll(h);
                List<Entry> featured = preview.get(pkg);
                if (featured != null) for (Entry e : featured) if (c.items.size() < 3) c.items.add(e);
                c.header = c.items.size() > h.size() ? "SEGUIR VIENDO Y DESTACADOS" : "SEGUIR VIENDO";
                return c;
            }
        }
        List<Entry> pv = preview.get(pkg);
        if (pv == null || pv.isEmpty()) return null;
        String name = channelName.get(pkg);
        boolean generic = name == null || name.isEmpty() || name.equalsIgnoreCase(label);
        c.header = (generic ? "Destacados en " + label : name).toUpperCase(Locale.getDefault());
        c.variant = pv.get(0).duration < 0 ? ROW_SQUARE : ROW_WIDE;
        for (int i = 0; i < Math.min(3, pv.size()); i++) c.items.add(pv.get(i));
        return c;
    }

    /**
     * The entry's picture at w × h px (centre-cropped), from memory; or null, and it's fetched (https, content:// or a
     * file) and `done` runs on the UI thread once it's there.
     */
    Bitmap image(Entry e, int w, int h, Runnable done) {
        if (e.image == null || w <= 0 || h <= 0) return null;
        final String key = e.image + '@' + w + 'x' + h;
        Bitmap b = images.get(key);
        if (b != null || !loading.add(key)) return b;
        final String src = e.image;
        IMAGES.execute(() -> {
            Bitmap out = fetch(src, w, h);
            main.post(() -> {
                loading.remove(key);
                if (out == null) return;
                images.put(key, out);
                done.run();
            });
        });
        return null;
    }

    void trim() {
        images.evictAll();
    }

    private Bitmap fetch(String src, int w, int h) {
        try {
            byte[] bytes;
            if (src.startsWith("http://") || src.startsWith("https://")) {
                HttpURLConnection conn = (HttpURLConnection) new URL(src).openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(10000);
                try (InputStream in = conn.getInputStream()) {
                    bytes = readAll(in);
                } finally {
                    conn.disconnect();
                }
            } else if (src.startsWith("content://") || src.startsWith("android.resource://")) {
                try (InputStream in = context.getContentResolver().openInputStream(Uri.parse(src))) {
                    if (in == null) return null;
                    bytes = readAll(in);
                }
            } else if (src.startsWith("/")) {
                try (InputStream in = new java.io.FileInputStream(src)) {
                    bytes = readAll(in);
                }
            } else {
                return null;
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            int sample = 1;
            while (o.outWidth / (sample * 2) >= w && o.outHeight / (sample * 2) >= h) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            Bitmap b = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
            return b == null ? null : crop(b, w, h);
        } catch (Exception e) {
            AppLog.w("watch next image: " + e.getMessage());
            return null;
        }
    }

    /** Centre-cropped to the aspect of w × h, then scaled to exactly that: drawn pixel for pixel. */
    static Bitmap crop(Bitmap b, int w, int h) {
        float want = w / (float) h, have = b.getWidth() / (float) b.getHeight();
        int cw = b.getWidth(), ch = b.getHeight();
        if (have > want) cw = Math.round(ch * want);
        else ch = Math.round(cw / want);
        Bitmap c = Bitmap.createBitmap(b, (b.getWidth() - cw) / 2, (b.getHeight() - ch) / 2, cw, ch);
        return c.getWidth() == w && c.getHeight() == h ? c : Bitmap.createScaledBitmap(c, w, h, true);
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > 8 * 1024 * 1024) throw new java.io.IOException("image too big");
        }
        return out.toByteArray();
    }

    private static String str(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        return i < 0 || c.isNull(i) ? null : c.getString(i);
    }

    private static long num(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        return i < 0 || c.isNull(i) ? 0 : c.getLong(i);
    }

    private static String nonNull(String s) {
        return s == null ? "" : s;
    }
}
