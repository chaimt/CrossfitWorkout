package com.wodcapture.domain.protocol

import com.wodcapture.domain.model.Session
import com.wodcapture.domain.model.WorkoutResult
import kotlinx.serialization.json.JsonObject

/** In-memory [ResultRepository] for tests; mirrors the Room transaction semantics. */
class InMemoryResults : ResultRepository {
    val results = mutableMapOf<String, WorkoutResult>()
    val records = mutableMapOf<String, DedupeRecord>()
    val quarantined = mutableListOf<Pair<JsonObject, Int>>()
    override fun dedupe(resultId: String) = records[resultId]
    override fun commit(result: WorkoutResult, raw: JsonObject, record: DedupeRecord) {
        results[result.id] = result
        records[result.id] = record
    }
    override fun quarantine(raw: JsonObject, generation: Int) { quarantined += raw to generation }

    /** Phone-side deletion: drop the result, keep a tombstone (REQ-DM27). */
    fun delete(id: String) { results.remove(id); records[id] = records.getValue(id).copy(tombstoned = true) }
    fun editLocally(id: String) { records[id] = records.getValue(id).copy(locallyEdited = true) }
}

class InMemorySessions : SessionRepository {
    val sessions = mutableMapOf<String, Session>()
    val records = mutableMapOf<String, DedupeRecord>()
    override fun dedupe(sessionId: String) = records[sessionId]
    override fun commit(session: Session, raw: JsonObject, record: DedupeRecord) {
        sessions[session.id] = session
        records[session.id] = record
    }
}
