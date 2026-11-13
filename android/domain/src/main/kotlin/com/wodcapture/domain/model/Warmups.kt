package com.wodcapture.domain.model

/** Warm-up clocks have rounds and a time cap, but never request a score. */
object Warmups {
    const val DEFAULT_CAP_SEC = 600

    fun prepare(workout: Workout): Workout = workout.copy(parts = workout.parts.map(::prepare))

    fun timeCap(part: Part): Int = (part.format as? ForTime)?.capSec
        ?: part.fallback?.capSec ?: part.fallback?.durationSec ?: DEFAULT_CAP_SEC

    fun rounds(part: Part): Int = (part.body.singleOrNull() as? Group)
        ?.takeIf { it.kind == GroupKind.ROUNDS && it.repScheme.isEmpty() }?.repeat ?: 1

    fun withRounds(part: Part, rounds: Int, newId: () -> String): Part {
        val group = (part.body.singleOrNull() as? Group)?.takeIf { it.kind == GroupKind.ROUNDS && it.repScheme.isEmpty() }
        val body = when {
            group != null -> listOf(group.copy(repeat = rounds))
            rounds == 1 -> part.body
            else -> listOf(Group(newId(), kind = GroupKind.ROUNDS, repeat = rounds, children = part.body))
        }
        return prepare(part.copy(body = body))
    }

    fun prepare(part: Part): Part {
        if (part.kind != PartKind.WARMUP) return part
        val cap = timeCap(part)
        val format = (part.format as? ForTime)?.copy(capSec = cap) ?: part.format
        val fallback = when (format) {
            NotForTime -> Fallback(accepted = true, timer = FallbackTimer.COUNT_UP, capSec = cap, reason = "Warm-up rounds: no score")
            is ForTime -> Fallback(accepted = true, timer = FallbackTimer.COUNT_DOWN,
                durationSec = cap, reason = "Timed warm-up: no score")
            else -> part.fallback
        }
        return part.copy(format = format, scoring = Scoring(type = ScoreType.NONE, ruleVersion = part.scoring.ruleVersion), fallback = fallback)
    }
}
