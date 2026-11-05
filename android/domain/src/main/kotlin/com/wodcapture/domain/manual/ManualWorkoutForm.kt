package com.wodcapture.domain.manual

import com.wodcapture.domain.IdSource
import com.wodcapture.domain.catalog.Movement
import com.wodcapture.domain.catalog.MovementCatalog
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
    val parts: List<ManualPartForm> = emptyList(),
)

/**
 * Formats offered by the form; each maps to one REQ-DM04 format (Custom → REQ-DM18 fallback) and
 * belongs to exactly one [section] of the form (weights or metcon).
 */
enum class ManualFormat(val label: String, val section: WorkoutSection) {
    STRENGTH("Strength sets", WorkoutSection.WEIGHTS),
    BUILD_TO_MAX("Build to max", WorkoutSection.WEIGHTS),
    FOR_TIME("For time", WorkoutSection.METCON),
    AMRAP("AMRAP", WorkoutSection.METCON),
    EMOM("EMOM / E#MOM", WorkoutSection.METCON),
    TABATA("Tabata", WorkoutSection.METCON),
    INTERVALS("Intervals", WorkoutSection.METCON),
    MAX_EFFORT("Max effort", WorkoutSection.METCON),
    CUSTOM("Custom", WorkoutSection.METCON),
    ;

    companion object {
        /** The formats offered when adding a part to [section]. */
        fun of(section: WorkoutSection): List<ManualFormat> = entries.filter { it.section == section }
    }
}

/** A heading of the weights picker and its lifts, A–Z. */
data class LiftGroup(val title: String, val lifts: List<Movement>)

/** The two sections of the form, in the order a class runs them: lift first, then the metcon. */
val MANUAL_SECTIONS: List<WorkoutSection> = listOf(WorkoutSection.WEIGHTS, WorkoutSection.METCON)

/**
 * Part kinds offered in each form section (the section itself comes from the format). Warm-up,
 * skill and cool-down pieces are timed like a metcon, so they live in the metcon section.
 */
fun kindsFor(section: WorkoutSection): List<PartKind> = when (section) {
    WorkoutSection.WEIGHTS -> listOf(PartKind.STRENGTH, PartKind.ACCESSORY)
    WorkoutSection.METCON, WorkoutSection.OTHER ->
        listOf(PartKind.METCON, PartKind.WARMUP, PartKind.SKILL, PartKind.COOLDOWN, PartKind.OTHER)
}

/** What a max-effort part measures, with the score type it implies. */
enum class MaxEffortMeasure(val wire: String, val score: ScoreType) {
    REPS("reps", ScoreType.REPS),
    CALORIES("calories", ScoreType.CALORIES),
    DISTANCE("distance", ScoreType.DISTANCE),
    LOAD("load", ScoreType.LOAD),
}

/** How a strength row prescribes its load (REQ-DM06). */
enum class StrengthLoadMode { ABSOLUTE, PERCENT_1RM, NONE }

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
    /** AMRAP duration; max-effort / build-to-max window; Custom countdown length or count-up cap. */
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
    /** Max-effort attempts or build-to-max attempt limit. */
    val attempts: Int? = null,
    /** Build to max: reps per attempt. */
    val repsPerSet: Int? = 1,
    /** Custom: the instructions, one watch line per text line. */
    val customText: String = "",
    val customTimer: FallbackTimer = FallbackTimer.COUNT_UP,
    val movements: List<ManualMovementForm> = emptyList(),
    /** Strength sets: one scheme for the whole part, set above the lifts (owner request 2026-11-05). */
    val scheme: StrengthScheme = StrengthScheme(),
    val strength: List<ManualStrengthForm> = emptyList(),
)

/**
 * Sets × reps, load type and rest of a strength part. It is set once, before the lifts, and
 * every lift of the part follows it, so a new lift needs only the lift and its weight.
 */
data class StrengthScheme(
    val sets: Int? = 5,
    val reps: Int? = 5,
    val loadMode: StrengthLoadMode = StrengthLoadMode.ABSOLUTE,
    val loadUnit: MassUnit = MassUnit.KG,
    /** Rest after each set; the compiler drops it after the last set. */
    val restText: String = "2:00",
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
    /** Load (per implement). With [maleFemale] it is the male value of an explicit M/F pair. */
    val load: Double? = null,
    val loadUnit: MassUnit = MassUnit.KG,
    /** Explicit M/F prescription: the female fields below are used and the pair is labelled. */
    val maleFemale: Boolean = false,
    val amountFemale: Double? = null,
    val loadFemale: Double? = null,
    val heightFemale: Double? = null,
    /** Implements held at once (2 = double DB/KB). */
    val implementCount: Int = 1,
    /** Box height or wall-ball target. */
    val height: Double? = null,
    val heightUnit: LengthUnit = LengthUnit.IN,
    val heightIsTarget: Boolean = false,
)

