package net.betterdan.storeremote

import android.content.Context
import android.net.Uri

/** 서버 주소·키·비밀번호와 음악 앱 선택을 저장 */
object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    fun url(c: Context): String = sp(c).getString("url", "") ?: ""
    fun key(c: Context): String = sp(c).getString("key", "") ?: ""
    fun secret(c: Context): String = sp(c).getString("secret", "") ?: ""
    fun musicPkg(c: Context): String? = sp(c).getString("music", null)

    fun isConfigured(c: Context) = url(c).isNotEmpty() && key(c).isNotEmpty() && secret(c).isNotEmpty()

    fun setMusicPkg(c: Context, pkg: String) {
        sp(c).edit().putString("music", pkg).apply()
    }

    /** storeremote://setup?u=...&k=...&s=... 형식의 연결 링크를 저장. 성공하면 true */
    fun applySetupLink(c: Context, uri: Uri?): Boolean {
        if (uri == null || uri.scheme != "storeremote" || uri.host != "setup") return false
        val u = uri.getQueryParameter("u") ?: return false
        val k = uri.getQueryParameter("k") ?: return false
        val s = uri.getQueryParameter("s") ?: return false
        if (u.isBlank() || k.isBlank() || s.isBlank()) return false
        sp(c).edit().putString("url", u.trim()).putString("key", k.trim()).putString("secret", s.trim()).apply()
        return true
    }
}
