package com.wodcapture.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/** Score union (REQ-DM22). Manual/derived metadata lives in [PartScore]. */
@Serializable
@JsonClassDiscriminator("type")
sealed interface Score {
    @Serializable @SerialName("time")
    data class Time(val seconds: Int) : Score

    @Serializable @SerialName("rounds_reps")
    data class RoundsReps(val rounds: Int, val extraReps: Int, val ruleId: String? = null) : Score

    @Serializable @SerialName("reps")
    data class Reps(val count: Int) : Score

    @Serializable @SerialName("load")
    data class Load(val value: Double, val unit: MassUnit) : Score

    @Serializable @SerialName("total_load")
    data class TotalLoad(val value: Double, val unit: MassUnit) : Score

    /** Volume = Σ completed reps × per-implement physical load × implement count. */
    @Serializable @SerialName("volume")
    data class Volume(val value: Double, val massUnit: MassUnit, val convention: String = VOLUME_CONVENTION) : Score

    @Serializable @SerialName("calories")
    data class Calories(val count: Int) : Score

    @Serializable @SerialName("distance")
    data class Distance(val value: Double, val unit: DistanceUnit) : Score

    @Serializable @SerialName("points")
    data class Points(val value: Double, val ruleId: String) : Score

    @Serializable @SerialName("minutes_reps")
    data class MinutesReps(val completedIntervals: Int, val extraReps: Int) : Score

    @Serializable @SerialName("completion")
    data class Completion(val completed: Boolean) : Score

    @Serializable @SerialName("text")
    data class Text(val text: String) : Score

    @Serializable @SerialName("none")
    data object None : Score

    companion object {
        const val VOLUME_CONVENTION = "reps_x_load_x_count"
    }
}

/** A typed score with provenance (REQ-DM22, REQ-DM25). */
@Serializable
data class PartScore(
    val score: Score,
    val manual: Boolean = false,
    val ruleVersion: Int = 1,
    /** Athlete edited a measured value; measured timing stays untouched. */
    val corrected: Boolean = false,
    val correctedAt: Long? = null,
    val correctionReason: String? = null,
)

@Serializable
enum class ResultStatus(val wire: String) {
    @SerialName("completed") COMPLETED("completed"),
    @SerialName("capped") CAPPED("capped"),
    @SerialName("stopped_early") STOPPED_EARLY("stopped_early"),
    @SerialName("interrupted") INTERRUPTED("interrupted"),
    @SerialName("skipped") SKIPPED("skipped");

    companion object {
        fun fromWire(s: String): ResultStatus? = entries.firstOrNull { it.wire == s }
    }
}

@Serializable
enum class SetStatus(val wire: String) {
    @SerialName("done") DONE("done"),
    @SerialName("partial") PARTIAL("partial"),
    @SerialName("failed") FAILED("failed"),
    @SerialName("skipped") SKIPPED("skipped");

    companion object {
        fun fromWire(s: String): SetStatus? = entries.firstOrNull { it.wire == s }
    }
}

/** Observed set (REQ-DM09/DM21). `null` = missing, never zero. */
@Serializable
data class SetObservation(
    val setId: String,
    val index: Int,
    val targetReps: Int? = null,
    val actualReps: Int? = null,
    val load: Mass? = null,
    val implementCount: Int = 1,
    val status: SetStatus,
    val atMs: Long? = null,
    val rpe: Double? = null,
)

/** Labeled progress event (`rd`, `mv`, `fin`, `cap`, `undo`) at monotonic run ms. */
@Serializable
data class ProgressEvent(val index: Int, val kind: String, val atMs: Long)

/** Actual load used for a loaded metcon item, keyed by stable node ID. */
@Serializable
data class LoadObservation(val nodeId: String, val load: Mass, val implementCount: Int = 1)

@Serializable
enum class ResultSource {
    @SerialName("watch") WATCH,
    @SerialName("manual") MANUAL,
}

/** Part result (REQ-DM09). Watch results arrive with revision 1; phone edits add revisions. */
@Serializable
data class WorkoutResult(
    val id: String,
    val sessionId: String,
    val workoutId: String,
    val workoutRevision: Int,
    val planId: String? = null,
    val planRevision: Int? = null,
    val partId: String,
    val resultRevision: Int = 1,
    val startedAt: Long,
    val status: ResultStatus,
    val elapsedSec: Int? = null,
    val activeSec: Int? = null,
    val workSec: Int? = null,
    val score: PartScore? = null,
    val level: String? = null,
    val sets: List<SetObservation> = emptyList(),
    val events: List<ProgressEvent> = emptyList(),
    val loads: List<LoadObservation> = emptyList(),
    val timerEdits: Map<String, Int> = emptyMap(),
    val hrAvg: Int? = null,
    val hrMax: Int? = null,
    val calories: Int? = null,
    val fit: String? = null,
    val source: ResultSource = ResultSource.WATCH,
    val device: String? = null,
    val appVersion: String? = null,
    val notes: String? = null,
)

/** Session entry: part, its result (null when skipped/unreached) and status (REQ-SY16). */
@Serializable
data class SessionEntry(val partId: String, val resultId: String?, val status: ResultStatus)

/** Session summary (REQ-DM15, REQ-SY16). */
@Serializable
data class Session(
    val id: String,
    val revision: Int = 1,
    val planId: String,
    val planRevision: Int,
    val entries: List<SessionEntry>,
    val startedAt: Long,
    val endedAt: Long,
    val elapsedSec: Int,
    val activeSec: Int,
    val hrAvg: Int? = null,
    val hrMax: Int? = null,
    val calories: Int? = null,
    val restartOfSessionId: String? = null,
    val fit: String,
)
