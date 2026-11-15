package com.wodcapture.domain.review

import com.wodcapture.domain.catalog.MovementCatalog
import com.wodcapture.domain.load.MassMath
import com.wodcapture.domain.model.BodyTree
import com.wodcapture.domain.model.Item
import com.wodcapture.domain.model.Mass
import com.wodcapture.domain.model.MassUnit
import com.wodcapture.domain.model.Workout
import com.wodcapture.domain.model.WorkoutResult
import com.wodcapture.domain.scoring.TopLift

/** Where a missing-load suggestion came from (REQ-AE07). */
enum class SuggestionSource {
    LAST_METCON,
    TOP_LIFT,
    REP_MAX,
}

/** A tap-to-apply weight for an item that currently has no load. Weight only, never reps. */
data class LoadSuggestion(
    val mass: Mass,
    val source: SuggestionSource,
    val detail: String? = null,
)

/**
 * Suggests a fill for loadable metcon items with no load (REQ-AE07, owner request 2026-11-15):
 * last metcon weight actually logged for that lift first, then the top lift / measured rep max.
 * Never auto-fills: callers show a chip and apply only on tap. Never invents reps.
 */
object LoadSuggestions {
    fun keyOf(item: Item): String = item.movementId ?: item.name.trim().lowercase()

    fun forWorkout(
        workout: Workout,
        catalog: MovementCatalog,
        results: List<WorkoutResult>,
        workoutsById: Map<String, Workout>,
        topLiftsByKey: Map<String, TopLift>,
        unit: MassUnit,
    ): Map<String, LoadSuggestion> {
        val lastMetcon = lastMetconLoads(results, workoutsById, unit)
        val out = mutableMapOf<String, LoadSuggestion>()
        workout.parts.forEach { part ->
            BodyTree.items(part.body).forEach { item ->
                // Metcon items only: strength sets keep their own per-set entry flow (REQ-WS02).
                if (item.load != null || item.sets.isNotEmpty()) return@forEach
                if (catalog.byId(item.movementId)?.loadable == "N") return@forEach
                val key = keyOf(item)
                if (key.isBlank()) return@forEach
                val metcon = lastMetcon[key]
                if (metcon != null) {
                    out[item.id] = LoadSuggestion(metcon, SuggestionSource.LAST_METCON)
                    return@forEach
                }
                val top = topLiftsByKey[key] ?: return@forEach
                val mass = if (top.load.unit == unit) top.load
                else runCatching { MassMath.convertPhysical(top.load, unit).let { Mass(MassMath.clean(it.value), unit) } }.getOrNull()
                    ?: return@forEach
                if (mass.value <= 0) return@forEach
                out[item.id] = LoadSuggestion(
                    mass = mass,
                    source = if (top.manual) SuggestionSource.REP_MAX else SuggestionSource.TOP_LIFT,
                    detail = top.workoutTitle,
                )
            }
        }
        return out
    }

    /**
     * Most recent actual metcon load per lift key, newest result first.
     * Resolves each [WorkoutResult.loads] nodeId through its workout's body so renames
     * and re-parses still match by catalog movement (else lower-cased name).
     */
    private fun lastMetconLoads(
        results: List<WorkoutResult>,
        workoutsById: Map<String, Workout>,
        unit: MassUnit,
    ): Map<String, Mass> {
        val out = mutableMapOf<String, Mass>()
        results.sortedByDescending { it.startedAt }.forEach { r ->
            val workout = workoutsById[r.workoutId] ?: return@forEach
            val part = workout.parts.firstOrNull { it.id == r.partId } ?: return@forEach
            val itemsById = BodyTree.items(part.body).associateBy { it.id }
            r.loads.forEach { obs ->
                val item = itemsById[obs.nodeId] ?: return@forEach
                val key = keyOf(item)
                if (key.isBlank() || out.containsKey(key)) return@forEach
                if (obs.load.unit == MassUnit.UNSPECIFIED || obs.load.value <= 0) return@forEach
                val mass = if (obs.load.unit == unit) obs.load
                else runCatching { MassMath.convertPhysical(obs.load, unit).let { Mass(MassMath.clean(it.value), unit) } }.getOrNull()
                    ?: return@forEach
                out[key] = mass
            }
        }
        return out
    }
}
