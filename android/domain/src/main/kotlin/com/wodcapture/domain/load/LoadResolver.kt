package com.wodcapture.domain.load

import com.wodcapture.domain.model.AbsoluteLoad
import com.wodcapture.domain.model.AthleteChoiceLoad
import com.wodcapture.domain.model.AthleteProfile
import com.wodcapture.domain.model.BodyweightLoad
import com.wodcapture.domain.model.ConversionMode
import com.wodcapture.domain.model.LoadSpec
import com.wodcapture.domain.model.Mass
import com.wodcapture.domain.model.MassUnit
import com.wodcapture.domain.model.NoLoad
import com.wodcapture.domain.model.PercentOfBodyweight
import com.wodcapture.domain.model.PercentOfMax
import com.wodcapture.domain.model.RelativeToTopLoad
import com.wodcapture.domain.model.RpeLoad
import com.wodcapture.domain.model.SexSelection
import com.wodcapture.domain.model.SplitLoad

/** How a resolved load was obtained; shown in review and preview (REQ-AE04). */
enum class ResolutionMethod { AS_WRITTEN, EQUIVALENT, ARITHMETIC, PERCENT_1RM, PERCENT_BODYWEIGHT }

/** Outcome of resolving one prescription for one athlete (REQ-DM11, REQ-DM19). */
sealed interface ResolvedLoad {
    /** A concrete per-implement load in the athlete's unit. [original] is the board text side used. */
    data class Resolved(
        val mass: Mass,
        val count: Int,
        val method: ResolutionMethod,
        val original: Mass?,
        /** Short explanation for the watch note line ("80% -> 100 kg"). */
        val note: String? = null,
        val warnings: List<String> = emptyList(),
    ) : ResolvedLoad

    /** Bodyweight-only movement. */
    data object Bodyweight : ResolvedLoad

    /** No load prescribed. */
    data object Unloaded : ResolvedLoad

    /** Load must be entered on the watch (missing RM, back-off, RPE, athlete choice). Never 0. */
    data class Manual(val reason: String, val entryUnit: MassUnit, val count: Int, val note: String? = null) : ResolvedLoad

    /** Review must choose first (unconfirmed split, "always ask", unselected % range). Blocks Send. */
    data class NeedsChoice(val reason: String) : ResolvedLoad
}

/** The movement context a load belongs to. */
data class LoadContext(
    val movementId: String?,
    val implement: String?,
    val count: Int = 1,
)

/**
 * Resolves [LoadSpec]s against an [AthleteProfile] (REQ-DM11, REQ-DM19, §4.1, Appendix D).
 * Pure and deterministic; never invents a rep max or a zero load.
 */
class LoadResolver(private val profile: AthleteProfile, private val sexOverride: SexSelection? = null) {

    fun resolve(spec: LoadSpec?, ctx: LoadContext): ResolvedLoad {
        val target = profile.unitFor(ctx.implement)
        return when (spec) {
            null, NoLoad -> ResolvedLoad.Unloaded
            BodyweightLoad -> ResolvedLoad.Bodyweight
            is AbsoluteLoad -> convertPrescription(spec.mass, null, ctx, target)
            is SplitLoad -> resolveSplit(spec, ctx, target)
            is PercentOfMax -> resolvePercentOfMax(spec, ctx, target)
            is PercentOfBodyweight -> resolvePercentBw(spec, ctx, target)
            is RpeLoad -> ResolvedLoad.Manual("rpe", target, ctx.count, "RPE ${MassMath.format(spec.value)}: enter load")
            is RelativeToTopLoad -> ResolvedLoad.Manual(
                "runtime_backoff", target, ctx.count, "${MassMath.format(spec.deltaPercent)}% of top set",
            )
            is AthleteChoiceLoad -> ResolvedLoad.Manual("athlete_choice", target, ctx.count, spec.text.ifBlank { null })
        }
    }

    private fun effectiveSex(): SexSelection = sexOverride ?: profile.sex

    private fun resolveSplit(spec: SplitLoad, ctx: LoadContext, target: MassUnit): ResolvedLoad {
        // §4.4: "higher = male" is only a suggestion until review confirms the mapping.
        if (!spec.labeled && !spec.confirmed) return ResolvedLoad.NeedsChoice("split_unconfirmed")
        val side = when (effectiveSex()) {
            SexSelection.MALE -> spec.male
            SexSelection.FEMALE -> spec.female
            SexSelection.ALWAYS_ASK -> return ResolvedLoad.NeedsChoice("sex_always_ask")
        }
        val partner = if (side === spec.male) spec.female else spec.male
        return convertPrescription(side, partner, ctx, target)
    }

    /** Resolves an unmarked unit: parser guess, else lb for standard pairs, else the board unit (§4.1). */
    fun concreteUnit(m: Mass, partner: Mass?): MassUnit = when {
        m.unit != MassUnit.UNSPECIFIED -> m.unit
        m.guessedUnit != null && m.guessedUnit != MassUnit.UNSPECIFIED -> m.guessedUnit
        partner != null && Equivalents.isStandardLbPair(m.value, partner.value) -> MassUnit.LB
        else -> profile.boardUnit
    }

