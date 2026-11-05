package com.wodcapture.domain.parser

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * The response schema the parser asks for (REQ-AX02), derived from the canonical
 * `workout.schema.json` with ParseResult as the root.
 *
 * It is sent in the cached system prompt, not as `output_config.format`: the canonical schema
 * (about 50 definitions, 128 optional properties, a recursive body tree) is far past the API's
 * structured-output grammar limits (24 optional parameters, 16 unions per request) and was
 * rejected with "The compiled grammar is too large". Deterministic decoding and validation
 * (REQ-AX05) check the reply instead. Output is byte-stable so the prompt prefix caches.
 */
object StructuredSchema {
    /** Keywords that only identify the file; they cost prompt tokens and tell the model nothing. */
    private val DROPPED = setOf("\$schema", "\$id", "title")

    fun canonicalText(): String = StructuredSchema::class.java.getResourceAsStream("/workout.schema.json")
        ?.bufferedReader()?.use { it.readText() } ?: error("workout.schema.json resource missing")

    /** Root = ParseResult, all definitions kept (recursion is fine in a prompt). */
    fun forPrompt(canonical: JsonObject = Json.parseToJsonElement(canonicalText()).jsonObject): JsonObject {
        val defs = canonical.getValue("\$defs").jsonObject
        require("ParseResult" in defs) { "workout.schema.json has no ParseResult definition" }
        return JsonObject(
            mapOf(
                "\$ref" to JsonPrimitive("#/\$defs/ParseResult"),
                "\$defs" to strip(JsonObject(defs.toSortedMap())),
            ),
        )
    }

    /** Compact single-line text of [forPrompt]. */
    fun promptText(): String = forPrompt().toString()

    private fun strip(e: JsonElement): JsonElement = when (e) {
        is JsonObject -> JsonObject(
            e.filterKeys { it !in DROPPED }.mapValues { (k, v) ->
                // Property names are data, not keywords: recurse into their schemas only.
                if (k == "properties" && v is JsonObject) JsonObject(v.mapValues { strip(it.value) }) else strip(v)
            },
        )
        is JsonArray -> JsonArray(e.map { strip(it) })
        else -> e
    }
}
