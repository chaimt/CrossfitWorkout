package com.wodcapture.domain.scoring

import com.wodcapture.domain.catalog.MovementCatalog
import com.wodcapture.domain.load.MassMath
import com.wodcapture.domain.model.AbsoluteLoad
import com.wodcapture.domain.model.BodyTree
import com.wodcapture.domain.model.BuildToMax
import com.wodcapture.domain.model.Item
import com.wodcapture.domain.model.Mass
import com.wodcapture.domain.model.MassUnit
import com.wodcapture.domain.model.Part
import com.wodcapture.domain.model.ResultSource
import com.wodcapture.domain.model.ResultStatus
import com.wodcapture.domain.model.Score
import com.wodcapture.domain.model.SetStatus
import com.wodcapture.domain.model.Workout
import com.wodcapture.domain.model.WorkoutResult
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Heaviest weight for one lift across the workouts (REQ-AL07). [load] is per implement, in the display unit. */
data class TopLift(
    /** Catalog movement ID, or the lower-cased name of a free-text lift. */
    val key: String,
    val name: String,
    val load: Mass,
    /** Reps of the set that set the record; `null` for a manual load score without sets. */
    val reps: Int?,
    val date: LocalDate,
    val workoutId: String,
    val workoutTitle: String,
)

/**
 * Top weight per lift across the workout list (REQ-AL07). Every workout's strength sets with an
 * absolute weight count, dated by the workout's scheduled date (else its creation day). Logged
 * results add to that: their completed sets (status done and at least the prescribed reps, the
 * same rule as [ScoreCalculator.bestLoad]) and a load score for a part that has a single lift, so
 * a weight heavier than planned still shows. Ties go to more reps, then the earliest date — the
 * day the weight was first reached.
 */
object TopLifts {
    private data class Candidate(val item: Item, val kg: Double, val load: Mass, val reps: Int?, val date: LocalDate, val workout: Workout)

    /** [workouts] are the library's saved workouts; results of workouts not in it are ignored. */
    fun compute(
        workouts: List<Workout>,
        results: List<WorkoutResult>,
        catalog: MovementCatalog,
        unit: MassUnit,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<TopLift> {
        val byId = workouts.associateBy { it.id }
        val planned = workouts.flatMap { planned(it, zone) }
        val logged = results.flatMap { r -> byId[r.workoutId]?.let { candidates(r, it, zone) }.orEmpty() }
        return (planned + logged)
            .groupBy { key(it.item) }
            .map { (key, group) ->
                val best = group.sortedWith(compareByDescending<Candidate> { it.kg }.thenByDescending { it.reps ?: 0 }.thenBy { it.date }).first()
                TopLift(
                    key = key,
                    name = catalog.byId(best.item.movementId)?.name ?: best.item.displayName ?: best.item.name,
                    load = MassMath.convertPhysical(best.load, unit).let { Mass(MassMath.clean(it.value), unit) },
                    reps = best.reps,
                    date = best.date,
                    workoutId = best.workout.id,
                    workoutTitle = best.workout.title,
                )
            }
            .sortedBy { it.name.lowercase() }
    }

    /** The workout's own set weights; a set without a load inherits the item's. */
    private fun planned(w: Workout, zone: ZoneId): List<Candidate> {
        val date = scheduled(w) ?: w.createdAt?.let { Instant.ofEpochSecond(it).atZone(zone).toLocalDate() } ?: return emptyList()
        return w.parts.flatMap { part -> BodyTree.items(part.body) }.flatMap { item ->
            item.sets.mapNotNull { s ->
                val load = ((s.load ?: item.load) as? AbsoluteLoad)?.mass?.takeIf { it.isMeasured() } ?: return@mapNotNull null
                Candidate(item, MassMath.toKgPhysical(load), load, s.reps.value?.takeUnless { s.reps.max }, date, w)
            }
        }
    }

    private fun candidates(r: WorkoutResult, w: Workout, zone: ZoneId): List<Candidate> {
        if (r.status == ResultStatus.SKIPPED) return emptyList()
        val part = w.parts.firstOrNull { it.id == r.partId } ?: return emptyList()
        val items = BodyTree.items(part.body)
        val date = date(r, w, zone)
        val fromSets = r.sets
            .filter { it.status == SetStatus.DONE && it.load.isMeasured() }
            .filter { s -> s.targetReps == null || (s.actualReps ?: 0) >= s.targetReps }
            .mapNotNull { s ->
                val item = items.firstOrNull { i -> i.id == s.setId || i.sets.any { it.id == s.setId } } ?: return@mapNotNull null
                Candidate(item, MassMath.toKgPhysical(s.load!!), s.load, s.actualReps ?: s.targetReps, date, w)
            }
        return fromSets + listOfNotNull(fromScore(r, part, items, date, w))
    }

    /** A load score (typed on the phone, or the watch's build-to-max result) for a one-lift part. */
    private fun fromScore(r: WorkoutResult, part: Part, items: List<Item>, date: LocalDate, w: Workout): Candidate? {
        val score = r.score?.score as? Score.Load ?: return null
        val lifts = items.filter { it.sets.isNotEmpty() }.ifEmpty { items }
        val item = lifts.singleOrNull() ?: return null
        val load = Mass(score.value, score.unit)
        if (!load.isMeasured()) return null
        return Candidate(item, MassMath.toKgPhysical(load), load, (part.format as? BuildToMax)?.repsPerSet, date, w)
    }

    /**
     * The day the lift was done. A watch result has its start time; a manual result is logged
     * after the fact, so the workout's scheduled date is the better guess when it has one.
     */
    private fun date(r: WorkoutResult, w: Workout, zone: ZoneId): LocalDate {
        val scheduled = scheduled(w)
        if (r.source == ResultSource.MANUAL && scheduled != null) return scheduled
        return Instant.ofEpochSecond(r.startedAt).atZone(zone).toLocalDate()
    }

    private fun scheduled(w: Workout): LocalDate? = w.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    private fun key(i: Item): String = i.movementId ?: i.name.trim().lowercase()

    private fun Mass?.isMeasured(): Boolean = this != null && unit != MassUnit.UNSPECIFIED && value > 0
}
