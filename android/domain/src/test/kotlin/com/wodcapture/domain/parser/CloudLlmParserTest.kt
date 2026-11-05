package com.wodcapture.domain.parser

import com.wodcapture.domain.DomainJson
import com.wodcapture.domain.Fixtures
import com.wodcapture.domain.IdSource
import com.wodcapture.domain.TestSupport
import com.wodcapture.domain.eval.EvalHarness
import com.wodcapture.domain.isWireUuid
import com.wodcapture.domain.model.BodyTree
import com.wodcapture.domain.model.Emom
import com.wodcapture.domain.model.ParseResult
import com.wodcapture.domain.model.SourceKind
import com.wodcapture.domain.validation.WorkoutValidator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** REQ-AX02–AX04 with a mocked HTTP layer; REQ-AX12 harness; REQ-AX18 job lifecycle. */
class CloudLlmParserTest {
    private val config = CloudParserConfig(apiKey = "sk-test-not-real")
    private val sleeps = mutableListOf<Long>()

    private class Scripted(vararg responses: () -> HttpResponse) : HttpTransport {
        val queue = ArrayDeque(responses.toList())
        val requests = mutableListOf<HttpRequest>()
        override fun execute(request: HttpRequest): HttpResponse { requests += request; return queue.removeFirst()() }
    }

    private fun parser(http: HttpTransport) = CloudLlmParser(config, TestSupport.catalog, http, IdSource.sequential("b0000000"), { sleeps += it })

    /** A Messages API response whose text block is the model's JSON. */
    private fun ok(result: String, stop: String = "end_turn") = HttpResponse(
        200, emptyMap(),
        """{"id":"msg_1","type":"message","role":"assistant","stop_reason":"$stop","content":[{"type":"thinking","thinking":""},{"type":"text","text":${JsonPrimitive(result)}}],"usage":{"input_tokens":5000,"output_tokens":900,"cache_read_input_tokens":4000}}""",
    )

    /** What a model returns: the H3 expectation with short local ids instead of UUIDs. */
    private fun modelOutput(): String {
        val w = Fixtures.h3.expected
        var text = DomainJson.encodeParseResult(ParseResult(w))
        val ids = Regex("f03\\d0000-0000-4000-8000-\\d{12}").findAll(text).map { it.value }.distinct().toList()
        ids.forEachIndexed { i, id -> text = text.replace(id, "n$i") }
        return text
    }

    @Test
    fun settingsOfferTheFourClaudeModelsDefaultFirst() {
        assertEquals(listOf("claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-5-5", "claude-fable-5-1"), CloudParserConfig.MODELS)
        assertEquals(CloudParserConfig.DEFAULT_MODEL, CloudParserConfig.MODELS.first())
    }

