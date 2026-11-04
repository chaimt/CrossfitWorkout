package com.wodcapture.domain.manual

import com.wodcapture.domain.IdSource
import com.wodcapture.domain.model.FallbackTimer
import com.wodcapture.domain.model.MassUnit
import com.wodcapture.domain.model.MeasureKind
import com.wodcapture.domain.model.PartKind

/** A one-tap skeleton for the manual form (REQ-AC09). */
data class ManualTemplate(val name: String, val build: (IdSource) -> ManualPartForm)

/**
 * Prefilled format skeletons ("AMRAP 20", "EMOM 10", "For time 21-15-9", "5 × 5 strength", …).
 * Each produces one ready-to-create part with fresh row IDs; the athlete edits from there.
 */
object ManualTemplates {

    val all: List<ManualTemplate> = listOf(
        ManualTemplate("AMRAP 20", ::amrap20),
        ManualTemplate("EMOM 10", ::emom10),
        ManualTemplate("For time 21-15-9", ::forTime21159),
        ManualTemplate("5 × 5 strength", ::strength5x5),
        ManualTemplate("Tabata", ::tabata),
        ManualTemplate("Intervals 5 × 3:00", ::intervals),
        ManualTemplate("Custom", ::custom),
    )

    /**
     * Applies [template] to [form]: replaces the parts with the template's part and fills the title
     * only when the athlete has not typed one.
     */
    fun apply(form: ManualWorkoutForm, template: ManualTemplate, ids: IdSource): ManualWorkoutForm =
        form.copy(
            title = form.title.ifBlank { template.name },
            parts = listOf(template.build(ids)),
        )

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
