package com.wodcapture.domain.parser

import com.wodcapture.domain.DomainJson
import com.wodcapture.domain.IdSource
import com.wodcapture.domain.catalog.MovementCatalog
import com.wodcapture.domain.model.ParseResult
import com.wodcapture.domain.model.SourceKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.util.Base64

/** Parser settings (REQ-AG03). The API key is supplied per call and never logged (REQ-NF07). */
data class CloudParserConfig(
    val apiKey: String,
    val model: String = DEFAULT_MODEL,
    val effort: String = "medium",
    val maxTokens: Int = 16_000,
    val baseUrl: String = "https://api.anthropic.com",
    val apiVersion: String = "2026-10-21",
    /** Server-side refusal fallback (`fallbacks: "default"`), on by default for this model family. */
    val serverFallback: Boolean = true,
    val maxRetries: Int = 2,
) {
    override fun toString(): String = "CloudParserConfig(model=$model, effort=$effort, key=***)"

    companion object {
        const val DEFAULT_MODEL = "claude-opus-5-5"

        /** Models offered in the Settings → Model dropdown (REQ-AG03), default first. */
        val MODELS: List<String> = listOf(DEFAULT_MODEL, "claude-sonnet-5-5", "claude-haiku-5-5", "claude-fable-5-1")
        const val FALLBACK_BETA = "server-side-fallback-2026-07-01"
    }
}

/** Sleep hook so retry backoff is instant in tests. */
fun interface Sleeper {
    fun sleep(ms: Long)

    companion object {
        val REAL = Sleeper { Thread.sleep(it) }
    }
}

/**
 * Cloud parser on the Anthropic Messages API over plain HTTP (REQ-AX02–AX04): image blocks or
 * text, the response JSON Schema in the system prompt (see [StructuredSchema] for why it is not
 * `output_config.format`), adaptive thinking with an explicit effort, byte-stable cached system prompt, `stop_reason` checked before consuming output, and at
 * most [CloudParserConfig.maxRetries] retries on network/429/5xx with Retry-After/backoff.
 */
