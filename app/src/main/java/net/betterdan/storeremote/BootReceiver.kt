package net.betterdan.storeremote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 공기계가 재부팅되거나 앱이 업데이트되면 리모컨을 자동으로 다시 켠다 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            if (Prefs.isConfigured(context)) SyncService.start(context)
        }
    }
}
