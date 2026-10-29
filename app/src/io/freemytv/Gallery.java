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

    static final String LANDSCAPES = "landscapes", ART = "art", ANIMALS = "animals", ABSTRACT = "abstract", FAVORITES = "favorites";

    /** Every kind of photos, in the order they're offered. */
    static final String[] KINDS = {LANDSCAPES, ART, ABSTRACT, ANIMALS, FAVORITES};

    /**
     * Seasonal themes (Home Settings > Backgrounds > Seasonal theme): while one is on, the home screen's background and
     * the screensaver's photos come from it, whatever was picked for them. For a few days a year, not every day.
     */
    static final String HALLOWEEN = "halloween", CHRISTMAS = "christmas", NEW_YEAR = "newyear", CARNIVAL = "carnival",
            EASTER = "easter", BIRTHDAY = "birthday";
    static final String[] THEMES = {HALLOWEEN, CHRISTMAS, NEW_YEAR, CARNIVAL, EASTER, BIRTHDAY};

    /** A kind of photos (not "black" or "clock"), a seasonal theme included. */
    static boolean photos(String kind) {
        for (String k : KINDS) if (k.equals(kind)) return true;
        for (String k : THEMES) if (k.equals(kind)) return true;
        return false;
    }

    /** Its name in the settings and menus. */
    static String name(String kind) {
        switch (kind) {
            case ART: return "Arte";
            case ABSTRACT: return "Arte abstracto";
            case ANIMALS: return "Animales";
            case FAVORITES: return "Favoritas";
            case HALLOWEEN: return "Halloween";
            case CHRISTMAS: return "Navidad";
            case NEW_YEAR: return "Año Nuevo";
            case CARNIVAL: return "Carnaval";
            case EASTER: return "Pascuas";
            case BIRTHDAY: return "Cumpleaños";
            default: return "Paisajes";
        }
    }

    static final class Item {
        String url = "", title = "", author = "";
        /** The original's width (not saved): the sharpest originals are kept. */
        int width;
    }

    private static final String API = "https://commons.wikimedia.org/w/api.php";
    /** Wikimedia asks every client to say who it is. */
    private static final String AGENT = "TVdeMati/1.0 (Android TV home screen; Free My TV fork)";
    private static final long LIST_EVERY = 7 * 24 * 3600_000L;
    private static final int KEEP_IMAGES = 40;
    /** Per kind: the sharpest originals only (quality over quantity). */
    private static final int PER_KIND = 20;
    /** Saved at the screensaver's size (the UI draws at 1920, zoomed up to 10%): sharp, and a fourth of the original's space. */
    private static final int SAVE_WIDTH = 2112;

    private static final String[] LANDSCAPE_CATEGORIES = {"Featured pictures of landscapes", "Featured pictures of mountains"};
    private static final String[] ART_CATEGORIES = {
        "Featured pictures of paintings from France", "Featured pictures of paintings from the Netherlands",
        "Featured pictures of paintings from Japan", "Featured pictures of paintings from Norway",
        "Featured pictures of paintings from Spain", "Featured pictures of paintings from Italy",
        "Featured pictures of paintings from the United States", "Featured pictures of paintings from Russia"};

    /**
     * Animals and abstract art come from Commons' search, which walks subcategories (the featured categories of
     * animals hold almost nothing directly: their pictures sit in a category per family). Abstract art: abstract
     * paintings by its public-domain founders (the whole category also holds graffiti and climate charts).
     */
    private static final String[] ANIMAL_SEARCHES = {"deepcat:\"Featured pictures of mammals\"",
        "deepcat:\"Featured pictures of birds\"", "deepcat:\"Featured pictures of reptiles\"",
        "deepcat:\"Featured pictures of animals in flight\""};
    /** The themes' pictures: decorations, fireworks, masks and cakes (Commons' quality images where there are enough). */
    private static String[] themeSearches(String kind) {
        switch (kind) {
            case HALLOWEEN: return new String[] {"deepcat:\"Jack-o'-lantern\""};   // carved pumpkins, lit
            case CHRISTMAS: return new String[] {"deepcat:\"Christmas lights\" incategory:\"Quality images\"",
                    "deepcat:\"Christmas trees\" incategory:\"Featured pictures on Wikimedia Commons\""};
            case NEW_YEAR: return new String[] {"deepcat:\"New Year fireworks\""};
            case CARNIVAL: return new String[] {"deepcat:\"Carnival in Venice\"", "deepcat:\"Carnival masks\""};
            case EASTER: return new String[] {"deepcat:\"Easter decorations\""};
            case BIRTHDAY: return new String[] {"deepcat:\"Birthday cakes\""};
            default: return null;
        }
    }

    private static final String[] ABSTRACT_SEARCHES = {"deepcat:\"Abstract paintings\" Kandinsky OR Klee OR Mondrian"
        + " OR Malevich OR Delaunay OR Kupka OR \"af Klint\" OR Jawlensky OR \"Franz Marc\" OR Popova"};

    /** The list (saved, or fetched when older than a week), shuffled once per fetch so every TV sees its own order. */
    static List<Item> list(Context c, String kind) {
        if (kind.equals(FAVORITES)) return read(favoritesFile(c, "off"));   // the everyday ones, never a theme's
        File f = new File(c.getFilesDir(), "gallery2-" + kind + ".json");
        List<Item> all = read(f), saved = suitable(all);
        if (!saved.isEmpty() && System.currentTimeMillis() - f.lastModified() < LIST_EVERY
                && saved.size() == all.size()) {   // a list saved before the filter caught something is fetched again
            return withLocal(c, kind, saved);
        }
        try {
            String[] theme = themeSearches(kind);
            List<Item> fresh = theme != null ? search(theme, 1.3f, 4)
                    : kind.equals(ANIMALS) ? search(ANIMAL_SEARCHES, 1.3f, 4)
                    : kind.equals(ABSTRACT) ? search(ABSTRACT_SEARCHES, 1.15f, 10)   // a painting crops well enough
                    : fetch(kind.equals(ART) ? ART_CATEGORIES : LANDSCAPE_CATEGORIES, 1.3f);
            if (!fresh.isEmpty()) {
                Collections.sort(fresh, (a, b) -> b.width - a.width);   // the sharpest originals first
                if (fresh.size() > PER_KIND) fresh = new ArrayList<>(fresh.subList(0, PER_KIND));
                Collections.shuffle(fresh);
                write(f, fresh);
                return withLocal(c, kind, fresh);
            }
        } catch (Exception e) {
            AppLog.w("gallery " + kind + ": " + e.getMessage());
        }
        return withLocal(c, kind, saved);
    }

    /**
     * A seasonal theme's pictures: this TV's own (the git-ignored app/assets/local/temas/<theme>/: a film's wallpaper,
     * say, kept out of the repository) one every third picture, then the theme's favourites one every other.
     */
    private static List<Item> withLocal(Context c, String kind, List<Item> list) {
        if (localFolder(kind) == null) return list;
        List<Item> out = withOwn(c, kind, list);
        List<Item> favs = read(favoritesFile(c, kind));
        if (favs.isEmpty()) return out;
        List<Item> mixed = new ArrayList<>();
        int f = 0;
        for (Item it : out) {
            if (mixed.size() % 2 == 0) mixed.add(favs.get(f++ % favs.size()));
            mixed.add(it);
        }
        if (mixed.isEmpty()) mixed.addAll(favs);
        return mixed;
    }

    private static List<Item> withOwn(Context c, String kind, List<Item> list) {
        String folder = localFolder(kind);
        String[] files = null;
        try {
            files = c.getAssets().list(folder);
        } catch (IOException ignored) {
        }
        if (files == null || files.length == 0) return list;
        // Titles may sit in titulos.properties beside them (file name = title, in UTF-8; "<file>.autor" = author):
        // short file names keep the build's paths short, and a title can have accents and ñ.
        java.util.Properties titles = new java.util.Properties();
        try (InputStream in = c.getAssets().open(folder + "/titulos.properties")) {
            titles.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
        List<Item> local = new ArrayList<>();
        for (String name : files) {
            if (!name.toLowerCase(java.util.Locale.ROOT).matches(".*\\.(jpe?g|png|webp)$")) continue;
            Item it = new Item();
            it.url = "asset:" + folder + "/" + name;
            it.title = titles.getProperty(name, name.replaceFirst("\\.[^.]+$", "").replace('_', ' ').replace('-', ' '));
            it.author = titles.getProperty(name + ".autor", "");   // a CC BY photo is credited
            local.add(it);
        }
        if (local.isEmpty()) return list;
        List<Item> out = new ArrayList<>();
        int l = 0;
        for (Item it : list) {
            if (out.size() % 3 == 0) out.add(local.get(l++ % local.size()));
            out.add(it);
        }
        if (out.isEmpty()) out.addAll(local);
        return out;
    }

    /** The theme's folder of the person's own pictures, in Spanish. */
    private static String localFolder(String kind) {
        switch (kind) {
            case HALLOWEEN: return "local/temas/halloween";
            case CHRISTMAS: return "local/temas/navidad";
            case NEW_YEAR: return "local/temas/anio-nuevo";
            case CARNIVAL: return "local/temas/carnaval";
            case EASTER: return "local/temas/pascuas";
            case BIRTHDAY: return "local/temas/cumpleanos";
            default: return null;
        }
    }

    /** The image on disk, downloaded first if needed (background thread). */
    static File image(Context c, Item it) throws IOException {
        File dir = new File(c.getCacheDir(), "gallery");
        File f = new File(dir, Integer.toHexString(it.url.hashCode()) + SMALL);
        if (f.exists()) return f;
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File part = new File(f.getPath() + ".part");
        if (it.url.startsWith("asset:")) {
            try (InputStream in = c.getAssets().open(it.url.substring(6)); FileOutputStream out = new FileOutputStream(part)) {
                copy(in, out);
            }
        } else {
            HttpURLConnection con = open(it.url);
            try (InputStream in = con.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
                copy(in, out);
            } catch (IOException e) {
                //noinspection ResultOfMethodCallIgnored
                part.delete();
                throw e;
            } finally {
                con.disconnect();
            }
        }
        shrink(part);
        if (!part.renameTo(f)) throw new IOException("couldn't save the image");
        prune(dir);
        return f;
    }

    /** "PAISAJE", "ARTE"...: the screensaver's label over the title. */
    static String label(String kind) {
        switch (kind) {
            case ART: return "ARTE";
            case ABSTRACT: return "ARTE ABSTRACTO";
            case ANIMALS: return "ANIMAL";
            case FAVORITES: return "FAVORITA";
            case HALLOWEEN: case CHRISTMAS: case NEW_YEAR: case CARNIVAL: case EASTER: case BIRTHDAY:
                return name(kind).toUpperCase(java.util.Locale.getDefault());
            default: return "PAISAJE";
        }
    }

    // MARK: - Favourites: the pictures the person marked, to see them again and again

    /**
     * The favourites: everyday ones, and each seasonal theme's own. While a theme is on, marking a picture saves it to
     * that theme's favourites (shown often within the theme), so Halloween never turns up among the everyday ones.
     */
    private static File favoritesFile(Context c, String theme) {
        return new File(c.getFilesDir(), photos(theme) ? "gallery-favorites-" + theme + ".json" : "gallery-favorites.json");
    }

    private static File favoritesFile(Context c) {
        return favoritesFile(c, Prefs.seasonalTheme(c));
    }

    /** The favourites in use now: the seasonal theme's while one is on, else the everyday ones. */
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
            JSONObject query = new JSONObject(get(q)).optJSONObject("query");
            Thread.sleep(400);   // one category at a time, gently (Wikimedia limits bursts)
            add(query, minRatio, out);
        }
        return out;
    }

    /** Commons' search for files (it walks subcategories with deepcat:), 50 a page, up to `pages` pages per query. */
    private static List<Item> search(String[] queries, float minRatio, int pages) throws Exception {
        List<Item> out = new ArrayList<>();
        for (String s : queries) {
            String offset = null;
            for (int p = 0; p < pages; p++) {
                String q = API + "?action=query&format=json&generator=search&gsrnamespace=6&gsrlimit=50"
                        + "&gsrsearch=" + URLEncoder.encode(s, "UTF-8") + (offset != null ? "&gsroffset=" + offset : "")
                        + "&prop=imageinfo&iiprop=url%7Csize%7Cextmetadata&iiurlwidth=2560"
                        + "&iiextmetadatafilter=Artist%7CObjectName";
                JSONObject answer = new JSONObject(get(q));
                Thread.sleep(1500);   // searches cost Wikimedia more: slower than the category lists
                add(answer.optJSONObject("query"), minRatio, out);
                JSONObject more = answer.optJSONObject("continue");
                if (more == null) break;
                offset = more.optString("gsroffset", null);
                if (offset == null) break;
            }
        }
        return out;
    }

    /** The usable pictures of an answer: wide (a 16:9 screen crops the rest badly), big enough, nothing grim. */
    private static void add(JSONObject query, float minRatio, List<Item> out) throws org.json.JSONException {
        JSONObject pages = query == null ? null : query.optJSONObject("pages");
        if (pages == null) return;
        for (Iterator<String> keys = pages.keys(); keys.hasNext(); ) {
            JSONObject page = pages.getJSONObject(keys.next());
            JSONArray infos = page.optJSONArray("imageinfo");
            if (infos == null || infos.length() == 0) continue;
            JSONObject info = infos.getJSONObject(0);
            int w = info.optInt("width"), h = info.optInt("height");
            if (h <= 0 || w < 1600 || w / (float) h < minRatio || w / (float) h > 2.4f) continue;
            String url = info.optString("thumburl", "");
            if (url.isEmpty()) continue;
            if (page.optString("title").toLowerCase(java.util.Locale.ROOT).matches(".*\\.(webm|ogv|mp4|pdf|djvu)$")) continue;   // videos, documents
            JSONObject meta = info.optJSONObject("extmetadata");
            Item it = new Item();
            it.url = url;
            it.width = w;
            it.title = clean(field(meta, "ObjectName"));
            if (it.title.isEmpty()) it.title = fileTitle(page.optString("title"));
            it.author = clean(field(meta, "Artist"));
            if (UNSUITABLE.matcher(it.title + " " + page.optString("title")).find()) continue;
            if (it.author.length() > 60) it.author = it.author.substring(0, 59) + "…";
            for (Item seen : out) if (seen.url.equals(url)) { url = null; break; }   // a picture in two categories
            if (url != null) out.add(it);
        }
    }

    /** A saved list without what the filter now leaves out (it may have been saved before the filter had it). */
    private static List<Item> suitable(List<Item> list) {
        List<Item> out = new ArrayList<>();
        for (Item it : list) if (!UNSUITABLE.matcher(it.title).find()) out.add(it);
        return out;
    }

    /** Grim scenes stay out of a living room (by their titles, in the usual languages): executions, martyrdoms, prey. */
    private static final java.util.regex.Pattern UNSUITABLE = java.util.regex.Pattern.compile(
            "(?i)martyr|mártir|martir|execution|ejecución|exécution|massacre|masacre|beheading|decapita|holofernes"
            + "|carcass|cadáver|with prey|eating a|feeding on|killed"
            // Not photos: carving stencils, templates, instruction sheets, posters, diagrams.
            + "|stencil|template|plantilla|instructions|instrucciones|diagram|infographic|poster|flyer|clip ?art");

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

    /** Saved images end so; the earlier full-size ones (2560 px, four times the space) are pruned. */
    private static final String SMALL = "-s.jpg";

    private static void copy(InputStream in, FileOutputStream out) throws IOException {
        byte[] buffer = new byte[16384];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
    }

    /** At most SAVE_WIDTH wide, as a good JPEG: what the screen can show, in a fourth of the space. */
    private static void shrink(File file) throws IOException {
        android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeFile(file.getPath(), o);
        if (o.outWidth <= 0) throw new IOException("not an image");
        if (o.outWidth <= SAVE_WIDTH && file.getName().endsWith(".jpg.part")) return;
        int sample = 1;
        while (o.outWidth / (sample * 2) >= SAVE_WIDTH) sample *= 2;
        o = new android.graphics.BitmapFactory.Options();
        o.inSampleSize = sample;
        android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeFile(file.getPath(), o);
        if (b == null) throw new IOException("not an image");
        if (b.getWidth() > SAVE_WIDTH) {
            android.graphics.Bitmap s = android.graphics.Bitmap.createScaledBitmap(b, SAVE_WIDTH,
                    Math.round(b.getHeight() * SAVE_WIDTH / (float) b.getWidth()), true);
            b.recycle();
            b = s;
        }
        try (FileOutputStream out = new FileOutputStream(file)) {
            b.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out);
        }
        b.recycle();
    }

    /** Keeps the newest images only (and drops the old full-size ones). */
    private static void prune(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        int kept = 0;
        for (File file : files) {
            if (file.getName().endsWith(SMALL) && kept++ < KEEP_IMAGES) continue;
            if (file.getName().endsWith(".part")) continue;   // one being saved
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }
}
