package com.wodcapture.domain.plan

import com.wodcapture.domain.Fixtures
import com.wodcapture.domain.TestSupport
import com.wodcapture.domain.model.BodyTree
import com.wodcapture.domain.model.ForTime
import com.wodcapture.domain.model.Item
import com.wodcapture.domain.model.MassUnit
import com.wodcapture.domain.model.Part
import com.wodcapture.domain.model.RepTarget
import com.wodcapture.domain.model.SexSelection
import com.wodcapture.domain.model.StrengthSet
import com.wodcapture.domain.model.Workout
import com.wodcapture.domain.protocol.EnvelopeCodec
import com.wodcapture.domain.protocol.WatchPlanCodec
import com.wodcapture.domain.review.ReviewActions
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** REQ-DM14 plan compiler; cross-checked key-for-key against shared/fixtures/wire/valid/plan.put.json. */
class PlanCompilerTest {
    private val compiler = PlanCompiler(TestSupport.catalog)

    private fun success(r: CompileResult) = assertIs<CompileResult.Success>(r, (r as? CompileResult.Failure)?.errors.toString())

    /** The shared wire fixture's inputs: strength 5x3 @80% (1RM 125 kg) + Fran, male, kg. */
    private fun sharedFixtureWorkout(): Workout {
        val strength = Fixtures.h2.expected.parts[0]
        val sq = strength.body[0] as Item
        val strengthPart = strength.copy(
            id = "30000000-0000-4000-8000-000000000002",
            body = listOf(sq.copy(sets = sq.sets.mapIndexed { i, s -> s.copy(id = "4000000${i + 1}-0000-4000-8000-000000000001") })),
        )
        val fran = Fixtures.h1.expected.parts[0]
        val thruster = BodyTree.items(fran.body)[0]
        val franPart = fran.copy(
            id = "30000000-0000-4000-8000-000000000001",
            body = BodyTree.map(fran.body) { n -> if (n.id == thruster.id) (n as Item).copy(id = "50000000-0000-4000-8000-000000000001") else n },
            levels = fran.levels.map { l -> l.copy(overrides = l.overrides.map { o -> if (o.targetId == thruster.id) o.copy(targetId = "50000000-0000-4000-8000-000000000001") else o }) },
        )
        val w = Fixtures.h1.expected.copy(id = "20000000-0000-4000-8000-000000000001", revision = 3, parts = listOf(strengthPart, franPart))
        return ReviewActions.confirmSplits(w)
    }

    @Test
    fun franAndStrengthMatchTheSharedPlanPutFixture() {
        val expected = TestSupport.sharedJson("fixtures/wire/valid/plan.put.json").getValue("p").jsonObject
        val r = success(compiler.compile(sharedFixtureWorkout(), TestSupport.profile, PlanIdentity("10000000-0000-4000-8000-000000000001", 2)))
        assertEquals(expected, WatchPlanCodec.toJson(r.plan))
        assertEquals(EnvelopeCodec.planPutSize(r.plan), r.encodedBytes)
    }

    @Test
    fun lbAthleteSeesBoardValuesWithoutConversion() {
        val lbProfile = TestSupport.profile.copy(displayUnit = MassUnit.LB)
        val r = success(compiler.compile(Fixtures.h1.review(Fixtures.h1.expected), lbProfile, Fixtures.h1.identity))
        val part = r.plan.p[0]
        assertEquals("Thruster 95 lb", part.ln[2])
        assertEquals(95.0, part.ld!![0].v)
        assertEquals("lb", r.plan.u)
    }

    @Test
    fun femaleScaledLevelUsesOverrides() {
        val w = Fixtures.h1.review(Fixtures.h1.expected)
        val r = success(compiler.compile(w, TestSupport.profile.copy(sex = SexSelection.FEMALE), Fixtures.h1.identity, CompileOptions(levels = mapOf(w.parts[0].id to "Scaled"))))
        val part = r.plan.p[0]
        assertEquals("Scaled", part.lv)
        // "(Scaled 45 lb)" would exceed 28 characters, so only the resolved load is shown.
        assertEquals("Thruster 20 kg", part.ln[2])
        assertEquals("Jumping pull-up", part.ln[3])
    }

    @Test
    fun overBudgetPlansAreRejectedWithAReason() {
        val r = compiler.compile(Fixtures.h2.review(Fixtures.h2.expected), TestSupport.profile, Fixtures.h2.identity, CompileOptions(messageBudget = 500))
        val f = assertIs<CompileResult.Failure>(r)
        assertEquals("plan_too_large", f.errors.single().code)
        assertTrue(f.errors.single().message.contains("bytes"))
    }

    @Test
    fun tooManyPartsAndSetsAreRejected() {
        val p = Fixtures.h10.expected.parts[0]
        val seven = Fixtures.h10.expected.copy(parts = (1..7).map { i -> p.copy(id = "f9900000-0000-4000-8000-00000000000$i") })
        val f = assertIs<CompileResult.Failure>(compiler.compile(seven, TestSupport.profile, Fixtures.h10.identity))
        assertTrue(f.errors.any { it.code == "too_many_parts" })
        val strength = Fixtures.h2.expected.parts[0]
        val sq = strength.body[0] as Item
        val many = strength.copy(body = listOf(sq.copy(sets = (1..31).map { StrengthSet("f9800000-0000-4000-8000-%012d".format(it), RepTarget(1)) })))
        val f2 = assertIs<CompileResult.Failure>(compiler.compile(Fixtures.h2.expected.copy(parts = listOf(many)), TestSupport.profile, Fixtures.h2.identity))
        assertTrue(f2.errors.any { it.code == "too_many_sets" })
    }

    @Test
    fun warmupsDoNotRunOnTheWatchByDefault() {
        val warm = Fixtures.h10.expected.parts[0].copy(id = "f9700000-0000-4000-8000-000000000001", kind = com.wodcapture.domain.model.PartKind.WARMUP)
        val w = Fixtures.h10.expected.copy(parts = listOf(warm) + Fixtures.h10.expected.parts)
        val r = success(compiler.compile(w, TestSupport.profile, Fixtures.h10.identity))
        assertEquals(1, r.plan.p.size)
    }

    @Test
    fun longMovementNamesShortenViaCatalogNeverTruncate() {
        val part: Part = Fixtures.h4.review(Fixtures.h4.expected).parts[0]
        val r = success(compiler.compile(Fixtures.h4.expected.copy(parts = listOf(part)), TestSupport.profile, Fixtures.h4.identity))
        assertTrue(r.plan.p[0].ln.all { it.length <= WatchLimits.LINE })
        assertTrue(r.plan.p[0].mv!!.contains("3 LBO DB +3/rd"))
    }

    @Test
    fun forTimeWithoutCapOmitsCapScore() {
        val w = Fixtures.h1.review(Fixtures.h1.expected)
        val noCap = w.copy(parts = listOf(w.parts[0].copy(format = ForTime())))
        val part = success(compiler.compile(noCap, TestSupport.profile, Fixtures.h1.identity)).plan.p[0]
        assertEquals(null, part.sc.cap)
        assertEquals(null, part.seg!![0].cap)
    }
}
