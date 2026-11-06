package com.wodcapture.domain.protocol

import com.wodcapture.domain.Fixtures
import com.wodcapture.domain.IdSource
import com.wodcapture.domain.TestSupport
import com.wodcapture.domain.model.Amrap
import com.wodcapture.domain.plan.CompileResult
import com.wodcapture.domain.plan.PlanCompiler
import com.wodcapture.domain.plan.PlanIdentity
import com.wodcapture.domain.plan.WatchPlan
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Minimal watch-side receiver following protocol §3.1 (validate → commit → ack) so the phone's
 * outbox can be exercised end to end without a device (REQ-SY04, SY11, SY13, SY14; REQ-TS09).
 */
class FakeWatch(private val ids: IdSource) {
    val plans = mutableMapOf<String, Pair<Int, JsonObject>>()
    val tombstones = mutableMapOf<String, Int>()
    var generation = 1
    var dropNext = 0
    val received = mutableListOf<Envelope>()

    /** Handles one phone message; returns the watch's ack (or null if lost/none). */
    fun receive(map: Map<String, Any>): Map<String, Any>? {
        val env = (EnvelopeCodec.decode(map) as Decoded.Ok).value
        received += env
        if (dropNext > 0) { dropNext--; return null }
        val st: Pair<String, String?> = when {
            env.g < generation -> AckStatus.STALE to "generation"
            env.t == MessageType.PLAN_PUT -> putPlan(env)
            env.t == MessageType.PLAN_DEL -> {
                val cur = tombstones[env.oid!!]
                if (cur != null && cur >= env.rev!!) AckStatus.ALREADY_APPLIED to null
                else { tombstones[env.oid] = env.rev!!; plans.remove(env.oid); AckStatus.OK to null }
            }
            else -> return null
        }
        val ackType = MessageType.PLAN_ACK
        return EnvelopeCodec.encode(Envelope(t = ackType, id = ids.newId(), ts = env.ts, g = env.g, oid = env.oid, rev = env.rev, p = Ack(env.id, st.first, st.second).toJson()))
    }

    private fun putPlan(env: Envelope): Pair<String, String?> {
        val oid = env.oid!!
        val rev = env.rev!!
        try { WatchPlanCodec.parse(env.p!!) } catch (e: WireException) { return AckStatus.ERROR to e.reason }
        if (EnvelopeCodec.sizeBytes(env) > Protocol.MAX_MESSAGE_BYTES) return AckStatus.ERROR to "size"
        tombstones[oid]?.let { if (rev <= it) return AckStatus.STALE to "stale" }
        val cur = plans[oid]
        return when {
            cur == null || cur.first < rev -> { plans[oid] = rev to env.p; AckStatus.OK to null }
            cur.first > rev -> AckStatus.STALE to "stale"
            cur.second == env.p -> AckStatus.ALREADY_APPLIED to null
            else -> AckStatus.CONFLICT to "conflict"
        }
    }

    fun hello(g: Int = generation): Map<String, Any> = EnvelopeCodec.encode(
        Envelope(
            t = MessageType.HELLO, id = ids.newId(), ts = 1, g = g,
            p = Hello(listOf(1), "0.1.0", "fr965", plans.map { IdRev(it.key, it.value.first) }, tombstones.map { IdRev(it.key, it.value) }, 61440, 0, listOf("amrap")).toJson(),
        ),
    )
}

/** Transport that hands messages synchronously to a [FakeWatch] and loops acks back. */
class FakeTransport(private val watch: FakeWatch) : WatchTransport {
    lateinit var engine: SyncEngine
    var status = SendStatus.SUCCESS
    var deliverAcks = true
    val sent = mutableListOf<Map<String, Any>>()
    val pendingAcks = mutableListOf<Map<String, Any>>()

    override fun send(target: WatchTarget, message: Map<String, Any>, onStatus: (SendStatus) -> Unit) {
        sent += message
        onStatus(status)
        if (status != SendStatus.SUCCESS) return
        val ack = watch.receive(message) ?: return
        if (deliverAcks) engine.onMessage(target, ack) else pendingAcks += ack
    }
}

class SyncEngineTest {
    @Test
    fun exhaustedCallbackRetriesPersistWaitingState() {
        var persisted = emptyList<OutboxEntry>()
        val ingest = ResultIngest(InMemoryResults()) { 1 }
        val silent = object : WatchTransport {
            override fun send(target: WatchTarget, message: Map<String, Any>, onStatus: (SendStatus) -> Unit) {}
        }
        val e = SyncEngine(silent, Outbox(), ingest, SessionIngest(InMemorySessions()) { 1 },
            ChunkStager(InMemoryChunkStore(), ingest) { null }, ids, { now },
            object : SyncListener {
                override fun onOutboxChanged(outbox: List<OutboxEntry>) { persisted = outbox }
            })
        e.sendPlan(target, plan(1))
        listOf(35_000L, 45_000L, 90_000L, 90_000L).forEach { now += it; e.tick(target) }
        assertEquals(DeliveryState.WAITING, persisted.single().state)
    }

