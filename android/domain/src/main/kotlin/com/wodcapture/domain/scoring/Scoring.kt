package com.wodcapture.domain.scoring

import com.wodcapture.domain.load.MassMath
import com.wodcapture.domain.model.Mass
import com.wodcapture.domain.model.MassUnit
import com.wodcapture.domain.model.ProgressEvent
import com.wodcapture.domain.model.ResultStatus
import com.wodcapture.domain.model.Score
import com.wodcapture.domain.model.SetObservation
import com.wodcapture.domain.model.SetStatus

/** A derived aggregate; incomplete when any observation is missing (REQ-DM23). */
data class Aggregate(val complete: Boolean, val value: Double?, val missing: List<Int>) {
    /** Sum over complete observations only, shown as a labeled partial, never as the score. */
    var partialValue: Double? = null
        internal set
}

/** Outcome of a for-time part from its events (REQ-DM24). */
data class ForTimeOutcome(val status: ResultStatus, val score: Score, val rounds: Int)

/** Scoring rules version persisted with results (REQ-DM07). */
const val SCORING_RULE_VERSION = 1

/** Pure scoring from typed observations (REQ-DM21–DM24). Missing is never treated as zero. */
object ScoreCalculator {

    /**
     * Total reps of `rounds + extra` given per-round totals [rr] (`[6,12,18]`: 2 + 5 = 23).
     * Extra reps must be 0 .. current-round total − 1; a full round is normalised first.
     * When rounds run past [rr] the last entry repeats (open AMRAP with constant rounds).
     */
    fun totalReps(rounds: Int, extra: Int, rr: List<Int>): Int {
        require(rr.isNotEmpty() && rr.all { it > 0 }) { "Per-round totals must be positive" }
        val (r, x) = normalize(rounds, extra, rr)
        return (0 until r).sumOf { roundTotal(rr, it) } + x
    }

    /** Folds a completed round into the round count (REQ-DM24). Rejects impossible extra reps. */
    fun normalize(rounds: Int, extra: Int, rr: List<Int>): Pair<Int, Int> {
        require(rounds >= 0 && extra >= 0) { "Negative progress" }
        val current = roundTotal(rr, rounds)
        require(extra <= current) { "Extra reps $extra exceed round ${rounds + 1} total $current" }
        return if (extra == current) (rounds + 1) to 0 else rounds to extra
    }

    /** Converts a total rep count back into rounds + reps. */
    fun roundsReps(total: Int, rr: List<Int>): Score.RoundsReps {
        var left = total
        var r = 0
        while (left >= roundTotal(rr, r) && (rr.size == 1 || r < rr.size)) {
            left -= roundTotal(rr, r)
            r += 1
        }
        return Score.RoundsReps(r, left)
    }

    private fun roundTotal(rr: List<Int>, index: Int): Int = rr.getOrElse(index) { rr.last() }

    /**
     * For Time with optional cap (REQ-DM24): events processed by timestamp, finish wins an exact
     * tie with the cap; capped progress needs a rep counting rule, otherwise it stays manual text.
     */
    fun forTime(events: List<ProgressEvent>, capMs: Long?, rr: List<Int>?, extraRepsAtCap: Int?): ForTimeOutcome {
        val order = events.sortedWith(compareBy<ProgressEvent> { it.atMs }.thenBy { if (it.kind == "fin") 0 else 1 })
        var rounds = 0
        for (e in order) {
            if (capMs != null && e.atMs > capMs) break
            when (e.kind) {
                "rd" -> rounds += 1
                "undo" -> rounds = maxOf(0, rounds - 1)
                "fin" -> return ForTimeOutcome(ResultStatus.COMPLETED, Score.Time((e.atMs / 1000).toInt()), rounds)
            }
        }
        if (capMs == null) return ForTimeOutcome(ResultStatus.STOPPED_EARLY, Score.Text("stopped"), rounds)
        if (rr == null || extraRepsAtCap == null) return ForTimeOutcome(ResultStatus.CAPPED, Score.Text("capped after $rounds rounds"), rounds)
        val done = (0 until rounds).sumOf { rr.getOrElse(it) { 0 } }
        val current = rr.getOrNull(rounds)
        require(current == null || extraRepsAtCap < current) { "Extra reps exceed the current round" }
        return ForTimeOutcome(ResultStatus.CAPPED, Score.Reps(done + extraRepsAtCap), rounds)
    }

