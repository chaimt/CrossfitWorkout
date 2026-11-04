package com.wodcapture.app.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.wodcapture.app.data.WorkoutEntity
import com.wodcapture.domain.DomainJson
import com.wodcapture.domain.levels.BASE_LEVEL
import com.wodcapture.domain.levels.LevelApplier
import com.wodcapture.domain.model.Amrap
import com.wodcapture.domain.model.BodyNode
import com.wodcapture.domain.model.BuildToMax
import com.wodcapture.domain.model.Confidence
import com.wodcapture.domain.model.Emom
import com.wodcapture.domain.model.FallbackTimer
import com.wodcapture.domain.model.ForTime
import com.wodcapture.domain.model.Group
import com.wodcapture.domain.model.GroupKind
import com.wodcapture.domain.model.Intervals
import com.wodcapture.domain.model.Item
import com.wodcapture.domain.model.MaxEffort
import com.wodcapture.domain.model.Measure
import com.wodcapture.domain.model.MeasureKind
import com.wodcapture.domain.model.Part
import com.wodcapture.domain.model.PartKind
import com.wodcapture.domain.model.Rest
import com.wodcapture.domain.model.ScoreType
import com.wodcapture.domain.model.SexSelection
import com.wodcapture.domain.model.StrengthSets
import com.wodcapture.domain.model.Tabata
import com.wodcapture.domain.model.Workout
import com.wodcapture.domain.parser.JobState
import com.wodcapture.domain.plan.CompileResult
import com.wodcapture.domain.review.LoadText
import com.wodcapture.domain.review.ReviewActions
import com.wodcapture.domain.validation.Severity
import com.wodcapture.domain.validation.WorkoutValidator
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Review and edit (REQ-AE01–AE06): photos next to the parsed parts, low-confidence highlights,
 * warnings first, a structured editor, level choice, the exact watch preview, Send / Save.
 */
@Composable
fun ReviewScreen(vm: AppViewModel, id: String, back: () -> Unit, logResult: (String) -> Unit) {
    val entity by vm.observe(id).collectAsState(initial = null)
    val e = entity ?: run { Text("Loading…", Modifier.padding(16.dp)); return }
    ReviewContent(vm, e, back, logResult)
}

