package com.tvremote

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.*
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.util.concurrent.Executors

@SuppressLint("MissingPermission")
class MainActivity : Activity() {
    private var adapter: BluetoothAdapter? = null
    private var hid: BluetoothHidDevice? = null
    private var host: BluetoothDevice? = null
    private lateinit var status: TextView
    private lateinit var input: EditText
    private val cbExec = Executors.newSingleThreadExecutor()
    private val typer = Executors.newSingleThreadExecutor()

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
            ui(if (registered) "Ready. Pair with your TV or tap Choose TV." else "Not ready")
        }
        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            if (state == BluetoothProfile.STATE_CONNECTED) {
                host = device
                ui("Connected: " + (device?.name ?: device?.address))
            } else if (state == BluetoothProfile.STATE_DISCONNECTED && host == device) {
                host = null
                ui("Disconnected. Tap Choose TV.")
            }
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        buildUi()
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
        } else setup()
    }

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) {
        if (r.isNotEmpty() && r[0] == PackageManager.PERMISSION_GRANTED) setup()
        else ui("Bluetooth permission is needed. Allow it in app settings.")
    }

    private fun setup() {
        adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val a = adapter
        if (a == null || !a.isEnabled) { ui("Turn Bluetooth on, then reopen the app."); return }
        a.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                val h = proxy as BluetoothHidDevice
                hid = h
                val sdp = BluetoothHidDeviceAppSdpSettings(
                    "Phone Remote", "TV remote", "Me", BluetoothHidDevice.SUBCLASS1_COMBO, desc
                )
                h.registerApp(sdp, null, null, cbExec, cb)
            }
            override fun onServiceDisconnected(profile: Int) { hid = null }
        }, BluetoothProfile.HID_DEVICE)
    }

    private fun ui(t: String) = runOnUiThread { status.text = t }

    private fun connected(): Boolean {
        if (hid == null || host == null) { toast("Not connected. Tap Choose TV."); return false }
        return true
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

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
            toast("Pair first: tap Make discoverable, then pair from the TV's Bluetooth settings")
            return
        }
        val names = list.map { it.name ?: it.address }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Choose TV")
            .setItems(names) { _, i -> hid?.connect(list[i]); ui("Connecting...") }
            .show()
    }

    private fun discoverable() {
        startActivity(
            Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
        )
    }

    private fun btn(t: String, f: () -> Unit) = Button(this).apply {
        text = t; textSize = 18f
        setOnClickListener { f() }
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }

    private fun gap() = Space(this).apply {
        layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
    }

    private fun row(vararg v: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        v.forEach { addView(it) }
    }

    private fun buildUi() {
        status = TextView(this).apply { textSize = 16f; text = "Starting..." }
        input = EditText(this).apply { hint = "Type here, or tap the mic on your keyboard" }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 48, 24, 24)
            addView(status)
            addView(row(btn("Make discoverable") { discoverable() }, btn("Choose TV") { pickTv() }))
            addView(row(gap(), btn("Up") { key(0x52) }, gap()))
            addView(row(btn("Left") { key(0x50) }, btn("OK") { key(0x28) }, btn("Right") { key(0x4F) }))
            addView(row(gap(), btn("Down") { key(0x51) }, gap()))
            addView(row(btn("Back") { media(0x224) }, btn("Home") { media(0x223) }))
            addView(row(btn("Vol -") { media(0xEA) }, btn("Mute") { media(0xE2) }, btn("Vol +") { media(0xE9) }))
            addView(input)
            addView(row(btn("Send") { sendText() }, btn("Delete") { key(0x2A) }, btn("Enter") { key(0x28) }))
        }
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onDestroy() {
        hid?.unregisterApp()
        super.onDestroy()
    }
}
