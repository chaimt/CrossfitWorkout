package com.wodcapture.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/** Current canonical workout schema version (REQ-DM01, §4.6). */
const val WORKOUT_SCHEMA_VERSION = 1

/**
 * Canonical workout (REQ-DM02). Editing creates a new [revision]; node IDs of unchanged
 * nodes are retained (REQ-DM15). Timestamps are UTC epoch seconds.
 */
@Serializable
data class Workout(
    val schemaVersion: Int = WORKOUT_SCHEMA_VERSION,
    val id: String,
    val revision: Int = 1,
    val title: String = "",
    /** Scheduled local date, `YYYY-MM-DD`. */
    val date: String? = null,
    /** IANA zone of [date] (REQ-WN01). */
    val scheduleTimeZone: String? = null,
    val source: Source = Source(),
    val parts: List<Part> = emptyList(),
    val notes: String? = null,
    val tags: List<String> = emptyList(),
    val createdAt: Long? = null,
    val updatedAt: Long? = null,
)

/** Where a workout came from, with the parser/model/prompt versions used (REQ-DM02). */
@Serializable
data class Source(
    val kind: SourceKind = SourceKind.MANUAL,
    val imageRefs: List<String> = emptyList(),
    val rawText: String? = null,
    val parser: String? = null,
    val model: String? = null,
    val promptVersion: String? = null,
)

@Serializable
enum class SourceKind {
    @SerialName("photo") PHOTO,
    @SerialName("text") TEXT,
    @SerialName("manual") MANUAL,
    @SerialName("duplicate") DUPLICATE,
    @SerialName("benchmark") BENCHMARK,
}

/** Section of a class (REQ-DM03). Warm-up and cool-down default to not running on the watch. */
@Serializable
enum class PartKind {
    @SerialName("warmup") WARMUP,
    @SerialName("skill") SKILL,
    @SerialName("strength") STRENGTH,
    @SerialName("metcon") METCON,
    @SerialName("accessory") ACCESSORY,
    @SerialName("cooldown") COOLDOWN,
    @SerialName("other") OTHER,
}

/**
 * One part of a workout (REQ-DM03). [body] is authoritative; [format] controls the clock and
 * only references body nodes (REQ-DM16).
 */
@Serializable
data class Part(
    val id: String,
    val label: String = "",
    val kind: PartKind = PartKind.METCON,
    /** Optional name such as a benchmark ("Fran"). */
    val title: String? = null,
    val format: Format,
    val body: List<BodyNode> = emptyList(),
    val scoring: Scoring = Scoring(),
    val levels: List<Level> = emptyList(),
    /** Name of the chosen level; `null` = the base prescription ("Rx"). */
    val chosenLevel: String? = null,
    val restAfterSec: Int? = null,
    val notes: String? = null,
    /** `null` = default by [kind] (REQ-AE06). */
    val runOnWatch: Boolean? = null,
    val hints: PartHints? = null,
    /** Explicitly reviewed manual-timer fallback (REQ-DM18). */
    val fallback: Fallback? = null,
) {
    /** Effective run-on-watch flag: include warm-up by default; cool-down stays opt-in. */
    val effectiveRunOnWatch: Boolean
        get() = runOnWatch ?: (kind != PartKind.COOLDOWN)
}

/** Non-executable context the parser preserves (partner rules, chipper, tiebreaks). */
@Serializable
data class PartHints(
    val partner: PartnerHint? = null,
    val chipper: Boolean = false,
    /** Node IDs after which a tiebreak time is recorded (v1). */
    val tiebreakAfter: List<String> = emptyList(),
)

/** Team instructions preserved verbatim; dedicated logging is Later (REQ-WM13). */
@Serializable
data class PartnerHint(
    val teamSize: Int,
    val splitRule: String? = null,
    val synchro: Boolean = false,
    val notes: String? = null,
)

/** Reviewed fallback acceptance: plain clock plus manual score (REQ-DM18). */
@Serializable
data class Fallback(
    val accepted: Boolean = false,
    val timer: FallbackTimer = FallbackTimer.COUNT_UP,
    /** Countdown length for [FallbackTimer.COUNT_DOWN]. */
    val durationSec: Int? = null,
    /** Optional cap for [FallbackTimer.COUNT_UP]. */
    val capSec: Int? = null,
    val reason: String? = null,
)

