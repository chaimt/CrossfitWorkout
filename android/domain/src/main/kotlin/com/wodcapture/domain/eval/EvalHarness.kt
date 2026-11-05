package com.wodcapture.domain.eval

import com.wodcapture.domain.DomainJson
import com.wodcapture.domain.model.BodyNode
import com.wodcapture.domain.model.Group
import com.wodcapture.domain.model.Item
import com.wodcapture.domain.model.Rest
import com.wodcapture.domain.model.Workout
import com.wodcapture.domain.parser.ParseInput
import com.wodcapture.domain.parser.ParseOutcome
import com.wodcapture.domain.parser.WorkoutParser
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File

/** Field categories reported separately (REQ-AX12: format, movements, reps, loads, caps, levels). */
enum class FieldCategory { STRUCTURE, FORMAT, MOVEMENTS, REPS, LOADS, CAPS, LEVELS, SCORING }

/** Accuracy of one fixture run. */
data class FixtureScore(val fixture: String, val matched: Map<FieldCategory, Int>, val total: Map<FieldCategory, Int>, val mismatches: List<String>, val error: String? = null) {
    val accuracy: Double get() = total.values.sum().let { t -> if (t == 0) 0.0 else matched.values.sum().toDouble() / t }
}

data class EvalReport(val fixtures: List<FixtureScore>) {
    fun accuracy(c: FieldCategory): Double {
        val t = fixtures.sumOf { it.total[c] ?: 0 }
        return if (t == 0) 1.0 else fixtures.sumOf { it.matched[c] ?: 0 }.toDouble() / t
    }

    val overall: Double get() = fixtures.map { it.accuracy }.average()
    val worst: FixtureScore? get() = fixtures.minByOrNull { it.accuracy }

    fun render(): String = buildString {
        appendLine("fixture  accuracy  mismatches")
        fixtures.forEach { f -> appendLine("%-8s %7.1f%%  %s".format(f.fixture, f.accuracy * 100, f.error ?: f.mismatches.take(5).joinToString("; "))) }
        FieldCategory.entries.forEach { c -> appendLine("%-10s %6.1f%%".format(c.name.lowercase(), accuracy(c) * 100)) }
        appendLine("overall    %6.1f%%  worst=%s".format(overall * 100, worst?.fixture))
    }
}

/**
 * Parser eval harness (REQ-AX12, REQ-TS02, REQ-TS11): runs a parser over fixture inputs and
 * compares ID-free, normalised field paths against the expected workout.
 */
object EvalHarness {

    /** Flattens a workout into `path → value` pairs independent of node IDs. */
    fun fields(w: Workout): Map<String, Pair<FieldCategory, String>> {
        val out = linkedMapOf<String, Pair<FieldCategory, String>>()
        out["parts.count"] = FieldCategory.STRUCTURE to w.parts.size.toString()
        w.parts.forEachIndexed { pi, p ->
            val pre = "p$pi"
            out["$pre.kind"] = FieldCategory.STRUCTURE to p.kind.name
            val fmt = json(DomainJson.storage.encodeToJsonElement(com.wodcapture.domain.model.Format.serializer(), p.format))
            fmt.forEach { (k, v) ->
                if (k == "stations" || k == "blocks" || k == "setRefs") return@forEach
                val cat = if (k == "capSec") FieldCategory.CAPS else FieldCategory.FORMAT
                out["$pre.format.$k"] = cat to v.toString()
            }
            out["$pre.scoring.type"] = FieldCategory.SCORING to p.scoring.type.name
            out["$pre.levels"] = FieldCategory.LEVELS to p.levels.joinToString { "${it.name}:${it.overrides.size}" }
            p.levels.forEachIndexed { li, l ->
                l.overrides.forEachIndexed { oi, o ->
                    o.movementId?.let { out["$pre.level$li.o$oi.movement"] = FieldCategory.LEVELS to it }
                    o.load?.let { out["$pre.level$li.o$oi.load"] = FieldCategory.LEVELS to loadKey(it) }
                }
            }
            nodes(p.body, "$pre.b", out)
        }
        return out
    }

