package com.formmitra.app.agent

import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.OutputStream
import java.net.Socket
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLSocketFactory

/**
 * v29 P8 — REALTIME server↔app coordination: minimal RFC 6455 WebSocket
 * client + Phoenix protocol v1 (Supabase Realtime).
 *
 * - Pure JDK: javax.net.ssl.SSLSocket par haath se handshake
 *   (Sec-WebSocket-Key + SHA1 accept verify), frame encode (client→server
 *   masked) / decode, ping→pong jawab.
 * - Phoenix: join `[join_ref,ref,"realtime:<channel>","phx_join",{}]`,
 *   heartbeat `phx_heartbeat` har 25s, incoming `broadcast` events parse.
 * - Auto-reconnect exponential backoff (3s→60s cap). Single socket,
 *   battery-friendly. Har network path try/catch — zero-crash gate.
 * - Polling code ko chhua NAHI — ye sirf foreground fast-path hai.
 *
 * Testable pure logic alag objects me hai (MiniJson, WsFrame, PhoenixMsg,
 * RealtimeChannel, RealtimeCrypto) — selftest bina network ke chalta hai.
 */

// ---------------------------------------------------------------------------
// MiniJson — chhota recursive JSON parser (pure Kotlin, org.json nahi).
// org.json android.jar ka stub selftest JVM par throw karta hai, isliye
// socket path me iska istemal — parse bhi, aur string-build bhi.
// ---------------------------------------------------------------------------
object MiniJson {
    fun parse(text: String): Any? {
        val p = Parser(text)
        p.ws()
        val v = p.value()
        p.ws()
        return v
    }

    private class Parser(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            ws()
            if (i >= s.length) return null
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> num()
            }
        }
        private fun lit(w: String, v: Any?): Any? {
            if (!s.startsWith(w, i)) return null
            i += w.length
            return v
        }
        private fun str(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i++]
                if (c == '"') return sb.toString()
                if (c == '\\' && i < s.length) {
                    when (val e = s[i++]) {
                        '"', '\\', '/' -> sb.append(e)
                        'b' -> sb.append('\b')
                        'f' -> sb.append('')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (i + 4 <= s.length) {
                                sb.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                        }
                        else -> sb.append(e)
                    }
                } else sb.append(c)
            }
            return sb.toString()
        }
        private fun num(): Number {
            val st = i
            while (i < s.length && s[i] in "-+0123456789.eE") i++
            val t = s.substring(st, i)
            return t.toLongOrNull() ?: t.toDoubleOrNull() ?: 0.0
        }
        private fun arr(): List<Any?> {
            i++
            val l = mutableListOf<Any?>()
            ws()
            if (i < s.length && s[i] == ']') { i++; return l }
            while (true) {
                l.add(value()); ws()
                if (i < s.length && s[i] == ',') { i++; continue }
                break
            }
            ws(); if (i < s.length && s[i] == ']') i++
            return l
        }
        private fun obj(): Map<String, Any?> {
            i++
            val m = LinkedHashMap<String, Any?>()
            ws()
            if (i < s.length && s[i] == '}') { i++; return m }
            while (true) {
                ws(); val k = str(); ws()
                if (i < s.length && s[i] == ':') i++
                m[k] = value(); ws()
                if (i < s.length && s[i] == ',') { i++; continue }
                break
            }
            ws(); if (i < s.length && s[i] == '}') i++
            return m
        }
    }

    /** JSON string value escape (client→server message build ke liye). */
    fun esc(s: String): String = buildString {
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
    }
}

// ---------------------------------------------------------------------------
// WsFrame — RFC 6455 frame encode/decode (pure).
// ---------------------------------------------------------------------------
object WsFrame {
    const val OP_TEXT = 0x1
    const val OP_CLOSE = 0x8
    const val OP_PING = 0x9
    const val OP_PONG = 0xA

    data class Frame(val opcode: Int, val payload: ByteArray, val consumed: Int)

    private val rng = SecureRandom()

