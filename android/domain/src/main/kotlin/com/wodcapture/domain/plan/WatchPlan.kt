package com.wodcapture.domain.plan

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double

/**
 * Serialises whole doubles as integers (`43`, not `43.0`) so wire sizes and golden fixtures match
 * the protocol examples and the watch receives an Integer where one was meant.
 */
object WireNumberSerializer : KSerializer<Double> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("WireNumber", PrimitiveKind.DOUBLE)

    override fun serialize(encoder: Encoder, value: Double) {
        val json = encoder as? JsonEncoder ?: return encoder.encodeDouble(value)
        val prim = if (value == Math.rint(value) && kotlin.math.abs(value) < 1e15) JsonPrimitive(value.toLong()) else JsonPrimitive(value)
        json.encodeJsonElement(prim)
    }

    override fun deserialize(decoder: Decoder): Double {
        val json = decoder as? JsonDecoder ?: return decoder.decodeDouble()
        return (json.decodeJsonElement() as JsonPrimitive).double
    }
}

/** WatchPlan — payload of `plan.put` (docs/protocol.md §4). Absent values are omitted keys. */
@Serializable
data class WatchPlan(
    val sv: Int = 1,
    val id: String,
    val rev: Int,
    val wid: String,
    val wr: Int,
    val ti: String,
    val date: String,
    val tz: String,
    val u: String,
    val p: List<WatchPart>,
)

/** Part of a WatchPlan (protocol §4.1). Field order mirrors the protocol table. */
@Serializable
data class WatchPart(
    val id: String,
    val l: String,
    val n: String,
    val mode: String,
    val fb: Boolean,
    val ln: List<String>,
    val seg: List<WatchSegment>? = null,
    val trk: String? = null,
    val mv: List<String>? = null,
    val rr: List<Int>? = null,
    /** True when the last `rr` entry applies to every later round (constant-round AMRAP). */
    val rrc: Boolean? = null,
    val rl: List<String>? = null,
    val rnd: Int? = null,
    val sets: List<WatchSet>? = null,
    val att: Int? = null,
    val sc: WatchScore,
    val ld: List<WatchLoadItem>? = null,
    val ra: Int? = null,
    val lv: String? = null,
    val lvs: List<String>? = null,
)

/** Timer segment (protocol §4.2). */
@Serializable
data class WatchSegment(
    val k: String,
    val d: Int? = null,
    val cap: Int? = null,
    val lb: String,
    val ix: Int,
    val s: Int? = null,
    val ic: Boolean? = null,
)

/** Strength set (protocol §4.3). */
@Serializable
data class WatchSet(
    val id: String,
    val nm: String,
    val mv: String? = null,
    val r: Int? = null,
    val rx: String,
    val w: WatchLoad? = null,
    val bw: Boolean? = null,
    val rest: Int? = null,
    val ty: String? = null,
    val nt: String? = null,
)

/** Load `{v,u,c}`; `v` omitted = manual entry in unit `u`. */
@Serializable
data class WatchLoad(
    @Serializable(with = WireNumberSerializer::class) val v: Double? = null,
    val u: String,
    val c: Int,
)

/** Score rule (protocol §4.4). */
@Serializable
data class WatchScore(
    val ty: String,
    val rv: Int = 1,
    val cap: String? = null,
    val du: String? = null,
    val man: Boolean? = null,
    val agg: String? = null,
)

/** Loaded metcon item whose actual load is entered on the result screen (REQ-WE06). */
@Serializable
data class WatchLoadItem(
    val id: String,
    val nm: String,
    @Serializable(with = WireNumberSerializer::class) val v: Double? = null,
    val u: String,
    val c: Int,
)

/** Wire JSON: compact, nulls omitted, defaults encoded (fields like `sv`/`rv` are always sent). */
val WireJson: Json = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = false
}
