package com.tvremote

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.*
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

@SuppressLint("MissingPermission")
class MainActivity : Activity() {
    private val BG1 = Color.parseColor("#0B1020")
    private val BG2 = Color.parseColor("#1C1147")
    private val CARD = Color.parseColor("#26FFFFFF")
    private val ACCENT = Color.parseColor("#6C8CFF")
    private val PURPLE = Color.parseColor("#7C5CFF")
    private val BLUE = Color.parseColor("#3D7BFF")
    private val TEAL = Color.parseColor("#14C8A8")
    private val ORANGE = Color.parseColor("#FF8A3D")
    private val RED = Color.parseColor("#FF4D6D")
    private val GREEN = Color.parseColor("#2ECC71")
    private val AMBER = Color.parseColor("#FFB020")
    private val SLATE = Color.parseColor("#3A4166")

    private var adapter: BluetoothAdapter? = null
    private var hid: BluetoothHidDevice? = null
    private var host: BluetoothDevice? = null
    private var pending: BluetoothDevice? = null
    private lateinit var status: TextView
    private lateinit var dot: View
    private lateinit var input: EditText
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var remoteView: View
    private lateinit var logView: View
    private lateinit var tabRemote: TextView
    private lateinit var tabLog: TextView
    private val main = Handler(Looper.getMainLooper())
    private val cbExec = Executors.newSingleThreadExecutor()
    private val typer = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("tv", MODE_PRIVATE) }
    private val logLines = ArrayList<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val logFile by lazy { File(filesDir, "log.txt") }

    // HID descriptor: report 1 = keyboard, report 2 = consumer control (volume, home, back)
    private val desc = intArrayOf(
        0x05, 0x01, 0x09, 0x06, 0xA1, 0x01, 0x85, 0x01,
        0x05, 0x07, 0x19, 0xE0, 0x29, 0xE7, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x08, 0x81, 0x02,
        0x95, 0x01, 0x75, 0x08, 0x81, 0x01,
        0x95, 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65, 0x05, 0x07, 0x19, 0x00, 0x29, 0x65, 0x81, 0x00,
        0xC0,
        0x05, 0x0C, 0x09, 0x01, 0xA1, 0x01, 0x85, 0x02,
        0x15, 0x00, 0x26, 0xFF, 0x03, 0x19, 0x00, 0x2A, 0xFF, 0x03, 0x75, 0x10, 0x95, 0x01, 0x81, 0x00,
        0xC0
    ).map { it.toByte() }.toByteArray()

    private val cb = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            log("onAppStatusChanged registered=$registered plugged=${pluggedDevice?.name}")
            if (!registered) { setStatus("Bluetooth remote is not ready", RED); return }
            setStatus("Ready. Tap Pair TV, or Choose TV if already paired.", Color.GRAY)
            val last = prefs.getString("last", null)
            val d = adapter?.bondedDevices?.firstOrNull { it.address == last }
            if (d != null) { log("Auto-connecting to last TV"); runOnUiThread { connectTo(d) } }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            log("onConnectionStateChanged ${device?.name ?: device?.address} -> ${st(state)}")
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    host = device; pending = null
                    setStatus("Connected to " + (device?.name ?: device?.address), GREEN)
                }
                BluetoothProfile.STATE_CONNECTING -> setStatus("Connecting...", AMBER)
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (host == device) {
                        host = null
                        setStatus("Disconnected. Tap Choose TV.", AMBER)
                    } else if (pending == device) {
                        pending = null
                        setStatus("Could not connect. Open the Log tab.", RED)
                    }
                }
            }
        }

        override fun onGetReport(device: BluetoothDevice?, type: Byte, id: Byte, bufferSize: Int) {
            log("TV asked onGetReport type=$type id=$id size=$bufferSize (replied unsupported)")
            if (device != null) hid?.reportError(device, BluetoothHidDevice.ERROR_RSP_UNSUPPORTED_REQ)
        }

        override fun onSetReport(device: BluetoothDevice?, type: Byte, id: Byte, data: ByteArray?) {
            log("TV sent onSetReport type=$type id=$id len=${data?.size}")
            if (device != null) hid?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
        }

        override fun onSetProtocol(device: BluetoothDevice?, protocol: Byte) {
            log("TV set protocol=$protocol")
        }

        override fun onInterruptData(device: BluetoothDevice?, reportId: Byte, data: ByteArray?) {
            log("TV sent interrupt data id=$reportId len=${data?.size}")
        }

        override fun onVirtualCableUnplug(device: BluetoothDevice?) {
            log("TV sent virtual cable unplug (it removed this phone)")
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val prev = try { if (logFile.exists()) logFile.readText().takeLast(6000) else "" } catch (e: Exception) { "" }
        try { logFile.writeText("") } catch (e: Exception) {}
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { logFile.appendText("CRASH: " + Log.getStackTraceString(e) + "\n") } catch (x: Exception) {}
            old?.uncaughtException(t, e)
        }
        window.statusBarColor = BG1
        window.navigationBarColor = Color.parseColor("#141A33")
        buildUi()
        if (prev.isNotBlank()) log("--- previous session ---\n" + prev.trimEnd() + "\n--- end previous session ---")
        log("App started")
        val need = needed()
        if (need.isNotEmpty()) {
            log("Asking for Bluetooth permissions: " + need.joinToString { it.substringAfterLast('.') })
            requestPermissions(need, 1)
        } else setup()
    }

    private fun needed(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31)
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_SCAN
            ).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()
        else emptyArray()

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) {
        for (i in p.indices) log("Permission ${p[i].substringAfterLast('.')} granted=${r.getOrNull(i) == PackageManager.PERMISSION_GRANTED}")
        val ok = Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        if (ok) setup() else setStatus("Bluetooth permission is needed. Allow it in app settings.", RED)
    }

    private fun setup() {
        log("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val a = adapter
        if (a == null) { log("ERROR: no Bluetooth adapter"); setStatus("No Bluetooth on this phone", RED); return }
        log("Adapter name=${a.name} enabled=${a.isEnabled}")
        if (!a.isEnabled) { setStatus("Turn Bluetooth on, then reopen the app.", RED); return }
        val bonded = a.bondedDevices.toList()
        log("Paired devices: ${bonded.size}")
        bonded.forEach { log("  " + describe(it)) }
        val ok = a.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                log("HID service connected")
                val h = proxy as BluetoothHidDevice
                hid = h
                val sdp = BluetoothHidDeviceAppSdpSettings(
                    "Phone Remote", "TV remote", "Me", BluetoothHidDevice.SUBCLASS1_COMBO, desc
                )
                val reg = h.registerApp(sdp, null, null, cbExec, cb)
                log("registerApp() returned $reg")
                if (!reg) setStatus("Phone refused the remote setup. Restart Bluetooth and retry.", RED)
            }
            override fun onServiceDisconnected(profile: Int) { log("HID service disconnected"); hid = null }
        }, BluetoothProfile.HID_DEVICE)
        log("getProfileProxy(HID_DEVICE) returned $ok")
        if (!ok) setStatus("This phone does not support the Bluetooth keyboard feature", RED)
    }

    private fun st(s: Int) = when (s) {
        0 -> "DISCONNECTED"; 1 -> "CONNECTING"; 2 -> "CONNECTED"; 3 -> "DISCONNECTING"; else -> "STATE_$s"
    }

    private fun bond(s: Int) = when (s) { 10 -> "NOT_BONDED"; 11 -> "BONDING"; 12 -> "BONDED"; else -> "$s" }

    private fun describe(d: BluetoothDevice): String {
        val cls = d.bluetoothClass?.deviceClass ?: 0
        val u = d.uuids?.joinToString(",") { it.uuid.toString().substring(4, 8) } ?: "none"
        return "${d.name ?: "?"} [${d.address}] bond=${bond(d.bondState)} type=${d.type} class=0x${Integer.toHexString(cls)} uuids=$u"
    }

    private fun connectTo(d: BluetoothDevice) {
        val h = hid
        if (h == null) { log("connectTo: HID not ready"); toast("Bluetooth remote is not ready yet"); return }
        pending = d
        prefs.edit().putString("last", d.address).apply()
        setStatus("Connecting to " + (d.name ?: d.address) + "...", AMBER)
        log("Connecting to " + describe(d))
        log("Current state=${st(h.getConnectionState(d))}, connected devices=${h.connectedDevices.size}")
        val r = h.connect(d)
        log("connect() returned $r")
        if (!r) {
            pending = null
            setStatus("Phone refused to start connecting. Open the Log tab.", RED)
            return
        }
        main.postDelayed({
            if (pending == d && host != d) {
                pending = null
                log("Timed out after 12s waiting for the TV to accept")
                setStatus("TV did not accept. Open the Log tab, or tap Help.", RED)
            }
        }, 12000)
    }

    private fun setStatus(t: String, c: Int) {
        log("STATUS: $t")
        runOnUiThread {
            status.text = t
            dot.background = rect(c, 0f, true)
        }
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    private fun connected(): Boolean {
        if (hid == null || host == null) { toast("Not connected. Tap Choose TV."); return false }
        return true
    }

    private fun key(code: Int, mod: Int = 0) {
        if (!connected()) return
        val h = hid!!; val d = host!!
        typer.execute {
            val a = h.sendReport(d, 1, byteArrayOf(mod.toByte(), 0, code.toByte(), 0, 0, 0, 0, 0))
            Thread.sleep(30)
            val b = h.sendReport(d, 1, ByteArray(8))
            Thread.sleep(30)
            if (!a || !b) log("key 0x%02X send failed (press=$a release=$b)".format(code))
        }
    }

    private fun media(usage: Int) {
        if (!connected()) return
        val h = hid!!; val d = host!!
        typer.execute {
            val a = h.sendReport(d, 2, byteArrayOf((usage and 0xFF).toByte(), (usage shr 8).toByte()))
            Thread.sleep(30)
            val b = h.sendReport(d, 2, byteArrayOf(0, 0))
            Thread.sleep(30)
            if (!a || !b) log("media 0x%X send failed (press=$a release=$b)".format(usage))
        }
    }

    private fun map(c: Char): Pair<Int, Int>? = when (c) {
        in 'a'..'z' -> (0x04 + (c - 'a')) to 0
        in 'A'..'Z' -> (0x04 + (c - 'A')) to 2
        in '1'..'9' -> (0x1E + (c - '1')) to 0
        '0' -> 0x27 to 0
        ' ' -> 0x2C to 0
        '\n' -> 0x28 to 0
        '.' -> 0x37 to 0
        ',' -> 0x36 to 0
        '-' -> 0x2D to 0
        '/' -> 0x38 to 0
        ';' -> 0x33 to 0
        '\'' -> 0x34 to 0
        '!' -> 0x1E to 2
        '@' -> 0x1F to 2
        '?' -> 0x38 to 2
        ':' -> 0x33 to 2
        '_' -> 0x2D to 2
        '(' -> 0x26 to 2
        ')' -> 0x27 to 2
        else -> null
    }

    private fun sendText() {
        val t = input.text.toString()
        if (t.isEmpty() || !connected()) return
        var skipped = 0
        for (c in t) { val m = map(c); if (m == null) skipped++ else key(m.first, m.second) }
        log("Typed ${t.length} characters ($skipped skipped)")
        if (skipped > 0) toast("$skipped unsupported characters skipped")
        input.setText("")
    }

    private fun pickTv() {
        val list = adapter?.bondedDevices?.toList() ?: emptyList()
        if (list.isEmpty()) {
            toast("No paired devices yet. Tap Pair TV first.")
            return
        }
        val names = list.map { it.name ?: it.address }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Choose TV")
            .setItems(names) { _, i -> connectTo(list[i]) }
            .show()
    }

    private fun pairTv() {
        log("Pair TV tapped")
        toast("Allow 'discoverable', then on the TV pick this phone in Bluetooth / Pair remote or accessory.")
        try {
            startActivityForResult(
                Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                    .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300), 7
            )
        } catch (e: Exception) {
            log("Discoverable request failed: $e")
            openBtSettings()
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(req: Int, res: Int, d: Intent?) {
        super.onActivityResult(req, res, d)
        if (req == 7) {
            log("Discoverable result=$res (0 means denied)")
            if (res == RESULT_CANCELED) {
                toast("Discoverable was blocked. Opening Bluetooth settings: stay on that screen while the TV searches.")
                openBtSettings()
            } else setStatus("Phone is visible. On the TV, search for Bluetooth devices and pick this phone.", AMBER)
        }
    }

    private fun openBtSettings() {
        try { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) } catch (e: Exception) { log("Cannot open settings: $e") }
    }

    private fun help() {
        AlertDialog.Builder(this).setTitle("If it won't connect")
            .setMessage(
                "1. Phone Settings > Bluetooth: forget (unpair) the TV.\n" +
                "2. On the TV, remove this phone from its Bluetooth / remote list.\n" +
                "3. Open this app first and wait for \"Ready\".\n" +
                "4. Tap Pair TV, then on the TV search for Bluetooth devices and pick this phone.\n" +
                "5. Back in the app, tap Choose TV and select the TV.\n\n" +
                "Keep this app open while using it. If it still fails, open the Log tab, tap Copy and send the log to Claude."
            )
            .setPositiveButton("OK", null)
            .setNeutralButton("Bluetooth settings") { _, _ -> startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            .show()
    }

    // ---------- Log ----------
    private fun log(msg: String) {
        val line = fmt.format(Date()) + "  " + msg
        val all: String
        synchronized(logLines) {
            logLines.add(line)
            if (logLines.size > 1500) logLines.removeAt(0)
            all = logLines.joinToString("\n")
        }
        try { logFile.appendText(line + "\n") } catch (e: Exception) {}
        runOnUiThread {
            if (::logText.isInitialized) {
                logText.text = all
                logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    private fun logAsText() = synchronized(logLines) { logLines.joinToString("\n") }

    private fun copyLog() {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("log", logAsText()))
        toast("Log copied")
    }

    private fun shareLog() {
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, logAsText())
        startActivity(Intent.createChooser(i, "Share log"))
    }

    private fun clearLog() {
        synchronized(logLines) { logLines.clear() }
        try { logFile.writeText("") } catch (e: Exception) {}
        logText.text = ""
    }

    // ---------- UI ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun lighten(c: Int, f: Float) = Color.rgb(
        (Color.red(c) + (255 - Color.red(c)) * f).toInt(),
        (Color.green(c) + (255 - Color.green(c)) * f).toInt(),
        (Color.blue(c) + (255 - Color.blue(c)) * f).toInt()
    )

    private fun rect(color: Int, radius: Float, oval: Boolean = false) = GradientDrawable().apply {
        setColor(color)
        if (oval) setShape(GradientDrawable.OVAL) else setCornerRadius(radius)
    }

    private fun grad(c1: Int, c2: Int, radius: Float, oval: Boolean = false, stroke: Int = 0) =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(c1, c2)).apply {
            if (oval) setShape(GradientDrawable.OVAL) else setCornerRadius(radius)
            if (stroke != 0) setStroke(dp(2), stroke)
        }

    private fun keyBg(c: Int, radius: Float, oval: Boolean = false) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), grad(lighten(c, 0.55f), lighten(c, 0.35f), radius, oval))
        addState(intArrayOf(), grad(lighten(c, 0.22f), c, radius, oval))
    }

    private fun flatBg() = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), rect(Color.parseColor("#55FFFFFF"), 0f, true))
        addState(intArrayOf(), rect(Color.TRANSPARENT, 0f, true))
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun press(v: View, repeat: Boolean, action: () -> Unit) {
        val r = object : Runnable {
            override fun run() { action(); main.postDelayed(this, 110) }
        }
        v.setOnTouchListener { view, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    view.isPressed = true
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    action()
                    if (repeat) main.postDelayed(r, 450)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.isPressed = false
                    main.removeCallbacks(r)
                }
            }
            true
        }
    }

    private fun pill(label: String, color: Int = SLATE, repeat: Boolean = false, f: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            background = keyBg(color, dp(18).toFloat())
            setPadding(dp(8), dp(16), dp(8), dp(16))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }
            press(this, repeat, f)
        }

    private fun row(vararg v: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        v.forEach { addView(it) }
    }

    private fun label(t: String) = TextView(this).apply {
        text = t
        setTextColor(ACCENT)
        textSize = 12f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(6), dp(18), 0, dp(6))
    }

    private fun dpad(): View {
        val size = dp(270)
        val frame = FrameLayout(this).apply {
            background = grad(Color.parseColor("#2E3480"), Color.parseColor("#171B45"), 0f, true, Color.parseColor("#6C8CFF"))
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setMargins(0, dp(18), 0, dp(14))
            }
        }
        fun arrow(sym: String, g: Int, code: Int) {
            val t = TextView(this).apply {
                text = sym
                textSize = 28f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                background = flatBg()
                layoutParams = FrameLayout.LayoutParams(dp(86), dp(86), g)
            }
            press(t, true) { key(code) }
            frame.addView(t)
        }
        arrow("▲", Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0x52)
        arrow("▼", Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0x51)
        arrow("◀", Gravity.START or Gravity.CENTER_VERTICAL, 0x50)
        arrow("▶", Gravity.END or Gravity.CENTER_VERTICAL, 0x4F)
        val ok = TextView(this).apply {
            text = "OK"
            textSize = 22f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = keyBg(PURPLE, 0f, true)
            layoutParams = FrameLayout.LayoutParams(dp(100), dp(100), Gravity.CENTER)
        }
        press(ok, false) { key(0x28) }
        frame.addView(ok)
        return frame
    }

    private fun tab(t: String, f: () -> Unit) = TextView(this).apply {
        text = t
        textSize = 15f
        gravity = Gravity.CENTER
        setPadding(0, dp(16), 0, dp(16))
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        setOnClickListener { f() }
    }

    private fun showTab(lg: Boolean) {
        remoteView.visibility = if (lg) View.GONE else View.VISIBLE
        logView.visibility = if (lg) View.VISIBLE else View.GONE
        tabRemote.setTextColor(if (lg) Color.GRAY else ACCENT)
        tabLog.setTextColor(if (lg) ACCENT else Color.GRAY)
        tabRemote.setTypeface(null, if (lg) Typeface.NORMAL else Typeface.BOLD)
        tabLog.setTypeface(null, if (lg) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun buildLogView(): View {
        logText = TextView(this).apply {
            setTextColor(Color.parseColor("#C8D3F5"))
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        logScroll = ScrollView(this).apply { addView(logText) }
        val bar = row(pill("Copy", TEAL) { copyLog() }, pill("Share", BLUE) { shareLog() }, pill("Clear", RED) { clearLog() })
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(8), dp(8), dp(8), 0)
            addView(bar)
            addView(logScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }

    private fun buildUi() {
        dot = View(this).apply { background = rect(Color.GRAY, 0f, true) }
        status = TextView(this).apply {
            text = "Starting..."
            setTextColor(Color.WHITE)
            textSize = 15f
        }
        val title = TextView(this).apply {
            text = "Phone Remote  v3"
            textSize = 26f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(6), 0, 0, dp(10))
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rect(CARD, dp(18).toFloat())
            setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(dot, LinearLayout.LayoutParams(dp(12), dp(12)).apply { rightMargin = dp(12) })
            addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        input = EditText(this).apply {
            hint = "Type or dictate here, then tap Send"
            setHintTextColor(Color.parseColor("#8890B5"))
            setTextColor(Color.WHITE)
            textSize = 17f
            minLines = 2
            gravity = Gravity.TOP
            background = grad(Color.parseColor("#26FFFFFF"), Color.parseColor("#1AFFFFFF"), dp(18).toFloat(), false, Color.parseColor("#556C8CFF"))
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        val remote = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
            addView(title)
            addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
            addView(row(pill("Pair TV", PURPLE) { pairTv() }, pill("Choose TV", BLUE) { pickTv() }, pill("Help") { help() }))
            addView(dpad())
            addView(row(pill("Back", ORANGE) { media(0x224) }, pill("Home", TEAL) { media(0x223) }))
            addView(row(pill("Vol -", BLUE, true) { media(0xEA) }, pill("Mute", RED) { media(0xE2) }, pill("Vol +", BLUE, true) { media(0xE9) }))
            addView(label("KEYBOARD  (tap the mic on your keyboard to speak)"))
            addView(input)
            addView(row(pill("Send", PURPLE) { sendText() }, pill("Delete", AMBER, true) { key(0x2A) }, pill("Enter", GREEN) { key(0x28) }))
        }
        remoteView = ScrollView(this).apply { isFillViewport = true; addView(remote) }
        logView = buildLogView()
        tabRemote = tab("Remote") { showTab(false) }
        tabLog = tab("Log") { showTab(true) }
        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#141A33"))
            addView(tabRemote)
            addView(tabLog)
        }
        val content = FrameLayout(this).apply { addView(remoteView); addView(logView) }
        val screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(BG1, BG2))
            addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(tabBar)
        }
        setContentView(screen)
        showTab(false)
    }

    override fun onDestroy() {
        log("App closing")
        hid?.unregisterApp()
        super.onDestroy()
    }
}
