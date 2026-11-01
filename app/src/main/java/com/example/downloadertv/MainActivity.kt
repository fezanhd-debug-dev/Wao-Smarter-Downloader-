package com.example.downloadertv

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.StyleSpan
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
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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

    private val orange = Color.parseColor("#E8632B")
    private val gray = Color.parseColor("#B9B9B9")

    private val tabIcons = listOf(
        IconPaths.DOWNLOAD, IconPaths.GLOBE, IconPaths.USB, IconPaths.SEND,
        IconPaths.NETWORK, IconPaths.APPS, IconPaths.ANDROID, IconPaths.SETTINGS
    )
    private val tabNames = listOf(
        "Downloader", "Browser", "USB & Storage", "Send Apps",
        "Local Send", "Apps", "APK Manager", "Settings"
    )
    private val sideViews = ArrayList<ImageView>()
    private var cur = 0

    private var webView: WebView? = null
    private var dlBar: ProgressBar? = null
    private var dlText: TextView? = null
    private var eventView: TextView? = null
    private var deviceBox: LinearLayout? = null
    private var lastEvent: String? = null
    private val devices = LinkedHashMap<String, Long>()
    private var downloading = false
    private var browseDir: File? = null

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
        content.background = rr(Color.parseColor("#E6121010"), 0, 26)
        downloadsDir.exists()

        val small = resources.configuration.screenHeightDp < 420
        findViewById<TextView>(R.id.hint).visibility = if (small) View.GONE else View.VISIBLE

        buildSidebar()
        buildHeader()
        startServer()
        show(0)
    }

    override fun onResume() {
        super.onResume()
        updateStatusIcons()
        if (cur == 2) show(2)
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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (cur == 2) show(2)
    }

    // ------------------------------------------------------------------ small helpers

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun lp(w: Int, h: Int, wt: Float = 0f) = LinearLayout.LayoutParams(w, h, wt)
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()

    private fun safeStart(i: Intent) {
        try { startActivity(i) } catch (e: ActivityNotFoundException) { toast("Not available on this device") }
    }

    private fun rr(fill: Int, stroke: Int = 0, radius: Int = 12): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        if (stroke != 0) setStroke(dp(3), stroke)
    }

    private fun oval(fill: Int, stroke: Int = 0): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
        if (stroke != 0) setStroke(dp(3), stroke)
    }

    private fun focusBg(normal: Int, radius: Int = 12): StateListDrawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), rr(Color.parseColor("#33FFB07A"), Color.parseColor("#FFD54F"), radius))
        addState(intArrayOf(), rr(normal, 0, radius))
    }

    private fun tv(t: String, size: Float = 16f, color: Int = Color.WHITE, bold: Boolean = false) =
        TextView(this).apply {
            text = t
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(3), 0, dp(3))
        }

    private fun btn(label: String, primary: Boolean = false, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 17f
        gravity = Gravity.CENTER
        setPadding(dp(20), dp(11), dp(20), dp(11))
        isFocusable = true
        isClickable = true
        background = focusBg(if (primary) orange else Color.parseColor("#4D4D4D"))
        setOnClickListener { onClick() }
    }

    private fun field(hintText: String, value: String = ""): EditText = EditText(this).apply {
        hint = hintText
        setText(value)
        setTextColor(Color.WHITE)
        setHintTextColor(Color.parseColor("#888888"))
        textSize = 18f
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        imeOptions = EditorInfo.IME_ACTION_GO
        setPadding(dp(18), dp(13), dp(18), dp(13))
        background = focusBg(Color.parseColor("#33FFFFFF"))
    }

    private fun panel(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(28), dp(20), dp(28), dp(14))
    }

    private fun scroll(v: View): ScrollView = ScrollView(this).apply {
        isFillViewport = true
        addView(v, ViewGroup.LayoutParams(MATCH, MATCH))
    }

    private fun pageHead(path: String, title: String, sub: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val ic = ImageView(this).apply {
            setImageDrawable(IconDrawable(path))
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = oval(Color.parseColor("#33FFFFFF"))
        }
        row.addView(ic, lp(dp(56), dp(56)))
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
        }
        col.addView(tv(title, 24f, Color.WHITE, true))
        col.addView(tv(sub, 13f, gray))
        row.addView(col)
        return row
    }

    private fun boldPart(prefix: String, strong: String): CharSequence {
        val s = SpannableString(prefix + strong)
        s.setSpan(StyleSpan(Typeface.BOLD), prefix.length, prefix.length + strong.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return s
    }

    private fun step(n: Int, msg: CharSequence): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val num = TextView(this).apply {
            text = n.toString()
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            background = oval(orange)
        }
        row.addView(num, lp(dp(30), dp(30)))
        val t = tv("", 17f)
        t.text = msg
        t.setPadding(dp(14), dp(4), 0, dp(4))
        row.addView(t)
        return row
    }

    private fun codeBox(code: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(26), dp(14), dp(26), dp(14))
            setBackgroundResource(R.drawable.bg_code_box)
            isFocusable = true
            isClickable = true
        }
        row.addView(tv(code, 32f, Color.WHITE, true))
        val cp = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(18), 0, 0, 0)
        }
        cp.addView(ImageView(this).apply { setImageDrawable(IconDrawable(IconPaths.CHECK)) }, lp(dp(18), dp(18)))
        cp.addView(tv("OK Copy", 11f, gray))
        row.addView(cp)
        row.setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("code", code))
            toast("Code copied")
        }
        return row
    }

    private fun networkBox(url: String): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(16))
            setBackgroundResource(R.drawable.bg_code_box)
            isFocusable = true
            isClickable = true
        }
        box.addView(tv("THIS TV ON YOUR NETWORK", 11f, gray, true).apply { letterSpacing = 0.1f })
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(tv(deviceName, 26f, Color.WHITE, true))
        row.addView(tv(url, 18f, Color.parseColor("#FFB07A")).apply { setPadding(dp(16), 0, 0, 0) })
        box.addView(row)
        box.addView(tv("Visible only on this Wi-Fi network · saves to Downloads", 12f, gray))
        box.setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("url", url))
            toast("Address copied")
        }
        return box
    }

    private fun statusRow(initial: String): View {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val pb = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(orange)
        }
        r.addView(pb, lp(dp(20), dp(20)))
        val t = tv(lastEvent ?: initial, 15f, Color.parseColor("#BBDDE8"))
        t.setPadding(dp(12), 0, 0, 0)
        eventView = t
        r.addView(t)
        return r
    }

    private fun listRow(path: String, name: String, info: String, onClick: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(11), dp(16), dp(11))
            isFocusable = true
            isClickable = true
            background = focusBg(Color.parseColor("#26FFFFFF"))
            addView(ImageView(context).apply { setImageDrawable(IconDrawable(path)) }, LinearLayout.LayoutParams(dp(24), dp(24)))
            addView(tv(name, 17f).apply {
                setPadding(dp(14), 0, dp(10), 0)
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.MIDDLE
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(tv(info, 13f, Color.GRAY))
            setOnClickListener { onClick() }
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
            lastEvent = m
            eventView?.text = m
        }
    }

    private fun addDevice(ip: String) {
        runOnUiThread {
            devices[ip] = System.currentTimeMillis()
            deviceBox?.let { fillDevices(it) }
        }
    }

    private fun fillDevices(box: LinearLayout) {
        box.removeAllViews()
        if (devices.isEmpty()) {
            val ring = ImageView(this).apply {
                setImageDrawable(IconDrawable(IconPaths.WIFI, orange))
                setPadding(dp(26), dp(26), dp(26), dp(26))
                background = oval(Color.parseColor("#22E8632B"), orange)
            }
            box.addView(ring, lp(dp(96), dp(96)))
            box.addView(tv("Searching for phones and PCs…", 13f, Color.WHITE).apply { setPadding(0, dp(14), 0, 0) })
        } else {
            for (ip in devices.keys) {
                box.addView(tv("●  $ip", 16f, Color.parseColor("#9FE6A0")))
            }
        }
    }

    // ------------------------------------------------------------------ sidebar / header

    private fun buildSidebar() {
        val sb = findViewById<LinearLayout>(R.id.sidebar)
        sb.background = rr(Color.parseColor("#73000000"), 0, 40)
        val size = minOf(dp(56), (resources.displayMetrics.heightPixels - dp(70)) / tabIcons.size - dp(8))
        val pad = size / 4
        tabIcons.forEachIndexed { i, p ->
            val v = ImageView(this).apply {
                setImageDrawable(IconDrawable(p))
                setPadding(pad, pad, pad, pad)
                isFocusable = true
                isClickable = true
                contentDescription = tabNames[i]
                setBackgroundResource(R.drawable.bg_active_icon)
                setOnClickListener { show(i) }
                setOnFocusChangeListener { _, f -> if (f && cur != i) show(i) }
            }
            sb.addView(v, LinearLayout.LayoutParams(size, size).apply {
                topMargin = dp(4); bottomMargin = dp(4)
            })
            sideViews.add(v)
        }
    }

    private fun chip(path: String, label: String, onClick: () -> Unit): View {
        val wide = resources.configuration.screenWidthDp >= 800
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(if (wide) 16 else 14), dp(8))
            isFocusable = true
            isClickable = true
            background = focusBg(Color.parseColor("#4D9AA0A8"), 14)
            contentDescription = label
            addView(ImageView(context).apply { setImageDrawable(IconDrawable(path)) }, LinearLayout.LayoutParams(dp(20), dp(20)))
            if (wide) addView(tv(label, 15f, Color.WHITE, true).apply { setPadding(dp(8), 0, 0, 0) })
            setOnClickListener { onClick() }
        }
    }

    private fun clockChip(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(6), dp(14), dp(6))
        background = rr(Color.parseColor("#4D9AA0A8"), 0, 14)
        addView(TextClock(context).apply {
            format12Hour = "HH:mm"
            format24Hour = "HH:mm"
            textSize = 24f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
        })
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, 0, 0)
        }
        col.addView(TextClock(context).apply {
            format12Hour = "EEEE"
            format24Hour = "EEEE"
            textSize = 12f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
        })
        col.addView(TextClock(context).apply {
            format12Hour = "dd MMMM yyyy"
            format24Hour = "dd MMMM yyyy"
            textSize = 11f
            setTextColor(Color.parseColor("#CCCCCC"))
        })
        addView(col)
    }

    private fun buildHeader() {
        val chips = findViewById<LinearLayout>(R.id.headerChips)
        chips.addView(chip(IconPaths.SETTINGS, "Settings") { show(7) })
        chips.addView(chip(IconPaths.NETWORK, "Network") { show(4) },
            lp(WRAP, WRAP).apply { marginStart = dp(8) })
        chips.addView(clockChip(), lp(WRAP, WRAP).apply { marginStart = dp(8) })
        findViewById<ImageView>(R.id.ivBt).setImageDrawable(IconDrawable(IconPaths.BLUETOOTH))
        findViewById<ImageView>(R.id.ivWifi).apply {
            setImageDrawable(IconDrawable(IconPaths.WIFI))
            setOnClickListener { safeStart(Intent(Settings.ACTION_WIFI_SETTINGS)) }
        }
    }

    @Suppress("DEPRECATION")
    private fun updateStatusIcons() {
        val wifiOn = try {
            (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager).activeNetworkInfo?.isConnected == true
        } catch (e: Exception) { false }
        val btOn = try { BluetoothAdapter.getDefaultAdapter()?.isEnabled == true } catch (e: Exception) { false }
        findViewById<ImageView>(R.id.ivWifi).alpha = if (wifiOn) 1f else 0.35f
        findViewById<ImageView>(R.id.ivBt).alpha = if (btOn) 1f else 0.35f
    }

    private fun show(i: Int) {
        cur = i
        val old = webView
        webView = null; dlBar = null; dlText = null; eventView = null; deviceBox = null
        content.removeAllViews()
        old?.destroy()
        sideViews.forEachIndexed { k, v -> v.isSelected = (k == i) }
        val v = when (i) {
            0 -> tabDownloader()
            1 -> tabBrowser()
            2 -> tabStorage()
            3 -> tabSend(true)
            4 -> tabSend(false)
            5 -> tabApps()
            6 -> tabApkManager()
            else -> tabSettings()
        }
        content.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    // ------------------------------------------------------------------ 0: Downloader

    private fun tabDownloader(): View {
        val p = panel()
        p.addView(pageHead(IconPaths.DOWNLOAD, "Downloader", "Paste a direct link to download a file or an APK"))
        val et = field("https://example.com/app.apk")
        et.setOnEditorActionListener { _, _, _ -> startDownload(et.text.toString()); true }
        p.addView(et, lp(MATCH, WRAP).apply { topMargin = dp(22) })
        p.addView(btn("Download", true) { startDownload(et.text.toString()) },
            lp(WRAP, WRAP).apply { topMargin = dp(12) })
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(orange)
        }
        dlBar = bar
        p.addView(bar, lp(MATCH, WRAP).apply { topMargin = dp(22) })
        val t = tv(if (downloading) "Downloading…" else "Idle", 16f, Color.parseColor("#9FE6A0"))
        dlText = t
        p.addView(t)
        p.addView(tv("Saved to: " + downloadsDir.absolutePath, 12f, Color.GRAY))
        return scroll(p)
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

    // ------------------------------------------------------------------ 1: Browser

    private fun normalizeUrl(s: String): String {
        val t = s.trim()
        return when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            t.contains(" ") || !t.contains(".") -> "https://www.google.com/search?q=" + Uri.encode(t)
            else -> "https://$t"
        }
    }

    private fun tabBrowser(): View {
        val p = panel()
        p.setPadding(dp(18), dp(14), dp(18), dp(12))
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val et = field("Search or type a URL", "https://www.google.com")
        val wv = WebView(this)
        webView = wv
        bar.addView(btn("◀") { if (wv.canGoBack()) wv.goBack() })
        bar.addView(et, lp(0, WRAP, 1f).apply { marginStart = dp(8); marginEnd = dp(8) })
        bar.addView(btn("Go", true) { wv.loadUrl(normalizeUrl(et.text.toString())) })
        bar.addView(btn("⟳") { wv.reload() }, lp(WRAP, WRAP).apply { marginStart = dp(8) })
        et.setOnEditorActionListener { _, _, _ -> wv.loadUrl(normalizeUrl(et.text.toString())); true }
        p.addView(bar, lp(MATCH, WRAP))

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
        p.addView(wv, lp(MATCH, 0, 1f).apply { topMargin = dp(8) })
        wv.loadUrl("https://www.google.com")
        return p
    }

    // ------------------------------------------------------------------ 2: USB & Storage

    private fun hasStorageAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestStorage() {
        if (Build.VERSION.SDK_INT >= 30) {
            safeStart(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 11)
        }
    }

    private fun storageRoots(): List<Pair<String, File>> {
        val r = ArrayList<Pair<String, File>>()
        r.add("App downloads" to downloadsDir)
        r.add("Internal storage" to Environment.getExternalStorageDirectory())
        File("/storage").listFiles()?.forEach {
            if (it.isDirectory && it.name != "emulated" && it.name != "self" && it.canRead()) {
                r.add("USB / SD card (" + it.name + ")" to it)
            }
        }
        return r
    }

    private fun navigate(dir: File?) {
        browseDir = dir
        show(2)
    }

    private fun tabStorage(): View {
        val p = panel()
        p.addView(pageHead(IconPaths.USB, "USB & Storage", "Browse USB drives and storage — click an APK to install it"))
        if (!hasStorageAccess()) {
            p.addView(tv("Storage access is needed to read USB drives and internal storage.", 14f, Color.parseColor("#FFD54F"))
                .apply { setPadding(0, dp(10), 0, dp(4)) })
            p.addView(btn("Grant storage access", true) { requestStorage() })
        }
        val roots = storageRoots()
        val dir = browseDir
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bar.addView(btn("⬆ Up") {
            val d = browseDir
            if (d == null) return@btn
            val isRoot = roots.any { it.second == d }
            navigate(if (isRoot) null else d.parentFile)
        })
        bar.addView(tv(dir?.absolutePath ?: "Select a location", 14f, gray).apply {
            setPadding(dp(14), 0, 0, 0)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.START
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        p.addView(bar, lp(MATCH, WRAP).apply { topMargin = dp(12) })

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (dir == null) {
            for ((label, f) in roots) {
                list.addView(listRow(IconPaths.FOLDER, label, "") { navigate(f) },
                    lp(MATCH, WRAP).apply { topMargin = dp(6) })
            }
        } else {
            val items = dir.listFiles()?.filter { !it.name.startsWith(".") }
                ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase(Locale.getDefault()) }))
            if (items == null) list.addView(tv("Cannot read this folder (no permission).", 15f, Color.GRAY))
            else if (items.isEmpty()) list.addView(tv("Empty folder.", 15f, Color.GRAY))
            else for (f in items) {
                if (f.isDirectory) {
                    list.addView(listRow(IconPaths.FOLDER, f.name, "") { navigate(f) },
                        lp(MATCH, WRAP).apply { topMargin = dp(6) })
                } else {
                    list.addView(listRow(IconPaths.FILE, f.name, fmt(f.length())) { fileDialog(f) },
                        lp(MATCH, WRAP).apply { topMargin = dp(6) })
                }
            }
        }
        p.addView(ScrollView(this).apply { addView(list) }, lp(MATCH, 0, 1f).apply { topMargin = dp(8) })
        return p
    }

    private fun fileDialog(f: File) {
        val isApk = f.name.endsWith(".apk", true)
        val inDl = f.absolutePath.startsWith(downloadsDir.absolutePath)
        val opts = ArrayList<String>()
        opts.add(if (isApk) "Install" else "Open")
        opts.add(if (inDl) "Delete" else "Copy to Downloads")
        AlertDialog.Builder(this).setTitle(f.name)
            .setItems(opts.toTypedArray()) { _, which ->
                when {
                    which == 0 -> openFile(f)
                    inDl -> { f.delete(); show(2) }
                    else -> copyToDownloads(f)
                }
            }.show()
    }

    private fun copyToDownloads(f: File) {
        toast("Copying…")
        thread {
            try {
                val d = uniqueFile(f.name)
                f.copyTo(d, true)
                runOnUiThread { toast("Copied: " + d.name) }
            } catch (e: Exception) {
                runOnUiThread { toast("Copy failed: " + e.message) }
            }
        }
    }

    // ------------------------------------------------------------------ 3/4: Send Apps + Local Send

    private fun tabSend(sendApps: Boolean): View {
        val ip = localIp()
        val base = "http://$ip:$serverPort"
        val shown = if (sendApps) "$base/send" else base
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(20), dp(28), dp(14))
        }
        val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(pageHead(
            if (sendApps) IconPaths.SEND else IconPaths.NETWORK,
            if (sendApps) "Send Apps From Any Device" else "Local Send",
            if (sendApps) "Phone, tablet or PC on the same Wi-Fi — only a web browser is needed."
            else "Big files over your own Wi-Fi — no internet, no size limit."
        ))

        if (serverPort == 0) {
            left.addView(tv("Server is not running. Connect to Wi-Fi and reopen this tab.", 18f, Color.YELLOW)
                .apply { setPadding(0, dp(20), 0, 0) })
            body.addView(left, lp(0, WRAP, 1f))
            root.addView(body, lp(MATCH, 0, 1f))
            return scroll(root)
        }

        val gap = lp(MATCH, WRAP).apply { topMargin = dp(12) }
        if (sendApps) {
            left.addView(step(1, boldPart("On the other device, open ", shown)), lp(MATCH, WRAP).apply { topMargin = dp(18) })
            left.addView(step(2, "Type this code — or scan the QR instead"), gap)
            left.addView(step(3, "Send a file or a message. You approve everything here."), gap)
            left.addView(codeBox(pairCode), lp(WRAP, WRAP).apply { topMargin = dp(20) })
        } else {
            left.addView(step(1, "On your phone or PC, open the address below in a browser"), lp(MATCH, WRAP).apply { topMargin = dp(18) })
            left.addView(step(2, "Devices on the same Wi-Fi reach this TV — no internet needed"), gap)
            left.addView(step(3, "Pick files there — you accept them here. Nothing arrives without your OK."), gap)
            left.addView(networkBox(base), lp(WRAP, WRAP).apply { topMargin = dp(20) })
        }
        body.addView(left, lp(0, WRAP, 1f))

        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(8), 0, 0)
        }
        if (sendApps) {
            val card = ImageView(this).apply {
                setImageBitmap(qr("$base/send?code=$pairCode", 420))
                scaleType = ImageView.ScaleType.FIT_XY
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = rr(Color.WHITE, 0, 20)
            }
            right.addView(card, lp(dp(190), dp(190)))
            right.addView(tv("Scan with your phone camera", 13f, gray).apply { setPadding(0, dp(8), 0, 0) })
        } else {
            right.addView(tv("DEVICES ON YOUR WI-FI", 11f, gray, true).apply { letterSpacing = 0.1f })
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(24), 0, 0)
            }
            deviceBox = box
            fillDevices(box)
            right.addView(box, lp(dp(230), WRAP))
        }
        body.addView(right, lp(WRAP, WRAP))

        root.addView(body, lp(MATCH, 0, 1f))
        root.addView(statusRow(if (sendApps) "Waiting for a device to connect…" else "Waiting — nobody has sent anything yet"),
            lp(MATCH, WRAP).apply { topMargin = dp(10) })
        return scroll(root)
    }

    // ------------------------------------------------------------------ 5: Apps (grid)

    private fun tabApps(): View {
        val p = panel()
        p.addView(pageHead(IconPaths.APPS, "Apps", "Installed apps — click one to open, uninstall or see info"))
        val pm = packageManager
        val apps = pm.getInstalledApplications(0)
            .filter {
                pm.getLeanbackLaunchIntentForPackage(it.packageName) != null ||
                    pm.getLaunchIntentForPackage(it.packageName) != null
            }
            .sortedBy { pm.getApplicationLabel(it).toString().lowercase(Locale.getDefault()) }
        val cols = maxOf(3, (resources.displayMetrics.widthPixels - dp(220)) / dp(112))
        val grid = GridLayout(this).apply { columnCount = cols }
        for (a in apps) {
            val label = pm.getApplicationLabel(a).toString()
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(6), dp(10), dp(6), dp(8))
                isFocusable = true
                isClickable = true
                background = focusBg(Color.parseColor("#1AFFFFFF"), 14)
                setOnClickListener { appDialog(a.packageName, label) }
            }
            val ic = ImageView(this)
            try { ic.setImageDrawable(pm.getApplicationIcon(a)) } catch (_: Exception) {}
            cell.addView(ic, lp(dp(56), dp(56)))
            cell.addView(tv(label, 12f, Color.WHITE).apply {
                gravity = Gravity.CENTER
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }, lp(MATCH, WRAP).apply { topMargin = dp(6) })
            val glp = GridLayout.LayoutParams()
            glp.width = dp(104)
            glp.height = WRAP
            glp.setMargins(dp(4), dp(4), dp(4), dp(4))
            grid.addView(cell, glp)
        }
        p.addView(ScrollView(this).apply { addView(grid) }, lp(MATCH, 0, 1f).apply { topMargin = dp(10) })
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

    // ------------------------------------------------------------------ 6: APK Manager

    private fun tabApkManager(): View {
        val p = panel()
        p.addView(pageHead(IconPaths.ANDROID, "APK Manager", "Back up or uninstall the apps you installed"))
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        top.addView(btn("Allow installing unknown apps", true) {
            safeStart(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
        })
        p.addView(top, lp(WRAP, WRAP).apply { topMargin = dp(12) })

        val pm = packageManager
        val apps = pm.getInstalledApplications(0)
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
            .sortedBy { pm.getApplicationLabel(it).toString().lowercase(Locale.getDefault()) }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (a in apps) {
            val label = pm.getApplicationLabel(a).toString()
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(6), dp(8), dp(6))
            }
            val ic = ImageView(this)
            try { ic.setImageDrawable(pm.getApplicationIcon(a)) } catch (_: Exception) {}
            row.addView(ic, lp(dp(40), dp(40)))
            row.addView(tv(label, 17f).apply {
                setPadding(dp(14), 0, dp(10), 0)
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            row.addView(btn("Backup") { backupApk(a, label) })
            row.addView(btn("Uninstall") {
                safeStart(Intent(Intent.ACTION_DELETE, Uri.parse("package:" + a.packageName)))
            }, lp(WRAP, WRAP).apply { marginStart = dp(8) })
            list.addView(row, lp(MATCH, WRAP).apply { topMargin = dp(4) })
        }
        if (apps.isEmpty()) list.addView(tv("No user-installed apps found.", 15f, Color.GRAY))
        p.addView(ScrollView(this).apply { addView(list) }, lp(MATCH, 0, 1f).apply { topMargin = dp(8) })
        return p
    }

    private fun backupApk(a: ApplicationInfo, label: String) {
        toast("Backing up $label…")
        thread {
            try {
                val dest = uniqueFile(sanitize(label) + ".apk")
                File(a.sourceDir).copyTo(dest, true)
                runOnUiThread { toast("Saved: " + dest.name) }
            } catch (e: Exception) {
                runOnUiThread { toast("Backup failed: " + e.message) }
            }
        }
    }

    // ------------------------------------------------------------------ 7: Settings

    private fun tabSettings(): View {
        val p = panel()
        p.addView(pageHead(IconPaths.SETTINGS, "Settings", "Device name, pairing code and system shortcuts"))
        val et = field("Device name", deviceName)
        et.inputType = InputType.TYPE_CLASS_TEXT
        p.addView(et, lp(MATCH, WRAP).apply { topMargin = dp(18) })
        val gap = { lp(WRAP, WRAP).apply { topMargin = dp(10) } }
        p.addView(btn("Save device name", true) {
            prefs.edit().putString("name", et.text.toString().trim().ifEmpty { "Living Room TV" }).apply()
            toast("Saved")
        }, gap())
        p.addView(btn("Generate new pairing code") {
            pairCode = genCode(); toast("New code: $pairCode")
        }, gap())
        p.addView(btn("Open Wi-Fi settings") { safeStart(Intent(Settings.ACTION_WIFI_SETTINGS)) }, gap())
        p.addView(btn("Open Android settings") { safeStart(Intent(Settings.ACTION_SETTINGS)) }, gap())
        p.addView(btn("Allow installing unknown apps") {
            safeStart(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
        }, gap())
        p.addView(btn("Delete all downloads") {
            AlertDialog.Builder(this).setTitle("Delete all downloaded files?")
                .setPositiveButton("Delete") { _, _ ->
                    downloadsDir.listFiles()?.forEach { if (it.isFile) it.delete() }
                    toast("Deleted")
                }
                .setNegativeButton("Cancel", null).show()
        }, gap())
        p.addView(tv("IP: " + localIp() + "     Port: " + serverPort, 14f, Color.LTGRAY).apply { setPadding(0, dp(18), 0, 0) })
        p.addView(tv("Download folder: " + downloadsDir.absolutePath, 13f, Color.GRAY))
        p.addView(tv("Downloader TV v1.0", 13f, Color.GRAY))
        return scroll(p)
    }

    // ------------------------------------------------------------------ mini web server

    private fun startServer() {
        thread(isDaemon = true) {
            val ss = try { ServerSocket(53317) } catch (e: Exception) {
                try { ServerSocket(0) } catch (e2: Exception) { null }
            } ?: return@thread
            serverSocket = ss
            serverPort = ss.localPort
            runOnUiThread { if (cur == 3 || cur == 4 || cur == 7) show(cur) }
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

    /** "send" mode needs the pairing code; "local" mode relies on the TV approval dialog. */
    private fun authOk(h: Map<String, String>): Boolean = h["x-mode"] != "send" || codeOk(h["x-code"])

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
                method == "GET" && (path == "/" || path == "/send") -> {
                    val mode = if (path == "/send") "send" else "local"
                    val code = query.split("&").firstOrNull { it.startsWith("code=") }
                        ?.substringAfter("=")?.filter { it.isDigit() || it == '-' } ?: ""
                    val html = WEB_PAGE
                        .replace("%%NAME%%", TextUtils.htmlEncode(deviceName))
                        .replace("%%CODE%%", code)
                        .replace("%%MODE%%", mode)
                    addDevice(ip)
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
        if (!authOk(h)) {
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
        if (!authOk(h)) {
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
            b.setNeutralButton("Open in Browser") { _, _ -> show(1); webView?.loadUrl(t) }
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
body{font-family:sans-serif;background:#1e0b05;color:#fff;max-width:520px;margin:0 auto;padding:20px}
h2{color:#ffb07a}
input,textarea,button{width:100%;box-sizing:border-box;padding:14px;margin:8px 0;border-radius:12px;border:1px solid #555;background:#2a1a12;color:#fff;font-size:16px}
button{background:#e8632b;border:0;font-weight:bold}
progress{width:100%}
#log{white-space:pre-wrap;color:#9fe6a0;margin-top:10px}
</style></head><body>
<h2>Send to %%NAME%%</h2>
<div id="codeBox"><input id="code" placeholder="Pairing code shown on the TV" value="%%CODE%%" inputmode="numeric"></div>
<input id="file" type="file">
<button onclick="sendFile()">Send file</button>
<progress id="p" value="0" max="100"></progress>
<textarea id="msg" rows="3" placeholder="Message or link (https://...)"></textarea>
<button onclick="sendMsg()">Send message / link</button>
<div id="log"></div>
<script>
var MODE='%%MODE%%';
if(MODE!=='send'){document.getElementById('codeBox').style.display='none'}
function log(t){document.getElementById('log').textContent=t}
function hdr(x){x.setRequestHeader('X-Mode',MODE);x.setRequestHeader('X-Code',document.getElementById('code').value)}
function sendFile(){
  var f=document.getElementById('file').files[0];
  if(!f){log('Choose a file first');return}
  var x=new XMLHttpRequest();
  x.open('POST','/upload');
  hdr(x);
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
  hdr(x);
  x.onload=function(){log(x.responseText)};
  x.onerror=function(){log('Connection failed')};
  x.send(m);
}
</script></body></html>"""
    }
}
