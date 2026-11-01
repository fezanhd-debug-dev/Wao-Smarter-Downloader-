package com.example.downloadertv

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.Collections
import java.util.Locale
import java.util.UUID
import kotlin.concurrent.thread

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

class MainActivity : Activity() {

    private lateinit var content: FrameLayout
    private val prefs: SharedPreferences by lazy { getSharedPreferences("wao", MODE_PRIVATE) }
    private val downloadsDir: File by lazy {
        (getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir).also { it.mkdirs() }
    }
    private val incomingDir: File by lazy { File(downloadsDir, ".incoming").also { it.mkdirs() } }

    private val icons = listOf("⬇", "🌐", "📁", "📲", "📡", "📱", "⚙")
    private val names = listOf("Downloader", "Browser", "Files", "Send Apps", "Local Send", "Apps", "Settings")
    private val sideViews = ArrayList<TextView>()
    private var cur = 0

    private var webView: WebView? = null
    private var dlBar: ProgressBar? = null
    private var dlText: TextView? = null
    private var eventView: TextView? = null
    private var downloading = false
    private val events = ArrayList<String>()

    private var serverSocket: ServerSocket? = null
    @Volatile private var serverPort = 0
    private var pairCode = genCode()

    private val deviceName: String
        get() = prefs.getString("name", null) ?: "Living Room TV"

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        content = findViewById(R.id.content)
        downloadsDir.exists()
        buildSidebar()

        val hb = findViewById<LinearLayout>(R.id.headerButtons)
        hb.addView(btn("⚙ Settings") { show(6) })
        hb.addView(btn("Network") { safeStart(Intent(Settings.ACTION_WIFI_SETTINGS)) },
            lp(WRAP, WRAP).apply { marginStart = dp(8) })

