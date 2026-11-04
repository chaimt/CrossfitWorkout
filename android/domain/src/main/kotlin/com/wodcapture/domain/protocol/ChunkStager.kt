package com.wodcapture.domain.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.util.Base64

/** A staged chunked transfer (REQ-SY15). Persisted so staging survives restarts. */
data class Transfer(
    val targetKey: String,
    val xid: String,
    val resultId: String,
    val revision: Int,
    val n: Int,
    val len: Int,
    val sha: String,
    /** index → base64 data. */
    val chunks: Map<Int, String>,
    val createdAtMs: Long,
)

/** Durable chunk staging. */
interface ChunkStore {
    fun activeFor(targetKey: String): Transfer?
    fun put(t: Transfer)
    fun remove(targetKey: String, xid: String)
}

/** Simple in-memory [ChunkStore]; the app persists transfers in Room. */
class InMemoryChunkStore : ChunkStore {
    private val map = mutableMapOf<String, Transfer>()
    override fun activeFor(targetKey: String): Transfer? = map[targetKey]
    override fun put(t: Transfer) { map[t.targetKey] = t }
    override fun remove(targetKey: String, xid: String) { if (map[targetKey]?.xid == xid) map.remove(targetKey) }
}

/** Acks produced for one chunk: the chunk ack, plus the final `result.ack` when complete. */
data class ChunkOutcome(val chunkAck: Ack, val resultAck: Ack? = null, val ingest: IngestOutcome? = null)

/**
 * `result.chunk` receiver (REQ-SY15): stage durably, ack each chunk, verify length + SHA-256 +
 * schema after the last, ingest transactionally, then `result.ack`. One active transfer per
 * target; out-of-order chunks accepted; duplicates re-acked; differing content is a conflict.
 */
class ChunkStager(
    private val store: ChunkStore,
    private val results: ResultIngest,
    private val resultDedupe: (String) -> DedupeRecord?,
) {
    fun accept(target: WatchTarget, env: Envelope, nowMs: Long): ChunkOutcome {
        val chunk = try {
            Chunk.parse(env.p ?: throw WireException("invalid", "chunk payload"))
        } catch (e: WireException) {
            return ChunkOutcome(Ack(env.id, AckStatus.ERROR, e.reason))
        }
        fun ack(st: String, rs: String? = null) = Ack(env.id, st, rs, chunk.ix)
        val oid = env.oid!!
        val rev = env.rev!!
        // Final ack lost: a committed result is re-acked from its dedupe record.
        resultDedupe(oid)?.let { rec ->
            if (rec.revision >= rev) return ChunkOutcome(ack(AckStatus.ALREADY_APPLIED), Ack(env.id, AckStatus.ALREADY_APPLIED))
        }
        var t = store.activeFor(target.key)?.takeIf { nowMs - it.createdAtMs <= EXPIRY_MS }
        if (t != null && t.xid != chunk.xid) {
            if (chunk.ix != 0) return ChunkOutcome(ack(AckStatus.ERROR, "busy"))
            store.remove(target.key, t.xid) // a new transfer restarts from chunk zero
            t = null
        }
        if (t == null) {
            if (chunk.ix != 0) {
                // Missing staging state (expired/never seen): ask the watch to restart at chunk 0.
                store.activeFor(target.key)?.let { store.remove(target.key, it.xid) }
                return ChunkOutcome(ack(AckStatus.ERROR, "restart"))
            }
            t = Transfer(target.key, chunk.xid, oid, rev, chunk.n, chunk.len, chunk.sha, emptyMap(), nowMs)
        }
        if (t.n != chunk.n || t.len != chunk.len || t.sha != chunk.sha || t.resultId != oid || t.revision != rev) {
            return ChunkOutcome(ack(AckStatus.CONFLICT, "conflict"))
        }
        val prior = t.chunks[chunk.ix]
        if (prior != null && prior != chunk.d) return ChunkOutcome(ack(AckStatus.CONFLICT, "conflict"))
        t = t.copy(chunks = t.chunks + (chunk.ix to chunk.d))
        store.put(t)
        if (t.chunks.size < t.n) return ChunkOutcome(ack(AckStatus.OK))
        return complete(target, env, t, ack(AckStatus.OK))
    }

    private fun complete(target: WatchTarget, env: Envelope, t: Transfer, chunkAck: Ack): ChunkOutcome {
        fun fail(reason: String): ChunkOutcome {
            store.remove(target.key, t.xid)
            return ChunkOutcome(chunkAck, Ack(env.id, AckStatus.ERROR, reason))
        }
        val bytes = try {
            (0 until t.n).fold(ByteArray(0)) { acc, i -> acc + Base64.getDecoder().decode(t.chunks.getValue(i)) }
        } catch (e: IllegalArgumentException) {
            return fail("invalid")
        }
        if (bytes.size != t.len || bytes.size > MAX_DECODED) return fail("size")
        if (sha256Hex(bytes) != t.sha) return fail("hash")
        val payload = try {
            Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject ?: return fail("invalid")
        } catch (e: Exception) {
            return fail("invalid")
        }
        val outcome = results.ingestPayload(env, payload, env.id)
        store.remove(target.key, t.xid)
        return ChunkOutcome(chunkAck, outcome.ack, outcome)
    }

    companion object {
        const val EXPIRY_MS = 7L * 24 * 3600 * 1000
        const val MAX_DECODED = 65_536

        /** Splits a Result payload into chunk payloads (used by tests and the simulator harness). */
        fun split(payload: JsonObject, xid: String, chunkBytes: Int): List<Chunk> {
            val bytes = WireValues.canonical(payload).toByteArray(Charsets.UTF_8)
            val sha = sha256Hex(bytes)
            val parts = bytes.toList().chunked(chunkBytes)
            return parts.mapIndexed { i, p ->
                Chunk(xid, i, parts.size, bytes.size, sha, Base64.getEncoder().encodeToString(p.toByteArray()))
            }
        }
    }
}
