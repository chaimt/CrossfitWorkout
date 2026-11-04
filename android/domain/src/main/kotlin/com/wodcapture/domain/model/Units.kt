package com.wodcapture.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Load units as written on the board (REQ-DM06). `UNSPECIFIED` = the parser could not tell. */
@Serializable
enum class MassUnit(val wire: String) {
    @SerialName("kg") KG("kg"),
    @SerialName("lb") LB("lb"),
    @SerialName("pood") POOD("pood"),
    @SerialName("unspecified") UNSPECIFIED("unspecified"),
}

/** Distance units for runs, rows, lunges and distance scores (REQ-DM05, REQ-DM22). */
@Serializable
enum class DistanceUnit(val wire: String) {
    @SerialName("m") M("m"),
    @SerialName("km") KM("km"),
    @SerialName("mi") MI("mi"),
    @SerialName("ft") FT("ft"),
    @SerialName("yd") YD("yd"),
}

/** Length units for box heights and wall-ball targets; never inferred from weight units (§4.4). */
@Serializable
enum class LengthUnit(val wire: String) {
    @SerialName("in") IN("in"),
    @SerialName("cm") CM("cm"),
    @SerialName("ft") FT("ft"),
    @SerialName("m") M("m"),
}

/** A mass as written: value plus unit, and the parser's guess when the unit was not marked (§4.1). */
@Serializable
data class Mass(
    val value: Double,
    val unit: MassUnit,
    /** Parser's guess when [unit] is `UNSPECIFIED`; review must confirm it. */
    val guessedUnit: MassUnit? = null,
)

/** A length (box height, target height) as written. */
@Serializable
data class Length(val value: Double, val unit: LengthUnit)