    @Test
    fun sdkCallbacksWaitForSerialExecutorAndCannotUndoAck() {
        val callbacks = ArrayDeque<Runnable>()
        val ingest = ResultIngest(InMemoryResults()) { 1 }
        lateinit var statusCallback: (SendStatus) -> Unit
        val asyncTransport = object : WatchTransport {
            override fun send(target: WatchTarget, message: Map<String, Any>, onStatus: (SendStatus) -> Unit) {
                statusCallback = onStatus
            }
        }
        val box = Outbox()
        val serialEngine = SyncEngine(
            asyncTransport, box, ingest, SessionIngest(InMemorySessions()) { 1 },
            ChunkStager(InMemoryChunkStore(), ingest) { null }, ids, { now },
            callbackExecutor = java.util.concurrent.Executor { callbacks.addLast(it) },
        )
        val entry = serialEngine.sendPlan(target, plan(1))
        statusCallback(SendStatus.SUCCESS)
        assertEquals(DeliveryState.SENDING, box.snapshot().single().state, "callback must not mutate outside executor")
        box.onAck(target, Ack(entry.messageId, AckStatus.OK))
        callbacks.removeFirst().run()
        assertEquals(DeliveryState.ON_WATCH, box.snapshot().single().state)
    }

    private val ids = IdSource.sequential("e0000000")
    private val target = WatchTarget("fr965-1", Protocol.APP_UUID_PRODUCTION, 1)
    private var now = 1_793_700_000_000L
    private lateinit var watch: FakeWatch
    private lateinit var transport: FakeTransport
    private lateinit var results: InMemoryResults
    private lateinit var engine: SyncEngine

    @BeforeTest
    fun setUp() {
        watch = FakeWatch(IdSource.sequential("d0000000"))
        transport = FakeTransport(watch)
        results = InMemoryResults()
        val ingest = ResultIngest(results) { target.generation }
        engine = SyncEngine(
            transport, Outbox(), ingest, SessionIngest(InMemorySessions()) { target.generation },
            ChunkStager(InMemoryChunkStore(), ingest) { results.dedupe(it) }, ids, { now },
        )
        transport.engine = engine
    }

    private fun plan(rev: Int, minutes: Int = 10): WatchPlan {
        val fx = Fixtures.h10
        val w = fx.expected.copy(revision = rev, parts = listOf(fx.expected.parts[0].copy(format = Amrap(minutes * 60))))
        return (PlanCompiler(TestSupport.catalog).compile(w, TestSupport.profile, PlanIdentity(fx.identity.planId, rev)) as CompileResult.Success).plan
    }

    @Test
    fun planIsOnWatchOnlyAfterTheApplicationAck() {
        transport.deliverAcks = false
        val e = engine.sendPlan(target, plan(1))
        assertEquals(DeliveryState.SENT, engine.outbox.stateOf(target, e.objectId)!!.state, "SDK success is not delivery")
        engine.onMessage(target, transport.pendingAcks.removeAt(0))
        assertEquals(DeliveryState.ON_WATCH, engine.outbox.stateOf(target, e.objectId)!!.state)
    }

    @Test
    fun lostAckRetriesWithSameIdAndBodyThenWaits() {
        watch.dropNext = 10
        val e = engine.sendPlan(target, plan(1))
        val delays = listOf(35_000L, 45_000L, 90_000L)
        delays.forEach { d -> now += d; engine.tick(target) }
        assertEquals(4, transport.sent.size)
        assertTrue(transport.sent.all { it == transport.sent[0] }, "retries reuse message id + body (REQ-SY02)")
        now += 90_000; engine.tick(target)
        assertEquals(DeliveryState.WAITING, engine.outbox.stateOf(target, e.objectId)!!.state)
        assertEquals(4, transport.sent.size, "max 3 retries per drain")
        // Explicit Sync restarts the drain; watch now acks.
        watch.dropNext = 0
        engine.requestSync(target)
        assertEquals(DeliveryState.ON_WATCH, engine.outbox.stateOf(target, e.objectId)!!.state)
        assertEquals(1, watch.plans.size)
    }

    @Test
    fun disconnectedSuspendsAndReconnectResumes() {
        transport.status = SendStatus.FAILURE_DEVICE_NOT_CONNECTED
        val e = engine.sendPlan(target, plan(1))
        assertEquals(DeliveryState.SUSPENDED, engine.outbox.stateOf(target, e.objectId)!!.state)
        transport.status = SendStatus.SUCCESS
        engine.onDeviceConnected(target)
        assertEquals(DeliveryState.ON_WATCH, engine.outbox.stateOf(target, e.objectId)!!.state)
    }

