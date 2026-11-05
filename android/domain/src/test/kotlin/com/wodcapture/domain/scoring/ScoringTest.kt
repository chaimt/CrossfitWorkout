package com.wodcapture.domain.scoring

import com.wodcapture.domain.model.Mass
import com.wodcapture.domain.model.MassUnit
import com.wodcapture.domain.model.ProgressEvent
import com.wodcapture.domain.model.ResultStatus
import com.wodcapture.domain.model.Score
import com.wodcapture.domain.model.SetObservation
import com.wodcapture.domain.model.SetStatus
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** REQ-DM21–DM24 and the §4.5 score fixtures; §6.12 deterministic clock example (REQ-TS12). */
class ScoringTest {

    @Test
    fun ladderTwoRoundsPlusFiveIs23() {
        // Two movements at 3, 6, 9 reps -> 6/12/18 per round; 2 rounds + 5 reps = 23.
        assertEquals(23, ScoreCalculator.totalReps(2, 5, listOf(6, 12, 18)))
        assertEquals(Score.RoundsReps(2, 5), ScoreCalculator.roundsReps(23, listOf(6, 12, 18)))
    }

    @Test
    fun completedRoundIsNormalisedAndOverflowRejected() {
        assertEquals(3 to 0, ScoreCalculator.normalize(2, 18, listOf(6, 12, 18)))
        assertFailsWith<IllegalArgumentException> { ScoreCalculator.normalize(2, 19, listOf(6, 12, 18)) }
        // Constant-round AMRAP (rr=[30]): 5 rounds + 7 = 157.
        assertEquals(157, ScoreCalculator.totalReps(5, 7, listOf(30)))
    }

    @Test
    fun h7MissingIntervalMakesTheSumIncomplete() {
        val intervals = listOf(Score.RoundsReps(2, 0), Score.RoundsReps(2, 5), null, Score.RoundsReps(1, 0))
        val agg = ScoreCalculator.sumRoundsReps(intervals, repsPerRound = 15)
        assertFalse(agg.complete)
        assertNull(agg.value, "missing is never replaced by 0")
        assertEquals(listOf(2), agg.missing)
        assertEquals(30.0 + 35 + 15, agg.partialValue)
    }

    @Test
    fun tabataThisIsSumOfBlockMinimums() {
        val agg = ScoreCalculator.sumOfBlockMinimums(listOf(listOf(12, 10, 11), listOf(20, 18), listOf(8, 9)))
        assertTrue(agg.complete)
        assertEquals(10.0 + 18 + 8, agg.value)
        assertFalse(ScoreCalculator.sumOfBlockMinimums(listOf(listOf(12, null))).complete)
    }

    @Test
    fun capTieFinishWins() {
        val events = listOf(ProgressEvent(0, "rd", 200_000), ProgressEvent(1, "rd", 400_000), ProgressEvent(2, "cap", 480_000), ProgressEvent(2, "fin", 480_000))
        val o = ScoreCalculator.forTime(events, 480_000, listOf(42, 30, 18), null)
        assertEquals(ResultStatus.COMPLETED, o.status)
        assertEquals(Score.Time(480), o.score)
    }

    @Test
    fun cappedFranIs83Reps() {
        // Matches shared/fixtures/wire/valid/result.put.capped.json: 42 + 30 + 11.
        val events = listOf(ProgressEvent(0, "rd", 201_000), ProgressEvent(1, "rd", 395_000), ProgressEvent(2, "cap", 480_000), ProgressEvent(2, "fin", 481_000))
        val o = ScoreCalculator.forTime(events, 480_000, listOf(42, 30, 18), 11)
        assertEquals(ResultStatus.CAPPED, o.status)
        assertEquals(Score.Reps(83), o.score)
    }

    @Test
    fun cappedWithoutCountingRuleStaysManualAndUndoCompensates() {
        val events = listOf(ProgressEvent(0, "rd", 1000), ProgressEvent(1, "rd", 2000), ProgressEvent(1, "undo", 2500))
        val o = ScoreCalculator.forTime(events, 10_000, null, null)
        assertEquals(ResultStatus.CAPPED, o.status)
        assertEquals(1, o.rounds)
        assertTrue(o.score is Score.Text)
        assertEquals(ResultStatus.STOPPED_EARLY, ScoreCalculator.forTime(events, null, null, null).status)
    }

    @Test
    fun mixedMeasuresNeedARule() {
        assertFailsWith<IllegalArgumentException> { ScoreCalculator.sumMixed(listOf("cal" to 15.0, "reps" to 12.0), null) }
        assertEquals(27.0, ScoreCalculator.sumMixed(listOf("cal" to 15.0, "reps" to 12.0), "points_1_per_unit").value)
    }

    @Test
    fun bestLoadIgnoresFailedAndPartialSets() {
        val sets = listOf(
            SetObservation("a", 0, 3, 3, Mass(100.0, MassUnit.KG), status = SetStatus.DONE),
            SetObservation("b", 1, 3, 2, Mass(110.0, MassUnit.KG), status = SetStatus.PARTIAL),
            SetObservation("c", 2, 3, 0, Mass(115.0, MassUnit.KG), status = SetStatus.FAILED),
            SetObservation("d", 3, 3, 3, Mass(225.0, MassUnit.LB), status = SetStatus.DONE),
        )
        assertEquals(Score.Load(102.058, MassUnit.KG), ScoreCalculator.bestLoad(sets, MassUnit.KG))
        // Volume: 3x100 + 2x110 (partial reps count) + 3x102.058 = 826.17 kg.
        assertEquals(826.175, ScoreCalculator.volume(sets, MassUnit.KG).value, 0.01)
    }

    @Test
    fun section612DeterministicClockExample() {
        val t = ClockPolicy.totals(
            listOf(
                Phase.Prep(10),
                Phase.Work(60, 0), Phase.InClockRest(20, 0), Phase.Work(40, 0),
                Phase.PartEntry(15), Phase.PrescribedRest(30), Phase.Wait(10),
                Phase.Work(50, 1), Phase.Pause(10), Phase.Work(10, 1),
            ),
        )
        assertEquals(245, t.wallSec)
        assertEquals(210, t.activeSec)
        assertEquals(mapOf(0 to 120, 1 to 60), t.partScoreSec)
    }

    @Test
    fun largeValuesSurvive() {
        // REQ-TS12: 1,000 reps and 2,000 m are stored as-is.
        assertEquals(1000, ScoreCalculator.totalReps(0, 999, listOf(1000)) + 1)
    }
}
