package com.tvremote

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

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
    private val ROSE = Color.parseColor("#B04A7A")

    data class Tv(val name: String, val host: String)

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
    private val net = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("tv", MODE_PRIVATE) }
    private val logLines = ArrayList<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val logFile by lazy { File(filesDir, "log.txt") }

    private val ALIAS = "tvremote_client"
    private var privateKey: PrivateKey? = null
    private var clientCert: X509Certificate? = null
    private val writeLock = Any()
    @Volatile private var sock: SSLSocket? = null
    @Volatile private var ready = false
    @Volatile private var connecting = false
    @Volatile private var closing = false
    @Volatile private var manualOff = false
    private val gen = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var current: Tv? = null
    private val codeQ = LinkedBlockingQueue<String>()
    private val found = ConcurrentHashMap<String, Tv>()
    private val resolveQ = ConcurrentLinkedQueue<NsdServiceInfo>()
    private var resolving = false
    private val nsd by lazy { getSystemService(NSD_SERVICE) as NsdManager }

    // ---------- tiny protobuf helpers ----------
    object Pb {
        fun varint(v0: Long): ByteArray {
            val o = ByteArrayOutputStream()
            var v = v0
            while ((v and 0x7FL.inv()) != 0L) {
                o.write(((v and 0x7FL) or 0x80L).toInt())
                v = v ushr 7
            }
            o.write(v.toInt())
            return o.toByteArray()
        }
        private fun tag(n: Int, wt: Int) = varint(((n shl 3) or wt).toLong())
        fun f(n: Int, v: Long): ByteArray = tag(n, 0) + varint(v)
        fun f(n: Int, b: ByteArray): ByteArray = tag(n, 2) + varint(b.size.toLong()) + b
        fun f(n: Int, s: String): ByteArray = f(n, s.toByteArray(Charsets.UTF_8))
        fun parse(b: ByteArray): Map<Int, List<Any>> {
            val m = LinkedHashMap<Int, MutableList<Any>>()
            var p = 0
            fun rv(): Long {
                var r = 0L
                var s = 0
                while (true) {
                    val x = b[p++].toInt() and 0xFF
                    r = r or ((x and 0x7F).toLong() shl s)
                    if ((x and 0x80) == 0) break
                    s += 7
                }
                return r
            }
            while (p < b.size) {
                val k = rv()
                val n = (k shr 3).toInt()
                val wt = (k and 7L).toInt()
                val v: Any = when (wt) {
                    0 -> rv()
                    2 -> { val l = rv().toInt(); val a = b.copyOfRange(p, p + l); p += l; a }
                    1 -> { p += 8; 0L }
                    5 -> { p += 4; 0L }
                    else -> throw IOException("bad wire type $wt")
                }
                m.getOrPut(n) { ArrayList() }.add(v)
            }
            return m
        }
    }

    private fun writeFrame(o: OutputStream, m: ByteArray) {
        synchronized(writeLock) {
            o.write(Pb.varint(m.size.toLong()))
            o.write(m)
            o.flush()
        }
    }

    private fun readFrame(i: InputStream): ByteArray {
        var len = 0
        var s = 0
        while (true) {
            val x = i.read()
            if (x < 0) throw IOException("connection closed by TV")
            len = len or ((x and 0x7F) shl s)
            if ((x and 0x80) == 0) break
            s += 7
        }
        if (len < 0 || len > 1_000_000) throw IOException("bad frame size $len")
        val b = ByteArray(len)
        var off = 0
        while (off < len) {
            val r = i.read(b, off, len - off)
            if (r < 0) throw IOException("connection closed by TV")
            off += r
        }
        return b
    }

    // ---------- TLS with our own client certificate ----------
    private class FixedKm(val key: PrivateKey, val chain: Array<X509Certificate>) : X509KeyManager {
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> = arrayOf("me")
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String = "me"
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
        override fun getCertificateChain(alias: String?): Array<X509Certificate> = chain
        override fun getPrivateKey(alias: String?): PrivateKey = key
    }

    private val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(c: Array<X509Certificate>?, a: String?) {}
        override fun checkServerTrusted(c: Array<X509Certificate>?, a: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    private fun der(tag: Int, vararg parts: ByteArray): ByteArray {
        val body = ByteArrayOutputStream()
        parts.forEach { body.write(it) }
        val c = body.toByteArray()
        val o = ByteArrayOutputStream()
        o.write(tag)
        if (c.size < 128) o.write(c.size)
        else if (c.size < 256) { o.write(0x81); o.write(c.size) }
        else { o.write(0x82); o.write(c.size shr 8); o.write(c.size and 0xFF) }
        o.write(c)
        return o.toByteArray()
    }

    private fun selfSigned(pub: PublicKey, priv: PrivateKey): X509Certificate {
        val algId = der(0x30,
            der(0x06, byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B)),
            byteArrayOf(0x05, 0x00))
        val name = der(0x30, der(0x31, der(0x30,
            der(0x06, byteArrayOf(0x55, 0x04, 0x03)), der(0x0C, "phone-remote".toByteArray()))))
        val validity = der(0x30, der(0x17, "250101000000Z".toByteArray()), der(0x17, "491231235959Z".toByteArray()))
        val tbs = der(0x30,
            byteArrayOf(0xA0.toByte(), 0x03, 0x02, 0x01, 0x02),
            der(0x02, byteArrayOf(0x01)),
            algId, name, validity, name, pub.encoded)
        val sg = Signature.getInstance("SHA256withRSA")
        sg.initSign(priv)
        sg.update(tbs)
        val sig = sg.sign()
        val cert = der(0x30, tbs, algId, der(0x03, byteArrayOf(0), sig))
        return CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(cert)) as X509Certificate
    }

    private fun initKeys() {
        try {
            val kf = File(filesDir, "client.key")
            val cf = File(filesDir, "client.crt")
            if (!kf.exists() || !cf.exists()) {
                val kpg = KeyPairGenerator.getInstance("RSA")
                kpg.initialize(2048)
                val kp = kpg.generateKeyPair()
                kf.writeBytes(kp.private.encoded)
                cf.writeBytes(selfSigned(kp.public, kp.private).encoded)
                log("Generated a new client key (you will need to pair again)")
            }
            privateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(kf.readBytes()))
            clientCert = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(cf.readBytes())) as X509Certificate
            log("Client key ready")
        } catch (e: Exception) {
            log("KEY ERROR: $e")
            setStatus("Could not create the security key on this phone", RED)
        }
    }

    private fun fp(c: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(c.encoded).joinToString("") { "%02x".format(it) }

    private fun pinned(pin: String): X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(c: Array<X509Certificate>?, a: String?) {}
        override fun checkServerTrusted(c: Array<X509Certificate>?, a: String?) {
            val got = c?.firstOrNull()?.let { fp(it) }
            if (got != pin) throw java.security.cert.CertificateException("TV certificate changed")
        }
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    private fun tls(host: String, port: Int, pin: String? = null): SSLSocket {
        val k = privateKey ?: throw IOException("no client key")
        val c = clientCert ?: throw IOException("no client certificate")
        val ctx = SSLContext.getInstance("TLSv1.2")
        val tm: TrustManager = if (pin == null) trustAll else pinned(pin)
        ctx.init(arrayOf(FixedKm(k, arrayOf(c))), arrayOf(tm), null)
        val s = ctx.socketFactory.createSocket() as SSLSocket
        s.connect(InetSocketAddress(host, port), 8000)
        s.soTimeout = 20000
        s.startHandshake()
        s.keepAlive = true
        log("TLS connected to $host:$port (${s.session.protocol}, ${s.session.cipherSuite})")
        return s
    }

    // ---------- Pairing (port 6467) ----------
    private fun unsigned(n: BigInteger): ByteArray {
        val b = n.toByteArray()
        return if (b.size > 1 && b[0] == 0.toByte()) b.copyOfRange(1, b.size) else b
    }

    private fun pmsg(field: Int, body: ByteArray) = Pb.f(1, 2L) + Pb.f(2, 200L) + Pb.f(field, body)

    private fun askCode() {
        runOnUiThread {
            val e = EditText(this).apply {
                hint = "6 characters"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            }
            AlertDialog.Builder(this).setTitle("Enter the code shown on the TV").setView(e).setCancelable(false)
                .setPositiveButton("OK") { _, _ -> codeQ.offer(e.text.toString()) }
                .setNegativeButton("Cancel") { _, _ -> codeQ.offer("") }
                .show()
        }
    }

    private fun pair(tv: Tv) {
        Thread {
            var s: SSLSocket? = null
            try {
                setStatus("Pairing with ${tv.name}...", AMBER)
                log("Pairing: connecting to ${tv.host}:6467")
                s = tls(tv.host, 6467)
                val inp = s.inputStream
                val out = s.outputStream
                val serverCert = s.session.peerCertificates[0] as X509Certificate

                fun recv(step: String): Map<Int, List<Any>> {
                    val m = Pb.parse(readFrame(inp))
                    val st = m[2]?.firstOrNull() as? Long
                    log("Pairing [$step] TV replied, fields=${m.keys}, status=$st")
                    if (st != null && st != 200L) throw IOException("TV rejected pairing step '$step' (status $st)")
                    return m
                }

                val enc = Pb.f(1, 3L) + Pb.f(2, 6L)
                writeFrame(out, pmsg(10, Pb.f(1, "androidtvremote") + Pb.f(2, "Phone Remote")))
                recv("request")
                writeFrame(out, pmsg(20, Pb.f(1, enc) + Pb.f(3, 1L)))
                recv("options")
                writeFrame(out, pmsg(30, Pb.f(1, enc) + Pb.f(2, 1L)))
                recv("configuration")

                codeQ.clear()
                setStatus("Type the code shown on your TV screen", AMBER)
                askCode()
                val code = codeQ.poll(180, TimeUnit.SECONDS)?.trim()?.uppercase() ?: ""
                if (code.length != 6 || !code.all { it in '0'..'9' || it in 'A'..'F' }) {
                    throw IOException("No valid 6-character code entered")
                }
                val cb = ByteArray(3) { code.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
                val cc = clientCert!!.publicKey as RSAPublicKey
                val sc = serverCert.publicKey as? RSAPublicKey ?: throw IOException("TV certificate is not RSA")
                val md = MessageDigest.getInstance("SHA-256")
                md.update(unsigned(cc.modulus))
                md.update(unsigned(cc.publicExponent))
                md.update(unsigned(sc.modulus))
                md.update(unsigned(sc.publicExponent))
                md.update(cb, 1, cb.size - 1)
                val secret = md.digest()
                log("Code check byte matches: ${secret[0] == cb[0]}")
                writeFrame(out, pmsg(40, Pb.f(1, secret)))
                val r = recv("secret")
                log("Pairing finished, secret ack present=${r.containsKey(41)}")
                prefs.edit().putString("pin_" + tv.name, fp(serverCert)).apply()
                setStatus("Paired. Connecting...", GREEN)
                try { s.close() } catch (e: Exception) {}
                Thread.sleep(800)
                connectRemote(tv)
            } catch (e: Exception) {
                log("Pairing failed: $e")
                setStatus("Pairing failed: ${e.message}. Open the Log tab.", RED)
                try { s?.close() } catch (x: Exception) {}
            }
        }.start()
    }

    // ---------- Remote connection (port 6466) ----------
    private fun saved(): List<Tv> =
        (prefs.getString("saved", "") ?: "").split("\n").filter { it.contains("\t") }
            .map { val p = it.split("\t"); Tv(p[1], p[0]) }

    private fun saveList(l: List<Tv>) {
        prefs.edit().putString("saved", l.joinToString("\n") { it.host + "\t" + it.name }).apply()
    }

    private fun saveTv(tv: Tv) {
        saveList(saved().filter { it.name != tv.name && it.host != tv.host } + tv)
        prefs.edit().putString("last", tv.name).apply()
    }

    private fun resolveHost(tv: Tv): Tv {
        val f = found.values.firstOrNull { it.name == tv.name }
        return if (f != null && f.host != tv.host) { log("TV address changed to ${f.host}"); tv.copy(host = f.host) } else tv
    }

    private fun closeSock() {
        closing = true
        ready = false
        current = null
        try { sock?.close() } catch (e: Exception) {}
        sock = null
    }

    private fun connectRemote(tv0: Tv) {
        if (connecting && !closing) { log("Already connecting"); return }
        connecting = true
        manualOff = false
        closeSock()
        closing = false
        val my = gen.incrementAndGet()
        val tv = resolveHost(tv0)
        setStatus("Connecting to ${tv.name}...", AMBER)
        Thread {
            var wasReady = false
            var pinProblem = false
            try {
                val pin = prefs.getString("pin_" + tv.name, null)
                val s = tls(tv.host, 6466, pin)
                if (pin == null) {
                    prefs.edit().putString("pin_" + tv.name, fp(s.session.peerCertificates[0] as X509Certificate)).apply()
                    log("Remembered this TV's certificate")
                }
                s.soTimeout = 0
                sock = s
                val inp = s.inputStream
                val devInfo = Pb.f(1, "Phone Remote") + Pb.f(2, "Android") + Pb.f(3, 1L) +
                    Pb.f(4, "1") + Pb.f(5, "com.tvremote.wifi") + Pb.f(6, "1.0.0")
                while (true) {
                    val m = Pb.parse(readFrame(inp))
                    when {
                        m.containsKey(1) -> {
                            log("TV sent configure; replying")
                            writeFrame(s.outputStream, Pb.f(1, Pb.f(1, 622L) + Pb.f(2, devInfo)))
                        }
                        m.containsKey(2) -> {
                            log("TV sent set_active; replying")
                            writeFrame(s.outputStream, Pb.f(2, Pb.f(1, 622L)))
                            if (!ready) { wasReady = true; onReady(tv) }
                        }
                        m.containsKey(8) -> {
                            val pp = Pb.parse(m[8]!![0] as ByteArray)
                            val v = pp[1]?.firstOrNull() as? Long ?: 0L
                            writeFrame(s.outputStream, Pb.f(9, Pb.f(1, v)))
                        }
                        m.containsKey(40) -> { log("TV sent start"); if (!ready) { wasReady = true; onReady(tv) } }
                        m.containsKey(3) -> log("TV sent an error message")
                        else -> log("TV message fields=${m.keys}")
                    }
                }
            } catch (e: Exception) {
                pinProblem = generateSequence<Throwable>(e) { it.cause }.any { it.message?.contains("certificate changed") == true }
                if (!closing) log("Connection ended: $e")
            }
            if (gen.get() != my) return@Thread
            ready = false
            current = null
            if (!closing) {
                if (pinProblem) setStatus("This TV's identity changed, so I did not connect. If you reset the TV, forget it in Devices and pair again.", RED)
                else if (wasReady) setStatus("Disconnected. Tap Connect.", AMBER)
                else setStatus("Could not connect. If this TV was never paired, tap Pair TV. Otherwise check the TV is on and on the same Wi-Fi.", RED)
            }
            connecting = false
        }.start()
    }

    private fun onReady(tv: Tv) {
        ready = true
        current = tv
        saveTv(tv)
        setStatus("Connected to ${tv.name}", GREEN)
    }

    private fun keyMsg(code: Int, dir: Int) = Pb.f(10, Pb.f(1, code.toLong()) + Pb.f(2, dir.toLong()))

    private fun sendRemote(m: ByteArray) {
        val o = sock?.outputStream ?: throw IOException("not connected")
        writeFrame(o, m)
    }

    private fun lost(e: Exception) {
        log("Send failed: $e")
        ready = false
        setStatus("Connection lost. Tap Connect.", RED)
    }

    private fun key(code: Int) {
        if (!ready) { toast("Not connected. Tap Connect."); return }
        net.execute { try { sendRemote(keyMsg(code, 3)) } catch (e: Exception) { lost(e) } }
    }

    private fun keyFor(c: Char): Pair<Int, Boolean>? = when (c) {
        in 'a'..'z' -> (29 + (c - 'a')) to false
        in 'A'..'Z' -> (29 + (c - 'A')) to true
        in '0'..'9' -> (7 + (c - '0')) to false
        ' ' -> 62 to false
        '\n' -> 66 to false
        '.' -> 56 to false
        ',' -> 55 to false
        '-' -> 69 to false
        '/' -> 76 to false
        ';' -> 74 to false
        '\'' -> 75 to false
        '@' -> 77 to false
        '=' -> 70 to false
        '_' -> 69 to true
        '?' -> 76 to true
        ':' -> 74 to true
        '!' -> 8 to true
        '(' -> 16 to true
        ')' -> 7 to true
        else -> null
    }

    private fun sendText() {
        val t = input.text.toString()
        if (t.isEmpty()) return
        if (!ready) { toast("Not connected. Tap Connect."); return }
        var skipped = 0
        val seq = ArrayList<Pair<Int, Boolean>>()
        for (c in t) { val m = keyFor(c); if (m == null) skipped++ else seq.add(m) }
        net.execute {
            try {
                for ((code, shift) in seq) {
                    if (shift) sendRemote(keyMsg(59, 1))
                    sendRemote(keyMsg(code, 3))
                    if (shift) sendRemote(keyMsg(59, 2))
                    Thread.sleep(25)
                }
            } catch (e: Exception) { lost(e) }
        }
        log("Typed ${seq.size} characters ($skipped skipped)")
        if (skipped > 0) toast("$skipped unsupported characters skipped (English letters, digits and common symbols only)")
        input.setText("")
    }

    // ---------- Finding TVs ----------
    private val disc = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(t: String) { log("Searching for TVs on Wi-Fi...") }
        override fun onServiceFound(i: NsdServiceInfo) { log("Found service: ${i.serviceName}"); resolveQ.add(i); nextResolve() }
        override fun onServiceLost(i: NsdServiceInfo) { log("Service lost: ${i.serviceName}") }
        override fun onDiscoveryStopped(t: String) {}
        override fun onStartDiscoveryFailed(t: String, e: Int) { log("Discovery failed to start: $e") }
        override fun onStopDiscoveryFailed(t: String, e: Int) {}
    }

    private fun startDiscovery() {
        try { nsd.discoverServices("_androidtvremote2._tcp", NsdManager.PROTOCOL_DNS_SD, disc) }
        catch (e: Exception) { log("Discovery error: $e") }
    }

    @Suppress("DEPRECATION")
    @Synchronized
    private fun nextResolve() {
        if (resolving) return
        val i = resolveQ.poll() ?: return
        resolving = true
        try {
            nsd.resolveService(i, object : NsdManager.ResolveListener {
                override fun onResolveFailed(si: NsdServiceInfo, err: Int) {
                    log("Could not resolve ${si.serviceName}: $err")
                    resolving = false
                    nextResolve()
                }
                override fun onServiceResolved(si: NsdServiceInfo) {
                    val addr = if (Build.VERSION.SDK_INT >= 34)
                        (si.hostAddresses.firstOrNull { it is Inet4Address } ?: si.hostAddresses.firstOrNull())?.hostAddress
                    else si.host?.hostAddress
                    if (addr != null) {
                        found[addr] = Tv(si.serviceName, addr)
                        log("TV found: ${si.serviceName} at $addr")
                    }
                    resolving = false
                    nextResolve()
                }
            })
        } catch (e: Exception) {
            log("Resolve error: $e")
            resolving = false
        }
    }

    private fun askIp(then: (Tv) -> Unit) {
        val e = EditText(this).apply {
            hint = "e.g. 192.168.1.50"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        AlertDialog.Builder(this).setTitle("TV IP address (TV Settings > Network)").setView(e)
            .setPositiveButton("OK") { _, _ ->
                val ip = e.text.toString().trim()
                if (ip.isNotEmpty()) then(Tv("TV $ip", ip))
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun pairTv() {
        val list = (found.values + saved()).distinctBy { it.host }
        val items = list.map { it.name + "  (" + it.host + ")" } + "Enter IP address..."
        if (list.isEmpty()) toast("Still searching. If your TV doesn't appear, use Enter IP address.")
        AlertDialog.Builder(this).setTitle("Pair which TV?")
            .setItems(items.toTypedArray()) { _, i ->
                if (i < list.size) pair(list[i]) else askIp { pair(it) }
            }.show()
    }

    private fun connectTv() {
        val list = saved()
        if (list.isEmpty()) { toast("No paired TV yet. Tap Pair TV first."); return }
        if (list.size == 1) { connectRemote(list[0]); return }
        AlertDialog.Builder(this).setTitle("Connect to")
            .setItems(list.map { it.name + "  (" + it.host + ")" }.toTypedArray()) { _, i -> connectRemote(list[i]) }
            .show()
    }

    private fun autoConnect() {
        if (ready || (connecting && !closing) || manualOff) return
        val last = prefs.getString("last", null) ?: return
        val tv = saved().firstOrNull { it.name == last || it.host == last } ?: return
        log("Auto-connecting to ${tv.name}")
        connectRemote(tv)
    }

    private fun disconnectTv() {
        manualOff = true
        closeSock()
        setStatus("Disconnected. Tap Connect to reconnect.", AMBER)
    }

    private fun devices() {
        val list = saved()
        if (list.isEmpty()) { toast("No paired TVs yet."); return }
        val items = list.map { (if (ready && current?.name == it.name) "● " else "○ ") + it.name + "  (" + it.host + ")" }
        AlertDialog.Builder(this).setTitle("Paired TVs  (● = connected)")
            .setItems(items.toTypedArray()) { _, i -> deviceMenu(list[i]) }
            .setNegativeButton("Close", null).show()
    }

    private fun deviceMenu(tv: Tv) {
        val isCur = ready && current?.name == tv.name
        val opts = arrayOf(if (isCur) "Disconnect" else "Connect", "Forget this TV")
        AlertDialog.Builder(this).setTitle(tv.name).setItems(opts) { _, i ->
            if (i == 0) { if (isCur) disconnectTv() else connectRemote(tv) } else forget(tv)
        }.show()
    }

    private fun forget(tv: Tv) {
        if (current?.name == tv.name) disconnectTv()
        saveList(saved().filter { it.name != tv.name })
        prefs.edit().remove("pin_" + tv.name).apply()
        if (prefs.getString("last", null) == tv.name) prefs.edit().remove("last").apply()
        log("Forgot ${tv.name}")
        toast("Forgotten. You can also remove this phone from the TV's remote list in the TV settings.")
    }

    private fun help() {
        AlertDialog.Builder(this).setTitle("Help")
            .setMessage(
                "1. Put the phone and the TV on the same Wi-Fi, and turn the TV on.\n" +
                "2. Tap Pair TV and choose your TV (or Enter IP address: TV Settings > Network).\n" +
                "3. A 6-character code appears on the TV. Type it here and tap OK.\n" +
                "4. After that the app connects by itself. Use Connect if it drops, Disconnect to stop it, and Devices to see or forget paired TVs.\n\n" +
                "To type: write or dictate in the keyboard box, then tap Send.\n\n" +
                "If anything fails, open the Log tab, tap Copy and send it to Claude."
            )
            .setPositiveButton("OK", null).show()
    }

    private fun setStatus(t: String, c: Int) {
        log("STATUS: $t")
        runOnUiThread {
            status.text = t
            dot.background = rect(c, 0f, true)
        }
    }

    private fun toast(t: String) = runOnUiThread { Toast.makeText(this, t, Toast.LENGTH_LONG).show() }

    // ---------- Activity ----------
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
        log("App started (Wi-Fi version)")
        log("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        setStatus("Not connected. Tap Pair TV the first time.", Color.GRAY)
        net.execute { initKeys() }
        startDiscovery()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && !manualOff && (ready || connecting)) {
            log("App left the screen: closing the TV connection")
            closeSock()
            setStatus("Paused. It reconnects when you return.", Color.GRAY)
        }
    }

    override fun onResume() {
        super.onResume()
        net.execute { autoConnect() }
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
        val size = dp(215)
        val frame = FrameLayout(this).apply {
            background = grad(Color.parseColor("#2E3480"), Color.parseColor("#171B45"), 0f, true, Color.parseColor("#6C8CFF"))
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                setMargins(0, dp(8), dp(8), dp(8))
            }
        }
        fun arrow(sym: String, g: Int, code: Int) {
            val t = TextView(this).apply {
                text = sym
                textSize = 24f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                background = flatBg()
                layoutParams = FrameLayout.LayoutParams(dp(68), dp(68), g)
            }
            press(t, true) { key(code) }
            frame.addView(t)
        }
        arrow("▲", Gravity.TOP or Gravity.CENTER_HORIZONTAL, 19)
        arrow("▼", Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 20)
        arrow("◀", Gravity.START or Gravity.CENTER_VERTICAL, 21)
        arrow("▶", Gravity.END or Gravity.CENTER_VERTICAL, 22)
        val ok = TextView(this).apply {
            text = "OK"
            textSize = 20f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = keyBg(PURPLE, 0f, true)
            layoutParams = FrameLayout.LayoutParams(dp(80), dp(80), Gravity.CENTER)
        }
        press(ok, false) { key(23) }
        frame.addView(ok)
        return frame
    }

    private fun volColumn(): View {
        fun vb(label: String, c: Int, rep: Boolean, code: Int) = pill(label, c, rep) { key(code) }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
                .apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, dp(215), 1f)
            addView(vb("Vol +", BLUE, true, 24))
            addView(vb("Mute", SLATE, false, 164))
            addView(vb("Vol -", BLUE, true, 25))
        }
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
            text = "Phone Remote  v5.1"
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
            addView(row(pill("Pair TV", PURPLE) { pairTv() }, pill("Connect", BLUE) { connectTv() }, pill("Disconnect", ROSE) { disconnectTv() }))
            addView(row(pill("Devices", TEAL) { devices() }, pill("Help") { help() }))
            addView(row(dpad(), volColumn()).apply { gravity = Gravity.CENTER_VERTICAL })
            addView(row(pill("Back", ORANGE) { key(4) }, pill("Home", TEAL) { key(3) }, pill("Power", RED) { key(26) }))
            addView(label("KEYBOARD  (tap the mic on your keyboard to speak)"))
            addView(input)
            addView(row(pill("Send", PURPLE) { sendText() }, pill("Delete", AMBER, true) { key(67) }, pill("Enter", GREEN) { key(66) }))
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
        closeSock()
        try { nsd.stopServiceDiscovery(disc) } catch (e: Exception) {}
        super.onDestroy()
    }
}