    private fun nodes(list: List<BodyNode>, pre: String, out: MutableMap<String, Pair<FieldCategory, String>>) {
        list.forEachIndexed { i, n ->
            val k = "$pre$i"
            when (n) {
                is Item -> {
                    out["$k.movement"] = FieldCategory.MOVEMENTS to (n.movementId ?: "?" + n.name.lowercase())
                    n.measure?.let { m -> out["$k.measure"] = FieldCategory.REPS to "${m.kind}:${m.value}:${m.female}:${m.unit}" }
                    n.load?.let { out["$k.load"] = FieldCategory.LOADS to loadKey(it) }
                    if (n.sets.isNotEmpty()) {
                        out["$k.sets"] = FieldCategory.REPS to n.sets.joinToString(",") { s -> "${s.reps.value}-${s.reps.upTo}-${s.reps.max}" }
                        out["$k.setLoads"] = FieldCategory.LOADS to n.sets.joinToString(",") { s -> s.load?.let(::loadKey) ?: "-" }
                    }
                }
                is Group -> {
                    out["$k.group"] = FieldCategory.STRUCTURE to "${n.kind}:${n.repeat}"
                    if (n.repScheme.isNotEmpty()) out["$k.scheme"] = FieldCategory.REPS to n.repScheme.joinToString("-")
                    nodes(n.children, "$k.c", out)
                }
                is Rest -> out["$k.rest"] = FieldCategory.FORMAT to "${n.sec}:${n.inClock}"
            }
        }
    }

    private fun loadKey(l: com.wodcapture.domain.model.LoadSpec): String =
        DomainJson.storage.encodeToJsonElement(com.wodcapture.domain.model.LoadSpec.serializer(), l).let { e ->
            // Confirmation is a review flag, not parser output.
            (e as JsonObject).filterKeys { it != "confirmed" }.toString()
        }

    private fun json(e: JsonElement): Map<String, JsonElement> = (e as? JsonObject)?.filterKeys { it != "type" }?.let { o ->
        o + mapOf("type" to (e.jsonObject["type"] ?: JsonPrimitive("")))
    } ?: emptyMap()

    fun compare(fixture: String, expected: Workout, actual: Workout): FixtureScore {
        val exp = fields(expected)
        val act = fields(actual)
        val matched = mutableMapOf<FieldCategory, Int>()
        val total = mutableMapOf<FieldCategory, Int>()
        val mismatches = mutableListOf<String>()
        exp.forEach { (path, pair) ->
            val (cat, value) = pair
            total.merge(cat, 1, Int::plus)
            if (act[path]?.second == value) matched.merge(cat, 1, Int::plus)
            else mismatches += "$path: expected $value, got ${act[path]?.second}"
        }
        // Extra fields the parser invented count against structure.
        (act.keys - exp.keys).forEach { total.merge(FieldCategory.STRUCTURE, 1, Int::plus); mismatches += "$it: unexpected" }
        return FixtureScore(fixture, matched, total, mismatches)
    }

    /** Runs [parser] over every `<fixturesDir>/<name>/input.txt` that has a golden expectation. */
    fun run(parser: WorkoutParser, fixturesDir: File, goldenDir: File, runs: Int = 1): EvalReport {
        val scores = mutableListOf<FixtureScore>()
        fixturesDir.listFiles().orEmpty().filter { File(it, "input.txt").isFile }.sortedBy { it.name }.forEach { dir ->
            val expectedFile = File(goldenDir, "${dir.name}/expected.workout.json")
            if (!expectedFile.isFile) return@forEach
            val expected = DomainJson.decodeWorkout(expectedFile.readText())
            val text = File(dir, "input.txt").readText()
            repeat(runs) { r ->
                val name = if (runs > 1) "${dir.name}#${r + 1}" else dir.name
                scores += when (val o = parser.parse(ParseInput(text = text))) {
                    is ParseOutcome.Success -> compare(name, expected, o.result.workout)
                    is ParseOutcome.Failure -> FixtureScore(name, emptyMap(), mapOf(FieldCategory.STRUCTURE to 1), emptyList(), error = o.error.toString())
                }
            }
        }
        return EvalReport(scores)
    }
}