@Serializable
enum class FallbackTimer {
    @SerialName("count_up") COUNT_UP,
    @SerialName("count_down") COUNT_DOWN,
}

// ---------------------------------------------------------------------------------------------
// Body tree (REQ-DM05, REQ-DM16)
// ---------------------------------------------------------------------------------------------

/** A node of the authoritative body: item, group or rest. Every node has a stable ID. */
@Serializable
@JsonClassDiscriminator("type")
sealed interface BodyNode {
    val id: String
}

/** A movement prescription (REQ-DM05). */
@Serializable
@SerialName("item")
data class Item(
    override val id: String,
    val movementId: String? = null,
    val name: String,
    /** Explicitly shortened display text chosen in review (REQ-SY06); original stays in [name]. */
    val displayName: String? = null,
    val rawText: String? = null,
    val measure: Measure? = null,
    val load: LoadSpec? = null,
    val height: Length? = null,
    val target: Length? = null,
    val implement: String? = null,
    /** Implements used at once (2 = double DB/KB). Load is per implement (REQ-DM19). */
    val count: Int = 1,
    val side: Side? = null,
    val modifiers: List<String> = emptyList(),
    val role: ItemRole = ItemRole.MAIN,
    val progression: Progression? = null,
    /** Strength sets for this movement (REQ-DM06); empty for metcon items. */
    val sets: List<StrengthSet> = emptyList(),
    val notes: String? = null,
) : BodyNode

/** Group of children (REQ-DM16): rounds ×N, a sequence ("then"), a complex or a superset. */
@Serializable
@SerialName("group")
data class Group(
    override val id: String,
    val kind: GroupKind = GroupKind.ROUNDS,
    /** Repeat count, 1–200. With a [repScheme] it equals the scheme length. */
    val repeat: Int = 1,
    /** Reps per round applied to every child whose measure is `rep_scheme` (21-15-9). */
    val repScheme: List<Int> = emptyList(),
    val children: List<BodyNode> = emptyList(),
    val label: String? = null,
) : BodyNode

/** Prescribed rest (REQ-DM16). [inClock] = counts on the running clock and in part score time. */
@Serializable
@SerialName("rest")
data class Rest(
    override val id: String,
    val sec: Int,
    val inClock: Boolean = true,
    /** "rest 3:00 b/w rounds": inside a repeated group, no rest after the final round. */
    val betweenOnly: Boolean = false,
) : BodyNode

@Serializable
enum class GroupKind {
    @SerialName("rounds") ROUNDS,
    @SerialName("sequence") SEQUENCE,
    @SerialName("complex") COMPLEX,
    @SerialName("superset") SUPERSET,
}

@Serializable
enum class Side {
    @SerialName("each") EACH,
    @SerialName("alternating") ALTERNATING,
    @SerialName("total") TOTAL,
}

@Serializable
enum class ItemRole {
    @SerialName("buy_in") BUY_IN,
    @SerialName("main") MAIN,
    @SerialName("cash_out") CASH_OUT,
}

/** Explicit per-item progression, e.g. "+3 reps each round" (Open 25.1, H4). */
@Serializable
data class Progression(val repsPerRound: Int)

/**
 * One measure of an item (REQ-DM05): a single value, e.g. `15 cal`.
 * A board pair like `15/12 cal` is unparsed text: the single value stays unset
 * and the raw pair is preserved in the item/measure notes for the athlete to edit.
 */
@Serializable
data class Measure(
    val kind: MeasureKind,
    val value: Double? = null,
    /** Distance unit for [MeasureKind.DISTANCE]. */
    val unit: DistanceUnit? = null,
)

@Serializable
enum class MeasureKind {
    @SerialName("reps") REPS,
    /** Reps come from the enclosing group's rep scheme. */
    @SerialName("rep_scheme") REP_SCHEME,
    @SerialName("max") MAX,
    @SerialName("calories") CALORIES,
    @SerialName("distance") DISTANCE,
    @SerialName("duration") DURATION,
}

// ---------------------------------------------------------------------------------------------
// Loads (REQ-DM06, REQ-DM11, REQ-DM19)
// ---------------------------------------------------------------------------------------------

/** How a load is prescribed. Stored as written; conversion happens only for planning (D8). */
@Serializable
@JsonClassDiscriminator("kind")
sealed interface LoadSpec

