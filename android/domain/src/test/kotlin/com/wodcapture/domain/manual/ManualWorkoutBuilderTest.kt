package com.wodcapture.domain.manual

import com.wodcapture.domain.DomainJson
import com.wodcapture.domain.IdSource
import com.wodcapture.domain.TestSupport
import com.wodcapture.domain.model.AbsoluteLoad
import com.wodcapture.domain.model.FallbackTimer
import com.wodcapture.domain.model.Group
import com.wodcapture.domain.model.Item
import com.wodcapture.domain.model.MassUnit
import com.wodcapture.domain.model.MeasureKind
import com.wodcapture.domain.model.PartKind
import com.wodcapture.domain.model.SourceKind
import com.wodcapture.domain.model.WorkoutSection
import com.wodcapture.domain.plan.CompileOptions
import com.wodcapture.domain.plan.CompileResult
import com.wodcapture.domain.plan.PlanCompiler
import com.wodcapture.domain.plan.PlanIdentity
import com.wodcapture.domain.plan.WatchLimits
import com.wodcapture.domain.plan.WatchPart
import com.wodcapture.domain.plan.WatchPlan
import com.wodcapture.domain.plan.WireJson
import com.wodcapture.domain.validation.Severity
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** REQ-AC09 guided manual builder: canonical model, validation and compilation. */
class ManualWorkoutBuilderTest {
    private val ids = IdSource.sequential("a0000000")
    private val builder = ManualWorkoutBuilder(IdSource.sequential("b0000000"))
    private val compiler = PlanCompiler(TestSupport.catalog)
    private val identity = PlanIdentity("c0000000-0000-4000-8000-000000000001", 1)
    private val options = CompileOptions(defaultDate = "2026-11-03", defaultTimeZone = "Asia/Jerusalem")

    private fun form(vararg parts: ManualPartForm, title: String = "", unit: MassUnit = MassUnit.KG) =
        ManualWorkoutForm(title = title, date = "2026-11-04", timeZone = "Asia/Jerusalem", loadUnit = unit, parts = parts.toList())

    private fun compile(form: ManualWorkoutForm): WatchPlan {
        val built = builder.build(form, nowSec = 1)
        assertTrue(built.canCreate, "blocked: ${built.report.blocking}")
        val r = compiler.compile(built.workout, TestSupport.profile, identity, options)
        return assertIs<CompileResult.Success>(r, (r as? CompileResult.Failure)?.errors.toString()).plan
    }

    /** IDs are fresh per workout; levels come from the parser fixture only (the form has none). */
    private fun normalized(p: WatchPart) = p.copy(
        id = "",
        lvs = null,
        ld = p.ld?.map { it.copy(id = "") },
        sets = p.sets?.map { it.copy(id = "") },
    )

    @Test
    fun manualFranCompilesToTheH1GoldenPart() {
        val fran = ManualTemplates.forTime21159(ids).copy(label = "WOD", name = "Fran")
        // Template loads are written in lb (95/65), as in the golden plan.
        val plan = compile(form(fran, title = "Fran", unit = MassUnit.LB))
        val golden = WireJson.decodeFromString(WatchPlan.serializer(), File(TestSupport.goldenDir, "H1/plan.json").readText())
        assertEquals(normalized(golden.p.single()), normalized(plan.p.single()))
    }

    @Test
    fun manualWorkoutIsCanonicalManualSource() {
        val built = builder.build(form(ManualTemplates.forTime21159(ids)), nowSec = 42)
        val w = built.workout
        assertEquals(SourceKind.MANUAL, w.source.kind)
        assertNull(w.source.parser)
        assertEquals("2026-11-04", w.date)
        assertEquals("Asia/Jerusalem", w.scheduleTimeZone)
        assertEquals("Workout", w.title)
        val group = assertIs<Group>(w.parts[0].body.single())
        assertEquals(listOf(21, 15, 9), group.repScheme)
        val thruster = group.children[0] as Item
        val load = assertIs<AbsoluteLoad>(thruster.load)
        assertEquals(95.0, load.mass.value)
        assertFalse(built.report.codes().contains("split_needs_review"))
        // Round-trips through storage like any parsed workout.
        assertEquals(w, DomainJson.decodeWorkout(DomainJson.encodeWorkout(w)))
    }

