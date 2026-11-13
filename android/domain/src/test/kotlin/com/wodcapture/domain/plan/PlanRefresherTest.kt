package com.wodcapture.domain.plan

import com.wodcapture.domain.Fixtures
import com.wodcapture.domain.TestSupport
import com.wodcapture.domain.model.*
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.*

class PlanRefresherTest {
    private val compiler = PlanCompiler(TestSupport.catalog)
    private val refresher = PlanRefresher(compiler)
    private val today = LocalDate.of(2026, 11, 13)
    private val identity = PlanIdentity("10000000-0000-4000-8000-000000000001", 3)
    private fun workout(date: String? = today.toString()) = Fixtures.h1.review(Fixtures.h1.expected).copy(date = date)
    private fun compile(w: Workout, levels: Map<String, String> = emptyMap()): WatchPlan =
        assertIs<CompileResult.Success>(compiler.compile(w, TestSupport.profile, identity,
            CompileOptions(levels = levels, defaultDate = today.toString(), defaultTimeZone = "Asia/Jerusalem"))).plan

    @Test fun changedTodayAndFuturePlansKeepIdentityAndAdvanceRevisionOnce() {
        for (date in listOf(today.toString(), today.plusDays(1).toString())) {
            val w = workout(date)
            val old = compile(w)
            val changed = w.copy(title = "Updated workout", revision = w.revision + 1)
            val update = assertIs<PlanRefreshResult.Updated>(refresher.refresh(old, changed, TestSupport.profile, today)).plan
            assertEquals(old.id, update.id)
            assertEquals(old.rev + 1, update.rev)
            assertEquals("Updated workout", update.ti)
            assertEquals(PlanRefreshResult.Unchanged, refresher.refresh(update, changed, TestSupport.profile, today))
        }
    }

    @Test fun pastPlansRemainHistoricalEvenWhenTheirWorkoutIsRescheduled() {
        val w = workout(today.minusDays(1).toString())
        val old = compile(w)
        val changed = w.copy(title = "Changed", date = today.plusDays(1).toString(), revision = w.revision + 1)
        assertEquals(PlanRefreshResult.Historical, refresher.refresh(old, changed, TestSupport.profile, today))
        val current = workout()
        assertEquals(PlanRefreshResult.Historical, refresher.refresh(compile(current), current.copy(date = w.date), TestSupport.profile, today))
    }

    @Test fun unchangedUndatedPlansKeepTheirSentScheduleAndLevelChoice() {
        val w = workout(null)
        val old = compile(w, mapOf(w.parts[0].id to "Scaled"))
        assertEquals(PlanRefreshResult.Unchanged, refresher.refresh(old, w, TestSupport.profile, today))
        val updated = assertIs<PlanRefreshResult.Updated>(refresher.refresh(old, w.copy(title = "New title"), TestSupport.profile, today)).plan
        assertEquals(old.date, updated.date)
        assertEquals(old.tz, updated.tz)
        assertEquals("Scaled", updated.p[0].lv)
    }

    @Test fun syncRecompilesOldStrengthAndMetconPlanToIncludeDefaultWarmupFirst() {
        val metcon = workout()
        val strength = Fixtures.h2.review(Fixtures.h2.expected).parts[0]
        val warmup = Part(id = "30000000-0000-4000-8000-000000000003", label = "WU", kind = PartKind.WARMUP,
            format = NotForTime, scoring = Scoring(type = ScoreType.NONE),
            fallback = Fallback(accepted = true, reason = "For quality", timer = FallbackTimer.COUNT_UP),
            body = listOf(Item(id = "50000000-0000-4000-8000-000000000003", name = "Air squat", measure = Measure(MeasureKind.REPS, 10.0))))
        val current = metcon.copy(parts = listOf(metcon.parts[0], strength, warmup))
        val old = compile(current.copy(parts = current.parts.map { if (it.id == warmup.id) it.copy(runOnWatch = false) else it }))
        assertEquals(2, old.p.size)
        val updated = assertIs<PlanRefreshResult.Updated>(refresher.refresh(old, current, TestSupport.profile, today)).plan
        assertEquals(listOf(warmup.id, strength.id, metcon.parts[0].id), updated.p.map { it.id })
    }

    @Test fun invalidChangesAreRejectedWithoutReplacingTheOldPlan() {
        val w = workout()
        val old = compile(w)
        assertIs<PlanRefreshResult.Rejected>(refresher.refresh(old, w.copy(title = "Too long ".repeat(20)), TestSupport.profile, today))
        assertEquals(identity.planRevision, old.rev)
    }
}
