package com.wodcapture.domain.review

import com.wodcapture.domain.IdSource
import com.wodcapture.domain.model.BodyNode
import com.wodcapture.domain.model.BodyTree
import com.wodcapture.domain.model.Fallback
import com.wodcapture.domain.model.FallbackTimer
import com.wodcapture.domain.model.Group
import com.wodcapture.domain.model.Item
import com.wodcapture.domain.model.Level
import com.wodcapture.domain.model.Part
import com.wodcapture.domain.model.Rest
import com.wodcapture.domain.model.Workout
import com.wodcapture.domain.validation.WorkoutValidator

/**
 * Explicit review decisions (REQ-AE02, §4.4, REQ-DM18). Each returns a new workout; callers bump
 * the revision via [newRevision] when saving an edit (REQ-DM02, REQ-DM15).
 */
object ReviewActions {

    /** No-op kept for old callers: single-value model has no M/F splits to confirm. */
    fun confirmSplits(w: Workout): Workout = w

    /** No-op kept for old callers: single-value model has no M/F splits to swap. */
    fun swapSplit(w: Workout, nodeId: String): Workout = w

    /** Accepts the labeled manual-timer fallback for [partId] (REQ-DM18). */
    fun acceptFallback(w: Workout, partId: String, timer: FallbackTimer, durationSec: Int? = null, capSec: Int? = null): Workout =
        w.copy(parts = w.parts.map { p ->
            if (p.id != partId) p else p.copy(
                fallback = Fallback(true, timer, durationSec, capSec, WorkoutValidator.requiresFallback(p) ?: "Chosen in review"),
            )
        })

    /** Accepts fallbacks for every part that needs one, with each format's natural timer. */
    fun acceptRequiredFallbacks(w: Workout): Workout = w.parts.fold(w) { acc, p ->
        if (WorkoutValidator.requiresFallback(p) == null || p.fallback?.accepted == true) acc
        else acceptFallback(acc, p.id, FallbackTimer.COUNT_UP)
    }

    /** Saves an edit as a new revision; node IDs stay stable (REQ-DM15). */
    fun newRevision(w: Workout, edited: Workout, nowSec: Long): Workout =
        edited.copy(id = w.id, revision = w.revision + 1, updatedAt = nowSec)

    /** Duplicates a workout for the manual builder (REQ-AC08): new IDs everywhere. */
    fun duplicate(w: Workout, ids: IdSource, nowSec: Long): Workout {
        val map = mutableMapOf<String, String>()
        fun id(old: String) = map.getOrPut(old) { ids.newId() }
        fun node(n: BodyNode): BodyNode = when (n) {
            is Item -> n.copy(id = id(n.id), sets = n.sets.map { it.copy(id = id(it.id)) })
            is Group -> n.copy(id = id(n.id), children = n.children.map(::node))
            is Rest -> n.copy(id = id(n.id))
        }
        fun part(p: Part) = p.copy(id = id(p.id), body = p.body.map(::node))
        val parts = w.parts.map(::part)
        val remapped = parts.mapIndexed { i, p ->
            p.copy(levels = w.parts[i].levels.map { l: Level -> l.copy(overrides = l.overrides.map { o -> o.copy(targetId = map[o.targetId] ?: o.targetId) }) })
        }
        return w.copy(id = ids.newId(), revision = 1, parts = remapped, createdAt = nowSec, updatedAt = nowSec,
            source = w.source.copy(kind = com.wodcapture.domain.model.SourceKind.DUPLICATE))
    }
}
