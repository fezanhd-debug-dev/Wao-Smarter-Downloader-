package com.example.downloadertv

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread

class RelayItem(
    val id: String,
    val type: String,
    val name: String,
    val size: Long,
    val text: String,
    val ip: String
)

/** Talks to api.php on the VPS: registers this TV, polls for items, downloads and acknowledges them. */
class Relay(
    private val label: () -> String,
    private val deviceId: () -> String
) {
    companion object {
        const val BASE = "https://iptv-updates.duckdns.org:10066/wao-iptv/downloader/"
        const val SHORT = "iptv-updates.duckdns.org:10066/wao-iptv/downloader/"
    }

    @Volatile var code: String? = null
    @Volatile private var token: String? = null
    @Volatile private var running = false
    @Volatile private var renewNext = false
    private var lastDevices = -1
    private val seen = HashSet<String>()

    var onCode: (() -> Unit)? = null
    /** key is "wait", "linked" (n = linked devices) or "down". */
    var onStatus: ((String, Int) -> Unit)? = null
    var onItem: ((RelayItem) -> Unit)? = null

    fun qrUrl(): String = BASE + "#/send?code=" + (code ?: "")

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun open(url: String, post: String? = null): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.setRequestProperty("User-Agent", "DownloaderTV/1.0")
        if (post != null) {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.outputStream.use { it.write(post.toByteArray()) }
        }
        return c
    }

    private fun json(url: String, post: String? = null): JSONObject {
        val c = open(url, post)
        try {
            val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
            val txt = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return JSONObject(txt)
        } finally {
            c.disconnect()
        }
    }

    fun fetchText(path: String): String {
        val c = open(BASE + path)
        try {
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun register() {
        val body = "type=tv&id=" + enc(deviceId()) + "&name=" + enc(label()) +
            (if (renewNext) "&renew=1" else "")
        val j = json(BASE + "api.php?a=tv_register", body)
        if (!j.optBoolean("ok")) throw IOException(j.optString("error", "register failed"))
        renewNext = false
        token = j.getString("token")
        code = j.getString("code")
        seen.clear()
        lastDevices = -1
        onCode?.invoke()
        onStatus?.invoke("wait", 0)
    }

    private fun poll() {
        val j = json(BASE + "api.php?a=tv_poll&code=" + enc(code!!) + "&token=" + enc(token!!))
        if (!j.optBoolean("ok")) {
            if (j.optString("error") == "bad_token") token = null
            return
        }
        val n = j.optInt("devices", 0)
        if (n != lastDevices) {
            lastDevices = n
            onStatus?.invoke(if (n > 0) "linked" else "wait", n)
        }
        val arr = j.optJSONArray("items") ?: return
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.getString("id")
            if (!seen.add(id)) continue
            onItem?.invoke(
                RelayItem(id, o.optString("type", "file"), o.optString("name"),
                    o.optLong("size"), o.optString("text"), o.optString("ip"))
            )
        }
    }

    fun start() {
        if (running) return
        running = true
        thread(isDaemon = true) {
            while (running) {
                try {
                    if (token == null) register()
                    poll()
                    Thread.sleep(2000)
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    onStatus?.invoke("down", 0)
                    try { Thread.sleep(5000) } catch (_: InterruptedException) { break }
                }
            }
        }
    }

    fun stop() { running = false }

    fun renew() { renewNext = true; token = null }

    fun download(item: RelayItem, dest: File, progress: (Long, Long) -> Unit) {
        val url = BASE + "api.php?a=tv_get&code=" + enc(code!!) + "&token=" + enc(token!!) + "&id=" + enc(item.id)
        val c = open(url)
        try {
            if (c.responseCode !in 200..299) throw IOException("HTTP " + c.responseCode)
            var done = 0L
            var last = 0L
            c.inputStream.use { input ->
                FileOutputStream(dest).use { out ->
                    val buf = ByteArray(65536)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val now = System.currentTimeMillis()
                        if (now - last > 500) { last = now; progress(done, item.size) }
                    }
                }
            }
            progress(done, item.size)
            if (item.size > 0 && done != item.size) throw IOException("Incomplete download")
        } catch (e: Exception) {
            dest.delete()
            throw e
        } finally {
            c.disconnect()
        }
    }

    fun ack(item: RelayItem, ok: Boolean) {
        json(BASE + "api.php?a=tv_ack&code=" + enc(code!!) + "&token=" + enc(token!!) +
            "&id=" + enc(item.id) + "&status=" + (if (ok) "ok" else "rejected"))
    }
}
