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
    /** The HY300's two-boxes (F10, scan 68) and box-with-arrow (F11, scan 87) buttons, as HY_KEY + scan code. */
    static final int HY_KEY = 200_000, KEY_HY_BOXES = HY_KEY + 68, KEY_HY_ARROW = HY_KEY + 87;
    static final int KEY_SONY_CORE = SONY_KEY + 496, KEY_SONY_NETFLIX = SONY_KEY + 583, KEY_SONY_DISNEY = SONY_KEY + 491,
            KEY_SONY_PRIME = SONY_KEY + 490, KEY_SONY_YOUTUBE = SONY_KEY + 485, KEY_SONY_CRUNCHYROLL = SONY_KEY + 507,
            KEY_SONY_PLAY = SONY_KEY + 587;   // the Google Play button of older remotes: BUTTON_8 (BRAVIA 4K UR3, Android 10)
    static final int[] KEYS = RemoteKeys.PROJECTOR ? new int[] {KEY_HY_BOXES, KEY_HY_ARROW}
            : RemoteKeys.SONY
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

    /**
     * HY300: whether the quick panel shows its "ADB por red" button (off unless chosen in Home Settings). Network ADB
     * stays closed by the boot firewall either way. A file: the panel (:home) and the settings read it.
     */
    static boolean quickAdb(Context c) {
        return readInt(c, "quick-adb.txt", 0) == 1;
    }

    static void setQuickAdb(Context c, boolean on) {
        writeInt(c, "quick-adb.txt", on ? 1 : 0);
    }

    /** The home screen's background: "black" or one of Gallery.KINDS (a file: the screensaver reads it too). */
    static String homeBackground(Context c) {
        String v = readString(c, "home-background.txt", Gallery.SYSTEM);
        return v.equals("black") || Gallery.photos(v) ? v : Gallery.SYSTEM;   // Wikimedia's kinds, from before
    }

    static void setHomeBackground(Context c, String v) {
        writeString(c, "home-background.txt", v);
    }

    static String homeBackgroundShown(Context c) {
        return homeBackground(c);
    }

    static String screensaverShown(Context c) {
        return screensaver(c);
    }

    /** How long each home background stays, in minutes (360: six hours). */
    static int homeBackgroundMinutes(Context c) {
        return Math.max(1, readInt(c, "home-background-minutes.txt", 360));
    }

    static void setHomeBackgroundMinutes(Context c, int minutes) {
        writeInt(c, "home-background-minutes.txt", minutes);
    }

    /**
     * The home background's place in its list (taken modulo its length): the first picture every time the TV starts
     * or turns on, then one step on after each `minutes` of it being on ("Change now" moves it on too), so the
     * favourites go by in the order the person gave them. Not the wall clock: the projector starts before its clock is
     * set, and the same hour always gave the same picture.
     */
    static synchronized long homeBackgroundStep(Context c) {
        long now = android.os.SystemClock.elapsedRealtime();
        int boot = bootCount(c);
        long[] s = readStep(c);   // step, boot, since (elapsed), seed (the shuffle, when random)
        if (s == null || s[1] != boot) {   // a start: from the first one again
            saveStep(c, 0, boot, now, new java.util.Random().nextLong());
            return 0;
        }
        if (now - s[2] >= homeBackgroundMinutes(c) * 60_000L) {   // always one step: never one skipped
            s[0]++;
            saveStep(c, s[0], boot, now, s[3]);
        }
        return s[0];
    }

    /** The TV turned off (the screen went dark): when it turns on, the first picture again (a new shuffle). */
    static synchronized void restartHomeBackground(Context c) {
        saveStep(c, 0, bootCount(c), android.os.SystemClock.elapsedRealtime(), new java.util.Random().nextLong());
    }

    /** The shuffle of this start, for "Al azar". */
    static long homeBackgroundSeed(Context c) {
        long[] s = readStep(c);
        return s == null ? 0 : s[3];
    }

    /** "Orden de las fotos": the favourites in their order (false) or shuffled anew on every start (true). */
    static boolean homeBackgroundRandom(Context c) {
        return readInt(c, "home-background-random.txt", 0) == 1;
    }

    static void setHomeBackgroundRandom(Context c, boolean random) {
        restartHomeBackground(c);   // from the start of the new order
        writeInt(c, "home-background-random.txt", random ? 1 : 0);
    }

    /** A step that lands on a given picture (the photo viewer, a new order); the time starts again. */
    static synchronized void setHomeBackgroundStep(Context c, long step) {
        saveStep(c, step, bootCount(c), android.os.SystemClock.elapsedRealtime(), homeBackgroundSeed(c));
        c.sendBroadcast(new android.content.Intent(DIM_CHANGED).setPackage(c.getPackageName()));
    }

    static synchronized void skipHomeBackground(Context c) {
        long[] s = readStep(c);
        setHomeBackgroundStep(c, s == null ? 1 : s[0] + 1);
    }

    private static int bootCount(Context c) {
        return android.provider.Settings.Global.getInt(c.getContentResolver(), android.provider.Settings.Global.BOOT_COUNT, 0);
    }

    private static long[] readStep(Context c) {
        try {
            String[] p = readString(c, "home-background-step.txt", "").split(" ");
            return new long[] {Long.parseLong(p[0]), Long.parseLong(p[1]), Long.parseLong(p[2]),
                    p.length > 3 ? Long.parseLong(p[3]) : 0};
        } catch (Exception e) {
            return null;
        }
    }

    private static void saveStep(Context c, long step, int boot, long since, long seed) {
        try {   // no broadcast: Home reads it on its own tick
            java.nio.file.Files.write(new File(c.getFilesDir(), "home-background-step.txt").toPath(),
                    (step + " " + boot + " " + since + " " + seed).getBytes("UTF-8"));
        } catch (Exception e) {
            AppLog.w("home-background-step.txt: " + e.getMessage());
        }
    }

    /** How long each screensaver photo stays, in seconds. */
    static int screensaverSeconds(Context c) {
        return Math.max(5, readInt(c, "screensaver-seconds.txt", 30));
    }

    static void setScreensaverSeconds(Context c, int seconds) {
        writeInt(c, "screensaver-seconds.txt", seconds);
    }

    /** The screensaver: one of Gallery.KINDS passing by, or "clock" (the big clock, no photos). */
    static String screensaver(Context c) {
        String v = readString(c, "screensaver.txt", Gallery.SYSTEM);
        return v.equals("clock") || Gallery.photos(v) ? v : Gallery.SYSTEM;   // Wikimedia's kinds, from before
    }

    static void setScreensaver(Context c, String v) {
        writeString(c, "screensaver.txt", v);
    }

    /** The HY300's picture: "auto,vertical,horizontal,zoom,mode" (Projector). A file: the panel and Home both read it. */
    static String projector(Context c) {
        return readString(c, "projector.txt", "");
    }

    static void setProjector(Context c, String v) {
        writeString(c, "projector.txt", v);
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

    // MARK: - Widgets

    static boolean weather() { return sp().getBoolean("weather", true); }
    static void setWeather(boolean on) { put("weather", on); }

    /** The floating "Continuar viendo" island over the home screen (what to resume, recent, featured). */
    static boolean watchNext() { return sp().getBoolean("watch_next", true); }
    static void setWatchNext(boolean on) { put("watch_next", on); }

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
            // RK3326: the box-with-arrow is the input button (the system opened Rockchip's HDMI app with it): our HDMI.
            case KEY_HY_ARROW: return RemoteKeys.RK3326 ? RemoteKeys.OPEN_HDMI : "";
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
