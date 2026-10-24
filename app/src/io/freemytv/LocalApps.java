package io.freemytv;

import android.content.Context;

import java.io.InputStream;
import java.util.Properties;

/**
 * Per-TV touches for particular apps, kept out of the repository: app/assets/local/apps.properties (git-ignored), with
 * the pictures it names beside it. For each package:
 *   com.example.app.name=Shown name        (an app that calls itself just "TV")
 *   com.example.app.art=card.jpg           (its card: the picture's middle, cropped to 16:9)
 *   com.example.app.video=true             (a video app: the now-playing widget, for music, leaves it out)
 * Without the file, every app shows as it is.
 */
final class LocalApps {
    private LocalApps() {}

    private static final Properties PROPS = new Properties();
    private static boolean loaded;

    static synchronized void load(Context c) {
        if (loaded) return;
        loaded = true;
        try (InputStream in = c.getAssets().open("local/apps.properties")) {
            PROPS.load(new java.io.InputStreamReader(in, "UTF-8"));
        } catch (java.io.IOException ignored) {   // no file: nothing to change
        }
    }

    static String name(String pkg) {
        return pkg == null ? null : PROPS.getProperty(pkg + ".name");
    }

    /** The card picture's asset path, or null. */
    static String art(String pkg) {
        String file = pkg == null ? null : PROPS.getProperty(pkg + ".art");
        return file == null ? null : "local/" + file;
    }

    static boolean video(String pkg) {
        return pkg != null && "true".equalsIgnoreCase(PROPS.getProperty(pkg + ".video"));
    }
}
