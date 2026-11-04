package com.wodcapture.app.ui

import com.wodcapture.domain.model.Amrap
import com.wodcapture.domain.model.BodyNode
import com.wodcapture.domain.model.BodyTree
import com.wodcapture.domain.model.BuildToMax
import com.wodcapture.domain.model.Emom
import com.wodcapture.domain.model.ForTime
import com.wodcapture.domain.model.Format
import com.wodcapture.domain.model.Group
import com.wodcapture.domain.model.Intervals
import com.wodcapture.domain.model.Item
import com.wodcapture.domain.model.MaxEffort
import com.wodcapture.domain.model.Part
import com.wodcapture.domain.model.RepTarget
import com.wodcapture.domain.model.ScoreType
import com.wodcapture.domain.model.StrengthSet
import com.wodcapture.domain.model.StrengthSets
import com.wodcapture.domain.model.Tabata
import com.wodcapture.domain.model.Workout

/**
 * Pure edit operations used by the review editor (REQ-AE02). They preserve untouched fields and
 * node IDs; new nodes get new IDs (REQ-DM15).
 */
object Editing {
    fun updatePart(w: Workout, partId: String, f: (Part) -> Part) = w.copy(parts = w.parts.map { if (it.id == partId) f(it) else it })

    fun updateNode(p: Part, nodeId: String, f: (BodyNode) -> BodyNode) = p.copy(body = BodyTree.map(p.body) { if (it.id == nodeId) f(it) else it })

    fun removeNode(p: Part, nodeId: String): Part {
        fun strip(nodes: List<BodyNode>): List<BodyNode> = nodes.filter { it.id != nodeId }.map { if (it is Group) it.copy(children = strip(it.children)) else it }
        return p.copy(body = strip(p.body), levels = p.levels.map { l -> l.copy(overrides = l.overrides.filter { it.targetId != nodeId }) })
    }

    /** Adds an item at the end of [groupId] (or the top level when null). */
    fun addItem(p: Part, groupId: String?, item: Item): Part =
        if (groupId == null) p.copy(body = p.body + item)
        else updateNode(p, groupId) { (it as Group).copy(children = it.children + item) }

    /** Moves a top-level node up or down (reorder, REQ-AE02). */
    fun move(p: Part, nodeId: String, delta: Int): Part {
        val i = p.body.indexOfFirst { it.id == nodeId }
        val j = i + delta
        if (i < 0 || j !in p.body.indices) return p
        val l = p.body.toMutableList()
        l[i] = l[j].also { l[j] = l[i] }
        return p.copy(body = l)
    }

    /** Re-shapes an item's strength sets keeping existing set IDs (REQ-DM15). */
    fun reshapeSets(item: Item, count: Int, reps: Int?, restSec: Int?, newId: () -> String): Item {
        val sets = (0 until count.coerceIn(0, 30)).map { i ->
            val prior = item.sets.getOrNull(i)
            (prior ?: StrengthSet(newId())).copy(reps = RepTarget(reps ?: prior?.reps?.value), restSec = restSec ?: prior?.restSec)
        }
        return item.copy(sets = sets)
    }

    val formatTypes = listOf("for_time", "amrap", "emom", "tabata", "intervals", "max_effort", "strength_sets", "build_to_max")

    fun formatType(f: Format): String = when (f) {
        is ForTime -> "for_time"
        is Amrap -> "amrap"
        is Emom -> "emom"
        is Tabata -> "tabata"
        is Intervals -> "intervals"
        is MaxEffort -> "max_effort"
        is StrengthSets -> "strength_sets"
        is BuildToMax -> "build_to_max"
        else -> "other"
    }

    /** A new format of [type]; the body is untouched (formats only reference it). */
    fun newFormat(type: String): Format = when (type) {
        "for_time" -> ForTime()
        "amrap" -> Amrap(600)
        "emom" -> Emom(60, 10)
        "tabata" -> Tabata()
        "intervals" -> Intervals(rounds = 4, workSec = 240, restSec = 120)
        "max_effort" -> MaxEffort(windowSec = 60)
        "strength_sets" -> StrengthSets()
        else -> BuildToMax()
    }

    fun defaultScore(type: String): ScoreType = when (type) {
        "for_time" -> ScoreType.TIME
        "amrap", "intervals" -> ScoreType.ROUNDS_REPS
        "strength_sets", "build_to_max" -> ScoreType.LOAD
        "max_effort", "tabata" -> ScoreType.REPS
        else -> ScoreType.COMPLETION
    }
}
