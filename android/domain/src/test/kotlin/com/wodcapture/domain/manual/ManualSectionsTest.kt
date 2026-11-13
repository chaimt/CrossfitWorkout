package com.wodcapture.domain.manual

import com.wodcapture.domain.IdSource
import com.wodcapture.domain.TestSupport
import com.wodcapture.domain.catalog.Movement
import com.wodcapture.domain.model.AbsoluteLoad
import com.wodcapture.domain.model.Amrap
import com.wodcapture.domain.model.BodyTree
import com.wodcapture.domain.model.Group
import com.wodcapture.domain.model.Item
import com.wodcapture.domain.model.Mass
import com.wodcapture.domain.model.MassUnit
import com.wodcapture.domain.model.NotForTime
import com.wodcapture.domain.model.Part
import com.wodcapture.domain.model.PartKind
import com.wodcapture.domain.model.ScoreType
import com.wodcapture.domain.model.StrengthSets
import com.wodcapture.domain.model.WorkoutSection
import com.wodcapture.domain.plan.WatchLimits
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Weights and metcon stay in separate form sections, ordered into class run order (owner request 2026-11-05, run order 2026-11-07). */
class ManualSectionsTest {
    private val ids = IdSource.sequential("a0000000")
    private val builder = ManualWorkoutBuilder(IdSource.sequential("b0000000"))
    private fun blank() = ManualForms.blank(ids, "2026-11-05", "Asia/Jerusalem")
    private fun template(name: String) = ManualTemplates.all.single { it.name == name }

    @Test
    fun everyFormatBelongsToOneSection() {
        assertEquals(listOf(ManualFormat.STRENGTH), ManualFormat.of(WorkoutSection.WEIGHTS))
        // Warm-up is a for-quality list, not metcon timers; metcon and the trailing
        // section share the timed formats.
        assertEquals(listOf(ManualFormat.FOR_TIME, ManualFormat.FOR_QUALITY), ManualFormat.of(WorkoutSection.WARMUP))
        val timed = ManualFormat.entries.filter { it != ManualFormat.STRENGTH && it != ManualFormat.FOR_QUALITY }
        assertEquals(timed, ManualFormat.of(WorkoutSection.METCON))
        assertEquals(timed, ManualFormat.of(WorkoutSection.OTHER))
        assertEquals(
            setOf(WorkoutSection.WARMUP, WorkoutSection.WEIGHTS, WorkoutSection.METCON),
            ManualTemplates.all.map { it.section }.toSet(),
        )
    }

    @Test
    fun weightsAreAddedAheadOfTheMetcon() {
        val form = ManualForms.addPart(blank(), WorkoutSection.WEIGHTS, ManualFormat.STRENGTH, ids)
        assertEquals(listOf(ManualFormat.STRENGTH, ManualFormat.FOR_TIME), form.parts.map { it.format })
        assertEquals(listOf("A", "B"), form.parts.map { ManualForms.effectiveLabel(form, it) })
        val built = builder.build(form).workout
        assertEquals(listOf("A", "B"), built.parts.map { it.label })
        assertEquals(listOf(PartKind.STRENGTH, PartKind.METCON), built.parts.map { it.kind })
    }

    @Test
    fun weightsAndMetconTemplatesCombine() {
        val strength = ManualTemplates.apply(blank(), template("5 × 5 · 70–80%"), ids)
            .let { f -> f.copy(parts = f.parts.map { ManualForms.withGoal(it, 100.0, 1.0) }) }
        // The untouched default For-time part gives way to the template.
        assertEquals(listOf(ManualFormat.STRENGTH), strength.parts.map { it.format })
        val day = ManualTemplates.apply(strength, template("AMRAP 20"), ids)
        assertEquals(listOf(ManualFormat.STRENGTH, ManualFormat.AMRAP), day.parts.map { it.format })
        assertEquals("5 × 5 · 70–80% + AMRAP 20", day.title)
        val built = builder.build(day)
        assertEquals(true, built.canCreate, built.report.blocking.toString())
        assertEquals(listOf(WorkoutSection.WEIGHTS, WorkoutSection.METCON), built.workout.parts.map(WorkoutSection::of))

        // A second metcon template swaps only the metcon; the weights and a typed title stay.
        val swapped = ManualTemplates.apply(day.copy(title = "Monday"), template("EMOM 10"), ids)
        assertEquals(listOf(ManualFormat.STRENGTH, ManualFormat.EMOM), swapped.parts.map { it.format })
        assertEquals(day.parts.first().id, swapped.parts.first().id)
        assertEquals("Monday", swapped.title)
    }

