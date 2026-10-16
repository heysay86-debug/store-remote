package net.betterdan.storeremote

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.view.KeyEvent

/** 음악 앱 실행과 재생·정지·곡 이동·볼륨 제어 */
class Player(private val ctx: Context) {
    private val msm = ctx.getSystemService(MediaSessionManager::class.java)
    private val am = ctx.getSystemService(AudioManager::class.java)

    /** 지정한 음악 앱의 재생 세션을 우선으로, 없으면 가장 최근 세션 */
    fun controller(): MediaController? {
        if (!MediaListener.isEnabled(ctx)) return null
        return try {
            val list = msm.getActiveSessions(MediaListener.component(ctx))
            val pkg = Prefs.musicPkg(ctx)
            list.firstOrNull { it.packageName == pkg } ?: list.firstOrNull()
        } catch (e: SecurityException) {
            null
        }
    }

    /** 지금 재생 세션을 가진 앱 패키지 목록 */
    fun sessionPackages(): List<String> {
        if (!MediaListener.isEnabled(ctx)) return emptyList()
        return try {
            msm.getActiveSessions(MediaListener.component(ctx)).map { it.packageName }.distinct()
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    fun isPlaying(c: MediaController?): Boolean = c?.playbackState?.state == PlaybackState.STATE_PLAYING

    fun title(c: MediaController?): String? = c?.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)

    fun artist(c: MediaController?): String? =
        c?.metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: c?.metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)

    fun appLabel(pkg: String?): String? {
        if (pkg == null) return null
        return try {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            pkg
        }
    }

    private fun key(code: Int) {
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    fun launchApp(): Boolean {
        val pkg = Prefs.musicPkg(ctx) ?: return false
        val intent = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 음악 앱이 꺼져 있으면 실행부터 하고, 몇 초 뒤 재생 */
    fun play(handler: Handler) {
        val pkg = Prefs.musicPkg(ctx)
        val c = controller()
        if (c != null && (pkg == null || c.packageName == pkg)) {
            c.transportControls.play()
            return
        }
        if (!launchApp()) {
            key(KeyEvent.KEYCODE_MEDIA_PLAY)
            return
        }
        handler.postDelayed({ playNow() }, 5000)
        // 앱이 늦게 뜨는 경우를 대비해 한 번 더 확인
        handler.postDelayed({ if (!isPlaying(controller())) playNow() }, 10000)
    }

    private fun playNow() {
        val c = controller()
        if (c != null) c.transportControls.play() else key(KeyEvent.KEYCODE_MEDIA_PLAY)
    }

    fun pause() {
        val c = controller()
        if (c != null) c.transportControls.pause() else key(KeyEvent.KEYCODE_MEDIA_PAUSE)
    }

    fun next() {
        val c = controller()
        if (c != null) c.transportControls.skipToNext() else key(KeyEvent.KEYCODE_MEDIA_NEXT)
    }

    fun prev() {
        val c = controller()
        if (c != null) c.transportControls.skipToPrevious() else key(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
    }

    fun volumePct(): Int {
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return if (max == 0) 0 else am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
    }

    fun setVolume(pct: Int) {
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val v = ((pct.coerceIn(0, 100) * max) + 50) / 100
        am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
    }

    fun adjust(direction: Int) {
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
    }
}
