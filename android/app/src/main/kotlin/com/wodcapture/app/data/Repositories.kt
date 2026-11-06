package com.wodcapture.app.data

import androidx.room.withTransaction
import com.wodcapture.domain.DomainJson
import com.wodcapture.domain.IdSource
import com.wodcapture.domain.model.Session
import com.wodcapture.domain.model.Workout
import com.wodcapture.domain.model.WorkoutResult
import com.wodcapture.domain.protocol.ChunkStore
import com.wodcapture.domain.protocol.DedupeRecord
import com.wodcapture.domain.protocol.DeliveryState
import com.wodcapture.domain.protocol.OutboxEntry
import com.wodcapture.domain.protocol.ResultRepository
import com.wodcapture.domain.protocol.SessionRepository
import com.wodcapture.domain.protocol.Transfer
import com.wodcapture.domain.protocol.WatchTarget
import com.wodcapture.domain.review.ReviewActions
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

/** Library/draft persistence with immutable revision history (REQ-DM02, AL01, AX16). */
class WorkoutRepository(private val db: WodDatabase, private val ids: IdSource = IdSource.RANDOM) {
    private val dao = db.dao()

    fun library() = dao.library()
    fun observe(id: String) = dao.observeWorkout(id)
    fun get(id: String): WorkoutEntity? = dao.workout(id)

    /** A pending draft from captured images/text; parsing happens later (REQ-AX16). */
    fun createDraft(imagePaths: List<String>, rawText: String?, nowSec: Long): WorkoutEntity {
        val w = Workout(id = ids.newId(), title = "Draft", createdAt = nowSec, updatedAt = nowSec)
        val e = WorkoutEntity(
            id = w.id, revision = 1, title = w.title, date = null, workoutJson = DomainJson.encodeWorkout(w),
            imagePaths = json.encodeToString(ListSerializer(String.serializer()), imagePaths), rawText = rawText,
            status = "draft", createdAt = nowSec, updatedAt = nowSec,
        )
        dao.upsertWorkout(e)
        return e
    }

    /**
     * Stores a workout built in the guided manual form (REQ-AC09) as a draft: no images, no source
     * text, no parse result. It reaches the watch only after Review (REQ-AE05).
     */
    fun createManual(w: Workout, nowSec: Long): WorkoutEntity {
        val stored = w.copy(revision = 1, createdAt = nowSec, updatedAt = nowSec)
        val e = WorkoutEntity(
            id = stored.id, revision = 1, title = stored.title, date = stored.date, workoutJson = DomainJson.encodeWorkout(stored),
            imagePaths = json.encodeToString(ListSerializer(String.serializer()), emptyList()), rawText = null,
            status = "draft", createdAt = nowSec, updatedAt = nowSec,
        )
        dao.upsertWorkout(e)
        return e
    }

    /** Saves an edit as the next revision; prior revisions stay immutable (REQ-DM15). */
    fun saveRevision(entity: WorkoutEntity, edited: Workout, nowSec: Long, status: String = "saved"): WorkoutEntity {
        val prev = DomainJson.decodeWorkout(entity.workoutJson)
        val next = ReviewActions.newRevision(prev, edited, nowSec)
        val e = entity.copy(revision = next.revision, title = next.title, date = next.date, workoutJson = DomainJson.encodeWorkout(next), status = status, updatedAt = nowSec)
        runBlocking { db.withTransaction { dao.insertRevision(WorkoutRevisionEntity(entity.id, entity.revision, entity.workoutJson, nowSec)); dao.upsertWorkout(e) } }
        return e
    }

    fun setStatus(entity: WorkoutEntity, status: String) = dao.upsertWorkout(entity.copy(status = status))

    /** Delete = archive the visible entry (REQ-DM27). */
    fun archive(id: String) { dao.workout(id)?.let { dao.upsertWorkout(it.copy(archived = true)) } }

    fun duplicate(id: String, nowSec: Long): WorkoutEntity? {
        val src = dao.workout(id) ?: return null
        val copy = ReviewActions.duplicate(DomainJson.decodeWorkout(src.workoutJson), ids, nowSec)
        val e = src.copy(id = copy.id, revision = 1, title = copy.title, workoutJson = DomainJson.encodeWorkout(copy), parseJson = null, alternativeJson = null, status = "saved", archived = false, createdAt = nowSec, updatedAt = nowSec)
        dao.upsertWorkout(e)
        return e
    }

    fun saveManualResult(r: WorkoutResult) = dao.upsertResult(
        ResultEntity(r.id, r.workoutId, r.partId, r.sessionId, DomainJson.storage.encodeToString(WorkoutResult.serializer(), r), null, r.startedAt),
    )

    /** Phone edit of a watch result: new local revision, dedupe marks it authoritative (REQ-SY12). */
    fun editResult(r: WorkoutResult) {
        val edited = r.copy(resultRevision = r.resultRevision + 1)
        runBlocking {
            db.withTransaction {
                saveManualResult(edited)
                dao.dedupe("result", r.id)?.let { dao.upsertDedupe(it.copy(locallyEdited = true)) }
            }
        }
    }

