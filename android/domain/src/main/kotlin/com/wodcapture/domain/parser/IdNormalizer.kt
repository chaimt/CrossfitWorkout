package com.wodcapture.domain.parser

import com.wodcapture.domain.IdSource
import com.wodcapture.domain.isWireUuid
import com.wodcapture.domain.model.BodyNode
import com.wodcapture.domain.model.Emom
import com.wodcapture.domain.model.Format
import com.wodcapture.domain.model.Group
import com.wodcapture.domain.model.Item
import com.wodcapture.domain.model.ParseResult
import com.wodcapture.domain.model.Part
import com.wodcapture.domain.model.Rest
import com.wodcapture.domain.model.StrengthSets
import com.wodcapture.domain.model.Tabata

/**
 * Replaces the model's short local ids ("p1", "n3") with UUIDs consistently across nodes, format
 * references, overrides and diagnostics (REQ-DM15, REQ-DM17). Valid UUIDs are kept.
 */
class IdNormalizer(private val ids: IdSource) {
    private val map = mutableMapOf<String, String>()

    private fun id(old: String): String = if (isWireUuid(old)) old else map.getOrPut(old) { ids.newId() }

    /** Maps a reference; unknown references are kept so validation reports them as dangling. */
    private fun refId(old: String): String = map[old] ?: old

    fun normalize(r: ParseResult): ParseResult {
        val w = r.workout
        // First pass assigns ids to every node so references can be rewritten in the second.
        w.parts.forEach { p -> id(p.id); assign(p.body); p.levels.forEach { l -> l.overrides.forEach { o -> o.replace?.let { assign(listOf(it)) } } } }
        val parts = w.parts.map(::part)
        return r.copy(
            workout = w.copy(id = id(w.id), parts = parts),
            diagnostics = r.diagnostics.map { d -> d.copy(nodeId = d.nodeId?.let(::refId)) },
            assumptions = r.assumptions.map { a -> a.copy(nodeId = a.nodeId?.let(::refId)) },
        )
    }

    private fun assign(nodes: List<BodyNode>) {
        nodes.forEach { n ->
            id(n.id)
            if (n is Item) n.sets.forEach { id(it.id) }
            if (n is Group) assign(n.children)
        }
    }

    private fun part(p: Part): Part = p.copy(
        id = id(p.id),
        body = p.body.map(::node),
        format = format(p.format),
        levels = p.levels.map { l ->
            l.copy(overrides = l.overrides.map { o -> o.copy(targetId = refId(o.targetId), replace = o.replace?.let(::node)) })
        },
        hints = p.hints?.let { h -> h.copy(tiebreakAfter = h.tiebreakAfter.map(::refId)) },
    )

    private fun node(n: BodyNode): BodyNode = when (n) {
        is Item -> n.copy(id = id(n.id), sets = n.sets.map { it.copy(id = id(it.id)) })
        is Group -> n.copy(id = id(n.id), children = n.children.map(::node))
        is Rest -> n.copy(id = id(n.id))
    }

    private fun format(f: Format): Format = when (f) {
        is Emom -> f.copy(stations = f.stations.map { s -> s.copy(nodeId = s.nodeId?.let(::refId)) })
        is Tabata -> f.copy(blocks = f.blocks.map(::refId))
        is StrengthSets -> f.copy(setRefs = f.setRefs.map(::refId))
        else -> f
    }
}
