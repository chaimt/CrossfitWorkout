package com.wodcapture.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Parser output wrapper (REQ-DM17): the workout plus diagnostics keyed by node ID and field
 * path, assumptions and verbatim unparsed spans. Deterministic validation — not confidence —
 * decides executability.
 */
@Serializable
data class ParseResult(
    val workout: Workout,
    val diagnostics: List<Diagnostic> = emptyList(),
    val assumptions: List<Assumption> = emptyList(),
    val unparsed: List<UnparsedSpan> = emptyList(),
)

@Serializable
enum class Confidence {
    @SerialName("high") HIGH,
    @SerialName("medium") MEDIUM,
    @SerialName("low") LOW,
}

/** Per-field confidence and alternatives (REQ-AX03). */
@Serializable
data class Diagnostic(
    val nodeId: String? = null,
    val path: String,
    val confidence: Confidence,
    val reason: String? = null,
    val rawSpan: String? = null,
    /** Source image index (0-based) or `text`. */
    val sourceRef: String? = null,
    val alternatives: List<String> = emptyList(),
)

/** Something the parser had to guess ("assumed 95/65 means lb", "PU = pull-up"; REQ-AX15). */
@Serializable
data class Assumption(
    val text: String,
    val nodeId: String? = null,
    val path: String? = null,
    /** Short token that was disambiguated, shown as a chip in review. */
    val token: String? = null,
)

/** Unreadable or unmapped board text, kept verbatim (REQ-AX03, REQ-AX07). */
@Serializable
data class UnparsedSpan(
    val text: String,
    val sourceRef: String? = null,
)
