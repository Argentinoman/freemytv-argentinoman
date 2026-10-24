package io.freemytv;

import android.service.notification.NotificationListenerService;

/**
 * Holds the notification access: Android lets an app see the media sessions (what Spotify or YouTube Music is playing) only while
 * it holds notification access, and this service is what that access is granted to. The quick settings panel also
 * lists the notifications through it, when the person opens that list. While connected (always, with the access
 * granted), MediaHistory keeps what plays in YouTube and Spotify for the home screen's "recently" cards.
 * Granted once over ADB: cmd notification allow_listener io.freemytv/io.freemytv.NowPlayingListener
 */
public class NowPlayingListener extends NotificationListenerService {
    /** While connected: the quick settings panel lists the notifications through it (same process). */
    static volatile NowPlayingListener instance;

    @Override public void onListenerConnected() {
        instance = this;
        MediaHistory.get(this).start();
    }

    @Override public void onListenerDisconnected() {
        instance = null;
        MediaHistory.get(this).stop();
    }
}
