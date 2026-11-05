package com.wodcapture.domain.expand

import com.wodcapture.domain.model.BodyNode
import com.wodcapture.domain.model.Group
import com.wodcapture.domain.model.GroupKind
import com.wodcapture.domain.model.Item
import com.wodcapture.domain.model.ItemRole
import com.wodcapture.domain.model.MeasureKind
import com.wodcapture.domain.model.Rest
import com.wodcapture.domain.model.SexSelection

/** One concrete step of an expanded round. */
sealed interface Step {
    /** [reps] is the per-round rep count when the item is rep-measured, else `null`. */
    data class Work(val item: Item, val reps: Int?, val countable: Boolean) : Step
    data class Pause(val rest: Rest) : Step
}

/** One round of the program with its concrete steps. */
data class ExpandedRound(val index: Int, val steps: List<Step>) {
    /** Rep total of the round when every work step is rep-countable (REQ-DM24), else `null`. */
    val repTotal: Int?
        get() {
            val works = steps.filterIsInstance<Step.Work>()
            if (works.isEmpty() || works.any { !it.countable || it.reps == null }) return null
            return works.sumOf { it.reps!! }
        }
}

/** Shape of the body for display/scoring: optional buy-in, a round program, optional cash-out. */
data class RoundProgram(
    val buyIn: List<Item>,
    val rounds: List<ExpandedRound>,
    val cashOut: List<Item>,
    /** The repeated top-level group, or `null` when the body is a single pass. */
    val group: Group?,
)

/**
 * Expands rep schemes, nested rounds and progressions into concrete rounds (REQ-DM04,
 * "repeated segment templates are expanded and validated before compilation").
 */
class RepSchemeExpander(private val sex: SexSelection = SexSelection.MALE) {

    /**
     * Expands [body]. A body with one top-level rounds group (plus buy-in/cash-out items) yields
     * that group's rounds; otherwise the whole body is one round. [progressiveRounds] is how many
     * rounds to compile for an open-ended progressive AMRAP.
     */
    fun program(body: List<BodyNode>, progressiveRounds: Int = 1): RoundProgram {
        val groups = body.filterIsInstance<Group>().filter { it.kind == GroupKind.ROUNDS || it.kind == GroupKind.SEQUENCE }
        val top = groups.singleOrNull()?.takeIf { g -> body.all { it === g || it is Item || it is Rest } }
        if (top == null) {
            val single = body.any { it is Item && it.progression != null }
            val count = if (single) progressiveRounds else 1
            val rounds = (0 until count).map { r -> ExpandedRound(r, expandNodes(body, r, null, isLast = r == count - 1)) }
            return RoundProgram(emptyList(), rounds, emptyList(), null)
        }
        val idx = body.indexOf(top)
        val before = body.subList(0, idx).filterIsInstance<Item>()
        val after = body.subList(idx + 1, body.size).filterIsInstance<Item>()
        val n = roundCount(top)
        val rounds = (0 until n).map { r -> ExpandedRound(r, expandNodes(top.children, r, top, isLast = r == n - 1)) }
        return RoundProgram(before, rounds, after, top)
    }

    /** Rounds of a group: the rep scheme length wins when present. */
    fun roundCount(g: Group): Int = if (g.repScheme.isNotEmpty()) g.repScheme.size else g.repeat

    private fun expandNodes(nodes: List<BodyNode>, round: Int, scheme: Group?, isLast: Boolean): List<Step> =
        nodes.flatMap { n ->
            when (n) {
                is Item -> listOf(work(n, round, scheme))
                is Rest -> if (n.betweenOnly && isLast) emptyList() else listOf(Step.Pause(n))
                is Group -> {
                    val inner = roundCount(n)
                    val innerScheme = if (n.repScheme.isNotEmpty()) n else scheme
                    (0 until inner).flatMap { i ->
                        expandNodes(n.children, if (n.repScheme.isNotEmpty()) i else round, innerScheme, i == inner - 1)
                    }
                }
            }
        }

    private fun work(item: Item, round: Int, scheme: Group?): Step.Work {
        val m = item.measure
        val base: Int? = when (m?.kind) {
            MeasureKind.REPS -> pick(m.value, m.female)?.toInt()
            MeasureKind.REP_SCHEME -> scheme?.repScheme?.getOrNull(round)
            null -> scheme?.repScheme?.getOrNull(round)
            else -> null
        }
        val reps = base?.let { it + (item.progression?.repsPerRound ?: 0) * round }
        val countable = reps != null && item.role == ItemRole.MAIN
        return Step.Work(item, reps, countable)
    }

    private fun pick(male: Double?, female: Double?): Double? =
        if (sex == SexSelection.FEMALE && female != null) female else male

    /** Reps of [item] summed over all [rounds] (e.g. 55 per movement for a 1→10 ladder). */
    fun totalRepsFor(item: Item, rounds: List<ExpandedRound>): Int =
        rounds.sumOf { r -> r.steps.filterIsInstance<Step.Work>().filter { it.item.id == item.id }.sumOf { it.reps ?: 0 } }
}