@Composable
private fun ReviewContent(vm: AppViewModel, e: WorkoutEntity, back: () -> Unit, logResult: (String) -> Unit) {
    var draft by remember(e.id, e.revision) { mutableStateOf(DomainJson.decodeWorkout(e.workoutJson)) }
    var levels by remember(e.id) { mutableStateOf(mapOf<String, String>()) }
    var sex by remember(e.id) { mutableStateOf<SexSelection?>(null) }
    val settings by vm.settings.collectAsState()
    val job by vm.observeJob(e.id).collectAsState(initial = null)
    val parse = remember(e.parseJson) { vm.parseResultOf(e) }
    val review = remember(draft, levels, sex, settings) { vm.review(draft, levels, sex) }
    val lowConfidence = parse?.diagnostics.orEmpty().filter { it.confidence != Confidence.HIGH }.mapNotNull { d -> d.nodeId?.let { it to d } }.toMap()
    val images = remember(e.imagePaths) { Json.decodeFromString(ListSerializer(String.serializer()), e.imagePaths).map(::File) }
    var zoom by remember { mutableStateOf<File?>(null) }

    Column(Modifier.fillMaxSize()) {
        Bar(draft.title.ifBlank { "Review" }, back)
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (images.isNotEmpty()) item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    images.forEach { f -> Thumbnail(f, Modifier.width(110.dp).height(110.dp).clickable { zoom = f }) }
                }
            }
            item { ParseStatus(vm, e, job, draft.parts.isEmpty()) }
            item {
                Field("Title", draft.title, Modifier.fillMaxWidth()) { draft = draft.copy(title = it) }
                Field("Date (YYYY-MM-DD)", draft.date.orEmpty(), Modifier.fillMaxWidth()) { draft = draft.copy(date = it.ifBlank { null }) }
            }
            item { Findings(review, parse) }
            if (settings.profile.sex == SexSelection.ALWAYS_ASK) item {
                Choice("Split loads for", sex ?: SexSelection.ALWAYS_ASK, listOf(SexSelection.MALE, SexSelection.FEMALE), { it.name.lowercase() }) { sex = it }
            }
            items(draft.parts, key = { it.id }) { part ->
                PartEditor(
                    vm, part, lowConfidence.keys, levels[part.id],
                    onLevel = { lv -> levels = levels + (part.id to lv) },
                    onChange = { updated -> draft = Editing.updatePart(draft, part.id) { updated } },
                    onRemove = { draft = draft.copy(parts = draft.parts.filter { it.id != part.id }) },
                )
            }
            item {
                OutlinedButton(onClick = {
                    val p = Part(id = vm.newId(), label = ('A' + draft.parts.size).toString(), format = ForTime(), body = listOf(Item(vm.newId(), name = "New movement", measure = Measure(MeasureKind.REPS, 10.0))))
                    draft = draft.copy(parts = draft.parts + p)
                }) { Text("Add part") }
            }
            item { Preview(review.compile) }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 16.dp)) {
                    Button(enabled = review.compile is CompileResult.Success, onClick = { vm.sendToWatch(e, draft, levels, sex) }) { Text("Send to watch") }
                    OutlinedButton(onClick = { vm.save(e, draft) }) { Text("Save only") }
                    // REQ-AC09: a session already done without the watch goes straight to manual entry (REQ-AL03).
                    OutlinedButton(enabled = draft.parts.isNotEmpty(), onClick = { vm.saveAndLog(e, draft, logResult) }) { Text("Save & log result") }
                }
            }
        }
    }
    zoom?.let { f -> AlertDialog(onDismissRequest = { zoom = null }, confirmButton = { TextButton(onClick = { zoom = null }) { Text("Close") } }, text = { Thumbnail(f, Modifier.fillMaxWidth().height(480.dp), sample = 2) }) }
}

@Composable
private fun Thumbnail(f: File, modifier: Modifier, sample: Int = 8) {
    val bmp = remember(f, sample) { BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }) }
    if (bmp != null) Image(bmp.asImageBitmap(), contentDescription = "Whiteboard photo", modifier = modifier)
}

@Composable
private fun ParseStatus(vm: AppViewModel, e: WorkoutEntity, job: com.wodcapture.app.data.ParseJobEntity?, empty: Boolean) {
    Column {
        when (job?.state) {
            JobState.QUEUED.name, JobState.RUNNING.name -> {
                Text(if (job.state == JobState.QUEUED.name) "Parse queued (waits for a connection)…" else "Parsing…")
                LinearProgressIndicator(Modifier.fillMaxWidth())
                TextButton(onClick = { vm.cancelParse(job) }) { Text("Cancel") }
            }
            JobState.FAILED.name -> {
                Text("Parse failed: ${job.error}", color = MaterialTheme.colorScheme.error)
                // Manual Retry creates a new job (REQ-AX18).
                TextButton(onClick = { vm.parse(e.id) }) { Text("Retry") }
            }
            else -> if (empty) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.parse(e.id) }) { Text("Parse") }
                Text("or add parts manually below", Modifier.padding(8.dp))
            }
        }
        if (e.alternativeJson != null) {
            Text("A late parse result is available (you edited the draft meanwhile).")
            TextButton(onClick = { vm.applyAlternative(e) }) { Text("Use late parse") }
        }
        e.rawText?.let { Text("Source text: ${it.take(200)}", style = MaterialTheme.typography.bodySmall) }
    }
}

