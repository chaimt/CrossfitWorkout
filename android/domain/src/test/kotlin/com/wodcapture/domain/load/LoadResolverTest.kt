package com.wodcapture.domain.load

import com.wodcapture.domain.TestSupport
import com.wodcapture.domain.model.AbsoluteLoad
import com.wodcapture.domain.model.AthleteChoiceLoad
import com.wodcapture.domain.model.BodyweightLoad
import com.wodcapture.domain.model.ConversionMode
import com.wodcapture.domain.model.Mass
import com.wodcapture.domain.model.MassUnit
import com.wodcapture.domain.model.PercentOfBodyweight
import com.wodcapture.domain.model.PercentOfMax
import com.wodcapture.domain.model.RelativeToTopLoad
import com.wodcapture.domain.model.RepMax
import com.wodcapture.domain.model.SexSelection
import com.wodcapture.domain.model.SplitLoad
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** REQ-DM11, REQ-DM19, §4.1, Appendix D. */
class LoadResolverTest {
    private val profile = TestSupport.profile
    private val barbell = LoadContext("thruster", "barbell")
    private fun lb(v: Double) = Mass(v, MassUnit.LB)
    private fun kg(v: Double) = Mass(v, MassUnit.KG)

    private fun resolved(r: ResolvedLoad): ResolvedLoad.Resolved = assertIs<ResolvedLoad.Resolved>(r)

    @ParameterizedTest(name = "{0} lb barbell = {1} kg (Appendix D)")
    @CsvSource("95,43", "185,83", "125,56", "135,61", "225,102", "45,20", "315,143")
    fun publishedBarbellEquivalents(lbValue: Double, kgValue: Double) {
        val r = resolved(LoadResolver(profile).resolve(AbsoluteLoad(lb(lbValue)), barbell))
        assertEquals(kg(kgValue), r.mass)
        assertEquals(ResolutionMethod.EQUIVALENT, r.method)
        assertEquals(lb(lbValue), r.original)
    }

    @Test
    fun dumbbellAndKettlebellTablesAreImplementSpecific() {
        val r = LoadResolver(profile)
        assertEquals(kg(22.5), resolved(r.resolve(AbsoluteLoad(lb(50.0)), LoadContext("dumbbell_snatch", "dumbbell"))).mass)
        assertEquals(kg(24.0), resolved(r.resolve(AbsoluteLoad(lb(53.0)), LoadContext("american_kettlebell_swing", "kettlebell"))).mass)
        assertEquals(kg(9.0), resolved(r.resolve(AbsoluteLoad(lb(20.0)), LoadContext("wall_ball_shot", "medicine_ball"))).mass)
    }

    @Test
    fun kgToLbUsesTheTableInReverse() {
        val lbProfile = profile.copy(displayUnit = MassUnit.LB)
        val r = resolved(LoadResolver(lbProfile).resolve(AbsoluteLoad(kg(24.0)), LoadContext("american_kettlebell_swing", "kettlebell")))
        assertEquals(lb(53.0), r.mass)
    }

    @Test
    fun poodUsesRulebookMapping() {
        val r = LoadResolver(profile)
        val kb = LoadContext("american_kettlebell_swing", "kettlebell")
        assertEquals(kg(24.0), resolved(r.resolve(AbsoluteLoad(Mass(1.5, MassUnit.POOD)), kb)).mass)
        val lbR = LoadResolver(profile.copy(displayUnit = MassUnit.LB))
        assertEquals(lb(70.0), resolved(lbR.resolve(AbsoluteLoad(Mass(2.0, MassUnit.POOD)), kb)).mass)
    }

