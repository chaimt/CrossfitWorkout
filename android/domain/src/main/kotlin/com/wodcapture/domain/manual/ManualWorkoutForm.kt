package com.wodcapture.domain.manual

import com.wodcapture.domain.IdSource
import com.wodcapture.domain.catalog.Movement
import com.wodcapture.domain.catalog.MovementCatalog
import com.wodcapture.domain.load.MassMath
import com.wodcapture.domain.model.DistanceUnit
import com.wodcapture.domain.model.FallbackTimer
import com.wodcapture.domain.model.LengthUnit
import com.wodcapture.domain.model.MassUnit
import com.wodcapture.domain.model.MeasureKind
import com.wodcapture.domain.model.PartKind
import com.wodcapture.domain.model.ScoreType
import com.wodcapture.domain.model.WorkoutSection

/**
 * State of the guided "New workout" form (REQ-AC09). It is deliberately simpler than the
 * canonical [com.wodcapture.domain.model.Workout]: the athlete picks a format and fills a few
 * fields, and [ManualWorkoutBuilder] turns that into the canonical model. Time fields are kept as
 * typed text (m:ss / h:mm:ss) so partial input never jumps while typing.
 */
data class ManualWorkoutForm(
    val title: String = "",
    /** Scheduled local date `YYYY-MM-DD` (default today). */
    val date: String,
    /** IANA zone of [date] (default: the phone's zone). */
    val timeZone: String,
    /**
     * Unit of every weight typed in the form. It is not chosen per entry: it follows the app's
     * Settings → Units ([com.wodcapture.domain.model.Profile.displayUnit]) (owner request 2026-11-05).
     */
    val loadUnit: MassUnit = MassUnit.KG,
    /**
     * Rounding step for weights the form computes (a % of the goal weight): Settings → smallest
     * total increment for [loadUnit].
     */
    val loadStep: Double = 1.0,
    val parts: List<ManualPartForm> = emptyList(),
)

/**
 * Formats offered by the form; each maps to one REQ-DM04 format (Custom → REQ-DM18 fallback).
 * Strength sets are the weights section; every other format can run in warm-up, metcon or the
 * trailing section — the part's kind decides which ([ManualForms.sectionOf]).
 */
enum class ManualFormat(val label: String, val section: WorkoutSection) {
    STRENGTH("Strength sets", WorkoutSection.WEIGHTS),
    FOR_QUALITY("For quality", WorkoutSection.METCON),
    FOR_TIME("For time", WorkoutSection.METCON),
    AMRAP("AMRAP", WorkoutSection.METCON),
    EMOM("EMOM / E#MOM", WorkoutSection.METCON),
    TABATA("Tabata", WorkoutSection.METCON),
    INTERVALS("Intervals", WorkoutSection.METCON),
    MAX_EFFORT("Max effort", WorkoutSection.METCON),
    CUSTOM("Custom", WorkoutSection.METCON),
    ;

    companion object {
        /**
         * The formats offered when adding a part to [section]: one lift for weights, one
         * for-quality list for warm-up, every timed format for metcon and the trailing section.
         */
        fun of(section: WorkoutSection): List<ManualFormat> = when (section) {
            WorkoutSection.WEIGHTS -> listOf(STRENGTH)
            WorkoutSection.WARMUP -> listOf(FOR_QUALITY)
            WorkoutSection.METCON, WorkoutSection.OTHER ->
                entries.filter { it != STRENGTH && it != FOR_QUALITY }
        }
    }
}

/** A heading of the weights picker and its lifts, A–Z. */
data class LiftGroup(val title: String, val lifts: List<Movement>)

/** The sections of the form, in class run order: warm-up first, then lift, metcon, cooldown last. */
val MANUAL_SECTIONS: List<WorkoutSection> =
    listOf(WorkoutSection.WARMUP, WorkoutSection.WEIGHTS, WorkoutSection.METCON, WorkoutSection.OTHER)

/**
 * Part kinds offered in each form section. A weights part is always strength and a warm-up part
 * is always a for-quality warm-up (owner request 2026-11-05, 2026-11-09: no accessory or skill
 * choice); parsed skill parts still group under Warm-up via [WorkoutSection.of].
 */
