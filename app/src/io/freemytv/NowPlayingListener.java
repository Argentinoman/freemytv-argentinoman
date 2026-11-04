package io.freemytv;

import android.app.Notification;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * Holds the notification access: Android lets an app see the media sessions (what Spotify or YouTube Music is playing) only while
 * it holds notification access, and this service is what that access is granted to. The quick settings panel also
 * lists the notifications through it, when the person opens that list. While connected (always, with the access
 * granted), MediaHistory keeps what plays in YouTube and Spotify for the home screen's "recently" cards, and Netflix's
 * own notifications ("new season of…") for its cards: only Netflix's are read.
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

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        if (!WatchNext.NETFLIX.equals(sbn.getPackageName())) return;
        Notification n = sbn.getNotification();
        Bundle x = n.extras;
        // Not what's playing (its media controls), not an ongoing download, not a group's summary.
        if (x == null || x.containsKey(Notification.EXTRA_MEDIA_SESSION) || (n.flags & Notification.FLAG_ONGOING_EVENT) != 0
                || (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
        String title = str(x.getCharSequence(Notification.EXTRA_TITLE));
        String text = str(x.getCharSequence(Notification.EXTRA_BIG_TEXT));
        if (text.isEmpty()) text = str(x.getCharSequence(Notification.EXTRA_TEXT));
        if (title.isEmpty()) return;
        Bitmap picture = x.getParcelable(Notification.EXTRA_PICTURE);
        if (picture == null) picture = bitmap(n.getLargeIcon());
        MediaHistory.get(this).notice(title, text, picture);
    }

    private static String str(CharSequence s) {
        return s == null ? "" : s.toString().trim();
    }

    private Bitmap bitmap(Icon icon) {
        if (icon == null) return null;
        try {
            Drawable d = icon.loadDrawable(this);
            if (d instanceof BitmapDrawable) return ((BitmapDrawable) d).getBitmap();
            if (d == null || d.getIntrinsicWidth() <= 0 || d.getIntrinsicHeight() <= 0) return null;
            Bitmap b = Bitmap.createBitmap(d.getIntrinsicWidth(), d.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
            d.setBounds(0, 0, b.getWidth(), b.getHeight());
            d.draw(new Canvas(b));
            return b;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
