package io.github.bags156.messagecenter;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * Android only tells an app which song is playing (in Spotify, YouTube Music, or any app) if the
 * app has notification access, which it grants to a service like this one. It does nothing with
 * notifications: ⊕ → Song playing only asks Android what's playing, when you tap it.
 */
public final class NowPlayingAccess extends NotificationListenerService {
    @Override public void onNotificationPosted(StatusBarNotification sbn) {}
    @Override public void onNotificationRemoved(StatusBarNotification sbn) {}
}
