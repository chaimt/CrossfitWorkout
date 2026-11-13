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
        /** Warm-up/skill stays first; other lifting formats are grouped as weights. */
        fun of(part: Part): WorkoutSection = when {
            part.kind == PartKind.WARMUP || part.kind == PartKind.SKILL -> WARMUP
            part.format is StrengthSets || part.format is BuildToMax -> WEIGHTS
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
        fun runRank(part: Part): Int = when (of(part)) {
            WARMUP -> 0
            WEIGHTS -> 1
            METCON -> 2
            OTHER -> if (part.kind == PartKind.COOLDOWN) 4 else 3
        }

        /** Parts in class run order, keeping the existing relative order inside each rank. */
        fun runOrdered(parts: List<Part>): List<Part> = parts.sortedBy(::runRank)
    }
}
