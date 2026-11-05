package com.wodcapture.domain.protocol

import com.wodcapture.domain.TestSupport
import com.wodcapture.domain.model.ResultStatus
import com.wodcapture.domain.model.Score
import com.wodcapture.domain.model.SetStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** Decodes every shared wire fixture losslessly and rejects every invalid one (REQ-TS08, REQ-SY14). */
class WireFixturesTest {
    private val valid = File(TestSupport.sharedDir, "fixtures/wire/valid")
    private val invalid = File(TestSupport.sharedDir, "fixtures/wire/invalid")

    private fun load(f: File): JsonObject = Json.parseToJsonElement(f.readText()).jsonObject

    /** Decodes envelope + payload into typed objects, then re-encodes them. */
    private fun roundTrip(json: JsonObject): JsonObject {
        val env = assertIs<Decoded.Ok<Envelope>>(EnvelopeCodec.fromJson(json)).value
        val p = env.p
        val re: JsonObject? = when (env.t) {
            MessageType.PLAN_PUT -> WatchPlanCodec.toJson(WatchPlanCodec.parse(p!!))
            in MessageType.ACKS -> Ack.parse(p!!).toJson()
            MessageType.HELLO -> Hello.parse(p!!).toJson()
            MessageType.RESULT_PUT -> WireResult.parse(p!!).toJson()
            MessageType.SESSION_PUT -> WireSession.parse(p!!).toJson()
            MessageType.RESULT_CHUNK -> Chunk.parse(p!!).toJson()
            else -> p
        }
        return EnvelopeCodec.toJson(env.copy(p = re))
    }

    @TestFactory
    fun validFixturesRoundTripLosslessly() = valid.listFiles()!!.sortedBy { it.name }.map { f ->
        DynamicTest.dynamicTest(f.name) {
            val json = load(f)
            assertEquals(json, roundTrip(json))
            // Through the SDK Map graph as well (what ConnectIQ.sendMessage receives).
            val env = (EnvelopeCodec.fromJson(json) as Decoded.Ok).value
            val map = EnvelopeCodec.encode(env)
            assertEquals(env, (EnvelopeCodec.decode(map) as Decoded.Ok).value)
            assertTrue(map.values.none { it == null })
        }
    }

    @TestFactory
    fun invalidFixturesAreRejected() = invalid.listFiles()!!.sortedBy { it.name }.map { f ->
        DynamicTest.dynamicTest(f.name) {
            val json = load(f)
            val rejected = try {
                roundTrip(json)
                false
            } catch (e: WireException) {
                true
            } catch (e: AssertionError) {
                true // envelope rejected
            }
            if (!rejected) fail("${f.name} was accepted")
        }
    }

    @Test
    fun pendingScoreIsNeverReadAsZero() {
        val env = (EnvelopeCodec.fromJson(load(File(valid, "result.put.pending-score.json"))) as Decoded.Ok).value
        val out = ResultIngest(InMemoryResults()) { 1 }.ingest(env)
        assertEquals(AckStatus.OK, out.ack!!.st)
        val r = out.committedResult!!
        assertEquals(null, r.score, "pending = no score, not 0")
        assertEquals("time", r.scorePendingType)
    }

    @Test
    fun profilePutMatchesTheSharedFixture() {
        val env = (EnvelopeCodec.fromJson(load(File(valid, "profile.put.json"))) as Decoded.Ok).value
        val defaults = com.wodcapture.domain.model.WatchDefaults()
        assertEquals(env.p, ProfilePayload.toJson(defaults, "kg", 120))
        assertEquals("00000000-0000-4000-8000-000000000000", env.oid)
    }

    @Test
    fun unknownVersionIsAVersionError() {
        val json = load(File(invalid, "plan.unknown-schema-version.json"))
        val env = (EnvelopeCodec.fromJson(json) as Decoded.Ok).value
        val e = runCatching { WatchPlanCodec.parse(env.p!!) }.exceptionOrNull()
        assertEquals("version", (e as WireException).reason)
        val v2 = JsonObject(json + ("v" to kotlinx.serialization.json.JsonPrimitive(2)))
        assertEquals("version", (EnvelopeCodec.fromJson(v2) as Decoded.Err).reason)
    }

    @Test
    fun resultAndSessionFixturesIngest() {
        val repo = InMemoryResults()
        val sessions = InMemorySessions()
        val ingest = ResultIngest(repo) { 1 }
        val capped = (EnvelopeCodec.fromJson(load(File(valid, "result.put.capped.json"))) as Decoded.Ok).value
        val strength = (EnvelopeCodec.fromJson(load(File(valid, "result.put.strength.json"))) as Decoded.Ok).value
        val session = (EnvelopeCodec.fromJson(load(File(valid, "session.put.json"))) as Decoded.Ok).value

        // Session may arrive before its parts (REQ-SY16).
        val s = SessionIngest(sessions) { 1 }.ingest(session)
        assertEquals(AckStatus.OK, s.ack!!.st)
        assertEquals(2, SessionIngest.missingResults(s.committedSession!!) { it in repo.results }.size)

        val a = ingest.ingest(capped)
        assertEquals(Ack(capped.id, AckStatus.OK), a.ack)
        val r = assertNotNull(a.committedResult)
        assertEquals(ResultStatus.CAPPED, r.status)
        assertEquals(Score.Reps(83), r.score!!.score)
        assertEquals(151, r.hrAvg)
        assertEquals(null, r.calories, "missing calories stay null")

        val st = ingest.ingest(strength).committedResult!!
        assertEquals(SetStatus.PARTIAL, st.sets.last().status)
        assertEquals(2, st.sets.last().actualReps)
        assertEquals(Score.Load(100.0, com.wodcapture.domain.model.MassUnit.KG), st.score!!.score)

        assertTrue(SessionIngest.missingResults(s.committedSession!!) { it in repo.results }.isEmpty())
        // A duplicate delivery is re-acked, not re-applied.
        assertEquals(AckStatus.ALREADY_APPLIED, ingest.ingest(capped).ack!!.st)
        assertEquals(AckStatus.ALREADY_APPLIED, SessionIngest(sessions) { 1 }.ingest(session).ack!!.st)
    }
}
