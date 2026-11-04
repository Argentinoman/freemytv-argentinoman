package io.freemytv;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * The pictures for the home screen's background and the screensaver, packed into the APK (no network): the system
 * ones (app/assets/fondos/, in the repository) and the person's own (the git-ignored app/assets/local/fondos/). Each
 * one is shrunk once to the UI's width and the last few dozen kept in the cache. Disk work: background threads only.
 */
final class Gallery {
    private Gallery() {}

    static final String SYSTEM = "system", MINE = "mine", FAVORITES = "favorites";

    /** Every kind of pictures, in the order they're offered. */
    static final String[] KINDS = {SYSTEM, MINE, FAVORITES};

    /** The folders in assets; titulos.properties beside the pictures may name them ("<file>=Title", "<file>.autor="). */
    private static final String SYSTEM_FOLDER = "fondos", MINE_FOLDER = "local/fondos";

    /** A kind of pictures (not "black" or "clock"). */
    static boolean photos(String kind) {
        for (String k : KINDS) if (k.equals(kind)) return true;
        return false;
    }

    /** Its name in the settings and menus. */
    static String name(String kind) {
        switch (kind) {
            case FAVORITES: return "Favoritas";
            case MINE: return "Fondos personalizados";
            default: return "Fondos del sistema";
        }
    }

    static final class Item {
        String url = "", title = "", author = "";
        /** A favourite kept but not shown for now (the favourites screen pauses and resumes it). */
        boolean paused;
    }

    private static final int KEEP_IMAGES = 40;
    /** Saved this wide at most: a 1080p screen shows no more, and a 720p UI far less. */
    private static final int SAVE_WIDTH = 1920;

    private static List<Item> system, mine;

    /**
     * The pictures, in a fixed shuffled order (the same on every call: the home screen counts its turns in it). No
     * custom ones in this build: the system ones, so the screen is never left black.
     */
    static synchronized List<Item> list(Context c, String kind) {
        if (system == null) system = folder(c, SYSTEM_FOLDER);
        if (mine == null) mine = folder(c, MINE_FOLDER);
        if (kind.equals(FAVORITES)) {   // none, or all paused: the system ones
            List<Item> shown = shownFavorites(c);
            return shown.isEmpty() ? system : shown;
        }
        return kind.equals(MINE) && !mine.isEmpty() ? mine : system;
    }

