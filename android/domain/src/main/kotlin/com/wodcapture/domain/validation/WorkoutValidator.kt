package com.wodcapture.domain.validation

import com.wodcapture.domain.expand.RepSchemeExpander
import com.wodcapture.domain.model.AbsoluteLoad
import com.wodcapture.domain.model.Amrap
import com.wodcapture.domain.model.BodyNode
import com.wodcapture.domain.model.BodyTree
import com.wodcapture.domain.model.BuildToMax
import com.wodcapture.domain.model.CustomSegments
import com.wodcapture.domain.model.DeathBy
import com.wodcapture.domain.model.Emom
import com.wodcapture.domain.model.ForTime
import com.wodcapture.domain.model.Group
import com.wodcapture.domain.model.GroupKind
import com.wodcapture.domain.model.Intervals
import com.wodcapture.domain.model.Item
import com.wodcapture.domain.model.LoadSpec
import com.wodcapture.domain.model.Mass
import com.wodcapture.domain.model.MassUnit
import com.wodcapture.domain.model.MaxEffort
import com.wodcapture.domain.model.MeasureKind
import com.wodcapture.domain.model.NotForTime
import com.wodcapture.domain.model.PartKind
import com.wodcapture.domain.model.Warmups
import com.wodcapture.domain.model.Part
import com.wodcapture.domain.model.PercentOfMax
import com.wodcapture.domain.model.Rest
import com.wodcapture.domain.model.ScoreType
import com.wodcapture.domain.model.StrengthSets
import com.wodcapture.domain.model.Tabata
import com.wodcapture.domain.model.WORKOUT_SCHEMA_VERSION
import com.wodcapture.domain.model.Workout

enum class Severity { BLOCKING, WARNING }

/** One validation finding, keyed by part/node and field path (REQ-AX05, REQ-DM17). */
data class ValidationIssue(
    val severity: Severity,
    val code: String,
    val message: String,
    val partId: String? = null,
    val nodeId: String? = null,
    val path: String? = null,
)

/** Validation outcome. Send is allowed only without blocking issues. */
data class ValidationReport(val issues: List<ValidationIssue>) {
    val blocking: List<ValidationIssue> get() = issues.filter { it.severity == Severity.BLOCKING }
    val warnings: List<ValidationIssue> get() = issues.filter { it.severity == Severity.WARNING }
    val canSend: Boolean get() = blocking.isEmpty()
    fun codes(): Set<String> = issues.map { it.code }.toSet()
}

/**
 * Deterministic validation after parse and every edit (REQ-AX05). Blocking findings block Send
 * until repaired or converted to an accepted fallback; nothing is clamped or discarded.
 */
object WorkoutValidator {
    const val MAX_DEPTH = 4
    const val MAX_REPEAT = 200
    const val MAX_PARTS = 6
    const val MAX_EXPANDED_ROUNDS = 200
    private const val UNUSUAL_LOAD_KG = 300.0
    private const val UNUSUAL_CAP_SEC = 2 * 3600

    fun validate(w: Workout): ValidationReport {
        val issues = mutableListOf<ValidationIssue>()
        if (w.schemaVersion != WORKOUT_SCHEMA_VERSION) {
            issues += block("unsupported_schema_version", "Workout schema version ${w.schemaVersion} is not supported")
            return ValidationReport(issues)
        }
        if (w.parts.isEmpty()) issues += block("no_parts", "Workout has no parts")
        if (w.parts.count { it.effectiveRunOnWatch } > MAX_PARTS) {
            issues += block("too_many_parts", "More than $MAX_PARTS parts run on the watch; split the workout")
        }
        val allIds = w.parts.map { it.id } + w.parts.flatMap { BodyTree.allIds(it) }
        allIds.groupBy { it }.filter { it.value.size > 1 }.keys.forEach {
            issues += block("duplicate_id", "Node ID $it is used more than once", nodeId = it)
        }
        w.parts.forEach { issues += validatePart(it) }
        return ValidationReport(issues)
    }

