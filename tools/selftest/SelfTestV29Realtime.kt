import com.formmitra.app.agent.MiniJson
import com.formmitra.app.agent.PhoenixMsg
import com.formmitra.app.agent.RealtimeChannel
import com.formmitra.app.agent.RealtimeCrypto
import com.formmitra.app.agent.RealtimeSocket
import com.formmitra.app.agent.WsFrame

// Self-test: v29 P8 realtime — pure logic, NO network.
//  - WsFrame: RFC 6455 frame encode/decode round-trip (short/extended/64-bit,
//    masked/unmasked, ping/pong/close opcodes, incomplete → null)
//  - PhoenixMsg: join/heartbeat message format + incoming parse (dono wire
//    shapes: broadcast-wrapper aur direct event)
//  - RealtimeChannel: naam format build/validate
//  - RealtimeCrypto: HMAC-SHA256 RFC 4231 test vector
//  - RealtimeSocket.buildUrl: apikey+vsn query
//  - MiniJson: parser sanity
//
// Compile: kotlinc -cp <android.jar> <src>/agent/RealtimeSocket.kt
//   tools/selftest/SelfTestV29Realtime.kt -d out
//   && java -cp out:<stdlib> SelfTestV29RealtimeKt
// NOTE: sirf pure objects chhute hain (org.json/android stubs runtime par
// kabhi load nahi hote) — selftest me koi android/org.json call NAHI.

var failures = 0

fun check(name: String, cond: Boolean) {
    if (cond) println("PASS: $name") else { println("FAIL: $name"); failures++ }
}