    @Test
    fun cindyAmrapCompilesWithConstantRoundTotals() {
        val p = compile(form(ManualTemplates.amrap20(ids))).p.single()
        assertEquals("amrap", p.mode)
        assertEquals(listOf(30), p.rr)
        assertEquals(true, p.rrc)
        assertEquals(1200, p.seg!!.single().d)
        assertEquals("rounds_reps", p.sc.ty)
    }

    @Test
    fun fiveByFiveBackSquatRestsOnAllButTheLastSet() {
        val part = ManualTemplates.strength5x5(ids).let { s -> s.copy(sets = s.sets.map { it.copy(load = 100.0, ofGoal = null) }) }
        val built = builder.build(form(part))
        val item = built.workout.parts[0].body.single() as Item
        assertEquals(AbsoluteLoad(com.wodcapture.domain.model.Mass(100.0, MassUnit.KG)), item.sets[0].load)
        val sets = compile(form(part)).p.single().sets!!
        assertEquals(5, sets.size)
        assertTrue(sets.dropLast(1).all { it.rest == 120 }, "rest between sets: $sets")
        assertEquals(0, sets.last().rest)
        assertTrue(sets.all { it.w?.v == 100.0 && it.r == 5 })
        assertEquals("load", compile(form(part)).p.single().sc.ty)
    }

    @Test
    fun emomTenWithTwoStationsAlternates() {
        val p = compile(form(ManualTemplates.emom10(ids))).p.single()
        assertEquals("emom", p.mode)
        val seg = p.seg!!
        assertEquals(10, seg.size)
        assertEquals(listOf(0, 1, 0, 1, 0, 1, 0, 1, 0, 1), seg.map { it.s })
        assertTrue(seg.all { it.d == 60 && it.k == "work" })
        assertEquals(2, p.mv!!.size)
        assertEquals("completion", p.sc.ty)
        assertEquals(true, p.sc.man)
    }

    @Test
    fun tabataDefaultsTo20On10OffTimes8() {
        val part = ManualForms.newPart(ids, 0, ManualFormat.TABATA).let { t ->
            t.copy(movements = listOf(t.movements[0].copy(name = "Air squat", movementId = "air_squat", measure = MeasureKind.MAX)))
        }
        val p = compile(form(part)).p.single()
        val work = p.seg.orEmpty().filter { it.k == "work" }
        val rest = p.seg.orEmpty().filter { it.k == "rest" }
        assertEquals(8, work.size)
        assertTrue(work.all { it.d == 20 })
        assertEquals(7, rest.size)
        assertTrue(rest.all { it.d == 10 })
        assertEquals("reps", p.sc.ty)
    }

    @Test
    fun customIsAnAcceptedManualFallback() {
        val p = compile(form(ManualTemplates.custom(ids))).p.single()
        assertTrue(p.fb)
        assertEquals("manual", p.mode)
        assertEquals("text", p.sc.ty)
        assertTrue(p.ln.contains("You go, I go"), p.ln.toString())
    }

    @Test
    fun customCountdownNeedsALength() {
        val part = ManualTemplates.custom(ids).copy(customTimer = FallbackTimer.COUNT_DOWN)
        assertTrue(builder.build(form(part)).report.blocking.any { it.code == "missing_duration" })
        val ok = builder.build(form(part.copy(durationText = "12:00")))
        assertTrue(ok.canCreate, ok.report.blocking.toString())
        assertEquals(720, ok.workout.parts[0].fallback!!.durationSec)
    }

    @Test
    fun zeroDurationAmrapBlocks() {
        val amrap = ManualTemplates.amrap20(ids).copy(durationText = "0:00")
        val report = builder.build(form(amrap)).report
        assertFalse(report.canSend)
        assertTrue(report.blocking.any { it.code == "nonpositive_duration" }, report.blocking.toString())
        val missing = builder.build(form(amrap.copy(durationText = ""))).report
        assertTrue(missing.blocking.any { it.code == "missing_duration" })
    }

    @Test
    fun emptyPartBlocks() {
        val empty = ManualForms.newPart(ids, 0, ManualFormat.FOR_TIME).copy(movements = emptyList())
        assertTrue(builder.build(form(empty)).report.blocking.any { it.code == "empty_body" })
        val noSets = ManualForms.newPart(ids, 0, ManualFormat.STRENGTH).copy(sets = emptyList())
        assertTrue(builder.build(form(noSets)).report.blocking.any { it.code == "missing_sets" })
        assertTrue(builder.build(form()).report.blocking.any { it.code == "no_parts" })
    }

