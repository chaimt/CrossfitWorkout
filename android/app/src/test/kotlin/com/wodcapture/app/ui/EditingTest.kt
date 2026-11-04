package com.wodcapture.app.ui

import com.wodcapture.domain.model.Amrap
import com.wodcapture.domain.model.BodyTree
import com.wodcapture.domain.model.Group
import com.wodcapture.domain.model.Item
import com.wodcapture.domain.model.Level
import com.wodcapture.domain.model.Measure
import com.wodcapture.domain.model.MeasureKind
import com.wodcapture.domain.model.Override
import com.wodcapture.domain.model.Part
import com.wodcapture.domain.model.RepTarget
import com.wodcapture.domain.model.StrengthSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Editor operations keep IDs stable and never flatten nesting (REQ-AE02, REQ-DM15). */
class EditingTest {
    private val a = Item("a", "pull_up", "Pull-up", measure = Measure(MeasureKind.REPS, 5.0))
    private val b = Item("b", "push_up", "Push-up", measure = Measure(MeasureKind.REPS, 10.0))
    private val part = Part(
        id = "p", format = Amrap(600),
        body = listOf(Group("g", children = listOf(a, b)), Item("c", "air_squat", "Air squat")),
        levels = listOf(Level("Scaled", listOf(Override("b", name = "Knee push-up")))),
    )

    @Test
    fun removingANodeDropsItsOverrides() {
        val p = Editing.removeNode(part, "b")
        assertEquals(listOf("a"), (p.body[0] as Group).children.map { it.id })
        assertTrue(p.levels[0].overrides.isEmpty())
    }

    @Test
    fun updateAndAddPreserveNesting() {
        val p = Editing.addItem(Editing.updateNode(part, "a") { (it as Item).copy(name = "Chest-to-bar") }, "g", Item("d", null, "New"))
        assertEquals(listOf("a", "b", "d"), (p.body[0] as Group).children.map { it.id })
        assertEquals("Chest-to-bar", (BodyTree.find(p.body, "a") as Item).name)
        assertEquals(listOf("c", "g"), Editing.move(p, "c", -1).body.map { it.id })
    }

    @Test
    fun reshapingSetsKeepsExistingIds() {
        val item = Item("s", "back_squat", "Back squat", sets = listOf(StrengthSet("s1", RepTarget(5)), StrengthSet("s2", RepTarget(5))))
        var n = 0
        val r = Editing.reshapeSets(item, 3, 3, 120) { "new${++n}" }
        assertEquals(listOf("s1", "s2", "new1"), r.sets.map { it.id })
        assertTrue(r.sets.all { it.reps.value == 3 && it.restSec == 120 })
        assertEquals(1, Editing.reshapeSets(item, 1, null, null) { "x" }.sets.size)
    }

    @Test
    fun formatSwitchPicksANaturalScore() {
        Editing.formatTypes.forEach { t -> assertEquals(t, Editing.formatType(Editing.newFormat(t))) }
        assertEquals("for_time", Editing.formatType(Editing.newFormat("for_time")))
    }
}
