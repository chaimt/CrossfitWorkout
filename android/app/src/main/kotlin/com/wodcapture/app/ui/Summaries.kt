package com.wodcapture.app.ui

import com.wodcapture.domain.model.Amrap
import com.wodcapture.domain.model.BodyTree
import com.wodcapture.domain.model.BuildToMax
import com.wodcapture.domain.model.CustomSegments
import com.wodcapture.domain.model.Emom
import com.wodcapture.domain.model.ForTime
import com.wodcapture.domain.model.Format
import com.wodcapture.domain.model.Intervals
import com.wodcapture.domain.model.Item
import com.wodcapture.domain.model.MaxEffort
import com.wodcapture.domain.model.NotForTime
import com.wodcapture.domain.model.Part
import com.wodcapture.domain.model.StrengthSets
import com.wodcapture.domain.model.Tabata
import com.wodcapture.domain.plan.DisplayText
import com.wodcapture.domain.review.LoadText
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Short human text for lists and headers; pure so it is unit-tested. */
object Summaries {
    private const val MOVEMENTS_SHOWN = 3
    private val dayFormat = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault())
    private val longDayFormat = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())

    /** Friendly name of a canonical format ("AMRAP 20:00", "EMOM 10 × 1:00", "Strength sets"). */
    fun format(f: Format): String = when (f) {
        is ForTime -> "For time" + (f.capSec?.let { " · cap ${DisplayText.clock(it)}" } ?: "")
        is Amrap -> "AMRAP" + (f.durationSec?.let { " ${DisplayText.clock(it)}" } ?: "")
        is Emom -> "EMOM" + (f.intervalCount?.let { " $it" } ?: "") + (f.intervalSec?.takeIf { it != 60 }?.let { " × ${DisplayText.clock(it)}" } ?: "")
        is Tabata -> "Tabata ${f.rounds} × ${f.workSec}/${f.restSec}"
        is Intervals -> "Intervals" + (f.rounds?.let { " $it" } ?: "") + (f.workSec?.let { " × ${DisplayText.clock(it)}" } ?: "")
        is MaxEffort -> "Max ${f.measure}" + (f.windowSec?.let { " in ${DisplayText.clock(it)}" } ?: "")
        is BuildToMax -> "Build to ${if (f.repsPerSet == 1) "1RM" else "${f.repsPerSet}RM"}"
        is StrengthSets -> "Strength sets"
        is CustomSegments -> "Custom"
        NotForTime -> "For quality"
        else -> "Workout"
    }

    /**
     * One line per part. Weights read as the lift ("Back squat 5×5 @ 75%"); a metcon reads as
     * its format plus the first movements ("AMRAP 20:00 · Pull-up, Push-up, Air squat").
     */
    fun part(p: Part): String {
        val items = BodyTree.items(p.body)
        val title = p.title?.takeIf { it.isNotBlank() }
        if (p.format is StrengthSets) return items.joinToString(", ") { lift(it) }.ifBlank { title ?: format(p.format) }
        if (p.format is BuildToMax) return listOfNotNull(items.firstOrNull()?.name, format(p.format)).joinToString(" · ")
        val head = listOfNotNull(title, format(p.format)).joinToString(" · ")
        val names = items.take(MOVEMENTS_SHOWN).joinToString(", ") { it.displayName ?: it.name } +
            if (items.size > MOVEMENTS_SHOWN) " +${items.size - MOVEMENTS_SHOWN}" else ""
        return if (names.isBlank()) head else "$head · $names"
    }

    private fun lift(i: Item): String {
        val reps = i.sets.mapNotNull { it.reps.value }.distinct()
        val scheme = when {
            i.sets.isEmpty() -> ""
            reps.size == 1 -> " ${i.sets.size}×${reps.single()}"
            else -> " " + i.sets.joinToString("-") { s -> s.reps.value?.toString() ?: "?" }
        }
        val load = LoadText.format(i.load).takeIf { it.isNotBlank() }?.let { " @ $it" } ?: ""
        return "${i.displayName ?: i.name}$scheme$load"
    }

    /** "Thu, 5 Nov" for an ISO date; the raw text when it does not parse; "No date" when absent. */
    fun day(iso: String?): String =
        iso?.let { runCatching { LocalDate.parse(it).format(dayFormat) }.getOrDefault(it) } ?: "No date"

    /** "5 Nov 2026": records can be years old, so the year is shown. */
    fun longDay(date: LocalDate): String = date.format(longDayFormat)

    fun status(status: String): String = when (status) {
        "draft" -> "Draft"
        "parsing" -> "Parsing…"
        "ready" -> "Ready to review"
        "saved" -> "Saved"
        else -> status.replaceFirstChar { it.uppercase() }
    }
}
