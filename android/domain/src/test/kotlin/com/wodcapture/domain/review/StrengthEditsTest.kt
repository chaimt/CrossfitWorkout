package com.wodcapture.domain.review

import com.wodcapture.domain.DomainJson
import com.wodcapture.domain.model.AbsoluteLoad
import com.wodcapture.domain.model.Item
import com.wodcapture.domain.model.Mass
import com.wodcapture.domain.model.MassUnit
import com.wodcapture.domain.model.Part
import com.wodcapture.domain.model.PartKind
import com.wodcapture.domain.model.PercentOfMax
import com.wodcapture.domain.model.RepTarget
import com.wodcapture.domain.model.ScoreType
import com.wodcapture.domain.model.Scoring
import com.wodcapture.domain.model.StrengthSet
import com.wodcapture.domain.model.StrengthSets
import com.wodcapture.domain.model.Workout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Editing a strength session before it is done: the top weight and each set's % · weight stay two
 * views of one value (REQ-AE08, owner request 2026-11-15).
 */
class StrengthEditsTest {
    private fun session(top: Double? = null, weights: List<Double?> = listOf(70.0, 80.0, 90.0), percents: List<Double?> = listOf(null, null, null)): Part {
        val sets = weights.mapIndexed { i, w ->
            StrengthSet(
                id = "s$i",
                reps = RepTarget(5),
                load = w?.let { AbsoluteLoad(Mass(it, MassUnit.KG)) },
                ofTop = percents.getOrNull(i),
            )
        }
        return Part(
            id = "p",
            label = "A",
            kind = PartKind.STRENGTH,
            format = StrengthSets(topWeight = top?.let { Mass(it, MassUnit.KG) }),
            scoring = Scoring(ScoreType.LOAD),
            body = listOf(Item(id = "lift", movementId = "back_squat", name = "Back squat", sets = sets)),
        )
    }

    private fun lift(part: Part): Item = StrengthEdits.liftOf(part)!!

    private fun weights(part: Part): List<Double?> = lift(part).sets.map { StrengthEdits.weightOf(lift(part), it, MassUnit.KG) }

    private fun percents(part: Part): List<Double?> = lift(part).sets.map { StrengthEdits.percentOf(lift(part), it, StrengthEdits.topWeight(part)) }

    @Test
    fun aSetsWeightAndItsPercentOfTheTopWeightFollowEachOther() {
        val part = session(top = 100.0)
        // Weight → %, to one decimal.
        assertEquals(listOf(70.0, 80.0, 90.0), percents(part))
        // % → weight, rounded to the Settings increment.
        val typed = StrengthEdits.withSetPercent(part, "s0", 72.0, step = 2.5)
        assertEquals(72.5, weights(typed)[0])
        assertEquals(72.0, percents(typed)[0], "the % stays as typed, the weight is the rounded one")
        // Typing a weight re-reads the % from it.
        val byWeight = StrengthEdits.withSetWeight(typed, "s0", 75.0, MassUnit.KG)
        assertEquals(75.0 to 75.0, weights(byWeight)[0] to percents(byWeight)[0])
    }

    @Test
    fun withoutATopWeightOnlyTheWeightCanBeTyped() {
        val part = session(top = null)
        assertEquals(listOf(null, null, null), percents(part))
        // A % has nothing to compute from, so it waits for the top weight and the weight stays.
        val typed = StrengthEdits.withSetPercent(part, "s0", 80.0, step = 1.0)
        assertEquals(70.0, weights(typed)[0])
        assertEquals(80.0, percents(typed)[0])
        // Entering the top weight then fills that set from its %.
        val filled = StrengthEdits.withTopWeight(typed, Mass(120.0, MassUnit.KG), step = 1.0)
        assertEquals(96.0, weights(filled)[0])
        // The sets that were weights keep their weight and gain a %.
        assertEquals(listOf(96.0, 80.0, 90.0), weights(filled))
        assertEquals(listOf(80.0, 66.7, 75.0), percents(filled))
    }

