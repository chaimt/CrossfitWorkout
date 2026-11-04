package com.wodcapture.domain.parser

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Derives the structured-output schema from the canonical `workout.schema.json` (REQ-AX02).
 * Structured outputs reject recursive schemas and numeric/string/array constraints, so the body
 * tree is unrolled to the model depth limit (4, REQ-DM16) and unsupported keywords are dropped;
 * deterministic validation re-checks them after parsing (REQ-AX05). Output is byte-stable.
 */
object StructuredSchema {
    const val DEPTH = 4
    private val UNSUPPORTED = setOf(
        "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "multipleOf", "minLength",
        "maxLength", "pattern", "minItems", "maxItems", "uniqueItems", "default", "\$schema", "\$id", "title",
    )

    fun canonicalText(): String = StructuredSchema::class.java.getResourceAsStream("/workout.schema.json")
        ?.bufferedReader()?.use { it.readText() } ?: error("workout.schema.json resource missing")

    /** Schema for the parser response: root = ParseResult, body unrolled, no recursion. */
    fun forParser(canonical: JsonObject = Json.parseToJsonElement(canonicalText()).jsonObject): JsonObject {
        val defs = canonical.getValue("\$defs").jsonObject
        val out = LinkedHashMap<String, JsonElement>()
        for ((name, def) in defs) {
            if (name == "BodyNode" || name == "Group") continue
            out[name] = retarget(def, "#/\$defs/BodyNode", "#/\$defs/BodyNode1")
        }
        for (d in 1..DEPTH) {
            val variants = buildList {
                add(ref("Item"))
                if (d < DEPTH) add(ref("Group$d"))
                add(ref("Rest"))
            }
            out["BodyNode$d"] = JsonObject(mapOf("anyOf" to JsonArray(variants)))
            if (d < DEPTH) out["Group$d"] = retarget(defs.getValue("Group"), "#/\$defs/BodyNode", "#/\$defs/BodyNode${d + 1}")
        }
        val root = LinkedHashMap(defs.getValue("ParseResult").jsonObject)
        root["\$defs"] = JsonObject(out.toSortedMap())
        return strip(JsonObject(root)) as JsonObject
    }

    private fun ref(name: String) = JsonObject(mapOf("\$ref" to JsonPrimitive("#/\$defs/$name")))

    private fun retarget(e: JsonElement, from: String, to: String): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.mapValues { (k, v) ->
            if (k == "\$ref" && v is JsonPrimitive && v.content == from) JsonPrimitive(to) else retarget(v, from, to)
        })
        is JsonArray -> JsonArray(e.map { retarget(it, from, to) })
        else -> e
    }

    /** Drops unsupported keywords and forces `additionalProperties: false` on every object schema. */
    private fun strip(e: JsonElement): JsonElement = when (e) {
        is JsonObject -> {
            val m = LinkedHashMap<String, JsonElement>()
            for ((k, v) in e) {
                if (k in UNSUPPORTED) continue
                // Property names are data, not keywords: recurse into their schemas only.
                m[k] = if (k == "properties" && v is JsonObject) JsonObject(v.mapValues { strip(it.value) }) else strip(v)
            }
            if (m["type"] == JsonPrimitive("object")) m["additionalProperties"] = JsonPrimitive(false)
            JsonObject(m)
        }
        is JsonArray -> JsonArray(e.map { strip(it) })
        else -> e
    }

    /** All `$ref` targets reachable from [schema], for the acyclicity check in tests. */
    fun refs(e: JsonElement): Set<String> = when (e) {
        is JsonObject -> e.flatMap { (k, v) -> if (k == "\$ref" && v is JsonPrimitive) setOf(v.content) else refs(v) }.toSet()
        is JsonArray -> e.flatMap { refs(it) }.toSet()
        else -> emptySet()
    }
}
