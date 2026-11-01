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
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.webkit.WebChromeClient
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
import org.json.JSONObject
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
import java.util.Collections
import java.util.Locale
import java.util.UUID
import kotlin.concurrent.thread

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
private const val DESKTOP_UA =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

class MainActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var content: FrameLayout
    private lateinit var cursorView: View
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
    private val tabKeys = listOf("n_dl", "n_br", "n_usb", "n_send", "n_local", "n_apps", "n_apk", "n_set")
    private val sideViews = ArrayList<ImageView>()
    private var cur = 0

    // browser
    private var webView: WebView? = null
    private var browserHolder: FrameLayout? = null
    private var urlField: EditText? = null
    private var loadBar: ProgressBar? = null
    private var mouseBtn: TextView? = null
    private var mouseOn = false
    private var cx = 0f
    private var cy = 0f
    private var customView: View? = null
    private var customCb: WebChromeClient.CustomViewCallback? = null
    private val defaultLinks = listOf(
        "Google" to "https://www.google.com", "YouTube" to "https://www.youtube.com",
        "Wikipedia" to "https://www.wikipedia.org", "GitHub" to "https://github.com",
        "Reddit" to "https://www.reddit.com", "Speedtest" to "https://www.speedtest.net"
    )

    // downloader
    private var dlBar: ProgressBar? = null
    private var dlText: TextView? = null
    private var dlCancelBtn: View? = null
    @Volatile private var downloading = false
    @Volatile private var cancelDl = false

    // send tabs
    private var eventView: TextView? = null
    private var deviceBox: LinearLayout? = null
    private var lastEvent: String? = null
    private val devices = LinkedHashMap<String, Long>()
    private var browseDir: File? = null

    // local server
    private var serverSocket: ServerSocket? = null
    @Volatile private var serverPort = 0

    private val relay by lazy {
        Relay({ Build.MANUFACTURER + " " + Build.MODEL }, {
            var id = prefs.getString("rid", null)
            if (id == null) {
                id = UUID.randomUUID().toString().replace("-", "")
                prefs.edit().putString("rid", id).apply()
            }
            id
        })
    }

    private val deviceName: String
        get() = prefs.getString("name", null) ?: "Living Room TV"

    private fun t(k: String): String = L.t(k)

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        L.lang = prefs.getString("lang", "en") ?: "en"
        root = findViewById(R.id.root)
        root.background = CanyonDrawable()
        root.layoutDirection = View.LAYOUT_DIRECTION_LTR
        content = findViewById(R.id.content)
        content.background = rr(c("#EE101010"), 0, 22)
        findViewById<View>(R.id.mainFrame).background = rr(c("#70000000"), 0, 30)
        downloadsDir.exists()

        cursorView = makeCursor()
        cursorView.visibility = View.GONE
        root.addView(cursorView, FrameLayout.LayoutParams(dp(26), dp(26)))

        applyHint()
        buildSidebar()
        buildHeader()
        startServer()

        relay.onCode = { runOnUiThread { if (cur == 3) show(3) } }
        relay.onStatus = { k, n ->
            addEvent(when (k) {
                "linked" -> t("linked_n") + " (" + n + ")"
                "down" -> t("server_down")
                else -> t("wait_dev")
            })
        }
        relay.onItem = { item -> runOnUiThread { askRelayItem(item) } }
        relay.start()

        show(0)
    }

    override fun onResume() {
        super.onResume()
        updateStatusIcons()
        if (cur == 2) show(2)
    }

    override fun onDestroy() {
        relay.stop()
        try { serverSocket?.close() } catch (_: Exception) {}
        webView?.destroy()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val w = webView
        when {
            customView != null -> hideCustom()
            cur == 1 && w != null && w.canGoBack() -> w.goBack()
            cur == 1 && w != null -> showHome()
            cur != 0 -> show(0)
            else -> super.onBackPressed()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (cur == 2) show(2)
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (mouseOn && cur == 1 && customView == null) {
            val w = webView
            if (w != null) {
                when (e.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (e.action == KeyEvent.ACTION_DOWN) moveMouse(e.keyCode, e.repeatCount, w)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) clickMouse()
                        return true
                    }
                    KeyEvent.KEYCODE_BACK -> {
                        if (e.action == KeyEvent.ACTION_UP) setMouse(false)
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(e)
    }

    // ------------------------------------------------------------------ helpers

    private fun c(hex: String): Int = Color.parseColor(hex)
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
        addState(intArrayOf(android.R.attr.state_focused), rr(c("#33FFB07A"), c("#FFD54F"), radius))
        addState(intArrayOf(), rr(normal, 0, radius))
    }

    private fun tv(s: String, size: Float = 16f, color: Int = Color.WHITE, bold: Boolean = false) =
        TextView(this).apply {
            text = s
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
        setPadding(dp(18), dp(10), dp(18), dp(10))
        isFocusable = true
        isClickable = true
        background = focusBg(if (primary) orange else c("#4D4D4D"))
        setOnClickListener { onClick() }
    }

    private fun field(hintText: String, value: String = ""): EditText = EditText(this).apply {
        hint = hintText
        setText(value)
        setTextColor(Color.WHITE)
        setHintTextColor(c("#888888"))
        textSize = 18f
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        imeOptions = EditorInfo.IME_ACTION_GO
        setPadding(dp(18), dp(12), dp(18), dp(12))
        background = focusBg(c("#33FFFFFF"))
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
            background = oval(c("#33FFFFFF"))
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
        val tx = tv("", 17f)
        tx.text = msg
        tx.setPadding(dp(14), dp(4), 0, dp(4))
        row.addView(tx)
        return row
    }

    private fun copyToClipboard(label: String, value: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, value))
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
        cp.addView(tv(t("ok_copy"), 11f, gray))
        row.addView(cp)
        row.setOnClickListener { copyToClipboard("code", code); toast(t("code_copied")) }
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
        box.addView(tv(t("this_tv"), 11f, gray, true).apply { letterSpacing = 0.1f })
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(tv(deviceName, 26f, Color.WHITE, true))
        row.addView(tv(url, 18f, c("#FFB07A")).apply { setPadding(dp(16), 0, 0, 0) })
        box.addView(row)
        box.addView(tv(t("visible"), 12f, gray))
        box.setOnClickListener { copyToClipboard("url", url); toast(t("addr_copied")) }
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
        val tx = tv(lastEvent ?: initial, 15f, c("#BBDDE8"))
        tx.setPadding(dp(12), 0, 0, 0)
        eventView = tx
        r.addView(tx)
        return r
    }

    private fun listRow(path: String, name: String, info: String, onClick: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(11), dp(16), dp(11))
            isFocusable = true
            isClickable = true
            background = focusBg(c("#26FFFFFF"))
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
        runOnUiThread { lastEvent = m; eventView?.text = m }
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
                background = oval(c("#22E8632B"), orange)
            }
            box.addView(ring, lp(dp(96), dp(96)))
            box.addView(tv(t("searching"), 13f, Color.WHITE).apply { setPadding(0, dp(14), 0, 0) })
        } else {
            for (ip in devices.keys) box.addView(tv("●  $ip", 16f, c("#9FE6A0")))
        }
    }

    private fun applyHint() {
        val h = findViewById<TextView>(R.id.hint)
        h.text = t("hint")
        h.visibility = if (resources.configuration.screenHeightDp < 420) View.GONE else View.VISIBLE
    }

    // ------------------------------------------------------------------ sidebar / header

    private fun buildSidebar() {
        val sb = findViewById<LinearLayout>(R.id.sidebar)
        sb.background = rr(c("#80000000"), 0, 40)
        val size = minOf(dp(56), (resources.displayMetrics.heightPixels - dp(70)) / tabIcons.size - dp(8))
        val pad = size / 4
        tabIcons.forEachIndexed { i, p ->
            val v = ImageView(this).apply {
                setImageDrawable(IconDrawable(p))
                setPadding(pad, pad, pad, pad)
                isFocusable = true
                isClickable = true
                contentDescription = t(tabKeys[i])
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

    private fun chip(path: String?, label: String, onClick: () -> Unit): View {
        val wide = resources.configuration.screenWidthDp >= 800
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(8))
            isFocusable = true
            isClickable = true
            background = focusBg(c("#669AA0A8"), 14)
            contentDescription = label
            if (path != null) {
                addView(ImageView(context).apply { setImageDrawable(IconDrawable(path)) }, LinearLayout.LayoutParams(dp(20), dp(20)))
            }
            if (wide || path == null) {
                addView(tv(label, 15f, Color.WHITE, true).apply { setPadding(if (path != null) dp(8) else 0, 0, 0, 0) })
            }
            setOnClickListener { onClick() }
        }
    }

    private fun clockChip(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(6), dp(14), dp(6))
        background = rr(c("#669AA0A8"), 0, 14)
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
            setTextColor(c("#CCCCCC"))
        })
        addView(col)
    }

    private fun buildHeader() {
        val chips = findViewById<LinearLayout>(R.id.headerChips)
        chips.removeAllViews()
        chips.addView(chip(IconPaths.SETTINGS, t("settings")) { show(7) })
        chips.addView(chip(IconPaths.NETWORK, t("network")) { show(4) }, lp(WRAP, WRAP).apply { marginStart = dp(8) })
        chips.addView(clockChip(), lp(WRAP, WRAP).apply { marginStart = dp(8) })
        chips.addView(chip(null, (L.flags[L.lang] ?: "EN") + " ⌄") { langDialog() }, lp(WRAP, WRAP).apply { marginStart = dp(8) })
        findViewById<ImageView>(R.id.ivBt).setImageDrawable(IconDrawable(IconPaths.BLUETOOTH))
        findViewById<ImageView>(R.id.ivWifi).apply {
            setImageDrawable(IconDrawable(IconPaths.WIFI))
            setOnClickListener { safeStart(Intent(Settings.ACTION_WIFI_SETTINGS)) }
        }
    }

    private fun langDialog() {
        val codes = listOf("en", "ur")
        AlertDialog.Builder(this).setTitle(t("language"))
            .setItems(codes.map { L.flags[it] ?: it }.toTypedArray()) { _, i -> setLang(codes[i]) }
            .show()
    }

    private fun setLang(l: String) {
        L.lang = l
        prefs.edit().putString("lang", l).apply()
        buildHeader()
        applyHint()
        sideViews.forEachIndexed { i, v -> v.contentDescription = t(tabKeys[i]) }
        show(cur)
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
        if (i != 1) setMouse(false)
        val old = webView
        webView = null; browserHolder = null; urlField = null; loadBar = null; mouseBtn = null
        dlBar = null; dlText = null; dlCancelBtn = null; eventView = null; deviceBox = null
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
        p.addView(pageHead(IconPaths.DOWNLOAD, t("n_dl"), t("dl_sub")))
        val et = field("https://example.com/app.apk")
        et.setOnEditorActionListener { _, _, _ -> startDownload(et.text.toString()); true }
        p.addView(et, lp(MATCH, WRAP).apply { topMargin = dp(20) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(btn(t("download"), true) { startDownload(et.text.toString()) })
        row.addView(btn(t("paste")) {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val s = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
            if (s.isNotEmpty()) et.setText(s)
        }, lp(WRAP, WRAP).apply { marginStart = dp(8) })
        val cancel = btn(t("cancel")) { cancelDl = true }
        cancel.visibility = if (downloading) View.VISIBLE else View.GONE
        dlCancelBtn = cancel
        row.addView(cancel, lp(WRAP, WRAP).apply { marginStart = dp(8) })
        p.addView(row, lp(WRAP, WRAP).apply { topMargin = dp(10) })

        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(orange)
        }
        dlBar = bar
        p.addView(bar, lp(MATCH, WRAP).apply { topMargin = dp(16) })
        val st = tv(if (downloading) t("starting") else t("idle"), 15f, c("#9FE6A0"))
        dlText = st
        p.addView(st)
        p.addView(tv(t("saved_to") + downloadsDir.absolutePath, 11f, Color.GRAY))

        p.addView(tv(t("recent"), 15f, gray, true).apply { setPadding(0, dp(14), 0, dp(4)) })
        val files = downloadsDir.listFiles()?.filter { it.isFile && !it.name.startsWith(".") }
            ?.sortedByDescending { it.lastModified() }?.take(4) ?: emptyList()
        if (files.isEmpty()) p.addView(tv(t("no_files"), 14f, Color.GRAY))
        for (f in files) {
            p.addView(listRow(IconPaths.FILE, f.name, fmt(f.length())) { fileDialog(f) },
                lp(MATCH, WRAP).apply { topMargin = dp(4) })
        }
        return scroll(p)
    }

    private fun startDownload(raw: String) {
        var url = raw.trim()
        if (url.isEmpty()) { toast(t("enter_link")); return }
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
        if (downloading) { toast(t("dl_running")); return }
        downloading = true
        cancelDl = false
        dlText?.text = t("starting")
        dlBar?.progress = 0
        dlCancelBtn?.visibility = View.VISIBLE
        toast(t("dl_started"))
        val target = url
        thread {
            var lastT = System.currentTimeMillis()
            var lastD = 0L
            try {
                val f = fetch(target) { done, total ->
                    val now = System.currentTimeMillis()
                    val sp = if (now > lastT) (done - lastD) * 1000 / (now - lastT) else 0L
                    lastT = now
                    lastD = done
                    runOnUiThread {
                        if (total > 0) dlBar?.progress = (done * 100 / total).toInt()
                        dlText?.text = fmt(done) + (if (total > 0) " / " + fmt(total) else "") + "   •   " + fmt(sp) + "/s"
                    }
                }
                runOnUiThread {
                    downloading = false
                    dlBar?.progress = 100
                    dlCancelBtn?.visibility = View.GONE
                    dlText?.text = t("saved") + f.name
                    afterDownload(f)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    downloading = false
                    dlCancelBtn?.visibility = View.GONE
                    if (cancelDl) dlText?.text = t("cancelled")
                    else { dlText?.text = t("dl_failed") + e.message; toast(t("dl_failed") + e.message) }
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
                        if (cancelDl) throw IOException("Cancelled")
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val now = System.currentTimeMillis()
                        if (now - last > 400) { last = now; progress(done, total) }
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
            .setTitle(t("dl_complete"))
            .setMessage(f.name + "\n" + fmt(f.length()))
            .setPositiveButton(if (isApk) t("install") else t("open")) { _, _ -> openFile(f) }
            .setNegativeButton(t("close"), null)
            .show()
    }

    private fun openFile(f: File) {
        val isApk = f.name.endsWith(".apk", true)
        if (isApk && Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            toast(t("allow_hint"))
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
        val x = s.trim()
        return when {
            x.startsWith("http://") || x.startsWith("https://") -> x
            x.contains(" ") || !x.contains(".") -> "https://www.google.com/search?q=" + Uri.encode(x)
            else -> "https://$x"
        }
    }

    private fun tabBrowser(): View {
        val p = panel()
        p.setPadding(dp(18), dp(12), dp(18), dp(10))
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val et = field(t("search_hint"), "")
        urlField = et
        et.setOnEditorActionListener { _, _, _ -> navigate(normalizeUrl(et.text.toString())); true }
        val gp = { lp(WRAP, WRAP).apply { marginStart = dp(6) } }
        bar.addView(btn("◀") { val w = webView; if (w != null && w.canGoBack()) w.goBack() else showHome() })
        bar.addView(btn("▶") { webView?.goForward() }, gp())
        bar.addView(btn("⟳") { webView?.reload() }, gp())
        bar.addView(btn("⌂") { showHome() }, gp())
        bar.addView(et, lp(0, WRAP, 1f).apply { marginStart = dp(8); marginEnd = dp(8) })
        bar.addView(btn(t("go"), true) { navigate(normalizeUrl(et.text.toString())) })
        bar.addView(btn("★") { addBookmark() }, gp())
        val mb = btn(t("mouse_off")) { setMouse(!mouseOn) }
        mouseBtn = mb
        bar.addView(mb, gp())
        p.addView(bar, lp(MATCH, WRAP))

        val lb = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.INVISIBLE
            progressTintList = ColorStateList.valueOf(orange)
        }
        loadBar = lb
        p.addView(lb, lp(MATCH, dp(4)).apply { topMargin = dp(6) })

        val holder = FrameLayout(this)
        browserHolder = holder
        p.addView(holder, lp(MATCH, 0, 1f).apply { topMargin = dp(4) })
        holder.addView(homeView(), FrameLayout.LayoutParams(MATCH, MATCH))
        return p
    }

    private fun loadBm(): MutableList<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        (prefs.getString("bm", "") ?: "").split("\n").forEach { l ->
            val i = l.indexOf('|')
            if (i > 0) out.add(l.substring(0, i) to l.substring(i + 1))
        }
        return out
    }

    private fun saveBm(l: List<Pair<String, String>>) {
        prefs.edit().putString("bm", l.joinToString("\n") { it.first + "|" + it.second }).apply()
    }

    private fun avatarColor(s: String): Int {
        val p = arrayOf("#E8632B", "#2563EB", "#0F9D6B", "#9333EA", "#DB2777", "#CA8A04")
        return Color.parseColor(p[(s.hashCode() and 0x7fffffff) % p.size])
    }

    private fun homeView(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(14), dp(8), dp(8))
        }
        box.addView(tv(t("quick_links"), 16f, gray, true))
        val cols = maxOf(3, (resources.displayMetrics.widthPixels - dp(260)) / dp(132))
        val grid = GridLayout(this).apply { columnCount = cols }

        fun tile(name: String, url: String, removable: Boolean) {
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(6), dp(12), dp(6), dp(10))
                isFocusable = true
                isClickable = true
                background = focusBg(c("#1AFFFFFF"), 14)
                setOnClickListener { navigate(url) }
                if (removable) setOnLongClickListener {
                    AlertDialog.Builder(this@MainActivity).setTitle(name).setMessage(t("remove_bm"))
                        .setPositiveButton(t("remove")) { _, _ ->
                            saveBm(loadBm().filter { it.second != url })
                            showHome()
                        }
                        .setNegativeButton(t("cancel"), null).show()
                    true
                }
            }
            val av = TextView(this).apply {
                text = name.take(1).uppercase(Locale.getDefault())
                textSize = 26f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setTypeface(null, Typeface.BOLD)
                background = oval(avatarColor(name))
            }
            cell.addView(av, lp(dp(60), dp(60)))
            cell.addView(tv(name, 13f).apply {
                gravity = Gravity.CENTER
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
            }, lp(MATCH, WRAP).apply { topMargin = dp(6) })
            val glp = GridLayout.LayoutParams()
            glp.width = dp(120)
            glp.height = WRAP
            glp.setMargins(dp(5), dp(5), dp(5), dp(5))
            grid.addView(cell, glp)
        }

        defaultLinks.forEach { tile(it.first, it.second, false) }
        loadBm().forEach { tile(it.first, it.second, true) }
        box.addView(grid)
        box.addView(tv(t("mouse_help"), 13f, Color.GRAY).apply { setPadding(0, dp(18), 0, 0) })
        return ScrollView(this).apply { addView(box) }
    }

    private fun newWebView(): WebView {
        val wv = WebView(this)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.useWideViewPort = true
        wv.settings.loadWithOverviewMode = true
        wv.settings.mediaPlaybackRequiresUserGesture = false
        wv.settings.userAgentString = DESKTOP_UA
        wv.settings.setSupportZoom(true)
        wv.settings.builtInZoomControls = true
        wv.settings.displayZoomControls = false
        wv.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                urlField?.setText(url ?: "")
            }
        }
        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                loadBar?.progress = newProgress
                loadBar?.visibility = if (newProgress < 100) View.VISIBLE else View.INVISIBLE
            }
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view == null) return
                customView = view
                customCb = callback
                root.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
            }
            override fun onHideCustomView() { hideCustom() }
        }
        wv.setDownloadListener { url, _, _, _, _ -> startDownload(url) }
        return wv
    }

    private fun hideCustom() {
        customView?.let { root.removeView(it) }
        customView = null
        customCb?.onCustomViewHidden()
        customCb = null
    }

    private fun navigate(url: String) {
        val holder = browserHolder ?: return
        var w = webView
        if (w == null) {
            w = newWebView()
            webView = w
            holder.removeAllViews()
            holder.addView(w, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        w.loadUrl(url)
    }

    private fun showHome() {
        setMouse(false)
        val holder = browserHolder ?: return
        val old = webView
        webView = null
        holder.removeAllViews()
        old?.destroy()
        urlField?.setText("")
        loadBar?.visibility = View.INVISIBLE
        holder.addView(homeView(), FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun addBookmark() {
        val w = webView
        val u = w?.url
        if (w == null || u == null) { toast(t("no_page")); return }
        val name = (w.title ?: Uri.parse(u).host ?: u).replace("|", " ").replace("\n", " ").take(24)
        val l = loadBm()
        if (l.none { it.second == u }) l.add(name to u)
        saveBm(l)
        toast(t("add_bm"))
    }

    // ----- virtual mouse for D-pad remotes -----

    private fun makeCursor(): View = object : View(this) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        private val path = Path()
        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            path.reset()
            path.moveTo(0f, 0f)
            path.lineTo(0f, h * 0.9f)
            path.lineTo(w * 0.3f, h * 0.68f)
            path.lineTo(w * 0.55f, h)
            path.lineTo(w * 0.7f, h * 0.9f)
            path.lineTo(w * 0.45f, h * 0.6f)
            path.lineTo(w * 0.85f, h * 0.55f)
            path.close()
            canvas.drawPath(path, fill)
            canvas.drawPath(path, line)
        }
    }

    private fun webRect(w: WebView): Rect {
        val loc = IntArray(2)
        w.getLocationInWindow(loc)
        return Rect(loc[0], loc[1], loc[0] + w.width, loc[1] + w.height)
    }

    private fun moveCursor() {
        cursorView.translationX = cx
        cursorView.translationY = cy
    }

    private fun setMouse(on: Boolean) {
        val w = webView
        if (on && w == null) { toast(t("no_page")); return }
        mouseOn = on
        cursorView.visibility = if (on) View.VISIBLE else View.GONE
        if (on && w != null) {
            val r = webRect(w)
            cx = r.exactCenterX()
            cy = r.exactCenterY()
            moveCursor()
        }
        mouseBtn?.text = t(if (on) "mouse_on" else "mouse_off")
        if (!on) mouseBtn?.requestFocus()
    }

    private fun moveMouse(code: Int, rep: Int, w: WebView) {
        val r = webRect(w)
        val step = dp(if (rep > 6) 34 else if (rep > 0) 20 else 12)
        when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> cx -= step
            KeyEvent.KEYCODE_DPAD_RIGHT -> cx += step
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (cy - step < r.top) {
                    if (w.scrollY > 0) w.scrollBy(0, -dp(120)) else { setMouse(false); return }
                } else cy -= step
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (cy + step > r.bottom - dp(4)) w.scrollBy(0, dp(120)) else cy += step
            }
        }
        cx = cx.coerceIn(r.left.toFloat(), (r.right - dp(4)).toFloat())
        cy = cy.coerceIn(r.top.toFloat(), (r.bottom - dp(4)).toFloat())
        moveCursor()
    }

    private fun clickMouse() {
        val t0 = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t0, t0, MotionEvent.ACTION_DOWN, cx, cy, 0)
        window.decorView.dispatchTouchEvent(down)
        down.recycle()
        val x = cx
        val y = cy
        cursorView.postDelayed({
            val t1 = SystemClock.uptimeMillis()
            val up = MotionEvent.obtain(t0, t1, MotionEvent.ACTION_UP, x, y, 0)
            window.decorView.dispatchTouchEvent(up)
            up.recycle()
        }, 60)
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
        r.add(t("app_dl") to downloadsDir)
        r.add(t("internal") to Environment.getExternalStorageDirectory())
        File("/storage").listFiles()?.forEach {
            if (it.isDirectory && it.name != "emulated" && it.name != "self" && it.canRead()) {
                r.add(t("usb_sd") + " (" + it.name + ")" to it)
            }
        }
        return r
    }

    private fun navigateDir(dir: File?) {
        browseDir = dir
        show(2)
    }

    private fun tabStorage(): View {
        val p = panel()
        p.addView(pageHead(IconPaths.USB, t("n_usb"), t("usb_sub")))
        if (!hasStorageAccess()) {
            p.addView(tv(t("need_storage"), 14f, c("#FFD54F")).apply { setPadding(0, dp(10), 0, dp(4)) })
            p.addView(btn(t("grant"), true) { requestStorage() })
        }
        val roots = storageRoots()
        val dir = browseDir
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bar.addView(btn(t("up")) {
            val d = browseDir
            if (d != null) {
                val isRoot = roots.any { it.second == d }
                navigateDir(if (isRoot) null else d.parentFile)
            }
        })
        bar.addView(tv(dir?.absolutePath ?: t("pick_loc"), 14f, gray).apply {
            setPadding(dp(14), 0, 0, 0)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.START
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        p.addView(bar, lp(MATCH, WRAP).apply { topMargin = dp(12) })

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (dir == null) {
            for ((label, f) in roots) {
                list.addView(listRow(IconPaths.FOLDER, label, "") { navigateDir(f) },
                    lp(MATCH, WRAP).apply { topMargin = dp(6) })
            }
        } else {
            val items = dir.listFiles()?.filter { !it.name.startsWith(".") }
                ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase(Locale.getDefault()) }))
            if (items == null) list.addView(tv(t("cant_read"), 15f, Color.GRAY))
            else if (items.isEmpty()) list.addView(tv(t("empty"), 15f, Color.GRAY))
            else for (f in items) {
                if (f.isDirectory) {
                    list.addView(listRow(IconPaths.FOLDER, f.name, "") { navigateDir(f) },
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
        opts.add(if (isApk) t("install") else t("open"))
        opts.add(if (inDl) t("delete") else t("copy_dl"))
        AlertDialog.Builder(this).setTitle(f.name)
            .setItems(opts.toTypedArray()) { _, which ->
                when {
                    which == 0 -> openFile(f)
                    inDl -> { f.delete(); show(cur) }
                    else -> copyToDownloads(f)
                }
            }.show()
    }

    private fun copyToDownloads(f: File) {
        toast("…")
        thread {
            try {
                val d = uniqueFile(f.name)
                f.copyTo(d, true)
                runOnUiThread { toast(t("copied") + ": " + d.name) }
            } catch (e: Exception) {
                runOnUiThread { toast("Copy failed: " + e.message) }
            }
        }
    }

    // ------------------------------------------------------------------ 3/4: Send Apps (VPS) + Local Send (Wi-Fi)

    private fun tabSend(cloud: Boolean): View {
        val base = "http://" + localIp() + ":" + serverPort
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(20), dp(28), dp(14))
        }
        val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(pageHead(
            if (cloud) IconPaths.SEND else IconPaths.NETWORK,
            if (cloud) t("send_title") else t("local_title"),
            if (cloud) t("send_sub") else t("local_sub")
        ))
        body.addView(left, lp(0, WRAP, 1f))

        if (!cloud && serverPort == 0) {
            left.addView(tv(t("no_server"), 18f, Color.YELLOW).apply { setPadding(0, dp(20), 0, 0) })
            root.addView(body, lp(MATCH, 0, 1f))
            return scroll(root)
        }

        val gap = lp(MATCH, WRAP).apply { topMargin = dp(12) }
        val first = lp(MATCH, WRAP).apply { topMargin = dp(18) }
        if (cloud) {
            left.addView(step(1, boldPart(t("s1"), Relay.SHORT)), first)
            left.addView(step(2, t("s2")), gap)
            left.addView(step(3, t("s3")), gap)
            left.addView(codeBox(relay.code ?: t("connecting")), lp(WRAP, WRAP).apply { topMargin = dp(20) })
        } else {
            left.addView(step(1, t("l1")), first)
            left.addView(step(2, t("l2")), gap)
            left.addView(step(3, t("l3")), gap)
            left.addView(networkBox(base), lp(WRAP, WRAP).apply { topMargin = dp(20) })
        }

        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(8), 0, 0)
        }
        if (cloud) {
            val card = ImageView(this).apply {
                setImageBitmap(qr(relay.qrUrl(), 420))
                scaleType = ImageView.ScaleType.FIT_XY
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = rr(Color.WHITE, 0, 20)
            }
            right.addView(card, lp(dp(190), dp(190)))
            right.addView(tv(t("scan_cap"), 13f, gray).apply { setPadding(0, dp(8), 0, 0) })
        } else {
            right.addView(tv(t("devices"), 11f, gray, true).apply { letterSpacing = 0.1f })
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
        root.addView(statusRow(if (cloud) t("wait_dev") else t("wait_local")), lp(MATCH, WRAP).apply { topMargin = dp(10) })
        return scroll(root)
    }

    // ------------------------------------------------------------------ 5: Apps grid

    private fun tabApps(): View {
        val p = panel()
        p.addView(pageHead(IconPaths.APPS, t("n_apps"), t("apps_sub")))
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
                background = focusBg(c("#1AFFFFFF"), 14)
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
            .setItems(arrayOf(t("open"), t("uninstall"), t("app_info"))) { _, which ->
                when (which) {
                    0 -> {
                        val i = packageManager.getLeanbackLaunchIntentForPackage(pkg)
                            ?: packageManager.getLaunchIntentForPackage(pkg)
                        if (i != null) safeStart(i) else toast(t("cant_open"))
                    }
                    1 -> safeStart(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")))
                    else -> safeStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
                }
            }.show()
    }

    // ------------------------------------------------------------------ 6: APK Manager

    private fun tabApkManager(): View {
        val p = panel()
        p.addView(pageHead(IconPaths.ANDROID, t("n_apk"), t("apk_sub")))
        p.addView(btn(t("allow_unknown"), true) {
            safeStart(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
        }, lp(WRAP, WRAP).apply { topMargin = dp(12) })

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
            row.addView(btn(t("backup")) { backupApk(a, label) })
            row.addView(btn(t("uninstall")) {
                safeStart(Intent(Intent.ACTION_DELETE, Uri.parse("package:" + a.packageName)))
            }, lp(WRAP, WRAP).apply { marginStart = dp(8) })
            list.addView(row, lp(MATCH, WRAP).apply { topMargin = dp(4) })
        }
        if (apps.isEmpty()) list.addView(tv(t("no_user_apps"), 15f, Color.GRAY))
        p.addView(ScrollView(this).apply { addView(list) }, lp(MATCH, 0, 1f).apply { topMargin = dp(8) })
        return p
    }

    private fun backupApk(a: ApplicationInfo, label: String) {
        toast("$label…")
        thread {
            try {
                val dest = uniqueFile(sanitize(label) + ".apk")
                File(a.sourceDir).copyTo(dest, true)
                runOnUiThread { toast(t("saved") + dest.name) }
            } catch (e: Exception) {
                runOnUiThread { toast("Backup failed: " + e.message) }
            }
        }
    }

    // ------------------------------------------------------------------ 7: Settings

    private fun tabSettings(): View {
        val p = panel()
        p.addView(pageHead(IconPaths.SETTINGS, t("n_set"), t("set_sub")))
        val et = field(t("dev_name"), deviceName)
        et.inputType = InputType.TYPE_CLASS_TEXT
        p.addView(et, lp(MATCH, WRAP).apply { topMargin = dp(16) })
        val gap = { lp(WRAP, WRAP).apply { topMargin = dp(8) } }
        p.addView(btn(t("save_name"), true) {
            prefs.edit().putString("name", et.text.toString().trim().ifEmpty { "Living Room TV" }).apply()
            toast(t("saved_ok"))
        }, gap())
        p.addView(btn(t("language") + ":  " + (L.flags[L.lang] ?: "EN") + " ⌄") { langDialog() }, gap())
        p.addView(btn(t("check_upd")) { checkUpdate() }, gap())
        p.addView(btn(t("new_code")) { relay.renew(); toast(t("code_new")) }, gap())
        p.addView(btn(t("wifi_set")) { safeStart(Intent(Settings.ACTION_WIFI_SETTINGS)) }, gap())
        p.addView(btn(t("android_set")) { safeStart(Intent(Settings.ACTION_SETTINGS)) }, gap())
        p.addView(btn(t("allow_unknown")) {
            safeStart(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
        }, gap())
        p.addView(btn(t("del_all")) {
            AlertDialog.Builder(this).setTitle(t("del_q"))
                .setPositiveButton(t("delete")) { _, _ ->
                    downloadsDir.listFiles()?.forEach { if (it.isFile) it.delete() }
                    toast(t("deleted"))
                }
                .setNegativeButton(t("cancel"), null).show()
        }, gap())
        p.addView(tv("IP: " + localIp() + "     Port: " + serverPort + "     v" + versionName(), 13f, Color.LTGRAY)
            .apply { setPadding(0, dp(14), 0, 0) })
        return scroll(p)
    }

    @Suppress("DEPRECATION")
    private fun versionName(): String = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (e: Exception) { "" }

    @Suppress("DEPRECATION")
    private fun checkUpdate() {
        toast(t("up_checking"))
        thread {
            try {
                val j = JSONObject(relay.fetchText("version.json?t=" + System.currentTimeMillis()))
                val remote = j.optInt("versionCode", 0)
                val local = packageManager.getPackageInfo(packageName, 0).versionCode
                val apk = Relay.BASE + j.optString("apk", "downloader-tv.apk")
                runOnUiThread {
                    if (remote > local) {
                        AlertDialog.Builder(this).setTitle(t("up_avail"))
                            .setMessage(j.optString("versionName") + "\n" + j.optString("notes"))
                            .setPositiveButton(t("download")) { _, _ -> show(0); startDownload(apk) }
                            .setNegativeButton(t("close"), null).show()
                    } else toast(t("up_latest"))
                }
            } catch (e: Exception) {
                runOnUiThread { toast(t("up_fail")) }
            }
        }
    }

    // ------------------------------------------------------------------ incoming items (VPS relay)

    private fun askRelayItem(item: RelayItem) {
        if (isFinishing) return
        if (item.type == "file") {
            AlertDialog.Builder(this)
                .setTitle(t("incoming"))
                .setMessage(item.name + "\n" + fmt(item.size) + "\n" + t("from") + " " + item.ip)
                .setCancelable(false)
                .setPositiveButton(t("accept")) { _, _ -> receiveRelayFile(item) }
                .setNegativeButton(t("reject")) { _, _ ->
                    thread { try { relay.ack(item, false) } catch (_: Exception) {} }
                    addEvent(t("rejected") + ": " + item.name)
                }
                .show()
        } else {
            thread { try { relay.ack(item, true) } catch (_: Exception) {} }
            askMessage(item.text, item.ip)
        }
    }

    private fun receiveRelayFile(item: RelayItem) {
        addEvent(t("receiving") + ": " + item.name)
        thread {
            try {
                val dest = uniqueFile(sanitize(item.name))
                relay.download(item, dest) { d, total ->
                    if (total > 0) addEvent(t("receiving") + " " + item.name + ": " + (d * 100 / total) + "%")
                }
                relay.ack(item, true)
                runOnUiThread { addEvent(t("received") + ": " + dest.name); afterDownload(dest) }
            } catch (e: Exception) {
                runOnUiThread { toast(t("recv_fail") + ": " + e.message); addEvent(t("recv_fail")) }
            }
        }
    }

    private fun askMessage(text: String, ip: String) {
        if (isFinishing) return
        addEvent(t("msg_from") + " " + ip)
        val x = text.trim()
        val isLink = x.startsWith("http://") || x.startsWith("https://")
        val b = AlertDialog.Builder(this).setTitle(t("msg_from") + " " + ip).setMessage(x)
        if (isLink) {
            b.setPositiveButton(t("download")) { _, _ -> show(0); startDownload(x) }
            b.setNeutralButton(t("open_browser")) { _, _ -> show(1); navigate(x) }
            b.setNegativeButton(t("close"), null)
        } else {
            b.setPositiveButton(t("ok"), null)
        }
        b.show()
    }

    // ------------------------------------------------------------------ Local Send mini web server (same Wi-Fi)

    private fun startServer() {
        thread(isDaemon = true) {
            val ss = try { ServerSocket(53317) } catch (e: Exception) {
                try { ServerSocket(0) } catch (e2: Exception) { null }
            } ?: return@thread
            serverSocket = ss
            serverPort = ss.localPort
            runOnUiThread { if (cur == 4 || cur == 7) show(cur) }
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

    private fun handle(sock: Socket) {
        try {
            sock.soTimeout = 120000
            val ins = BufferedInputStream(sock.getInputStream())
            val out = sock.getOutputStream()
            val reqLine = readLine(ins) ?: return
            val parts = reqLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val path = parts[1].substringBefore('?')
            val headers = HashMap<String, String>()
            while (true) {
                val l = readLine(ins) ?: break
                if (l.isEmpty()) break
                val i = l.indexOf(':')
                if (i > 0) headers[l.substring(0, i).trim().lowercase(Locale.US)] = l.substring(i + 1).trim()
            }
            val ip = sock.inetAddress.hostAddress ?: "?"
            when {
                method == "GET" && path == "/" -> {
                    val html = WEB_PAGE.replace("%%NAME%%", TextUtils.htmlEncode(deviceName))
                    addDevice(ip)
                    addEvent("Device connected: $ip")
                    respond(out, "200 OK", "text/html; charset=utf-8", html.toByteArray())
                }
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
            .setTitle(t("incoming"))
            .setMessage(name + "\n" + fmt(size) + "\n" + t("from") + " " + ip)
            .setCancelable(false)
            .setPositiveButton(t("accept")) { _, _ ->
                val dest = uniqueFile(name)
                if (!tmp.renameTo(dest)) { tmp.copyTo(dest, true); tmp.delete() }
                addEvent(t("received") + ": " + dest.name)
                afterDownload(dest)
            }
            .setNegativeButton(t("reject")) { _, _ ->
                tmp.delete(); addEvent(t("rejected") + ": " + name)
            }
            .show()
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
  x.onload=function(){log(x.responseText)};
  x.onerror=function(){log('Connection failed')};
  x.send(m);
}
</script></body></html>"""
    }
}
