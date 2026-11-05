package com.wodcapture.domain.scoring

import com.wodcapture.domain.TestSupport
import com.wodcapture.domain.model.AbsoluteLoad
import com.wodcapture.domain.model.BuildToMax
import com.wodcapture.domain.model.Item
import com.wodcapture.domain.model.Mass
import com.wodcapture.domain.model.MassUnit
import com.wodcapture.domain.model.Part
import com.wodcapture.domain.model.PartScore
import com.wodcapture.domain.model.PercentOfMax
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

/** REQ-AL07: heaviest weight per lift across the workouts and their results, with its day. */
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
        TopLifts.compute(w.toList(), results, TestSupport.catalog, unit, ZoneOffset.UTC)

    private fun planned(id: String, movementId: String, name: String, date: String?, vararg kg: Double, createdAt: Long? = null) = Workout(
        id = id, title = "W $id", date = date, createdAt = createdAt,
        parts = listOf(
            Part(
                id = "p", format = StrengthSets(),
                body = listOf(Item("i", movementId, name, sets = kg.mapIndexed { n, v -> StrengthSet("$id-s$n", reps = RepTarget(3), load = AbsoluteLoad(Mass(v, MassUnit.KG))) })),
            ),
        ),
    )

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

    @Test
    fun workoutWeightsCountWithoutAnyResult() {
        val a = planned("a", "back_squat", "Back squat", "2026-10-20", 100.0, 120.0, 110.0)
        val b = planned("b", "back_squat", "Back squat", "2026-10-22", 115.0)
        val lift = top(emptyList(), a, b).single()
        assertEquals(Mass(120.0, MassUnit.KG), lift.load)
        assertEquals(3, lift.reps)
        assertEquals(LocalDate.of(2026, 10, 20), lift.date)
        assertEquals("a", lift.workoutId)
    }

    @Test
    fun unscheduledWorkoutIsDatedByItsCreationDay() {
        val a = planned("a", "deadlift", "Deadlift", null, 140.0, createdAt = day1)
        assertEquals(LocalDate.of(2026, 10, 26), top(emptyList(), a).single().date)
    }

    @Test
    fun loggedWeightHeavierThanPlannedWins() {
        val a = planned("a", "back_squat", "Back squat", "2026-10-20", 100.0)
        val logged = result("r", a, day2, listOf(set("a-s0", 105.0, 3, target = 3)))
        val lift = top(listOf(logged), a).single()
        assertEquals(Mass(105.0, MassUnit.KG), lift.load)
        assertEquals(LocalDate.of(2026, 10, 27), lift.date)
    }

    @Test
    fun percentOfMaxSetsHaveNoWeightToShow() {
        val w = planned("a", "back_squat", "Back squat", "2026-10-20", 100.0).let { p ->
            p.copy(parts = p.parts.map { part -> part.copy(body = listOf(Item("i", "back_squat", "Back squat", sets = listOf(StrengthSet("s", load = PercentOfMax(80.0)))))) })
        }
        assertTrue(top(emptyList(), w).isEmpty())
    }
}
