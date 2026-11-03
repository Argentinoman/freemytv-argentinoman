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
 * The pictures for the home screen's background and the screensaver: the person's own, packed into the APK from the
 * git-ignored app/assets/local/fondos/ (no network). Each one is shrunk once to the UI's width and the last few dozen
 * kept in the cache. Disk work: background threads only.
 */
final class Gallery {
    private Gallery() {}

    static final String MINE = "mine", FAVORITES = "favorites";

    /** Every kind of pictures, in the order they're offered. */
    static final String[] KINDS = {MINE, FAVORITES};

    /** The folder in assets; titulos.properties beside the pictures may name them ("<file>=Title", "<file>.autor="). */
    private static final String FOLDER = "local/fondos";

    /** A kind of pictures (not "black" or "clock"). */
    static boolean photos(String kind) {
        for (String k : KINDS) if (k.equals(kind)) return true;
        return false;
    }

    /** Its name in the settings and menus. */
    static String name(String kind) {
        return kind.equals(FAVORITES) ? "Favoritas" : "Mis fondos";
    }

    static final class Item {
        String url = "", title = "", author = "";
    }

    private static final int KEEP_IMAGES = 40;
    /** Saved this wide at most: a 1080p screen shows no more, and a 720p UI far less. */
    private static final int SAVE_WIDTH = 1920;

    private static List<Item> mine;

    /** The pictures, in a fixed shuffled order (the same on every call: the home screen counts its turns in it). */
    static synchronized List<Item> list(Context c, String kind) {
        if (kind.equals(FAVORITES)) return read(favoritesFile(c));
        if (mine != null) return mine;
        String[] files = null;
        try {
            files = c.getAssets().list(FOLDER);
        } catch (IOException ignored) {
        }
        java.util.Properties titles = new java.util.Properties();
        try (InputStream in = c.getAssets().open(FOLDER + "/titulos.properties")) {
            titles.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
        List<Item> out = new ArrayList<>();
        if (files != null) {
            Arrays.sort(files);
            for (String name : files) {
                if (!name.toLowerCase(java.util.Locale.ROOT).matches(".*\\.(jpe?g|png|webp)$")) continue;
                Item it = new Item();
                it.url = "asset:" + FOLDER + "/" + name;
                it.title = titles.getProperty(name, name.replaceFirst("\\.[^.]+$", "").replace('_', ' ').replace('-', ' '));
                it.author = titles.getProperty(name + ".autor", "");
                out.add(it);
            }
        }
        Collections.shuffle(out, new java.util.Random(out.size()));
        mine = Collections.unmodifiableList(out);
        return mine;
    }

    /** Always on the TV: they come in the APK. */
    static boolean saved(Context c, Item it) {
        return true;
    }

    /** The picture on disk, shrunk to SAVE_WIDTH (background thread). */
    static File image(Context c, Item it) throws IOException {
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
                // Favourites from before (Wikimedia links) aren't in the APK.
                if (it.url.startsWith("asset:")) out.add(it);
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
