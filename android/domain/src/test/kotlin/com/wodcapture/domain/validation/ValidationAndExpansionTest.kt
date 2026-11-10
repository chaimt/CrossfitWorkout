package com.wodcapture.domain.validation

import com.wodcapture.domain.DomainJson
import com.wodcapture.domain.Fixtures
import com.wodcapture.domain.TestSupport
import com.wodcapture.domain.expand.RepSchemeExpander
import com.wodcapture.domain.fid
import com.wodcapture.domain.levels.DanglingOverrideException
import com.wodcapture.domain.levels.LevelApplier
import com.wodcapture.domain.model.Amrap
import com.wodcapture.domain.model.BodyNode
import com.wodcapture.domain.model.Emom
import com.wodcapture.domain.model.ForTime
import com.wodcapture.domain.model.Group
import com.wodcapture.domain.model.GroupKind
import com.wodcapture.domain.model.Item
import com.wodcapture.domain.model.Level
import com.wodcapture.domain.model.Measure
import com.wodcapture.domain.model.MeasureKind
import com.wodcapture.domain.model.Override
import com.wodcapture.domain.model.Part
import com.wodcapture.domain.model.Rest
import com.wodcapture.domain.model.ScoreType
import com.wodcapture.domain.model.Scoring
import com.wodcapture.domain.model.Workout
import com.wodcapture.domain.plan.CompileOptions
import com.wodcapture.domain.plan.CompileResult
import com.wodcapture.domain.plan.PlanCompiler
import com.wodcapture.domain.plan.PlanIdentity
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** REQ-AX05 validation, REQ-DM16 bounds, rep-scheme expansion and REQ-TS08 negative fixtures. */
class ValidationAndExpansionTest {
    private val expander = RepSchemeExpander()

    @Test
    fun franSchemeExpandsToPerRoundTotals() {
        val prog = expander.program(Fixtures.h1.expected.parts[0].body)
        assertEquals(listOf(42, 30, 18), prog.rounds.map { it.repTotal })
    }

    @Test
    fun ladderGivesFiftyFiveRepsPerMovement() {
        val body = Fixtures.h8.expected.parts[2].body
        val prog = expander.program(body)
        assertEquals(10, prog.rounds.size)
        val dl = (body[0] as Group).children[0] as Item
        assertEquals(55, expander.totalRepsFor(dl, prog.rounds))
    }

    @Test
    fun progressionAddsRepsPerRoundAndDistanceIsNotCountable() {
        val prog = expander.program(Fixtures.h4.expected.parts[0].body, progressiveRounds = 3)
        val firstItemReps = prog.rounds.map { r -> (r.steps[0] as com.wodcapture.domain.expand.Step.Work).reps }
        assertEquals(listOf(3, 6, 9), firstItemReps)
        assertTrue(prog.rounds.all { it.repTotal == null }, "lunges in feet have no counting rule")
    }

    @Test
    fun betweenOnlyRestIsSkippedAfterTheLastRound() {
        val prog = expander.program(Fixtures.h5.expected.parts[0].body)
        val rests = prog.rounds.map { r -> r.steps.count { it is com.wodcapture.domain.expand.Step.Pause } }
        assertEquals(listOf(1, 1, 0), rests)
        assertEquals(1, prog.cashOut.size)
    }

    @Test
    fun allAppendixHFixturesValidateWithoutBlockingAfterReview() {
        Fixtures.all.forEach { fx ->
            val r = WorkoutValidator.validate(fx.review(fx.expected))
            assertTrue(r.canSend, "${fx.name}: ${r.blocking}")
        }
    }

    @Test
    fun warningsCoverFreeTextAndRepMaxNeeds() {
        val codes = WorkoutValidator.validate(Fixtures.h1.expected).codes() + WorkoutValidator.validate(Fixtures.h9.expected).codes()
        assertTrue("needs_rep_max" in codes)
        val free = Fixtures.h10.expected.copy(parts = listOf(Fixtures.h10.expected.parts[0].copy(body = listOf(Item(fid(99, 2, 1), null, "Sandbag thing", measure = Measure(MeasureKind.REPS, 5.0))))))
        val report = WorkoutValidator.validate(free)
        assertTrue(report.canSend, "free-text movements never block")
        assertTrue("free_text_movement" in report.codes())
    }

