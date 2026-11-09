package com.wodcapture.domain.plan

import com.wodcapture.domain.catalog.MovementCatalog
import com.wodcapture.domain.expand.RepSchemeExpander
import com.wodcapture.domain.expand.RoundProgram
import com.wodcapture.domain.expand.Step
import com.wodcapture.domain.levels.BASE_LEVEL
import com.wodcapture.domain.levels.DanglingOverrideException
import com.wodcapture.domain.levels.LevelApplier
import com.wodcapture.domain.load.LoadContext
import com.wodcapture.domain.load.LoadResolver
import com.wodcapture.domain.load.MassMath
import com.wodcapture.domain.load.ResolutionMethod
import com.wodcapture.domain.load.ResolvedLoad
import com.wodcapture.domain.model.Amrap
import com.wodcapture.domain.model.AthleteProfile
import com.wodcapture.domain.model.BodyNode
import com.wodcapture.domain.model.BodyTree
import com.wodcapture.domain.model.BuildToMax
import com.wodcapture.domain.model.CustomSegments
import com.wodcapture.domain.model.DeathBy
import com.wodcapture.domain.model.Emom
import com.wodcapture.domain.model.FallbackTimer
import com.wodcapture.domain.model.ForTime
import com.wodcapture.domain.model.Format
import com.wodcapture.domain.model.Group
import com.wodcapture.domain.model.Intervals
import com.wodcapture.domain.model.Item
import com.wodcapture.domain.model.ItemRole
import com.wodcapture.domain.model.MaxEffort
import com.wodcapture.domain.model.MeasureKind
import com.wodcapture.domain.model.NotForTime
import com.wodcapture.domain.model.Part
import com.wodcapture.domain.model.WorkoutSection
import com.wodcapture.domain.model.PercentOfBodyweight
import com.wodcapture.domain.model.PercentOfMax
import com.wodcapture.domain.model.Rest
import com.wodcapture.domain.model.RpeLoad
import com.wodcapture.domain.model.ScoreType
import com.wodcapture.domain.model.SexSelection
import com.wodcapture.domain.model.Side
import com.wodcapture.domain.model.SplitLength
import com.wodcapture.domain.model.StrengthSet
import com.wodcapture.domain.model.StrengthSets
import com.wodcapture.domain.model.Tabata
import com.wodcapture.domain.model.Workout
import com.wodcapture.domain.protocol.EnvelopeCodec
import com.wodcapture.domain.validation.Severity
import com.wodcapture.domain.validation.WorkoutValidator

/** Stable plan lineage: one plan UUID per workout + target; recompiling bumps the revision (REQ-DM15). */
data class PlanIdentity(val planId: String, val planRevision: Int)

/** Athlete choices made in review (REQ-AE03) plus scheduling defaults. */
data class CompileOptions(
    /** partId → level name; missing = the part's chosen level or Rx. */
    val levels: Map<String, String> = emptyMap(),
    /** Overrides the profile's sex selection (answers "always ask"). */
    val sexOverride: SexSelection? = null,
    /** Used when the workout has no scheduled date / zone. */
    val defaultDate: String = "1970-01-01",
    val defaultTimeZone: String = "UTC",
    val messageBudget: Int = WatchLimits.MESSAGE_BUDGET,
)

/** A precise reason the plan cannot be sent (REQ-SY06: reject over-limit plans before send). */
data class CompileError(val code: String, val message: String, val partId: String? = null, val nodeId: String? = null)

sealed interface CompileResult {
    data class Success(val plan: WatchPlan, val encodedBytes: Int, val warnings: List<String>) : CompileResult
    data class Failure(val errors: List<CompileError>) : CompileResult
}

/**
 * Compiles Workout + Profile + chosen levels into a [WatchPlan] (REQ-DM14, protocol §4). Every
 * display string is pre-rendered; loads are resolved per implement in the athlete's unit; parts
 * without a dedicated MVP mode compile only as an accepted manual fallback (REQ-DM18). Nothing is
 * truncated: anything that does not fit is reported as a [CompileError].
 */
class PlanCompiler(private val catalog: MovementCatalog) {