    @Test
    fun arithmeticModeRoundsToBarIncrementTiesDown() {
        val r = LoadResolver(profile.copy(conversionMode = ConversionMode.ARITHMETIC))
        // 95 lb = 43.09 kg -> nearest 1 kg = 43.
        val a = resolved(r.resolve(AbsoluteLoad(lb(95.0)), barbell))
        assertEquals(kg(43.0), a.mass)
        assertEquals(ResolutionMethod.ARITHMETIC, a.method)
        // Values absent from the table fall back to arithmetic even in equivalents mode: 110 lb = 49.9 kg.
        assertEquals(kg(50.0), resolved(LoadResolver(profile).resolve(AbsoluteLoad(lb(110.0)), barbell)).mass)
    }

    @Test
    fun tiesGoDownward() {
        assertEquals(42.0, MassMath.roundToStep(42.5, 1.0))
        assertEquals(100.0, MassMath.roundToStep(101.25, 2.5))
        assertEquals(102.5, MassMath.roundToStep(101.3, 2.5))
        assertEquals(20.0, MassMath.nearestInInventory(21.0, listOf(18.0, 20.0, 22.0)))
    }

    @Test
    fun percentOfRepMaxUsesPhysicalMassAndLoadableRounding() {
        val r = resolved(LoadResolver(profile).resolve(PercentOfMax(80.0), LoadContext("back_squat", "barbell")))
        assertEquals(kg(100.0), r.mass)
        assertEquals("80% -> 100 kg", r.note)
        // 1RM in lb, athlete in kg: 275 lb physical = 124.74 kg; 75% = 93.55 -> 94 kg (not the table's 125).
        val lbRm = profile.copy(repMaxes = listOf(RepMax("deadlift", 1, lb(275.0))))
        assertEquals(kg(94.0), resolved(LoadResolver(lbRm).resolve(PercentOfMax(75.0), LoadContext("deadlift", "barbell"))).mass)
    }

    @Test
    fun missingRepMaxIsManualNeverZero() {
        val r = LoadResolver(profile.copy(repMaxes = emptyList())).resolve(PercentOfMax(75.0), LoadContext("back_squat", "barbell"))
        val m = assertIs<ResolvedLoad.Manual>(r)
        assertEquals("missing_rep_max", m.reason)
        assertEquals(MassUnit.KG, m.entryUnit)
    }

    @Test
    fun percentRangeNeedsASelection() {
        val range = PercentOfMax(70.0, percentMax = 75.0)
        assertIs<ResolvedLoad.NeedsChoice>(LoadResolver(profile).resolve(range, LoadContext("back_squat", "barbell")))
        val chosen = range.copy(selectedPercent = 72.0)
        assertEquals(kg(90.0), resolved(LoadResolver(profile).resolve(chosen, LoadContext("back_squat", "barbell"))).mass)
    }

    @Test
    fun bodyweightPercentage() {
        assertEquals(kg(120.0), resolved(LoadResolver(profile).resolve(PercentOfBodyweight(150.0), LoadContext("deadlift", "barbell"))).mass)
        val noBw = LoadResolver(profile.copy(bodyweight = null)).resolve(PercentOfBodyweight(150.0), LoadContext("deadlift", "barbell"))
        assertEquals("missing_bodyweight", assertIs<ResolvedLoad.Manual>(noBw).reason)
    }

    @Test
    fun belowTheBarRequiresManualChoice() {
        val r = LoadResolver(profile.copy(bodyweight = kg(20.0))).resolve(PercentOfBodyweight(50.0), LoadContext("deadlift", "barbell"))
        assertEquals("below_bar", assertIs<ResolvedLoad.Manual>(r).reason)
    }