class CloudLlmParser(
    private val config: CloudParserConfig,
    catalog: MovementCatalog,
    private val http: HttpTransport,
    private val ids: IdSource = IdSource.RANDOM,
    private val sleeper: Sleeper = Sleeper.REAL,
) : WorkoutParser {
    override val id: String = "cloud:${config.model}"
    private val systemPrompt = SystemPrompt.build(catalog)

    /** Request body, exposed for tests and token counting. */
    fun buildRequestBody(input: ParseInput): JsonObject = buildJsonObject {
        put("model", config.model)
        put("max_tokens", config.maxTokens)
        putJsonObject("thinking") { put("type", "adaptive") }
        putJsonObject("output_config") { put("effort", config.effort) }
        putJsonArray("system") {
            addJsonObject {
                put("type", "text")
                put("text", systemPrompt)
                putJsonObject("cache_control") { put("type", "ephemeral") }
            }
        }
        if (config.serverFallback) put("fallbacks", "default")
        putJsonArray("messages") {
            addJsonObject {
                put("role", "user")
                put("content", userContent(input))
            }
        }
    }

    private fun userContent(input: ParseInput): JsonArray = buildJsonArray {
        input.images.forEach { img ->
            addJsonObject {
                put("type", "image")
                putJsonObject("source") {
                    put("type", "base64")
                    put("media_type", img.mediaType)
                    put("data", Base64.getEncoder().encodeToString(img.bytes))
                }
            }
        }
        val text = buildString {
            append(
                if (input.images.isNotEmpty()) "Parse the workout on these ${input.images.size} whiteboard image(s), in order."
                else "Parse this workout text.",
            )
            input.text?.takeIf { it.isNotBlank() }?.let { append("\n\n<workout_text>\n").append(it).append("\n</workout_text>") }
            input.hint?.takeIf { it.isNotBlank() }?.let { append("\n\nAthlete hint: ").append(it) }
        }
        addJsonObject {
            put("type", "text")
            put("text", text)
        }
    }

    fun headers(): Map<String, String> = buildMap {
        put("x-api-key", config.apiKey)
        put("anthropic-version", config.apiVersion)
        put("content-type", "application/json")
        if (config.serverFallback) put("anthropic-beta", CloudParserConfig.FALLBACK_BETA)
    }

    override fun parse(input: ParseInput, cancel: CancellationSignal): ParseOutcome {
        val body = buildRequestBody(input).toString()
        var attempt = 0
        while (true) {
            if (cancel.isCancelled()) return ParseOutcome.Failure(ParseError.Cancelled, attempt)
            attempt += 1
            val outcome = try {
                interpret(http.execute(HttpRequest("POST", "${config.baseUrl}/v1/messages", headers(), body)), input, attempt)
            } catch (e: IOException) {
                ParseOutcome.Failure(ParseError.Network(e.message), attempt)
            }
            if (outcome !is ParseOutcome.Failure || !outcome.error.retryable || attempt > config.maxRetries) return outcome
            sleeper.sleep(backoffMs(outcome.error, attempt))
            // A request already sent cannot be recalled; a cancel now just ignores the next try (REQ-AX18).
            if (cancel.isCancelled()) return ParseOutcome.Failure(ParseError.Cancelled, attempt)
        }
    }

    private fun backoffMs(error: ParseError, attempt: Int): Long {
        val retryAfter = (error as? ParseError.RateLimited)?.retryAfterSec
        return retryAfter?.let { it * 1000 } ?: (1000L shl (attempt - 1))
    }

    private fun interpret(resp: HttpResponse, input: ParseInput, attempt: Int): ParseOutcome {
        if (resp.status != 200) return ParseOutcome.Failure(httpError(resp), attempt)
        val json = try {
            Json.parseToJsonElement(resp.body).jsonObject
        } catch (e: Exception) {
            return ParseOutcome.Failure(ParseError.SchemaInvalid("Response is not JSON"), attempt)
        }
        when (val stop = json["stop_reason"]?.jsonPrimitive?.contentOrNull) {
            "end_turn" -> Unit
            "refusal" -> {
                val d = json["stop_details"] as? JsonObject
                return ParseOutcome.Failure(
                    ParseError.Refusal(d?.get("category")?.jsonPrimitive?.contentOrNull, d?.get("explanation")?.jsonPrimitive?.contentOrNull),
                    attempt,
                )
            }
            "max_tokens" -> return ParseOutcome.Failure(ParseError.Truncated, attempt)
            else -> return ParseOutcome.Failure(ParseError.SchemaInvalid("Unexpected stop_reason $stop"), attempt)
        }
        val text = json["content"]?.jsonArray.orEmpty()
            .filterIsInstance<JsonObject>()
            .filter { it["type"]?.jsonPrimitive?.contentOrNull == "text" }
            .joinToString("") { it["text"]?.jsonPrimitive?.contentOrNull.orEmpty() }
        val jsonText = jsonObjectText(text) ?: return ParseOutcome.Failure(ParseError.SchemaInvalid("No JSON object in the reply"), attempt)
        val parsed = try {
            DomainJson.decodeParseResult(jsonText)
        } catch (e: Exception) {
            return ParseOutcome.Failure(ParseError.SchemaInvalid(e.message?.lineSequence()?.firstOrNull() ?: "decode failed"), attempt)
        }
        if (parsed.workout.parts.isEmpty()) return ParseOutcome.Failure(ParseError.SchemaInvalid("No parts"), attempt)
        return ParseOutcome.Success(finish(parsed, input), usage(json), attempt)
    }

    /** The reply's JSON object; tolerates a code fence or a stray sentence around it. */
    private fun jsonObjectText(text: String): String? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        return if (start < 0 || end <= start) null else text.substring(start, end + 1)
    }

    private fun finish(r: ParseResult, input: ParseInput): ParseResult {
        val normalized = IdNormalizer(ids).normalize(r)
        val w = normalized.workout
        return normalized.copy(
            workout = w.copy(
                schemaVersion = 1,
                revision = 1,
                source = w.source.copy(
                    kind = if (input.images.isNotEmpty()) SourceKind.PHOTO else SourceKind.TEXT,
                    rawText = input.text,
                    parser = "cloud",
                    model = config.model,
                    promptVersion = SystemPrompt.VERSION,
                ),
                // Review sets these; never trust them from the model.
                parts = w.parts.map { it.copy(fallback = null, chosenLevel = null) },
            ),
        )
    }

    private fun usage(json: JsonObject): ParseUsage? {
        val u = json["usage"] as? JsonObject ?: return null
        fun n(k: String) = u[k]?.jsonPrimitive?.intOrNull ?: 0
        return ParseUsage(n("input_tokens"), n("output_tokens"), n("cache_read_input_tokens"), n("cache_creation_input_tokens"))
    }

    private fun httpError(resp: HttpResponse): ParseError {
        val message = runCatching {
            Json.parseToJsonElement(resp.body).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        return when (resp.status) {
            401 -> ParseError.InvalidCredentials
            403 -> ParseError.PermissionDenied(message)
            429 -> ParseError.RateLimited(resp.header("retry-after")?.trim()?.toLongOrNull())
            400, 404, 413, 422 -> ParseError.Configuration(message)
            408 -> ParseError.Network("request timeout")
            in 500..599 -> ParseError.Server(resp.status, message)
            else -> ParseError.Configuration("HTTP ${resp.status}: $message")
        }
    }

    /** "Test key" (REQ-AX11): fetches the model metadata; 200 = key and model usable. */
    fun testKey(): KeyTestResult = try {
        val r = http.execute(HttpRequest("GET", "${config.baseUrl}/v1/models/${config.model}", headers() - "content-type"))
        when (r.status) {
            200 -> KeyTestResult.Ok
            401 -> KeyTestResult.InvalidKey
            404 -> KeyTestResult.UnknownModel
            else -> KeyTestResult.Error("HTTP ${r.status}")
        }
    } catch (e: IOException) {
        KeyTestResult.Error(e.message ?: "network error")
    }
}

