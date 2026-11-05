package com.wodcapture.domain.load

import com.wodcapture.domain.model.Mass
import com.wodcapture.domain.model.MassUnit
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.floor

/** Physical-mass conversion and number formatting (§4.1, Appendix D). */
object MassMath {
    const val KG_PER_LB = 0.45359237

    /** CrossFit Rulebook kettlebell mapping: 1 pood = 16 kg for prescriptions (§4.1). */
    const val KG_PER_POOD_RULEBOOK = 16.0

    /** True pood used for physical conversions of measured loads. */
    const val KG_PER_POOD_PHYSICAL = 16.38

    /** Physical conversion; pood uses the true pood (actual results, PRs, percentages). */
    fun toKgPhysical(m: Mass): Double = when (m.unit) {
        MassUnit.KG -> m.value
        MassUnit.LB -> m.value * KG_PER_LB
        MassUnit.POOD -> m.value * KG_PER_POOD_PHYSICAL
        MassUnit.UNSPECIFIED -> error("Resolve the unit of $m before converting")
    }

    fun fromKg(kg: Double, unit: MassUnit): Double = when (unit) {
        MassUnit.KG -> kg
        MassUnit.LB -> kg / KG_PER_LB
        MassUnit.POOD -> kg / KG_PER_POOD_PHYSICAL
        MassUnit.UNSPECIFIED -> error("Target unit must be concrete")
    }

    fun convertPhysical(m: Mass, to: MassUnit): Mass =
        if (m.unit == to) m else Mass(fromKg(toKgPhysical(m), to), to)

    /**
     * Rounds [value] to the nearest multiple of [step]; exact ties go **down** (REQ-DM19) so the
     * athlete is never prescribed more than the arithmetic says.
     */
    fun roundToStep(value: Double, step: Double): Double {
        require(step > 0) { "step must be positive" }
        val lower = floor(value / step + 1e-9) * step
        val upper = lower + step
        val dl = value - lower
        val du = upper - value
        return clean(if (du < dl - 1e-9) upper else lower)
    }

    /** Nearest value of a discrete inventory, ties downward. */
    fun nearestInInventory(value: Double, inventory: List<Double>): Double? =
        inventory.sorted().minWithOrNull(compareBy<Double> { abs(it - value) }.thenBy { it })

    /** Removes floating-point noise (0.1+0.2) from computed loads. */
    fun clean(v: Double): Double = BigDecimal(v).setScale(3, RoundingMode.HALF_UP).toDouble()

    /** "43", "102.5", "7.25": no trailing zeros, at most 2 decimals. */
    fun format(v: Double): String =
        BigDecimal(v).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    fun formatMass(m: Mass): String = "${format(m.value)} ${unitLabel(m.unit)}"

    fun unitLabel(u: MassUnit): String = when (u) {
        MassUnit.KG -> "kg"
        MassUnit.LB -> "lb"
        MassUnit.POOD -> "pood"
        MassUnit.UNSPECIFIED -> ""
    }
}
