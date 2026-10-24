package io.freemytv;

import android.app.Application;

/** Gives Prefs a context in every process, and reads this TV's own app touches (LocalApps). */
public class App extends Application {
    @Override public void onCreate() {
        super.onCreate();
        Prefs.init(this);
        LocalApps.load(this);
    }
}
