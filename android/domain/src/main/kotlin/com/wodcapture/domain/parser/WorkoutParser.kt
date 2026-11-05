package com.wodcapture.domain.parser

import com.wodcapture.domain.model.ParseResult

/** One pre-processed image (EXIF-stripped JPEG, long edge ≤ 2576 px; REQ-AC05). */
class ImageInput(val bytes: ByteArray, val mediaType: String = "image/jpeg", val width: Int? = null, val height: Int? = null)

/** What to parse: up to 4 ordered images and/or text, plus an optional hint (REQ-AC04, AX14). */
data class ParseInput(
    val images: List<ImageInput> = emptyList(),
    val text: String? = null,
    val hint: String? = null,
) {
    init {
        require(images.isNotEmpty() || !text.isNullOrBlank()) { "Nothing to parse" }
        require(images.size <= 4) { "At most 4 images per parse (REQ-AC04)" }
    }
}

/** Token usage reported by the provider, for the cost display (REQ-AX09). */
data class ParseUsage(val inputTokens: Int, val outputTokens: Int, val cacheReadTokens: Int = 0, val cacheWriteTokens: Int = 0)

/** Specific, user-actionable parse failures (REQ-AX04). Partial output is never a parse. */
sealed interface ParseError {
    /** May be retried automatically (network, 429, 5xx) — budget already spent when surfaced. */
    val retryable: Boolean get() = false

    data class Refusal(val category: String?, val explanation: String?) : ParseError
    data object InvalidCredentials : ParseError
    data class PermissionDenied(val message: String?) : ParseError
    /** Bad model id, too-large request, unsupported configuration. */
    data class Configuration(val message: String?) : ParseError
    /** `stop_reason: max_tokens` — output incomplete. */
    data object Truncated : ParseError
    data class SchemaInvalid(val detail: String) : ParseError
    data class RateLimited(val retryAfterSec: Long?) : ParseError { override val retryable get() = true }
    data class Server(val status: Int, val message: String?) : ParseError { override val retryable get() = true }
    data class Network(val message: String?) : ParseError { override val retryable get() = true }
    data object Cancelled : ParseError
    data object ConsentRequired : ParseError
}

sealed interface ParseOutcome {
    data class Success(val result: ParseResult, val usage: ParseUsage?, val attempts: Int) : ParseOutcome
    data class Failure(val error: ParseError, val attempts: Int) : ParseOutcome
}

/** Cooperative cancellation checked between attempts (REQ-AX08 cancellable). */
fun interface CancellationSignal {
    fun isCancelled(): Boolean

    companion object {
        val NONE = CancellationSignal { false }
    }
}

/**
 * Parser abstraction (REQ-AX01). Implementations: [CloudLlmParser] (MVP); OnDevice/OCR/TextRule
 * parsers are v1. Blocking: callers run it off the main thread (REQ-NF05).
 */
interface WorkoutParser {
    val id: String
    fun parse(input: ParseInput, cancel: CancellationSignal = CancellationSignal.NONE): ParseOutcome
}
