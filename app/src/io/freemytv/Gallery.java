package io.freemytv;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * Landscapes and paintings for the home screen's background and the screensaver: Wikimedia Commons' featured pictures
 * (freely licensed, no key), only the wide ones, at the UI's width. The lists are kept a week; the images are
 * downloaded when first shown and the last few dozen kept. Network calls: background threads only.
 */
final class Gallery {
    private Gallery() {}

    static final String LANDSCAPES = "landscapes", ART = "art", FAVORITES = "favorites";

    static final class Item {
        String url = "", title = "", author = "";
    }

    private static final String API = "https://commons.wikimedia.org/w/api.php";
    /** Wikimedia asks every client to say who it is. */
    private static final String AGENT = "TVdeMati/1.0 (Android TV home screen; Free My TV fork)";
    private static final long LIST_EVERY = 7 * 24 * 3600_000L;
    private static final int KEEP_IMAGES = 40;

    private static final String[] LANDSCAPE_CATEGORIES = {"Featured pictures of landscapes", "Featured pictures of mountains"};
    private static final String[] ART_CATEGORIES = {
        "Featured pictures of paintings from France", "Featured pictures of paintings from the Netherlands",
        "Featured pictures of paintings from Japan", "Featured pictures of paintings from Norway",
        "Featured pictures of paintings from Spain", "Featured pictures of paintings from Italy",
        "Featured pictures of paintings from the United States", "Featured pictures of paintings from Russia"};

    /** The list (saved, or fetched when older than a week), shuffled once per fetch so every TV sees its own order. */
    static List<Item> list(Context c, String kind) {
        if (kind.equals(FAVORITES)) return favorites(c);
        File f = new File(c.getFilesDir(), "gallery-" + kind + ".json");
        List<Item> saved = read(f);
        if (!saved.isEmpty() && System.currentTimeMillis() - f.lastModified() < LIST_EVERY) return saved;
        try {
            List<Item> fresh = fetch(kind.equals(ART) ? ART_CATEGORIES : LANDSCAPE_CATEGORIES, 1.3f);
            if (!fresh.isEmpty()) {
                Collections.shuffle(fresh);
                write(f, fresh);
                return fresh;
            }
        } catch (Exception e) {
            AppLog.w("gallery " + kind + ": " + e.getMessage());
        }
        return saved;
    }

    /** The image on disk, downloaded first if needed (background thread). */
    static File image(Context c, Item it) throws IOException {
        File dir = new File(c.getCacheDir(), "gallery");
        File f = new File(dir, Integer.toHexString(it.url.hashCode()) + ".jpg");
        if (f.exists()) return f;
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File part = new File(f.getPath() + ".part");
        HttpURLConnection con = open(it.url);
        try (InputStream in = con.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw e;
        } finally {
            con.disconnect();
        }
        if (!part.renameTo(f)) throw new IOException("couldn't save the image");
        prune(dir);
        return f;
    }

    /** "PAISAJE" or "ARTE": the screensaver's label over the title. */
    static String label(String kind) {
        return kind.equals(ART) ? "ARTE" : kind.equals(FAVORITES) ? "FAVORITA" : "PAISAJE";
    }

    // MARK: - Favourites: the pictures the person marked, to see them again and again

    private static File favoritesFile(Context c) {
        return new File(c.getFilesDir(), "gallery-favorites.json");
    }

    static synchronized List<Item> favorites(Context c) {
        return read(favoritesFile(c));
    }

    static synchronized boolean isFavorite(Context c, String url) {
        for (Item it : favorites(c)) if (it.url.equals(url)) return true;
        return false;
    }

    /** Adds the picture to the favourites, or takes it out if it was there; true when it's a favourite now. */
    static synchronized boolean toggleFavorite(Context c, Item item) {
        List<Item> list = favorites(c);
        boolean was = false;
        for (Iterator<Item> i = list.iterator(); i.hasNext(); ) {
            if (i.next().url.equals(item.url)) {
                i.remove();
                was = true;
            }
        }
        if (!was) list.add(item);
        try {
            write(favoritesFile(c), list);
        } catch (Exception e) {
            AppLog.w("favorites: " + e.getMessage());
        }
        return !was;
    }

    /** The home screen's current background (the home screen writes it; Home Settings > Backgrounds reads it). */
    static void setCurrent(Context c, Item item) {
        try {
            write(new File(c.getFilesDir(), "gallery-current.json"), Collections.singletonList(item));
        } catch (Exception ignored) {
        }
    }

    static Item current(Context c) {
        List<Item> l = read(new File(c.getFilesDir(), "gallery-current.json"));
        return l.isEmpty() ? null : l.get(0);
    }

    /**
     * A title as people would write it: without the author (shown under it), "Google Art Project", the file's own
     * codes ("-4224", "DD 10-12 HDR", "IMG_1234") and dates.
     */
    static String displayTitle(Item it) {
        // Commons' titles may carry Wikidata markup with the same title in other languages: `X label QS:Len,"X"`.
        String t = it.title.split("(?i)\\s*(label\\s+)?QS:")[0];
        if (!it.author.isEmpty() && it.author.length() > 3) {
            t = t.replace(it.author, "");
            String last = it.author.substring(it.author.lastIndexOf(' ') + 1);
            if (last.length() > 3) t = t.replaceAll("(?i)\\b" + java.util.regex.Pattern.quote(last) + "\\b", "");
        }
        t = t.replaceAll("(?i)google art project", "")
                .replaceAll("(?i)\\b(img|dsc|dscf|p)[_-]?\\d{3,}\\b", "")
                .replaceAll("(?i)\\bDD [\\d-]+( HDR)?\\b", "")
                .replaceAll("(?i)\\bHDR\\b", "")
                .replaceAll("\\b(1[5-9]|20)\\d{2}-\\d{2}-\\d{2}\\b", "")
                .replaceAll("[-_ ]\\d{3,}$", "")
                .replaceAll("\\(\\s*\\)", "")
                .replaceAll("\\s*[-–—,;:]\\s*([-–—,;:]\\s*)+", " – ")
                .replaceAll("^[\\s\\-–—,;:.]+|[\\s\\-–—,;:.]+$", "")
                .replaceAll("\\s+", " ").trim();
        return t.isEmpty() ? it.title : t;
    }

