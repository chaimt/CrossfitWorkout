package com.wodcapture.domain.manual

import com.wodcapture.domain.IdSource
import com.wodcapture.domain.load.Equivalents
import com.wodcapture.domain.load.MassMath
import com.wodcapture.domain.model.FallbackTimer
import com.wodcapture.domain.model.MassUnit
import com.wodcapture.domain.model.MeasureKind
import com.wodcapture.domain.model.PartKind
import com.wodcapture.domain.model.WorkoutSection

/**
 * A one-tap skeleton for one section of the manual form (REQ-AC09). Its prescribed loads are in
 * lb (the rulebook standard); [ManualTemplates.apply] converts them to the form's unit.
 */
data class ManualTemplate(val name: String, val section: WorkoutSection, val build: (IdSource) -> ManualPartForm)

/**
 * Prefilled format skeletons ("5 × 5 · 70–80%", "AMRAP 20", "EMOM 10", "For time 21-15-9", …),
 * grouped by section. Each produces one ready-to-create part with fresh row IDs; the athlete edits
 * from there. A weights template and a metcon template combine into a typical class day.
 */
object ManualTemplates {
    private const val TITLE_JOIN = " + "

    val all: List<ManualTemplate> = listOf(
        ManualTemplate("Cardio warm-up", WorkoutSection.WARMUP, ::cardioWarmup),
        ManualTemplate("Cardio + mobility", WorkoutSection.WARMUP, ::cardioMobility),
        ManualTemplate("Burgener", WorkoutSection.WARMUP, ::burgener),
        ManualTemplate("5 × 5 · 70–80%", WorkoutSection.WEIGHTS, ::strength5x5),
        ManualTemplate("5 × 3 · 80–85%", WorkoutSection.WEIGHTS, ::strength5x3),
        ManualTemplate("3 × 5 · 70–80%", WorkoutSection.WEIGHTS, ::strength3x5),
        ManualTemplate("AMRAP 20", WorkoutSection.METCON, ::amrap20),
        ManualTemplate("EMOM 10", WorkoutSection.METCON, ::emom10),
        ManualTemplate("For time 21-15-9", WorkoutSection.METCON, ::forTime21159),
        ManualTemplate("Tabata", WorkoutSection.METCON, ::tabata),
        ManualTemplate("Intervals 5 × 3:00", WorkoutSection.METCON, ::intervals),
        ManualTemplate("Custom", WorkoutSection.METCON, ::custom),
    )

    fun of(section: WorkoutSection): List<ManualTemplate> = all.filter { it.section == section }

    /**
     * Applies [template] to [form]: replaces the parts of the template's section and keeps the
     * other section, dropping only parts nobody has typed into. The title is filled while it is
     * blank or still made of template names ("5 × 5 · 70–80% + AMRAP 20"); a typed title is kept.
     */
    fun apply(form: ManualWorkoutForm, template: ManualTemplate, ids: IdSource): ManualWorkoutForm {
        val kept = form.parts.filter { ManualForms.sectionOf(it) != template.section && !ManualForms.isUntouched(it) }
        return form.copy(
            title = title(form.title, template),
            parts = ManualForms.ordered(kept + inUnit(template.build(ids), form.loadUnit)),
        )
    }

    /** Template loads are written in lb; give them in the Settings unit, rulebook equivalents first. */
    private fun inUnit(part: ManualPartForm, unit: MassUnit): ManualPartForm {
        if (unit == MassUnit.LB) return part
        fun convert(implement: String?, lb: Double?): Double? = lb?.let {
            Equivalents.lookup(implement, it, MassUnit.LB, unit) ?: MassMath.roundToStep(MassMath.fromKg(it * MassMath.KG_PER_LB, unit), 0.5)
        }
        return part.copy(
            movements = part.movements.map { m -> m.copy(load = convert(m.implement, m.load)) },
            sets = part.sets.map { s -> s.copy(load = convert(part.lift?.implement, s.load)) },
        )
    }

