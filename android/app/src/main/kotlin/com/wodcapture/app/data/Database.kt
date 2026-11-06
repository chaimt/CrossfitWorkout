package com.wodcapture.app.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.Transaction
import com.wodcapture.domain.DomainJson
import com.wodcapture.domain.parser.JobCompletion
import com.wodcapture.domain.parser.JobPreconditions
import com.wodcapture.domain.parser.JobState
import com.wodcapture.domain.parser.ParseJob
import com.wodcapture.domain.parser.ParseJobs
import com.wodcapture.domain.parser.ParseOutcome
import kotlinx.coroutines.flow.Flow

/**
 * A library entry: draft or saved workout (REQ-AL01, REQ-AX16). Domain objects are stored as
 * JSON blobs (canonical model, REQ-DM02); revisions live in [WorkoutRevisionEntity].
 */
@Entity(tableName = "workouts")
data class WorkoutEntity(
    @PrimaryKey val id: String,
    val revision: Int,
    val title: String,
    val date: String?,
    val workoutJson: String,
    /** Diagnostics/assumptions/unparsed of the parse this draft came from (REQ-DM17). */
    val parseJson: String? = null,
    /** Late parse output kept as a reviewable alternative (REQ-AX18). */
    val alternativeJson: String? = null,
    /** App-private image paths, ordered (REQ-AC04, AC07). */
    val imagePaths: String = "[]",
    val rawText: String? = null,
    /** draft · parsing · ready · saved. */
    val status: String,
    /** Deleting archives the visible entry; run snapshots survive (REQ-DM27). */
    val archived: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
)

/** Immutable revision history (REQ-DM02, §4.6). */
@Entity(tableName = "workout_revisions", primaryKeys = ["workoutId", "revision"])
data class WorkoutRevisionEntity(val workoutId: String, val revision: Int, val workoutJson: String, val savedAt: Long)

/** Parse job lifecycle (REQ-AX18). */
@Entity(tableName = "parse_jobs")
data class ParseJobEntity(
    @PrimaryKey val jobId: String,
    val draftId: String,
    val draftRevision: Int,
    val state: String,
    val attempts: Int,
    val error: String? = null,
    val createdAt: Long,
)

/** Results with raw wire payload (REQ-SY12); [deleted] rows are tombstones (REQ-DM27). */
@Entity(tableName = "results")
data class ResultEntity(
    @PrimaryKey val id: String,
    val workoutId: String,
    val partId: String,
    val sessionId: String,
    val resultJson: String,
    val rawJson: String?,
    val startedAt: Long,
    val deleted: Boolean = false,
)

/** Dedupe record for results and sessions (REQ-SY12, SY14). */
@Entity(tableName = "dedupe", primaryKeys = ["kind", "objectId"])
data class DedupeEntity(
    val kind: String,
    val objectId: String,
    val revision: Int,
    val contentHash: String,
    val tombstoned: Boolean,
    val locallyEdited: Boolean,
)

@Entity(tableName = "sessions")
data class SessionEntity(@PrimaryKey val id: String, val planId: String, val sessionJson: String, val rawJson: String)

/** Results from an older pairing awaiting explicit import-or-discard (REQ-DM27). */
@Entity(tableName = "quarantine")
data class QuarantineEntity(@PrimaryKey(autoGenerate = true) val rowId: Long = 0, val generation: Int, val rawJson: String)

/** Durable outbox (REQ-SY14: queue state survives process death). */
@Entity(tableName = "outbox")
data class OutboxEntity(@PrimaryKey val key: String, val targetKey: String, val entryJson: String)

/** Plan lineage per workout + target (REQ-DM15). */
@Entity(tableName = "plans", primaryKeys = ["workoutId", "targetKey"])
data class PlanEntity(
    val workoutId: String,
    val targetKey: String,
    val planId: String,
    val revision: Int,
    val workoutRevision: Int,
    val planJson: String,
)

/** Staged result chunks (REQ-SY15). */
@Entity(tableName = "chunk_transfers")
data class ChunkTransferEntity(@PrimaryKey val targetKey: String, val xid: String, val transferJson: String)

@Dao
interface WodDao {
    @Query("SELECT * FROM workouts WHERE archived = 0 ORDER BY updatedAt DESC")
    fun library(): Flow<List<WorkoutEntity>>

    @Query("SELECT * FROM workouts WHERE id = :id")
    fun workout(id: String): WorkoutEntity?

    @Query("SELECT * FROM workouts WHERE id = :id")
    fun observeWorkout(id: String): Flow<WorkoutEntity?>

    @Upsert fun upsertWorkout(w: WorkoutEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) fun insertRevision(r: WorkoutRevisionEntity)

    @Upsert fun upsertJob(j: ParseJobEntity)
    @Query("SELECT * FROM parse_jobs WHERE jobId = :id") fun job(id: String): ParseJobEntity?
    @Query("SELECT * FROM parse_jobs WHERE draftId = :draftId ORDER BY createdAt DESC LIMIT 1")
    fun observeLatestJob(draftId: String): Flow<ParseJobEntity?>
    @Query("UPDATE parse_jobs SET state = 'CANCELLED' WHERE state IN ('QUEUED','RUNNING')") fun cancelAllJobs()