    fun validatePart(raw: Part): List<ValidationIssue> {
        val p = Warmups.prepare(raw)
        val out = mutableListOf<ValidationIssue>()
        fun b(code: String, msg: String, node: String? = null, path: String? = null) =
            out.add(block(code, msg, p.id, node, path))
        fun warn(code: String, msg: String, node: String? = null, path: String? = null) =
            out.add(ValidationIssue(Severity.WARNING, code, msg, p.id, node, path))

        if (BodyTree.depth(p.body) > MAX_DEPTH) b("depth_exceeded", "Body nesting deeper than $MAX_DEPTH")
        validateFormat(p, ::b, ::warn)
        if (p.kind == PartKind.WARMUP && (p.format is ForTime || p.format is NotForTime) && Warmups.timeCap(p) <= 0) {
            b("nonpositive_duration", "Warm-up time cap must be positive", path = "fallback.capSec")
        }
        validateNodes(p, p.body, ::b, ::warn)
        validateOverrides(p, ::b)
        validateExpansion(p, ::b)
        validateScoring(p, ::b)
        if (requiresFallback(p) != null && p.fallback?.accepted != true) {
            b("fallback_required", "${requiresFallback(p)}: accept the manual-timer fallback or edit the part", path = "fallback")
        }
        return out
    }

    /** Why a part has no dedicated MVP watch mode, or `null` if it has one (REQ-DM18, §4.3). */
    fun requiresFallback(p: Part): String? {
        val groups = BodyTree.flatten(p.body).filterIsInstance<Group>()
        return when {
            p.hints?.partner != null -> "Partner/team workout"
            p.format is ForTime && (p.format as ForTime).eachRoundForTime -> "Each round for time"
            p.format is DeathBy -> "Death By (v1)"
            p.format is NotForTime -> "Not for time (v1)"
            p.format is CustomSegments -> "Custom segments"
            groups.any { it.kind == GroupKind.SUPERSET } -> "Superset (v1)"
            groups.any { it.kind == GroupKind.COMPLEX } -> "Complex (v1)"
            BodyTree.items(p.body).any { s -> s.sets.any { it.cluster != null } } -> "Cluster sets (v1)"
            else -> null
        }
    }

    private fun validateFormat(
        p: Part,
        b: (String, String, String?, String?) -> Boolean,
        warn: (String, String, String?, String?) -> Boolean,
    ) {
        fun positive(v: Int?, field: String, label: String) {
            when {
                v == null -> b("missing_duration", "$label is required", null, "format.$field")
                v <= 0 -> b("nonpositive_duration", "$label must be positive", null, "format.$field")
            }
        }
        when (val f = p.format) {
            is ForTime -> f.capSec?.let {
                if (it <= 0) b("nonpositive_duration", "Cap must be positive", null, "format.capSec")
                else if (it > UNUSUAL_CAP_SEC) warn("unusual_cap", "Cap over 2 hours", null, "format.capSec")
            }
            is Amrap -> positive(f.durationSec, "durationSec", "AMRAP duration")
            is Emom -> {
                positive(f.intervalSec, "intervalSec", "EMOM interval")
                positive(f.intervalCount, "intervalCount", "EMOM interval count")
                f.stations.forEach { s ->
                    if (s.nodeId != null && BodyTree.find(p.body, s.nodeId) == null) {
                        b("dangling_reference", "EMOM station references missing node ${s.nodeId}", s.nodeId, "format.stations")
                    }
                    val iv = f.intervalSec
                    if (s.workSec != null && (s.workSec <= 0 || (iv != null && s.workSec > iv))) {
                        b("invalid_bounds", "Station work window must be within the interval", s.nodeId, "format.stations")
                    }
                }
            }
            is Tabata -> {
                positive(f.workSec, "workSec", "Tabata work")
                positive(f.rounds, "rounds", "Tabata rounds")
                if (f.restSec < 0) b("nonpositive_duration", "Tabata rest cannot be negative", null, "format.restSec")
                f.blocks.filter { BodyTree.find(p.body, it) == null }.forEach {
                    b("dangling_reference", "Tabata block references missing node $it", it, "format.blocks")
                }
            }
            is Intervals -> {
                positive(f.workSec, "workSec", "Interval work")
                positive(f.rounds, "rounds", "Interval rounds")
                if (f.restSec < 0) b("nonpositive_duration", "Interval rest cannot be negative", null, "format.restSec")
            }
            is DeathBy -> positive(f.intervalSec, "intervalSec", "Death By interval")
            is MaxEffort -> if (f.windowSec == null && f.attempts == null) {
                b("missing_duration", "Max effort needs a window or an attempt count", null, "format.windowSec")
            } else f.windowSec?.let { positive(it, "windowSec", "Max-effort window") }
            is StrengthSets -> {
                val setIds = BodyTree.items(p.body).flatMap { i -> i.sets.map { it.id } }.toSet()
                f.setRefs.filter { it !in setIds }.forEach {
                    b("dangling_reference", "Set reference $it not found", it, "format.setRefs")
                }
                if (setIds.isEmpty()) b("missing_sets", "Strength part has no sets", null, "body")
            }
            is BuildToMax -> {
                if (f.repsPerSet <= 0) b("invalid_bounds", "Reps per attempt must be positive", null, "format.repsPerSet")
                f.attemptLimit?.let { if (it !in 1..30) b("invalid_bounds", "Attempt limit must be 1–30", null, "format.attemptLimit") }
                f.windowSec?.let { positive(it, "windowSec", "Build window") }
            }
            NotForTime, is CustomSegments -> Unit
        }
    }

