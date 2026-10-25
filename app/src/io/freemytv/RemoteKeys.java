package io.freemytv;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.view.InputDevice;
import android.view.KeyEvent;

/**
 * The remote's app buttons on TCL TVs. The stock home screen opens the apps for Netflix, YouTube and Prime (and TCL
 * Channel); with it off, the keys still arrive, so we open the app the person picked (Settings > Remote Buttons).
 * Disney and Globoplay are opened by the system itself, so they are not listed. The YouTube button (BUTTON_3) never
 * reaches an app's window; only the accessibility service's key filter (RemoteKeyService) sees it.
 * On Vestel TVs and Formovie (Xiaomi) projectors only the Netflix button is ours: the system opens Netflix itself (a
 * global key, after the filter).
 * On KTC TVs (NextStar) only the Tabii button is ours, a global key too: the TV's hot-key receiver opens Tabii with it.
 * On Sony TVs, the app buttons (and the Google Play button of older remotes) are global keys for Sony's own receiver;
 * the filter sees them first, the same way.
 */
final class RemoteKeys {
    private RemoteKeys() {}

    /** Some TCL remotes (BT_RC833) send TCL's own YouTube key instead of BUTTON_3: the same button. */
    private static final int KEY_YOUTUBE_TCL = 4065;

    /**
     * The key codes are the brand's own: on other brands they can be other buttons, so nothing here runs there.
     * Vestel builds TVs sold under other brands (F&U...), so BRAND varies: check the maker too.
     */
    static final boolean TCL = is("TCL"), VESTEL = is("Vestel"), KTC = is("KTC"), SONY = is("Sony"), FORMOVIE = is("Formovie");
    /** Brands whose Netflix button is STEM_1, a global key (MediaTek): only that button is ours. */
    static final boolean STEM_NETFLIX = VESTEL || FORMOVIE;

    static boolean supported() {
        return TCL || STEM_NETFLIX || KTC || SONY;
    }

    private static boolean is(String maker) {
        return maker.equalsIgnoreCase(Build.MANUFACTURER) || maker.equalsIgnoreCase(Build.BRAND);
    }

    /** TCL's gear key (scan code 233, TCL_SET): Google TV's launcher answered it; with that off, nobody did. */
    private static final int KEY_TCL_SETTINGS = 4077;

    /**
     * The TV's quick settings: TCL's panel (brightness, picture and sound modes, sleep timer, Bluetooth, network), the
     * one that stands in for Google TV's (which lives in its launcher, off in Free My TV mode); else Android's.
     */
    static Intent quickSettings(Context c) {
        Intent tcl = new Intent("com.tcl.settings.action.QUICKSETTINGS");
        if (tcl.resolveActivity(c.getPackageManager()) != null) return tcl;
        return new Intent(android.provider.Settings.ACTION_SETTINGS);
    }

    /**
     * The TV's settings, where "Accounts & sign-in" is near the top: Google TV's profile switcher lives in its launcher,
     * the settings' own accounts page waits on it forever, and a section can't be opened on its own.
     */
    static Intent accounts(Context c) {
        return new Intent(android.provider.Settings.ACTION_SETTINGS);
    }

    /** Our own quick settings panel (Wi-Fi, Bluetooth, notifications, screensaver...), Google TV's gear panel's stand-in. */
    static Intent quickPanel(Context c) {
        return new Intent(c, QuickSettingsActivity.class);
    }

    /** Opens the app for this key on its first press. True if the key is ours and in use (it is then consumed). */
    static boolean onKey(Context c, KeyEvent e) {
        if (!supported()) return false;
        // BUTTON_3 is also a game controller's button: keys from a gamepad or a joystick are left alone.
        int source = e.getSource();
        if ((source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) return false;
        int keyCode = e.getKeyCode() == KEY_YOUTUBE_TCL ? Prefs.KEY_YOUTUBE : e.getKeyCode();
        // Sony sends BUTTON_9 for five app buttons (BUTTON_4 for Netflix, BUTTON_8 for Google Play): only the scan code
        // tells them apart.
        if (SONY && (keyCode == KeyEvent.KEYCODE_BUTTON_4 || keyCode == KeyEvent.KEYCODE_BUTTON_8
                || keyCode == KeyEvent.KEYCODE_BUTTON_9)) {
            keyCode = Prefs.SONY_KEY + e.getScanCode();
        }
        // The gear and the profile keys: fixed, the stock home screen's own jobs.
        if ((TCL && keyCode == KEY_TCL_SETTINGS) || keyCode == KeyEvent.KEYCODE_PROFILE_SWITCH) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                final Intent intent = keyCode == KEY_TCL_SETTINGS ? quickPanel(c) : accounts(c);
                final int code = keyCode;
                afterScreensaver(c, () -> {
                    try {
                        c.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    } catch (RuntimeException ex) {
                        AppLog.w("remote key " + code + ": " + ex.getMessage());
                    }
                });
            }
            return true;
        }
        boolean ours = false;
        for (int key : Prefs.KEYS) ours |= key == keyCode;
        if (!ours) return false;
        String pkg = Prefs.keyApp(keyCode);
        if (pkg.isEmpty()) return false;   // "Nothing": the key goes on as if we weren't here
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            // The TV's own live TV app: straight to the antenna's channels, as Home's Live TV does (opened plainly, it
            // goes back to the last source, often an HDMI).
            boolean live = java.util.Arrays.asList(HomeApps.LIVE_TV_APPS).contains(pkg);
            Intent intent = live ? HomeApps.liveTv(c) : launchIntent(c.getPackageManager(), pkg);
            AppLog.w("remote key " + e.getKeyCode() + " -> " + pkg + (intent == null ? " (not installed)" : ""));
            // The same quick fade through black as from Home: what's on screen fades out, the app (and its grey
            // loading window) fades in, instead of the grey cutting in.
            if (intent != null) {
                afterScreensaver(c, () -> c.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        android.app.ActivityOptions.makeCustomAnimation(c, live ? R.anim.tv_in : R.anim.app_in, R.anim.app_out).toBundle()));
            }
        }
        return true;
    }

    private static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());

    /**
     * Opens an app over the screensaver too: this service takes the key before the screensaver sees it, so the
     * screensaver is told to end first (it stayed over the app otherwise), and the app opens once it has gone.
     */
    private static void afterScreensaver(Context c, Runnable open) {
        if (!Screensaver.dreamingFlag(c).exists()) {
            open.run();
            return;
        }
        c.sendBroadcast(new Intent(Screensaver.END).setPackage(c.getPackageName()));
        MAIN.postDelayed(open, 250);
    }

    /** The app's TV launcher, or its plain one (kept here, not in HomeApps, so the key process stays small). */
    private static Intent launchIntent(PackageManager pm, String pkg) {
        Intent intent = pm.getLeanbackLaunchIntentForPackage(pkg);
        return intent != null ? intent : pm.getLaunchIntentForPackage(pkg);
    }
}