fun kindsFor(section: WorkoutSection): List<PartKind> = when (section) {
    WorkoutSection.WARMUP -> listOf(PartKind.WARMUP)
    WorkoutSection.WEIGHTS -> listOf(PartKind.STRENGTH)
    WorkoutSection.METCON -> listOf(PartKind.METCON)
    WorkoutSection.OTHER -> listOf(PartKind.ACCESSORY, PartKind.COOLDOWN, PartKind.OTHER)
}

/** What a max-effort part measures, with the score type it implies. */
enum class MaxEffortMeasure(val wire: String, val score: ScoreType) {
    REPS("reps", ScoreType.REPS),
    CALORIES("calories", ScoreType.CALORIES),
    DISTANCE("distance", ScoreType.DISTANCE),
    LOAD("load", ScoreType.LOAD),
}

/**
 * One part of the form. Only the fields of the chosen [format] are read; the others are kept so
 * switching formats back and forth does not lose what was typed.
 */
data class ManualPartForm(
    /** Becomes the part ID (stable across rebuilds while the form is open). */
    val id: String,
    val label: String = "",
    /** Optional name such as a benchmark ("Fran"). */
    val name: String = "",
    val kind: PartKind = PartKind.METCON,
    val format: ManualFormat = ManualFormat.FOR_TIME,
    /** For time: optional cap. */
    val capText: String = "",
    /** AMRAP duration; max-effort window; Custom countdown length or count-up cap. */
    val durationText: String = "",
    /** For time / Tabata / Intervals rounds. `null` = format default. */
    val rounds: Int? = null,
    /** For time rep scheme such as "21-15-9". */
    val repSchemeText: String = "",
    /** EMOM interval (blank = 1:00) and number of intervals. */
    val intervalText: String = "",
    val intervalCount: Int? = null,
    /** Tabata / Intervals work and rest windows (Tabata blanks = 0:20 / 0:10). */
    val workText: String = "",
    val restText: String = "",
    val maxMeasure: MaxEffortMeasure = MaxEffortMeasure.REPS,
    /** Max-effort attempts. */
    val attempts: Int? = null,
    /** Custom: the instructions, one watch line per text line. */
    val customText: String = "",
    val customTimer: FallbackTimer = FallbackTimer.COUNT_UP,
    val movements: List<ManualMovementForm> = emptyList(),
    /**
     * Strength sets: one lift per session, its load type and rest set once, then one row per set
     * with only weight and reps (owner request 2026-11-05).
     */
    val scheme: StrengthScheme = StrengthScheme(),
    val lift: ManualStrengthForm? = null,
    val sets: List<ManualSetForm> = emptyList(),
)

/**
 * Session-wide settings of a strength session. Every set is a weight; there is no load-type choice
 * (owner request 2026-11-06), so a hand-built session and a quick start look the same: % · weight ·
 * reps per set. The number of sets is the number of set rows, and rest between sets is not asked
 * for: the compiler uses Settings → Default strength rest.
 */
data class StrengthScheme(
    /**
     * Optional goal weight of the session, in the form's unit (owner request 2026-11-05). With it,
     * each set's weight and its % of the goal are two views of one value: editing either updates
     * the other ([ManualForms.withSetWeight], [ManualForms.withSetPercent]).
     */
    val goalWeight: Double? = null,
)

/** A movement row of a metcon part (REQ-DM05). */
data class ManualMovementForm(
    /** Becomes the item ID. */
    val id: String,
    val name: String = "",
    /** Catalog ID when picked from autocomplete; `null` = free text (allowed, REQ-DM12). */
    val movementId: String? = null,
    val implement: String? = null,
    val measure: MeasureKind = MeasureKind.REPS,
    /** Reps, calories or distance value. */
    val amount: Double? = null,
    val distanceUnit: DistanceUnit = DistanceUnit.M,
    /** Duration measure as m:ss. */
    val durationText: String = "",
    /** Load (per implement), in the form's [ManualWorkoutForm.loadUnit]. */
    val load: Double? = null,
    /** Implements held at once (2 = double DB/KB). */
    val implementCount: Int = 1,
    /** Box height or wall-ball target. */
    val height: Double? = null,
    val heightUnit: LengthUnit = LengthUnit.IN,
    val heightIsTarget: Boolean = false,
)

/** The one lift of a strength session (REQ-DM06), picked from the list once for the whole session. */
data class ManualStrengthForm(
    /** Becomes the item ID. */
    val id: String,
    val name: String = "",
    val movementId: String? = null,
    val implement: String? = null,
)