    @Test
    fun aFormatChangeAcrossSectionsMovesThePartAndItsKind() {
        val form = ManualForms.addPart(blank(), WorkoutSection.METCON, ManualFormat.AMRAP, ids)
        val amrap = form.parts.last()
        val moved = ManualForms.updatePart(form, amrap.id) { ManualForms.withFormat(it, ManualFormat.STRENGTH, ids) }
        assertEquals(amrap.id, moved.parts.first().id)
        assertEquals(PartKind.STRENGTH, moved.parts.first().kind)
        val warmup = ManualForms.withFormat(amrap.copy(kind = PartKind.WARMUP), ManualFormat.TABATA, ids)
        assertEquals(PartKind.WARMUP, warmup.kind)
    }

    @Test
    fun warmupOffersTimeAndRoundsAndRetainsItsKind() {
        // Add warm-up produces a for-quality list under Warm-up, never a metcon timer.
        val added = ManualForms.addPart(blank(), WorkoutSection.WARMUP, ManualFormat.FOR_QUALITY, ids)
        val warmup = ManualForms.partsIn(added, WorkoutSection.WARMUP).single()
        assertEquals(ManualFormat.FOR_QUALITY, warmup.format)
        assertEquals(PartKind.WARMUP, warmup.kind)
        // Switching a metcon to for-quality moves it into warm-up with kind Warm-up.
        val moved = ManualForms.withFormat(
            ManualForms.addPart(blank(), WorkoutSection.METCON, ManualFormat.AMRAP, ids).parts.last(),
            ManualFormat.FOR_QUALITY, ids,
        )
        assertEquals(PartKind.WARMUP, moved.kind)
        assertEquals(WorkoutSection.WARMUP, ManualForms.sectionOf(moved))
    }

    @Test
    fun forQualityBuildsNotForTimeWithAcceptedFallback() {
        val built = builder.build(blank().copy(parts = listOf(ManualTemplates.cardioWarmup(ids))))
        assertTrue(built.canCreate, built.report.blocking.toString())
        val part = built.workout.parts.single()
        assertTrue(part.format is NotForTime, part.format.toString())
        assertEquals(PartKind.WARMUP, part.kind)
        assertEquals(ScoreType.NONE, part.scoring.type)
        assertEquals(true, part.fallback?.accepted)
        assertEquals(2, part.body.filterIsInstance<Group>().single().repeat)
        // Every movement resolves in the catalog.
        val missing = BodyTree.items(part.body).filter { it.movementId != null && TestSupport.catalog.byId(it.movementId) == null }
        assertTrue(missing.isEmpty(), missing.map { it.movementId }.toString())
    }

    @Test
    fun aWeightsPartIsAlwaysStrengthWithNoLabelOrName() {
        assertEquals(listOf(PartKind.STRENGTH), kindsFor(WorkoutSection.WEIGHTS))
        val named = ManualForms.newPart(ids, 0, ManualFormat.AMRAP).copy(label = "B", name = "Cindy", kind = PartKind.WARMUP)
        listOf(ManualFormat.STRENGTH).forEach { f ->
            val weights = ManualForms.withFormat(named, f, ids)
            assertEquals(PartKind.STRENGTH, weights.kind, f.toString())
            assertEquals("" to "", weights.label to weights.name, f.toString())
        }
    }