    private fun validateNodes(
        p: Part,
        nodes: List<BodyNode>,
        b: (String, String, String?, String?) -> Boolean,
        warn: (String, String, String?, String?) -> Boolean,
    ) {
        for (n in BodyTree.flatten(nodes)) when (n) {
            is Group -> {
                if (n.repeat !in 1..MAX_REPEAT) b("repeat_out_of_range", "Repeat must be 1–$MAX_REPEAT", n.id, "repeat")
                if (n.repScheme.any { it <= 0 }) b("invalid_rep_scheme", "Rep scheme values must be positive", n.id, "repScheme")
                if (n.repScheme.size > MAX_REPEAT) b("invalid_rep_scheme", "Rep scheme longer than $MAX_REPEAT", n.id, "repScheme")
                if (n.repScheme.isNotEmpty() && n.repeat != 1 && n.repeat != n.repScheme.size) {
                    b("invalid_rep_scheme", "Repeat ${n.repeat} does not match rep scheme length ${n.repScheme.size}", n.id, "repScheme")
                }
                if (n.children.isEmpty()) b("empty_group", "Group has no children", n.id, "children")
            }
            is Rest -> if (n.sec <= 0) b("nonpositive_duration", "Rest must be positive", n.id, "sec")
            is Item -> validateItem(n, nodes, b, warn)
        }
        if (p.format !is StrengthSets && p.format !is BuildToMax && BodyTree.items(p.body).isEmpty() && p.format !is NotForTime) {
            b("empty_body", "Part has no movements", null, "body")
        }
    }

    private fun validateItem(
        n: Item,
        root: List<BodyNode>,
        b: (String, String, String?, String?) -> Boolean,
        warn: (String, String, String?, String?) -> Boolean,
    ) {
        if (n.movementId == null) warn("free_text_movement", "'${n.name}' is not in the catalog", n.id, "movementId")
        if (n.count !in 1..4) b("invalid_bounds", "Implement count must be 1–4", n.id, "count")
        n.measure?.let { m ->
            if (m.kind in setOf(MeasureKind.REPS, MeasureKind.CALORIES, MeasureKind.DISTANCE, MeasureKind.DURATION)) {
                when {
                    m.value == null -> b("missing_value", "${m.kind.name.lowercase()} needs a value", n.id, "measure.value")
                    m.value <= 0 -> b("invalid_bounds", "Measure must be positive", n.id, "measure.value")
                }
            }
            if (m.kind == MeasureKind.DISTANCE && m.unit == null) b("missing_unit", "Distance needs a unit", n.id, "measure.unit")
            if (m.kind == MeasureKind.REP_SCHEME && !hasSchemeAncestor(root, n.id)) {
                b("invalid_rep_scheme", "Item uses a rep scheme but no enclosing group defines one", n.id, "measure")
            }
        }
        validateLoad(n.load, n, "load", b, warn)
        n.sets.forEachIndexed { i, s ->
            validateLoad(s.load, n, "sets[$i].load", b, warn)
            s.restSec?.let { if (it < 0) b("nonpositive_duration", "Set rest cannot be negative", s.id, "sets[$i].restSec") }
            val r = s.reps
            if (!r.max && r.value == null) b("missing_value", "Set needs a rep target", s.id, "sets[$i].reps")
            if (r.value != null && r.value <= 0) b("invalid_bounds", "Set reps must be positive", s.id, "sets[$i].reps")
            if (r.upTo != null && r.value != null && r.upTo < r.value) b("invalid_bounds", "Rep range is inverted", s.id, "sets[$i].reps")
        }
    }