    /** Re-read the job under the write lock so start cannot undo a concurrent cancel. */
    @Transaction
    fun startParse(id: String, preconditions: JobPreconditions): ParseJobEntity? {
        val row = job(id) ?: return null
        if (row.state != JobState.QUEUED.name) return null
        val started = ParseJobs.start(ParseJob(row.jobId, row.draftId, row.draftRevision, attempts = row.attempts), preconditions)
        val updated = row.copy(state = started.state.name, error = started.error?.toString())
        upsertJob(updated)
        return updated
    }

    /** Cancellation and draft status change are one operation; terminal jobs stay terminal. */
    @Transaction
    fun cancelParse(id: String) {
        val row = job(id) ?: return
        if (row.state != JobState.QUEUED.name && row.state != JobState.RUNNING.name) return
        upsertJob(row.copy(state = JobState.CANCELLED.name))
        workout(row.draftId)?.let { upsertWorkout(it.copy(status = "draft")) }
    }

    /** Commit against current rows so cancellation, deletion, and edits cannot be overwritten. */
    @Transaction
    fun completeParse(id: String, outcome: ParseOutcome, nowSec: Long, stopped: Boolean): ParseJob? {
        val row = job(id) ?: return null
        val current = workout(row.draftId)
        val job = ParseJob(row.jobId, row.draftId, row.draftRevision, JobState.valueOf(row.state), row.attempts)
        if (job.state != JobState.RUNNING) return job
        if (stopped || current == null || current.archived) {
            val cancelled = ParseJobs.cancel(job)
            upsertJob(row.copy(state = cancelled.state.name))
            return cancelled
        }
        val (done, completion) = ParseJobs.complete(job, outcome, current.revision)
        when (completion) {
            is JobCompletion.Apply -> {
                val result = completion.result
                val w = result.workout.copy(id = current.id, revision = current.revision + 1, createdAt = current.createdAt, updatedAt = nowSec)
                upsertWorkout(current.copy(revision = w.revision, title = w.title, date = w.date,
                    workoutJson = DomainJson.encodeWorkout(w), parseJson = DomainJson.encodeParseResult(result),
                    status = "ready", updatedAt = nowSec))
            }
            is JobCompletion.Alternative -> upsertWorkout(current.copy(alternativeJson = DomainJson.encodeParseResult(completion.result)))
            JobCompletion.Ignore -> if (done.state == JobState.FAILED) upsertWorkout(current.copy(status = "draft"))
        }
        upsertJob(row.copy(state = done.state.name, attempts = done.attempts, error = done.error?.toString()))
        return done
    }

    @Upsert fun upsertResult(r: ResultEntity)
    @Query("SELECT * FROM results WHERE deleted = 0 ORDER BY startedAt DESC") fun observeResults(): Flow<List<ResultEntity>>
    @Query("SELECT * FROM results WHERE id = :id") fun result(id: String): ResultEntity?

    @Upsert fun upsertDedupe(d: DedupeEntity)
    @Query("SELECT * FROM dedupe WHERE kind = :kind AND objectId = :id") fun dedupe(kind: String, id: String): DedupeEntity?

    @Upsert fun upsertSession(s: SessionEntity)
    @Insert fun insertQuarantine(q: QuarantineEntity)

    @Upsert fun upsertOutbox(o: OutboxEntity)
    @Query("SELECT * FROM outbox") fun outbox(): List<OutboxEntity>
    @Query("SELECT * FROM outbox") fun observeOutbox(): Flow<List<OutboxEntity>>
    @Query("DELETE FROM outbox") fun clearOutbox()
    @Query("DELETE FROM outbox WHERE `key` = :key") fun deleteOutbox(key: String)

    /** Apply a snapshot delta atomically without rewriting unchanged message bodies. */
    @Transaction
    fun applyOutboxChanges(changed: List<OutboxEntity>, removed: List<String>) {
        changed.forEach(::upsertOutbox)
        removed.forEach(::deleteOutbox)
    }

    @Upsert fun upsertPlan(p: PlanEntity)
    @Query("SELECT * FROM plans WHERE workoutId = :workoutId AND targetKey = :targetKey") fun plan(workoutId: String, targetKey: String): PlanEntity?

    @Upsert fun upsertChunk(c: ChunkTransferEntity)
    @Query("SELECT * FROM chunk_transfers WHERE targetKey = :targetKey") fun chunk(targetKey: String): ChunkTransferEntity?
    @Query("DELETE FROM chunk_transfers WHERE targetKey = :targetKey AND xid = :xid") fun deleteChunk(targetKey: String, xid: String)

    @Query("DELETE FROM workouts") fun wipeWorkouts()
    @Query("DELETE FROM workout_revisions") fun wipeRevisions()
    @Query("DELETE FROM parse_jobs") fun wipeJobs()
    @Query("DELETE FROM results") fun wipeResults()
    @Query("DELETE FROM sessions") fun wipeSessions()
    @Query("DELETE FROM plans") fun wipePlans()
    @Query("DELETE FROM chunk_transfers") fun wipeChunks()
    @Query("DELETE FROM quarantine") fun wipeQuarantine()
}

@Database(
    entities = [
        WorkoutEntity::class, WorkoutRevisionEntity::class, ParseJobEntity::class, ResultEntity::class,
        DedupeEntity::class, SessionEntity::class, QuarantineEntity::class, OutboxEntity::class,
        PlanEntity::class, ChunkTransferEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class WodDatabase : RoomDatabase() {
    abstract fun dao(): WodDao
}
