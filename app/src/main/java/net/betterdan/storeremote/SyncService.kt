package net.betterdan.storeremote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 2초마다 서버에 공기계 상태를 보고하고, 쌓여 있는 명령을 받아 실행하는 상시 서비스.
 */
class SyncService : Service() {
    companion object {
        private const val CHANNEL = "sync"
        private const val INTERVAL_MS = 2000L

        @Volatile var running = false
        @Volatile var lastResult: String = "아직 연결 전"

        fun start(c: Context) {
            val i = Intent(c, SyncService::class.java)
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
        }

        fun stop(c: Context) {
            c.stopService(Intent(c, SyncService::class.java))
        }
    }

    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private lateinit var player: Player
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val loop = object : Runnable {
        override fun run() {
            try {
                tick()
            } catch (e: Exception) {
                lastResult = "연결 오류: ${e.message}"
            }
            if (running) handler.postDelayed(this, INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        player = Player(this)
        startAsForeground()

        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "storeremote:sync").apply { acquire() }
        @Suppress("DEPRECATION")
        val wifiMode = WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = applicationContext.getSystemService(WifiManager::class.java)
            ?.createWifiLock(wifiMode, "storeremote:wifi")?.apply { acquire() }

        thread = HandlerThread("sync").apply { start() }
        handler = Handler(thread.looper)
        running = true
        handler.post(loop)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running = false
        lastResult = "리모컨 중지됨"
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "리모컨 상태", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("매장 리모컨 작동 중")
            .setContentText("원격 명령을 기다리고 있습니다")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }

    private fun tick() {
        if (!Prefs.isConfigured(this)) {
            lastResult = "연결 정보 없음"
            return
        }
        val c = player.controller()
        val bm = getSystemService(BatteryManager::class.java)
        val body = JSONObject()
        body.put("p_secret", Prefs.secret(this))
        body.put("p_playing", player.isPlaying(c))
        body.put("p_title", player.title(c) ?: JSONObject.NULL)
        body.put("p_artist", player.artist(c) ?: JSONObject.NULL)
        body.put("p_app", player.appLabel(c?.packageName) ?: JSONObject.NULL)
        body.put("p_volume", player.volumePct())
        body.put("p_battery", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
        body.put("p_charging", bm.isCharging)

        val commands = JSONArray(Api.rpc(this, "device_sync", body))
        for (i in 0 until commands.length()) {
            val o = commands.getJSONObject(i)
            execute(o.getString("action"), if (o.isNull("value")) null else o.getInt("value"))
        }
        lastResult = "정상 연결 " + SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date())
    }

    private fun execute(action: String, value: Int?) {
        when (action) {
            "play" -> player.play(handler)
            "pause" -> player.pause()
            "next" -> player.next()
            "prev" -> player.prev()
            "volume" -> if (value != null) player.setVolume(value)
            "vol_up" -> player.adjust(AudioManager.ADJUST_RAISE)
            "vol_down" -> player.adjust(AudioManager.ADJUST_LOWER)
            "launch" -> player.launchApp()
        }
    }
}