/** One set of a strength session: its weight ([load], in the form's unit), reps and % of the goal. */
data class ManualSetForm(
    /** Becomes the set ID. */
    val id: String,
    val load: Double? = null,
    val reps: Int? = null,
    /**
     * [load] as a % of the session's goal weight. It is an input aid kept in step with [load] once
     * a goal is set (before that it waits for the goal, e.g. a quick start's ramp); the workout
     * stores only the weight.
     */
    val ofGoal: Double? = null,
)

/**
 * Pure edit helpers for the form (REQ-AC09), shared by the screen and tests so the UI stays thin.
 */
object ManualForms {
    private const val SUGGESTIONS = 4

    /**
     * A new part. Its label stays blank so the builder letters parts by position (a single part
     * shows "W" on the watch, several get A, B, C…) and reordering never leaves stale letters.
     */
    @Suppress("UNUSED_PARAMETER")
    fun newPart(ids: IdSource, index: Int, format: ManualFormat = ManualFormat.FOR_TIME): ManualPartForm =
        withFormat(ManualPartForm(id = ids.newId()), format, ids)

    /** The letter the builder gives a part with a blank label at [index] of [count] parts. */
    fun defaultLabel(index: Int, count: Int): String = if (count == 1) "" else ('A' + index).toString()

    /** The label [part] will carry: what the athlete typed, else its position letter. */
    fun effectiveLabel(form: ManualWorkoutForm, part: ManualPartForm): String =
        part.label.trim().ifBlank { defaultLabel(form.parts.indexOfFirst { it.id == part.id }, form.parts.size) }

    /** Which form section [part] sits in: strength sets lift; the kind decides the rest. */
    fun sectionOf(part: ManualPartForm): WorkoutSection = when {
        part.format == ManualFormat.STRENGTH -> WorkoutSection.WEIGHTS
        part.kind == PartKind.WARMUP || part.kind == PartKind.SKILL -> WorkoutSection.WARMUP
        part.kind == PartKind.METCON -> WorkoutSection.METCON
        else -> WorkoutSection.OTHER
    }

    /** Parts of [form] in [section], in workout order. */
    fun partsIn(form: ManualWorkoutForm, section: WorkoutSection): List<ManualPartForm> = form.parts.filter { sectionOf(it) == section }

    /** Warm-up first, then weights, then metcon, trailing work last — order inside each section kept. */
    fun ordered(parts: List<ManualPartForm>): List<ManualPartForm> = MANUAL_SECTIONS.flatMap { s -> parts.filter { sectionOf(it) == s } }

    /** Adds a [format] part at the end of [section]. */
    fun addPart(form: ManualWorkoutForm, section: WorkoutSection, format: ManualFormat, ids: IdSource): ManualWorkoutForm {
        val seedKind = when (section) {
            WorkoutSection.WARMUP -> PartKind.WARMUP
            WorkoutSection.WEIGHTS -> PartKind.STRENGTH
            WorkoutSection.METCON -> PartKind.METCON
            WorkoutSection.OTHER -> PartKind.COOLDOWN
        }
        val seeded = withFormat(ManualPartForm(id = ids.newId(), kind = seedKind), format, ids)
        return form.copy(parts = ordered(form.parts + seeded))
    }

    fun removePart(form: ManualWorkoutForm, partId: String): ManualWorkoutForm = form.copy(parts = form.parts.filter { it.id != partId })

    /** Replaces part [partId] with [f] of it; a format change across sections moves it. */
    fun updatePart(form: ManualWorkoutForm, partId: String, f: (ManualPartForm) -> ManualPartForm): ManualWorkoutForm =
        form.copy(parts = ordered(form.parts.map { if (it.id == partId) f(it) else it }))

    /** True for a part nobody has typed into yet (so a template may replace it). */
    fun isUntouched(part: ManualPartForm): Boolean =
        part.name.isBlank() && part.label.isBlank() && part.customText.isBlank() &&
            part.movements.all { it.name.isBlank() } && part.lift?.name.isNullOrBlank()

    fun newMovement(ids: IdSource, measure: MeasureKind = MeasureKind.REPS): ManualMovementForm =
        ManualMovementForm(id = ids.newId(), measure = measure)

    /** A movement row for [part]: follows the part's rep scheme when there is one. */
    fun newMovementFor(part: ManualPartForm, ids: IdSource): ManualMovementForm =
        newMovement(ids, if (usesScheme(part)) MeasureKind.REP_SCHEME else MeasureKind.REPS)

