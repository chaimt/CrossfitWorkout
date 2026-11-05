package com.wodcapture.domain.levels

import com.wodcapture.domain.model.BodyNode
import com.wodcapture.domain.model.BodyTree
import com.wodcapture.domain.model.Item
import com.wodcapture.domain.model.Override
import com.wodcapture.domain.model.Part

/** Name used for the base prescription when no level is chosen (REQ-DM08). */
const val BASE_LEVEL = "Rx"

/** Thrown when an override targets a node that does not exist (blocks Send, REQ-DM16). */
class DanglingOverrideException(val level: String, val targetId: String) :
    IllegalArgumentException("Level '$level' overrides missing node $targetId")

/** Applies a part's level overrides to produce the effective body (REQ-DM08, REQ-AE03). */
object LevelApplier {

    /** Level names offered on the watch toggle: the base prescription first. */
    fun levelNames(part: Part): List<String> =
        if (part.levels.isEmpty()) emptyList() else listOf(BASE_LEVEL) + part.levels.map { it.name }

    /** Returns [part] with [levelName]'s overrides applied; base level returns it unchanged. */
    fun apply(part: Part, levelName: String?): Part {
        val name = levelName ?: part.chosenLevel ?: BASE_LEVEL
        if (name == BASE_LEVEL) return part.copy(chosenLevel = null)
        val level = part.levels.firstOrNull { it.name == name }
            ?: throw IllegalArgumentException("Unknown level '$name' for part ${part.id}")
        var body = part.body
        for (o in level.overrides) {
            if (BodyTree.find(body, o.targetId) == null) throw DanglingOverrideException(level.name, o.targetId)
            body = BodyTree.map(body) { n -> if (n.id == o.targetId) patch(n, o) else n }
        }
        return part.copy(body = body, chosenLevel = name)
    }

    private fun patch(node: BodyNode, o: Override): BodyNode {
        o.replace?.let { return it }
        if (node !is Item) return node
        return node.copy(
            movementId = o.movementId ?: node.movementId,
            name = o.name ?: node.name,
            // A renamed movement keeps nothing of the old display shortening.
            displayName = if (o.name != null) null else node.displayName,
            load = o.load ?: node.load,
            measure = o.measure ?: node.measure,
        )
    }
}
