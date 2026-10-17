package net.betterdan.storeremote

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 서버에 공기계 상태를 보고하고, 쌓여 있는 명령을 받아 실행하는 상시 서비스.
 *
 * 평소: 공기계를 잠들게 두고, 30초마다 알람으로 잠깐 깨워 한 번 확인한 뒤 다시 재운다.
 * 아이폰 리모컨 페이지를 보고 있을 때: 깨어 있는 상태로 2초마다 확인한다.
 */
class SyncService : Service() {
    companion object {
        private const val CHANNEL = "sync"
        private const val FAST_MS = 2000L
        private const val IDLE_MS = 30000L
        private const val RETRY_MS = 15000L
        // 한 번 깨어 있는 최대 시간. 빠른 모드에서는 매번 연장된다.
        private const val AWAKE_MS = 20000L

        @Volatile var running = false
        @Volatile var lastResult: String = "아직 연결 전"
        @Volatile var instance: SyncService? = null

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
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var alarms: AlarmManager

    private val loop = object : Runnable {
        override fun run() {
            if (!running) return
            val next = try {
                if (tick()) FAST_MS else IDLE_MS
            } catch (e: Exception) {
                lastResult = "연결 오류: ${e.message}"
                RETRY_MS
            }
            if (!running) return
            if (next == FAST_MS) {
                // 리모컨을 보고 있는 동안만 깨어 있음
                wakeLock.acquire(AWAKE_MS)
                handler.postDelayed(this, FAST_MS)
            } else {
                scheduleAlarm(next)
                if (wakeLock.isHeld) wakeLock.release()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        player = Player(this)
        alarms = getSystemService(AlarmManager::class.java)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "storeremote:sync")
            .apply { setReferenceCounted(false) }
        startAsForeground()

        thread = HandlerThread("sync").apply { start() }
        handler = Handler(thread.looper)
        running = true
        wakeUp()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running = false
        instance = null
        lastResult = "리모컨 중지됨"
        alarms.cancel(alarmIntent())
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
        if (wakeLock.isHeld) wakeLock.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** 알람이나 앱 화면에서 호출: 잠깐 깨어 바로 한 번 확인 */
    fun wakeUp() {
        if (!running) return
        wakeLock.acquire(AWAKE_MS)
        handler.removeCallbacks(loop)
        handler.post(loop)
    }

    private fun alarmIntent(): PendingIntent = PendingIntent.getBroadcast(
        this, 1, Intent(this, AlarmReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun scheduleAlarm(delayMs: Long) {
        val at = SystemClock.elapsedRealtime() + delayMs
        val pi = alarmIntent()
        val exactAllowed = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()
        if (exactAllowed) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
        }
    }

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

    /** 서버와 한 번 주고받는다. 아이폰 페이지가 열려 있으면 true(빠른 간격) */
    private fun tick(): Boolean {
        if (!Prefs.isConfigured(this)) {
            lastResult = "연결 정보 없음"
            return false
        }
        val c = player.controller()
        // 음악 앱을 고르지 않았으면 처음 재생된 앱을 자동으로 지정
        if (Prefs.musicPkg(this) == null && c != null && c.packageName != packageName) {
            Prefs.setMusicPkg(this, c.packageName)
        }
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
        body.put("p_output", player.outputName())
        body.put("p_audio_active", player.audioActive())

        val res = JSONObject(Api.rpc(this, "device_sync2", body))
        val commands = res.optJSONArray("commands") ?: JSONArray()
        for (i in 0 until commands.length()) {
            val o = commands.getJSONObject(i)
            execute(o.getString("action"), if (o.isNull("value")) null else o.getInt("value"))
        }
        val fast = res.optBoolean("fast", false) || commands.length() > 0
        lastResult = "정상 연결 " + SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date()) +
            if (fast) " · 빠른 확인" else " · 30초 간격"
        return fast
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