    /**
     * A blank form for [date] in [timeZone] with one For-time metcon part (REQ-AC09 defaults);
     * weights are in [loadUnit], the Settings unit.
     */
    fun blank(ids: IdSource, date: String, timeZone: String, loadUnit: MassUnit = MassUnit.KG, loadStep: Double = 1.0): ManualWorkoutForm =
        ManualWorkoutForm(date = date, timeZone = timeZone, loadUnit = loadUnit, loadStep = loadStep, parts = listOf(newPart(ids, 0)))

    fun newLift(ids: IdSource): ManualStrengthForm = ManualStrengthForm(id = ids.newId())

    /**
     * Typing a set's weight: with a goal weight, its % of the goal follows. Without a goal there
     * is nothing to compare against, so the % is kept.
     */
    fun withSetWeight(set: ManualSetForm, weight: Double?, goal: Double?): ManualSetForm =
        set.copy(load = weight, ofGoal = if (hasGoal(goal)) percentOf(weight, goal) else set.ofGoal)

    /**
     * Typing a set's % of the goal: its weight becomes goal × %, rounded to [step] (Settings →
     * smallest increment). Without a goal there is nothing to compute, so the weight is kept.
     */
    fun withSetPercent(set: ManualSetForm, percent: Double?, goal: Double?, step: Double): ManualSetForm =
        set.copy(ofGoal = percent, load = weightOf(percent, goal, step) ?: set.load)

    /**
     * Changing the session goal: sets given as a % get their weight recomputed; sets given only
     * as a weight get their % of the new goal. Clearing the goal keeps every set as it is, so
     * retyping the goal does not lose the %s.
     */
    fun withGoal(part: ManualPartForm, goal: Double?, step: Double): ManualPartForm = part.copy(
        scheme = part.scheme.copy(goalWeight = goal),
        sets = if (!hasGoal(goal)) part.sets else part.sets.map { s ->
            val computed = weightOf(s.ofGoal, goal, step)
            if (computed != null) s.copy(load = computed) else s.copy(ofGoal = percentOf(s.load, goal))
        },
    )

    private fun hasGoal(goal: Double?): Boolean = goal != null && goal > 0

    private fun weightOf(percent: Double?, goal: Double?, step: Double): Double? {
        if (percent == null || goal == null || !hasGoal(goal)) return null
        return MassMath.roundToStep(goal * percent / 100, step.takeIf { it > 0 } ?: 1.0)
    }

    /** % of [goal], to one decimal; `null` when either is missing. */
    private fun percentOf(weight: Double?, goal: Double?): Double? {
        if (weight == null || goal == null || !hasGoal(goal)) return null
        return Math.round(weight / goal * 1000) / 10.0
    }

    /** A new set for [part]: it repeats the last set's weight and reps, so 5 × 5 is five taps. */
    fun newSet(part: ManualPartForm, ids: IdSource): ManualSetForm =
        part.sets.lastOrNull()?.copy(id = ids.newId()) ?: ManualSetForm(id = ids.newId())

    /**
     * Switches [part] to [format]: the kind follows the format and its section (strength sets →
     * always strength; a move into warm-up picks warm-up, into the trailing section picks
     * cool-down, unless the current kind already fits), and a strength part gets its lift and a
     * first set so the form never shows an empty card. Weights parts have no label or name of
     * their own (owner request 2026-11-05), so moving a part there clears them rather than
     * keeping hidden values.
     */
    fun withFormat(part: ManualPartForm, format: ManualFormat, ids: IdSource): ManualPartForm {
        val targetSection = when {
            format == ManualFormat.STRENGTH -> WorkoutSection.WEIGHTS
            format == ManualFormat.FOR_QUALITY -> WorkoutSection.WARMUP
            part.kind == PartKind.WARMUP || part.kind == PartKind.SKILL -> WorkoutSection.WARMUP
            part.kind == PartKind.METCON -> WorkoutSection.METCON
            else -> WorkoutSection.OTHER
        }
        val kind = when {
            format == ManualFormat.STRENGTH -> PartKind.STRENGTH
            format == ManualFormat.FOR_QUALITY -> PartKind.WARMUP
            part.kind in kindsFor(targetSection) -> part.kind
            targetSection == WorkoutSection.WARMUP -> PartKind.WARMUP
            targetSection == WorkoutSection.METCON -> PartKind.METCON
            else -> PartKind.COOLDOWN
        }
        val strength = format == ManualFormat.STRENGTH
        val lift = if (strength && part.lift == null) newLift(ids) else part.lift
        val sets = if (strength && part.sets.isEmpty()) listOf(ManualSetForm(id = ids.newId())) else part.sets
        val movements = if (format != ManualFormat.STRENGTH && format != ManualFormat.CUSTOM && part.movements.isEmpty()) {
            listOf(newMovement(ids))
        } else part.movements
        val weights = format == ManualFormat.STRENGTH
        return part.copy(
            format = format,
            kind = kind,
            label = if (weights) "" else part.label,
            name = if (weights) "" else part.name,
            lift = lift,
            sets = sets,
            movements = movements,
        )
    }