    fun compile(workout: Workout, profile: AthleteProfile, identity: PlanIdentity, options: CompileOptions = CompileOptions()): CompileResult {
        // Unknown local versions stay retained but never compile (REQ-DM26).
        if (workout.schemaVersion != com.wodcapture.domain.model.WORKOUT_SCHEMA_VERSION) {
            return CompileResult.Failure(listOf(CompileError("unsupported_schema_version", "Workout schema version ${workout.schemaVersion} is not supported")))
        }
        val errors = mutableListOf<CompileError>()
        val warnings = mutableListOf<String>()
        // Canonical class run order (warm-up → weights → metcon → other, cool-down last):
        // the phone sorts here; the watch just runs plan order.
        val parts = WorkoutSection.runOrdered(workout.parts.filter { it.effectiveRunOnWatch })
        if (parts.isEmpty()) errors += CompileError("no_parts", "No part runs on the watch")
        if (parts.size > WatchLimits.PARTS) errors += CompileError("too_many_parts", "${parts.size} parts; the watch takes at most ${WatchLimits.PARTS}")
        val title = workout.title.ifBlank { parts.firstNotNullOfOrNull { it.title } ?: "Workout" }
        if (title.length > WatchLimits.LINE) errors += CompileError("title_too_long", "Title '$title' is longer than ${WatchLimits.LINE} characters")
        val sex = options.sexOverride ?: profile.sex
        val compiled = parts.mapNotNull { part ->
            val ctx = PartCompiler(catalog, profile, sex, part, options.levels[part.id])
            ctx.compile().also { errors += ctx.errors; warnings += ctx.warnings }
        }
        if (errors.isNotEmpty()) return CompileResult.Failure(errors)
        val plan = WatchPlan(
            id = identity.planId,
            rev = identity.planRevision,
            wid = workout.id,
            wr = workout.revision,
            ti = title,
            date = workout.date ?: options.defaultDate,
            tz = workout.scheduleTimeZone ?: options.defaultTimeZone,
            u = MassMath.unitLabel(profile.displayUnit),
            p = compiled,
        )
        val bytes = EnvelopeCodec.planPutSize(plan)
        if (bytes > options.messageBudget) {
            return CompileResult.Failure(
                listOf(CompileError("plan_too_large", "Encoded plan is $bytes bytes; the limit is ${options.messageBudget}. Shorten text or split the workout.")),
            )
        }
        return CompileResult.Success(plan, bytes, warnings)
    }
}

