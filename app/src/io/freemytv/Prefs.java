package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.KeyEvent;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Settings changed on the TV itself (Free My TV > Settings). config.properties only gives the first
 * values; anything the user changes here wins. Read from the :home process (home screen, screensaver, settings).
 */
final class Prefs {
    private Prefs() {}

    /*
     * TCL's remote (Google TV, Android 11, tested on a 2 GB TCL; codes read from its log with getevent and logcat).
     * In Free My TV mode TCL's own launcher and dashboard are off, and several buttons that they answered did nothing:
     *   Netflix   4062  -> Netflix                 (also TCL's default; kept so it works from inside other apps)
     *   YouTube    190  -> YouTube                 (BUTTON_3; some remotes, BT_RC833, send TCL's own 4065 instead)
     *   Prime     4099  -> Prime Video
     *   Channel   4020  -> nothing until chosen    (TCL Channel, a promo app this mode turns off)
     *   Browser   4159  -> BrowseHere              (com.tcl.browser: TCL's handler opened nothing without its launcher)
     *   TV        4001  -> the antenna's channels  (TCL made it the programme guide, gone with its launcher; opened
     *                                              plainly, com.tcl.tv reopens the last source, often an HDMI, so
     *                                              HomeApps.liveTv asks it for the tuner input's channel list)
     *   TCL Home  4137  -> nothing until chosen    (TCL's home is off in this mode)
     *   Gear      4077  -> the quick panel         (RemoteKeys: Google TV's launcher answered it; off, nobody did)
     * Each is picked in Free My TV > Remote Buttons; "Nothing" hands the key back to the TV.
     */
    /** Remote keys we handle, with the app each opens until the user picks another. Codes read from the TCL's log. */
    static final int KEY_NETFLIX = 4062, KEY_YOUTUBE = 190, KEY_PRIME = 4099, KEY_TCL_CHANNEL = 4020;
    /** TCL's browser button (4159): its own handler opens nothing in Free My TV mode, so it's ours to give an app. */
    static final int KEY_TCL_BROWSER = 4159;
    /** TCL's TV button (4001): TCL turns it into the programme guide, which opens nothing in Free My TV mode. */
    static final int KEY_TCL_TV = 4001;
    /** TCL's "TCL Home" button (4137): its home is off in Free My TV mode, so no app until one is chosen. */
    static final int KEY_TCL_EXTRA = 4137;
    /** The Netflix button on Vestel TVs and Formovie projectors: STEM_1, which the TV hands to its Netflix key receiver (dumpsys window policy). */
    static final int KEY_STEM_NETFLIX = KeyEvent.KEYCODE_STEM_1;
    /** KTC's (NextStar) Tabii button: BUTTON_8, which the TV hands to its hot-key receiver (its log: "keycode = 195"). */
    static final int KEY_KTC_TABII = KeyEvent.KEYCODE_BUTTON_8;
    /** Sony's app buttons, as SONY_KEY + scan code (read from a BRAVIA's log; the key codes are shared). */
    static final int SONY_KEY = 100_000;
    static final int KEY_SONY_CORE = SONY_KEY + 496, KEY_SONY_NETFLIX = SONY_KEY + 583, KEY_SONY_DISNEY = SONY_KEY + 491,
            KEY_SONY_PRIME = SONY_KEY + 490, KEY_SONY_YOUTUBE = SONY_KEY + 485, KEY_SONY_CRUNCHYROLL = SONY_KEY + 507,
            KEY_SONY_PLAY = SONY_KEY + 587;   // the Google Play button of older remotes: BUTTON_8 (BRAVIA 4K UR3, Android 10)
    static final int[] KEYS = RemoteKeys.SONY
            ? new int[] {KEY_SONY_CORE, KEY_SONY_NETFLIX, KEY_SONY_DISNEY, KEY_SONY_PRIME, KEY_SONY_YOUTUBE, KEY_SONY_CRUNCHYROLL,
                    KEY_SONY_PLAY}
            : RemoteKeys.STEM_NETFLIX ? new int[] {KEY_STEM_NETFLIX}
            : RemoteKeys.KTC ? new int[] {KEY_KTC_TABII}
            : new int[] {KEY_NETFLIX, KEY_YOUTUBE, KEY_PRIME, KEY_TCL_CHANNEL, KEY_TCL_BROWSER, KEY_TCL_TV, KEY_TCL_EXTRA};

