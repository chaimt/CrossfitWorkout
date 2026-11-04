package com.wodcapture.domain.model

/** Traversal helpers over the recursive body tree (REQ-DM16). */
object BodyTree {
    /** Every node of [nodes] in pre-order. */
    fun flatten(nodes: List<BodyNode>): List<BodyNode> = buildList {
        fun visit(n: BodyNode) {
            add(n)
            if (n is Group) n.children.forEach(::visit)
        }
        nodes.forEach(::visit)
    }

    /** All items in pre-order. */
    fun items(nodes: List<BodyNode>): List<Item> = flatten(nodes).filterIsInstance<Item>()

    /** Maximum nesting depth; a flat list of items has depth 1. */
    fun depth(nodes: List<BodyNode>): Int =
        nodes.maxOfOrNull { if (it is Group) 1 + depth(it.children) else 1 } ?: 0

    fun find(nodes: List<BodyNode>, id: String): BodyNode? = flatten(nodes).firstOrNull { it.id == id }

    /** Every node ID of a part, including strength set IDs (they are body nodes too). */
    fun allIds(part: Part): List<String> = flatten(part.body).flatMap { n ->
        if (n is Item) listOf(n.id) + n.sets.map { it.id } else listOf(n.id)
    }

    /** Returns [nodes] with [transform] applied bottom-up to every node. */
    fun map(nodes: List<BodyNode>, transform: (BodyNode) -> BodyNode): List<BodyNode> = nodes.map { n ->
        val mapped = if (n is Group) n.copy(children = map(n.children, transform)) else n
        transform(mapped)
    }
}
