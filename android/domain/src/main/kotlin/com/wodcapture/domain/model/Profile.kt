package com.wodcapture.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Which side of an M/F split the athlete takes (REQ-DM10). */
@Serializable
enum class SexSelection {
    @SerialName("male") MALE,
    @SerialName("female") FEMALE,
    /** Every split must be chosen in review; blocks resolved preview until chosen (§4.4). */
    @SerialName("always_ask") ALWAYS_ASK,
}

/** How prescriptions are converted between kg and lb (REQ-DM19). */
@Serializable
enum class ConversionMode {
    /** Appendix D implement-specific published equivalents, else arithmetic (default, my call). */
    @SerialName("equivalents") EQUIVALENTS,
    @SerialName("arithmetic") ARITHMETIC,
}

/** A barbell the athlete uses. */
@Serializable
data class Bar(val id: String, val name: String, val weight: Mass)

/** Manually measured rep max (REQ-DM10); never guessed (REQ-DM19). */
@Serializable
data class RepMax(val movementId: String, val reps: Int = 1, val load: Mass, val date: String? = null)

/** Defaults the phone sends to the watch in `profile.put` (REQ-AG02, protocol §6). */
@Serializable
data class WatchDefaults(
    val prepSec: Int = 10,
    val sound: Boolean = true,
    val vibration: Boolean = true,
    val record: Boolean = true,
    val touch: Boolean = true,
    val weightStep: Double = 2.5,
    val weightStepFine: Double = 0.5,
    val tapToLog: Boolean = true,
)

/** Athlete profile (REQ-DM10, REQ-AG01). */
@Serializable
data class AthleteProfile(
    val displayUnit: MassUnit = MassUnit.KG,
    /** Unit assumed for unmarked loads that do not match a standard lb pair (§4.1). */
    val boardUnit: MassUnit = MassUnit.KG,
    /** Per-implement gear unit (`barbell` → kg, `kettlebell` → lb, …). */
    val gearUnits: Map<String, MassUnit> = emptyMap(),
    val sex: SexSelection = SexSelection.MALE,
    val bodyweight: Mass? = null,
    val bars: List<Bar> = listOf(
        Bar("men", "Men's bar", Mass(20.0, MassUnit.KG)),
        Bar("women", "Women's bar", Mass(15.0, MassUnit.KG)),
    ),
    val selectedBarId: String = "men",
    /** Smallest total barbell increment per unit (default 1 kg / 2.5 lb, REQ-DM10). */
    val barIncrementKg: Double = 1.0,
    val barIncrementLb: Double = 2.5,
    /** Optional discrete gear inventory per implement, values in that implement's unit. */
    val inventory: Map<String, List<Double>> = emptyMap(),
    val conversionMode: ConversionMode = ConversionMode.EQUIVALENTS,
    val defaultRestSec: Int = 120,
    val repMaxes: List<RepMax> = emptyList(),
    val watch: WatchDefaults = WatchDefaults(),
) {
    /** Unit the athlete wants a given implement's loads in. */
    fun unitFor(implement: String?): MassUnit = implement?.let { gearUnits[it] } ?: displayUnit

    /** The selected barbell, falling back to the first one. */
    val selectedBar: Bar? get() = bars.firstOrNull { it.id == selectedBarId } ?: bars.firstOrNull()
}