    /** Whether movement rows of [part] take their reps from a part rep scheme. */
    fun usesScheme(part: ManualPartForm): Boolean = part.format == ManualFormat.FOR_TIME && part.repSchemeText.isNotBlank()

    /**
     * Sets the For-time rep scheme text. Rows without their own rep count follow the scheme while
     * one is present and go back to plain reps when it is cleared; rows with a count are untouched.
     */
    fun withRepScheme(part: ManualPartForm, text: String): ManualPartForm {
        val updated = part.copy(repSchemeText = text)
        val scheme = usesScheme(updated)
        return updated.copy(movements = updated.movements.map { m ->
            when {
                scheme && m.measure == MeasureKind.REPS && m.amount == null -> m.copy(measure = MeasureKind.REP_SCHEME)
                !scheme && m.measure == MeasureKind.REP_SCHEME -> m.copy(measure = MeasureKind.REPS)
                else -> m
            }
        })
    }

    /**
     * Applies a warm-up list pick to a movement row: the row keeps its measure and amount
     * and takes the owner's label plus the catalog ID, implement and default units
     * (none of the 15 needs a load).
     */
    fun pickWarmup(row: ManualMovementForm, m: Movement): ManualMovementForm = row.copy(
        name = warmupLabel(m.id, m.name),
        movementId = m.id,
        implement = implementOf(m),
        distanceUnit = distanceUnit(m) ?: row.distanceUnit,
        heightIsTarget = m.target != null,
        heightUnit = (m.target ?: m.height)?.m?.u?.let(::lengthUnit) ?: row.heightUnit,
    )

    /** Applies a catalog pick to a movement row: name, ID, implement and the catalog's default measure. */
    fun pick(row: ManualMovementForm, m: Movement, schemePart: Boolean): ManualMovementForm {
        val measure = defaultMeasure(m, schemePart)
        return row.copy(
            name = m.name,
            movementId = m.id,
            implement = implementOf(m),
            measure = measure,
            distanceUnit = distanceUnit(m) ?: row.distanceUnit,
            heightIsTarget = m.target != null,
            heightUnit = (m.target ?: m.height)?.m?.u?.let(::lengthUnit) ?: row.heightUnit,
        )
    }

    /** Applies a catalog pick to the session lift. */
    fun pick(row: ManualStrengthForm, m: Movement): ManualStrengthForm =
        row.copy(name = m.name, movementId = m.id, implement = implementOf(m))