    @Test
    fun canonicalPartsMapToSections() {
        assertEquals(WorkoutSection.WEIGHTS, WorkoutSection.of(Part(id = "p", kind = PartKind.METCON, format = StrengthSets())))
        assertEquals(WorkoutSection.METCON, WorkoutSection.of(Part(id = "p", format = Amrap(durationSec = 600))))
        assertEquals(WorkoutSection.WARMUP, WorkoutSection.of(Part(id = "p", kind = PartKind.WARMUP, format = Amrap(durationSec = 300))))
        assertEquals(WorkoutSection.WEIGHTS, WorkoutSection.of(Part(id = "p", kind = PartKind.ACCESSORY, format = StrengthSets())))
        assertEquals(WorkoutSection.OTHER, WorkoutSection.of(Part(id = "p", kind = PartKind.ACCESSORY, format = Amrap(durationSec = 300))))
        assertEquals(WorkoutSection.OTHER, WorkoutSection.of(Part(id = "p", kind = PartKind.COOLDOWN, format = Amrap(durationSec = 300))))
    }

    @Test
    fun runOrderIsWarmupWeightsMetconTrailingWithCooldownLast() {
        fun part(id: String, kind: PartKind) = Part(id = id, kind = kind, format = Amrap(durationSec = 300))
        val shuffled = listOf(
            part("cooldown", PartKind.COOLDOWN),
            part("metcon", PartKind.METCON),
            Part(id = "strength", kind = PartKind.STRENGTH, format = StrengthSets()),
            part("warmup", PartKind.WARMUP),
            part("other", PartKind.OTHER),
            part("skill", PartKind.SKILL),
            part("accessory", PartKind.ACCESSORY),
        )
        assertEquals(
            listOf("warmup", "skill", "strength", "metcon", "other", "accessory", "cooldown"),
            WorkoutSection.runOrdered(shuffled).map { it.id },
        )
    }

    @Test
    fun warmupWithLiftingSetsStillRunsBeforeTheWeightsSession() {
        val weights = Part(id = "weights", kind = PartKind.STRENGTH, format = StrengthSets())
        val warmup = weights.copy(id = "warmup", kind = PartKind.WARMUP)
        assertEquals(WorkoutSection.WARMUP, WorkoutSection.of(warmup))
        assertEquals(listOf("warmup", "weights"), WorkoutSection.runOrdered(listOf(weights, warmup)).map { it.id })
        assertTrue(warmup.effectiveRunOnWatch)
        assertFalse(warmup.copy(runOnWatch = false).effectiveRunOnWatch)
        assertFalse(Part(id = "cooldown", kind = PartKind.COOLDOWN, format = Amrap(300)).effectiveRunOnWatch)
    }

    @Test
    fun warmupTypesComeFromAFixedListWithACustomOption() {
        val warmups = ManualForms.warmupMovements(TestSupport.catalog)
        assertEquals(
            listOf(
                "jumping_jack", "high_knees", "butt_kicks", "jog", "single_under", "double_under",
                "row", "air_bike", "skierg", "mountain_climber", "lateral_shuffle", "bear_crawl",
                "inchworm_push_up", "burpee", "skipping",
            ),
            warmups.map { it.id },
        )
        // Display names match the owner's list.
        assertEquals(
            listOf(
                "Jumping Jacks", "High Knees", "Butt Kicks", "Jogging", "Jump Rope (Single Unders)", "Double Unders",
                "Rowing", "Assault Bike / Echo Bike", "SkiErg", "Mountain Climbers", "Lateral Shuffles", "Bear Crawls",
                "Inchworms", "Light Burpees", "Skipping",
            ),
            warmups.map { ManualForms.warmupLabel(it.id, it.name) },
        )
        // A list pick pins the catalog ID and the owner's label.
        val picked = ManualForms.pickWarmup(
            ManualMovementForm(id = "m", name = "", measure = com.wodcapture.domain.model.MeasureKind.DURATION),
            warmups.first { it.id == "air_bike" },
        )
        assertEquals("air_bike", picked.movementId)
        assertEquals("Assault Bike / Echo Bike", picked.name)
        // Every quick-start warm-up movement is in the list; search still filters it.
        val cardio = ManualTemplates.cardioWarmup(ids).movements
        assertTrue(cardio.map { it.movementId }.all { it in warmups.map { m -> m.id } }, cardio.map { it.movementId }.toString())
        assertEquals(listOf("jog"), ManualForms.warmupMovements(TestSupport.catalog, "jog").map { it.id })
    }