    /** Bumped by every change: screens compare it to know when to redraw. */
    static volatile int version;

    private static Context app;

    static void init(Context c) {
        app = c.getApplicationContext();
        migrateKeys();   // before anything reads the button choices
        Ui.applyLanguage();   // the language picked on the TV, now that it can be read
    }

    /** The app's context in this process (null before App.onCreate). */
    static Context context() {
        return app;
    }

    /**
     * How much the home screen is dimmed, in percent (0, 20, 40, 60). A file, not SharedPreferences: the quick panel
     * (main process) writes it and the home screen (:home) reads it, and each process caches its SharedPreferences.
     */
    static final String DIM_CHANGED = "io.freemytv.LAUNCHER_DIM";
    static final int[] DIM_LEVELS = {0, 20, 40, 60};

    static int launcherDim(Context c) {
        return Math.max(0, Math.min(80, readInt(c, "launcher-dim.txt", 0)));
    }

    static void setLauncherDim(Context c, int percent) {
        writeInt(c, "launcher-dim.txt", percent);
    }

    /** The home screen's background: "black", Gallery.LANDSCAPES or Gallery.ART (a file: the screensaver reads it too). */
    static String homeBackground(Context c) {
        String v = readString(c, "home-background.txt", "black");
        return v.equals("modern") ? Gallery.ART : v;   // modern art was an option for a while
    }

    static void setHomeBackground(Context c, String v) {
        writeString(c, "home-background.txt", v);
    }

    /** How long each home background stays, in minutes (360: six hours). */
    static int homeBackgroundMinutes(Context c) {
        return Math.max(1, readInt(c, "home-background-minutes.txt", 360));
    }

    static void setHomeBackgroundMinutes(Context c, int minutes) {
        writeInt(c, "home-background-minutes.txt", minutes);
    }

    /** Added to the background's turn: "Change now" moves it one picture on. */
    static int homeBackgroundSkip(Context c) {
        return readInt(c, "home-background-skip.txt", 0);
    }

    static void skipHomeBackground(Context c) {
        writeInt(c, "home-background-skip.txt", homeBackgroundSkip(c) + 1);
    }

    /** How long each screensaver photo stays, in seconds. */
    static int screensaverSeconds(Context c) {
        return Math.max(5, readInt(c, "screensaver-seconds.txt", 30));
    }

    static void setScreensaverSeconds(Context c, int seconds) {
        writeInt(c, "screensaver-seconds.txt", seconds);
    }

    /** The screensaver: Gallery.LANDSCAPES or Gallery.ART passing by, or "clock" (the big clock, no photos). */
    static String screensaver(Context c) {
        String v = readString(c, "screensaver.txt", Gallery.LANDSCAPES);
        return v.equals("modern") ? Gallery.ART : v;
    }

    static void setScreensaver(Context c, String v) {
        writeString(c, "screensaver.txt", v);
    }

    private static String readString(Context c, String file, String fallback) {
        try {
            String s = new String(java.nio.file.Files.readAllBytes(new File(c.getFilesDir(), file).toPath()), "UTF-8").trim();
            return s.isEmpty() ? fallback : s;
        } catch (Exception e) {
            return fallback;
        }
    }

    private static void writeString(Context c, String file, String value) {
        try {
            java.nio.file.Files.write(new File(c.getFilesDir(), file).toPath(), value.getBytes("UTF-8"));
        } catch (Exception e) {
            AppLog.w(file + ": " + e.getMessage());
        }
        c.sendBroadcast(new android.content.Intent(DIM_CHANGED).setPackage(c.getPackageName()));
    }