    @Test
    fun levelsApplyAndDanglingOverridesBlock() {
        val part = Fixtures.h1.expected.parts[0]
        val scaled = LevelApplier.apply(part, "Scaled")
        val items = com.wodcapture.domain.model.BodyTree.items(scaled.body)
        assertEquals("jumping_pull_up", items[1].movementId)
        assertEquals(65.0, (items[0].load as com.wodcapture.domain.model.AbsoluteLoad).mass.value)
        assertEquals(listOf("Rx", "Scaled"), LevelApplier.levelNames(part))
        val broken = part.copy(levels = listOf(Level("Scaled", listOf(Override("f9990000-0000-4000-8000-000000000001", name = "x")))))
        assertFailsWith<DanglingOverrideException> { LevelApplier.apply(broken, "Scaled") }
    }

    /** REQ-TS08 negative fixtures: each must block Send with its specific code. */
    @TestFactory
    fun negativeFixtures() = negatives().map { (name, workout, code) ->
        DynamicTest.dynamicTest("$name -> $code") {
            TestSupport.assertGolden("negative/$name.workout.json", DomainJson.encodeWorkout(workout) + "\n")
            val report = WorkoutValidator.validate(workout)
            val compile = PlanCompiler(TestSupport.catalog).compile(workout, TestSupport.profile, PlanIdentity(fid(98, 4, 1), 1), CompileOptions())
            val codes = report.codes() + ((compile as? CompileResult.Failure)?.errors?.map { it.code } ?: emptyList())
            assertTrue(code in codes, "$name expected $code, got $codes")
            assertFalse(compile is CompileResult.Success, "$name must not compile")
        }
    }

    private fun negatives(): List<Triple<String, Workout, String>> {
        val base = Fixtures.h10.expected
        val p = base.parts[0]
        fun with(part: Part) = base.copy(parts = listOf(part))
        fun nest(depth: Int): BodyNode =
            if (depth == 0) Item(fid(97, 2, 99), "air_squat", "Air squat", measure = Measure(MeasureKind.REPS, 1.0))
            else Group(fid(97, 2, depth), GroupKind.ROUNDS, repeat = 2, children = listOf(nest(depth - 1)))
        val longLines = (1..13).map { Item(fid(96, 2, it), "air_squat", "Air squat", measure = Measure(MeasureKind.REPS, it.toDouble())) }
        val hugeTitle = (1..30).map { Item(fid(95, 2, it), null, "Movement number $it with a long name", measure = Measure(MeasureKind.REPS, 1.0)) }
        return listOf(
            Triple("missing-duration", with(p.copy(format = Amrap(null))), "missing_duration"),
            Triple("zero-intervals", with(p.copy(format = Emom(60, 0))), "nonpositive_duration"),
            Triple("unknown-version", base.copy(schemaVersion = 2), "unsupported_schema_version"),
            Triple("dangling-override", with(p.copy(levels = listOf(Level("Scaled", listOf(Override("f9990000-0000-4000-8000-000000000001", name = "Ring row")))))), "dangling_override"),
            Triple("depth-overflow", with(p.copy(body = listOf(nest(5)))), "depth_exceeded"),
            Triple("repeat-overflow", with(p.copy(body = listOf(Group(fid(94, 2, 1), repeat = 201, children = p.body)))), "repeat_out_of_range"),
            Triple("too-many-lines", with(p.copy(body = longLines)), "too_many_lines"),
            Triple("size-overflow", with(p.copy(format = ForTime(), body = hugeTitle)), "line_too_long"),
            Triple("nonpositive-rest", with(p.copy(body = p.body + Rest(fid(93, 2, 1), 0))), "nonpositive_duration"),
            Triple("mixed-measure-sum", with(p.copy(scoring = Scoring(ScoreType.REPS), body = p.body + Item(fid(92, 2, 1), "row", "Row", measure = Measure(MeasureKind.CALORIES, 10.0)))), "mixed_measure_sum"),
            Triple("rep-scheme-mismatch", with(p.copy(body = listOf(Group(fid(91, 2, 1), repeat = 4, repScheme = listOf(21, 15, 9), children = p.body)))), "invalid_rep_scheme"),
        )
    }
}
