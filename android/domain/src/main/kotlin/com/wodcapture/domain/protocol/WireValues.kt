package com.wodcapture.domain.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** A message violated the wire rules (protocol §1); [reason] is the ack `rs` code. */
class WireException(val reason: String, message: String) : Exception(message)

/**
 * Conversion between JSON trees and the `Map`/`List`/primitive graph the Connect IQ Android SDK
 * sends (protocol §1). Nulls are rejected in both directions: absent = omitted key.
 */
object WireValues {

    /** JSON → SDK value graph. Integers become `Int` when they fit (Monkey C Number), else `Long`. */
    fun toWire(e: JsonElement): Any = when (e) {
        JsonNull -> throw WireException("invalid", "null is not allowed on the wire")
        is JsonObject -> LinkedHashMap<String, Any>().apply { e.forEach { (k, v) -> put(k, toWire(v)) } }
        is JsonArray -> e.map { toWire(it) }
        is JsonPrimitive -> primitive(e)
    }

    private fun primitive(p: JsonPrimitive): Any {
        if (p.isString) return p.content
        p.booleanOrNull?.let { return it }
        val c = p.content
        if (!c.contains('.') && !c.contains('e') && !c.contains('E')) {
            val l = p.longOrNull ?: throw WireException("invalid", "Bad number $c")
            return if (l in Int.MIN_VALUE..Int.MAX_VALUE) l.toInt() else l
        }
        return p.doubleOrNull ?: throw WireException("invalid", "Bad number $c")
    }

    /** SDK value graph → JSON. Whole floating values are normalised to integers. */
    fun fromWire(v: Any?): JsonElement = when (v) {
        null -> throw WireException("invalid", "null is not allowed on the wire")
        is String -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        is Int, is Long, is Short, is Byte -> JsonPrimitive((v as Number).toLong())
        is Float, is Double -> {
            val d = (v as Number).toDouble()
            if (d.isNaN() || d.isInfinite()) throw WireException("invalid", "Non-finite number")
            if (d == Math.rint(d) && kotlin.math.abs(d) < 1e15) JsonPrimitive(d.toLong()) else JsonPrimitive(d)
        }
        is Char -> JsonPrimitive(v.toString())
        is Map<*, *> -> JsonObject(
            v.entries.associate { (k, value) ->
                (k as? String ?: throw WireException("invalid", "Non-string key $k")) to fromWire(value)
            },
        )
        is List<*> -> JsonArray(v.map { fromWire(it) })
        is Array<*> -> JsonArray(v.map { fromWire(it) })
        else -> throw WireException("invalid", "Unsupported wire type ${v::class.java.simpleName}")
    }

    /** Throws if any JSON `null` occurs anywhere in [e] (protocol §1 no-nulls rule). */
    fun requireNoNulls(e: JsonElement, path: String = "$") {
        when (e) {
            JsonNull -> throw WireException("invalid", "null at $path")
            is JsonObject -> e.forEach { (k, v) -> requireNoNulls(v, "$path.$k") }
            is JsonArray -> e.forEachIndexed { i, v -> requireNoNulls(v, "$path[$i]") }
            is JsonPrimitive -> Unit
        }
    }

    /** Canonical JSON: compact, keys sorted at every level (protocol §3.8 chunk hash input). */
    fun canonical(e: JsonElement): String = buildString { writeCanonical(e, this) }

    private fun writeCanonical(e: JsonElement, sb: StringBuilder) {
        when (e) {
            is JsonObject -> {
                sb.append('{')
                e.keys.sorted().forEachIndexed { i, k ->
                    if (i > 0) sb.append(',')
                    sb.append(JsonPrimitive(k).toString()).append(':')
                    writeCanonical(e.getValue(k), sb)
                }
                sb.append('}')
            }
            is JsonArray -> {
                sb.append('[')
                e.forEachIndexed { i, v -> if (i > 0) sb.append(','); writeCanonical(v, sb) }
                sb.append(']')
            }
            else -> sb.append(e.toString())
        }
    }

    /** UTF-8 length of the compact JSON — the phone's size rule (protocol §1). */
    fun compactSize(e: JsonElement): Int = e.toString().toByteArray(Charsets.UTF_8).size
}
