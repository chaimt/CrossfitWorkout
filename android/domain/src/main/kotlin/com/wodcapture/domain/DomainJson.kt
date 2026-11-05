package com.wodcapture.domain

import com.wodcapture.domain.model.ParseResult
import com.wodcapture.domain.model.Workout
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * JSON configurations used across the domain. Storage keeps defaults explicit so persisted
 * data is self-describing; nulls are always omitted (the wire has no nulls, protocol §1).
 */
object DomainJson {
    /** Canonical storage/fixture encoding: pretty, defaults included, nulls omitted. */
    val storage: Json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    /** Lenient decoding for model output: tolerates unknown keys and coerces bad enums to defaults. */
    val lenient: Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
        isLenient = false
    }

    fun encodeWorkout(w: Workout): String = storage.encodeToString(Workout.serializer(), w)
    fun decodeWorkout(s: String): Workout = lenient.decodeFromString(Workout.serializer(), s)
    fun encodeParseResult(r: ParseResult): String = storage.encodeToString(ParseResult.serializer(), r)
    fun decodeParseResult(s: String): ParseResult = lenient.decodeFromString(ParseResult.serializer(), s)
}

/** Source of UUIDs; injectable so tests and fixtures are deterministic (REQ-DM15). */
fun interface IdSource {
    fun newId(): String

    companion object {
        /** Random lower-case hyphenated v4 UUIDs (protocol §0). */
        val RANDOM = IdSource { UUID.randomUUID().toString() }

        /** Deterministic sequence `prefix-0000-4000-8000-<counter>` for tests. */
        fun sequential(prefix: String = "f0000000"): IdSource {
            var n = 0L
            return IdSource { n += 1; "%s-0000-4000-8000-%012x".format(prefix, n) }
        }
    }
}

/** Lower-case hyphenated UUID check used by validators (protocol §0). */
private val UUID_RE = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

fun isWireUuid(s: String): Boolean = UUID_RE.matches(s)