    /** Converts a written prescription to the athlete's unit (REQ-DM19). */
    fun convertPrescription(written: Mass, partner: Mass?, ctx: LoadContext, target: MassUnit): ResolvedLoad {
        val unit = concreteUnit(written, partner)
        val warnings = if (written.unit == MassUnit.UNSPECIFIED) listOf("ambiguous_unit") else emptyList()
        val source = Mass(written.value, unit)
        if (written.value <= 0) return ResolvedLoad.Manual("nonpositive_load", target, ctx.count)
        if (unit == target) {
            return ResolvedLoad.Resolved(source, ctx.count, ResolutionMethod.AS_WRITTEN, source, warnings = warnings)
        }
        if (profile.conversionMode == ConversionMode.EQUIVALENTS) {
            val eq = if (unit == MassUnit.POOD) Equivalents.pood(source.value, target)
            else Equivalents.lookup(ctx.implement, source.value, unit, target)
            if (eq != null) {
                return ResolvedLoad.Resolved(Mass(eq, target), ctx.count, ResolutionMethod.EQUIVALENT, source, warnings = warnings)
            }
        }
        val kg = if (unit == MassUnit.POOD) source.value * MassMath.KG_PER_POOD_RULEBOOK else MassMath.toKgPhysical(source)
        return roundLoadable(MassMath.fromKg(kg, target), target, ctx, ResolutionMethod.ARITHMETIC, source, null, warnings)
    }

    private fun resolvePercentOfMax(spec: PercentOfMax, ctx: LoadContext, target: MassUnit): ResolvedLoad {
        val pct = when {
            spec.selectedPercent != null -> spec.selectedPercent
            spec.percentMax != null -> return ResolvedLoad.NeedsChoice("percent_range")
            else -> spec.percent
        }
        val ref = spec.ofMovementId ?: ctx.movementId
        val rm = profile.repMaxes.firstOrNull { it.movementId == ref && it.reps == spec.repMax }
            ?: return ResolvedLoad.Manual(
                "missing_rep_max", target, ctx.count, "${MassMath.format(pct)}% ${spec.repMax}RM: enter load",
            )
        val raw = MassMath.fromKg(MassMath.toKgPhysical(rm.load), target) * pct / 100.0
        val note = "${MassMath.format(pct)}% -> "
        return roundLoadable(raw, target, ctx, ResolutionMethod.PERCENT_1RM, null, note, emptyList())
    }

    private fun resolvePercentBw(spec: PercentOfBodyweight, ctx: LoadContext, target: MassUnit): ResolvedLoad {
        val bw = profile.bodyweight
            ?: return ResolvedLoad.Manual("missing_bodyweight", target, ctx.count, "${MassMath.format(spec.percent)}% BW: enter load")
        val raw = MassMath.fromKg(MassMath.toKgPhysical(bw), target) * spec.percent / 100.0
        val note = "${MassMath.format(spec.percent)}% BW -> "
        return roundLoadable(raw, target, ctx, ResolutionMethod.PERCENT_BODYWEIGHT, null, note, emptyList())
    }

    /**
     * Picks the nearest loadable value (ties down): bar increments for barbells (never below the
     * selected bar), the discrete inventory when one exists, otherwise 0.5 kg / 1 lb steps.
     */
    private fun roundLoadable(
        raw: Double,
        unit: MassUnit,
        ctx: LoadContext,
        method: ResolutionMethod,
        original: Mass?,
        notePrefix: String?,
        warnings: List<String>,
    ): ResolvedLoad {
        val inventory = profile.inventory[ctx.implement.orEmpty()]
        val value = when {
            ctx.implement == "barbell" -> {
                val step = if (unit == MassUnit.LB) profile.barIncrementLb else profile.barIncrementKg
                val rounded = MassMath.roundToStep(raw, step)
                val bar = profile.selectedBar?.weight?.let { MassMath.convertPhysical(it, unit).value }
                if (bar != null && rounded < bar - 1e-6) {
                    return ResolvedLoad.Manual("below_bar", unit, ctx.count, "${MassMath.format(raw)} below bar")
                }
                rounded
            }
            !inventory.isNullOrEmpty() -> {
                val nearest = MassMath.nearestInInventory(raw, inventory)!!
                if (raw > inventory.max() + 1e-6 || raw < inventory.min() - 1e-6) {
                    return ResolvedLoad.Manual("outside_inventory", unit, ctx.count)
                }
                nearest
            }
            else -> MassMath.roundToStep(raw, if (unit == MassUnit.LB) 1.0 else 0.5)
        }
        val mass = Mass(value, unit)
        val note = notePrefix?.let { it + MassMath.formatMass(mass) }
        return ResolvedLoad.Resolved(mass, ctx.count, method, original, note, warnings)
    }
}