    /** Aggregates per-interval values; any `null` makes the aggregate incomplete (REQ-DM23). */
    fun aggregate(values: List<Double?>, op: String): Aggregate {
        val missing = values.indices.filter { values[it] == null }
        val present = values.filterNotNull()
        val v = if (missing.isNotEmpty() || present.isEmpty()) null else when (op) {
            "sum" -> present.sum()
            "min" -> present.min()
            "max" -> present.max()
            "average" -> present.average()
            else -> throw IllegalArgumentException("Unknown aggregation $op")
        }
        return Aggregate(missing.isEmpty() && present.isNotEmpty(), v, missing).also {
            if (op == "sum" && present.isNotEmpty()) it.partialValue = present.sum()
        }
    }

    /** Tabata This!: sum over blocks of the minimum of each block's intervals (REQ-DM23). */
    fun sumOfBlockMinimums(blocks: List<List<Int?>>): Aggregate {
        val mins = blocks.map { b -> if (b.any { it == null } || b.isEmpty()) null else b.filterNotNull().min().toDouble() }
        return aggregate(mins, "sum")
    }

    /**
     * Sums rounds+reps interval scores under one counting rule ([repsPerRound], H7). Missing
     * intervals make the sum incomplete; they are never replaced by zero.
     */
    fun sumRoundsReps(intervals: List<Score.RoundsReps?>, repsPerRound: Int): Aggregate =
        aggregate(intervals.map { it?.let { s -> (s.rounds * repsPerRound + s.extraReps).toDouble() } }, "sum")

    /** Mixed measures need an explicit points rule before they can be summed (REQ-DM23). */
    fun sumMixed(values: List<Pair<String, Double>>, ruleId: String?): Aggregate {
        val kinds = values.map { it.first }.toSet()
        require(kinds.size <= 1 || ruleId != null) { "Cannot sum ${kinds.joinToString()} without a counting rule" }
        return aggregate(values.map { it.second }, "sum")
    }

    /** Heaviest fully completed set at the prescribed reps; failed/partial sets never count. */
    fun bestLoad(sets: List<SetObservation>, unit: MassUnit): Score.Load? {
        val best = sets.filter { it.status == SetStatus.DONE && it.load != null }
            .filter { s -> s.targetReps == null || (s.actualReps ?: 0) >= s.targetReps }
            .maxByOrNull { MassMath.toKgPhysical(it.load!!) } ?: return null
        val m = MassMath.convertPhysical(best.load!!, unit)
        return Score.Load(MassMath.clean(m.value), unit)
    }

    /**
     * Volume = Σ completed reps × per-implement physical load × implement count (REQ-DM22).
     * Partial sets contribute their completed reps; missing reps/loads contribute nothing.
     */
    fun volume(sets: List<SetObservation>, unit: MassUnit): Score.Volume {
        val kg = sets.filter { it.status == SetStatus.DONE || it.status == SetStatus.PARTIAL }
            .sumOf { s -> val r = s.actualReps ?: 0; val l = s.load ?: return@sumOf 0.0; r * MassMath.toKgPhysical(l) * s.implementCount }
        return Score.Volume(MassMath.clean(MassMath.fromKg(kg, unit)), unit)
    }

    /** Converts a measured load into the athlete unit physically (actual results, REQ-DM19). */
    fun physical(m: Mass, unit: MassUnit): Mass = MassMath.convertPhysical(m, unit)
}

/** A timed phase of a session for the clock policy (REQ-WR08, §6.12). */
sealed interface Phase {
    val sec: Int
    data class Prep(override val sec: Int) : Phase
    data class Work(override val sec: Int, val part: Int) : Phase
    data class InClockRest(override val sec: Int, val part: Int) : Phase
    data class PartEntry(override val sec: Int) : Phase
    data class PrescribedRest(override val sec: Int) : Phase
    data class Wait(override val sec: Int) : Phase
    data class Pause(override val sec: Int) : Phase
}

/** Wall, active and per-part score seconds. */
data class ClockTotals(val wallSec: Int, val activeSec: Int, val partScoreSec: Map<Int, Int>)

/**
 * Clock policy (REQ-WR08): wall time counts everything since first work start; active time is
 * work plus prescribed rest; part score time is work plus in-clock rest. Pure and independent of
 * real time, so the §6.12 example is a unit test.
 */
object ClockPolicy {
    fun totals(phases: List<Phase>): ClockTotals {
        val start = phases.indexOfFirst { it is Phase.Work }
        val run = if (start < 0) emptyList() else phases.drop(start)
        val wall = run.sumOf { it.sec }
        val active = run.filter { it is Phase.Work || it is Phase.InClockRest || it is Phase.PrescribedRest }.sumOf { it.sec }
        val score = mutableMapOf<Int, Int>()
        run.forEach {
            when (it) {
                is Phase.Work -> score.merge(it.part, it.sec, Int::plus)
                is Phase.InClockRest -> score.merge(it.part, it.sec, Int::plus)
                else -> Unit
            }
        }
        return ClockTotals(wall, active, score)
    }
}