    @Test
    fun changingTheTopWeightRescalesTheSessionAndClearingItKeepsIt() {
        val part = StrengthEdits.withTopWeight(session(top = 100.0), Mass(100.0, MassUnit.KG), step = 1.0)
        assertEquals(listOf(70.0, 80.0, 90.0), percents(part))
        val raised = StrengthEdits.withTopWeight(part, Mass(120.0, MassUnit.KG), step = 2.5)
        // 70/80/90 % of 120 kg = 84/96/108, each on the nearest 2.5 kg, ties downward (REQ-DM19).
        assertEquals(listOf(85.0, 95.0, 107.5), weights(raised))
        assertEquals(listOf(70.0, 80.0, 90.0), percents(raised), "the percentages are what stay fixed")
        // Clearing it leaves every set alone, so retyping a top weight loses nothing.
        val cleared = StrengthEdits.withTopWeight(raised, null, step = 1.0)
        assertNull(StrengthEdits.topWeight(cleared))
        assertEquals(listOf(85.0, 95.0, 107.5), weights(cleared))
        assertEquals(listOf(70.0, 80.0, 90.0), percents(cleared))
    }

    @Test
    fun aParsedPercentagePrescriptionShowsItsPercentBeforeAnythingIsTyped() {
        val sets = (0..1).map { StrengthSet(id = "s$it", reps = RepTarget(3)) }
        val item = Item(id = "lift", movementId = "back_squat", name = "Back squat", load = PercentOfMax(80.0, ofMovementId = "back_squat"), sets = sets)
        val part = session().copy(body = listOf(item))
        assertEquals(listOf(80.0, 80.0), percents(part))
        assertEquals(listOf(null, null), weights(part))
    }

    @Test
    fun aSetInAnotherUnitIsReadAgainstTheTopWeightByPhysicalMass() {
        val part = session(top = 100.0, weights = listOf(null)).let { p ->
            StrengthEdits.withSetWeight(p, "s0", 220.46, MassUnit.LB)
        }
        // 220.46 lb ≈ 100 kg → 100 % of the top weight, and read back in kg for the editor.
        assertEquals(100.0, percents(part)[0])
        assertEquals(100.0, weights(part)[0]!!, 0.01)
    }

    @Test
    fun setsAreAddedAndRemovedKeepingTheLastSetsShape() {
        val part = session(top = 100.0)
        var n = 0
        val added = StrengthEdits.addSet(part, "lift") { "new${n++}" }
        assertEquals(4, lift(added).sets.size)
        assertEquals(90.0, weights(added)[3], "a new set repeats the last one")
        assertEquals(5, lift(added).sets[3].reps.value)
        val removed = StrengthEdits.removeSet(added, "lift", "s1")
        assertEquals(listOf("s0", "s2", "new0"), lift(removed).sets.map { it.id })
        assertEquals(10, StrengthEdits.withSetReps(removed, "s0", 10).let { lift(it).sets[0].reps.value })
        assertEquals(listOf(120, 120, 120), lift(StrengthEdits.withRest(removed, "lift", 120)).sets.map { it.restSec })
    }

    @Test
    fun theTopWeightAndEachSetsPercentSurviveBeingSavedAndReopened() {
        val part = StrengthEdits.withSetPercent(session(top = 100.0), "s0", 72.5, step = 2.5)
        val json = DomainJson.encodeWorkout(Workout(id = "w", parts = listOf(part)))
        val reopened = DomainJson.decodeWorkout(json).parts[0]
        assertEquals(Mass(100.0, MassUnit.KG), StrengthEdits.topWeight(reopened))
        assertEquals(listOf(72.5, 80.0, 90.0), percents(reopened))
        assertEquals(listOf(72.5, 80.0, 90.0), weights(reopened))
        assertTrue(StrengthEdits.isStrength(reopened))
    }
}
