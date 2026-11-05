package com.wodcapture.domain.protocol

import com.wodcapture.domain.model.Session
import com.wodcapture.domain.model.WorkoutResult
import kotlinx.serialization.json.JsonObject
import java.security.MessageDigest

/**
 * Dedupe record kept for every result ever accepted, including deleted ones (tombstones), so watch
 * retries never resurrect or replace phone data (REQ-SY12, REQ-DM27).
 */
data class DedupeRecord(
    val objectId: String,
    val revision: Int,
    val contentHash: String,
    val tombstoned: Boolean = false,
    /** The phone holds a newer local revision (athlete edit); watch copies never replace it. */
    val locallyEdited: Boolean = false,
)

/** Durable store for watch results; [commit] must be one transaction (raw + parsed + dedupe). */
interface ResultRepository {
    fun dedupe(resultId: String): DedupeRecord?
    fun commit(result: WorkoutResult, raw: JsonObject, record: DedupeRecord)
    /** Results from an older pairing generation awaiting explicit import-or-discard (REQ-DM27). */
    fun quarantine(raw: JsonObject, generation: Int)
}

/** Durable store for session summaries (REQ-SY16). */
interface SessionRepository {
    fun dedupe(sessionId: String): DedupeRecord?
    fun commit(session: Session, raw: JsonObject, record: DedupeRecord)
}

/** What ingestion decided: the ack to send (if any) and what was committed. */
data class IngestOutcome(
    val ack: Ack?,
    val committedResult: WorkoutResult? = null,
    val committedSession: Session? = null,
    val quarantined: Boolean = false,
)

/** SHA-256 hex of the canonical JSON of [payload]: the content identity for dedupe. */
fun contentHash(payload: JsonObject): String = sha256Hex(WireValues.canonical(payload).toByteArray(Charsets.UTF_8))

fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/**
 * Receiver for `result.put` (REQ-SY12, SY14): validate fully, commit raw result plus dedupe
 * record transactionally, then ack. Lost acks cause a safe re-ack, never a second mutation.
 */
class ResultIngest(private val repo: ResultRepository, private val currentGeneration: () -> Int) {

    fun ingest(env: Envelope): IngestOutcome = ingestPayload(env, env.p ?: JsonObject(emptyMap()), env.id)

    /** Shared by `result.put` and completed `result.chunk` transfers ([ackMid] = message acked). */
    fun ingestPayload(env: Envelope, payload: JsonObject, ackMid: String): IngestOutcome {
        if (env.g < currentGeneration()) {
            // Old pairing: keep for explicit import-or-discard, and do not ack so the watch keeps
            // its copy (never auto-delete, REQ-DM27).
            repo.quarantine(payload, env.g)
            return IngestOutcome(ack = null, quarantined = true)
        }
        val wire = try {
            WireResult.parse(payload)
        } catch (e: WireException) {
            return IngestOutcome(Ack(ackMid, AckStatus.ERROR, e.reason))
        }
        if (wire.id != env.oid || wire.rr != env.rev) return IngestOutcome(Ack(ackMid, AckStatus.ERROR, "invalid"))
        val hash = contentHash(payload)
        val existing = repo.dedupe(wire.id)
        return when {
            existing == null -> {
                val result = wire.toDomain()
                repo.commit(result, payload, DedupeRecord(wire.id, wire.rr, hash))
                IngestOutcome(Ack(ackMid, AckStatus.OK), committedResult = result)
            }
            // Deleted or edited on the phone: acknowledge so the watch can clean up, change nothing.
            existing.tombstoned || existing.locallyEdited -> IngestOutcome(Ack(ackMid, AckStatus.ALREADY_APPLIED))
            existing.revision == wire.rr && existing.contentHash == hash -> IngestOutcome(Ack(ackMid, AckStatus.ALREADY_APPLIED))
            existing.revision > wire.rr -> IngestOutcome(Ack(ackMid, AckStatus.STALE, "stale"))
            else -> IngestOutcome(Ack(ackMid, AckStatus.CONFLICT, "conflict"))
        }
    }
}

/** Receiver for `session.put` (REQ-SY16); same dedupe/revision rules as results. */
class SessionIngest(private val repo: SessionRepository, private val currentGeneration: () -> Int) {

    fun ingest(env: Envelope): IngestOutcome {
        if (env.g < currentGeneration()) return IngestOutcome(ack = null, quarantined = true)
        val payload = env.p ?: return IngestOutcome(Ack(env.id, AckStatus.ERROR, "invalid"))
        val wire = try {
            WireSession.parse(payload)
        } catch (e: WireException) {
            return IngestOutcome(Ack(env.id, AckStatus.ERROR, e.reason))
        }
        if (wire.id != env.oid || wire.rev != env.rev) return IngestOutcome(Ack(env.id, AckStatus.ERROR, "invalid"))
        val hash = contentHash(payload)
        val existing = repo.dedupe(wire.id)
        return when {
            existing == null || existing.revision < wire.rev && !existing.tombstoned -> {
                val s = wire.toDomain()
                repo.commit(s, payload, DedupeRecord(wire.id, wire.rev, hash))
                IngestOutcome(Ack(env.id, AckStatus.OK), committedSession = s)
            }
            existing.tombstoned || existing.locallyEdited -> IngestOutcome(Ack(env.id, AckStatus.ALREADY_APPLIED))
            existing.revision == wire.rev && existing.contentHash == hash -> IngestOutcome(Ack(env.id, AckStatus.ALREADY_APPLIED))
            existing.revision > wire.rev -> IngestOutcome(Ack(env.id, AckStatus.STALE, "stale"))
            else -> IngestOutcome(Ack(env.id, AckStatus.CONFLICT, "conflict"))
        }
    }

    companion object {
        /**
         * Result IDs a session references that have not arrived yet; the session is complete only
         * when this is empty (REQ-SY16).
         */
        fun missingResults(session: Session, hasResult: (String) -> Boolean): List<String> =
            session.entries.mapNotNull { it.resultId }.filterNot(hasResult)
    }
}

/** Phone-side plan inventory record used to answer `hello` (REQ-SY05, SY11). */
data class PlanRecord(val planId: String, val revision: Int, val deleted: Boolean)

/** Decides which plan messages to resend after a `hello` (protocol §3.2). */
object HelloDiff {
    /**
     * Plans the watch lacks or holds at an older revision, and tombstones it has not applied.
     * Never resends an older revision than the watch reports.
     */
    fun toResend(phone: List<PlanRecord>, hello: Hello): List<PlanRecord> {
        val inv = hello.inv.associate { it.id to it.rev }
        val del = hello.del.associate { it.id to it.rev }
        return phone.filter { rec ->
            val watchRev = inv[rec.planId]
            val watchDel = del[rec.planId]
            when {
                watchDel != null && watchDel >= rec.revision -> false
                rec.deleted -> watchRev != null || (watchDel ?: 0) < rec.revision
                watchRev == null -> true
                else -> watchRev < rec.revision
            }
        }
    }
}