/** Warnings and assumptions shown before confirmation (REQ-AX05, AX15). */
@Composable
private fun Findings(review: ReviewState, parse: com.wodcapture.domain.model.ParseResult?) {
    Column {
        review.report.issues.sortedBy { it.severity }.forEach { i ->
            Text(
                (if (i.severity == Severity.BLOCKING) "Blocking: " else "Warning: ") + i.message,
                color = if (i.severity == Severity.BLOCKING) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        val assumptions = parse?.assumptions.orEmpty()
        if (assumptions.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            assumptions.forEach { a -> AssistChip(onClick = {}, label = { Text(a.token?.let { t -> "$t: ${a.text}" } ?: a.text) }) }
        }
        parse?.unparsed.orEmpty().forEach { Text("Unparsed: “${it.text}”", style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun PartEditor(
    vm: AppViewModel,
    part: Part,
    low: Set<String>,
    level: String?,
    onLevel: (String) -> Unit,
    onChange: (Part) -> Unit,
    onRemove: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row {
                Field("Label", part.label, Modifier.width(90.dp)) { onChange(part.copy(label = it)) }
                Field("Name", part.title.orEmpty(), Modifier.weight(1f).padding(start = 8.dp)) { onChange(part.copy(title = it.ifBlank { null })) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice("Kind", part.kind, PartKind.entries, { it.name.lowercase() }) { onChange(part.copy(kind = it)) }
                Choice("Format", Editing.formatType(part.format), Editing.formatTypes) { t ->
                    onChange(part.copy(format = Editing.newFormat(t), scoring = part.scoring.copy(type = Editing.defaultScore(t))))
                }
                Choice("Score", part.scoring.type, ScoreType.entries, { it.name.lowercase() }) { onChange(part.copy(scoring = part.scoring.copy(type = it))) }
            }
            Toggle("Run on watch", part.effectiveRunOnWatch) { onChange(part.copy(runOnWatch = it)) }
            Toggle("Manual score", part.scoring.manual) { onChange(part.copy(scoring = part.scoring.copy(manual = it))) }
            FormatEditor(part) { onChange(part.copy(format = it)) }
            SectionTitle("Movements")
            BodyEditor(vm, part, part.body, low, depth = 0, onChange)
            OutlinedButton(onClick = { onChange(Editing.addItem(part, null, Item(vm.newId(), name = "New movement", measure = Measure(MeasureKind.REPS, 10.0)))) }) { Text("Add movement") }
            val names = LevelApplier.levelNames(part)
            if (names.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Level:", Modifier.padding(8.dp))
                names.forEach { n -> FilterChip(selected = (level ?: part.chosenLevel ?: BASE_LEVEL) == n, onClick = { onLevel(n) }, label = { Text(n) }) }
            }
            val reason = WorkoutValidator.requiresFallback(part)
            if (reason != null && part.fallback?.accepted != true) {
                Text("$reason has no dedicated watch mode in this version.", color = MaterialTheme.colorScheme.error)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onChange(acceptFallback(part, FallbackTimer.COUNT_UP)) }) { Text("Use count-up timer + manual score") }
                    OutlinedButton(onClick = { onChange(acceptFallback(part, FallbackTimer.COUNT_DOWN)) }) { Text("Use countdown + manual score") }
                }
            }
            if (part.fallback?.accepted == true) Text("Manual timer: no automatic movement/interrupt/rest cues", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onChange(confirmSplits(part)) }) { Text("Confirm M/F splits") }
                TextButton(onClick = onRemove) { Text("Remove part") }
            }
        }
    }
}

private fun acceptFallback(p: Part, timer: FallbackTimer): Part {
    val w = Workout(id = "x", parts = listOf(p))
    return ReviewActions.acceptFallback(w, p.id, timer).parts[0]
}

private fun confirmSplits(p: Part): Part = ReviewActions.confirmSplits(Workout(id = "x", parts = listOf(p))).parts[0]