    /** Client→server: FIN+opcode, MASKED (RFC 6455 §5.3 — client MUST mask). */
    fun encode(opcode: Int, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0x80 or (opcode and 0x0F))
        val n = payload.size
        val maskBit = 0x80
        when {
            n < 126 -> out.write(maskBit or n)
            n < 65536 -> {
                out.write(maskBit or 126)
                out.write((n shr 8) and 0xFF); out.write(n and 0xFF)
            }
            else -> {
                out.write(maskBit or 127)
                val nn = n.toLong()
                for (s in 56 downTo 0 step 8) out.write(((nn shr s) and 0xFF).toInt())
            }
        }
        val key = ByteArray(4).also { rng.nextBytes(it) }
        out.write(key)
        for (k in payload.indices) out.write((payload[k].toInt() xor key[k % 4].toInt()) and 0xFF)
        return out.toByteArray()
    }

    fun encodeText(text: String): ByteArray =
        encode(OP_TEXT, text.toByteArray(Charsets.UTF_8))

    fun encodePong(payload: ByteArray): ByteArray = encode(OP_PONG, payload)

    fun encodeClose(): ByteArray = encode(OP_CLOSE, ByteArray(0))

    /**
     * Server→client frame decode (masked ya unmasked dono chalenge).
     * @return null = bytes adhoore (aur chahiye).
     */
    fun decode(bytes: ByteArray, offset: Int = 0): Frame? {
        var p = offset
        if (bytes.size - p < 2) return null
        val b0 = bytes[p++].toInt() and 0xFF
        val b1 = bytes[p++].toInt() and 0xFF
        val opcode = b0 and 0x0F
        val masked = (b1 and 0x80) != 0
        var len = (b1 and 0x7F).toLong()
        if (len == 126L) {
            if (bytes.size - p < 2) return null
            len = (((bytes[p].toInt() and 0xFF) shl 8) or (bytes[p + 1].toInt() and 0xFF)).toLong()
            p += 2
        } else if (len == 127L) {
            if (bytes.size - p < 8) return null
            var l = 0L
            for (k in 0 until 8) l = (l shl 8) or (bytes[p + k].toInt() and 0xFF).toLong()
            len = l
            p += 8
        }
        var key: ByteArray? = null
        if (masked) {
            if (bytes.size - p < 4) return null
            key = bytes.copyOfRange(p, p + 4)
            p += 4
        }
        if (len > Int.MAX_VALUE || bytes.size - p < len) return null
        val n = len.toInt()
        val payload = bytes.copyOfRange(p, p + n)
        if (key != null) {
            for (k in payload.indices) {
                payload[k] = (payload[k].toInt() xor key[k % 4].toInt()).toByte()
            }
        }
        return Frame(opcode, payload, p + n - offset)
    }
}

// ---------------------------------------------------------------------------
// PhoenixMsg — Phoenix v1 protocol messages (pure).
// Wire: JSON array [join_ref, ref, topic, event, payload].
// ---------------------------------------------------------------------------
object PhoenixMsg {
    data class Incoming(val topic: String, val event: String, val payload: Map<String, Any?>)

    fun join(joinRef: String, channel: String): String =
        "[\"${MiniJson.esc(joinRef)}\",\"${MiniJson.esc(joinRef)}\"," +
            "\"realtime:${MiniJson.esc(channel)}\",\"phx_join\",{}]"

    fun heartbeat(ref: String): String =
        "[null,\"${MiniJson.esc(ref)}\",\"phoenix\",\"phx_heartbeat\",{}]"

    @Suppress("UNCHECKED_CAST")
    fun parse(text: String): Incoming? {
        val v = try { MiniJson.parse(text) } catch (_: Exception) { return null }
        val arr = v as? List<Any?> ?: return null
        if (arr.size < 5) return null
        val topic = arr[2] as? String ?: return null
        val event = arr[3] as? String ?: return null
        val payload = (arr[4] as? Map<String, Any?>) ?: emptyMap()
        return Incoming(topic, event, payload)
    }