    private static List<Item> folder(Context c, String folder) {
        String[] files = null;
        try {
            files = c.getAssets().list(folder);
        } catch (IOException ignored) {
        }
        java.util.Properties titles = new java.util.Properties();
        try (InputStream in = c.getAssets().open(folder + "/titulos.properties")) {
            titles.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
        List<Item> out = new ArrayList<>();
        if (files != null) {
            Arrays.sort(files);
            for (String name : files) {
                if (!name.toLowerCase(java.util.Locale.ROOT).matches(".*\\.(jpe?g|png|webp)$")) continue;
                Item it = new Item();
                it.url = "asset:" + folder + "/" + name;
                it.title = titles.getProperty(name, name.replaceFirst("\\.[^.]+$", "").replace('_', ' ').replace('-', ' '));
                it.author = titles.getProperty(name + ".autor", "");
                out.add(it);
            }
        }
        Collections.shuffle(out, new java.util.Random(out.size()));
        return Collections.unmodifiableList(out);
    }

    /** Always on the TV: they come in the APK. */
    static boolean saved(Context c, Item it) {
        return true;
    }

    /** The picture on disk, shrunk to SAVE_WIDTH (background thread). */
    static File image(Context c, Item it) throws IOException {
        if (it.url.startsWith(OWN)) return new File(it.url.substring(OWN.length()));   // already a shrunk copy
        File dir = new File(c.getCacheDir(), "gallery");
        File f = new File(dir, Integer.toHexString(it.url.hashCode()) + SMALL);
        if (f.exists()) return f;
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File part = new File(f.getPath() + ".part");
        try (InputStream in = c.getAssets().open(it.url.substring(6)); FileOutputStream out = new FileOutputStream(part)) {
            copy(in, out);
        }
        shrink(part);
        if (!part.renameTo(f)) throw new IOException("couldn't save the image");
        prune(dir);
        return f;
    }

    /** The screensaver's label over the title. */
    static String label(String kind) {
        return kind.equals(FAVORITES) ? "FAVORITA" : "FONDO";
    }

    // MARK: - Favourites: the pictures the person marked, to see them again and again

    private static File favoritesFile(Context c) {
        return new File(c.getFilesDir(), "gallery-favorites.json");
    }

    static synchronized List<Item> favorites(Context c) {
        return read(favoritesFile(c));
    }

    /** The favourites the background and the screensaver go through: all but the paused ones. */
    private static List<Item> shownFavorites(Context c) {
        List<Item> out = new ArrayList<>();
        for (Item it : favorites(c)) if (!it.paused) out.add(it);
        return out;
    }

    /** The favourites in a new order (Mis favoritas → Cambiar de lugar): the background and screensaver follow it. */
    static synchronized void setOrder(Context c, List<Item> items) {
        try {
            write(favoritesFile(c), items);
        } catch (Exception e) {
            AppLog.w("favorites: " + e.getMessage());
        }
        keepCurrent(c);
    }

    /** Pauses a favourite (kept, not shown) or shows it again. */
    static synchronized void setPaused(Context c, String url, boolean paused) {
        List<Item> list = favorites(c);
        for (Item it : list) if (it.url.equals(url)) it.paused = paused;
        try {
            write(favoritesFile(c), list);
        } catch (Exception e) {
            AppLog.w("favorites: " + e.getMessage());
        }
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

    // MARK: - The person's own pictures, from a pen drive (the file explorer): copied here, kept among the favourites

    /** An own picture's url: "file:" and its copy in the app's files (the pen drive may go). */
    private static final String OWN = "file:";

    /**
     * Copies a picture from the pen drive into the app (shrunk to SAVE_WIDTH) and adds it to the favourites; with
     * `show`, the home screen switches to the favourites with this one on screen. Background thread.
     */
    static synchronized void addOwn(Context c, File source, boolean show) throws IOException {
        File dir = new File(c.getFilesDir(), "fondos-propios");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        String name = source.getName();
        File f = new File(dir, Integer.toHexString((source.getAbsolutePath() + source.length()).hashCode()) + ".jpg");
        if (!f.exists()) {
            File part = new File(f.getPath() + ".part");
            try (InputStream in = new java.io.FileInputStream(source); FileOutputStream out = new FileOutputStream(part)) {
                copy(in, out);
            }
            try {
                shrink(part);
            } catch (IOException e) {
                //noinspection ResultOfMethodCallIgnored
                part.delete();
                throw e;
            }
            if (!part.renameTo(f)) throw new IOException("couldn't save the image");
        }
        Item item = new Item();
        item.url = OWN + f.getAbsolutePath();
        item.title = name.lastIndexOf('.') > 0 ? name.substring(0, name.lastIndexOf('.')) : name;
        if (!isFavorite(c, item.url)) toggleFavorite(c, item);
        if (!show) return;
        setPaused(c, item.url, false);   // chosen as the background: shown again if it was paused
        List<Item> list = shownFavorites(c);
        int index = 0;
        for (int i = 0; i < list.size(); i++) if (list.get(i).url.equals(item.url)) index = i;
        // The home screen shows picture step % count: the step that lands on this one, never the step it's on now
        // (the same step would keep the background it already made).
        long now = Prefs.homeBackgroundStep(c), n = list.size(), wanted = now - Math.floorMod(now, n) + place(c, (int) n, index);
        if (wanted == now && Prefs.homeBackground(c).equals(FAVORITES)) wanted += n;
        Prefs.setHomeBackgroundStep(c, wanted);
        Prefs.setHomeBackground(c, FAVORITES);
    }

    /** The background for this step: the list in its order, or this start's shuffle of it ("Al azar"). */
    static Item pick(Context c, List<Item> items, long step) {
        return items.get(order(c, items.size())[(int) Math.floorMod(step, (long) items.size())]);
    }

    /** Step's place to picture's index: itself in order; at random, a shuffle that stays the same all through a start. */
    private static int[] order(Context c, int n) {
        int[] o = new int[n];
        for (int i = 0; i < n; i++) o[i] = i;
        if (!Prefs.homeBackgroundRandom(c)) return o;
        java.util.Random r = new java.util.Random(Prefs.homeBackgroundSeed(c));
        for (int i = n - 1; i > 0; i--) {
            int j = r.nextInt(i + 1), t = o[i];
            o[i] = o[j];
            o[j] = t;
        }
        return o;
    }

    /** The place among the steps where picture `index` comes. */
    private static int place(Context c, int n, int index) {
        int[] o = order(c, n);
        for (int p = 0; p < n; p++) if (o[p] == index) return p;
        return 0;
    }

    /** After a new order: the step that keeps the picture on Home now, so the next ones follow the new order. */
    private static void keepCurrent(Context c) {
        Item cur = current(c);
        if (cur == null || !Prefs.homeBackground(c).equals(FAVORITES)) return;
        List<Item> list = list(c, FAVORITES);   // the list Home goes through
        for (int i = 0; i < list.size(); i++) {
            if (!list.get(i).url.equals(cur.url)) continue;
            long now = Prefs.homeBackgroundStep(c), wanted = now - Math.floorMod(now, (long) list.size()) + place(c, list.size(), i);
            if (wanted != now) Prefs.setHomeBackgroundStep(c, wanted);
            return;
        }
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

    static String displayTitle(Item it) {
        return it.title;
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
                it.paused = o.optBoolean("paused");
                // Favourites from before (Wikimedia links) aren't in the APK.
                if (it.url.startsWith("asset:") || it.url.startsWith(OWN) && new File(it.url.substring(OWN.length())).isFile()) out.add(it);
            }
        } catch (Exception e) {
            AppLog.w("gallery list: " + e.getMessage());
        }
        return out;
    }

    private static void write(File f, List<Item> items) throws Exception {
        JSONArray a = new JSONArray();
        for (Item it : items) {
            JSONObject o = new JSONObject().put("url", it.url).put("title", it.title).put("author", it.author);
            if (it.paused) o.put("paused", true);
            a.put(o);
        }
        java.nio.file.Files.write(f.toPath(), a.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static final String SMALL = "-s.jpg";

    private static void copy(InputStream in, FileOutputStream out) throws IOException {
        byte[] buffer = new byte[16384];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
    }

    /** At most SAVE_WIDTH wide, as a good JPEG. */
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

    /** Keeps the newest images only. */
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
