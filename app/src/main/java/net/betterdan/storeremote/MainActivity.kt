package net.betterdan.storeremote

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** 공기계에서 한 번만 하는 설정 화면 */
class MainActivity : Activity() {
    private lateinit var info: TextView
    private val ui = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        handleSetupIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleSetupIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        ui.post(refresher)
    }

    override fun onPause() {
        ui.removeCallbacks(refresher)
        super.onPause()
    }

    private fun handleSetupIntent(intent: Intent?) {
        if (intent?.data == null) return
        applyLink(intent.data)
    }

    private fun applyLink(uri: Uri?) {
        if (Prefs.applySetupLink(this, uri)) {
            Toast.makeText(this, "연결 정보를 저장했습니다", Toast.LENGTH_SHORT).show()
            SyncService.start(this)
        } else {
            Toast.makeText(this, "연결 링크가 올바르지 않습니다", Toast.LENGTH_SHORT).show()
        }
        refresh()
    }

    private fun buildUi() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        col.addView(TextView(this).apply {
            text = "매장 음악 리모컨"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
        })
        info = TextView(this).apply {
            textSize = 15f
            setPadding(0, pad, 0, pad)
            setLineSpacing(0f, 1.3f)
        }
        col.addView(info)

        fun button(label: String, action: () -> Unit) {
            col.addView(Button(this).apply {
                text = label
                isAllCaps = false
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        button("1. 연결 정보 붙여넣기") { pasteLink() }
        button("2. 알림 접근 허용") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        button("3. 다른 앱 위에 표시 허용") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        button("4. 배터리 최적화 제외") {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
        button("5. 음악 앱 선택") { chooseMusicApp() }
        button("리모컨 시작") {
            SyncService.start(this)
            refresh()
        }
        button("리모컨 중지") {
            SyncService.stop(this)
            refresh()
        }

        setContentView(ScrollView(this).apply { addView(col) })
    }

    private fun pasteLink() {
        val cm = getSystemService(ClipboardManager::class.java)
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
        if (text.isNullOrEmpty()) {
            Toast.makeText(this, "복사된 연결 링크가 없습니다", Toast.LENGTH_SHORT).show()
            return
        }
        applyLink(Uri.parse(text))
    }

    private fun chooseMusicApp() {
        val player = Player(this)
        // 지금 재생 세션이 있는 앱을 맨 위에
        val playing = player.sessionPackages()
            .map { it to "▶ " + (player.appLabel(it) ?: it) }
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val installed = packageManager.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.packageName to it.loadLabel(packageManager).toString() }
            .sortedBy { it.second }
        val apps = (playing + installed)
            .filter { it.first != packageName }
            .distinctBy { it.first }
        AlertDialog.Builder(this)
            .setTitle("매장에서 틀 음악 앱")
            .setItems(apps.map { it.second }.toTypedArray()) { _, which ->
                Prefs.setMusicPkg(this, apps[which].first)
                refresh()
            }
            .show()
    }

    private fun refresh() {
        val pm = getSystemService(PowerManager::class.java)
        fun mark(ok: Boolean) = if (ok) "완료" else "필요"
        val music = Prefs.musicPkg(this)?.let { Player(this).appLabel(it) } ?: "선택 안 함"
        info.text = buildString {
            appendLine("연결 정보: ${mark(Prefs.isConfigured(this@MainActivity))}")
            appendLine("알림 접근: ${mark(MediaListener.isEnabled(this@MainActivity))}")
            appendLine("다른 앱 위에 표시: ${mark(Settings.canDrawOverlays(this@MainActivity))}")
            appendLine("배터리 최적화 제외: ${mark(pm.isIgnoringBatteryOptimizations(packageName))}")
            appendLine("음악 앱: $music")
            appendLine()
            append("리모컨: ")
            append(if (SyncService.running) SyncService.lastResult else "꺼져 있음")
        }
    }
}
