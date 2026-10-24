package io.freemytv;

import android.app.Activity;
import android.content.ComponentName;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Diagnostics for a "continue watching" widget (not reachable from the home screen; opened over ADB):
 *   adb shell am start -n io.freemytv/.DiagActivity
 * Lists what each app has in the TV Provider (Watch Next and preview channels) and, live, everything the media
 * sessions publish (YouTube, Spotify...) while something plays. Shown on screen and logged under the tag FreeMyTVDiag.
 */
public class DiagActivity extends Activity implements MediaSessionManager.OnActiveSessionsChangedListener {
    private static final String TAG = "FreeMyTVDiag";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<MediaController> watched = new ArrayList<>();
    private TextView view;
    private String provider = "";
    private MediaSessionManager msm;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        view = new TextView(this);
        view.setTextColor(Color.WHITE);
        view.setTypeface(android.graphics.Typeface.MONOSPACE);
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, 17);
        view.setPadding(48, 32, 48, 32);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF101014);
        scroll.addView(view);
        setContentView(scroll);
        new Thread(() -> {
            String text = "== MEDIA HISTORY ==\n" + history()
                    + "\n== TV PROVIDER: PREVIEW SUMMARY ==\n" + previewSummary()
                    + "\n== TV PROVIDER: WATCH NEXT ==\n" + table(TvContract.WatchNextPrograms.CONTENT_URI, true);
            log(text);
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(getExternalFilesDir(null), "diag.txt"))) {
                out.write(text.getBytes("UTF-8"));
            } catch (java.io.IOException ignored) {
            }
            // "quick": written to the file, then straight back to whatever was on screen.
            if (getIntent().getBooleanExtra("quick", false)) main.post(this::finish);
            main.post(() -> {
                provider = text;
                render();
            });
        }).start();
    }

    @Override protected void onResume() {
        super.onResume();
        try {
            msm = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
            ComponentName me = new ComponentName(this, NowPlayingListener.class);
            msm.addOnActiveSessionsChangedListener(this, me, main);
            onActiveSessionsChanged(msm.getActiveSessions(me));
        } catch (RuntimeException e) {
            log("media sessions unavailable: " + e);
        }
    }

    @Override protected void onPause() {
        if (msm != null) msm.removeOnActiveSessionsChangedListener(this);
        for (MediaController c : watched) c.unregisterCallback(callback);
        watched.clear();
        super.onPause();
    }

    /** Every row with every column that matters; the query takes no selection or order (a SecurityException otherwise). */
    private String table(Uri uri, boolean watchNext) {
        StringBuilder sb = new StringBuilder();
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c == null) return "  (null cursor: no provider, or no permission)\n";
            sb.append("  rows: ").append(c.getCount()).append('\n');
            String[] cols = watchNext
                    ? new String[] {"package_name", "title", "episode_title", "season_display_number", "episode_display_number",
                            "watch_next_type", "last_engagement_time_utc_millis", "last_playback_position_millis",
                            "duration_millis", "poster_art_uri", "thumbnail_uri", "intent_uri", "internal_provider_id",
                            "content_id", "searchable", "type"}
                    : new String[] {"package_name", "channel_id", "title", "type", "poster_art_uri", "intent_uri",
                            "last_playback_position_millis", "duration_millis", "searchable"};
            while (c.moveToNext()) {
                sb.append("  ---\n");
                for (String col : cols) {
                    int i = c.getColumnIndex(col);
                    if (i < 0) continue;
                    String v = c.isNull(i) ? null : c.getString(i);
                    if (v == null) continue;
                    if (col.endsWith("_uri") && v.length() > 110) v = v.substring(0, 110) + "…";
                    if (col.equals("last_engagement_time_utc_millis")) v += "  (" + time(Long.parseLong(v)) + ")";
                    sb.append("  ").append(col).append(" = ").append(v).append('\n');
                }
            }
        } catch (SecurityException e) {
            sb.append("  SecurityException: ").append(e.getMessage()).append('\n');
        } catch (RuntimeException e) {
            sb.append("  error: ").append(e).append('\n');
        }
        return sb.toString();
    }

    /** MediaHistory's file and its pictures (the home process writes them; same app, same files). */
    private String history() {
        StringBuilder sb = new StringBuilder();
        java.io.File f = new java.io.File(getFilesDir(), "media-history.json");
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int off = 0, n;
            while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
            sb.append("  ").append(new String(b, "UTF-8")).append('\n');
        } catch (java.io.IOException e) {
            sb.append("  ").append(e).append('\n');
        }
        java.io.File[] pics = new java.io.File(getFilesDir(), "media-history").listFiles();
        if (pics != null) for (java.io.File p : pics) sb.append("  picture ").append(p.getName()).append(' ').append(p.length()).append('\n');
        return sb.toString();
    }

    /** Per app: its channels (rows) by name, how many programs each, and the first titles. */
    private String previewSummary() {
        StringBuilder sb = new StringBuilder();
        java.util.Map<Long, String> channels = new java.util.HashMap<>();
        try (Cursor c = getContentResolver().query(TvContract.Channels.CONTENT_URI, null, null, null, null)) {
            if (c != null) {
                sb.append("  channels: ").append(c.getCount()).append('\n');
                while (c.moveToNext()) {
                    long id = c.getLong(c.getColumnIndex("_id"));
                    String pkg = c.getString(c.getColumnIndex("package_name"));
                    String name = c.getString(c.getColumnIndex("display_name"));
                    String type = c.getString(c.getColumnIndex("type"));
                    int b = c.getColumnIndex("browsable");
                    channels.put(id, pkg + " / \"" + name + "\" (" + type + ", browsable=" + (b >= 0 ? c.getInt(b) : -1) + ")");
                }
            }
        } catch (RuntimeException e) {
            sb.append("  channels: ").append(e).append('\n');
        }
        java.util.Map<String, List<String>> byChannel = new java.util.TreeMap<>();
        try (Cursor c = getContentResolver().query(TvContract.PreviewPrograms.CONTENT_URI, null, null, null, null)) {
            if (c == null) return sb.append("  (null cursor)\n").toString();
            sb.append("  programs: ").append(c.getCount()).append('\n');
            while (c.moveToNext()) {
                long ch = c.getLong(c.getColumnIndex("channel_id"));
                String key = channels.containsKey(ch) ? channels.get(ch)
                        : c.getString(c.getColumnIndex("package_name")) + " / channel " + ch;
                List<String> l = byChannel.get(key);
                if (l == null) byChannel.put(key, l = new ArrayList<>());
                int t = c.getColumnIndex("type");
                l.add(c.getString(c.getColumnIndex("title")) + (t >= 0 && !c.isNull(t) ? " [t" + c.getInt(t) + "]" : ""));
            }
        } catch (RuntimeException e) {
            sb.append("  error: ").append(e).append('\n');
        }
        for (java.util.Map.Entry<String, List<String>> e : byChannel.entrySet()) {
            sb.append("  ").append(e.getKey()).append(": ").append(e.getValue().size()).append(" programs\n");
            for (int i = 0; i < Math.min(6, e.getValue().size()); i++) sb.append("     · ").append(e.getValue().get(i)).append('\n');
        }
        return sb.toString();
    }

    @Override public void onActiveSessionsChanged(List<MediaController> list) {
        for (MediaController c : watched) c.unregisterCallback(callback);
        watched.clear();
        if (list != null) {
            for (MediaController c : list) {
                c.registerCallback(callback, main);
                watched.add(c);
            }
        }
        render();
    }

    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override public void onMetadataChanged(MediaMetadata m) { render(); }
        @Override public void onPlaybackStateChanged(PlaybackState s) { render(); }
        @Override public void onExtrasChanged(Bundle extras) { render(); }
    };

    private void render() {
        StringBuilder sb = new StringBuilder("== MEDIA SESSIONS (live) ==  " + time(System.currentTimeMillis()) + "\n");
        if (watched.isEmpty()) sb.append("  (none: play something on YouTube or Spotify)\n");
        for (MediaController c : watched) {
            sb.append("  --- ").append(c.getPackageName()).append('\n');
            PlaybackState s = c.getPlaybackState();
            if (s != null) {
                sb.append("  state=").append(s.getState()).append(" position=").append(s.getPosition())
                        .append(" actions=").append(s.getActions()).append('\n');
                bundle(sb, "  state.extras", s.getExtras());
            }
            MediaMetadata m = c.getMetadata();
            if (m != null) {
                for (String key : m.keySet()) {
                    Object v;
                    Bitmap b = m.getBitmap(key);
                    if (b != null) v = "[bitmap " + b.getWidth() + "x" + b.getHeight() + "]";
                    else {
                        CharSequence t = m.getText(key);
                        v = t != null ? t : m.getLong(key);
                    }
                    sb.append("  ").append(key.replace("android.media.metadata.", "")).append(" = ").append(v).append('\n');
                }
                if (m.getDescription() != null) {
                    sb.append("  description.mediaId = ").append(m.getDescription().getMediaId()).append('\n');
                    sb.append("  description.mediaUri = ").append(m.getDescription().getMediaUri()).append('\n');
                    sb.append("  description.iconUri = ").append(m.getDescription().getIconUri()).append('\n');
                    bundle(sb, "  description.extras", m.getDescription().getExtras());
                }
            }
            bundle(sb, "  controller.extras", c.getExtras());
            if (c.getSessionActivity() != null) sb.append("  sessionActivity = ").append(c.getSessionActivity()).append('\n');
        }
        String live = sb.toString();
        log(live);
        view.setText(live + "\n" + provider);
    }

    private static void bundle(StringBuilder sb, String name, Bundle b) {
        if (b == null || b.isEmpty()) return;
        for (String key : b.keySet()) {
            Object v;
            try {
                v = b.get(key);
            } catch (RuntimeException e) {
                v = "?";
            }
            sb.append(name).append('.').append(key).append(" = ").append(v).append('\n');
        }
    }

    private static String time(long ms) {
        return new SimpleDateFormat("dd/MM HH:mm:ss", Locale.US).format(new Date(ms));
    }

    private static void log(String text) {
        for (String line : text.split("\n")) android.util.Log.i(TAG, line);
    }
}
