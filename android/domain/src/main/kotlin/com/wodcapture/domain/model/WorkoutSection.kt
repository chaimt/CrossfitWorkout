package com.wodcapture.domain.model

/**
 * How a class day is grouped for the athlete: the warm-up ("A. Bike 5:00") runs first, then the
 * lifting piece ("B. Back squat 5 × 5"), then the conditioning piece ("C. AMRAP 20"), then
 * cool-down and other work last. Derived from a part's format and kind, so it needs no extra
 * field in the canonical model or on the wire. Ordinal is run order.
 */
enum class WorkoutSection(val label: String) {
    WARMUP("Warm-up"),
    WEIGHTS("Weights"),
    METCON("Metcon"),
    OTHER("Cool-down & other"),
    ;

    companion object {
        /** Lifting formats are weights whatever the kind says; otherwise the kind decides. */
        fun of(part: Part): WorkoutSection = when (part.format) {
            is StrengthSets, is BuildToMax -> WEIGHTS
            else -> of(part.kind)
        }

        fun of(kind: PartKind): WorkoutSection = when (kind) {
            PartKind.WARMUP, PartKind.SKILL -> WARMUP
            PartKind.STRENGTH -> WEIGHTS
            PartKind.METCON -> METCON
            PartKind.ACCESSORY, PartKind.COOLDOWN, PartKind.OTHER -> OTHER
        }

        /**
         * Canonical class run order: warm-up/skill first, weights, metcon, then
         * accessory/other, with cool-down last. Stable-sort parts by this before sending;
         * the watch just runs plan order.
         */
        fun runRank(part: Part): Int = when {
            part.format is StrengthSets || part.format is BuildToMax -> 1
            else -> when (part.kind) {
                PartKind.WARMUP, PartKind.SKILL -> 0
                PartKind.STRENGTH -> 1
                PartKind.METCON -> 2
                PartKind.ACCESSORY, PartKind.OTHER -> 3
                PartKind.COOLDOWN -> 4
            }
        }

        /** Parts in class run order, keeping the existing relative order inside each rank. */
        fun runOrdered(parts: List<Part>): List<Part> = parts.sortedBy(::runRank)
    }
}
