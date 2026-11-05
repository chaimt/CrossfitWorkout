package com.wodcapture.domain.scoring

import com.wodcapture.domain.TestSupport
import com.wodcapture.domain.model.BuildToMax
import com.wodcapture.domain.model.Item
import com.wodcapture.domain.model.Mass
import com.wodcapture.domain.model.MassUnit
import com.wodcapture.domain.model.Part
import com.wodcapture.domain.model.PartScore
import com.wodcapture.domain.model.RepTarget
import com.wodcapture.domain.model.ResultSource
import com.wodcapture.domain.model.ResultStatus
import com.wodcapture.domain.model.Score
import com.wodcapture.domain.model.SetObservation
import com.wodcapture.domain.model.SetStatus
import com.wodcapture.domain.model.StrengthSet
import com.wodcapture.domain.model.StrengthSets
import com.wodcapture.domain.model.Workout
import com.wodcapture.domain.model.WorkoutResult
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** REQ-AL07: heaviest completed set per lift, with the day it was done. */
class TopLiftsTest {
    private val day1 = 1_793_000_000L // 2026-10-26 UTC
    private val day2 = day1 + 86_400

    private fun strength(id: String, movementId: String?, name: String, sets: Int) = Workout(
        id = id, title = "W $id",
        parts = listOf(
            Part(
                id = "p", format = StrengthSets(),
                body = listOf(Item("i", movementId, name, sets = (1..sets).map { StrengthSet("$id-s$it", reps = RepTarget(5)) })),
            ),
        ),
    )

    private fun set(id: String, kg: Double, reps: Int?, status: SetStatus = SetStatus.DONE, target: Int? = 5) =
        SetObservation(setId = id, index = 0, targetReps = target, actualReps = reps, load = Mass(kg, MassUnit.KG), status = status)

    private fun result(id: String, workout: Workout, at: Long, sets: List<SetObservation> = emptyList(), score: Score? = null, source: ResultSource = ResultSource.WATCH) =
        WorkoutResult(
            id = id, sessionId = id, workoutId = workout.id, workoutRevision = 1, partId = "p", startedAt = at,
            status = ResultStatus.COMPLETED, sets = sets, score = score?.let { PartScore(it, manual = true) }, source = source,
        )

    private fun top(results: List<WorkoutResult>, vararg w: Workout, unit: MassUnit = MassUnit.KG) =
        TopLifts.compute(results, w.associateBy { it.id }, TestSupport.catalog, unit, ZoneOffset.UTC)

    @Test
    fun heaviestCompletedSetWinsAcrossWorkouts() {
        val a = strength("a", "back_squat", "Back squat", 3)
        val b = strength("b", "back_squat", "back squat", 2)
        val results = listOf(
            result("r1", a, day1, listOf(set("a-s1", 100.0, 5), set("a-s2", 110.0, 5), set("a-s3", 130.0, 2, SetStatus.FAILED))),
            result("r2", b, day2, listOf(set("b-s1", 120.0, 5), set("b-s2", 125.0, 3))),
        )
        val lift = top(results, a, b).single()
        assertEquals("Back squat", lift.name)
        assertEquals(Mass(120.0, MassUnit.KG), lift.load)
        assertEquals(5, lift.reps)
        assertEquals(LocalDate.of(2026, 10, 27), lift.date)
        assertEquals("b", lift.workoutId)
    }

    @Test
    fun tieGoesToTheFirstDayReached() {
        val a = strength("a", "deadlift", "Deadlift", 1)
        val b = strength("b", "deadlift", "Deadlift", 1)
        val results = listOf(result("r2", b, day2, listOf(set("b-s1", 150.0, 5))), result("r1", a, day1, listOf(set("a-s1", 150.0, 5))))
        assertEquals(LocalDate.of(2026, 10, 26), top(results, a, b).single().date)
    }

    @Test
    fun oneRowPerLiftSortedByNameAndConvertedToTheDisplayUnit() {
        val a = strength("a", "deadlift", "Deadlift", 1)
        val b = strength("b", null, "Zercher squat", 1)
        val results = listOf(result("r1", a, day1, listOf(set("a-s1", 100.0, 5))), result("r2", b, day1, listOf(set("b-s1", 60.0, 5))))
        val lifts = top(results, a, b, unit = MassUnit.LB)
        assertEquals(listOf("deadlift", "zercher squat"), lifts.map { it.key })
        assertEquals("Zercher squat", lifts[1].name)
        assertEquals(MassUnit.LB, lifts[0].load.unit)
        assertEquals(220.462, lifts[0].load.value)
    }

    @Test
    fun manualLoadScoreCountsOnTheScheduledDay() {
        val w = Workout(
            id = "m", title = "Heavy single", date = "2026-10-20",
            parts = listOf(Part(id = "p", format = BuildToMax(repsPerSet = 1), body = listOf(Item("i", "clean", "Clean")))),
        )
        val lift = top(listOf(result("r", w, day2, score = Score.Load(90.0, MassUnit.KG), source = ResultSource.MANUAL)), w).single()
        assertEquals(Mass(90.0, MassUnit.KG), lift.load)
        assertEquals(1, lift.reps)
        assertEquals(LocalDate.of(2026, 10, 20), lift.date)
    }

    @Test
    fun ignoresShortSetsUnknownWorkoutsAndSkippedResults() {
        val a = strength("a", "back_squat", "Back squat", 2)
        val short = result("r1", a, day1, listOf(set("a-s1", 140.0, 3)))
        val orphan = result("r2", a.copy(id = "gone"), day1, listOf(set("a-s1", 150.0, 5)))
        val skipped = result("r3", a, day1, listOf(set("a-s2", 160.0, 5))).copy(status = ResultStatus.SKIPPED)
        assertTrue(top(listOf(short, orphan, skipped), a).isEmpty())
    }

    @Test
    fun repsUnknownWhenNeitherActualNorTargetIsKnown() {
        val a = strength("a", "back_squat", "Back squat", 1)
        val lift = top(listOf(result("r", a, day1, listOf(set("a-s1", 100.0, null, target = null)))), a).single()
        assertNull(lift.reps)
    }
}