    private fun title(current: String, template: ManualTemplate): String {
        if (current.isBlank()) return template.name
        val used = current.split(TITLE_JOIN).map { n -> all.firstOrNull { it.name == n.trim() } }
        if (used.any { it == null }) return current
        val names = (used.filterNotNull().filter { it.section != template.section } + template).sortedBy { it.section.ordinal }
        return names.joinToString(TITLE_JOIN) { it.name }
    }

    /** Cindy-style AMRAP 20: 5 pull-ups, 10 push-ups, 15 air squats. */
    fun amrap20(ids: IdSource) = part(ids, ManualFormat.AMRAP).copy(
        durationText = "20:00",
        movements = listOf(
            reps(ids, "pull_up", "Pull-up", 5.0),
            reps(ids, "push_up", "Push-up", 10.0),
            reps(ids, "air_squat", "Air squat", 15.0),
        ),
    )

    /** EMOM 10 alternating two stations. */
    fun emom10(ids: IdSource) = part(ids, ManualFormat.EMOM).copy(
        intervalText = "1:00",
        intervalCount = 10,
        movements = listOf(
            reps(ids, "american_kettlebell_swing", "Kettlebell swing", 15.0).copy(
                implement = "kettlebell", load = 53.0,
            ),
            reps(ids, "burpee", "Burpee", 10.0),
        ),
    )

    /** Fran-style couplet: 21-15-9 thrusters 95/65 lb and pull-ups, 8:00 cap. */
    fun forTime21159(ids: IdSource) = part(ids, ManualFormat.FOR_TIME).copy(
        capText = "8:00",
        repSchemeText = "21-15-9",
        movements = listOf(
            ManualMovementForm(
                id = ids.newId(), name = "Thruster", movementId = "thruster", implement = "barbell", measure = MeasureKind.REP_SCHEME,
                load = 95.0,
            ),
            ManualMovementForm(id = ids.newId(), name = "Pull-up", movementId = "pull_up", measure = MeasureKind.REP_SCHEME),
        ),
    )

    /** 5 sets of 5 ramping 70 → 80 % of the goal weight. */
    fun strength5x5(ids: IdSource) = percentSession(ids, sets = 5, reps = 5, from = 70.0, to = 80.0)

    /** Heavy strength: 5 sets of 3 ramping 80 → 85 % of the goal weight. */
    fun strength5x3(ids: IdSource) = percentSession(ids, sets = 5, reps = 3, from = 80.0, to = 85.0)

    /** 3 sets of 5 ramping 70 → 80 % of the goal weight. */
    fun strength3x5(ids: IdSource) = percentSession(ids, sets = 3, reps = 5, from = 70.0, to = 80.0)

    /**
     * A strength quick start is the same session the manual form builds (owner request
     * 2026-11-06): no load type, a goal weight to fill in, and set rows of % · weight · reps
     * whose % ramps evenly from [from] to [to] (to 0.5 %). Entering the goal fills every weight.
     */
    private fun percentSession(ids: IdSource, sets: Int, reps: Int, from: Double, to: Double) =
        part(ids, ManualFormat.STRENGTH).copy(
            kind = PartKind.STRENGTH,
            scheme = StrengthScheme(),
            lift = ManualStrengthForm(id = ids.newId(), name = "Back squat", movementId = "back_squat", implement = "barbell"),
            sets = List(sets) { i ->
                val pct = if (sets == 1) from else from + (to - from) * i / (sets - 1)
                ManualSetForm(id = ids.newId(), ofGoal = MassMath.roundToStep(pct, 0.5), reps = reps)
            },
        )

    /** Classic Tabata 20/10 × 8 of air squats. */
    fun tabata(ids: IdSource) = part(ids, ManualFormat.TABATA).copy(
        workText = "0:20",
        restText = "0:10",
        rounds = 8,
        movements = listOf(ManualMovementForm(id = ids.newId(), name = "Air squat", movementId = "air_squat", measure = MeasureKind.MAX)),
    )