/** Compiles one part; collects errors instead of throwing so review can show all of them. */
private class PartCompiler(
    private val catalog: MovementCatalog,
    private val profile: AthleteProfile,
    private val sex: SexSelection,
    private val original: Part,
    private val levelOverride: String?,
) {
    val errors = mutableListOf<CompileError>()
    val warnings = mutableListOf<String>()
    private val resolver = LoadResolver(profile, sex)
    private val expander = RepSchemeExpander(sex)
    private lateinit var part: Part
    private val lines = mutableListOf<String>()
    private var fallback = false

    fun compile(): WatchPart? {
        part = try {
            LevelApplier.apply(original, levelOverride)
        } catch (e: DanglingOverrideException) {
            return fail("dangling_override", e.message ?: "Dangling override", e.targetId)
        } catch (e: IllegalArgumentException) {
            return fail("unknown_level", e.message ?: "Unknown level")
        }
        WorkoutValidator.validatePart(part).filter { it.severity == Severity.BLOCKING }.forEach {
            errors += CompileError(it.code, it.message, part.id, it.nodeId)
        }
        // Dangling overrides of other (unchosen) levels also block Send (REQ-DM16).
        WorkoutValidator.validatePart(original).filter { it.code == "dangling_override" }.forEach {
            if (errors.none { e -> e.code == it.code && e.nodeId == it.nodeId }) errors += CompileError(it.code, it.message, part.id, it.nodeId)
        }
        if (errors.isNotEmpty()) return null
        // An accepted fallback wins even when a dedicated mode exists: the athlete chose it.
        fallback = part.fallback?.accepted == true
        val wp = if (fallback) compileFallback() else compileDedicated()
        return wp?.takeIf { checkLimits(it) }
    }

    private fun fail(code: String, message: String, nodeId: String? = null): WatchPart? {
        errors += CompileError(code, message, original.id, nodeId)
        return null
    }

    // ------------------------------------------------------------------ dedicated modes

    private fun compileDedicated(): WatchPart? = when (val f = part.format) {
        is ForTime -> metcon("for_time", f)
        is Amrap -> metcon("amrap", f)
        is Emom -> emom(f)
        is Tabata -> tabata(f)
        is Intervals -> intervals(f)
        is MaxEffort -> maxEffort(f)
        is StrengthSets -> strength()
        is BuildToMax -> buildToMax(f)
        is DeathBy, NotForTime, is CustomSegments -> fail("fallback_required", "No dedicated MVP mode; accept the manual fallback")
    }

    private fun metcon(mode: String, f: Format): WatchPart? {
        val forTime = f as? ForTime
        val amrap = f as? Amrap
        val program = expander.program(part.body, progressiveRounds = PROGRESSIVE_ROUNDS)
        headerLines(f, program)
        bodyLines(program)
        val movements = programItems(program).map { mvLine(it) ?: return null }
        val rr = repTotals(program, amrap != null)
        val scheme = program.group?.repScheme.orEmpty()
        val rounds = program.group?.let { expander.roundCount(it) }
        val singlePass = program.group == null || rounds == 1
        val seg = if (forTime != null) {
            listOf(WatchSegment(k = "open", cap = forTime.capSec, lb = "FOR TIME", ix = 0))
        } else {
            listOf(WatchSegment(k = "work", d = amrap!!.durationSec!!, lb = "AMRAP", ix = 0))
        }
        val tracking = if (amrap == null && singlePass && (movements.size > 1 || part.hints?.chipper == true)) "mv" else "round"
        return part(
            mode = mode,
            seg = seg,
            trk = tracking,
            mv = movements,
            rr = rr,
            // A non-progressive AMRAP compiles one round template; tell the watch it
            // repeats so extra reps stay bounded beyond round 1 (protocol §4.1 `rrc`).
            rrc = if (amrap != null && rr?.size == 1) true else null,
            rl = if (scheme.isNotEmpty() && rr != null) scheme.map { if (it == 1) "1 rep" else "$it reps" } else null,
            rnd = if (forTime != null && rounds != null && rounds > 1) rounds else null,
            cap = forTime?.capSec?.let { if (rr != null) "reps" else "text" },
            ld = loadItems(),
        )
    }

    private fun emom(f: Emom): WatchPart? {
        val interval = f.intervalSec!!
        val count = f.intervalCount!!
        lines += emomTitle(f)
        val items = BodyTree.items(part.body)
        val stations = f.stations
        val mv: List<String>
        val seg = mutableListOf<WatchSegment>()
        if (stations.isEmpty()) {
            items.forEach { addItemLines(it, null) }
            mv = items.map { mvLine(it) ?: return null }
            repeat(count) { i -> seg += WatchSegment(k = "work", d = interval, lb = intervalLabel(i, interval), ix = i) }
        } else {
            mv = stations.map { st ->
                val item = st.nodeId?.let { id -> items.firstOrNull { it.id == id } }
                if (item == null) "Rest" else mvLine(item, st.workSec) ?: return null
            }
            mv.forEachIndexed { i, text -> addWrapped("${i + 1}: $text") }
            repeat(count) { i ->
                val s = i % stations.size
                val st = stations[s]
                when {
                    st.nodeId == null -> seg += WatchSegment(k = "rest", d = interval, lb = "REST", ix = i, s = s, ic = true)
                    st.workSec != null && st.workSec < interval -> {
                        seg += WatchSegment(k = "work", d = st.workSec, lb = intervalLabel(i, interval), ix = i, s = s)
                        seg += WatchSegment(k = "rest", d = interval - st.workSec, lb = "REST", ix = i, s = s, ic = true)
                    }
                    else -> seg += WatchSegment(k = "work", d = interval, lb = intervalLabel(i, interval), ix = i, s = s)
                }
            }
        }
        return part(mode = "emom", seg = seg, mv = mv, ld = loadItems())
    }

    private fun tabata(f: Tabata): WatchPart? {
        val blocks = if (f.blocks.isNotEmpty()) f.blocks.mapNotNull { id -> BodyTree.find(part.body, id) }
        else part.body.filter { it is Item || it is Group }
        lines += "Tabata ${DisplayText.clock(f.workSec)}/${DisplayText.clock(f.restSec)} x${f.rounds}"
        if (f.restBetweenBlocksSec > 0) lines += "Rest ${DisplayText.clock(f.restBetweenBlocksSec)} between"
        val mv = blocks.map { b ->
            val item = (b as? Item) ?: BodyTree.items(listOf(b)).first()
            addItemLines(item, null)
            mvLine(item) ?: return null
        }
        val seg = mutableListOf<WatchSegment>()
        blocks.indices.forEach { b ->
            repeat(f.rounds) { r ->
                seg += WatchSegment(k = "work", d = f.workSec, lb = "WORK", ix = r, s = b)
                val lastOfBlock = r == f.rounds - 1
                val lastOverall = lastOfBlock && b == blocks.lastIndex
                if (!lastOverall) {
                    val d = if (lastOfBlock && f.restBetweenBlocksSec > 0) f.restBetweenBlocksSec else f.restSec
                    if (d > 0) seg += WatchSegment(k = "rest", d = d, lb = "REST", ix = r, s = b, ic = true)
                }
            }
        }
        return part(mode = "tabata", seg = seg, mv = mv, ld = loadItems())
    }

    private fun intervals(f: Intervals): WatchPart? {
        val rounds = f.rounds!!
        val work = f.workSec!!
        lines += if (f.restSec > 0) "$rounds x ${DisplayText.clock(work)} on / ${DisplayText.clock(f.restSec)} off"
        else "$rounds x ${DisplayText.clock(work)}"
        val program = expander.program(part.body)
        program.buyIn.forEach { addItemLines(it, "then ${innerLabel(f)} rest of interval:") }
        if (program.buyIn.isEmpty() && f.inner != com.wodcapture.domain.model.InnerMode.NONE) lines += "${innerLabel(f)} each interval:"
        program.rounds.firstOrNull()?.steps?.forEach { s -> stepLine(s) }
        program.cashOut.forEach { addItemLines(it, null, prefix = "Cash-out: ") }
        val mv = programItems(program).map { mvLine(it) ?: return null }
        val seg = mutableListOf<WatchSegment>()
        repeat(rounds) { r ->
            seg += WatchSegment(k = "work", d = work, lb = "WORK", ix = r)
            if (f.restSec > 0 && (r < rounds - 1 || f.finalRest)) seg += WatchSegment(k = "rest", d = f.restSec, lb = "REST", ix = r, ic = true)
        }
        return part(mode = "intervals", seg = seg, trk = "round", mv = mv, rnd = rounds, ld = loadItems())
    }

    private fun maxEffort(f: MaxEffort): WatchPart? {
        lines += f.windowSec?.let { "Max ${f.measure} in ${DisplayText.clock(it)}" } ?: "Max ${f.measure}: ${f.attempts} attempts"
        val items = BodyTree.items(part.body)
        items.forEach { addItemLines(it, null) }
        val seg = listOf(
            f.windowSec?.let { WatchSegment(k = "work", d = it, lb = "MAX", ix = 0) } ?: WatchSegment(k = "open", lb = "MAX", ix = 0),
        )
        return part(mode = "max_effort", seg = seg, mv = items.map { mvLine(it) ?: return null }, ld = loadItems())
    }

    // ------------------------------------------------------------------ strength

    private fun strength(): WatchPart? {
        val items = BodyTree.items(part.body).filter { it.sets.isNotEmpty() }
        val refs = (part.format as StrengthSets).setRefs
        val ordered: List<Pair<Item, StrengthSet>> = if (refs.isEmpty()) items.flatMap { i -> i.sets.map { i to it } }
        else refs.mapNotNull { r -> items.firstNotNullOfOrNull { i -> i.sets.firstOrNull { it.id == r }?.let { i to it } } }
        items.forEach(::strengthLines)
        if (ordered.size > WatchLimits.SETS) return fail("too_many_sets", "${ordered.size} sets; the watch takes at most ${WatchLimits.SETS}")
        val sets = ordered.mapIndexed { idx, (item, set) -> watchSet(item, set, isLast = idx == ordered.lastIndex) ?: return null }
        return part(mode = "strength_sets", sets = sets, nameFallback = items.firstOrNull()?.let { strengthName(it) })
    }

    private fun buildToMax(f: BuildToMax): WatchPart? {
        val item = BodyTree.items(part.body).firstOrNull() ?: return fail("empty_body", "Build-to-max needs a movement")
        lines += "Build to heavy ${f.repsPerSet}RM"
        lines += setNameOrNull(item) ?: return null
        f.windowSec?.let { lines += "Window ${DisplayText.clock(it)}" }
        val unit = profile.unitFor(implementOf(item))
        val set = WatchSet(
            id = item.sets.firstOrNull()?.id ?: item.id,
            nm = setNameOrNull(item) ?: return null,
            mv = item.movementId,
            r = f.repsPerSet,
            rx = f.repsPerSet.toString(),
            w = WatchLoad(v = null, u = MassMath.unitLabel(unit), c = item.count),
            ty = "top",
        )
        return part(mode = "build_to_max", sets = listOf(set), att = f.attemptLimit ?: DEFAULT_ATTEMPTS)
    }

    private fun watchSet(item: Item, set: StrengthSet, isLast: Boolean): WatchSet? {
        val ctx = LoadContext(item.movementId, implementOf(item), item.count)
        val resolved = resolver.resolve(set.load ?: item.load, ctx)
        val unit = profile.unitFor(implementOf(item))
        var note: String? = null
        var load: WatchLoad? = null
        var bw: Boolean? = null
        when (resolved) {
            is ResolvedLoad.Resolved -> {
                load = WatchLoad(MassMath.clean(resolved.mass.value), MassMath.unitLabel(resolved.mass.unit), resolved.count)
                note = resolved.note
            }
            is ResolvedLoad.Manual -> {
                load = WatchLoad(null, MassMath.unitLabel(resolved.entryUnit), resolved.count)
                note = resolved.note
            }
            ResolvedLoad.Bodyweight -> bw = true
            ResolvedLoad.Unloaded -> if (catalog.byId(item.movementId)?.loadable == "N") bw = true
            else load = WatchLoad(null, MassMath.unitLabel(unit), item.count)
            is ResolvedLoad.NeedsChoice -> {
                errors += CompileError(resolved.reason, "Choose the load for ${item.name} in review", part.id, item.id)
                return null
            }
        }
        val extra = listOfNotNull(set.tempo?.let { "Tempo $it" }, set.rpe?.let { "@RPE${MassMath.format(it)}" })
        val nt = (listOfNotNull(note) + extra).joinToString(" ").ifBlank { null }
        if (nt != null && nt.length > WatchLimits.LINE) {
            errors += CompileError("line_too_long", "Set note '$nt' exceeds ${WatchLimits.LINE} characters", part.id, set.id)
            return null
        }
        return WatchSet(
            id = set.id,
            nm = setNameOrNull(item) ?: return null,
            mv = item.movementId,
            r = set.reps.exact,
            rx = repText(set),
            w = load,
            bw = bw,
            rest = if (isLast) 0 else (set.restSec ?: profile.defaultRestSec),
            ty = set.setType.wire,
            nt = nt,
        )
    }

    private fun repText(set: StrengthSet): String {
        val r = set.reps
        val base = when {
            r.max -> "max"
            r.upTo != null -> "${r.value}-${r.upTo}"
            else -> r.value.toString()
        }
        return if (r.perSide && base.length + 2 <= WatchLimits.REP_TEXT) "$base/s" else base
    }

    private fun strengthName(item: Item): String {
        val sets = item.sets
        val reps = sets.map { repText(it) }
        val scheme = if (reps.distinct().size == 1) "${sets.size}x${reps[0]}" else reps.joinToString("-")
        return "${displayName(item)} $scheme"
    }

    /** Summary, shared rest and tempo lines of one strength movement. */
    private fun strengthLines(item: Item) {
        addWrapped(strengthSummaryLine(item))
        val rests = item.sets.mapNotNull { it.restSec }.distinct()
        if (rests.size == 1 && rests[0] > 0) lines += "Rest ${DisplayText.clock(rests[0])}"
        item.sets.firstNotNullOfOrNull { it.tempo }?.let { lines += "Tempo $it" }
    }

    private fun strengthSummaryLine(item: Item): String {
        val load = when (val l = item.sets.firstOrNull()?.load ?: item.load) {
            is PercentOfMax -> " @${MassMath.format(l.selectedPercent ?: l.percent)}%"
            is PercentOfBodyweight -> " @${MassMath.format(l.percent / 100)}xBW"
            is RpeLoad -> ""
            else -> ""
        }
        val rpe = item.sets.firstNotNullOfOrNull { it.rpe }?.let { " @RPE${MassMath.format(it)}" }.orEmpty()
        return strengthName(item) + load + rpe
    }

    private fun setNameOrNull(item: Item): String? {
        val candidates = listOfNotNull(item.displayName, item.name, catalog.shortName(item.movementId))
        return candidates.firstOrNull { it.length <= WatchLimits.SET_NAME } ?: run {
            errors += CompileError("line_too_long", "Name '${item.name}' exceeds ${WatchLimits.SET_NAME} characters; shorten it in review", part.id, item.id)
            null
        }
    }

    // ------------------------------------------------------------------ fallback (REQ-DM18)

    private fun compileFallback(): WatchPart? {
        val fb = part.fallback!!
        val program = expander.program(part.body)
        when (val f = part.format) {
            is Emom -> {
                lines += emomTitle(f)
                BodyTree.items(part.body).forEach { addItemLines(it, null) }
            }
            is StrengthSets -> BodyTree.items(part.body).forEach(::strengthLines)
            else -> {
                headerLines(f, program)
                bodyLines(program)
            }
        }
        part.hints?.partner?.let { p ->
            p.notes?.let { addWrapped(it) }
        }
        val cap = fb.capSec ?: (part.format as? ForTime)?.capSec
        val seg = when (fb.timer) {
            FallbackTimer.COUNT_DOWN -> {
                val d = fb.durationSec ?: formatDuration(part.format)
                    ?: return fail("missing_duration", "Countdown fallback needs a duration")
                listOf(WatchSegment(k = "work", d = d, lb = "TIMER", ix = 0))
            }
            FallbackTimer.COUNT_UP -> listOf(WatchSegment(k = "open", cap = cap, lb = "TIMER", ix = 0))
        }
        val name = (part.format as? StrengthSets)?.let { BodyTree.items(part.body).firstOrNull { it.sets.isNotEmpty() }?.let(::strengthName) }
        return part(mode = "manual", seg = seg, ld = loadItems(), cap = cap?.let { "text" }, nameFallback = name)
    }

    /** Natural countdown of a format, used as the default fallback countdown. */
    private fun formatDuration(f: Format): Int? = when (f) {
        is Amrap -> f.durationSec
        is Emom -> if (f.intervalSec != null && f.intervalCount != null) f.intervalSec * f.intervalCount else null
        is Intervals -> f.rounds?.let { r -> f.workSec?.let { w -> r * w + (r - 1) * f.restSec } }
        is MaxEffort -> f.windowSec
        else -> null
    }

    // ------------------------------------------------------------------ text rendering

    private fun headerLines(f: Format, program: RoundProgram) {
        val scheme = program.group?.repScheme.orEmpty()
        val rounds = program.group?.let { expander.roundCount(it) } ?: 1
        when (f) {
            is ForTime -> {
                val cap = f.capSec?.let { ", cap ${DisplayText.clock(it)}" }.orEmpty()
                val head = when {
                    f.eachRoundForTime -> "$rounds rounds, each for time"
                    scheme.isEmpty() && rounds > 1 -> "$rounds rounds for time"
                    else -> "For time"
                }
                addWrapped(head + cap)
                part.hints?.partner?.let { addWrapped("Teams of ${it.teamSize}") }
                if (scheme.isNotEmpty()) addWrapped("${DisplayText.scheme(scheme)} reps of:")
            }
            is Amrap -> lines += "AMRAP ${DisplayText.clock(f.durationSec ?: 0)}"
            else -> Unit
        }
    }

    private fun bodyLines(program: RoundProgram) {
        program.buyIn.forEach { addItemLines(it, null, prefix = if (it.role == ItemRole.BUY_IN) "Buy-in: " else "") }
        val group = program.group
        if (group == null) {
            nodeLines(part.body.filter { !(it is Item && it.role != ItemRole.MAIN) })
        } else {
            nodeLines(group.children)
        }
        program.cashOut.forEach { addItemLines(it, null, prefix = if (it.role == ItemRole.CASH_OUT) "Cash-out: " else "") }
    }

    private fun nodeLines(nodes: List<BodyNode>) {
        nodes.forEach { n ->
            when (n) {
                is Item -> addItemLines(n, null)
                is Rest -> stepLine(Step.Pause(n))
                is Group -> {
                    val r = expander.roundCount(n)
                    if (r > 1 || n.repScheme.isNotEmpty()) addWrapped(n.label ?: "$r rounds of:")
                    nodeLines(n.children)
                }
            }
        }
    }

    private fun stepLine(s: Step) {
        when (s) {
            is Step.Work -> addItemLines(s.item, null)
            is Step.Pause -> {
                val clock = if (s.rest.inClock) " (clock runs)" else ""
                val between = if (s.rest.betweenOnly) " between" else ""
                addWrapped("Rest ${DisplayText.clock(s.rest.sec)}$between$clock")
            }
        }
    }

    private fun addItemLines(item: Item, after: String?, prefix: String = "") {
        val resolved = resolveMetcon(item)
        val base = itemText(item, resolved, withQty = true, workSec = null) ?: return
        val orig = originalSuffix(resolved)
        val notes = if (fallback) listOfNotNull(item.notes).joinToString { " ($it)" } else ""
        val full = prefix + base + orig + notes
        if (orig.isNotEmpty() && (prefix + base + orig).length <= WatchLimits.LINE) addWrapped(full)
        else addWrapped(prefix + base + notes)
        after?.let { addWrapped(it) }
    }

    /** "(Rx 95 lb)" when the load shown differs from what the board says (REQ-AE04). */
    private fun originalSuffix(r: ResolvedLoad?): String {
        val res = r as? ResolvedLoad.Resolved ?: return ""
        if (res.method == ResolutionMethod.AS_WRITTEN || res.original == null) return ""
        return " (${part.chosenLevel ?: BASE_LEVEL} ${MassMath.formatMass(res.original)})"
    }

    private fun addWrapped(text: String) {
        val wrapped = DisplayText.wrap(text)
        if (wrapped == null) errors += CompileError("line_too_long", "'$text' cannot fit ${WatchLimits.LINE} characters; shorten it in review", part.id)
        else lines += wrapped
    }

    /** Single-line movement text for `mv`; shortens via catalog alias, never truncates. */
    private fun mvLine(item: Item, workSec: Int? = null): String? {
        val text = itemText(item, resolveMetcon(item), withQty = true, workSec = workSec) ?: return null
        if (text.length <= WatchLimits.LINE) return text
        errors += CompileError("line_too_long", "'$text' exceeds ${WatchLimits.LINE} characters; shorten it in review", part.id, item.id)
        return null
    }

    private fun itemText(item: Item, resolved: ResolvedLoad?, withQty: Boolean, workSec: Int?): String? {
        val qty = if (withQty) quantity(item, workSec) else ""
        val load = when (resolved) {
            is ResolvedLoad.Resolved -> " " + MassMath.formatMass(resolved.mass) + if (resolved.count > 1) " x${resolved.count}" else ""
            // Athlete-choice loads keep the board's word ("build") visible; others are entered later.
            is ResolvedLoad.Manual -> if (resolved.reason == "athlete_choice" && resolved.note != null) " (${resolved.note})" else ""
            is ResolvedLoad.NeedsChoice -> {
                errors += CompileError(resolved.reason, "Choose the load for ${item.name} in review", part.id, item.id)
                return null
            }
            else -> ""
        }
        val height = (item.height ?: item.target)?.let { " " + lengthText(it) }.orEmpty()
        val progression = item.progression?.let { " +${it.repsPerRound}/rd" }.orEmpty()
        val side = if (item.side == Side.EACH) "/side" else ""
        val mods = if (fallback && item.modifiers.isNotEmpty()) item.modifiers.joinToString(" ", postfix = " ") else ""
        val names = listOfNotNull(item.displayName, item.name, catalog.shortName(item.movementId)).distinct()
        val candidates = names.map { n -> "$qty$side$mods$n$load$height$progression".trim() }
        return DisplayText.firstFitting(candidates) ?: candidates.first()
    }

    private fun quantity(item: Item, workSec: Int?): String {
        val m = item.measure
        val v = when {
            m == null -> null
            part.hints?.partner != null && m.female != null -> "${MassMath.format(m.value ?: 0.0)}/${MassMath.format(m.female)}"
            else -> (if (sex == SexSelection.FEMALE && m.female != null) m.female else m.value)?.let { MassMath.format(it) }
        }
        val window = workSec?.let { DisplayText.clock(it) + " " }.orEmpty()
        return window + when (m?.kind) {
            MeasureKind.REPS -> "$v "
            MeasureKind.CALORIES -> "$v cal "
            MeasureKind.DISTANCE -> "$v ${m.unit?.wire ?: ""} "
            MeasureKind.DURATION -> "${DisplayText.clock(m.value?.toInt() ?: 0)} "
            MeasureKind.MAX -> "Max "
            MeasureKind.REP_SCHEME, null -> ""
        }
    }

    private fun lengthText(s: SplitLength): String {
        val l = if (sex == SexSelection.FEMALE && s.female != null) s.female else s.male
        return "${MassMath.format(l.value)} ${l.unit.wire}"
    }

    private fun displayName(item: Item): String = item.displayName ?: item.name

    private fun implementOf(item: Item): String? = item.implement ?: catalog.byId(item.movementId)?.implement

    private fun resolveMetcon(item: Item): ResolvedLoad? =
        if (item.load == null) null else resolver.resolve(item.load, LoadContext(item.movementId, implementOf(item), item.count))

    // ------------------------------------------------------------------ assembly

    private fun programItems(program: RoundProgram): List<Item> {
        val inGroup = program.group?.let { BodyTree.items(listOf(it)) } ?: BodyTree.items(part.body).filter { it.role == ItemRole.MAIN }
        return (program.buyIn + inGroup + program.cashOut).distinctBy { it.id }
    }

    /** Per-round rep totals when every main item has a rep-based counting rule (REQ-DM24). */
    private fun repTotals(program: RoundProgram, amrap: Boolean): List<Int>? {
        if (part.scoring.manual) return null
        if (program.buyIn.isNotEmpty() || program.cashOut.isNotEmpty()) return null
        val totals = program.rounds.map { it.repTotal ?: return null }
        return if (amrap && program.group == null && program.rounds.size == 1) totals else totals
    }

    private fun loadItems(): List<WatchLoadItem>? {
        val items = BodyTree.items(part.body).filter { it.sets.isEmpty() && it.load != null }
        val out = items.mapNotNull { item ->
            val nm = setNameOrNull(item) ?: return null
            when (val r = resolveMetcon(item)) {
                is ResolvedLoad.Resolved -> WatchLoadItem(item.id, nm, MassMath.clean(r.mass.value), MassMath.unitLabel(r.mass.unit), r.count)
                is ResolvedLoad.Manual -> WatchLoadItem(item.id, nm, null, MassMath.unitLabel(r.entryUnit), r.count)
                else -> null
            }
        }
        if (out.size > WatchLimits.LOAD_ITEMS) {
            errors += CompileError("over_limit", "More than ${WatchLimits.LOAD_ITEMS} loaded items", part.id)
            return null
        }
        return out.ifEmpty { null }
    }

    private fun part(
        mode: String,
        seg: List<WatchSegment>? = null,
        trk: String? = null,
        mv: List<String>? = null,
        rr: List<Int>? = null,
        rrc: Boolean? = null,
        rl: List<String>? = null,
        rnd: Int? = null,
        sets: List<WatchSet>? = null,
        att: Int? = null,
        cap: String? = null,
        ld: List<WatchLoadItem>? = null,
        nameFallback: String? = null,
    ): WatchPart? {
        if (errors.isNotEmpty()) return null
        val levels = LevelApplier.levelNames(original)
        val type = part.scoring.type
        val score = WatchScore(
            ty = type.wire,
            rv = part.scoring.ruleVersion,
            cap = if (part.format is ForTime) cap else null,
            du = if (type == ScoreType.DISTANCE) part.scoring.distanceUnit?.wire else null,
            man = fallback || part.scoring.manual,
            agg = part.scoring.aggregation?.wire,
        )
        return WatchPart(
            id = part.id,
            l = label(part.label),
            n = summary(nameFallback) ?: return null,
            mode = mode,
            fb = fallback,
            ln = lines.toList(),
            seg = seg,
            trk = trk,
            mv = mv?.takeIf { it.isNotEmpty() },
            rr = rr,
            rrc = rrc,
            rl = rl,
            rnd = rnd,
            sets = sets,
            att = att,
            sc = score,
            ld = ld,
            ra = part.restAfterSec?.takeIf { it > 0 },
            lv = part.chosenLevel ?: BASE_LEVEL,
            lvs = levels.ifEmpty { null },
        )
    }

    /** "A" stays "A", "A1" stays, "WOD"/"Metcon" → "W"/"M" (protocol §4.1). */
    private fun label(raw: String): String {
        val t = raw.trim().trimEnd(')', '.', ':')
        if (t.isEmpty()) return "W"
        if (Regex("^[A-Z][0-9]?$").matches(t)) return t
        return t.first().uppercase()
    }

    private fun summary(nameFallback: String?): String? {
        val program = expander.program(part.body)
        val scheme = program.group?.repScheme.orEmpty()
        val rounds = program.group?.let { expander.roundCount(it) } ?: 1
        val title = part.title
        val base = when (val f = part.format) {
            is ForTime -> when {
                scheme.isNotEmpty() -> DisplayText.scheme(scheme)
                f.eachRoundForTime -> "$rounds rds each for time"
                rounds > 1 -> "$rounds RFT"
                else -> "For time"
            }
            is Amrap -> "AMRAP ${DisplayText.clock(f.durationSec ?: 0)}"
            is Emom -> emomTitle(f)
            is Tabata -> "Tabata x${f.rounds}"
            is Intervals -> "${f.rounds} x ${DisplayText.clock(f.workSec ?: 0)}"
            is MaxEffort -> "Max effort"
            is StrengthSets -> nameFallback ?: "Strength"
            is BuildToMax -> BodyTree.items(part.body).firstOrNull()?.let { "${displayName(it)} ${f.repsPerSet}RM" } ?: "Build to max"
            is DeathBy -> "Death by"
            NotForTime -> "For quality"
            is CustomSegments -> "Custom"
        }
        val candidates = buildList {
            if (title != null) {
                if (part.format is ForTime && base == "For time") add(title)
                add("$title $base")
            }
            add(base)
            if (title != null) add(title)
        }
        return DisplayText.firstFitting(candidates) ?: run {
            errors += CompileError("line_too_long", "Summary '${candidates.first()}' exceeds ${WatchLimits.LINE} characters", part.id)
            null
        }
    }

    private fun emomTitle(f: Emom): String {
        val iv = f.intervalSec ?: 60
        val n = f.intervalCount ?: 0
        return when {
            iv == 60 -> "EMOM $n"
            iv % 60 == 0 -> "E${iv / 60}MOM x$n"
            else -> "Every ${DisplayText.clock(iv)} x$n"
        }
    }

    private fun intervalLabel(i: Int, interval: Int): String = if (interval == 60) "MIN ${i + 1}" else "INT ${i + 1}"

    private fun innerLabel(f: Intervals): String = when (f.inner) {
        com.wodcapture.domain.model.InnerMode.AMRAP -> "AMRAP"
        com.wodcapture.domain.model.InnerMode.FOR_TIME -> "for time"
        com.wodcapture.domain.model.InnerMode.MAX_EFFORT -> "max"
        com.wodcapture.domain.model.InnerMode.NONE -> ""
    }

    private fun checkLimits(p: WatchPart): Boolean {
        val issues = mutableListOf<CompileError>()
        if (p.ln.size > WatchLimits.LINES) issues += CompileError("too_many_lines", "${p.ln.size} display lines; the watch shows at most ${WatchLimits.LINES}", p.id)
        (p.seg?.size ?: 0).let { if (it > WatchLimits.SEGMENTS) issues += CompileError("over_limit", "$it timer segments (max ${WatchLimits.SEGMENTS})", p.id) }
        (p.mv?.size ?: 0).let { if (it > WatchLimits.MOVEMENTS) issues += CompileError("over_limit", "$it movement lines (max ${WatchLimits.MOVEMENTS})", p.id) }
        p.rl?.firstOrNull { it.length > WatchLimits.ROUND_LABEL }?.let { issues += CompileError("line_too_long", "Round label '$it' too long", p.id) }
        p.lvs?.firstOrNull { it.length > WatchLimits.LEVEL }?.let { issues += CompileError("line_too_long", "Level name '$it' too long", p.id) }
        if (p.lvs != null && p.lvs.size > 6) issues += CompileError("over_limit", "More than 6 levels", p.id)
        errors += issues
        return issues.isEmpty()
    }

    companion object {
        /** Rounds compiled for a progressive AMRAP's per-round rep totals (protocol §4.1 `rr`). */
        const val PROGRESSIVE_ROUNDS = 30
        const val DEFAULT_ATTEMPTS = 10
    }
}
