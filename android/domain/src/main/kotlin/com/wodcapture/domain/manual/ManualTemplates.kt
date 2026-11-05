package com.wodcapture.domain.manual

import com.wodcapture.domain.IdSource
import com.wodcapture.domain.model.FallbackTimer
import com.wodcapture.domain.model.MassUnit
import com.wodcapture.domain.model.MeasureKind
import com.wodcapture.domain.model.PartKind
import com.wodcapture.domain.model.WorkoutSection

/** A one-tap skeleton for one section of the manual form (REQ-AC09). */
data class ManualTemplate(val name: String, val section: WorkoutSection, val build: (IdSource) -> ManualPartForm)

/**
 * Prefilled format skeletons ("5 × 5 strength", "AMRAP 20", "EMOM 10", "For time 21-15-9", …),
 * grouped by section. Each produces one ready-to-create part with fresh row IDs; the athlete edits
 * from there. A weights template and a metcon template combine into a typical class day.
 */
object ManualTemplates {
    private const val TITLE_JOIN = " + "

    val all: List<ManualTemplate> = listOf(
        ManualTemplate("5 × 5 strength", WorkoutSection.WEIGHTS, ::strength5x5),
        ManualTemplate("Build to heavy single", WorkoutSection.WEIGHTS, ::heavySingle),
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
     * blank or still made of template names ("5 × 5 strength + AMRAP 20"); a typed title is kept.
     */
    fun apply(form: ManualWorkoutForm, template: ManualTemplate, ids: IdSource): ManualWorkoutForm {
        val kept = form.parts.filter { ManualForms.sectionOf(it) != template.section && !ManualForms.isUntouched(it) }
        return form.copy(
            title = title(form.title, template),
            parts = ManualForms.ordered(kept + template.build(ids)),
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
                implement = "kettlebell", load = 24.0, loadFemale = 16.0, maleFemale = true, loadUnit = MassUnit.KG,
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
                load = 95.0, loadFemale = 65.0, maleFemale = true, loadUnit = MassUnit.LB,
            ),
            ManualMovementForm(id = ids.newId(), name = "Pull-up", movementId = "pull_up", measure = MeasureKind.REP_SCHEME),
        ),
    )

    /** Back squat 5 × 5 at 75 % of 1RM, 2:00 rest. */
    fun strength5x5(ids: IdSource) = part(ids, ManualFormat.STRENGTH).copy(
        kind = PartKind.STRENGTH,
        strength = listOf(
            ManualStrengthForm(
                id = ids.newId(), name = "Back squat", movementId = "back_squat", implement = "barbell",
                sets = 5, reps = 5, loadMode = StrengthLoadMode.PERCENT_1RM, percent = 75.0, restText = "2:00",
            ),
        ),
    )

    /** Deadlift: work up to a heavy single, 15:00 window. */
    fun heavySingle(ids: IdSource) = part(ids, ManualFormat.BUILD_TO_MAX).copy(
        kind = PartKind.STRENGTH,
        repsPerSet = 1,
        durationText = "15:00",
        movements = listOf(
            ManualMovementForm(id = ids.newId(), name = "Deadlift", movementId = "deadlift", implement = "barbell", measure = MeasureKind.REPS, amount = 1.0),
        ),
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

    private fun part(ids: IdSource, format: ManualFormat) = ManualPartForm(id = ids.newId(), label = "", format = format)

    private fun reps(ids: IdSource, movementId: String, name: String, n: Double) =
        ManualMovementForm(id = ids.newId(), name = name, movementId = movementId, measure = MeasureKind.REPS, amount = n)
}
