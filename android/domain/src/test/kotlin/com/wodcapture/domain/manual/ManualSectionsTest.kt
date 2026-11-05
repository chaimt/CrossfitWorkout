package com.wodcapture.domain.manual

import com.wodcapture.domain.IdSource
import com.wodcapture.domain.TestSupport
import com.wodcapture.domain.model.Amrap
import com.wodcapture.domain.model.Part
import com.wodcapture.domain.model.PartKind
import com.wodcapture.domain.model.StrengthSets
import com.wodcapture.domain.model.WorkoutSection
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Weights and metcon stay separate sections of the manual form (owner request 2026-11-05). */
class ManualSectionsTest {
    private val ids = IdSource.sequential("a0000000")
    private val builder = ManualWorkoutBuilder(IdSource.sequential("b0000000"))
    private fun blank() = ManualForms.blank(ids, "2026-11-05", "Asia/Jerusalem")
    private fun template(name: String) = ManualTemplates.all.single { it.name == name }

    @Test
    fun everyFormatBelongsToOneSection() {
        assertEquals(listOf(ManualFormat.STRENGTH, ManualFormat.BUILD_TO_MAX), ManualFormat.of(WorkoutSection.WEIGHTS))
        assertEquals(ManualFormat.entries.size, MANUAL_SECTIONS.sumOf { ManualFormat.of(it).size })
        assertEquals(MANUAL_SECTIONS.toSet(), ManualTemplates.all.map { it.section }.toSet())
    }

    @Test
    fun weightsAreAddedAheadOfTheMetcon() {
        val form = ManualForms.addPart(blank(), ManualFormat.STRENGTH, ids)
        assertEquals(listOf(ManualFormat.STRENGTH, ManualFormat.FOR_TIME), form.parts.map { it.format })
        assertEquals(listOf("A", "B"), form.parts.map { ManualForms.effectiveLabel(form, it) })
        val built = builder.build(form).workout
        assertEquals(listOf("A", "B"), built.parts.map { it.label })
        assertEquals(listOf(PartKind.STRENGTH, PartKind.METCON), built.parts.map { it.kind })
    }

    @Test
    fun weightsAndMetconTemplatesCombine() {
        val strength = ManualTemplates.apply(blank(), template("5 × 5 strength"), ids)
        // The untouched default For-time part gives way to the template.
        assertEquals(listOf(ManualFormat.STRENGTH), strength.parts.map { it.format })
        val day = ManualTemplates.apply(strength, template("AMRAP 20"), ids)
        assertEquals(listOf(ManualFormat.STRENGTH, ManualFormat.AMRAP), day.parts.map { it.format })
        assertEquals("5 × 5 strength + AMRAP 20", day.title)
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
        val form = ManualForms.addPart(blank(), ManualFormat.AMRAP, ids)
        val amrap = form.parts.last()
        val moved = ManualForms.updatePart(form, amrap.id) { ManualForms.withFormat(it, ManualFormat.STRENGTH, ids) }
        assertEquals(amrap.id, moved.parts.first().id)
        assertEquals(PartKind.STRENGTH, moved.parts.first().kind)
        val warmup = ManualForms.withFormat(amrap.copy(kind = PartKind.WARMUP), ManualFormat.TABATA, ids)
        assertEquals(PartKind.WARMUP, warmup.kind)
    }

    @Test
    fun canonicalPartsMapToSections() {
        assertEquals(WorkoutSection.WEIGHTS, WorkoutSection.of(Part(id = "p", kind = PartKind.METCON, format = StrengthSets())))
        assertEquals(WorkoutSection.METCON, WorkoutSection.of(Part(id = "p", format = Amrap(durationSec = 600))))
        assertEquals(WorkoutSection.OTHER, WorkoutSection.of(Part(id = "p", kind = PartKind.WARMUP, format = Amrap(durationSec = 300))))
        assertEquals(WorkoutSection.WEIGHTS, WorkoutSection.of(Part(id = "p", kind = PartKind.ACCESSORY, format = Amrap(durationSec = 300))))
    }

    @Test
    fun liftsComeFromAGroupedCatalogList() {
        val groups = ManualForms.liftGroups(TestSupport.catalog)
        val ids = groups.flatMap { g -> g.lifts.map { it.id } }
        assertEquals("Squats, presses & deadlifts", groups.first().title)
        // crossfit.com lifts, including the ones added for the picker.
        listOf("back_squat", "deadlift", "clean_and_push_jerk", "dumbbell_squat_snatch", "dumbbell_hang_clean", "american_kettlebell_swing")
            .forEach { assertTrue(it in ids, it) }
        // No unloaded gymnastics or cardio.
        listOf("air_squat", "pull_up", "row", "run").forEach { assertFalse(it in ids, it) }
        assertEquals(listOf("dumbbell_power_snatch"), ManualForms.liftGroups(TestSupport.catalog, "DB PS").flatMap { g -> g.lifts.map { it.id } })
    }

    @Test
    fun aWeightsLiftMustBePickedFromTheList() {
        val typed = ManualForms.newPart(ids, 0, ManualFormat.STRENGTH).let { p -> p.copy(strength = p.strength.map { it.copy(name = "Squat thing") }) }
        val codes = builder.build(blank().copy(parts = listOf(typed))).report.blocking.map { it.code }
        assertTrue("lift_not_in_list" in codes, codes.toString())
        assertFalse("missing_name" in codes, codes.toString())
        val back = TestSupport.catalog.byId("back_squat")!!
        val picked = typed.copy(strength = typed.strength.map { ManualForms.pick(it, back).copy(load = 100.0) })
        assertTrue(builder.build(blank().copy(parts = listOf(picked))).canCreate)
    }
}