    @Test
    fun liftsComeFromAGroupedCatalogList() {
        val groups = ManualForms.liftGroups(TestSupport.catalog)
        val ids = groups.flatMap { g -> g.lifts.map { it.id } }
        assertEquals("Squats, presses & deadlifts", groups.first().title)
        listOf("back_squat", "deadlift", "front_squat").forEach { assertTrue(it in ids, it) }
        // No unloaded gymnastics or cardio.
        listOf("air_squat", "pull_up", "row", "run").forEach { assertFalse(it in ids, it) }
        // Olympic, dumbbell and kettlebell lifts are not offered (owner request 2026-11-06).
        listOf("clean_and_push_jerk", "dumbbell_squat_snatch", "dumbbell_hang_clean", "american_kettlebell_swing")
            .forEach { assertFalse(it in ids, it) }
        assertTrue(groups.none { it.title in setOf("Olympic lifts", "Dumbbell", "Kettlebell") })
        // Search still filters; the dumbbell variant no longer shows up.
        assertEquals(listOf("front_squat"), ManualForms.liftGroups(TestSupport.catalog, "front squat").flatMap { g -> g.lifts.map { it.id } })
    }

    /** A strength session on [liftId] with [sets] (weight, reps) rows. */
    private fun session(liftId: String, vararg sets: Pair<Double?, Int?>): ManualPartForm =
        ManualForms.newPart(ids, 0, ManualFormat.STRENGTH).let { p ->
            p.copy(
                lift = ManualForms.pick(p.lift!!, TestSupport.catalog.byId(liftId)!!),
                sets = sets.map { (w, r) -> ManualSetForm(id = ids.newId(), load = w, reps = r) },
            )
        }

    @Test
    fun aWeightsLiftMustBePickedFromTheList() {
        val typed = ManualForms.newPart(ids, 0, ManualFormat.STRENGTH).let { p ->
            p.copy(lift = p.lift!!.copy(name = "Squat thing"), sets = listOf(ManualSetForm(ids.newId(), load = 100.0, reps = 5)))
        }
        val codes = builder.build(blank().copy(parts = listOf(typed))).report.blocking.map { it.code }
        assertTrue("lift_not_in_list" in codes, codes.toString())
        assertFalse("missing_name" in codes, codes.toString())
        val picked = typed.copy(lift = ManualForms.pick(typed.lift!!, TestSupport.catalog.byId("back_squat")!!))
        assertTrue(builder.build(blank().copy(parts = listOf(picked))).canCreate)
    }

    @Test
    fun aSessionIsOneLiftAndEachSetIsWeightAndReps() {
        val part = session("back_squat", 60.0 to 5, 80.0 to 5, 100.0 to 3)
        val built = builder.build(blank().copy(parts = listOf(part)))
        assertTrue(built.canCreate, built.report.blocking.toString())
        val lift = built.workout.parts.single().body.single() as Item
        assertEquals("back_squat", lift.movementId)
        assertEquals(listOf(5, 5, 3), lift.sets.map { it.reps.value })
        assertEquals(listOf(60.0, 80.0, 100.0), lift.sets.map { (it.load as AbsoluteLoad).mass.value })
        // No rest per session or set: the compiler applies Settings → Default strength rest.
        assertTrue(lift.sets.all { it.restSec == null })
        assertEquals(part.sets.map { it.id }, lift.sets.map { it.id })
    }