@Serializable
@SerialName("absolute")
data class AbsoluteLoad(val mass: Mass) : LoadSpec

/** % of a rep max. A range ([percentMax]) needs a [selectedPercent] before resolution. */
@Serializable
@SerialName("percent_1rm")
data class PercentOfMax(
    val percent: Double,
    val percentMax: Double? = null,
    val selectedPercent: Double? = null,
    /** Reference movement; `null` = the item's own movement. */
    val ofMovementId: String? = null,
    val repMax: Int = 1,
) : LoadSpec

@Serializable
@SerialName("percent_bodyweight")
data class PercentOfBodyweight(val percent: Double) : LoadSpec

@Serializable
@SerialName("bodyweight")
data object BodyweightLoad : LoadSpec

@Serializable
@SerialName("rpe")
data class RpeLoad(val value: Double, val rir: Int? = null) : LoadSpec

/** Back-off relative to the top set ("-10%"); runtime-dependent, entered manually in MVP. */
@Serializable
@SerialName("relative_to_top")
data class RelativeToTopLoad(val deltaPercent: Double) : LoadSpec

@Serializable
@SerialName("athlete_choice")
data class AthleteChoiceLoad(val text: String = "") : LoadSpec

@Serializable
@SerialName("none")
data object NoLoad : LoadSpec

// ---------------------------------------------------------------------------------------------
// Strength sets (REQ-DM06)
// ---------------------------------------------------------------------------------------------

/** A prescribed strength set; actual made/partial/missed belongs to the Result. */
@Serializable
data class StrengthSet(
    val id: String,
    val reps: RepTarget = RepTarget(),
    /** `null` = inherit the item's load. */
    val load: LoadSpec? = null,
    val restSec: Int? = null,
    val tempo: String? = null,
    val pauseSec: Int? = null,
    val rpe: Double? = null,
    val rir: Int? = null,
    val cluster: Cluster? = null,
    val setType: SetType = SetType.WORKING,
)

/** Rep target: exact ([value]), a range ([value]..[upTo]) or max reps. */
@Serializable
data class RepTarget(
    val value: Int? = null,
    val upTo: Int? = null,
    val max: Boolean = false,
    /** Reps count per side when the item side is `each`. */
    val perSide: Boolean = false,
) {
    /** Exact numeric target, or `null` when the watch must ask (range, max). */
    val exact: Int? get() = if (!max && upTo == null) value else null
}

@Serializable
data class Cluster(val miniSets: List<Int>, val intraRestSec: Int)

@Serializable
enum class SetType(val wire: String) {
    @SerialName("warmup") WARMUP("warmup"),
    @SerialName("working") WORKING("working"),
    @SerialName("top") TOP("top"),
    @SerialName("backoff") BACKOFF("backoff"),
    @SerialName("drop") DROP("backoff"),
    @SerialName("amrap") AMRAP("amrap"),
}

// ---------------------------------------------------------------------------------------------
// Formats (REQ-DM04)
// ---------------------------------------------------------------------------------------------

/** Clock and execution metadata. Parameters reference body nodes, never copy them. */
@Serializable
@JsonClassDiscriminator("type")
sealed interface Format

@Serializable
@SerialName("for_time")
data class ForTime(
    val capSec: Int? = null,
    val eachRoundForTime: Boolean = false,
) : Format

@Serializable
@SerialName("amrap")
data class Amrap(val durationSec: Int? = null) : Format

/** EMOM / E#MOM / alternating stations (H3). */
@Serializable
@SerialName("emom")
data class Emom(
    val intervalSec: Int? = null,
    val intervalCount: Int? = null,
    /** Stations cycled in order; empty = every interval runs the whole body. */
    val stations: List<Station> = emptyList(),
    val remainder: Remainder = Remainder.NONE,
) : Format

/** One EMOM station. `nodeId == null` = rest station. */
@Serializable
data class Station(
    val nodeId: String? = null,
    /** Partial-minute work window; the rest of the interval is in-clock rest. */
    val workSec: Int? = null,
)

@Serializable
enum class Remainder {
    @SerialName("none") NONE,
    @SerialName("amrap") AMRAP,
    @SerialName("max") MAX,
}