    /**
     * Catalog autocomplete (Appendix E): names containing the query and aliases starting with it.
     * Free text stays allowed, so an empty result is fine.
     */
    fun suggestions(catalog: MovementCatalog, query: String, currentId: String?): List<Movement> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        if (catalog.byId(currentId)?.name.equals(q, ignoreCase = true)) return emptyList()
        val byName = catalog.movements.filter { it.name.contains(q, ignoreCase = true) }
        val byAlias = catalog.movements.filter { m -> m.aliases.any { it !in m.ambiguousAliases && it.startsWith(q, ignoreCase = true) } }
        return (byName.sortedBy { !it.name.startsWith(q, ignoreCase = true) } + byAlias).distinctBy { it.id }.take(SUGGESTIONS)
    }

    /**
     * The weights picker list (owner request 2026-11-05: lifts come from a list, after
     * crossfit.com/crossfit-movements): every catalog movement that takes a load, minus cardio,
     * grouped by kind of lift. Olympic, dumbbell and kettlebell lifts are not listed (owner request
     * 2026-11-06); they stay in the catalog for parsed workouts. [query] filters by name or
     * unambiguous alias.
     */
    fun liftGroups(catalog: MovementCatalog, query: String = ""): List<LiftGroup> {
        val q = query.trim()
        val lifts = catalog.movements.filter { it.loadable != "N" && it.category != "monostructural" }.filter { m ->
            q.isEmpty() || m.name.contains(q, ignoreCase = true) ||
                m.aliases.any { it !in m.ambiguousAliases && it.startsWith(q, ignoreCase = true) }
        }
        return LIFT_GROUPS.mapNotNull { (category, title) ->
            lifts.filter { it.category == category }.sortedBy { it.name.lowercase() }.takeIf { it.isNotEmpty() }?.let { LiftGroup(title, it) }
        }
    }

    private val LIFT_GROUPS = listOf(
        "strength" to "Squats, presses & deadlifts",
        "gymnastics" to "Weighted gymnastics",
        "other" to "Med ball, sandbag & more",
    )

    /** Whether a movement can carry a load (catalog `loadable` Y/optional, or free text). */
    fun loadable(catalog: MovementCatalog, movementId: String?): Boolean = catalog.byId(movementId)?.loadable != "N"

    /**
     * Warm-up types offered in the Warm-up section (owner request 2026-11-09): the 15 general
     * cardio drills of the "Cardio warm-up" quick start, in that order, labelled exactly as
     * the owner listed them. Free text stays allowed, so a custom warm-up is kept as
     * `movementId = null`.
     */
    val WARMUP_MOVEMENT_IDS = listOf(
        "jumping_jack",
        "high_knees",
        "butt_kicks",
        "jog",
        "single_under",
        "double_under",
        "row",
        "air_bike",
        "skierg",
        "mountain_climber",
        "lateral_shuffle",
        "bear_crawl",
        "inchworm_push_up",
        "burpee",
        "skipping",
    )

    /** Display labels for [WARMUP_MOVEMENT_IDS], exactly the owner's list. */
    val WARMUP_LABELS = mapOf(
        "jumping_jack" to "Jumping Jacks",
        "high_knees" to "High Knees",
        "butt_kicks" to "Butt Kicks",
        "jog" to "Jogging",
        "single_under" to "Jump Rope (Single Unders)",
        "double_under" to "Double Unders",
        "row" to "Rowing",
        "air_bike" to "Assault Bike / Echo Bike",
        "skierg" to "SkiErg",
        "mountain_climber" to "Mountain Climbers",
        "lateral_shuffle" to "Lateral Shuffles",
        "bear_crawl" to "Bear Crawls",
        "inchworm_push_up" to "Inchworms",
        "burpee" to "Light Burpees",
        "skipping" to "Skipping",
    )

    /** Owner's label for a warm-up catalog ID, falling back to the row's stored name. */
    fun warmupLabel(movementId: String?, fallback: String): String =
        movementId?.let { WARMUP_LABELS[it] } ?: fallback

    /**
     * The warm-up picker list: [WARMUP_MOVEMENT_IDS] resolved against [catalog] in order,
     * skipping any ID the catalog does not (yet) know. [query] filters by name or
     * unambiguous alias.
     */
    fun warmupMovements(catalog: MovementCatalog, query: String = ""): List<Movement> {
        val q = query.trim()
        return WARMUP_MOVEMENT_IDS.mapNotNull { catalog.byId(it) }.filter { m ->
            q.isEmpty() || m.name.contains(q, ignoreCase = true) ||
                m.aliases.any { it !in m.ambiguousAliases && it.startsWith(q, ignoreCase = true) }
        }
    }

    /** Bodyweight-only movements need no implement; others keep the catalog implement for load rules. */
    private fun implementOf(m: Movement): String? = m.implement.takeIf { it !in setOf("none", "bodyweight") }

    private fun defaultMeasure(m: Movement, schemePart: Boolean): MeasureKind = when (m.measure) {
        "m", "km", "mi", "ft", "yd" -> MeasureKind.DISTANCE
        "cal" -> MeasureKind.CALORIES
        "s" -> MeasureKind.DURATION
        else -> if (schemePart) MeasureKind.REP_SCHEME else MeasureKind.REPS
    }

    private fun distanceUnit(m: Movement): DistanceUnit? = DistanceUnit.entries.firstOrNull { it.wire == m.measure }

    private fun lengthUnit(u: String): LengthUnit? = LengthUnit.entries.firstOrNull { it.wire == u }
}
