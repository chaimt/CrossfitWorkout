package com.wodcapture.domain.load

import com.wodcapture.domain.model.MassUnit

/**
 * CrossFit Games Rulebook Appendix D published lb↔kg equivalents (REQUIREMENTS Appendix D).
 * They are a prescription table, not arithmetic: 95 lb = 43 kg, 185 lb = 83 kg. Used only for
 * prescription conversion in [com.wodcapture.domain.model.ConversionMode.EQUIVALENTS] mode;
 * actual results and percentages always use physical mass (REQ-DM19).
 */
object Equivalents {
    /** Implement table → list of (lb, kg). */
    private val tables: Map<String, List<Pair<Double, Double>>> = mapOf(
        "barbell" to listOf(
            35.0 to 15.0, 45.0 to 20.0, 55.0 to 25.0, 65.0 to 29.0, 75.0 to 34.0, 85.0 to 38.0,
            95.0 to 43.0, 100.0 to 45.0, 105.0 to 47.0, 115.0 to 52.0, 125.0 to 56.0, 135.0 to 61.0,
            145.0 to 66.0, 155.0 to 70.0, 165.0 to 75.0, 175.0 to 79.0, 185.0 to 83.0, 195.0 to 88.0,
            205.0 to 93.0, 215.0 to 98.0, 225.0 to 102.0, 275.0 to 125.0, 315.0 to 143.0,
        ),
        "dumbbell" to listOf(
            10.0 to 5.0, 15.0 to 7.5, 20.0 to 10.0, 25.0 to 12.5, 35.0 to 15.0, 50.0 to 22.5, 70.0 to 32.5,
        ),
        "kettlebell" to listOf(26.0 to 12.0, 35.0 to 16.0, 53.0 to 24.0, 70.0 to 32.0),
        "medicine_ball" to listOf(10.0 to 4.0, 14.0 to 6.0, 20.0 to 9.0),
    )

    /** Implements that share a table (e.g. a wall ball is a medicine ball). */
    private fun tableFor(implement: String?): List<Pair<Double, Double>>? = when (implement) {
        "barbell" -> tables["barbell"]
        "dumbbell" -> tables["dumbbell"]
        "kettlebell" -> tables["kettlebell"]
        "medicine_ball", "wall_ball", "slam_ball" -> tables["medicine_ball"]
        else -> null
    }

    /** Published equivalent of [value] [from] → [to] for [implement], or `null` if absent. */
    fun lookup(implement: String?, value: Double, from: MassUnit, to: MassUnit): Double? {
        val table = tableFor(implement) ?: return null
        return when {
            from == MassUnit.LB && to == MassUnit.KG -> table.firstOrNull { it.first == value }?.second
            from == MassUnit.KG && to == MassUnit.LB -> table.firstOrNull { it.second == value }?.first
            else -> null
        }
    }

    /**
     * Rulebook pood mapping (1 / 1.5 / 2 pood = 16 / 24 / 32 kg = 35 / 53 / 70 lb); other pood
     * values use 16 kg per pood for prescriptions (§4.1).
     */
    fun pood(value: Double, to: MassUnit): Double? {
        val kg = value * MassMath.KG_PER_POOD_RULEBOOK
        return when (to) {
            MassUnit.KG -> kg
            MassUnit.LB -> lookup("kettlebell", kg, MassUnit.KG, MassUnit.LB)
            MassUnit.POOD -> value
            MassUnit.UNSPECIFIED -> null
        }
    }

    /** Standard CrossFit pound pairs: an unmarked pair matching one is read as lb (§4.1). */
    val standardLbPairs: Set<Pair<Double, Double>> = setOf(
        95.0 to 65.0, 135.0 to 95.0, 225.0 to 155.0, 53.0 to 35.0, 20.0 to 14.0, 185.0 to 125.0,
        155.0 to 105.0, 115.0 to 75.0, 75.0 to 55.0, 65.0 to 45.0, 50.0 to 35.0, 35.0 to 20.0,
        275.0 to 185.0, 315.0 to 205.0, 45.0 to 35.0, 70.0 to 53.0, 14.0 to 10.0, 245.0 to 165.0,
        250.0 to 175.0, 165.0 to 115.0, 105.0 to 75.0, 85.0 to 65.0,
    )

    fun isStandardLbPair(a: Double, b: Double): Boolean =
        (maxOf(a, b) to minOf(a, b)) in standardLbPairs
}
