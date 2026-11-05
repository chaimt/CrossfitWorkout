package com.wodcapture.domain.manual

import com.wodcapture.domain.IdSource
import com.wodcapture.domain.model.AbsoluteLoad
import com.wodcapture.domain.model.AggregationOp
import com.wodcapture.domain.model.Amrap
import com.wodcapture.domain.model.BodyNode
import com.wodcapture.domain.model.BodyTree
import com.wodcapture.domain.model.BuildToMax
import com.wodcapture.domain.model.CustomSegments
import com.wodcapture.domain.model.Emom
import com.wodcapture.domain.model.Fallback
import com.wodcapture.domain.model.FallbackTimer
import com.wodcapture.domain.model.ForTime
import com.wodcapture.domain.model.Format
import com.wodcapture.domain.model.Group
import com.wodcapture.domain.model.GroupKind
import com.wodcapture.domain.model.InnerMode
import com.wodcapture.domain.model.Intervals
import com.wodcapture.domain.model.Item
import com.wodcapture.domain.model.Length
import com.wodcapture.domain.model.LoadSpec
import com.wodcapture.domain.model.Mass
import com.wodcapture.domain.model.MassUnit
import com.wodcapture.domain.model.MaxEffort
import com.wodcapture.domain.model.Measure
import com.wodcapture.domain.model.MeasureKind
import com.wodcapture.domain.model.Part
import com.wodcapture.domain.model.PercentOfMax
import com.wodcapture.domain.model.RepTarget
import com.wodcapture.domain.model.ScoreType
import com.wodcapture.domain.model.Scoring
import com.wodcapture.domain.model.Source
import com.wodcapture.domain.model.SourceKind
import com.wodcapture.domain.model.SplitLength
import com.wodcapture.domain.model.SplitLoad
import com.wodcapture.domain.model.Station
import com.wodcapture.domain.model.StrengthSet
import com.wodcapture.domain.model.StrengthSets
import com.wodcapture.domain.model.Tabata
import com.wodcapture.domain.model.Workout
import com.wodcapture.domain.model.WorkoutSection
import com.wodcapture.domain.plan.WatchLimits
import com.wodcapture.domain.review.TimeText
import com.wodcapture.domain.validation.Severity
import com.wodcapture.domain.validation.ValidationIssue
import com.wodcapture.domain.validation.ValidationReport
import com.wodcapture.domain.validation.WorkoutValidator
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException

/**
 * The canonical workout built from a form plus everything that blocks or warns (REQ-AX05). The
 * report merges form-input problems (unreadable times, schemes, dates) with [WorkoutValidator].
 */
data class ManualBuild(val workout: Workout, val report: ValidationReport) {
    /** Create is offered only without blocking findings (REQ-AC09). */
    val canCreate: Boolean get() = report.canSend
}

/**
 * Turns a [ManualWorkoutForm] into the canonical [Workout] (REQ-AC09, REQ-AC08): source `manual`,
 * no parser diagnostics, the same body/format split as parsed workouts (REQ-DM04/DM05/DM16) and
 * scoring defaults per format. Explicit M/F pairs typed in the form are labelled, so they need no
 * "higher = male" confirmation (§4.4). A Custom part is the reviewed manual-timer fallback
 * (REQ-DM18): choosing it in the form *is* the explicit acceptance, and Review still precedes Send.
 *
 * Part and item IDs come from the form rows (stable while the form is open); workout, group, set
 * and text-line IDs are fresh from [ids] on every build.
 */
class ManualWorkoutBuilder(private val ids: IdSource) {

    fun build(form: ManualWorkoutForm, nowSec: Long? = null): ManualBuild {
        val issues = mutableListOf<ValidationIssue>()
        checkSchedule(form, issues)
        val parts = form.parts.mapIndexed { i, p -> PartBuilder(p, ManualForms.defaultLabel(i, form.parts.size), form.loadUnit, issues).build() }
        val workout = Workout(
            id = ids.newId(),
            title = form.title.trim().ifBlank { defaultTitle(parts) },
            date = form.date.trim(),
            scheduleTimeZone = form.timeZone,
            source = Source(kind = SourceKind.MANUAL),
            parts = parts,
            createdAt = nowSec,
            updatedAt = nowSec,
        )
        // A set row's empty Reps field is already reported in form terms; drop the validator's
        // duplicate "needs a rep target" for the same set.
        val setsMissingReps = issues.filter { it.path == "reps" }.mapNotNull { it.nodeId }.toSet()
        val validation = WorkoutValidator.validate(workout).issues
            .filterNot { it.nodeId in setsMissingReps && it.path?.endsWith(".reps") == true }
        return ManualBuild(workout, ValidationReport(issues + validation))
    }