    private static List<Item> fetch(String[] categories, float minRatio) throws Exception {
        List<Item> out = new ArrayList<>();
        int width = 2560;   // the screensaver zooms slowly into a photo a little bigger than the screen
        for (String cat : categories) {
            String q = API + "?action=query&format=json&generator=categorymembers&gcmtype=file&gcmlimit=200"
                    + "&gcmtitle=" + URLEncoder.encode("Category:" + cat, "UTF-8")
                    + "&prop=imageinfo&iiprop=url%7Csize%7Cextmetadata&iiurlwidth=" + width
                    + "&iiextmetadatafilter=Artist%7CObjectName";
            JSONObject pages = new JSONObject(get(q)).optJSONObject("query");
            Thread.sleep(400);   // one category at a time, gently (Wikimedia limits bursts)
            pages = pages == null ? null : pages.optJSONObject("pages");
            if (pages == null) continue;
            for (Iterator<String> keys = pages.keys(); keys.hasNext(); ) {
                JSONObject page = pages.getJSONObject(keys.next());
                JSONArray infos = page.optJSONArray("imageinfo");
                if (infos == null || infos.length() == 0) continue;
                JSONObject info = infos.getJSONObject(0);
                int w = info.optInt("width"), h = info.optInt("height");
                // Wide ones only (a 16:9 screen crops the rest badly), big enough for the screen.
                if (h <= 0 || w < 1600 || w / (float) h < minRatio || w / (float) h > 2.4f) continue;
                String url = info.optString("thumburl", "");
                if (url.isEmpty()) continue;
                JSONObject meta = info.optJSONObject("extmetadata");
                Item it = new Item();
                it.url = url;
                it.title = clean(field(meta, "ObjectName"));
                if (it.title.isEmpty()) it.title = fileTitle(page.optString("title"));
                it.author = clean(field(meta, "Artist"));
                if (UNSUITABLE.matcher(it.title + " " + page.optString("title")).find()) continue;
                if (it.author.length() > 60) it.author = it.author.substring(0, 59) + "…";
                out.add(it);
            }
        }
        return out;
    }

    /** Grim scenes stay out of a living room (by their titles, in the usual languages): executions, martyrdoms. */
    private static final java.util.regex.Pattern UNSUITABLE = java.util.regex.Pattern.compile(
            "(?i)martyr|mártir|martir|execution|ejecución|exécution|massacre|masacre|beheading|decapita|holofernes");

    private static String field(JSONObject meta, String name) {
        JSONObject o = meta == null ? null : meta.optJSONObject(name);
        return o == null ? "" : o.optString("value", "");
    }

    /** Commons' metadata is HTML: tags out, entities and spaces tidied. */
    private static String clean(String html) {
        String s = html.replaceAll("<[^>]*>", " ").replace("&amp;", "&").replace("&quot;", "\"").replace("&#039;", "'")
                .replace("&nbsp;", " ").replaceAll("\\s+", " ").trim();
        return s.length() > 90 ? s.substring(0, 89) + "…" : s;
    }

    /** "File:Lake Louise at dawn, 2019.jpg" → "Lake Louise at dawn, 2019". */
    private static String fileTitle(String t) {
        t = t.replaceFirst("^File:", "");
        int dot = t.lastIndexOf('.');
        if (dot > 0) t = t.substring(0, dot);
        return clean(t.replace('_', ' '));
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(8000);
        con.setReadTimeout(20000);
        con.setRequestProperty("User-Agent", AGENT);
        return con;
    }

    private static String get(String url) throws IOException {
        HttpURLConnection con = open(url);
        try (InputStream in = con.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            con.disconnect();
        }
    }

    private static List<Item> read(File f) {
        List<Item> out = new ArrayList<>();
        if (!f.exists()) return out;
        try {
            JSONArray a = new JSONArray(new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Item it = new Item();
                it.url = o.optString("url");
                it.title = o.optString("title");
                it.author = o.optString("author");
                if (!it.url.isEmpty()) out.add(it);
            }
        } catch (Exception e) {
            AppLog.w("gallery list: " + e.getMessage());
        }
        return out;
    }

    private static void write(File f, List<Item> items) throws Exception {
        JSONArray a = new JSONArray();
        for (Item it : items) a.put(new JSONObject().put("url", it.url).put("title", it.title).put("author", it.author));
        java.nio.file.Files.write(f.toPath(), a.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Keeps the newest images only. */
    private static void prune(File dir) {
        File[] files = dir.listFiles();
        if (files == null || files.length <= KEEP_IMAGES) return;
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        //noinspection ResultOfMethodCallIgnored
        for (int i = KEEP_IMAGES; i < files.length; i++) files[i].delete();
    }
}