@Composable
private fun FormatEditor(part: Part, onChange: (com.wodcapture.domain.model.Format) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val m = Modifier.width(150.dp)
        when (val f = part.format) {
            is ForTime -> {
                TimeField("Cap", f.capSec, m) { onChange(f.copy(capSec = it)) }
                Toggle("Each round for time", f.eachRoundForTime) { onChange(f.copy(eachRoundForTime = it)) }
            }
            is Amrap -> TimeField("Duration", f.durationSec, m) { onChange(f.copy(durationSec = it)) }
            is Emom -> {
                TimeField("Interval", f.intervalSec, m) { onChange(f.copy(intervalSec = it)) }
                IntField("Intervals", f.intervalCount, m) { onChange(f.copy(intervalCount = it)) }
            }
            is Tabata -> {
                TimeField("Work", f.workSec, m) { onChange(f.copy(workSec = it ?: 20)) }
                TimeField("Rest", f.restSec, m) { onChange(f.copy(restSec = it ?: 10)) }
                IntField("Rounds", f.rounds, m) { onChange(f.copy(rounds = it ?: 8)) }
            }
            is Intervals -> {
                IntField("Rounds", f.rounds, m) { onChange(f.copy(rounds = it)) }
                TimeField("Work", f.workSec, m) { onChange(f.copy(workSec = it)) }
                TimeField("Rest", f.restSec, m) { onChange(f.copy(restSec = it ?: 0)) }
                Toggle("Final rest", f.finalRest) { onChange(f.copy(finalRest = it)) }
            }
            is MaxEffort -> TimeField("Window", f.windowSec, m) { onChange(f.copy(windowSec = it)) }
            is BuildToMax -> {
                IntField("Reps per attempt", f.repsPerSet, m) { onChange(f.copy(repsPerSet = it ?: 1)) }
                TimeField("Window", f.windowSec, m) { onChange(f.copy(windowSec = it)) }
            }
            is StrengthSets -> Text("Sets are edited per movement below")
            else -> Text("Format edited as text in notes (v1)")
        }
    }
}

/** Recursive body editor: items, groups and rests (REQ-AE02); never flattens nesting. */
@Composable
private fun BodyEditor(vm: AppViewModel, part: Part, nodes: List<BodyNode>, low: Set<String>, depth: Int, onChange: (Part) -> Unit) {
    nodes.forEach { n ->
        val bg = if (n.id in low) MaterialTheme.colorScheme.tertiaryContainer else Color.Transparent
        Column(Modifier.fillMaxWidth().background(bg).padding(start = (depth * 12).dp, top = 4.dp, bottom = 4.dp)) {
            when (n) {
                is Item -> ItemEditor(vm, part, n, onChange)
                is Group -> {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Choice("Group", n.kind, GroupKind.entries, { it.name.lowercase() }) { k -> onChange(Editing.updateNode(part, n.id) { (it as Group).copy(kind = k) }) }
                        IntField("Repeat", n.repeat, Modifier.width(110.dp)) { r -> onChange(Editing.updateNode(part, n.id) { (it as Group).copy(repeat = r ?: 1) }) }
                        Field("Rep scheme", n.repScheme.joinToString("-"), Modifier.width(160.dp)) { s ->
                            val scheme = s.split('-', ' ', ',').mapNotNull { it.trim().toIntOrNull() }
                            onChange(Editing.updateNode(part, n.id) { (it as Group).copy(repScheme = scheme) })
                        }
                    }
                    BodyEditor(vm, part, n.children, low, depth + 1, onChange)
                    TextButton(onClick = { onChange(Editing.addItem(part, n.id, Item(vm.newId(), name = "New movement", measure = Measure(MeasureKind.REP_SCHEME)))) }) { Text("Add to group") }
                }
                is Rest -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TimeField("Rest", n.sec, Modifier.width(150.dp)) { s -> onChange(Editing.updateNode(part, n.id) { (it as Rest).copy(sec = s ?: 0) }) }
                    Toggle("On the clock", n.inClock) { c -> onChange(Editing.updateNode(part, n.id) { (it as Rest).copy(inClock = c) }) }
                }
            }
            if (depth == 0) Row {
                TextButton(onClick = { onChange(Editing.move(part, n.id, -1)) }) { Text("Up") }
                TextButton(onClick = { onChange(Editing.move(part, n.id, 1)) }) { Text("Down") }
                TextButton(onClick = { onChange(Editing.removeNode(part, n.id)) }) { Text("Delete") }
            } else TextButton(onClick = { onChange(Editing.removeNode(part, n.id)) }) { Text("Delete") }
        }
    }
}

