package com.wodcapture.domain.model

/**
 * How a class day is grouped for the athlete: the lifting piece ("A. Back squat 5 × 5") is kept
 * apart from the conditioning piece ("B. AMRAP 20"). Derived from a part's format and kind, so it
 * needs no extra field in the canonical model or on the wire.
 */
enum class WorkoutSection(val label: String) {
    WEIGHTS("Weights"),
    METCON("Metcon"),
    OTHER("Warm-up & other"),
    ;

    companion object {
        /** Lifting formats are weights whatever the kind says; otherwise the kind decides. */
        fun of(part: Part): WorkoutSection = when (part.format) {
            is StrengthSets, is BuildToMax -> WEIGHTS
            else -> of(part.kind)
        }

        fun of(kind: PartKind): WorkoutSection = when (kind) {
            PartKind.STRENGTH, PartKind.ACCESSORY -> WEIGHTS
            PartKind.METCON -> METCON
            PartKind.WARMUP, PartKind.SKILL, PartKind.COOLDOWN, PartKind.OTHER -> OTHER
        }
    }
}