    @Test
    fun tooLargeStopsRetrying() {
        transport.status = SendStatus.FAILURE_MESSAGE_TOO_LARGE
        val e = engine.sendPlan(target, plan(1))
        val s = engine.outbox.stateOf(target, e.objectId)!!
        assertEquals(DeliveryState.FAILED, s.state)
        assertEquals("size", s.reason)
    }

    @Test
    fun h10RevisionsNeverResendOlderAndHelloResendsMissing() {
        transport.deliverAcks = false
        engine.sendPlan(target, plan(1))
        // Manual edit v2 (AMRAP 12) before rev 1 was acked: rev 1 is superseded.
        engine.sendPlan(target, plan(2, 12))
        val states = engine.outbox.snapshot().associate { it.revision to it.state }
        assertEquals(null, states[1], "superseded pending revisions are compacted")
        // A late attempt to queue rev 1 again is refused (never send older, REQ-SY11).
        assertTrue(runCatching { engine.sendPlan(target, plan(1)) }.isFailure)
        transport.deliverAcks = true
        transport.pendingAcks.clear()
        engine.requestSync(target)
        assertEquals(2, watch.plans.values.single().first)
        // Watch reinstalled and lost its inbox: hello lists nothing, phone resends rev 2 only.
        watch.plans.clear()
        val before = transport.sent.size
        now += 6 * 60_000
        engine.onMessage(target, watch.hello())
        assertEquals(2, watch.plans.values.single().first)
        assertTrue(transport.sent.drop(before).none { (it["rev"] as Int) < 2 })
    }

    @Test
    fun deleteIsATombstoneThatBlocksDelayedPuts() {
        val p = plan(1)
        engine.sendPlan(target, p)
        engine.deletePlan(target, p.id, 2)
        assertTrue(watch.plans.isEmpty())
        assertEquals(2, watch.tombstones[p.id])
        // A delayed rev-1 put (duplicate from the mailbox) is stale on the watch.
        val replay = transport.sent.first()
        val ack = watch.receive(replay)!!
        assertEquals(AckStatus.STALE, Ack.parse((EnvelopeCodec.decode(ack) as Decoded.Ok).value.p!!).st)
        val h = Hello.parse((EnvelopeCodec.decode(watch.hello()) as Decoded.Ok).value.p!!)
        assertTrue(HelloDiff.toResend(listOf(PlanRecord(p.id, 2, deleted = true)), h).isEmpty())
    }

    @Test
    fun helloDiffSendsMissingNewerAndUnappliedTombstones() {
        val a = "a0000000-0000-4000-8000-000000000001"
        val b = "a0000000-0000-4000-8000-000000000002"
        val c = "a0000000-0000-4000-8000-000000000003"
        val d = "a0000000-0000-4000-8000-000000000004"
        val hello = Hello(listOf(1), "0.1.0", "fr965", listOf(IdRev(a, 2), IdRev(b, 1), IdRev(d, 5)), emptyList(), 1000, 0, emptyList())
        val phone = listOf(PlanRecord(a, 2, false), PlanRecord(b, 3, false), PlanRecord(c, 1, false), PlanRecord(d, 4, false), PlanRecord("a0000000-0000-4000-8000-000000000005", 2, true))
        assertEquals(listOf(b, c, "a0000000-0000-4000-8000-000000000005"), HelloDiff.toResend(phone, hello).map { it.planId })
    }

    private fun resultEnvelope(file: String, g: Int = 1): Map<String, Any> {
        val json = Json.parseToJsonElement(File(TestSupport.sharedDir, "fixtures/wire/valid/$file").readText()).jsonObject
        return EnvelopeCodec.encode((EnvelopeCodec.fromJson(JsonObject(json + ("g" to JsonPrimitive(g))))as Decoded.Ok).value)
    }

    @Test
    fun resultsAreCommittedThenAckedAndRetriesNeverResurrect() {
        engine.onMessage(target, resultEnvelope("result.put.capped.json"))
        val ack = transport.sent.last()
        assertEquals(MessageType.RESULT_ACK, ack["t"])
        assertEquals("ok", (ack["p"] as Map<*, *>)["st"])
        val id = "80000000-0000-4000-8000-000000000001"
        assertNotNull(results.results[id])
        // Phone edit, then a watch retry: re-acked, edit kept.
        results.editLocally(id)
        engine.onMessage(target, resultEnvelope("result.put.capped.json"))
        assertEquals("already_applied", (transport.sent.last()["p"] as Map<*, *>)["st"])
        // Phone deletion keeps a tombstone; a retry cannot resurrect it (REQ-DM27, TS09).
        results.delete(id)
        engine.onMessage(target, resultEnvelope("result.put.capped.json"))
        assertEquals("already_applied", (transport.sent.last()["p"] as Map<*, *>)["st"])
        assertTrue(id !in results.results)
    }