    /**
     * Wire shape se (eventName, payload) nikalo. Dono shapes support:
     *  A) event="broadcast", payload={"event":X,"payload":P} (client broadcast)
     *  B) event=X seedha (HTTP broadcast API path), payload=P
     */
    @Suppress("UNCHECKED_CAST")
    fun extractEvent(msg: Incoming): Pair<String, Map<String, Any?>>? {
        return when (msg.event) {
            "broadcast" -> {
                val ev = msg.payload["event"] as? String ?: return null
                val p = msg.payload["payload"] as? Map<String, Any?> ?: emptyMap()
                ev to p
            }
            "notification", "task_status", "operator_command" -> msg.event to msg.payload
            else -> null
        }
    }
}

// ---------------------------------------------------------------------------
// RealtimeChannel — channel naam format (pure). Naam server deta hai
// (/api/app/realtime-config); app sirf validate karta hai.
// ---------------------------------------------------------------------------
object RealtimeChannel {
    private val NAME_RE = Regex("^fm-user-[A-Za-z0-9_-]{1,120}-[0-9a-f]{16}$")

    fun build(userId: String, tag: String): String = "fm-user-$userId-$tag"

    fun isValid(name: String): Boolean = NAME_RE.matches(name)

    fun tagOf(name: String): String? =
        if (isValid(name)) name.substringAfterLast("-") else null
}