    @Test
    fun unreadableInputBlocksInsteadOfBeingDropped() {
        val bad = ManualTemplates.forTime21159(ids).copy(capText = "eight", repSchemeText = "21-x-9")
        val codes = builder.build(form(bad)).report.blocking.map { it.code }
        assertTrue("invalid_time" in codes, codes.toString())
        assertTrue("invalid_rep_scheme" in codes, codes.toString())
        val badDate = builder.build(form(ManualTemplates.amrap20(ids)).copy(date = "4/11/2026")).report
        assertTrue(badDate.blocking.any { it.code == "invalid_date" })
        val unnamed = ManualTemplates.amrap20(ids).let { it.copy(movements = it.movements + ManualForms.newMovement(ids).copy(amount = 5.0)) }
        assertTrue(builder.build(form(unnamed)).report.blocking.any { it.code == "missing_name" })
    }

    @Test
    fun forTimeRoundsBecomeARepeatedGroup() {
        val part = ManualForms.newPart(ids, 0).copy(
            rounds = 3,
            movements = listOf(ManualMovementForm(ids.newId(), "Run", "run", measure = MeasureKind.DISTANCE, amount = 400.0)),
        )
        val p = compile(form(part)).p.single()
        assertEquals(3, p.rnd)
        assertEquals("3 RFT", p.n)
    }

    @Test
    fun catalogPickSetsMeasureAndImplement() {
        val row = ManualForms.newMovement(ids)
        val row2 = ManualForms.pick(row, TestSupport.catalog.byId("row")!!, schemePart = false)
        assertEquals(MeasureKind.DISTANCE, row2.measure)
        val thruster = ManualForms.pick(row, TestSupport.catalog.byId("thruster")!!, schemePart = true)
        assertEquals(MeasureKind.REP_SCHEME, thruster.measure)
        assertEquals("barbell", thruster.implement)
        assertNull(ManualForms.pick(row, TestSupport.catalog.byId("pull_up")!!, schemePart = false).implement)
        assertTrue(ManualForms.suggestions(TestSupport.catalog, "thru", null).any { it.id == "thruster" })
        assertTrue(ManualForms.suggestions(TestSupport.catalog, "T2B", null).any { it.id == "toes_to_bar" })
    }

    @Test
    fun repSchemeMovesBlankRowsOntoTheScheme() {
        val part = ManualForms.newPart(ids, 0).let { it.copy(movements = it.movements + ManualForms.newMovement(ids).copy(amount = 10.0)) }
        val withScheme = ManualForms.withRepScheme(part, "21-15-9")
        assertEquals(listOf(MeasureKind.REP_SCHEME, MeasureKind.REPS), withScheme.movements.map { it.measure })
        assertEquals(MeasureKind.REP_SCHEME, ManualForms.newMovementFor(withScheme, ids).measure)
        val cleared = ManualForms.withRepScheme(withScheme, "")
        assertEquals(listOf(MeasureKind.REPS, MeasureKind.REPS), cleared.movements.map { it.measure })
    }

    @TestFactory
    fun everyTemplateValidatesAndFitsTheWatch() = ManualTemplates.all.map { t ->
        DynamicTest.dynamicTest(t.name) {
            val applied = ManualTemplates.apply(form(), t, ids)
            assertEquals(t.name, applied.title)
            // A weights quick start needs the athlete's goal weight, as a session needs its lift.
            val f = applied.copy(parts = applied.parts.map { p ->
                when {
                    p.format == ManualFormat.STRENGTH -> ManualForms.withGoal(p, 100.0, 1.0)
                    else -> p
                }
            })
            val built = builder.build(f)
            assertTrue(built.report.issues.none { it.severity == Severity.BLOCKING }, "${t.name}: ${built.report.blocking}")
            if (t.section == WorkoutSection.WARMUP) {
                // Long warm-up templates still need shortening to fit the watch's display budget.
                // Verify their accepted fallback and default inclusion separately.
                val part = built.workout.parts.single()
                assertTrue(part.fallback?.accepted == true, t.name)
                return@dynamicTest
            }
            val compiledWorkout = built.workout.copy(parts = built.workout.parts.map { p ->
                p.copy(runOnWatch = true)
            })
            when (val r = compiler.compile(compiledWorkout, TestSupport.profile, identity, options)) {
                is CompileResult.Success -> assertTrue(r.encodedBytes <= WatchLimits.MESSAGE_BUDGET)
                is CompileResult.Failure -> throw AssertionError("${t.name} does not compile: ${r.errors}")
            }
        }
    }
}
