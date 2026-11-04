package com.wodcapture.domain.plan

import com.wodcapture.domain.DomainJson
import com.wodcapture.domain.Fixtures
import com.wodcapture.domain.HFixture
import com.wodcapture.domain.TestSupport
import com.wodcapture.domain.protocol.WatchPlanCodec
import com.wodcapture.domain.validation.WorkoutValidator
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Compiler golden plans for Appendix H (REQ-TS01, REQ-TS08): every fixture's expected parse is
 * schema-stable JSON, review-free compilation shows the explicit MVP fallback expectations, and
 * the reviewed compile equals the committed `plan.json`.
 */
class GoldenFixturesTest {
    private val compiler = PlanCompiler(TestSupport.catalog)
    private val pretty = Json(from = WireJson) { prettyPrint = true; prettyPrintIndent = "  " }
    private val options = CompileOptions(defaultDate = "2026-11-03", defaultTimeZone = "Asia/Jerusalem")

    @TestFactory
    fun goldenPlans() = Fixtures.all.map { fx -> DynamicTest.dynamicTest(fx.name) { checkFixture(fx) } }

    private fun checkFixture(fx: HFixture) {
        assertTrue(File(TestSupport.sharedDir, "fixtures/${fx.name}/input.txt").isFile, "shared input for ${fx.name}")
        val json = DomainJson.encodeWorkout(fx.expected) + "\n"
        TestSupport.assertGolden("${fx.name}/expected.workout.json", json)
        // The golden file must decode back to the same workout (stable schema).
        assertEquals(fx.expected, DomainJson.decodeWorkout(File(TestSupport.goldenDir, "${fx.name}/expected.workout.json").readText()))
        val reviewed = fx.review(fx.expected)
        val report = WorkoutValidator.validate(reviewed)
        assertTrue(report.canSend, "${fx.name} reviewed workout blocked: ${report.blocking}")
        when (val r = compiler.compile(reviewed, fx.profile, fx.identity, options)) {
            is CompileResult.Success -> {
                assertTrue(r.encodedBytes <= WatchLimits.MESSAGE_BUDGET)
                WatchPlanCodec.validate(r.plan)
                val text = pretty.encodeToString(WatchPlan.serializer(), r.plan) + "\n"
                TestSupport.assertGolden("${fx.name}/plan.json", text)
            }
            is CompileResult.Failure -> fail("${fx.name} failed to compile: ${r.errors}")
        }
    }

    /** Parts that need a reviewed fallback must not compile before review (REQ-DM18, TS08). */
    @TestFactory
    fun unreviewedExpectations() = listOf(
        Fixtures.h1 to "split_unconfirmed",
        Fixtures.h2 to "fallback_required",
        Fixtures.h5 to "fallback_required",
        Fixtures.h6 to "fallback_required",
        Fixtures.h8 to "fallback_required",
    ).map { (fx, code) ->
        DynamicTest.dynamicTest("${fx.name} needs review: $code") {
            val r = compiler.compile(fx.expected, fx.profile, fx.identity, options)
            assertTrue(r is CompileResult.Failure && r.errors.any { it.code == code }, "${fx.name}: $r")
        }
    }

    @TestFactory
    fun fixturesWithoutReviewCompile() = listOf(Fixtures.h3, Fixtures.h9, Fixtures.h10).map { fx ->
        DynamicTest.dynamicTest(fx.name) {
            assertTrue(compiler.compile(fx.expected, fx.profile, fx.identity, options) is CompileResult.Success)
        }
    }
}
