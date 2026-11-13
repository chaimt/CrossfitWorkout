package com.wodcapture.domain.plan

import com.wodcapture.domain.model.AthleteProfile
import com.wodcapture.domain.model.Workout
import java.time.LocalDate

sealed interface PlanRefreshResult {
    data class Updated(val plan: WatchPlan) : PlanRefreshResult
    data object Unchanged : PlanRefreshResult
    data object Historical : PlanRefreshResult
    data class Rejected(val errors: List<CompileError>) : PlanRefreshResult
}

/** Recompile an existing today/future plan without changing its identity, schedule, or level choices. */
class PlanRefresher(private val compiler: PlanCompiler) {
    /** [today] is the current date in the existing plan's schedule time zone. */
    fun refresh(previous: WatchPlan, workout: Workout, profile: AthleteProfile, today: LocalDate): PlanRefreshResult {
        // A historical plan is an immutable snapshot; moving its workout to another date
        // does not rewrite it through Sync. Explicit Send remains available.
        if (LocalDate.parse(previous.date).isBefore(today)
            || workout.date?.let { LocalDate.parse(it).isBefore(today) } == true) return PlanRefreshResult.Historical
        val options = CompileOptions(
            levels = previous.p.mapNotNull { part -> part.lv?.let { part.id to it } }.toMap(),
            defaultDate = previous.date,
            defaultTimeZone = previous.tz,
        )
        val current = compiler.compile(workout, profile, PlanIdentity(previous.id, previous.rev), options)
        if (current is CompileResult.Failure) return PlanRefreshResult.Rejected(current.errors)
        if ((current as CompileResult.Success).plan == previous) return PlanRefreshResult.Unchanged
        // Recheck the final envelope size, including any extra revision digit.
        return when (val updated = compiler.compile(workout, profile, PlanIdentity(previous.id, previous.rev + 1), options)) {
            is CompileResult.Success -> PlanRefreshResult.Updated(updated.plan)
            is CompileResult.Failure -> PlanRefreshResult.Rejected(updated.errors)
        }
    }
}
