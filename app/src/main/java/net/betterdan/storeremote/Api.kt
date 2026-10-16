package net.betterdan.storeremote

import android.content.Context
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Supabase RPC 호출 */
object Api {
    fun rpc(c: Context, fn: String, body: JSONObject): String {
        val key = Prefs.key(c)
        val url = URL(Prefs.url(c).trimEnd('/') + "/rest/v1/rpc/" + fn)
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("apikey", key)
            if (key.startsWith("eyJ")) conn.setRequestProperty("Authorization", "Bearer $key")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IOException("서버 응답 $code: ${text.take(120)}")
            return text
        } finally {
            conn.disconnect()
        }
    }
}
