package com.wodcapture.domain.review

import com.wodcapture.domain.load.MassMath
import com.wodcapture.domain.model.AbsoluteLoad
import com.wodcapture.domain.model.AthleteChoiceLoad
import com.wodcapture.domain.model.BodyweightLoad
import com.wodcapture.domain.model.LoadSpec
import com.wodcapture.domain.model.Mass
import com.wodcapture.domain.model.MassUnit
import com.wodcapture.domain.model.NoLoad
import com.wodcapture.domain.model.PercentOfBodyweight
import com.wodcapture.domain.model.PercentOfMax
import com.wodcapture.domain.model.RelativeToTopLoad
import com.wodcapture.domain.model.RpeLoad
import com.wodcapture.domain.model.SplitLoad

/**
 * Board-style load notation for the structured editor (REQ-AE02, Appendix D): "95/65 lb",
 * "43 kg", "95#", "1.5 pood", "80%", "70-75%", "1.5xBW", "BW", "RPE 8", "-10%", free text.
 * Pairs typed in review count as a confirmed mapping (the athlete chose the order).
 */
object LoadText {
    private val NUM = "(\\d+(?:\\.\\d+)?)"
    private val unitRe = "\\s*(kg|kgs|lb|lbs|#|pood|pd)?"
    private val split = Regex("^$NUM\\s*/\\s*$NUM$unitRe$", RegexOption.IGNORE_CASE)
    private val single = Regex("^$NUM$unitRe$", RegexOption.IGNORE_CASE)
    private val pct = Regex("^@?\\s*$NUM(?:\\s*-\\s*$NUM)?\\s*%$")
    private val bw = Regex("^$NUM\\s*x\\s*BW$", RegexOption.IGNORE_CASE)
    private val rpe = Regex("^@?\\s*RPE\\s*$NUM$", RegexOption.IGNORE_CASE)
    private val backoff = Regex("^-\\s*$NUM\\s*%$")

    fun parse(text: String, defaultUnit: MassUnit = MassUnit.UNSPECIFIED): LoadSpec? {
        val t = text.trim()
        if (t.isEmpty()) return null
        if (t.equals("none", true)) return NoLoad
        if (t.equals("bw", true) || t.equals("bodyweight", true)) return BodyweightLoad
        backoff.matchEntire(t)?.let { return RelativeToTopLoad(-it.groupValues[1].toDouble()) }
        rpe.matchEntire(t)?.let { return RpeLoad(it.groupValues[1].toDouble()) }
        bw.matchEntire(t)?.let { return PercentOfBodyweight(it.groupValues[1].toDouble() * 100) }
        pct.matchEntire(t)?.let { m ->
            val hi = m.groupValues[2].takeIf { it.isNotEmpty() }?.toDouble()
            return PercentOfMax(m.groupValues[1].toDouble(), percentMax = hi)
        }
        split.matchEntire(t)?.let { m ->
            val u = unit(m.groupValues[3], defaultUnit)
            return SplitLoad(Mass(m.groupValues[1].toDouble(), u), Mass(m.groupValues[2].toDouble(), u), labeled = false, confirmed = true)
        }
        single.matchEntire(t)?.let { m -> return AbsoluteLoad(Mass(m.groupValues[1].toDouble(), unit(m.groupValues[2], defaultUnit))) }
        return AthleteChoiceLoad(t)
    }

    private fun unit(s: String, default: MassUnit): MassUnit = when (s.lowercase()) {
        "kg", "kgs" -> MassUnit.KG
        "lb", "lbs", "#" -> MassUnit.LB
        "pood", "pd" -> MassUnit.POOD
        else -> default
    }

    fun format(l: LoadSpec?): String = when (l) {
        null, NoLoad -> ""
        BodyweightLoad -> "BW"
        is AbsoluteLoad -> mass(l.mass)
        is SplitLoad -> "${MassMath.format(l.male.value)}/${MassMath.format(l.female.value)} ${MassMath.unitLabel(l.female.unit)}".trim()
        is PercentOfMax -> (l.percentMax?.let { "${MassMath.format(l.percent)}-${MassMath.format(it)}%" } ?: "${MassMath.format(l.percent)}%")
        is PercentOfBodyweight -> "${MassMath.format(l.percent / 100)}xBW"
        is RpeLoad -> "RPE ${MassMath.format(l.value)}"
        is RelativeToTopLoad -> "${MassMath.format(l.deltaPercent)}%"
        is AthleteChoiceLoad -> l.text
    }

    private fun mass(m: Mass) = "${MassMath.format(m.value)} ${MassMath.unitLabel(m.unit)}".trim()
}

/** Time entry in m:ss or h:mm:ss (REQ-AE02, REQ-WE04). */
object TimeText {
    fun parse(text: String): Int? {
        val t = text.trim()
        if (t.isEmpty()) return null
        t.toIntOrNull()?.let { return it * 60 } // bare number = minutes ("AMRAP 12")
        val parts = t.trimStart(':').split(':')
        if (parts.any { it.isEmpty() || it.toIntOrNull() == null }) return null
        val n = parts.map { it.toInt() }
        if (n.drop(1).any { it !in 0..59 }) return null
        return when (n.size) {
            1 -> n[0]
            2 -> n[0] * 60 + n[1]
            3 -> n[0] * 3600 + n[1] * 60 + n[2]
            else -> null
        }
    }

    fun format(sec: Int?): String = sec?.let { com.wodcapture.domain.plan.DisplayText.clock(it) }.orEmpty()
}
