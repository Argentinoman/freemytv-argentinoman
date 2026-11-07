package com.google.android.apps.play.movies.tv.usecase.home;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** The activity MediaTek's hot-key receiver opens for the Google TV button (by this exact name): hands it to Free My TV (Prefs.KEY_PHILCO_GOOGLE_TV) and closes. */
public class TvHomeActivity extends Activity {
    private static final int KEY_PHILCO_GOOGLE_TV = 200_501;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            startActivity(new Intent().setClassName("io.freemytv", "io.freemytv.SearchKeyActivity")
                    .putExtra("key", KEY_PHILCO_GOOGLE_TV).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            // Free My TV missing or too old: the button does nothing
        }
        finish();
        overridePendingTransition(0, 0);
    }
}