    @Test
    fun addSetRepeatsTheLastSet() {
        val part = session("back_squat", 100.0 to 5)
        val added = ManualForms.newSet(part, ids)
        assertEquals(100.0 to 5, added.load to added.reps)
        assertTrue(added.id != part.sets.single().id)
        val empty = ManualForms.newSet(part.copy(sets = emptyList()), ids)
        assertEquals(null to null, empty.load to empty.reps)
    }

    @Test
    fun aSetWithoutRepsBlocksCreate() {
        val part = session("back_squat", 100.0 to 5, 100.0 to null)
        val blocking = builder.build(blank().copy(parts = listOf(part))).report.blocking
        assertEquals(listOf("reps"), blocking.filter { it.code == "missing_value" }.map { it.path })
        assertEquals(part.sets[1].id, blocking.single { it.path == "reps" }.nodeId)
    }

    @Test
    fun tooManySetsBlockCreate() {
        val part = session("back_squat", *Array(WatchLimits.SETS + 1) { 100.0 to 1 })
        assertTrue(builder.build(blank().copy(parts = listOf(part))).report.blocking.any { it.code == "invalid_bounds" })
    }

    @Test
    fun everyWeightIsInTheSettingsUnit() {
        val catalog = TestSupport.catalog
        val lift = session("back_squat", 225.0 to 5)
        val metcon = ManualForms.newPart(ids, 1, ManualFormat.AMRAP).copy(
            durationText = "10:00",
            movements = listOf(
                ManualForms.pick(ManualForms.newMovement(ids), catalog.byId("thruster")!!, schemePart = false).copy(amount = 10.0, load = 95.0),
            ),
        )
        val form = ManualForms.blank(ids, "2026-11-05", "Asia/Jerusalem", MassUnit.LB).copy(parts = listOf(lift, metcon))
        val items = builder.build(form).workout.parts.flatMap { it.body }.filterIsInstance<Item>()
        val loads = items.map { it.load ?: it.sets.first().load }
        assertEquals(listOf(AbsoluteLoad(Mass(225.0, MassUnit.LB)), AbsoluteLoad(Mass(95.0, MassUnit.LB))), loads)
    }

    @Test
    fun templateLoadsAreGivenInTheSettingsUnit() {
        val thrusterKg = ManualTemplates.apply(blank(), template("For time 21-15-9"), ids).parts.single().movements.first()
        assertEquals(43.0, thrusterKg.load)
        val swingKg = ManualTemplates.apply(blank(), template("EMOM 10"), ids).parts.single().movements.first()
        assertEquals(24.0, swingKg.load)
        val lbForm = ManualForms.blank(ids, "2026-11-05", "Asia/Jerusalem", MassUnit.LB)
        val thrusterLb = ManualTemplates.apply(lbForm, template("For time 21-15-9"), ids).parts.single().movements.first()
        assertEquals(95.0, thrusterLb.load)
    }

    @Test
    fun aSetsWeightAndItsPercentOfTheGoalFollowEachOther() {
        val set = ManualSetForm(id = "s")
        // % → weight, rounded to the Settings increment (2.5 here).
        val byPercent = ManualForms.withSetPercent(set, 72.0, goal = 140.0, step = 2.5)
        assertEquals(72.0 to 100.0, byPercent.ofGoal to byPercent.load)
        // weight → % of the goal, one decimal.
        val byWeight = ManualForms.withSetWeight(byPercent, 105.0, goal = 140.0)
        assertEquals(105.0 to 75.0, byWeight.load to byWeight.ofGoal)
        // No goal: a weight stands alone and a % changes nothing else.
        assertEquals(null, ManualForms.withSetWeight(set, 80.0, goal = null).ofGoal)
        assertEquals(null, ManualForms.withSetPercent(set, 80.0, goal = null, step = 1.0).load)
    }

