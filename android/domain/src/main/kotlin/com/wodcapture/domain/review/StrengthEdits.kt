package com.wodcapture.domain.review

import com.wodcapture.domain.load.MassMath
import com.wodcapture.domain.model.AbsoluteLoad
import com.wodcapture.domain.model.BodyNode
import com.wodcapture.domain.model.BodyTree
import com.wodcapture.domain.model.Item
import com.wodcapture.domain.model.LoadSpec
import com.wodcapture.domain.model.Mass
import com.wodcapture.domain.model.MassUnit
import com.wodcapture.domain.model.Part
import com.wodcapture.domain.model.PercentOfMax
import com.wodcapture.domain.model.RepTarget
import com.wodcapture.domain.model.StrengthSet
import com.wodcapture.domain.model.StrengthSets

/**
 * Editing a strength session by its top weight and per-set percentages (owner request 2026-11-15,
 * REQ-AE08). The session's top weight ([StrengthSets.topWeight]) is the 100 %; every set carries
 * an absolute weight plus its % of that top ([StrengthSet.ofTop]), and the two are one value seen
 * two ways: typing a weight updates its %, typing a % updates the weight, and changing the top
 * weight re-computes the weight of every set entered as a %.
 *
 * The weight is what the watch runs (REQ-WS07: the watch never computes a percentage); the % only
 * travels with the plan on the phone so the session stays editable the same way before it is done.
 * The same rules drive the "New workout" builder ([com.wodcapture.domain.manual.ManualForms]).
 */
object StrengthEdits {
    /** The session's top weight, or `null` when it has none yet. */
    fun topWeight(part: Part): Mass? = (part.format as? StrengthSets)?.topWeight

    /** True when [part] is a strength session, so the top-weight editor applies. */
    fun isStrength(part: Part): Boolean = part.format is StrengthSets

    /** The lift of a one-lift session: the first item carrying sets, else the first item. */
    fun liftOf(part: Part): Item? =
        BodyTree.items(part.body).let { items -> items.firstOrNull { it.sets.isNotEmpty() } ?: items.firstOrNull() }

    /**
     * Unit the session's weights are read and typed in: the top weight's, else [fallback]
     * (Settings → Weight unit). One unit for the whole session, so a lb set parsed into a kg
     * session is shown converted rather than mislabelled (§4.1).
     */
    fun displayUnit(part: Part, fallback: MassUnit): MassUnit =
        topWeight(part)?.unit?.takeIf { it != MassUnit.UNSPECIFIED } ?: fallback

    /**
     * Weight of [set] in [unit]: its own load, else the item's (a set without a load inherits the
     * item's, REQ-DM06). `null` when neither is an absolute weight.
     */
    fun weightOf(item: Item, set: StrengthSet, unit: MassUnit): Double? =
        massOf(set.load ?: item.load)?.let { convert(it, unit).value }

    /**
     * [set] as a % of the top weight: as typed when it was entered as a %, else computed from its
     * weight. Falls back to a percentage prescription on the set or the item, so a parsed
     * "5 × 3 @ 80 %" shows its 80 % before anything is typed.
     */
    fun percentOf(item: Item, set: StrengthSet, top: Mass?): Double? {
        set.ofTop?.let { return it }
        massOf(set.load ?: item.load)?.let { w -> percentOfTop(w, top)?.let { return it } }
        return (set.load ?: item.load).let { it as? PercentOfMax }?.let { it.selectedPercent ?: it.percent }
    }

    /**
     * Sets the session's top weight. Sets entered as a % get the weight that % of the new top is,
     * rounded to [step] (Settings → smallest total increment, REQ-DM19); sets entered as a weight
     * get their % of the new top. Clearing it leaves every set as it is, so retyping a top weight
     * does not lose the percentages.
     */
    fun withTopWeight(part: Part, top: Mass?, step: Double): Part {
        val format = part.format as? StrengthSets ?: return part
        val body = if (!valid(top)) part.body else mapSets(part.body) { item, set ->
            val computed = set.ofTop?.let { weightFor(it, top, step, unitOf(item, set, top)) }
            when {
                computed != null -> set.copy(load = AbsoluteLoad(computed))
                else -> set.copy(ofTop = massOf(set.load ?: item.load)?.let { percentOfTop(it, top) })
            }
        }
        return part.copy(format = format.copy(topWeight = top), body = body)
    }