    /** 5 × 3:00 on / 1:00 off, AMRAP inside each interval. */
    fun intervals(ids: IdSource) = part(ids, ManualFormat.INTERVALS).copy(
        rounds = 5,
        workText = "3:00",
        restText = "1:00",
        movements = listOf(
            reps(ids, "burpee", "Burpee", 10.0),
            reps(ids, "air_squat", "Air squat", 15.0),
        ),
    )

    /** Free-text part on a manual count-up timer (REQ-DM18). */
    fun custom(ids: IdSource) = part(ids, ManualFormat.CUSTOM).copy(
        customText = "Partner workout\nYou go, I go\nScore: total time",
        customTimer = FallbackTimer.COUNT_UP,
        movements = emptyList(),
    )

    /**
     * General CrossFit warm-up: 2 rounds of easy cardio drills at conversational pace,
     * x time per exercise. For-quality, not timed rounds.
     */
    fun cardioWarmup(ids: IdSource) = part(ids, ManualFormat.FOR_QUALITY).copy(
        kind = PartKind.WARMUP,
        rounds = 2,
        movements = listOf(
            timed(ids, "jumping_jack", "Jumping Jacks", "0:30"),
            timed(ids, "high_knees", "High Knees", "0:30"),
            timed(ids, "butt_kicks", "Butt Kicks", "0:30"),
            timed(ids, "jog", "Jogging", "1:00"),
            timed(ids, "single_under", "Jump Rope (Single Unders)", "0:30"),
            timed(ids, "double_under", "Double Unders", "0:30"),
            timed(ids, "row", "Rowing", "1:00"),
            timed(ids, "air_bike", "Assault Bike / Echo Bike", "1:00"),
            timed(ids, "skierg", "SkiErg", "1:00"),
            timed(ids, "mountain_climber", "Mountain Climbers", "0:30"),
            timed(ids, "lateral_shuffle", "Lateral Shuffles", "0:30"),
            timed(ids, "bear_crawl", "Bear Crawls", "0:30"),
            timed(ids, "inchworm_push_up", "Inchworms", "0:30"),
            timed(ids, "burpee", "Light Burpees", "0:30"),
            timed(ids, "skipping", "Skipping", "0:30"),
        ),
    )

    /**
     * A generic class warm-up: easy monostructural cardio, then two rounds of a squat,
     * a push and a pull at warm-up effort. For-quality, not timed rounds.
     */
    fun cardioMobility(ids: IdSource) = part(ids, ManualFormat.FOR_QUALITY).copy(
        kind = PartKind.WARMUP,
        rounds = 2,
        movements = listOf(
            timed(ids, "row", "Row", "2:00"),
            reps(ids, "air_squat", "Air squat", 10.0),
            reps(ids, "push_up", "Push-up", 10.0),
            reps(ids, "ring_row", "Ring row", 10.0),
        ),
    )

    /**
     * The Burgener warm-up for the snatch (crossfit.com/essentials/foundational-movement-snatch):
     * down-and-finish, elbows high and outside, muscle snatch, power-snatch land, snatch land —
     * drilled with a PVC pipe or an empty bar before any snatch session. For-quality.
     */
    fun burgener(ids: IdSource) = part(ids, ManualFormat.FOR_QUALITY).copy(
        kind = PartKind.WARMUP,
        name = "Burgener warm-up",
        movements = listOf(
            reps(ids, "muscle_snatch", "Down and finish + muscle snatch", 3.0),
            reps(ids, "snatch_balance", "Snatch land + snatch balance", 3.0),
        ),
    )

    private fun part(ids: IdSource, format: ManualFormat) = ManualPartForm(id = ids.newId(), label = "", format = format)

    private fun reps(ids: IdSource, movementId: String, name: String, n: Double) =
        ManualMovementForm(id = ids.newId(), name = name, movementId = movementId, measure = MeasureKind.REPS, amount = n)

    private fun timed(ids: IdSource, movementId: String, name: String, time: String) =
        ManualMovementForm(id = ids.newId(), name = name, movementId = movementId, measure = MeasureKind.DURATION, durationText = time)
}