    @Test
    fun changingTheGoalRecomputesPercentSetsAndPercentagesOfWeightSets() {
        val part = session("back_squat", 60.0 to 5, null to 5).let { p ->
            p.copy(sets = listOf(p.sets[0], p.sets[1].copy(ofGoal = 80.0)))
        }
        val withGoal = ManualForms.withGoal(part, 100.0, step = 1.0)
        assertEquals(100.0, withGoal.scheme.goalWeight)
        assertEquals(listOf(60.0, 80.0), withGoal.sets.map { it.load })
        assertEquals(listOf(60.0, 80.0), withGoal.sets.map { it.ofGoal })
        val raised = ManualForms.withGoal(withGoal, 120.0, step = 1.0)
        assertEquals(listOf(72.0, 96.0), raised.sets.map { it.load })
        // The workout keeps only the weights.
        val built = builder.build(blank().copy(parts = listOf(raised)))
        assertTrue(built.canCreate, built.report.blocking.toString())
        val lift = built.workout.parts.single().body.single() as Item
        assertEquals(listOf(72.0, 96.0), lift.sets.map { (it.load as AbsoluteLoad).mass.value })
    }

    @Test
    fun strengthQuickStartsArePercentOfGoalSessions() {
        fun percents(name: String) = ManualTemplates.apply(blank(), template(name), ids).parts.single()
        val fiveByFive = percents("5 × 5 · 70–80%")
        assertEquals(StrengthScheme(), fiveByFive.scheme)
        assertEquals(null, fiveByFive.scheme.goalWeight)
        assertEquals(listOf(70.0, 72.5, 75.0, 77.5, 80.0), fiveByFive.sets.map { it.ofGoal })
        assertEquals(List(5) { 5 }, fiveByFive.sets.map { it.reps })
        val fiveByThree = percents("5 × 3 · 80–85%")
        assertEquals(listOf(80.0, 81.0, 82.5, 83.5, 85.0), fiveByThree.sets.map { it.ofGoal })
        assertEquals(List(5) { 3 }, fiveByThree.sets.map { it.reps })
        val threeByFive = percents("3 × 5 · 70–80%")
        assertEquals(listOf(70.0, 75.0, 80.0), threeByFive.sets.map { it.ofGoal })
        assertEquals(List(3) { 5 }, threeByFive.sets.map { it.reps })
        // Entering the goal fills every weight, rounded to the Settings increment.
        val filled = ManualForms.withGoal(fiveByThree, 140.0, 2.5)
        assertEquals(listOf(112.5, 112.5, 115.0, 117.5, 120.0), filled.sets.map { it.load })
        assertTrue(builder.build(blank().copy(parts = listOf(filled))).canCreate)
    }

    @Test
    fun setPercentsSurviveUntilAGoalIsSet() {
        val start = ManualTemplates.apply(blank(), template("3 × 5 · 70–80%"), ids).parts.single()
        // Without a goal the sets have no weight yet, so Create is blocked with a hint.
        val unfilled = builder.build(blank().copy(parts = listOf(start)))
        assertFalse(unfilled.canCreate)
        assertTrue(unfilled.report.blocking.any { "goal weight" in it.message }, unfilled.report.blocking.toString())
        // Typing a weight before the goal keeps that set's %.
        val typed = ManualForms.withSetWeight(start.sets.first(), 60.0, goal = null)
        assertEquals(60.0, typed.load)
        assertEquals(70.0, typed.ofGoal)
        // Clearing the goal (e.g. to retype it) keeps every %.
        val withGoal = ManualForms.withGoal(start, 100.0, step = 1.0)
        val cleared = ManualForms.withGoal(withGoal, null, step = 1.0)
        assertEquals(null, cleared.scheme.goalWeight)
        assertEquals(listOf(70.0, 75.0, 80.0), cleared.sets.map { it.ofGoal })
        assertEquals(listOf(70.0, 75.0, 80.0), cleared.sets.map { it.load })
    }
}
