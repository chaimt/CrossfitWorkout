package com.wodcapture.domain.review

import com.wodcapture.domain.TestSupport
import com.wodcapture.domain.model.AbsoluteLoad
import com.wodcapture.domain.model.ForTime
import com.wodcapture.domain.model.Item
import com.wodcapture.domain.model.LoadObservation
import com.wodcapture.domain.model.Mass
import com.wodcapture.domain.model.MassUnit
import com.wodcapture.domain.model.Measure
import com.wodcapture.domain.model.MeasureKind
import com.wodcapture.domain.model.Part
import com.wodcapture.domain.model.ResultSource
import com.wodcapture.domain.model.ResultStatus
import com.wodcapture.domain.model.Workout
import com.wodcapture.domain.model.WorkoutResult
import com.wodcapture.domain.scoring.TopLift
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** REQ-AE07: missing metcon loads suggest last metcon weight, then top lift / rep max. */
class LoadSuggestionsTest {
    private fun workout(id: String, item: Item) = Workout(
        id = id,
        parts = listOf(Part(id = "p", format = ForTime(), body = listOf(item))),
    )

    private fun barePushPress(id: String = "n1") = Item(
        id = id,
        movementId = "push_press",
        name = "Push press",
        measure = Measure(MeasureKind.REPS, 5.0),
    )

    private fun result(w: Workout, nodeId: String, kg: Double, startedAt: Long = 1_793_000_000L) =
        WorkoutResult(
            id = "r-$nodeId-$kg",
            sessionId = "s",
            workoutId = w.id,
            workoutRevision = 1,
            partId = "p",
            startedAt = startedAt,
            status = ResultStatus.COMPLETED,
            source = ResultSource.WATCH,
            loads = listOf(LoadObservation(nodeId, Mass(kg, MassUnit.KG))),
        )

    private fun withLoad(w: Workout, id: String, kg: Double): Workout {
        val loaded = Item(
            id = id,
            movementId = "push_press",
            name = "Push press",
            measure = Measure(MeasureKind.REPS, 5.0),
            load = AbsoluteLoad(Mass(kg, MassUnit.KG)),
        )
        return w.copy(parts = listOf(w.parts[0].copy(body = listOf(loaded))))
    }

    @Test
    fun lastMetconLoadWinsOverTopLift() {
        val history = withLoad(workout("hist", barePushPress("h1")), "h1", 40.0)
        val current = workout("cur", barePushPress("c1"))
        val byId = mapOf(history.id to history)
        val top = mapOf("push_press" to TopLift("push_press", "Push press", Mass(60.0, MassUnit.KG), 5, LocalDate.of(2026, 10, 20), "hist", "History"))
        val out = LoadSuggestions.forWorkout(current, TestSupport.catalog, listOf(result(history, "h1", 40.0)), byId, top, MassUnit.KG)
        val s = out.getValue("c1")
        assertEquals(Mass(40.0, MassUnit.KG), s.mass)
        assertEquals(SuggestionSource.LAST_METCON, s.source)
    }

    @Test
    fun topLiftFallsBackWhenNoMetconLoad() {
        val current = workout("cur", barePushPress("c1"))
        val top = mapOf("push_press" to TopLift("push_press", "Push press", Mass(60.0, MassUnit.KG), 5, LocalDate.of(2026, 10, 20), "hist", "History"))
        val out = LoadSuggestions.forWorkout(current, TestSupport.catalog, emptyList(), emptyMap(), top, MassUnit.KG)
        val s = out.getValue("c1")
        assertEquals(Mass(60.0, MassUnit.KG), s.mass)
        assertEquals(SuggestionSource.TOP_LIFT, s.source)
    }

    @Test
    fun manualRepMaxIsLabeled() {
        val current = workout("cur", barePushPress("c1"))
        val top = mapOf("push_press" to TopLift("push_press", "Push press", Mass(62.5, MassUnit.KG), 1, LocalDate.of(2026, 11, 1), null, null, manual = true))
        val out = LoadSuggestions.forWorkout(current, TestSupport.catalog, emptyList(), emptyMap(), top, MassUnit.KG)
        assertEquals(SuggestionSource.REP_MAX, out.getValue("c1").source)
    }

    @Test
    fun newestMetconLoadWinsAcrossWorkouts() {
        val older = withLoad(workout("older", barePushPress("o1")), "o1", 30.0)
        val newer = withLoad(workout("newer", barePushPress("n1")), "n1", 42.5)
        val current = workout("cur", barePushPress("c1"))
        val byId = mapOf(older.id to older, newer.id to newer)
        val out = LoadSuggestions.forWorkout(
            current, TestSupport.catalog,
            listOf(result(older, "o1", 30.0, 1_793_000_000L), result(newer, "n1", 42.5, 1_793_000_100L)),
            byId, emptyMap(), MassUnit.KG,
        )
        assertEquals(Mass(42.5, MassUnit.KG), out.getValue("c1").mass)
    }

    @Test
    fun skipsLoadedBodyweightAndStrengthItems() {
        val loaded = barePushPress("c1").copy(load = AbsoluteLoad(Mass(40.0, MassUnit.KG)))
        assertTrue(LoadSuggestions.forWorkout(workout("a", loaded), TestSupport.catalog, emptyList(), emptyMap(), emptyMap(), MassUnit.KG).isEmpty())
        val squat = Item(id = "c2", movementId = "air_squat", name = "Air squat", measure = Measure(MeasureKind.REPS, 10.0))
        assertTrue(LoadSuggestions.forWorkout(workout("b", squat), TestSupport.catalog, emptyList(), emptyMap(), emptyMap(), MassUnit.KG).isEmpty())
    }

    @Test
    fun suggestsNothingWhenNoHistory() {
        assertTrue(
            LoadSuggestions.forWorkout(workout("c", barePushPress("c1")), TestSupport.catalog, emptyList(), emptyMap(), emptyMap(), MassUnit.KG).isEmpty(),
        )
    }
}
