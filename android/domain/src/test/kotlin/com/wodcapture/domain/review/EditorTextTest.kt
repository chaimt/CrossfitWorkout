package com.wodcapture.domain.review

import com.wodcapture.domain.Fixtures
import com.wodcapture.domain.IdSource
import com.wodcapture.domain.model.AbsoluteLoad
import com.wodcapture.domain.model.AthleteChoiceLoad
import com.wodcapture.domain.model.BodyTree
import com.wodcapture.domain.model.BodyweightLoad
import com.wodcapture.domain.model.Mass
import com.wodcapture.domain.model.MassUnit
import com.wodcapture.domain.model.PercentOfBodyweight
import com.wodcapture.domain.model.PercentOfMax
import com.wodcapture.domain.model.RelativeToTopLoad
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** REQ-AE02 editor notation and review actions. */
class EditorTextTest {
    @Test
    fun loadNotation() {
        assertEquals(com.wodcapture.domain.model.AthleteChoiceLoad("95/65#"), LoadText.parse("95/65#"))
        assertEquals(AbsoluteLoad(Mass(43.0, MassUnit.KG)), LoadText.parse("43 kg"))
        assertEquals(AbsoluteLoad(Mass(1.5, MassUnit.POOD)), LoadText.parse("1.5 pood"))
        assertEquals(PercentOfMax(70.0, percentMax = 75.0), LoadText.parse("70-75%"))
        assertEquals(PercentOfBodyweight(150.0), LoadText.parse("1.5xBW"))
        assertEquals(RelativeToTopLoad(-10.0), LoadText.parse("-10%"))
        assertEquals(BodyweightLoad, LoadText.parse("BW"))
        assertEquals(AthleteChoiceLoad("build"), LoadText.parse("build"))
        assertEquals(AbsoluteLoad(Mass(60.0, MassUnit.UNSPECIFIED)), LoadText.parse("60"))
        assertNull(LoadText.parse(" "))
        listOf("43 kg", "80%", "1.5xBW", "BW", "RPE 8").forEach { assertEquals(it, LoadText.format(LoadText.parse(it))) }
    }

    @Test
    fun timeEntry() {
        assertEquals(480, TimeText.parse("8:00"))
        assertEquals(3600, TimeText.parse("1:00:00"))
        assertEquals(40, TimeText.parse(":40"))
        assertEquals(720, TimeText.parse("12"))
        assertNull(TimeText.parse("8:75"))
        assertEquals("8:00", TimeText.format(480))
    }

    @Test
    fun duplicateGetsNewIdsAndRemapsOverrides() {
        val w = Fixtures.h1.expected
        val d = ReviewActions.duplicate(w, IdSource.sequential("c0ffee00"), 1)
        assertNotEquals(w.id, d.id)
        val ids = BodyTree.allIds(d.parts[0]).toSet()
        assertTrue(d.parts[0].levels[0].overrides.all { it.targetId in ids })
        assertTrue(ids.intersect(BodyTree.allIds(w.parts[0]).toSet()).isEmpty())
    }

    @Test
    fun splitHelpersAreNoOps() {
        val w = Fixtures.h1.expected
        val thruster = BodyTree.items(w.parts[0].body)[0]
        val s = ReviewActions.swapSplit(w, thruster.id)
        assertEquals(w, ReviewActions.confirmSplits(s))
    }
}
