package io.freemytv;

import android.app.Activity;
import android.os.Bundle;

/**
 * Philco's buttons that MediaTek's hot-key receiver keeps for itself, opened with the app picked for them (Free My TV >
 * Remote Buttons); with "Nothing" picked it just closes:
 *  - Search: the receiver asks for android.intent.action.ASSIST, which this invisible activity answers;
 *  - Google TV: the receiver opens com.google.android.videos, which tools/googletv-bridge stands in for; the bridge
 *    starts this activity with the button in the "key" extra.
 * Off in the manifest; RemoteKeys.enableSearchKey turns it on, on Philco only.
 */
public class SearchKeyActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        int key = getIntent().getIntExtra("key", Prefs.KEY_PHILCO_SEARCH);
        if (key != Prefs.KEY_PHILCO_GOOGLE_TV) key = Prefs.KEY_PHILCO_SEARCH;
        String pkg = Prefs.keyApp(key);
        if (!pkg.isEmpty()) RemoteKeys.open(this, pkg, key);
        finish();
        overridePendingTransition(0, 0);
    }
}
