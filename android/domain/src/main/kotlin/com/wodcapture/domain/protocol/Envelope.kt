package com.wodcapture.domain.protocol

import com.wodcapture.domain.isWireUuid
import com.wodcapture.domain.plan.WatchPlan
import com.wodcapture.domain.plan.WireJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Protocol constants (docs/protocol.md §0–§3). */
object Protocol {
    const val VERSION = 1
    const val MAX_MESSAGE_BYTES = 4096
    const val PHONE_BUDGET_BYTES = 3584
    const val APP_UUID_PRODUCTION = "0508f6cf6f0040e59d7ed9f432688c46"
    const val APP_UUID_BETA = "a930ecf2337f4023a7b446ce221a73cf"
}

/** Message type keys (protocol §3). */
object MessageType {
    const val PLAN_PUT = "plan.put"
    const val PLAN_DEL = "plan.del"
    const val PROFILE_PUT = "profile.put"
    const val RESULT_ACK = "result.ack"
    const val RESULT_CHUNK_ACK = "result.chunk.ack"
    const val SESSION_ACK = "session.ack"
    const val SYNC_REQ = "sync.req"
    const val HELLO = "hello"
    const val PLAN_ACK = "plan.ack"
    const val PROFILE_ACK = "profile.ack"
    const val RESULT_PUT = "result.put"
    const val RESULT_CHUNK = "result.chunk"
    const val SESSION_PUT = "session.put"
    const val SYNC_ACK = "sync.ack"

    val ALL = setOf(
        PLAN_PUT, PLAN_DEL, PROFILE_PUT, RESULT_ACK, RESULT_CHUNK_ACK, SESSION_ACK, SYNC_REQ,
        HELLO, PLAN_ACK, PROFILE_ACK, RESULT_PUT, RESULT_CHUNK, SESSION_PUT, SYNC_ACK,
    )
    val ACKS = setOf(PLAN_ACK, PROFILE_ACK, RESULT_ACK, RESULT_CHUNK_ACK, SESSION_ACK)

    /** Types that concern an object and therefore carry `oid` + `rev`. */
    val WITH_OBJECT = ALL - setOf(HELLO, SYNC_REQ, SYNC_ACK)

    /** Types whose payload is mandatory. */
    val WITH_PAYLOAD = ACKS + setOf(PLAN_PUT, PROFILE_PUT, HELLO, RESULT_PUT, RESULT_CHUNK, SESSION_PUT)
}

/** Protocol envelope (protocol §2). [p] omitted when empty. */
data class Envelope(
    val v: Int = Protocol.VERSION,
    val t: String,
    val id: String,
    val ts: Long,
    val g: Int,
    val oid: String? = null,
    val rev: Int? = null,
    val p: JsonObject? = null,
)

/** Result of decoding untrusted input: either a value or the ack reason (`version`, `invalid`, `size`). */
sealed interface Decoded<out T> {
    data class Ok<T>(val value: T) : Decoded<T>
    data class Err(val reason: String, val detail: String) : Decoded<Nothing>
}

/** Envelope encode/decode to JSON and to the SDK `Map<String, Any>` graph (protocol §1–§2). */
object EnvelopeCodec {

    fun toJson(e: Envelope): JsonObject = buildJsonObject {
        put("v", e.v)
        put("t", e.t)
        put("id", e.id)
        put("ts", e.ts)
        put("g", e.g)
        e.oid?.let { put("oid", it) }
        e.rev?.let { put("rev", it) }
        e.p?.let { put("p", it) }
    }

    /** What `ConnectIQ.sendMessage` receives. */
    @Suppress("UNCHECKED_CAST")
    fun encode(e: Envelope): Map<String, Any> = WireValues.toWire(toJson(e)) as Map<String, Any>

    fun decode(map: Map<*, *>): Decoded<Envelope> = try {
        fromJson(WireValues.fromWire(map))
    } catch (ex: WireException) {
        Decoded.Err(ex.reason, ex.message ?: "invalid")
    }

    /** Validates and decodes an envelope; payload contents are validated by the payload codecs. */
    fun fromJson(json: JsonElement): Decoded<Envelope> = try {
        WireValues.requireNoNulls(json)
        val o = json as? JsonObject ?: throw WireException("invalid", "Envelope is not an object")
        if (WireValues.compactSize(o) > Protocol.MAX_MESSAGE_BYTES) throw WireException("size", "Message exceeds ${Protocol.MAX_MESSAGE_BYTES} bytes")
        val v = o.int("v") ?: throw WireException("invalid", "Missing v")
        if (v != Protocol.VERSION) throw WireException("version", "Unsupported protocol version $v")
        val t = o.str("t") ?: throw WireException("invalid", "Missing t")
        if (t !in MessageType.ALL) throw WireException("invalid", "Unknown type $t")
        val id = o.str("id")?.takeIf(::isWireUuid) ?: throw WireException("invalid", "Bad message id")
        val ts = o.long("ts")?.takeIf { it >= 0 } ?: throw WireException("invalid", "Missing ts")
        val g = o.int("g")?.takeIf { it >= 1 } ?: throw WireException("invalid", "Missing generation g")
        val oid = o.str("oid")
        val rev = o.int("rev")
        if (t in MessageType.WITH_OBJECT) {
            if (oid == null || !isWireUuid(oid)) throw WireException("invalid", "$t needs a valid oid")
            if (rev == null || rev < 1) throw WireException("invalid", "$t needs rev >= 1")
        }
        val p = o["p"]?.let { it as? JsonObject ?: throw WireException("invalid", "p is not an object") }
        if (t in MessageType.WITH_PAYLOAD && p == null) throw WireException("invalid", "$t needs a payload")
        val extra = o.keys - setOf("v", "t", "id", "ts", "g", "oid", "rev", "p")
        if (extra.isNotEmpty()) throw WireException("invalid", "Unknown envelope keys $extra")
        Decoded.Ok(Envelope(v, t, id, ts, g, oid, rev, p))
    } catch (ex: WireException) {
        Decoded.Err(ex.reason, ex.message ?: "invalid")
    }

    fun compactJson(e: Envelope): String = toJson(e).toString()

    fun sizeBytes(e: Envelope): Int = WireValues.compactSize(toJson(e))

    /** Encoded size of `plan.put` for [plan] with worst-case envelope fields (REQ-SY06). */
    fun planPutSize(plan: WatchPlan, generation: Int = 1): Int = sizeBytes(
        Envelope(
            t = MessageType.PLAN_PUT,
            id = "00000000-0000-4000-8000-000000000000",
            ts = 9_999_999_999L,
            g = generation,
            oid = plan.id,
            rev = plan.rev,
            p = WireJson.encodeToJsonElement(WatchPlan.serializer(), plan).jsonObject,
        ),
    )
}

// Small typed accessors over JsonObject used by the payload codecs.
internal fun JsonObject.prim(k: String): JsonPrimitive? = this[k] as? JsonPrimitive
internal fun JsonObject.str(k: String): String? = prim(k)?.takeIf { it.isString }?.content
internal fun JsonObject.long(k: String): Long? = prim(k)?.takeIf { !it.isString }?.content?.toLongOrNull()
internal fun JsonObject.int(k: String): Int? = long(k)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
internal fun JsonObject.num(k: String): Double? = prim(k)?.takeIf { !it.isString }?.content?.toDoubleOrNull()
internal fun JsonObject.bool(k: String): Boolean? = prim(k)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull()
