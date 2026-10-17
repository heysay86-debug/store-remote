package net.betterdan.storeremote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 30초마다 울리는 알람: 잠든 공기계를 잠깐 깨워 서버를 한 번 확인하게 한다 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val service = SyncService.instance
        if (service != null) {
            service.wakeUp()
        } else if (Prefs.isConfigured(context)) {
            SyncService.start(context)
        }
    }
}
