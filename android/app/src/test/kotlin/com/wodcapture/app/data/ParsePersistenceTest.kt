package com.wodcapture.app.data

import com.wodcapture.domain.DomainJson
import com.wodcapture.domain.model.ParseResult
import com.wodcapture.domain.model.Workout
import com.wodcapture.domain.parser.JobState
import com.wodcapture.domain.parser.ParseOutcome
import org.junit.Assert.*
import org.junit.Test

class ParsePersistenceTest {
    private val memory = MemoryDao()
    private val draft = WorkoutEntity("draft", 1, "My draft", null, DomainJson.encodeWorkout(Workout(id = "draft")),
        status = "parsing", createdAt = 1, updatedAt = 1)
    private val row = ParseJobEntity("job", "draft", 1, JobState.RUNNING.name, 0, createdAt = 1)
    private val success = ParseOutcome.Success(ParseResult(Workout(id = "parsed", title = "Parsed")), null, 1)

    init {
        memory.workouts[draft.id] = draft
        memory.jobs[row.jobId] = row
    }

    @Test
    fun cancellationBeforeCompletionPreservesDraftAndCancelledJob() {
        memory.dao.cancelParse("job")
        memory.dao.completeParse("job", success, 2, false)
        assertEquals("CANCELLED", memory.jobs["job"]!!.state)
        assertEquals(1, memory.workouts["draft"]!!.revision)
        assertNull(memory.workouts["draft"]!!.parseJson)
    }

    @Test
    fun deletedJobIsNotRecreatedByLateOutput() {
        memory.jobs.clear()
        assertNull(memory.dao.completeParse("job", success, 2, false))
        assertTrue(memory.jobs.isEmpty())
        assertEquals(draft, memory.workouts["draft"])
    }

    @Test
    fun editedDraftReceivesAlternativeWithoutLosingEdits() {
        memory.workouts["draft"] = draft.copy(revision = 2, title = "Edited")
        memory.dao.completeParse("job", success, 2, false)
        val current = memory.workouts["draft"]!!
        assertEquals("Edited", current.title)
        assertEquals(2, current.revision)
        assertNotNull(current.alternativeJson)
        assertNull(current.parseJson)
    }

    @Test
    fun stoppedWorkerAndArchivedDraftIgnoreOutput() {
        memory.dao.completeParse("job", success, 2, true)
        assertEquals("CANCELLED", memory.jobs["job"]!!.state)
        assertNull(memory.workouts["draft"]!!.parseJson)
        memory.jobs["job"] = row
        memory.workouts["draft"] = draft.copy(archived = true)
        memory.dao.completeParse("job", success, 2, false)
        assertNull(memory.workouts["draft"]!!.parseJson)
    }

    @Test
    fun successfulCompletionAppliesOnceAndLateCancelDoesNotUndoIt() {
        memory.dao.completeParse("job", success, 2, false)
        memory.dao.completeParse("job", success, 3, false)
        memory.dao.cancelParse("job")
        assertEquals("READY", memory.jobs["job"]!!.state)
        assertEquals(2, memory.workouts["draft"]!!.revision)
        assertEquals("ready", memory.workouts["draft"]!!.status)
    }
}