    private fun checkSchedule(form: ManualWorkoutForm, issues: MutableList<ValidationIssue>) {
        try {
            LocalDate.parse(form.date.trim())
        } catch (e: DateTimeParseException) {
            issues += blocking("invalid_date", "Date must be YYYY-MM-DD", path = "date")
        }
        if (runCatching { ZoneId.of(form.timeZone) }.isFailure) {
            issues += blocking("invalid_time_zone", "Unknown time zone ${form.timeZone}", path = "timeZone")
        }
    }

    private fun defaultTitle(parts: List<Part>): String =
        parts.firstNotNullOfOrNull { it.title } ?: if (parts.isEmpty()) "Manual workout" else "Workout"

    /** Builds one part; collects input problems into [issues] instead of throwing. */
    private inner class PartBuilder(
        private val form: ManualPartForm,
        private val fallbackLabel: String,
        /** The form-wide weight unit (Settings → Units); no entry carries its own. */
        private val unit: MassUnit,
        private val issues: MutableList<ValidationIssue>,
    ) {
        fun build(): Part {
            val format = format()
            val part = Part(
                id = form.id,
                label = form.label.trim().ifBlank { fallbackLabel },
                kind = form.kind,
                title = form.name.trim().ifBlank { null },
                format = format,
                body = body(),
                scoring = scoring(),
                fallback = fallback(),
            )
            return if (format is Emom) part.copy(format = format.copy(stations = BodyTree.items(part.body).map { Station(it.id) })) else part
        }

        // ---------------------------------------------------------------- format

        private fun format(): Format = when (form.format) {
            ManualFormat.FOR_TIME -> ForTime(capSec = time(form.capText, "Cap", "capText"))
            ManualFormat.AMRAP -> Amrap(durationSec = time(form.durationText, "AMRAP duration", "durationText"))
            ManualFormat.EMOM -> Emom(
                intervalSec = time(form.intervalText, "Interval", "intervalText") ?: ONE_MINUTE,
                intervalCount = form.intervalCount,
            )
            ManualFormat.TABATA -> Tabata(
                workSec = time(form.workText, "Work", "workText") ?: TABATA_WORK,
                restSec = time(form.restText, "Rest", "restText") ?: TABATA_REST,
                rounds = form.rounds ?: TABATA_ROUNDS,
            )
            ManualFormat.INTERVALS -> Intervals(
                rounds = form.rounds,
                workSec = time(form.workText, "Work", "workText"),
                restSec = time(form.restText, "Rest", "restText") ?: 0,
                inner = InnerMode.AMRAP,
            )
            ManualFormat.STRENGTH -> StrengthSets()
            ManualFormat.BUILD_TO_MAX -> BuildToMax(
                repsPerSet = form.repsPerSet ?: 1,
                windowSec = time(form.durationText, "Window", "durationText"),
                attemptLimit = form.attempts,
            )
            ManualFormat.MAX_EFFORT -> MaxEffort(
                windowSec = time(form.durationText, "Window", "durationText"),
                attempts = form.attempts,
                measure = form.maxMeasure.wire,
            )
            ManualFormat.CUSTOM -> CustomSegments()
        }

        /** Parses m:ss / h:mm:ss (REQ-AE02); blank = absent, unreadable text blocks. */
        private fun time(text: String, label: String, field: String): Int? {
            if (text.isBlank()) return null
            return TimeText.parse(text) ?: run {
                issues += blocking("invalid_time", "$label: enter m:ss or h:mm:ss", form.id, path = field)
                null
            }
        }

        // ---------------------------------------------------------------- body

        private fun body(): List<BodyNode> = when (form.format) {
            ManualFormat.FOR_TIME -> forTimeBody()
            ManualFormat.STRENGTH -> strengthBody()
            ManualFormat.BUILD_TO_MAX -> buildToMaxBody()
            ManualFormat.CUSTOM -> customLines() + form.movements.map(::item)
            else -> form.movements.map(::item)
        }

        /** Rep scheme → one rounds group whose length is the scheme; rounds > 1 → a repeated group. */
        private fun forTimeBody(): List<BodyNode> {
            val items = form.movements.map(::item)
            val scheme = repScheme()
            val rounds = form.rounds ?: 1
            if (scheme.isNotEmpty()) {
                if (rounds != 1 && rounds != scheme.size) {
                    issues += blocking("invalid_rep_scheme", "Rounds $rounds do not match the ${scheme.size}-round rep scheme", form.id, path = "rounds")
                }
                return listOf(Group(ids.newId(), GroupKind.ROUNDS, repeat = scheme.size, repScheme = scheme, children = items))
            }
            return if (rounds != 1) listOf(Group(ids.newId(), GroupKind.ROUNDS, repeat = rounds, children = items)) else items
        }

        private fun repScheme(): List<Int> {
            val text = form.repSchemeText.trim()
            if (text.isEmpty()) return emptyList()
            val values = text.split(SCHEME_SEPARATORS).filter { it.isNotBlank() }.map { it.toIntOrNull() }
            if (values.any { it == null }) {
                issues += blocking("invalid_rep_scheme", "Rep scheme must be numbers such as 21-15-9", form.id, path = "repSchemeText")
                return emptyList()
            }
            return values.filterNotNull()
        }

        private fun buildToMaxBody(): List<BodyNode> {
            if (form.movements.size > 1) {
                issues += blocking("invalid_bounds", "Build to max takes exactly one movement", form.id, path = "movements")
            }
            return form.movements.take(1).map { m -> requireListedLift(m.id, m.movementId); item(m).copy(measure = null, load = null) }
        }

        /** Custom instructions: one free-text line each so every watch line is previewed (REQ-DM18). */
        private fun customLines(): List<BodyNode> =
            form.customText.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { Item(ids.newId(), name = it, rawText = it) }

        /**
         * Weights are picked from the catalog list (owner request 2026-11-05), so a lift without
         * a catalog ID blocks; this replaces the generic missing-name message for lifts.
         */
        private fun requireListedLift(rowId: String, movementId: String?) {
            if (movementId == null) issues += blocking("lift_not_in_list", "Choose each lift from the list", form.id, rowId, "movementId")
        }

        private val weights: Boolean get() = form.format.section == WorkoutSection.WEIGHTS

        private fun item(m: ManualMovementForm): Item {
            val name = m.name.trim()
            if (name.isEmpty() && !weights) issues += blocking("missing_name", "Every movement needs a name", form.id, m.id, "name")
            return Item(
                id = m.id,
                movementId = m.movementId,
                name = name,
                measure = measure(m),
                load = load(m),
                height = height(m).takeIf { !m.heightIsTarget },
                target = height(m).takeIf { m.heightIsTarget },
                implement = m.implement,
                count = m.implementCount,
            )
        }

        private fun measure(m: ManualMovementForm): Measure {
            val female = m.amountFemale.takeIf { m.maleFemale }
            val labeled = female != null
            return when (m.measure) {
                MeasureKind.REPS -> {
                    if (m.amount != null && m.amount != Math.rint(m.amount)) {
                        issues += blocking("invalid_bounds", "Reps must be whole numbers", form.id, m.id, "amount")
                    }
                    Measure(MeasureKind.REPS, m.amount, female, labeled)
                }
                MeasureKind.CALORIES -> Measure(MeasureKind.CALORIES, m.amount, female, labeled)
                MeasureKind.DISTANCE -> Measure(MeasureKind.DISTANCE, m.amount, female, labeled, unit = m.distanceUnit)
                MeasureKind.DURATION -> Measure(MeasureKind.DURATION, time(m.durationText, "Duration", "durationText")?.toDouble())
                MeasureKind.REP_SCHEME, MeasureKind.MAX -> Measure(m.measure)
            }
        }

        /** Single value → as written; explicit M/F pair → labelled split (no review confirmation). */
        private fun load(m: ManualMovementForm): LoadSpec? {
            val male = m.load ?: return null
            val female = m.loadFemale.takeIf { m.maleFemale }
            return if (female != null) SplitLoad(Mass(male, unit), Mass(female, unit), labeled = true)
            else AbsoluteLoad(Mass(male, unit))
        }

        private fun height(m: ManualMovementForm): SplitLength? {
            val male = m.height ?: return null
            val female = m.heightFemale.takeIf { m.maleFemale }
            return SplitLength(Length(male, m.heightUnit), female?.let { Length(it, m.heightUnit) }, labeled = female != null)
        }

        /**
         * One lift per session: a single item whose sets are the set rows, each with its own
         * weight and reps; load type and rest come from the session.
         */
        private fun strengthBody(): List<BodyNode> {
            val lift = form.lift ?: ManualStrengthForm(id = form.id + "-lift")
            requireListedLift(lift.id, lift.movementId)
            // No sets at all is the validator's "missing_sets"; only the watch limit is checked here.
            if (form.sets.size > WatchLimits.SETS) {
                issues += blocking("invalid_bounds", "At most ${WatchLimits.SETS} sets", form.id, path = "sets")
            }
            val rest = time(form.scheme.restText, "Rest", "scheme.restText")
            val sets = form.sets.take(WatchLimits.SETS).map { strengthSet(it, rest) }
            return listOf(Item(id = lift.id, movementId = lift.movementId, name = lift.name.trim(), implement = lift.implement, sets = sets))
        }

        private fun strengthSet(s: ManualSetForm, rest: Int?): StrengthSet {
            val reps = s.reps?.takeIf { it > 0 } ?: run {
                issues += blocking("missing_value", "Enter the reps of each set", form.id, s.id, "reps")
                null
            }
            return StrengthSet(s.id, RepTarget(reps), load = strengthLoad(s), restSec = rest)
        }

        private fun strengthLoad(s: ManualSetForm): LoadSpec? = when (form.scheme.loadMode) {
            StrengthLoadMode.NONE -> null
            StrengthLoadMode.ABSOLUTE -> s.load?.let { AbsoluteLoad(Mass(it, unit)) } ?: run {
                issues += blocking("missing_value", "Enter the weight of each set or choose no load", form.id, s.id, "load")
                null
            }
            StrengthLoadMode.PERCENT_1RM -> s.percent?.let { PercentOfMax(it) } ?: run {
                issues += blocking("missing_value", "Enter a percentage of 1RM for each set", form.id, s.id, "percent")
                null
            }
        }

        // ---------------------------------------------------------------- scoring / fallback

        /**
         * Scoring defaults per format. For Time scores time; the compiler adds the capped-reps rule
         * when every item is rep-countable. Tabata with mixed measures is typed in by hand because
         * there is no counting rule to sum them (REQ-DM23).
         */
        private fun scoring(): Scoring = when (form.format) {
            ManualFormat.FOR_TIME -> Scoring(ScoreType.TIME)
            ManualFormat.AMRAP -> Scoring(ScoreType.ROUNDS_REPS)
            ManualFormat.EMOM -> Scoring(ScoreType.COMPLETION, manual = true)
            ManualFormat.TABATA -> Scoring(ScoreType.REPS, manual = mixedMeasures())
            ManualFormat.INTERVALS -> Scoring(ScoreType.ROUNDS_REPS, aggregation = AggregationOp.SUM)
            ManualFormat.STRENGTH, ManualFormat.BUILD_TO_MAX -> Scoring(ScoreType.LOAD)
            ManualFormat.MAX_EFFORT -> Scoring(form.maxMeasure.score)
            ManualFormat.CUSTOM -> Scoring(ScoreType.TEXT, manual = true)
        }

        private fun mixedMeasures(): Boolean = form.movements.map {
            if (it.measure == MeasureKind.MAX || it.measure == MeasureKind.REP_SCHEME) MeasureKind.REPS else it.measure
        }.toSet().size > 1

        private fun fallback(): Fallback? {
            if (form.format != ManualFormat.CUSTOM) return null
            val sec = time(form.durationText, "Timer", "durationText")
            if (form.customTimer == FallbackTimer.COUNT_DOWN && sec == null && form.durationText.isBlank()) {
                issues += blocking("missing_duration", "Countdown needs a length", form.id, path = "durationText")
            }
            return Fallback(
                accepted = true,
                timer = form.customTimer,
                durationSec = sec.takeIf { form.customTimer == FallbackTimer.COUNT_DOWN },
                capSec = sec.takeIf { form.customTimer == FallbackTimer.COUNT_UP },
                reason = CUSTOM_REASON,
            )
        }
    }

    private fun blocking(code: String, message: String, partId: String? = null, nodeId: String? = null, path: String? = null) =
        ValidationIssue(Severity.BLOCKING, code, message, partId, nodeId, path)

    companion object {
        private const val ONE_MINUTE = 60
        private const val TABATA_WORK = 20
        private const val TABATA_REST = 10
        private const val TABATA_ROUNDS = 8
        private val SCHEME_SEPARATORS = Regex("[-,/\\s]+")
        const val CUSTOM_REASON = "Custom format: manual timer and score"
    }
}