    @Test
    fun oldGenerationResultsAreQuarantinedNotAcked() {
        val gen2 = target.copy(generation = 2)
        val ingest = ResultIngest(results) { 2 }
        val engine2 = SyncEngine(transport, Outbox(), ingest, SessionIngest(InMemorySessions()) { 2 }, ChunkStager(InMemoryChunkStore(), ingest) { null }, ids, { now })
        val before = transport.sent.size
        engine2.onMessage(gen2, resultEnvelope("result.put.capped.json", g = 1))
        assertEquals(before, transport.sent.size, "no ack: the watch keeps its copy until import-or-discard")
        assertEquals(1, results.quarantined.size)
    }

    @Test
    fun chunkedResultIsStagedVerifiedAndAcked() {
        val json = Json.parseToJsonElement(File(TestSupport.sharedDir, "fixtures/wire/valid/result.put.strength.json").readText()).jsonObject
        val payload = json.getValue("p").jsonObject
        val chunks = ChunkStager.split(payload, "c0000000-0000-4000-8000-000000000001", 300)
        assertTrue(chunks.size > 1)
        // Out of order (after the first), with one duplicate.
        val order = listOf(0) + (chunks.size - 1 downTo 1) + listOf(1)
        order.forEachIndexed { i, ix ->
            val env = Envelope(t = MessageType.RESULT_CHUNK, id = "c1000000-0000-4000-8000-%012d".format(i), ts = 1, g = 1, oid = "80000000-0000-4000-8000-000000000002", rev = 1, p = chunks[ix].toJson())
            engine.onMessage(target, EnvelopeCodec.encode(env))
        }
        val acks = transport.sent.map { (EnvelopeCodec.decode(it) as Decoded.Ok).value }
        assertEquals(order.size, acks.count { it.t == MessageType.RESULT_CHUNK_ACK })
        val final = acks.filter { it.t == MessageType.RESULT_ACK }
        assertEquals(AckStatus.OK, Ack.parse(final.first().p!!).st)
        assertNotNull(results.results["80000000-0000-4000-8000-000000000002"])
        // The duplicate after completion re-acks from the dedupe record (lost final ack).
        assertEquals(AckStatus.ALREADY_APPLIED, Ack.parse(final.last().p!!).st)
    }

    @Test
    fun corruptedChunkFailsHashAndStartsOver() {
        val stager = ChunkStager(InMemoryChunkStore(), ResultIngest(results) { 1 }) { results.dedupe(it) }
        val payload = Json.parseToJsonElement(File(TestSupport.sharedDir, "fixtures/wire/valid/result.put.capped.json").readText()).jsonObject.getValue("p").jsonObject
        val chunks = ChunkStager.split(payload, "c0000000-0000-4000-8000-000000000002", 400)
        val bad = chunks.mapIndexed { i, c -> if (i == chunks.lastIndex) c.copy(d = java.util.Base64.getEncoder().encodeToString("x".repeat(java.util.Base64.getDecoder().decode(c.d).size).toByteArray())) else c }
        var last: ChunkOutcome? = null
        bad.forEach { c -> last = stager.accept(target, Envelope(t = MessageType.RESULT_CHUNK, id = ids.newId(), ts = 1, g = 1, oid = "80000000-0000-4000-8000-000000000001", rev = 1, p = c.toJson()), now) }
        assertEquals("hash", last!!.resultAck!!.rs)
        // Missing staging state for a non-zero index asks the watch to restart at chunk 0.
        val restart = stager.accept(target, Envelope(t = MessageType.RESULT_CHUNK, id = ids.newId(), ts = 1, g = 1, oid = "80000000-0000-4000-8000-000000000001", rev = 1, p = chunks[1].toJson()), now)
        assertEquals("restart", restart.chunkAck.rs)
    }

    @Test
    fun unknownVersionMessagesAreReported() {
        val reasons = mutableListOf<String>()
        val ingest = ResultIngest(results) { 1 }
        val listener = object : SyncListener {
            override fun onProtocolError(target: WatchTarget, reason: String, detail: String) { reasons += reason }
        }
        val e = SyncEngine(transport, Outbox(), ingest, SessionIngest(InMemorySessions()) { 1 }, ChunkStager(InMemoryChunkStore(), ingest) { null }, ids, { now }, listener)
        e.onMessage(target, mapOf("v" to 2, "t" to "hello", "id" to "60000000-0000-4000-8000-000000000005", "ts" to 1, "g" to 1))
        assertEquals(listOf("version"), reasons)
    }
}
