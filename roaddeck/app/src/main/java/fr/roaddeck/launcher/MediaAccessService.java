package fr.roaddeck.launcher;

import android.service.notification.NotificationListenerService;

/** Android grants media-session access through this user-approved service.
 * Notification contents are neither read, stored nor transmitted. */
public final class MediaAccessService extends NotificationListenerService {
    static volatile boolean connected;
    @Override public void onListenerConnected() { connected=true; }
    @Override public void onListenerDisconnected() { connected=false; }
}