// ---------------------------------------------------------------------------
// RealtimeCrypto — HMAC-SHA256 (pure JDK). App ko channel server deta hai,
// isliye production me zaroorat nahi — selftest me format verify ke liye.
// ---------------------------------------------------------------------------
object RealtimeCrypto {
    fun hmacSha256Hex(key: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}

/**
 * RealtimeSocket — minimal RFC 6455 client (SSLSocket, haath se handshake).
 *
 * Sirf foreground fast-path: connect → join → heartbeat → broadcast events
 * listener ko. Koi bhi failure → exponential backoff reconnect (max ~60s).
 * Har I/O try/catch — koi uncaught exception bahar nahi (zero-crash gate).
 */
class RealtimeSocket(
    private val wsUrl: String,
    private val channel: String,
    private val listener: Listener
) {
    interface Listener {
        fun onEvent(event: String, payload: Map<String, Any?>)
        fun onConnected()
        fun onDisconnected()
    }

    private val tag = "RealtimeSocket"
    private val guid = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

    @Volatile private var stop = false
    @Volatile private var socket: Socket? = null
    @Volatile private var out: OutputStream? = null
    @Volatile private var fastReconnect = false
    private val writeLock = Any()
    private val refCounter = AtomicInteger(1)
    private var worker: Thread? = null

    @Synchronized
    fun start() {
        if (worker?.isAlive == true) return
        stop = false
        worker = Thread({ runLoop() }, "RealtimeSocket").apply {
            isDaemon = true
            start()
        }
    }

    fun close() {
        stop = true
        try { socket?.close() } catch (_: Exception) { }
        socket = null
        out = null
        try { worker?.join(2000) } catch (_: Exception) { }
        worker = null
    }

    /** Network wapas aaya → turant reconnect (backoff skip). */
    fun reconnectNow() {
        if (stop) return
        fastReconnect = true
        try { socket?.close() } catch (_: Exception) { }
    }

    // ---------------- main loop ----------------

    private fun runLoop() {
        var backoffMs = 3000L
        while (!stop) {
            try {
                if (fastReconnect) {
                    fastReconnect = false
                } else if (backoffMs > 3000L) {
                    Thread.sleep(backoffMs)
                }
                if (stop) break
                connectOnce()
                // connectOnce sirf disconnect par wapas aata hai → backoff reset
                backoffMs = 3000L
            } catch (t: Throwable) {
                if (stop) break
                Log.w(tag, "realtime down, retry in ${backoffMs}ms (${t.message})")
                try { Thread.sleep(backoffMs) } catch (_: InterruptedException) { break }
                backoffMs = minOf(backoffMs * 2, 60_000L)
            }
        }
        try { listener.onDisconnected() } catch (_: Exception) { }
    }

    private fun connectOnce() {
        val uri = URI(wsUrl)
        val secure = uri.scheme.equals("wss", ignoreCase = true)
        val host = uri.host ?: throw IllegalArgumentException("bad ws url")
        val port = if (uri.port > 0) uri.port else if (secure) 443 else 80
        val path = buildString {
            append(if (uri.rawPath.isNullOrEmpty()) "/" else uri.rawPath)
            if (!uri.rawQuery.isNullOrEmpty()) append("?").append(uri.rawQuery)
        }

        val sock: Socket = if (secure) {
            (SSLSocketFactory.getDefault().createSocket(host, port) as javax.net.ssl.SSLSocket)
                .also { try { it.startHandshake() } catch (e: Exception) { try { it.close() } catch (_: Exception) { }; throw e } }
        } else {
            Socket(host, port)
        }
        sock.soTimeout = 50_000
        socket = sock
        try {
            doHandshake(sock, host, port, path)
            out = sock.getOutputStream()
            sendJoin()
            try { listener.onConnected() } catch (_: Exception) { }
            val hb = startHeartbeat()
            try {
                readLoop(sock)
            } finally {
                try { hb.interrupt() } catch (_: Exception) { }
            }
        } finally {
            out = null
            socket = null
            try { sock.close() } catch (_: Exception) { }
            try { listener.onDisconnected() } catch (_: Exception) { }
        }
    }

    private fun doHandshake(sock: Socket, host: String, port: Int, path: String) {
        val keyBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val key = Base64.encodeToString(keyBytes, Base64.NO_WRAP)
        val req = buildString {
            append("GET ").append(path).append(" HTTP/1.1\r\n")
            append("Host: ").append(host)
            if (port != 443 && port != 80) append(":").append(port)
            append("\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Key: ").append(key).append("\r\n")
            append("Sec-WebSocket-Version: 13\r\n\r\n")
        }
        val os = sock.getOutputStream()
        os.write(req.toByteArray(Charsets.UTF_8))
        os.flush()

        // Response headers padho (status line + headers, blank line tak)
        val inp = sock.getInputStream()
        val header = readHttpHeader(inp)
        val statusLine = header.lineSequence().firstOrNull() ?: ""
        if (!statusLine.contains("101")) {
            throw IllegalStateException("WS handshake fail: $statusLine")
        }
        val accept = header.lineSequence()
            .firstOrNull { it.startsWith("Sec-WebSocket-Accept:", ignoreCase = true) }
            ?.substringAfter(":")?.trim() ?: ""
        val expected = Base64.encodeToString(
            MessageDigest.getInstance("SHA-1")
                .digest((key + guid).toByteArray(Charsets.UTF_8)),
            Base64.NO_WRAP
        )
        if (accept != expected) throw IllegalStateException("WS accept mismatch")
        Log.i(tag, "handshake ok")
    }

    private fun readHttpHeader(inp: java.io.InputStream): String {
        val sb = StringBuilder()
        val one = ByteArray(1)
        var lastFour = 0
        // \r\n\r\n tak padho (header chhota hota hai; 8KB cap)
        while (sb.length < 8192) {
            val r = try { inp.read(one) } catch (_: Exception) { -1 }
            if (r <= 0) break
            val c = one[0].toInt() and 0xFF
            sb.append(c.toChar())
            lastFour = ((lastFour shl 8) or c) and 0xFFFFFFFF.toInt()
            if (lastFour == 0x0D0A0D0A) break
        }
        return sb.toString()
    }

    private fun sendJoin() {
        val joinRef = refCounter.getAndIncrement().toString()
        sendText(PhoenixMsg.join(joinRef, channel))
        Log.i(tag, "join sent (channel=$channel)")
    }

    private fun startHeartbeat(): Thread {
        return Thread({
            try {
                while (!stop && socket?.isClosed == false) {
                    Thread.sleep(25_000)
                    if (stop) break
                    try {
                        sendText(PhoenixMsg.heartbeat(refCounter.getAndIncrement().toString()))
                    } catch (_: Exception) {
                        try { socket?.close() } catch (_: Exception) { }
                        break
                    }
                }
            } catch (_: InterruptedException) { }
        }, "RealtimeSocket-hb").apply { isDaemon = true; start() }
    }

    private fun sendText(text: String) {
        val o = out ?: throw IllegalStateException("not connected")
        val frame = WsFrame.encodeText(text)
        synchronized(writeLock) {
            o.write(frame)
            o.flush()
        }
    }

    private fun readLoop(sock: Socket) {
        val din = DataInputStream(sock.getInputStream())
        while (!stop && !sock.isClosed) {
            val frame = try {
                readFrame(din)
            } catch (e: java.net.SocketTimeoutException) {
                Log.w(tag, "read timeout — stale, reconnect")
                break
            } catch (_: EOFException) {
                Log.i(tag, "server ne connection band kiya")
                break
            } catch (e: Exception) {
                if (!stop) Log.w(tag, "read error: ${e.message}")
                break
            } ?: break
            when (frame.opcode) {
                WsFrame.OP_TEXT -> handleText(String(frame.payload, Charsets.UTF_8))
                WsFrame.OP_PING -> {
                    // ping → pong (same payload), RFC 6455 §5.5.2
                    try {
                        val o = out
                        if (o != null) {
                            synchronized(writeLock) {
                                o.write(WsFrame.encodePong(frame.payload))
                                o.flush()
                            }
                        }
                    } catch (_: Exception) { }
                }
                WsFrame.OP_CLOSE -> {
                    Log.i(tag, "server close frame")
                    break
                }
                else -> { /* binary/continuation — ignore */ }
            }
        }
    }

    private fun readFrame(din: DataInputStream): WsFrame.Frame {
        val b0 = din.read()
        if (b0 < 0) throw EOFException()
        val b1 = din.read()
        if (b1 < 0) throw EOFException()
        val opcode = b0 and 0x0F
        val masked = (b1 and 0x80) != 0
        var len = (b1 and 0x7F).toLong()
        if (len == 126L) {
            len = din.readUnsignedShort().toLong()
        } else if (len == 127L) {
            len = din.readLong()
            if (len < 0) throw IllegalStateException("frame too large")
        }
        val key = if (masked) ByteArray(4).also { din.readFully(it) } else null
        if (len > 4 * 1024 * 1024) throw IllegalStateException("frame too large")
        val payload = ByteArray(len.toInt())
        if (payload.isNotEmpty()) din.readFully(payload)
        if (key != null) {
            for (k in payload.indices) {
                payload[k] = (payload[k].toInt() xor key[k % 4].toInt()).toByte()
            }
        }
        return WsFrame.Frame(opcode, payload, 0)
    }

    private fun handleText(text: String) {
        val msg = try { PhoenixMsg.parse(text) } catch (_: Exception) { null } ?: return
        // Sirf apne channel ke messages (phoenix heartbeat reply ignore)
        if (msg.topic != "realtime:$channel") return
        val (event, payload) = try {
            PhoenixMsg.extractEvent(msg) ?: return
        } catch (_: Exception) { return }
        if (event != "notification" && event != "task_status" && event != "operator_command") return
        try {
            listener.onEvent(event, payload)
        } catch (t: Throwable) {
            Log.e(tag, "onEvent failed (non-fatal)", t)
        }
    }

    companion object {
        /** wss URL banao — query me apikey + vsn jodo. */
        fun buildUrl(base: String, apiKey: String): String {
            val sep = if (base.contains("?")) "&" else "?"
            return base + sep + "apikey=" + URLEncoder.encode(apiKey, "UTF-8") + "&vsn=1.0.0"
        }
    }
}