    @Test
    fun unlabeledSplitNeedsReviewConfirmation() {
        val split = SplitLoad(lb(95.0), lb(65.0))
        assertEquals("split_unconfirmed", assertIs<ResolvedLoad.NeedsChoice>(LoadResolver(profile).resolve(split, barbell)).reason)
        val confirmed = split.copy(confirmed = true)
        assertEquals(kg(43.0), resolved(LoadResolver(profile).resolve(confirmed, barbell)).mass)
        assertEquals(kg(29.0), resolved(LoadResolver(profile.copy(sex = SexSelection.FEMALE)).resolve(confirmed, barbell)).mass)
        assertEquals("sex_always_ask", assertIs<ResolvedLoad.NeedsChoice>(LoadResolver(profile.copy(sex = SexSelection.ALWAYS_ASK)).resolve(confirmed, barbell)).reason)
        // Explicit labels win without confirmation.
        assertIs<ResolvedLoad.Resolved>(LoadResolver(profile).resolve(split.copy(labeled = true), barbell))
        // The review screen's answer to "always ask".
        assertIs<ResolvedLoad.Resolved>(LoadResolver(profile.copy(sex = SexSelection.ALWAYS_ASK), SexSelection.MALE).resolve(confirmed, barbell))
    }

    @Test
    fun unmarkedUnitsGuessLbForStandardPairsElseBoardUnit() {
        val r = LoadResolver(profile)
        val un = MassUnit.UNSPECIFIED
        assertEquals(MassUnit.LB, r.concreteUnit(Mass(95.0, un), Mass(65.0, un)))
        assertEquals(MassUnit.KG, r.concreteUnit(Mass(60.0, un), Mass(40.0, un)))
        assertEquals(MassUnit.LB, r.concreteUnit(Mass(60.0, un, guessedUnit = MassUnit.LB), null))
        val res = resolved(r.resolve(SplitLoad(Mass(95.0, un), Mass(65.0, un), confirmed = true), barbell))
        assertEquals(kg(43.0), res.mass)
        assertEquals(listOf("ambiguous_unit"), res.warnings)
    }

    @Test
    fun gearUnitsPerImplementAndInventory() {
        val p = profile.copy(gearUnits = mapOf("dumbbell" to MassUnit.LB), inventory = mapOf("dumbbell" to listOf(35.0, 40.0, 45.0, 50.0)))
        val asWritten = resolved(LoadResolver(p).resolve(AbsoluteLoad(lb(50.0)), LoadContext("dumbbell_snatch", "dumbbell", 2)))
        assertEquals(lb(50.0), asWritten.mass)
        assertEquals(2, asWritten.count)
        assertEquals(ResolutionMethod.AS_WRITTEN, asWritten.method)
        // 20 kg DB -> 44.09 lb -> nearest stocked 45 lb.
        assertEquals(lb(45.0), resolved(LoadResolver(p.copy(conversionMode = ConversionMode.ARITHMETIC)).resolve(AbsoluteLoad(kg(20.0)), LoadContext("dumbbell_snatch", "dumbbell"))).mass)
        // Beyond the inventory: manual.
        assertIs<ResolvedLoad.Manual>(LoadResolver(p.copy(conversionMode = ConversionMode.ARITHMETIC)).resolve(AbsoluteLoad(kg(40.0)), LoadContext("dumbbell_snatch", "dumbbell")))
    }

    @Test
    fun runtimeAndChoiceLoadsAreManual() {
        val r = LoadResolver(profile)
        assertIs<ResolvedLoad.Manual>(r.resolve(RelativeToTopLoad(-10.0), barbell))
        assertEquals("build", assertIs<ResolvedLoad.Manual>(r.resolve(AthleteChoiceLoad("build"), barbell)).note)
        assertEquals(ResolvedLoad.Bodyweight, r.resolve(BodyweightLoad, barbell))
        assertEquals(ResolvedLoad.Unloaded, r.resolve(null, barbell))
    }

    @Test
    fun physicalConversionAndFormatting() {
        assertEquals("43.09", MassMath.format(MassMath.convertPhysical(lb(95.0), MassUnit.KG).value))
        assertEquals("102.5", MassMath.format(102.5))
        assertEquals("43", MassMath.format(43.0))
        assertNull(Equivalents.lookup("sandbag", 100.0, MassUnit.LB, MassUnit.KG))
    }
}