    @Test
    fun requestFollowsTheMessagesApiContract() {
        val img = ImageInput(byteArrayOf(1, 2, 3))
        val body = parser(Scripted()).buildRequestBody(ParseInput(images = listOf(img), hint = "loads are kg"))
        assertEquals("claude-opus-5-5", body["model"]!!.jsonPrimitive.content)
        assertEquals("adaptive", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val oc = body["output_config"]!!.jsonObject
        assertEquals("medium", oc["effort"]!!.jsonPrimitive.content)
        assertEquals("json_schema", oc["format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val system = body["system"]!!.jsonArray[0].jsonObject
        assertEquals("ephemeral", system["cache_control"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val content = body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
        val image = content[0].jsonObject
        assertEquals("image", image["type"]!!.jsonPrimitive.content)
        assertEquals("AQID", image["source"]!!.jsonObject["data"]!!.jsonPrimitive.content)
        assertEquals("image/jpeg", image["source"]!!.jsonObject["media_type"]!!.jsonPrimitive.content)
        assertTrue(content[1].jsonObject["text"]!!.jsonPrimitive.content.contains("loads are kg"))
        assertFalse(body.containsKey("temperature"))
        assertEquals("default", body["fallbacks"]!!.jsonPrimitive.content)
        val h = parser(Scripted()).headers()
        assertEquals("sk-test-not-real", h["x-api-key"])
        assertEquals(CloudParserConfig.FALLBACK_BETA, h["anthropic-beta"])
        assertFalse(config.toString().contains("sk-test"), "the key is never printed")
    }

    @Test
    fun systemPromptIsByteStableAndCarriesTheCatalog() {
        val a = SystemPrompt.build(TestSupport.catalog)
        assertEquals(a, SystemPrompt.build(TestSupport.catalog))
        assertTrue(a.contains("thruster: Thruster | TH, THR, thrusters"))
        assertTrue(a.contains("PU = pull-up or push-up"))
        assertFalse(Regex("20\\d\\d-\\d\\d-\\d\\dT").containsMatchIn(a), "no timestamps")
        val textOnly = parser(Scripted()).buildRequestBody(ParseInput(text = "AMRAP 10"))
        val withImage = parser(Scripted()).buildRequestBody(ParseInput(images = listOf(ImageInput(byteArrayOf(9)))))
        assertEquals(textOnly["system"], withImage["system"], "cached prefix independent of the input")
    }

    @Test
    fun structuredSchemaHasNoRecursionAndClosedObjects() {
        val s = StructuredSchema.forParser()
        val defs = s["\$defs"]!!.jsonObject
        assertFalse(defs.containsKey("BodyNode"))
        assertTrue(defs.containsKey("BodyNode4") && !defs.containsKey("Group4"))
        // Acyclic: walk the ref graph.
        fun refsOf(name: String) = StructuredSchema.refs(defs.getValue(name)).map { it.removePrefix("#/\$defs/") }
        val visiting = mutableSetOf<String>()
        val done = mutableSetOf<String>()
        fun dfs(n: String) {
            check(n !in visiting) { "cycle through $n" }
            if (n in done) return
            visiting += n; refsOf(n).forEach(::dfs); visiting -= n; done += n
        }
        defs.keys.forEach(::dfs)
        fun walk(e: kotlinx.serialization.json.JsonElement) {
            when (e) {
                is JsonObject -> {
                    if (e["type"] == JsonPrimitive("object")) assertEquals(JsonPrimitive(false), e["additionalProperties"])
                    listOf("minimum", "maxLength", "minItems", "pattern").forEach { k -> assertFalse(e.containsKey(k) && e[k] !is JsonObject, "unsupported $k") }
                    e.forEach { (k, v) -> if (k == "properties") (v as JsonObject).values.forEach(::walk) else walk(v) }
                }
                is JsonArray -> e.forEach(::walk)
                else -> Unit
            }
        }
        walk(s)
        assertEquals(StructuredSchema.forParser().toString(), s.toString(), "byte-stable")
    }

    @Test
    fun successNormalisesIdsAndSetsSource() {
        val http = Scripted({ ok(modelOutput()) })
        val o = assertIs<ParseOutcome.Success>(parser(http).parse(ParseInput(text = "EMOM 16 ...")))
        val w = o.result.workout
        assertTrue(isWireUuid(w.id))
        val ids = w.parts.flatMap { BodyTree.allIds(it) } + w.parts.map { it.id }
        assertTrue(ids.all(::isWireUuid))
        val stations = (w.parts[0].format as Emom).stations.mapNotNull { it.nodeId }
        assertTrue(stations.all { s -> BodyTree.find(w.parts[0].body, s) != null }, "references rewritten consistently")
        assertEquals(SourceKind.TEXT, w.source.kind)
        assertEquals(SystemPrompt.VERSION, w.source.promptVersion)
        assertEquals(4000, o.usage!!.cacheReadTokens)
        assertTrue(WorkoutValidator.validate(w).canSend)
        assertEquals(1.0, EvalHarness.compare("H3", Fixtures.h3.expected, w).accuracy)
    }

    @Test
    fun refusalTruncationAndSchemaErrorsAreSpecificAndNotRetried() {
        val refusal = HttpResponse(200, emptyMap(), """{"stop_reason":"refusal","stop_details":{"type":"refusal","category":"cyber","explanation":"x"},"content":[]}""")
        assertEquals(ParseError.Refusal("cyber", "x"), assertIs<ParseOutcome.Failure>(parser(Scripted({ refusal })).parse(ParseInput(text = "x"))).error)
        assertEquals(ParseError.Truncated, (parser(Scripted({ ok("{\"workout\":", "max_tokens") })).parse(ParseInput(text = "x")) as ParseOutcome.Failure).error)
        assertIs<ParseError.SchemaInvalid>((parser(Scripted({ ok("{\"workout\": 5}") })).parse(ParseInput(text = "x")) as ParseOutcome.Failure).error)
        assertEquals(ParseError.InvalidCredentials, (parser(Scripted({ HttpResponse(401, emptyMap(), "{}") })).parse(ParseInput(text = "x")) as ParseOutcome.Failure).error)
        val badModel = parser(Scripted({ HttpResponse(404, emptyMap(), """{"error":{"type":"not_found_error","message":"model"}}""") })).parse(ParseInput(text = "x"))
        assertEquals(1, (badModel as ParseOutcome.Failure).attempts)
        assertIs<ParseError.Configuration>(badModel.error)
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun rateLimitHonoursRetryAfterThenSucceeds() {
        val http = Scripted({ HttpResponse(429, mapOf("retry-after" to "7"), "{}") }, { throw IOException("timeout") }, { ok(modelOutput()) })
        val o = assertIs<ParseOutcome.Success>(parser(http).parse(ParseInput(text = "x")))
        assertEquals(3, o.attempts)
        assertEquals(listOf(7000L, 2000L), sleeps)
    }

    @Test
    fun serverErrorsStopAfterTwoRetries() {
        val http = Scripted({ HttpResponse(529, emptyMap(), "{}") }, { HttpResponse(500, emptyMap(), "{}") }, { HttpResponse(503, emptyMap(), "{}") }, { ok(modelOutput()) })
        val f = assertIs<ParseOutcome.Failure>(parser(http).parse(ParseInput(text = "x")))
        assertEquals(3, f.attempts)
        assertEquals(3, http.requests.size)
        assertIs<ParseError.Server>(f.error)
    }

    @Test
    fun cancellationStopsBeforeTheNextAttempt() {
        var cancelled = false
        val http = Scripted({ cancelled = true; HttpResponse(500, emptyMap(), "{}") }, { ok(modelOutput()) })
        val f = assertIs<ParseOutcome.Failure>(parser(http).parse(ParseInput(text = "x")) { cancelled })
        assertEquals(ParseError.Cancelled, f.error)
        assertEquals(1, http.requests.size)
    }

    @Test
    fun testKeyMapsStatuses() {
        assertEquals(KeyTestResult.Ok, parser(Scripted({ HttpResponse(200, emptyMap(), "{}") })).testKey())
        assertEquals(KeyTestResult.InvalidKey, parser(Scripted({ HttpResponse(401, emptyMap(), "{}") })).testKey())
        assertIs<KeyTestResult.Error>(parser(Scripted({ throw IOException("offline") })).testKey())
    }

    @Test
    fun costEstimateMatchesAppendixI() {
        assertEquals(4784, CostEstimator.imageTokens(2576, 1932), "capped at the 2576-px limit")
        assertEquals(36 * 29, CostEstimator.imageTokens(1000, 800))
        val usd = CostEstimator.typicalParseUsd(1)
        assertTrue(usd in 0.05..0.20, "typical parse ≈ \$0.05–0.10, got $usd")
    }

    @Test
    fun evalHarnessScoresMismatchesPerCategory() {
        val expected = Fixtures.h1.expected
        assertEquals(1.0, EvalHarness.compare("H1", expected, expected).accuracy)
        val wrong = expected.copy(parts = listOf(expected.parts[0].copy(format = com.wodcapture.domain.model.ForTime(capSec = 600))))
        val s = EvalHarness.compare("H1", expected, wrong)
        assertTrue(s.accuracy < 1.0)
        assertTrue(s.mismatches.any { it.startsWith("p0.format.capSec") })
        val report = com.wodcapture.domain.eval.EvalReport(listOf(s))
        assertTrue(report.accuracy(com.wodcapture.domain.eval.FieldCategory.CAPS) < 1.0)
        assertEquals(1.0, report.accuracy(com.wodcapture.domain.eval.FieldCategory.MOVEMENTS))
    }

    @Test
    fun evalHarnessRunsOverSharedFixturesWithAFakeParser() {
        val byText = Fixtures.all.associateBy { it.input() }
        val fake = object : WorkoutParser {
            override val id = "fake"
            override fun parse(input: ParseInput, cancel: CancellationSignal) =
                ParseOutcome.Success(ParseResult(byText.getValue(input.text!!).expected), null, 1)
        }
        val report = EvalHarness.run(fake, java.io.File(TestSupport.sharedDir, "fixtures"), TestSupport.goldenDir)
        assertEquals(10, report.fixtures.size)
        assertEquals(1.0, report.overall)
    }

    @Test
    fun parseJobLifecycle() {
        assertEquals(null, ParseJobs.create("j", "d", 1, consent = false))
        val job = ParseJobs.create("j", "d", 1, consent = true)!!
        val running = ParseJobs.start(job, JobPreconditions(cloudConsent = true, sourceExists = true))
        assertEquals(JobState.RUNNING, running.state)
        val result = ParseResult(Fixtures.h10.expected)
        // Draft edited to revision 2 meanwhile: late output is an alternative, not an overwrite (H10).
        val (late, completion) = ParseJobs.complete(running, ParseOutcome.Success(result, null, 1), currentDraftRevision = 2)
        assertIs<JobCompletion.Alternative>(completion)
        assertEquals(JobState.READY, late.state)
        // Transient failure: requeued until 3 total attempts, then failed.
        val (retry, _) = ParseJobs.complete(running, ParseOutcome.Failure(ParseError.Network("x"), 1), 1)
        assertEquals(JobState.QUEUED, retry.state)
        val (exhausted, _) = ParseJobs.complete(retry.copy(state = JobState.RUNNING), ParseOutcome.Failure(ParseError.Server(500, null), 2), 1)
        assertEquals(JobState.FAILED, exhausted.state)
        // Non-retryable errors never auto-requeue.
        val (auth, _) = ParseJobs.complete(running, ParseOutcome.Failure(ParseError.InvalidCredentials, 1), 1)
        assertEquals(JobState.FAILED, auth.state)
        assertFalse(ParseJobs.autoRequeueOnConnectivity(auth))
        // Cancelled jobs ignore late responses; consent withdrawn at execution cancels.
        val (_, ignored) = ParseJobs.complete(ParseJobs.cancel(running), ParseOutcome.Success(result, null, 1), 1)
        assertEquals(JobCompletion.Ignore, ignored)
        assertEquals(JobState.CANCELLED, ParseJobs.start(job, JobPreconditions(cloudConsent = false, sourceExists = true)).state)
    }

    @Test
    fun parseOutputRoundTripsThroughTheSchemaShapes() {
        Fixtures.all.forEach { fx ->
            val json = DomainJson.encodeParseResult(ParseResult(fx.expected))
            assertEquals(fx.expected, DomainJson.decodeParseResult(json).workout)
            Json.parseToJsonElement(json) // valid JSON
        }
    }
}