/**
 * A lift of a strength part (REQ-DM06): the lift and its load in the part's [StrengthScheme]
 * load type — a weight ([load], in the scheme unit) or a percentage of 1RM ([percent]).
 */
data class ManualStrengthForm(
    /** Becomes the item ID. */
    val id: String,
    val name: String = "",
    val movementId: String? = null,
    val implement: String? = null,
    val load: Double? = null,
    val percent: Double? = null,
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

    /** Which form section [part] sits in (follows its format). */
    fun sectionOf(part: ManualPartForm): WorkoutSection = part.format.section

    /** Parts of [form] in [section], in workout order. */
    fun partsIn(form: ManualWorkoutForm, section: WorkoutSection): List<ManualPartForm> = form.parts.filter { sectionOf(it) == section }

    /** Weights before metcon, keeping the order inside each section. */
    fun ordered(parts: List<ManualPartForm>): List<ManualPartForm> = MANUAL_SECTIONS.flatMap { s -> parts.filter { sectionOf(it) == s } }

    /** Adds a [format] part at the end of its section. */
    fun addPart(form: ManualWorkoutForm, format: ManualFormat, ids: IdSource): ManualWorkoutForm =
        form.copy(parts = ordered(form.parts + newPart(ids, form.parts.size, format)))

    fun removePart(form: ManualWorkoutForm, partId: String): ManualWorkoutForm = form.copy(parts = form.parts.filter { it.id != partId })

    /** Replaces part [partId] with [f] of it; a format change across sections moves it. */
    fun updatePart(form: ManualWorkoutForm, partId: String, f: (ManualPartForm) -> ManualPartForm): ManualWorkoutForm =
        form.copy(parts = ordered(form.parts.map { if (it.id == partId) f(it) else it }))

    /** True for a part nobody has typed into yet (so a template may replace it). */
    fun isUntouched(part: ManualPartForm): Boolean =
        part.name.isBlank() && part.label.isBlank() && part.customText.isBlank() &&
            part.movements.all { it.name.isBlank() } && part.strength.all { it.name.isBlank() }

    fun newMovement(ids: IdSource, measure: MeasureKind = MeasureKind.REPS): ManualMovementForm =
        ManualMovementForm(id = ids.newId(), measure = measure)

    /** A movement row for [part]: follows the part's rep scheme when there is one. */
    fun newMovementFor(part: ManualPartForm, ids: IdSource): ManualMovementForm =
        newMovement(ids, if (usesScheme(part)) MeasureKind.REP_SCHEME else MeasureKind.REPS)

    /** A blank form for [date] in [timeZone] with one For-time metcon part (REQ-AC09 defaults). */
    fun blank(ids: IdSource, date: String, timeZone: String): ManualWorkoutForm =
        ManualWorkoutForm(date = date, timeZone = timeZone, parts = listOf(newPart(ids, 0)))

    fun newStrengthRow(ids: IdSource): ManualStrengthForm = ManualStrengthForm(id = ids.newId())

    /**
     * Switches [part] to [format]: kind follows the format's section (weights → strength unless
     * already accessory; metcon → metcon unless warm-up/skill/…), and a strength part gets its
     * first row so the form never shows an empty card.
     */
    fun withFormat(part: ManualPartForm, format: ManualFormat, ids: IdSource): ManualPartForm {
        val kind = when {
            part.kind in kindsFor(format.section) -> part.kind
            format.section == WorkoutSection.WEIGHTS -> PartKind.STRENGTH
            else -> PartKind.METCON
        }
        val strength = if (format == ManualFormat.STRENGTH && part.strength.isEmpty()) listOf(newStrengthRow(ids)) else part.strength
        val movements = if (format != ManualFormat.STRENGTH && format != ManualFormat.CUSTOM && part.movements.isEmpty()) {
            listOf(newMovement(ids))
        } else part.movements
        return part.copy(format = format, kind = kind, strength = strength, movements = movements)
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

    /** Applies a catalog pick to a strength row. */
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
     * grouped by kind of lift. [query] filters by name or unambiguous alias.
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
        "olympic" to "Olympic lifts",
        "dumbbell" to "Dumbbell",
        "kettlebell" to "Kettlebell",
        "gymnastics" to "Weighted gymnastics",
        "other" to "Med ball, sandbag & more",
    )

    /** Whether a movement can carry a load (catalog `loadable` Y/optional, or free text). */
    fun loadable(catalog: MovementCatalog, movementId: String?): Boolean = catalog.byId(movementId)?.loadable != "N"

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