fun main() {
    // ============ WsFrame: round-trip ============
    run {
        val text = "hello realtime"
        val enc = WsFrame.encodeText(text)
        // client frames masked hote hain (RFC 6455 §5.3)
        check("encode masked bit set", (enc[1].toInt() and 0x80) != 0)
        val dec = WsFrame.decode(enc)
        check("decode non-null", dec != null)
        check("round-trip opcode text", dec?.opcode == WsFrame.OP_TEXT)
        check(
            "round-trip payload equal",
            dec?.payload?.toString(Charsets.UTF_8) == text
        )
        check("round-trip consumed all", dec?.consumed == enc.size)
    }

    // 16-bit extended length (126)
    run {
        val text = "x".repeat(200)
        val enc = WsFrame.encodeText(text)
        check("ext16 marker", (enc[1].toInt() and 0x7F) == 126)
        val dec = WsFrame.decode(enc)
        check("ext16 round-trip", dec?.payload?.toString(Charsets.UTF_8) == text)
    }

    // 64-bit extended length (127)
    run {
        val text = "y".repeat(70000)
        val enc = WsFrame.encodeText(text)
        check("ext64 marker", (enc[1].toInt() and 0x7F) == 127)
        val dec = WsFrame.decode(enc)
        check("ext64 round-trip", dec?.payload?.size == 70000)
    }

    // Server ping (unmasked) → pong encode
    run {
        // FIN+ping, len 4, unmasked, payload "ping"
        val pingFrame = byteArrayOf(0x89.toByte(), 0x04.toByte()) + "ping".toByteArray()
        val dec = WsFrame.decode(pingFrame)
        check("ping opcode", dec?.opcode == WsFrame.OP_PING)
        check("ping payload", dec?.payload?.toString(Charsets.UTF_8) == "ping")
        val pong = WsFrame.encodePong("ping".toByteArray())
        val decPong = WsFrame.decode(pong)
        check("pong opcode", decPong?.opcode == WsFrame.OP_PONG)
        check("pong masked", (pong[1].toInt() and 0x80) != 0)
    }

    // Close frame
    run {
        val close = byteArrayOf(0x88.toByte(), 0x00.toByte())
        val dec = WsFrame.decode(close)
        check("close opcode", dec?.opcode == WsFrame.OP_CLOSE)
        check("close empty payload", dec?.payload?.isEmpty() == true)
    }

    // Incomplete → null
    run {
        check("incomplete 1 byte → null", WsFrame.decode(byteArrayOf(0x81.toByte())) == null)
        val partial = WsFrame.encodeText("abc").copyOf(3)
        check("incomplete header → null", WsFrame.decode(partial) == null)
    }

    // Concatenated frames: consumed offset se doosra frame
    run {
        val a = WsFrame.encodeText("one")
        val b = WsFrame.encodeText("two")
        val both = a + b
        val d1 = WsFrame.decode(both, 0)!!
        val d2 = WsFrame.decode(both, d1.consumed)!!
        check(
            "concatenated frames",
            d1.payload.toString(Charsets.UTF_8) == "one" &&
                d2.payload.toString(Charsets.UTF_8) == "two" &&
                d1.consumed + d2.consumed == both.size
        )
    }

    // ============ PhoenixMsg: format ============
    run {
        val join = PhoenixMsg.join("1", "fm-user-abc-0123456789abcdef")
        check(
            "join format",
            join == "[\"1\",\"1\",\"realtime:fm-user-abc-0123456789abcdef\",\"phx_join\",{}]"
        )
        val hb = PhoenixMsg.heartbeat("7")
        check("heartbeat format", hb == "[null,\"7\",\"phoenix\",\"phx_heartbeat\",{}]")
    }

    // Incoming parse — phx_reply
    run {
        val msg = PhoenixMsg.parse(
            "[\"1\",\"1\",\"realtime:fm-user-u-0123456789abcdef\",\"phx_reply\"," +
                "{\"status\":\"ok\",\"response\":{}}]"
        )
        check("parse phx_reply topic", msg?.topic == "realtime:fm-user-u-0123456789abcdef")
        check("parse phx_reply event", msg?.event == "phx_reply")
        check(
            "parse phx_reply status",
            (msg?.payload?.get("status") as? String) == "ok"
        )
        check("extractEvent phx_reply → null", PhoenixMsg.extractEvent(msg!!) == null)
    }

    // Wire shape A: event="broadcast", payload={event,payload}
    run {
        val msg = PhoenixMsg.parse(
            "[null,\"3\",\"realtime:fm-user-u-0123456789abcdef\",\"broadcast\"," +
                "{\"event\":\"notification\",\"payload\":{\"id\":\"n1\",\"title\":\"T\",\"body\":\"B\",\"ts\":123}}]"
        )
        val ev = PhoenixMsg.extractEvent(msg!!)
        check("shapeA event name", ev?.first == "notification")
        @Suppress("UNCHECKED_CAST")
        val p = ev?.second
        check("shapeA payload id", p?.get("id") == "n1")
        check("shapeA payload title", p?.get("title") == "T")
        check("shapeA payload ts number", (p?.get("ts") as? Number)?.toLong() == 123L)
    }

    // Wire shape B: direct event (HTTP broadcast API path)
    run {
        val msg = PhoenixMsg.parse(
            "[null,\"4\",\"realtime:fm-user-u-0123456789abcdef\",\"task_status\"," +
                "{\"taskId\":\"t1\",\"status\":\"done\",\"ts\":5}]"
        )
        val ev = PhoenixMsg.extractEvent(msg!!)
        check("shapeB event name", ev?.first == "task_status")
        check("shapeB payload taskId", ev?.second?.get("taskId") == "t1")
        check("shapeB payload status", ev?.second?.get("status") == "done")
    }

    // Unknown event → null (ignore)
    run {
        val msg = PhoenixMsg.parse("[null,\"5\",\"realtime:c\",\"presence_diff\",{}]")
        check("unknown event → null", PhoenixMsg.extractEvent(msg!!) == null)
    }

    // Malformed → null, no throw
    run {
        check("malformed → null", PhoenixMsg.parse("not json") == null)
        check("short array → null", PhoenixMsg.parse("[1,2]") == null)
    }

    // ============ MiniJson sanity ============
    run {
        @Suppress("UNCHECKED_CAST")
        val m = MiniJson.parse(
            "{\"a\":1,\"b\":\"x \\\"y\\\"\",\"c\":true,\"d\":null,\"e\":[1,2],\"f\":{\"g\":1.5}}"
        ) as? Map<String, Any?>
        check("json obj", m?.get("a") == 1L)
        check("json escaped string", m?.get("b") == "x \"y\"")
        check("json bool", m?.get("c") == true)
        check("json null", m?.containsKey("d") == true && m?.get("d") == null)
        @Suppress("UNCHECKED_CAST")
        val e = m?.get("e") as? List<Any?>
        check("json array", e?.size == 2 && e?.get(1) == 2L)
        @Suppress("UNCHECKED_CAST")
        val f = m?.get("f") as? Map<String, Any?>
        check("json nested", (f?.get("g") as? Number)?.toDouble() == 1.5)
        check("json esc", MiniJson.esc("a\"b\\c") == "a\\\"b\\\\c")
    }

    // ============ RealtimeCrypto: RFC 4231 vector ============
    run {
        // Test Case 1: key = 20×0x0b, data = "Hi There"
        val key = String(ByteArray(20) { 0x0b }, Charsets.ISO_8859_1)
        val hex = RealtimeCrypto.hmacSha256Hex(key, "Hi There")
        check(
            "hmac-sha256 rfc4231",
            hex == "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7"
        )
        check("hmac 64 hex chars", hex.length == 64 && hex.all { it in '0'..'9' || it in 'a'..'f' })
    }

    // ============ RealtimeChannel ============
    run {
        val tag = RealtimeCrypto.hmacSha256Hex("secret", "user123").take(16)
        val name = RealtimeChannel.build("user123", tag)
        check("channel build", name == "fm-user-user123-$tag")
        check("channel valid", RealtimeChannel.isValid(name))
        check("channel tag extract", RealtimeChannel.tagOf(name) == tag)
        check("channel reject bad tag", !RealtimeChannel.isValid("fm-user-x-zzz"))
        check("channel reject plain", !RealtimeChannel.isValid("evil"))
        check("channel reject empty", !RealtimeChannel.isValid(""))
    }

    // ============ RealtimeSocket.buildUrl ============
    run {
        val u = RealtimeSocket.buildUrl(
            "wss://xyz.supabase.co/realtime/v1/websocket", "anon-key"
        )
        check(
            "buildUrl",
            u == "wss://xyz.supabase.co/realtime/v1/websocket?apikey=anon-key&vsn=1.0.0"
        )
    }

    println("----")
    if (failures == 0) println("ALL PASS") else println("$failures FAILURES")
}