@Serializable
@SerialName("tabata")
data class Tabata(
    val workSec: Int = 20,
    val restSec: Int = 10,
    val rounds: Int = 8,
    /** One block per referenced node, run consecutively; empty = one block per top-level item. */
    val blocks: List<String> = emptyList(),
    val restBetweenBlocksSec: Int = 0,
) : Format

/** Work/rest intervals (H7). Final rest is omitted unless [finalRest] (my call). */
@Serializable
@SerialName("intervals")
data class Intervals(
    val rounds: Int? = null,
    val workSec: Int? = null,
    val restSec: Int = 0,
    val finalRest: Boolean = false,
    val inner: InnerMode = InnerMode.AMRAP,
) : Format

@Serializable
enum class InnerMode {
    @SerialName("amrap") AMRAP,
    @SerialName("for_time") FOR_TIME,
    @SerialName("max_effort") MAX_EFFORT,
    @SerialName("none") NONE,
}

@Serializable
@SerialName("death_by")
data class DeathBy(
    val intervalSec: Int = 60,
    val startReps: Int = 1,
    val increment: Int = 1,
    val maxRounds: Int? = null,
) : Format

@Serializable
@SerialName("max_effort")
data class MaxEffort(
    val windowSec: Int? = null,
    val attempts: Int? = null,
    val measure: String = "reps",
) : Format

@Serializable
@SerialName("strength_sets")
data class StrengthSets(
    /** Set node IDs in run order; empty = every item's sets in body order. */
    val setRefs: List<String> = emptyList(),
) : Format

@Serializable
@SerialName("build_to_max")
data class BuildToMax(
    val repsPerSet: Int = 1,
    val windowSec: Int? = null,
    val attemptLimit: Int? = null,
) : Format

@Serializable
@SerialName("not_for_time")
data object NotForTime : Format

@Serializable
@SerialName("custom_segments")
data class CustomSegments(val segments: List<CustomSegment> = emptyList()) : Format

@Serializable
data class CustomSegment(val kind: String, val durationSec: Int? = null, val label: String? = null)

// ---------------------------------------------------------------------------------------------
// Scoring (REQ-DM07, §4.5) and levels (REQ-DM08)
// ---------------------------------------------------------------------------------------------

@Serializable
enum class ScoreType(val wire: String) {
    @SerialName("time") TIME("time"),
    @SerialName("rounds_reps") ROUNDS_REPS("rounds_reps"),
    @SerialName("reps") REPS("reps"),
    @SerialName("load") LOAD("load"),
    @SerialName("total_load") TOTAL_LOAD("load"),
    @SerialName("volume") VOLUME("load"),
    @SerialName("calories") CALORIES("calories"),
    @SerialName("distance") DISTANCE("distance"),
    @SerialName("points") POINTS("reps"),
    @SerialName("minutes_reps") MINUTES_REPS("minutes_reps"),
    @SerialName("completion") COMPLETION("completion"),
    @SerialName("text") TEXT("text"),
    @SerialName("none") NONE("none"),
}

@Serializable
enum class AggregationOp(val wire: String) {
    @SerialName("sum") SUM("sum"),
    @SerialName("min") MIN("min"),
    @SerialName("max") MAX("max"),
    @SerialName("average") AVERAGE("each"),
    @SerialName("each") EACH("each"),
    /** Sum over blocks of the minimum inside each block (Tabata This!). */
    @SerialName("sum_of_block_min") SUM_OF_BLOCK_MIN("min"),
}

/** Part scoring rule (REQ-DM07). */
@Serializable
data class Scoring(
    val type: ScoreType = ScoreType.TIME,
    val aggregation: AggregationOp? = null,
    /** Explicit counting rule ID that makes mixed measures summable (REQ-DM23). */
    val countingRule: String? = null,
    val distanceUnit: DistanceUnit? = null,
    /** Score is entered manually (no declared counting rule). */
    val manual: Boolean = false,
    val ruleVersion: Int = 1,
)

/** Alternative prescription ("Scaled") expressed as overrides of body nodes (REQ-DM08). */
@Serializable
data class Level(
    val name: String,
    val overrides: List<Override> = emptyList(),
)

/** Override of one node: replace the subtree, or patch movement/name/load/reps. */
@Serializable
data class Override(
    val targetId: String,
    val replace: BodyNode? = null,
    val movementId: String? = null,
    val name: String? = null,
    val load: LoadSpec? = null,
    val measure: Measure? = null,
)