    /** Types a set's weight in [unit]; its % of the top weight follows from it. */
    fun withSetWeight(part: Part, setId: String, weight: Double?, unit: MassUnit): Part {
        val top = topWeight(part)
        return editSet(part, setId) { _, set ->
            set.copy(
                load = weight?.let { AbsoluteLoad(Mass(MassMath.clean(it), unit)) },
                ofTop = weight?.let { percentOfTop(Mass(it, unit), top) },
            )
        }
    }

    /**
     * Types a set's % of the top weight: the weight becomes top × %, rounded to [step]. Without a
     * top weight there is nothing to compute from, so the % waits for one (the editor says so) and
     * the weight is left alone.
     */
    fun withSetPercent(part: Part, setId: String, percent: Double?, step: Double): Part {
        val top = topWeight(part)
        return editSet(part, setId) { item, set ->
            val computed = percent?.let { weightFor(it, top, step, unitOf(item, set, top)) }
            set.copy(ofTop = percent, load = computed?.let { AbsoluteLoad(it) } ?: set.load)
        }
    }

    fun withSetReps(part: Part, setId: String, reps: Int?): Part =
        editSet(part, setId) { _, set -> set.copy(reps = set.reps.copy(value = reps)) }

    /** Rest after every set of the session (REQ-AC09 keeps one rest for the whole session). */
    fun withRest(part: Part, itemId: String, restSec: Int?): Part =
        editItem(part, itemId) { item -> item.copy(sets = item.sets.map { it.copy(restSec = restSec) }) }

    /** Appends a set repeating the last one, so a ramp is built with taps (REQ-AC09). */
    fun addSet(part: Part, itemId: String, newId: () -> String): Part =
        editItem(part, itemId) { item ->
            val last = item.sets.lastOrNull()
            item.copy(sets = item.sets + (last?.copy(id = newId()) ?: StrengthSet(newId(), reps = RepTarget(5))))
        }

    fun removeSet(part: Part, itemId: String, setId: String): Part =
        editItem(part, itemId) { item -> item.copy(sets = item.sets.filterNot { it.id == setId }) }
            .let { p -> (p.format as? StrengthSets)?.let { f -> p.copy(format = f.copy(setRefs = f.setRefs.filterNot { it == setId })) } ?: p }

    private fun editItem(part: Part, itemId: String, f: (Item) -> Item): Part =
        part.copy(body = BodyTree.map(part.body) { if (it is Item && it.id == itemId) f(it) else it })

    private fun editSet(part: Part, setId: String, f: (Item, StrengthSet) -> StrengthSet): Part =
        part.copy(body = mapSets(part.body) { item, set -> if (set.id == setId) f(item, set) else set })

    private fun mapSets(body: List<BodyNode>, f: (Item, StrengthSet) -> StrengthSet): List<BodyNode> =
        BodyTree.map(body) { n -> if (n is Item && n.sets.isNotEmpty()) n.copy(sets = n.sets.map { f(n, it) }) else n }

    /** Unit a computed weight is written in: the set's own, else the item's, else the top weight's. */
    private fun unitOf(item: Item, set: StrengthSet, top: Mass?): MassUnit =
        massOf(set.load)?.unit ?: massOf(item.load)?.unit ?: top?.unit ?: MassUnit.KG

    private fun massOf(load: LoadSpec?): Mass? = (load as? AbsoluteLoad)?.mass?.takeIf { it.unit != MassUnit.UNSPECIFIED }

    private fun valid(m: Mass?): Boolean = m != null && m.value > 0 && m.unit != MassUnit.UNSPECIFIED

    /** % of [top], to one decimal, comparing physical mass so kg and lb mix safely (§4.1). */
    private fun percentOfTop(weight: Mass, top: Mass?): Double? {
        if (!valid(top) || weight.unit == MassUnit.UNSPECIFIED) return null
        return Math.round(MassMath.toKgPhysical(weight) / MassMath.toKgPhysical(top!!) * 1000) / 10.0
    }

    /** top × [percent], rounded to [step], expressed in [unit]. */
    private fun weightFor(percent: Double, top: Mass?, step: Double, unit: MassUnit): Mass? {
        if (!valid(top)) return null
        val target = convert(top!!, unit).value * percent / 100
        return Mass(MassMath.roundToStep(target, step.takeIf { it > 0 } ?: 1.0), unit)
    }

    private fun convert(m: Mass, unit: MassUnit): Mass =
        if (m.unit == unit) m else Mass(MassMath.clean(MassMath.convertPhysical(m, unit).value), unit)
}