sealed interface KeyTestResult {
    data object Ok : KeyTestResult
    data object InvalidKey : KeyTestResult
    data object UnknownModel : KeyTestResult
    data class Error(val message: String) : KeyTestResult
}

/** Cost estimate shown in settings (REQ-AX09). Prices per MTok for claude-opus-5-5. */
object CostEstimator {
    const val INPUT_USD_PER_MTOK = 4.0
    const val OUTPUT_USD_PER_MTOK = 20.0
    const val MAX_IMAGE_TOKENS = 4784

    /** ⌈w/28⌉ × ⌈h/28⌉ visual tokens, capped at the 2576-px limit. */
    fun imageTokens(width: Int, height: Int): Int =
        minOf(((width + 27) / 28) * ((height + 27) / 28), MAX_IMAGE_TOKENS)

    fun usd(inputTokens: Int, outputTokens: Int): Double =
        inputTokens * INPUT_USD_PER_MTOK / 1e6 + outputTokens * OUTPUT_USD_PER_MTOK / 1e6

    fun usd(usage: ParseUsage): Double = usd(usage.inputTokens + usage.cacheWriteTokens, usage.outputTokens) +
        usage.cacheReadTokens * 0.2 / 1e6

    /** Typical parse: images + ~6k prompt tokens (mostly cached) + ~4k output/thinking. */
    fun typicalParseUsd(imageCount: Int): Double = usd(imageCount * MAX_IMAGE_TOKENS + 6000, 4000)
}
