package com.wodcapture.domain.model

import com.wodcapture.domain.IdSource
import com.wodcapture.domain.TestSupport
import com.wodcapture.domain.manual.*
import com.wodcapture.domain.plan.*
import com.wodcapture.domain.validation.Severity
import com.wodcapture.domain.validation.WorkoutValidator
import org.junit.jupiter.api.Test
import kotlin.test.*

class WarmupsTest {
    private val ids = IdSource.sequential("f1000000")
    private fun part(format: Format = NotForTime) = Part(
        "30000000-0000-4000-8000-000000000003", kind = PartKind.WARMUP, format = format,
        scoring = Scoring(ScoreType.ROUNDS_REPS, manual = true),
        body = listOf(Item("50000000-0000-4000-8000-000000000003", name = "Air squat", measure = Measure(MeasureKind.REPS, 10.0))),
    )

    @Test fun parsedRoundsWarmupDoesNotNeedScoreFallbackAcceptance() {
        val raw = part()
        assertNull(raw.fallback)
        assertTrue(WorkoutValidator.validatePart(raw).none { it.severity == Severity.BLOCKING })
        val workout = Workout(id = "20000000-0000-4000-8000-000000000001", parts = listOf(raw))
        val result = assertIs<CompileResult.Success>(PlanCompiler(TestSupport.catalog).compile(workout, TestSupport.profile,
            PlanIdentity("10000000-0000-4000-8000-000000000001", 1)))
        val compiled = result.plan.p.single()
        assertEquals("none", compiled.sc.ty)
        assertEquals("manual", compiled.mode)
        assertEquals(Warmups.DEFAULT_CAP_SEC, compiled.seg!!.single().cap)
    }

    @Test fun switchingTimeAndRoundsPreservesRoundsCapMovementsAndNoScore() {
        val rounds = Warmups.withRounds(part(), 3, ids::newId).let {
            Warmups.prepare(it.copy(fallback = it.fallback!!.copy(capSec = 420)))
        }
        val timed = Warmups.prepare(rounds.copy(format = ForTime(capSec = Warmups.timeCap(rounds))))
        val back = Warmups.prepare(timed.copy(format = NotForTime))
        for (p in listOf(rounds, timed, back)) {
            assertEquals(3, Warmups.rounds(p))
            assertEquals(420, Warmups.timeCap(p))
            assertEquals(ScoreType.NONE, p.scoring.type)
            assertFalse(p.scoring.manual)
            assertEquals(rounds.body, p.body)
        }
        assertEquals(FallbackTimer.COUNT_DOWN, timed.fallback!!.timer)
        assertEquals(FallbackTimer.COUNT_UP, back.fallback!!.timer)
    }

    @Test fun bothManualWarmupFormatsCarryRoundsCapAndNoScore() {
        for (format in listOf(ManualFormat.FOR_TIME, ManualFormat.FOR_QUALITY)) {
            val row = ManualForms.withFormat(ManualPartForm(ids.newId(), kind = PartKind.WARMUP), format, ids)
                .copy(rounds = 3, capText = "7:00", movements = listOf(ManualMovementForm(ids.newId(), name = "Air squat", amount = 10.0)))
            val built = ManualWorkoutBuilder(ids).build(ManualWorkoutForm(date = "2026-11-13", timeZone = "UTC", parts = listOf(row)))
            assertTrue(built.canCreate, built.report.blocking.toString())
            val p = built.workout.parts.single()
            assertEquals(3, Warmups.rounds(p))
            assertEquals(420, Warmups.timeCap(p))
            assertEquals(ScoreType.NONE, p.scoring.type)
            val compiled = assertIs<CompileResult.Success>(PlanCompiler(TestSupport.catalog).compile(built.workout, TestSupport.profile,
                PlanIdentity("10000000-0000-4000-8000-000000000001", 1))).plan.p.single()
            assertEquals("none", compiled.sc.ty)
            assertEquals(420, if (format == ManualFormat.FOR_TIME) compiled.seg!!.single().d else compiled.seg!!.single().cap)
        }
    }

    @Test fun nonWarmupNotForTimeStillRequiresExplicitAcceptance() {
        val metcon = part().copy(kind = PartKind.METCON)
        assertTrue(WorkoutValidator.validatePart(metcon).any { it.code == "fallback_required" })
        assertEquals(metcon, Warmups.prepare(metcon))
    }
}