    private static int readInt(Context c, String file, int fallback) {
        try {
            byte[] b = java.nio.file.Files.readAllBytes(new java.io.File(c.getFilesDir(), file).toPath());
            return Integer.parseInt(new String(b, "UTF-8").trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static void writeInt(Context c, String file, int value) {
        try {
            java.nio.file.Files.write(new java.io.File(c.getFilesDir(), file).toPath(), String.valueOf(value).getBytes("UTF-8"));
        } catch (Exception e) {
            AppLog.w(file + ": " + e.getMessage());
        }
        c.sendBroadcast(new android.content.Intent(DIM_CHANGED).setPackage(c.getPackageName()));
    }

    private static SharedPreferences sp() {
        return app.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    private static void put(String key, String value) {
        sp().edit().putString(key, value).apply();
        version++;
    }

    private static void put(String key, boolean value) {
        sp().edit().putBoolean(key, value).apply();
        version++;
    }

    // MARK: - Language and units

    /** "auto" or a language code as picked ("pt", "fr", "zh-CN"…); Ui.applyLanguage finds its catalogue. */
    static String language() {
        String v = app == null ? Config.LANGUAGE : sp().getString("language", Config.LANGUAGE);
        v = v.trim();
        return v.isEmpty() || v.equalsIgnoreCase("auto") ? "auto" : v;
    }

    static void setLanguage(String v) { put("language", v); Ui.applyLanguage(); }

    /** "auto", "C" or "F". */
    static String temperature() {
        String v = sp().getString("temperature", Config.TEMPERATURE).trim().toUpperCase(Locale.ROOT);
        return v.startsWith("F") ? "F" : v.startsWith("C") ? "C" : "auto";
    }

    static void setTemperature(String v) { put("temperature", v); }

    /** "auto" (dark from sunset to sunrise), "light" or "dark". */
    static String theme() {
        String v = sp().getString("theme", "auto");
        return v.equals("light") || v.equals("dark") ? v : "auto";
    }

    static void setTheme(String v) { put("theme", v); }

    // MARK: - City

    static String city() { return sp().getString("city", Config.CITY).trim(); }
    static String latitude() { return sp().getString("latitude", Config.LATITUDE); }
    static String longitude() { return sp().getString("longitude", Config.LONGITUDE); }

    static void setCity(String name, String latitude, String longitude) {
        sp().edit().putString("city", name).putString("latitude", latitude).putString("longitude", longitude).apply();
        version++;
    }

    /** The home screen's soft click when moving and opening. */
    static boolean sounds() { return sp().getBoolean("sounds", true); }
    static void setSounds(boolean on) { put("sounds", on); }

    // MARK: - Widgets and football

    static boolean weather() { return sp().getBoolean("weather", true); }
    static void setWeather(boolean on) { put("weather", on); }

    /** The football row under the clock, the drawer card and the screensaver line. */
    static boolean futbol() { return sp().getBoolean("futbol", true); }
    static void setFutbol(boolean on) { put("futbol", on); }

    /** The floating "Continuar viendo" island over the home screen (what to resume, recent, featured). */
    static boolean watchNext() { return sp().getBoolean("watch_next", true); }
    static void setWatchNext(boolean on) { put("watch_next", on); }

    /** Favourite club and national team, as ESPN team ids: their games come first everywhere. */
    static String club() { return sp().getString("club", "15"); }
    static void setClub(String id) { put("club", id); }
    static String nation() { return sp().getString("nation", "202"); }
    static void setNation(String id) { put("nation", id); }

    // MARK: - Shelf

    /** Packages on the home shelf, in order; null until the user edits it (then the built-in list applies). */
    static List<String> shelf() {
        String v = sp().getString("shelf", null);
        if (v == null) return null;
        List<String> out = new ArrayList<>();
        for (String p : v.split(",")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    static void setShelf(List<String> packages) {
        StringBuilder sb = new StringBuilder();
        for (String p : packages) sb.append(sb.length() == 0 ? "" : ",").append(p);
        put("shelf", sb.toString());
    }

    /** All Apps in the order the person arranged it (packages); empty: alphabetical. */
    static List<String> gridOrder() {
        List<String> out = new ArrayList<>();
        for (String p : sp().getString("grid_order", "").split(",")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    /** Saved without bumping `version`: rearranging All Apps needn't rebuild the home screen. */
    static void setGridOrder(List<String> packages) {
        StringBuilder sb = new StringBuilder();
        for (String p : packages) sb.append(sb.length() == 0 ? "" : ",").append(p);
        sp().edit().putString("grid_order", sb.toString()).apply();
    }

    // MARK: - Remote buttons

    private static String defaultApp(int keyCode) {
        switch (keyCode) {
            case KEY_NETFLIX: case KEY_STEM_NETFLIX: return "com.netflix.ninja";
            case KEY_YOUTUBE: return "com.google.android.youtube.tv";
            case KEY_PRIME: return "com.amazon.amazonvideo.livingroom";
            case KEY_TCL_CHANNEL: return "";
            case KEY_TCL_BROWSER: return "com.tcl.browser";   // BrowseHere, the TV's own browser
            case KEY_TCL_TV: return "com.tcl.tv";             // live TV
            default: return "";
        }
    }

    // The key service runs in its own small process (:keys), so that a busy home screen can never delay the TV's keys.
    // SharedPreferences are not shared between processes, so these few values live in a tiny file both processes read.
    // The service asks on every key: the values stay in memory and the file is read again only when it changes.
    private static File keysFile() {
        return new File(app.getFilesDir(), "remote-keys.txt");
    }

    private static final String[] keyApps = new String[KEYS.length];   // null = the default
    private static long keysStamp = Long.MIN_VALUE;

    /** A temporary file per process (two can start together after an update), synced before it replaces the old one. */
    private static boolean writeKeys(String text) {
        File tmp = new File(app.getFilesDir(), "remote-keys." + android.os.Process.myPid() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return false;
        }
        return tmp.renameTo(keysFile());
    }

    /** Choices saved before they moved to the file (in SharedPreferences) are copied there once. */
    private static void migrateKeys() {
        if (keysFile().exists()) return;
        SharedPreferences old = sp();
        boolean any = false;
        for (int key : KEYS) any |= old.contains("key" + key);
        if (!any) return;
        StringBuilder sb = new StringBuilder();
        for (int key : KEYS) sb.append(key).append('=').append(old.getString("key" + key, defaultApp(key))).append('\n');
        writeKeys(sb.toString());
    }

    /** Package the key opens ("" = nothing). */
    static String keyApp(int keyCode) {
        synchronized (keyApps) {
            File f = keysFile();
            long stamp = f.lastModified() * 31 + f.length();   // 0 without the file: every key opens its default
            if (stamp != keysStamp) {
                keysStamp = stamp;
                java.util.Arrays.fill(keyApps, null);
                if (stamp != 0) {
                    try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                        String line;
                        while ((line = r.readLine()) != null) {
                            for (int i = 0; i < KEYS.length; i++) {
                                String prefix = KEYS[i] + "=";
                                if (line.startsWith(prefix)) keyApps[i] = line.substring(prefix.length());
                            }
                        }
                    } catch (IOException ignored) {
                    }
                }
            }
            for (int i = 0; i < KEYS.length; i++) if (KEYS[i] == keyCode && keyApps[i] != null) return keyApps[i];
            return defaultApp(keyCode);
        }
    }

    static void setKeyApp(int keyCode, String pkg) {
        StringBuilder sb = new StringBuilder();
        for (int key : KEYS) sb.append(key).append('=').append(key == keyCode ? pkg : keyApp(key)).append('\n');
        if (writeKeys(sb.toString())) version++;
    }
}
