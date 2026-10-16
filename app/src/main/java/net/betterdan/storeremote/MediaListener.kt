package net.betterdan.storeremote

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService

/**
 * 알림 접근 권한을 받기 위한 서비스.
 * 이 권한이 있어야 다른 음악 앱의 재생 상태를 읽고 제어할 수 있다.
 */
class MediaListener : NotificationListenerService() {
    companion object {
        fun component(c: Context) = ComponentName(c, MediaListener::class.java)

        fun isEnabled(c: Context): Boolean {
            val flat = Settings.Secure.getString(c.contentResolver, "enabled_notification_listeners") ?: return false
            return flat.split(":").any { it == component(c).flattenToString() }
        }
    }
}