        startServer()
        show(0)
    }

    override fun onResume() {
        super.onResume()
        findViewById<TextView>(R.id.txtWifi).text = "IP: " + localIp()
    }

    override fun onDestroy() {
        try { serverSocket?.close() } catch (_: Exception) {}
        webView?.destroy()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val w = webView
        when {
            cur == 1 && w != null && w.canGoBack() -> w.goBack()
            cur != 0 -> show(0)
            else -> super.onBackPressed()
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun lp(w: Int, h: Int, wt: Float = 0f) = LinearLayout.LayoutParams(w, h, wt)
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()

    private fun safeStart(i: Intent) {
        try { startActivity(i) } catch (e: ActivityNotFoundException) { toast("Not available on this device") }
    }

    private fun rr(fill: Int, stroke: Int): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(12).toFloat()
        if (stroke != 0) setStroke(dp(3), stroke)
    }

    private fun tv(t: String, size: Float = 16f, color: Int = Color.WHITE, bold: Boolean = false) =
        TextView(this).apply {
            text = t
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(4))
        }

    private fun btn(text: String, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 18f
        gravity = Gravity.CENTER
        setPadding(dp(20), dp(12), dp(20), dp(12))
        isFocusable = true
        isClickable = true
        val sl = StateListDrawable()
        sl.addState(intArrayOf(android.R.attr.state_focused), rr(Color.parseColor("#FF6B35"), Color.parseColor("#FFD54F")))
        sl.addState(intArrayOf(), rr(Color.parseColor("#3D3D3D"), 0))
        background = sl
        setOnClickListener { onClick() }
    }

    private fun editText(hint: String, value: String = ""): EditText = EditText(this).apply {
        this.hint = hint
        setText(value)
        setTextColor(Color.WHITE)
        setHintTextColor(Color.GRAY)
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        imeOptions = EditorInfo.IME_ACTION_GO
    }

    private fun page(title: String, sub: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(16), dp(24), dp(16))
        setBackgroundColor(Color.parseColor("#CC000000"))
        addView(tv(title, 26f, Color.WHITE, true))
        addView(tv(sub, 15f, Color.parseColor("#BBBBBB")))
    }

    private fun fmt(b: Long): String {
        if (b < 1024) return "$b B"
        val u = arrayOf("KB", "MB", "GB", "TB")
        var v = b.toDouble()
        var i = -1
        while (v >= 1024 && i < 3) { v /= 1024; i++ }
        return String.format(Locale.US, "%.1f %s", v, u[i])
    }

    private fun sanitize(n: String): String =
        n.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().trimStart('.').ifEmpty { "file" }

    private fun uniqueFile(name: String): File {
        var f = File(downloadsDir, name)
        if (!f.exists()) return f
        val base = name.substringBeforeLast('.', name)
        val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
        var i = 1
        while (f.exists()) { f = File(downloadsDir, "$base ($i)$ext"); i++ }
        return f
    }

    private fun genCode(): String {
        val r = SecureRandom()
        return (1..4).joinToString("-") { String.format(Locale.US, "%04d", r.nextInt(10000)) }
    }

    private fun localIp(): String {
        try {
            for (ni in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp || ni.isLoopback) continue
                for (a in Collections.list(ni.inetAddresses)) {
                    if (a is Inet4Address && !a.isLoopbackAddress) return a.hostAddress ?: ""
                }
            }
        } catch (_: Exception) {}
        return "0.0.0.0"
    }

    private fun qr(text: String, size: Int): Bitmap {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) for (y in 0 until size) {
            bmp.setPixel(x, y, if (m.get(x, y)) Color.BLACK else Color.WHITE)
        }
        return bmp
    }

    private fun addEvent(m: String) {
        runOnUiThread {
            events.add(0, m)
            while (events.size > 6) events.removeAt(events.size - 1)
            eventView?.text = events.joinToString("\n")
        }
    }

    // ------------------------------------------------------------------ sidebar / tabs

    private fun buildSidebar() {
        val sb = findViewById<LinearLayout>(R.id.sidebar)
        icons.forEachIndexed { i, ic ->
            val t = TextView(this).apply {
                text = ic
                textSize = 24f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                isFocusable = true
                isClickable = true
                contentDescription = names[i]
                setBackgroundResource(R.drawable.bg_active_icon)
                setOnClickListener { show(i) }
            }
            sb.addView(t, LinearLayout.LayoutParams(dp(60), dp(60)).apply {
                topMargin = dp(8); bottomMargin = dp(8)
            })
            sideViews.add(t)
        }
    }

    private fun show(i: Int) {
        cur = i
        val old = webView
        webView = null; dlBar = null; dlText = null; eventView = null
        content.removeAllViews()
        old?.destroy()
        sideViews.forEachIndexed { k, v -> v.isSelected = (k == i) }
        val v = when (i) {
            0 -> tabDownloader()
            1 -> tabBrowser()
            2 -> tabFiles()
            3 -> tabSend(true)
            4 -> tabSend(false)
            5 -> tabApps()
            else -> tabSettings()
        }
        content.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    // ------------------------------------------------------------------ Downloader tab

    private fun tabDownloader(): View {
        val p = page("Downloader", "Enter a direct link to download a file or an APK")
        val et = editText("https://example.com/app.apk")
        et.setOnEditorActionListener { _, _, _ -> startDownload(et.text.toString()); true }
        p.addView(et, lp(MATCH, WRAP).apply { topMargin = dp(16) })
        p.addView(btn("Download") { startDownload(et.text.toString()) },
            lp(WRAP, WRAP).apply { topMargin = dp(8) })
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        dlBar = bar
        p.addView(bar, lp(MATCH, WRAP).apply { topMargin = dp(20) })
        val t = tv(if (downloading) "Downloading…" else "Idle", 16f, Color.parseColor("#9FE6A0"))
        dlText = t
        p.addView(t)
        p.addView(tv("Saved to: " + downloadsDir.absolutePath, 13f, Color.GRAY))
        return p
    }

    private fun startDownload(raw: String) {
        var url = raw.trim()
        if (url.isEmpty()) { toast("Enter a link first"); return }
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
        if (downloading) { toast("A download is already running"); return }
        downloading = true
        dlText?.text = "Starting…"
        dlBar?.progress = 0
        toast("Download started")
        val target = url
        thread {
            try {
                val f = fetch(target) { done, total ->
                    runOnUiThread {
                        if (total > 0) {
                            dlBar?.progress = (done * 100 / total).toInt()
                            dlText?.text = fmt(done) + " / " + fmt(total)
                        } else {
                            dlText?.text = fmt(done)
                        }
                    }
                }
                runOnUiThread {
                    downloading = false
                    dlBar?.progress = 100
                    dlText?.text = "Saved: " + f.name
                    afterDownload(f)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    downloading = false
                    dlText?.text = "Failed: " + e.message
                    toast("Download failed: " + e.message)
                }
            }
        }
    }

    private fun openConn(u: String): HttpURLConnection =
        (URL(u).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 15000
            readTimeout = 30000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 11; TV) DownloaderTV/1.0")
        }

    private fun fetch(urlStr: String, progress: (Long, Long) -> Unit): File {
        var conn = openConn(urlStr)
        var hops = 0
        while (conn.responseCode in 300..399) {
            val loc = conn.getHeaderField("Location") ?: break
            if (++hops > 8) throw IOException("Too many redirects")
            val next = URL(URL(conn.url.toString()), loc).toString()
            conn.disconnect()
            conn = openConn(next)
        }
        if (conn.responseCode !in 200..299) throw IOException("HTTP " + conn.responseCode)

        val cd = conn.getHeaderField("Content-Disposition")
        val fromHeader = cd?.let {
            Regex("filename\\*?=(?:UTF-8'')?\"?([^\";]+)\"?", RegexOption.IGNORE_CASE)
                .find(it)?.groupValues?.get(1)
        }
        val raw = if (fromHeader.isNullOrBlank())
            URLUtil.guessFileName(conn.url.toString(), cd, conn.contentType)
        else Uri.decode(fromHeader)
        val dest = uniqueFile(sanitize(raw))
        val total = conn.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L

        var done = 0L
        var last = 0L
        try {
            conn.inputStream.use { input ->
                FileOutputStream(dest).use { out ->
                    val buf = ByteArray(65536)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val now = System.currentTimeMillis()
                        if (now - last > 250) { last = now; progress(done, total) }
                    }
                }
            }
        } catch (e: Exception) {
            dest.delete()
            throw e
        } finally {
            conn.disconnect()
        }
        progress(done, total)
        return dest
    }

    private fun afterDownload(f: File) {
        if (isFinishing) return
        val isApk = f.name.endsWith(".apk", true)
        AlertDialog.Builder(this)
            .setTitle("Download complete")
            .setMessage(f.name + "\n" + fmt(f.length()))
            .setPositiveButton(if (isApk) "Install" else "Open") { _, _ -> openFile(f) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun openFile(f: File) {
        val isApk = f.name.endsWith(".apk", true)
        if (isApk && Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            toast("Allow \"Install unknown apps\" for Downloader TV, then try again")
            safeStart(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            val mime = if (isApk) "application/vnd.android.package-archive"
            else MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase(Locale.US)) ?: "*/*"
            val i = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        } catch (e: Exception) {
            toast("Cannot open file: " + e.message)
        }
    }

    // ------------------------------------------------------------------ Browser tab

    private fun normalizeUrl(s: String): String {
        val t = s.trim()
        return when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            t.contains(" ") || !t.contains(".") -> "https://www.google.com/search?q=" + Uri.encode(t)
            else -> "https://$t"
        }
    }

    private fun tabBrowser(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#CC000000"))
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val et = editText("Search or type a URL", "https://www.google.com")
        val wv = WebView(this)
        webView = wv
        bar.addView(btn("◀") { if (wv.canGoBack()) wv.goBack() })
        bar.addView(et, lp(0, WRAP, 1f).apply { marginStart = dp(8); marginEnd = dp(8) })
        bar.addView(btn("Go") { wv.loadUrl(normalizeUrl(et.text.toString())) })
        bar.addView(btn("⟳") { wv.reload() }, lp(WRAP, WRAP).apply { marginStart = dp(8) })
        et.setOnEditorActionListener { _, _, _ -> wv.loadUrl(normalizeUrl(et.text.toString())); true }
        root.addView(bar, lp(MATCH, WRAP))

        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.useWideViewPort = true
        wv.settings.loadWithOverviewMode = true
        wv.settings.mediaPlaybackRequiresUserGesture = false
        wv.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                et.setText(url ?: "")
            }
        }
        wv.setDownloadListener { url, _, _, _, _ -> startDownload(url) }
        root.addView(wv, lp(MATCH, 0, 1f).apply { topMargin = dp(8) })
        wv.loadUrl("https://www.google.com")
        return root
    }

    // ------------------------------------------------------------------ Files tab

    private fun tabFiles(): View {
        val p = page("Files", "Downloaded and received files — click a file for options")
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(btn("Refresh") { show(2) })
        row.addView(btn("Delete all") {
            AlertDialog.Builder(this).setTitle("Delete all files?")
                .setPositiveButton("Delete") { _, _ ->
                    downloadsDir.listFiles()?.forEach { if (it.isFile) it.delete() }
                    show(2)
                }
                .setNegativeButton("Cancel", null).show()
        }, lp(WRAP, WRAP).apply { marginStart = dp(8) })
        p.addView(row, lp(WRAP, WRAP).apply { topMargin = dp(10) })

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val files = downloadsDir.listFiles()
            ?.filter { it.isFile && !it.name.startsWith(".") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
        if (files.isEmpty()) list.addView(tv("No files yet.", 16f, Color.GRAY))
        for (f in files) {
            val b = btn(f.name + "    •    " + fmt(f.length())) { fileDialog(f) }
            b.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            list.addView(b, lp(MATCH, WRAP).apply { topMargin = dp(6) })
        }
        p.addView(ScrollView(this).apply { addView(list) }, lp(MATCH, 0, 1f).apply { topMargin = dp(8) })
        return p
    }

    private fun fileDialog(f: File) {
        val isApk = f.name.endsWith(".apk", true)
        AlertDialog.Builder(this).setTitle(f.name)
            .setItems(arrayOf(if (isApk) "Install" else "Open", "Delete")) { _, which ->
                if (which == 0) openFile(f) else { f.delete(); show(2) }
            }.show()
    }

    // ------------------------------------------------------------------ Apps tab

    private fun tabApps(): View {
        val p = page("Apps", "Installed apps — click one to open, uninstall or see info")
        val pm = packageManager
        val apps = pm.getInstalledApplications(0)
            .filter {
                pm.getLeanbackLaunchIntentForPackage(it.packageName) != null ||
                    pm.getLaunchIntentForPackage(it.packageName) != null
            }
            .sortedBy { pm.getApplicationLabel(it).toString().lowercase(Locale.getDefault()) }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (a in apps) {
            val label = pm.getApplicationLabel(a).toString()
            val b = btn(label) { appDialog(a.packageName, label) }
            try {
                val ic = pm.getApplicationIcon(a)
                ic.setBounds(0, 0, dp(36), dp(36))
                b.setCompoundDrawables(ic, null, null, null)
                b.compoundDrawablePadding = dp(16)
            } catch (_: Exception) {}
            b.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            list.addView(b, lp(MATCH, WRAP).apply { topMargin = dp(6) })
        }
        p.addView(ScrollView(this).apply { addView(list) }, lp(MATCH, 0, 1f).apply { topMargin = dp(8) })
        return p
    }

    private fun appDialog(pkg: String, label: String) {
        AlertDialog.Builder(this).setTitle(label)
            .setItems(arrayOf("Open", "Uninstall", "App info")) { _, which ->
                when (which) {
                    0 -> {
                        val i = packageManager.getLeanbackLaunchIntentForPackage(pkg)
                            ?: packageManager.getLaunchIntentForPackage(pkg)
                        if (i != null) safeStart(i) else toast("Cannot open this app")
                    }
                    1 -> safeStart(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")))
                    else -> safeStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
                }
            }.show()
    }

    // ------------------------------------------------------------------ Settings tab

    private fun tabSettings(): View {
        val p = page("Settings", "Device name, pairing code and system shortcuts")
        val et = editText("Device name", deviceName)
        et.inputType = InputType.TYPE_CLASS_TEXT
        p.addView(et, lp(MATCH, WRAP).apply { topMargin = dp(12) })
        p.addView(btn("Save device name") {
            prefs.edit().putString("name", et.text.toString().trim().ifEmpty { "Living Room TV" }).apply()
            toast("Saved")
        }, lp(WRAP, WRAP).apply { topMargin = dp(6) })
        p.addView(btn("Generate new pairing code") {
            pairCode = genCode(); toast("New code: $pairCode")
        }, lp(WRAP, WRAP).apply { topMargin = dp(10) })
        p.addView(btn("Open Wi-Fi settings") { safeStart(Intent(Settings.ACTION_WIFI_SETTINGS)) },
            lp(WRAP, WRAP).apply { topMargin = dp(10) })
        p.addView(btn("Open Android settings") { safeStart(Intent(Settings.ACTION_SETTINGS)) },
            lp(WRAP, WRAP).apply { topMargin = dp(10) })
        p.addView(btn("Allow installing unknown apps") {
            safeStart(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
        }, lp(WRAP, WRAP).apply { topMargin = dp(10) })
        p.addView(tv("IP: " + localIp() + "   Port: " + serverPort, 14f, Color.LTGRAY).apply { setPadding(0, dp(20), 0, 0) })
        p.addView(tv("Download folder: " + downloadsDir.absolutePath, 14f, Color.LTGRAY))
        p.addView(tv("Version 1.0", 14f, Color.LTGRAY))
        return ScrollView(this).apply { addView(p) }
    }

    // ------------------------------------------------------------------ Send Apps / Local Send tabs

    private fun tabSend(sendApps: Boolean): View {
        val ip = localIp()
        val url = "http://$ip:$serverPort"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#CC000000"))
        }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(16), dp(16))
        }
        left.addView(tv(if (sendApps) "Send Apps From Any Device" else "Local Send", 26f, Color.WHITE, true))
        left.addView(tv(
            if (sendApps) "Phone, tablet or PC on the same Wi-Fi — only a web browser is needed."
            else "Big files over your own Wi-Fi — no internet, no size limit.",
            15f, Color.parseColor("#BBBBBB")))
        root.addView(left, lp(0, MATCH, 1f))

        if (serverPort == 0) {
            left.addView(tv("Server is not running. Connect to Wi-Fi and reopen this tab.", 18f, Color.YELLOW))
            return root
        }

        left.addView(tv("1.  On the other device, open  $url", 18f).apply { setPadding(0, dp(16), 0, dp(4)) })
        left.addView(tv("2.  Type this code — or scan the QR", 18f))
        left.addView(tv("3.  Send a file or a message. You approve everything here.", 18f))

        val box = TextView(this).apply {
            text = pairCode
            textSize = 34f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(24), dp(20), dp(24), dp(20))
            setBackgroundResource(R.drawable.bg_code_box)
        }
        left.addView(box, lp(WRAP, WRAP).apply { topMargin = dp(20) })
        if (!sendApps) {
            left.addView(tv("This TV: $deviceName", 16f, Color.parseColor("#FFD54F")).apply { setPadding(0, dp(12), 0, 0) })
            left.addView(tv("Visible only on this Wi-Fi network · saves to Downloads", 13f, Color.GRAY))
        }
        val ev = tv(
            if (events.isEmpty()) "Waiting for a device to connect…" else events.joinToString("\n"),
            16f, Color.parseColor("#9FE6A0"))
        eventView = ev
        left.addView(ev, lp(WRAP, WRAP).apply { topMargin = dp(20) })

        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(24), dp(24), dp(16))
        }
        val iv = ImageView(this).apply {
            setImageBitmap(qr("$url/?code=$pairCode", 400))
            scaleType = ImageView.ScaleType.FIT_XY
            setBackgroundColor(Color.WHITE)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        right.addView(iv, lp(dp(220), dp(220)))
        right.addView(tv("Scan with your phone camera", 14f, Color.LTGRAY))
        root.addView(right, lp(WRAP, MATCH))
        return root
    }

    // ------------------------------------------------------------------ mini web server

    private fun startServer() {
        thread(isDaemon = true) {
            val ss = try { ServerSocket(53317) } catch (e: Exception) {
                try { ServerSocket(0) } catch (e2: Exception) { null }
            } ?: return@thread
            serverSocket = ss
            serverPort = ss.localPort
            runOnUiThread { if (cur == 3 || cur == 4) show(cur) }
            while (!ss.isClosed) {
                val s = try { ss.accept() } catch (e: IOException) { break }
                thread(isDaemon = true) { handle(s) }
            }
        }
    }

    private fun readLine(ins: InputStream): String? {
        val sb = ByteArrayOutputStream()
        while (true) {
            val b = ins.read()
            if (b < 0) return if (sb.size() == 0) null else sb.toString("UTF-8")
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.write(b)
        }
        return sb.toString("UTF-8")
    }

    private fun respond(out: OutputStream, status: String, ctype: String, body: ByteArray) {
        val head = "HTTP/1.1 $status\r\nContent-Type: $ctype\r\nContent-Length: ${body.size}\r\n" +
            "Connection: close\r\nAccess-Control-Allow-Origin: *\r\n\r\n"
        out.write(head.toByteArray())
        out.write(body)
        out.flush()
    }

    private fun codeOk(c: String?): Boolean =
        c != null && c.filter { it.isDigit() } == pairCode.filter { it.isDigit() }

    private fun handle(sock: Socket) {
        try {
            sock.soTimeout = 120000
            val ins = BufferedInputStream(sock.getInputStream())
            val out = sock.getOutputStream()
            val reqLine = readLine(ins) ?: return
            val parts = reqLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val target = parts[1]
            val headers = HashMap<String, String>()
            while (true) {
                val l = readLine(ins) ?: break
                if (l.isEmpty()) break
                val i = l.indexOf(':')
                if (i > 0) headers[l.substring(0, i).trim().lowercase(Locale.US)] = l.substring(i + 1).trim()
            }
            val path = target.substringBefore('?')
            val query = target.substringAfter('?', "")
            val ip = sock.inetAddress.hostAddress ?: "?"

            when {
                method == "GET" && path == "/" -> {
                    val code = query.split("&").firstOrNull { it.startsWith("code=") }
                        ?.substringAfter("=")?.filter { it.isDigit() || it == '-' } ?: ""
                    val html = WEB_PAGE
                        .replace("%%NAME%%", TextUtils.htmlEncode(deviceName))
                        .replace("%%CODE%%", code)
                    addEvent("Device connected: $ip")
                    respond(out, "200 OK", "text/html; charset=utf-8", html.toByteArray())
                }
                method == "GET" && path == "/ping" ->
                    respond(out, "200 OK", "application/json",
                        ("{\"name\":\"" + deviceName.replace("\"", "") + "\"}").toByteArray())
                method == "POST" && path == "/upload" -> handleUpload(ins, out, headers, ip)
                method == "POST" && path == "/message" -> handleMessage(ins, out, headers, ip)
                else -> respond(out, "404 Not Found", "text/plain", "Not found".toByteArray())
            }
        } catch (_: Exception) {
        } finally {
            try { sock.close() } catch (_: Exception) {}
        }
    }

    private fun handleUpload(ins: InputStream, out: OutputStream, h: Map<String, String>, ip: String) {
        if (!codeOk(h["x-code"])) {
            respond(out, "403 Forbidden", "text/plain", "Wrong pairing code".toByteArray()); return
        }
        val len = h["content-length"]?.toLongOrNull() ?: -1L
        if (len < 0) { respond(out, "411 Length Required", "text/plain", "No length".toByteArray()); return }
        val fname = sanitize(URLDecoder.decode(h["x-filename"] ?: "file", "UTF-8"))
        val tmp = File(incomingDir, UUID.randomUUID().toString())
        try {
            FileOutputStream(tmp).use { fos ->
                val buf = ByteArray(65536)
                var left = len
                while (left > 0) {
                    val n = ins.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) throw IOException("Connection lost")
                    fos.write(buf, 0, n)
                    left -= n
                }
            }
        } catch (e: Exception) {
            tmp.delete(); throw e
        }
        runOnUiThread { askAccept(tmp, fname, len, ip) }
        respond(out, "200 OK", "text/plain", "Sent! Waiting for approval on the TV…".toByteArray())
    }

    private fun handleMessage(ins: InputStream, out: OutputStream, h: Map<String, String>, ip: String) {
        if (!codeOk(h["x-code"])) {
            respond(out, "403 Forbidden", "text/plain", "Wrong pairing code".toByteArray()); return
        }
        val len = h["content-length"]?.toIntOrNull() ?: -1
        if (len < 0 || len > 100_000) { respond(out, "413 Too Large", "text/plain", "Bad size".toByteArray()); return }
        val buf = ByteArray(len)
        var off = 0
        while (off < len) {
            val r = ins.read(buf, off, len - off)
            if (r < 0) break
            off += r
        }
        val text = String(buf, 0, off, Charsets.UTF_8)
        runOnUiThread { askMessage(text, ip) }
        respond(out, "200 OK", "text/plain", "Message sent to the TV".toByteArray())
    }

    private fun askAccept(tmp: File, name: String, size: Long, ip: String) {
        if (isFinishing) { tmp.delete(); return }
        AlertDialog.Builder(this)
            .setTitle("Incoming file")
            .setMessage(name + "\n" + fmt(size) + "\nfrom " + ip)
            .setCancelable(false)
            .setPositiveButton("Accept") { _, _ ->
                val dest = uniqueFile(name)
                if (!tmp.renameTo(dest)) { tmp.copyTo(dest, true); tmp.delete() }
                addEvent("Received: " + dest.name)
                afterDownload(dest)
            }
            .setNegativeButton("Reject") { _, _ ->
                tmp.delete(); addEvent("Rejected: $name")
            }
            .show()
    }

    private fun askMessage(text: String, ip: String) {
        if (isFinishing) return
        addEvent("Message from $ip")
        val t = text.trim()
        val isLink = t.startsWith("http://") || t.startsWith("https://")
        val b = AlertDialog.Builder(this).setTitle("Message from $ip").setMessage(t)
        if (isLink) {
            b.setPositiveButton("Download") { _, _ -> show(0); startDownload(t) }
            b.setNeutralButton("Open in Browser") { _, _ ->
                show(1); webView?.loadUrl(t)
            }
            b.setNegativeButton("Close", null)
        } else {
            b.setPositiveButton("OK", null)
        }
        b.show()
    }

    companion object {
        private const val WEB_PAGE = """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Send to TV</title>
<style>
body{font-family:sans-serif;background:#140c08;color:#fff;max-width:520px;margin:0 auto;padding:20px}
h2{color:#ff8a50}
input,textarea,button{width:100%;box-sizing:border-box;padding:14px;margin:8px 0;border-radius:10px;border:1px solid #555;background:#222;color:#fff;font-size:16px}
button{background:#ff6b35;border:0;font-weight:bold}
progress{width:100%}
#log{white-space:pre-wrap;color:#9f9;margin-top:10px}
</style></head><body>
<h2>Send to %%NAME%%</h2>
<input id="code" placeholder="Pairing code shown on the TV" value="%%CODE%%" inputmode="numeric">
<input id="file" type="file">
<button onclick="sendFile()">Send file</button>
<progress id="p" value="0" max="100"></progress>
<textarea id="msg" rows="3" placeholder="Message or link (https://...)"></textarea>
<button onclick="sendMsg()">Send message / link</button>
<div id="log"></div>
<script>
function log(t){document.getElementById('log').textContent=t}
function sendFile(){
  var f=document.getElementById('file').files[0];
  if(!f){log('Choose a file first');return}
  var x=new XMLHttpRequest();
  x.open('POST','/upload');
  x.setRequestHeader('X-Code',document.getElementById('code').value);
  x.setRequestHeader('X-Filename',encodeURIComponent(f.name));
  x.upload.onprogress=function(e){if(e.lengthComputable){document.getElementById('p').value=e.loaded*100/e.total}};
  x.onload=function(){log(x.responseText)};
  x.onerror=function(){log('Connection failed')};
  x.send(f);
}
function sendMsg(){
  var m=document.getElementById('msg').value;
  if(!m){log('Type a message first');return}
  var x=new XMLHttpRequest();
  x.open('POST','/message');
  x.setRequestHeader('X-Code',document.getElementById('code').value);
  x.onload=function(){log(x.responseText)};
  x.onerror=function(){log('Connection failed')};
  x.send(m);
}
</script></body></html>"""
    }
}
