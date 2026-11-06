package com.wodcapture.domain.protocol

import com.wodcapture.domain.IdSource
import com.wodcapture.domain.plan.WatchPlan
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.Executor

/**
 * Phone side of the watch transport, implemented by the Connect IQ adapter in `:app` and by fakes
 * in tests. Implementations must send off the main thread and report every SDK status.
 */
interface WatchTransport {
    fun send(target: WatchTarget, message: Map<String, Any>, onStatus: (SendStatus) -> Unit)
}

/** Callbacks for UI/persistence; all default to no-ops. */
interface SyncListener {
    fun onOutboxChanged(outbox: List<OutboxEntry>) {}
    fun onResultCommitted(target: WatchTarget, outcome: IngestOutcome) {}
    fun onSessionCommitted(target: WatchTarget, outcome: IngestOutcome) {}
    fun onHello(target: WatchTarget, hello: Hello) {}
    fun onProtocolError(target: WatchTarget, reason: String, detail: String) {}
}

/**
 * Orchestrates the outbox, receivers and transport for each target (REQ-SY04–SY16). Acks bypass
 * the data queue; one data message is outstanding per target. Not thread-safe: the app drives it
 * from a single serial executor.
 */
class SyncEngine(
    private val transport: WatchTransport,
    val outbox: Outbox,
    private val results: ResultIngest,
    private val sessions: SessionIngest,
    private val chunks: ChunkStager,
    private val ids: IdSource,
    private val clockMs: () -> Long,
    private val listener: SyncListener = object : SyncListener {},
    private val callbackExecutor: Executor = Executor { it.run() },
) {
    private fun nowSec() = clockMs() / 1000

    /** Queues a compiled plan (REQ-AE05 "Send to watch") and starts draining. */
    fun sendPlan(target: WatchTarget, plan: WatchPlan): OutboxEntry {
        val env = Envelope(t = MessageType.PLAN_PUT, id = ids.newId(), ts = nowSec(), g = target.generation, oid = plan.id, rev = plan.rev, p = WatchPlanCodec.toJson(plan))
        return enqueue(target, env)
    }

    /** Queues a revisioned tombstone; delayed puts cannot resurrect it (REQ-SY11). */
    fun deletePlan(target: WatchTarget, planId: String, revision: Int): OutboxEntry =
        enqueue(target, Envelope(t = MessageType.PLAN_DEL, id = ids.newId(), ts = nowSec(), g = target.generation, oid = planId, rev = revision))

    fun sendProfile(target: WatchTarget, profileId: String, revision: Int, payload: JsonObject): OutboxEntry =
        enqueue(target, Envelope(t = MessageType.PROFILE_PUT, id = ids.newId(), ts = nowSec(), g = target.generation, oid = profileId, rev = revision, p = payload))

    private fun enqueue(target: WatchTarget, env: Envelope): OutboxEntry {
        val e = outbox.enqueue(target, env, clockMs())
        changed()
        pump(target)
        return e
    }

    /** Explicit Sync / app open (REQ-AW04): reset waiting entries and ask the watch to drain results. */
    fun requestSync(target: WatchTarget) {
        outbox.restart(target, clockMs(), explicit = true)
        sendControl(target, Envelope(t = MessageType.SYNC_REQ, id = ids.newId(), ts = nowSec(), g = target.generation))
        changed()
        pump(target)
    }

    /** Device connected lifecycle event (rate-limited automatic restart). */
    fun onDeviceConnected(target: WatchTarget) {
        if (outbox.restart(target, clockMs(), explicit = false)) changed()
        pump(target)
    }

    /** Called by a timer; performs due retries. */
    fun tick(target: WatchTarget) = pump(target)

    /** Sends the next due data message for [target], if any. */
    fun pump(target: WatchTarget) {
        val before = outbox.snapshot()
        val next = outbox.next(target, clockMs())
        if (next == null) {
            // Exhausting a retry budget changes durable state without sending a message.
            if (outbox.snapshot() != before) changed()
            return
        }
        changed()
        @Suppress("UNCHECKED_CAST")
        val message = WireValues.toWire(next.body) as Map<String, Any>
        transport.send(target, message) { status ->
            callbackExecutor.execute {
                outbox.onSendStatus(next.messageId, status, clockMs())
                changed()
            }
        }
    }

    /** Entry point for every message from the watch (`registerForAppEvents`). */
    fun onMessage(target: WatchTarget, raw: Map<*, *>) {
        val env = when (val d = EnvelopeCodec.decode(raw)) {
            is Decoded.Ok -> d.value
            is Decoded.Err -> {
                listener.onProtocolError(target, d.reason, d.detail)
                return
            }
        }
        when (env.t) {
            in MessageType.ACKS -> onAckMessage(target, env)
            MessageType.HELLO -> onHello(target, env)
            MessageType.RESULT_PUT -> {
                val outcome = results.ingest(env)
                outcome.ack?.let { sendAck(target, MessageType.RESULT_ACK, env, it) }
                listener.onResultCommitted(target, outcome)
            }
            MessageType.RESULT_CHUNK -> {
                val out = chunks.accept(target, env, clockMs())
                sendAck(target, MessageType.RESULT_CHUNK_ACK, env, out.chunkAck)
                out.resultAck?.let { sendAck(target, MessageType.RESULT_ACK, env, it) }
                out.ingest?.let { listener.onResultCommitted(target, it) }
            }
            MessageType.SESSION_PUT -> {
                val outcome = sessions.ingest(env)
                outcome.ack?.let { sendAck(target, MessageType.SESSION_ACK, env, it) }
                listener.onSessionCommitted(target, outcome)
            }
            MessageType.SYNC_ACK -> Unit
            else -> listener.onProtocolError(target, "invalid", "Unexpected ${env.t} from watch")
        }
        pump(target)
    }

    private fun onAckMessage(target: WatchTarget, env: Envelope) {
        val ack = try {
            Ack.parse(env.p!!)
        } catch (e: WireException) {
            listener.onProtocolError(target, e.reason, e.message ?: "bad ack")
            return
        }
        if (outbox.onAck(target, ack) != null) changed()
    }

    /** `hello` resends missing/newer plans, never older, and never marks anything delivered. */
    private fun onHello(target: WatchTarget, env: Envelope) {
        val hello = try {
            Hello.parse(env.p!!)
        } catch (e: WireException) {
            listener.onProtocolError(target, e.reason, e.message ?: "bad hello")
            return
        }
        listener.onHello(target, hello)
        val records = outbox.latestPerObject(target)
            .filter { it.type == MessageType.PLAN_PUT || it.type == MessageType.PLAN_DEL }
            .filter { it.state != DeliveryState.FAILED && it.state != DeliveryState.STALE }
            .map { PlanRecord(it.objectId, it.revision, it.type == MessageType.PLAN_DEL) }
        HelloDiff.toResend(records, hello).forEach { outbox.requeue(target, it.planId, it.revision) }
        outbox.restart(target, clockMs(), explicit = false)
        changed()
    }

    private fun sendAck(target: WatchTarget, type: String, about: Envelope, ack: Ack) {
        sendControl(target, Envelope(t = type, id = ids.newId(), ts = nowSec(), g = target.generation, oid = about.oid, rev = about.rev, p = ack.toJson()))
    }

    /** Acks and control messages bypass the data queue (protocol §7). */
    private fun sendControl(target: WatchTarget, env: Envelope) {
        transport.send(target, EnvelopeCodec.encode(env)) { status ->
            callbackExecutor.execute {
                if (status != SendStatus.SUCCESS) listener.onProtocolError(target, "send", "${env.t} send status $status")
            }
        }
    }

    private fun changed() = listener.onOutboxChanged(outbox.snapshot())
}