    private fun hasSchemeAncestor(nodes: List<BodyNode>, id: String, inScheme: Boolean = false): Boolean =
        nodes.any { n ->
            when (n) {
                is Group -> hasSchemeAncestor(n.children, id, inScheme || n.repScheme.isNotEmpty())
                else -> n.id == id && inScheme
            }
        }

    private fun validateLoad(
        load: LoadSpec?,
        n: Item,
        path: String,
        b: (String, String, String?, String?) -> Boolean,
        warn: (String, String, String?, String?) -> Boolean,
    ) {
        val masses: List<Mass> = when (load) {
            is AbsoluteLoad -> listOf(load.mass)
            else -> emptyList()
        }
        masses.forEach { m ->
            if (m.value <= 0) b("invalid_bounds", "Load must be positive", n.id, path)
            if (m.unit == MassUnit.UNSPECIFIED) warn("ambiguous_unit", "Unit of ${m.value} is not marked", n.id, path)
            val kg = when (m.unit) { MassUnit.LB -> m.value * 0.4536; MassUnit.POOD -> m.value * 16; else -> m.value }
            if (kg > UNUSUAL_LOAD_KG) warn("unusual_load", "Unusually heavy load ${m.value}", n.id, path)
        }
        if (load is PercentOfMax) {
            if (load.percent <= 0 || load.percent > 150) b("invalid_bounds", "Percentage out of range", n.id, path)
            if (load.percentMax != null && load.selectedPercent == null) warn("percent_range", "Pick a percentage within the range", n.id, path)
            warn("needs_rep_max", "Requires a stored rep max", n.id, path)
        }
    }

    private fun validateOverrides(p: Part, b: (String, String, String?, String?) -> Boolean) {
        val ids = BodyTree.allIds(p).toSet()
        p.levels.forEach { lv ->
            lv.overrides.filter { it.targetId !in ids }.forEach {
                b("dangling_override", "Level '${lv.name}' overrides missing node ${it.targetId}", it.targetId, "levels")
            }
        }
        if (p.chosenLevel != null && p.chosenLevel != "Rx" && p.levels.none { it.name == p.chosenLevel }) {
            b("unknown_level", "Chosen level '${p.chosenLevel}' does not exist", null, "chosenLevel")
        }
    }

    private fun validateExpansion(p: Part, b: (String, String, String?, String?) -> Boolean) {
        val prog = runCatching { RepSchemeExpander().program(p.body) }.getOrNull() ?: return
        if (prog.rounds.size > MAX_EXPANDED_ROUNDS) b("over_limit", "More than $MAX_EXPANDED_ROUNDS rounds", null, "body")
        val f = p.format
        if (f is Emom && (f.intervalCount ?: 0) > MAX_EXPANDED_ROUNDS) b("over_limit", "More than $MAX_EXPANDED_ROUNDS intervals", null, "format")
        if (f is Intervals && (f.rounds ?: 0) > MAX_EXPANDED_ROUNDS) b("over_limit", "More than $MAX_EXPANDED_ROUNDS intervals", null, "format")
    }

    /** Mixed measures cannot be summed without an explicit points/counting rule (REQ-DM23). */
    private fun validateScoring(p: Part, b: (String, String, String?, String?) -> Boolean) {
        val s = p.scoring
        if (s.manual || s.countingRule != null) return
        if (s.type != ScoreType.REPS && s.type != ScoreType.POINTS) return
        val kinds = BodyTree.items(p.body).mapNotNull { it.measure?.kind }
            .map { if (it == MeasureKind.REP_SCHEME || it == MeasureKind.MAX) MeasureKind.REPS else it }.toSet()
        if (kinds.size > 1) b("mixed_measure_sum", "Score sums ${kinds.joinToString { it.name.lowercase() }} without a counting rule", null, "scoring")
    }

    private fun block(code: String, msg: String, partId: String? = null, nodeId: String? = null, path: String? = null) =
        ValidationIssue(Severity.BLOCKING, code, msg, partId, nodeId, path)
}