@Composable
private fun ItemEditor(vm: AppViewModel, part: Part, item: Item, onChange: (Part) -> Unit) {
    fun set(f: (Item) -> Item) = onChange(Editing.updateNode(part, item.id) { f(it as Item) })
    val catalog = vm.catalog
    Column {
        Row {
            Field("Movement", item.name, Modifier.weight(1f)) { v -> set { it.copy(name = v, displayName = null) } }
            Text(item.movementId ?: "free text", Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall)
        }
        // Catalog autocomplete (REQ-AE02): names and unambiguous aliases.
        val suggestions = remember(item.name) {
            if (item.name.length < 2) emptyList() else catalog.movements.filter { m ->
                m.id != item.movementId && (m.name.contains(item.name, true) || m.aliases.any { it.equals(item.name, true) })
            }.take(3)
        }
        if (suggestions.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            suggestions.forEach { m -> AssistChip(onClick = { set { it.copy(movementId = m.id, name = m.name, implement = it.implement ?: m.implement) } }, label = { Text(m.name) }) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val measure = item.measure ?: Measure(MeasureKind.REPS)
            Choice("Measure", measure.kind, MeasureKind.entries, { it.name.lowercase() }) { k -> set { it.copy(measure = measure.copy(kind = k)) } }
            if (measure.kind !in setOf(MeasureKind.REP_SCHEME, MeasureKind.MAX)) {
                DoubleField("Value", measure.value, Modifier.width(110.dp)) { v -> set { it.copy(measure = measure.copy(value = v)) } }
                DoubleField("Female", measure.female, Modifier.width(110.dp)) { v -> set { it.copy(measure = measure.copy(female = v)) } }
            }
            var loadText by remember(item.id) { mutableStateOf(LoadText.format(item.load)) }
            Field("Load (95/65 lb, 43 kg, 80%)", loadText, Modifier.width(220.dp)) { t -> loadText = t; set { it.copy(load = LoadText.parse(t)) } }
            IntField("Implements", item.count, Modifier.width(110.dp)) { n -> set { it.copy(count = n ?: 1) } }
        }
        if (part.format is StrengthSets || item.sets.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IntField("Sets", item.sets.size, Modifier.width(90.dp)) { n -> set { Editing.reshapeSets(it, n ?: 0, null, null, vm::newId) } }
            IntField("Reps", item.sets.firstOrNull()?.reps?.value, Modifier.width(90.dp)) { r -> set { Editing.reshapeSets(it, it.sets.size, r, null, vm::newId) } }
            TimeField("Rest", item.sets.firstOrNull()?.restSec, Modifier.width(150.dp)) { s -> set { Editing.reshapeSets(it, it.sets.size, null, s, vm::newId) } }
        }
    }
}

/** Exactly what the watch will display (REQ-AE04, REQ-DM18 preview of every line). */
@Composable
private fun Preview(r: CompileResult) {
    SectionTitle("Watch preview")
    when (r) {
        is CompileResult.Success -> {
            Text("${r.encodedBytes} / 3584 bytes", style = MaterialTheme.typography.bodySmall)
            r.plan.p.forEach { p ->
                Column(Modifier.padding(vertical = 4.dp)) {
                    Text("${p.l} · ${p.n}${if (p.fb) " (manual timer)" else ""}", style = MaterialTheme.typography.titleSmall)
                    p.ln.forEach { Text(it, fontFamily = FontFamily.Monospace) }
                    p.sets?.forEach { s ->
                        val w = s.w
                        val load = w?.v?.let { v -> "${com.wodcapture.domain.load.MassMath.format(v)} ${w.u}" } ?: if (s.bw == true) "BW" else "enter load"
                        Text("  ${s.nm} ${s.rx} $load ${s.nt.orEmpty()}", fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
        is CompileResult.Failure -> r.errors.forEach { Text("Cannot send: ${it.message}", color = MaterialTheme.colorScheme.error) }
    }
}