    /** Deleting a result keeps a tombstone so watch retries cannot resurrect it (REQ-DM27). */
    fun deleteResult(id: String) {
        runBlocking {
            db.withTransaction {
                dao.result(id)?.let { dao.upsertResult(it.copy(deleted = true)) }
                val d = dao.dedupe("result", id) ?: DedupeEntity("result", id, 1, "", tombstoned = true, locallyEdited = false)
                dao.upsertDedupe(d.copy(tombstoned = true))
            }
        }
    }
}

/** Room [ResultRepository]: raw + parsed + dedupe committed in one transaction (REQ-SY12). */
class RoomResultRepository(private val db: WodDatabase) : ResultRepository {
    private val dao = db.dao()
    override fun dedupe(resultId: String): DedupeRecord? = dao.dedupe("result", resultId)?.toRecord()
    override fun commit(result: WorkoutResult, raw: JsonObject, record: DedupeRecord) = runBlocking {
        db.withTransaction {
            dao.upsertResult(ResultEntity(result.id, result.workoutId, result.partId, result.sessionId, DomainJson.storage.encodeToString(WorkoutResult.serializer(), result), raw.toString(), result.startedAt))
            dao.upsertDedupe(record.toEntity("result"))
        }
    }
    override fun quarantine(raw: JsonObject, generation: Int) = dao.insertQuarantine(QuarantineEntity(generation = generation, rawJson = raw.toString()))
}

class RoomSessionRepository(private val db: WodDatabase) : SessionRepository {
    private val dao = db.dao()
    override fun dedupe(sessionId: String): DedupeRecord? = dao.dedupe("session", sessionId)?.toRecord()
    override fun commit(session: Session, raw: JsonObject, record: DedupeRecord) = runBlocking {
        db.withTransaction {
            dao.upsertSession(SessionEntity(session.id, session.planId, DomainJson.storage.encodeToString(Session.serializer(), session), raw.toString()))
            dao.upsertDedupe(record.toEntity("session"))
        }
    }
}

private fun DedupeEntity.toRecord() = DedupeRecord(objectId, revision, contentHash, tombstoned, locallyEdited)
private fun DedupeRecord.toEntity(kind: String) = DedupeEntity(kind, objectId, revision, contentHash, tombstoned, locallyEdited)

@Serializable
private data class TransferRecord(val targetKey: String, val xid: String, val resultId: String, val revision: Int, val n: Int, val len: Int, val sha: String, val chunks: Map<Int, String>, val createdAtMs: Long)

/** Durable chunk staging (REQ-SY15: staging survives restart). */
class RoomChunkStore(db: WodDatabase) : ChunkStore {
    private val dao = db.dao()
    override fun activeFor(targetKey: String): Transfer? = dao.chunk(targetKey)?.let {
        val r = json.decodeFromString(TransferRecord.serializer(), it.transferJson)
        Transfer(r.targetKey, r.xid, r.resultId, r.revision, r.n, r.len, r.sha, r.chunks, r.createdAtMs)
    }
    override fun put(t: Transfer) = dao.upsertChunk(
        ChunkTransferEntity(t.targetKey, t.xid, json.encodeToString(TransferRecord.serializer(), TransferRecord(t.targetKey, t.xid, t.resultId, t.revision, t.n, t.len, t.sha, t.chunks, t.createdAtMs))),
    )
    override fun remove(targetKey: String, xid: String) = dao.deleteChunk(targetKey, xid)
}

@Serializable
private data class OutboxRecord(
    val messageId: String, val deviceId: String, val appUuid: String, val generation: Int, val type: String,
    val objectId: String, val revision: Int, val body: JsonObject, val state: String, val retries: Int,
    val sentAtMs: Long? = null, val reason: String? = null, val enqueuedAtMs: Long,
)

/** Persists the outbox snapshot after every mutation (REQ-SY14). */
class OutboxStore(private val dao: WodDao) {
    constructor(db: WodDatabase) : this(db.dao())

    private var persisted: Map<String, OutboxEntry>? = null

    fun load(): List<OutboxEntry> = dao.outbox().map { e ->
        val r = json.decodeFromString(OutboxRecord.serializer(), e.entryJson)
        OutboxEntry(r.messageId, WatchTarget(r.deviceId, r.appUuid, r.generation), r.type, r.objectId, r.revision, r.body, DeliveryState.valueOf(r.state), r.retries, r.sentAtMs, r.reason, r.enqueuedAtMs)
    }.also { entries -> persisted = entries.associateBy(::key) }

    /** Called only on the sync executor; advance the cache after the transaction commits. */
    fun save(entries: List<OutboxEntry>) {
        if (persisted == null) load()
        val previous = requireNotNull(persisted)
        val current = entries.associateBy(::key)
        val changed = entries.filter { previous[key(it)] != it }.map { e ->
            val rec = OutboxRecord(e.messageId, e.target.deviceId, e.target.appUuid, e.target.generation, e.type, e.objectId, e.revision, e.body, e.state.name, e.retries, e.sentAtMs, e.reason, e.enqueuedAtMs)
            OutboxEntity(key(e), e.target.key, json.encodeToString(OutboxRecord.serializer(), rec))
        }
        val removed = (previous.keys - current.keys).toList()
        if (changed.isNotEmpty() || removed.isNotEmpty()) dao.applyOutboxChanges(changed, removed)
        persisted = current
    }

    private fun key(e: OutboxEntry) = "${e.target.key}|${e.messageId}"
}
