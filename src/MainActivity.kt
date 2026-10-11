package com.tvremote

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.*
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
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.util.concurrent.Executors

@SuppressLint("MissingPermission")
class MainActivity : Activity() {
    private val BG = Color.parseColor("#0F1115")
    private val CARD = Color.parseColor("#1B1F27")
    private val KEY = Color.parseColor("#2A303B")
    private val KEY_DOWN = Color.parseColor("#3B4352")
    private val ACCENT = Color.parseColor("#4F8CFF")
    private val ACCENT_DOWN = Color.parseColor("#7FA9FF")
    private val GREEN = Color.parseColor("#3DDC84")
    private val AMBER = Color.parseColor("#FFB020")
    private val RED = Color.parseColor("#FF5A5F")

    private var adapter: BluetoothAdapter? = null
    private var hid: BluetoothHidDevice? = null
    private var host: BluetoothDevice? = null
    private var pending: BluetoothDevice? = null
    private lateinit var status: TextView
    private lateinit var dot: View
    private lateinit var input: EditText
    private val main = Handler(Looper.getMainLooper())
    private val cbExec = Executors.newSingleThreadExecutor()
    private val typer = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("tv", MODE_PRIVATE) }

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
            if (!registered) { setStatus("Bluetooth remote is not ready", RED); return }
            setStatus("Ready. Tap Pair TV, or Choose TV if already paired.", Color.GRAY)
            val last = prefs.getString("last", null)
            val d = adapter?.bondedDevices?.firstOrNull { it.address == last }
            if (d != null) runOnUiThread { connectTo(d) }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
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
                        setStatus("Could not connect. Tap Help for the fix.", RED)
                    }
                }
            }
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        buildUi()
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
        } else setup()
    }

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) {
        if (r.isNotEmpty() && r[0] == PackageManager.PERMISSION_GRANTED) setup()
        else setStatus("Bluetooth permission is needed. Allow it in app settings.", RED)
    }

    private fun setup() {
        adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val a = adapter
        if (a == null || !a.isEnabled) { setStatus("Turn Bluetooth on, then reopen the app.", RED); return }
        a.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                val h = proxy as BluetoothHidDevice
                hid = h
                val sdp = BluetoothHidDeviceAppSdpSettings(
                    "Phone Remote", "TV remote", "Me", BluetoothHidDevice.SUBCLASS1_COMBO, desc
                )
                if (!h.registerApp(sdp, null, null, cbExec, cb)) {
                    setStatus("Your phone refused the remote setup. Restart Bluetooth and retry.", RED)
                }
            }
            override fun onServiceDisconnected(profile: Int) { hid = null }
        }, BluetoothProfile.HID_DEVICE)
    }

    private fun connectTo(d: BluetoothDevice) {
        val h = hid ?: return
        pending = d
        prefs.edit().putString("last", d.address).apply()
        setStatus("Connecting to " + (d.name ?: d.address) + "...", AMBER)
        h.connect(d)
        main.postDelayed({
            if (pending == d && host != d) {
                pending = null
                setStatus("Could not connect. Tap Help for the fix.", RED)
            }
        }, 12000)
    }

    private fun setStatus(t: String, c: Int) = runOnUiThread {
        status.text = t
        dot.background = rect(c, 0f, true)
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
            h.sendReport(d, 1, byteArrayOf(mod.toByte(), 0, code.toByte(), 0, 0, 0, 0, 0))
            Thread.sleep(30)
            h.sendReport(d, 1, ByteArray(8))
            Thread.sleep(30)
        }
    }

    private fun media(usage: Int) {
        if (!connected()) return
        val h = hid!!; val d = host!!
        typer.execute {
            h.sendReport(d, 2, byteArrayOf((usage and 0xFF).toByte(), (usage shr 8).toByte()))
            Thread.sleep(30)
            h.sendReport(d, 2, byteArrayOf(0, 0))
            Thread.sleep(30)
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
        toast("Now on the TV: Settings > Remote & Accessories > Pair remote or accessory, then pick this phone.")
        startActivity(
            Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
        )
    }

    private fun help() {
        AlertDialog.Builder(this).setTitle("If it won't connect")
            .setMessage(
                "1. Phone Settings > Bluetooth: forget (unpair) the TV.\n" +
                "2. On the TV, Settings > Remote & Accessories: remove this phone.\n" +
                "3. Open this app first and wait for \"Ready\".\n" +
                "4. Tap Pair TV, then on the TV choose Pair remote or accessory and pick this phone.\n" +
                "5. Back in the app, tap Choose TV and select the TV.\n\n" +
                "Keep this app open while using it."
            )
            .setPositiveButton("OK", null).show()
    }

    // ---------- UI ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rect(color: Int, radius: Float, oval: Boolean = false) = GradientDrawable().apply {
        setColor(color)
        if (oval) setShape(GradientDrawable.OVAL) else setCornerRadius(radius)
    }

    private fun keyBg(color: Int, down: Int, radius: Float, oval: Boolean = false) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), rect(down, radius, oval))
        addState(intArrayOf(), rect(color, radius, oval))
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

    private fun pill(label: String, color: Int = KEY, repeat: Boolean = false, f: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            background = keyBg(color, if (color == KEY) KEY_DOWN else ACCENT_DOWN, dp(16).toFloat())
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
        setTextColor(Color.GRAY)
        textSize = 13f
        setPadding(dp(6), dp(14), 0, dp(4))
    }

    private fun dpad(): View {
        val size = dp(260)
        val frame = FrameLayout(this).apply {
            background = rect(CARD, 0f, true)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setMargins(0, dp(16), 0, dp(16))
            }
        }
        fun arrow(sym: String, g: Int, code: Int) {
            val t = TextView(this).apply {
                text = sym
                textSize = 26f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                background = keyBg(Color.TRANSPARENT, KEY_DOWN, 0f, true)
                layoutParams = FrameLayout.LayoutParams(dp(84), dp(84), g)
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
            textSize = 20f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = keyBg(ACCENT, ACCENT_DOWN, 0f, true)
            layoutParams = FrameLayout.LayoutParams(dp(96), dp(96), Gravity.CENTER)
        }
        press(ok, false) { key(0x28) }
        frame.addView(ok)
        return frame
    }

    private fun buildUi() {
        dot = View(this).apply { background = rect(Color.GRAY, 0f, true) }
        status = TextView(this).apply {
            text = "Starting..."
            setTextColor(Color.WHITE)
            textSize = 15f
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rect(CARD, dp(16).toFloat())
            setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(dot, LinearLayout.LayoutParams(dp(12), dp(12)).apply { rightMargin = dp(12) })
            addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        input = EditText(this).apply {
            hint = "Type or dictate here, then tap Send"
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            textSize = 16f
            minLines = 2
            gravity = Gravity.TOP
            background = rect(CARD, dp(16).toFloat())
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(28))
            setBackgroundColor(BG)
            addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
            addView(row(pill("Pair TV") { pairTv() }, pill("Choose TV") { pickTv() }, pill("Help") { help() }))
            addView(dpad())
            addView(row(pill("Back") { media(0x224) }, pill("Home") { media(0x223) }))
            addView(row(pill("Vol -", repeat = true) { media(0xEA) }, pill("Mute") { media(0xE2) }, pill("Vol +", repeat = true) { media(0xE9) }))
            addView(label("KEYBOARD"))
            addView(input)
            addView(row(pill("Send", ACCENT) { sendText() }, pill("Delete", repeat = true) { key(0x2A) }, pill("Enter") { key(0x28) }))
        }
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(BG)
            addView(root)
        })
    }

    override fun onDestroy() {
        hid?.unregisterApp()
        super.onDestroy()
    }
}
