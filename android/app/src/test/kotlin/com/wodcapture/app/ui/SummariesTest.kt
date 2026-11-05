package com.wodcapture.app.ui

import com.wodcapture.domain.model.Amrap
import com.wodcapture.domain.model.Item
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
