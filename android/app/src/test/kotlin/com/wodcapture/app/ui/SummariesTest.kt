package com.wodcapture.app.ui

import com.wodcapture.domain.model.AbsoluteLoad
import com.wodcapture.domain.model.Amrap
import com.wodcapture.domain.model.Item
import com.wodcapture.domain.model.Mass
import com.wodcapture.domain.model.MassUnit
import com.wodcapture.domain.model.Part
import com.wodcapture.domain.model.RepTarget
import com.wodcapture.domain.model.StrengthSet
import com.wodcapture.domain.model.StrengthSets
import org.junit.Assert.assertEquals
import org.junit.Test

/** Library one-liners read as the lift for weights and as format + movements for a metcon. */
class SummariesTest {
    @Test
    fun weightsReadAsTheLift() {
        val sets = (1..5).map { StrengthSet("s$it", reps = RepTarget(5)) }
        val p = Part(id = "a", format = StrengthSets(), body = listOf(Item("i", "back_squat", "Back squat", sets = sets)))
        assertEquals("Back squat 5×5", Summaries.part(p))
    }

    /** A session built set by set reads as its ramp, with the % of the top weight (REQ-AE08). */
    @Test
    fun weightsReadTheRampAndItsPercentOfTheTopWeight() {
        val weights = listOf(70.0, 75.0, 80.0)
        val sets = weights.mapIndexed { i, w -> StrengthSet("s$i", reps = RepTarget(5), load = AbsoluteLoad(Mass(w, MassUnit.KG)), ofTop = w) }
        val item = Item("i", "back_squat", "Back squat", sets = sets)
        val top = Part(id = "a", format = StrengthSets(topWeight = Mass(100.0, MassUnit.KG)), body = listOf(item))
        assertEquals("Back squat 3×5 @ 70–80 kg (70–80 %)", Summaries.part(top))
        // Without a top weight the weights still show; there is just no % to read them against.
        val plain = top.copy(format = StrengthSets(), body = listOf(item.copy(sets = sets.map { it.copy(ofTop = null) })))
        assertEquals("Back squat 3×5 @ 70–80 kg", Summaries.part(plain))
        // One weight for every set reads as that weight.
        val flat = top.copy(body = listOf(item.copy(sets = sets.map { it.copy(load = AbsoluteLoad(Mass(80.0, MassUnit.KG)), ofTop = 80.0) })))
        assertEquals("Back squat 3×5 @ 80 kg (80 %)", Summaries.part(flat))
    }

    @Test
    fun metconReadsAsFormatAndMovements() {
        val body = listOf("Pull-up", "Push-up", "Air squat", "Burpee").mapIndexed { i, n -> Item("i$i", null, n) }
        val p = Part(id = "b", format = Amrap(1200), body = body)
        assertEquals("AMRAP 20:00 · Pull-up, Push-up, Air squat +1", Summaries.part(p))
    }

    @Test
    fun datesAndStatuses() {
        assertEquals("No date", Summaries.day(null))
        assertEquals("not-a-date", Summaries.day("not-a-date"))
        assertEquals("Ready to review", Summaries.status("ready"))
    }
}
