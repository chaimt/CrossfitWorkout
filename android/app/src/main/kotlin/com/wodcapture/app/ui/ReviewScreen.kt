package com.wodcapture.app.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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
import com.wodcapture.domain.model.Scoring
import com.wodcapture.domain.model.SexSelection
import com.wodcapture.domain.model.StrengthSets
import com.wodcapture.domain.model.Tabata
import com.wodcapture.domain.model.Workout
import com.wodcapture.domain.model.WorkoutSection
import com.wodcapture.domain.parser.JobState
import com.wodcapture.domain.plan.CompileResult
import com.wodcapture.domain.review.LoadText
import com.wodcapture.domain.review.ReviewActions
import com.wodcapture.domain.validation.WorkoutValidator
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Review and edit (REQ-AE01–AE06): photos next to the parsed parts, low-confidence highlights,
 * warnings first, a structured editor, level choice, the exact watch preview, Send / Save.
 * Parts stay in class run order (warm-up → weights → metcon → trailing) under section headers.
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
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = Gutter, end = Gutter, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (images.isNotEmpty()) item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    images.forEach { f -> Thumbnail(f, Modifier.size(120.dp).clip(MaterialTheme.shapes.medium).clickable { zoom = f }) }
                }
            }
            item { ParseStatus(vm, e, job, draft.parts.isEmpty()) }
            item {
                Panel {
                    Field("Title", draft.title, Modifier.fillMaxWidth()) { draft = draft.copy(title = it) }
                    DateField(draft.date.orEmpty(), Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        draft = draft.copy(date = it.ifBlank { null })
                    }
                    if (settings.profile.sex == SexSelection.ALWAYS_ASK) {
                        Choice("Split loads for", sex ?: SexSelection.ALWAYS_ASK, listOf(SexSelection.MALE, SexSelection.FEMALE), { it.name.lowercase() }, Modifier.fillMaxWidth().padding(top = 8.dp)) { sex = it }
                    }
                }
            }
            item { Findings(review, parse) }
            itemsIndexed(draft.parts, key = { _, p -> p.id }) { i, part ->
                val section = WorkoutSection.of(part)
                if (i == 0 || WorkoutSection.of(draft.parts[i - 1]) != section) SectionHeader(section)
                PartEditor(
                    vm, part, section, lowConfidence.keys, levels[part.id],
                    onLevel = { lv -> levels = levels + (part.id to lv) },
                    onChange = { updated -> draft = Editing.updatePart(draft, part.id) { updated } },
                    onRemove = { draft = draft.copy(parts = draft.parts.filter { it.id != part.id }) },
                )
            }
            item {
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AddSectionButton(WorkoutSection.WARMUP, Modifier.weight(1f)) { draft = Editing.insertPart(draft, newWarmupPart(vm)) }
                    AddSectionButton(WorkoutSection.WEIGHTS, Modifier.weight(1f)) { draft = Editing.insertPart(draft, newWeightsPart(vm)) }
                }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AddSectionButton(WorkoutSection.METCON, Modifier.weight(1f)) { draft = Editing.insertPart(draft, newMetconPart(vm)) }
                    AddSectionButton(WorkoutSection.OTHER, Modifier.weight(1f)) { draft = Editing.insertPart(draft, newCooldownPart(vm)) }
                }
            }
            item { Preview(review.compile, draft.parts.associate { it.label.ifBlank { "W" } to WorkoutSection.of(it) }) }
        }
        ActionBar(top = {
            Button(enabled = review.compile is CompileResult.Success, onClick = { vm.sendToWatch(e, draft, levels, sex) }, modifier = Modifier.fillMaxWidth()) {
                Text("Send to watch")
            }
        }) {
            OutlinedButton(onClick = { vm.save(e, draft) }, modifier = Modifier.weight(1f)) { Text("Save only") }
            // REQ-AC09: a session already done without the watch goes straight to manual entry (REQ-AL03).
            OutlinedButton(enabled = draft.parts.isNotEmpty(), onClick = { vm.saveAndLog(e, draft, logResult) }, modifier = Modifier.weight(1f)) { Text("Save & log") }
        }
    }
    zoom?.let { f -> AlertDialog(onDismissRequest = { zoom = null }, confirmButton = { TextButton(onClick = { zoom = null }) { Text("Close") } }, text = { Thumbnail(f, Modifier.fillMaxWidth().height(480.dp), sample = 2) }) }
}

private fun newMetconPart(vm: AppViewModel) = Part(
    id = vm.newId(), format = ForTime(), scoring = Scoring(ScoreType.TIME),
    body = listOf(Item(vm.newId(), name = "New movement", measure = Measure(MeasureKind.REPS, 10.0))),
)

/** A 5 × 5 lift with 2:00 rest: the commonest strength prescription, edited from there. */
private fun newWeightsPart(vm: AppViewModel): Part {
    val lift = Editing.reshapeSets(Item(vm.newId(), name = "New lift"), count = 5, reps = 5, restSec = 120, newId = vm::newId)
    return Part(id = vm.newId(), kind = PartKind.STRENGTH, format = StrengthSets(), scoring = Scoring(ScoreType.LOAD), body = listOf(lift))
}

private fun newWarmupPart(vm: AppViewModel) = Part(
    id = vm.newId(), kind = PartKind.WARMUP, format = Amrap(durationSec = 600),
    scoring = Scoring(ScoreType.COMPLETION),
    body = listOf(Item(vm.newId(), name = "New warm-up movement", measure = Measure(MeasureKind.REPS, 10.0))),
)

private fun newCooldownPart(vm: AppViewModel) = Part(
    id = vm.newId(), kind = PartKind.COOLDOWN, format = Amrap(durationSec = 300),
    scoring = Scoring(ScoreType.COMPLETION),
    body = listOf(Item(vm.newId(), name = "New cool-down movement", measure = Measure(MeasureKind.REPS, 10.0))),
)

@Composable
private fun AddSectionButton(section: WorkoutSection, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = modifier, colors = ButtonDefaults.outlinedButtonColors(contentColor = sectionColors(section).accent)) {
        Icon(Icons.Filled.Add, contentDescription = null, Modifier.padding(end = 6.dp))
        Text("Add ${section.label.lowercase()}")
    }
}

@Composable
private fun Thumbnail(f: File, modifier: Modifier, sample: Int = 8) {
    val bmp = remember(f, sample) { BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }) }
    if (bmp != null) Image(bmp.asImageBitmap(), contentDescription = "Whiteboard photo", modifier = modifier, contentScale = ContentScale.Crop)
}

@Composable
private fun ParseStatus(vm: AppViewModel, e: WorkoutEntity, job: com.wodcapture.app.data.ParseJobEntity?, empty: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (job?.state) {
            JobState.QUEUED.name, JobState.RUNNING.name -> {
                Banner(if (job.state == JobState.QUEUED.name) "Parse queued: waits for a connection…" else "Reading the workout…", action = {
                    TextButton(onClick = { vm.cancelParse(job) }) { Text("Cancel") }
                })
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            // Manual Retry creates a new job (REQ-AX18).
            JobState.FAILED.name -> Banner("Parse failed: ${job.error}", error = true, action = { TextButton(onClick = { vm.parse(e.id) }) { Text("Retry") } })
            else -> if (empty) Banner("Nothing parsed yet. Parse it, or add the warm-up, weights and metcon below.", action = {
                Button(onClick = { vm.parse(e.id) }) { Text("Parse") }
            })
        }
        if (e.alternativeJson != null) {
            Banner("A late parse result is available (you edited the draft meanwhile).", action = { TextButton(onClick = { vm.applyAlternative(e) }) { Text("Use it") } })
        }
        e.rawText?.let { Hint("Source text: ${it.take(200)}") }
    }
}

/** Warnings and assumptions shown before confirmation (REQ-AX05, AX15). */
@Composable
private fun Findings(review: ReviewState, parse: com.wodcapture.domain.model.ParseResult?) {
    val assumptions = parse?.assumptions.orEmpty()
    val unparsed = parse?.unparsed.orEmpty()
    if (review.report.issues.isEmpty() && assumptions.isEmpty() && unparsed.isEmpty()) return
    Panel {
        Text("Check before sending", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 6.dp))
        Issues(review.report.issues, blockingPrefix = "Blocking", warningPrefix = "Warning")
        if (assumptions.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            assumptions.forEach { a -> AssistChip(onClick = {}, label = { Text(a.token?.let { t -> "$t: ${a.text}" } ?: a.text) }) }
        }
        unparsed.forEach { Hint("Unparsed: “${it.text}”") }
    }
}

private val formatNames = mapOf(
    "for_time" to "For time", "amrap" to "AMRAP", "emom" to "EMOM", "tabata" to "Tabata", "intervals" to "Intervals",
    "max_effort" to "Max effort", "strength_sets" to "Strength sets", "build_to_max" to "Build to max", "other" to "Other",
)

private fun pretty(name: String) = name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

@Composable
private fun PartEditor(
    vm: AppViewModel,
    part: Part,
    section: WorkoutSection,
    low: Set<String>,
    level: String?,
    onLevel: (String) -> Unit,
    onChange: (Part) -> Unit,
    onRemove: () -> Unit,
) {
    SectionCard(section) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LabelBadge(part.label, section)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(part.title ?: Summaries.format(part.format), style = MaterialTheme.typography.titleMedium)
                Hint(pretty(part.kind.name))
            }
            IconButton(onClick = onRemove) { Icon(Icons.Filled.Delete, contentDescription = "Remove part ${part.label}") }
        }
        Grid {
            val cell = Modifier
            Field("Label", part.label, cell) { onChange(part.copy(label = it)) }
            Field("Name", part.title.orEmpty(), cell) { onChange(part.copy(title = it.ifBlank { null })) }
            Choice("Kind", part.kind, PartKind.entries, { pretty(it.name) }, cell) { onChange(part.copy(kind = it)) }
            Choice("Format", Editing.formatType(part.format), Editing.formatTypes, { formatNames[it] ?: it }, cell) { t ->
                onChange(part.copy(format = Editing.newFormat(t), scoring = part.scoring.copy(type = Editing.defaultScore(t))))
            }
            Choice("Score", part.scoring.type, ScoreType.entries, { pretty(it.name) }, cell) { onChange(part.copy(scoring = part.scoring.copy(type = it))) }
        }
        FormatEditor(part) { onChange(part.copy(format = it)) }
        Toggle("Run on watch", part.effectiveRunOnWatch) { onChange(part.copy(runOnWatch = it)) }
        Toggle("Manual score", part.scoring.manual) { onChange(part.copy(scoring = part.scoring.copy(manual = it))) }
        Text(if (section == WorkoutSection.WEIGHTS) "Lifts" else "Movements", style = MaterialTheme.typography.titleSmall)
        BodyEditor(vm, part, part.body, low, depth = 0, onChange)
        TextButton(onClick = { onChange(Editing.addItem(part, null, Item(vm.newId(), name = "New movement", measure = Measure(MeasureKind.REPS, 10.0)))) }) {
            Icon(Icons.Filled.Add, contentDescription = null, Modifier.padding(end = 4.dp))
            Text("Add movement")
        }
        val names = LevelApplier.levelNames(part)
        if (names.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.Center) {
            Text("Level", Modifier.padding(top = 14.dp, end = 4.dp), style = MaterialTheme.typography.labelLarge)
            names.forEach { n -> FilterChip(selected = (level ?: part.chosenLevel ?: BASE_LEVEL) == n, onClick = { onLevel(n) }, label = { Text(n) }) }
        }
        val reason = WorkoutValidator.requiresFallback(part)
        if (reason != null && part.fallback?.accepted != true) {
            Banner("$reason has no dedicated watch mode in this version.", error = true)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onChange(acceptFallback(part, FallbackTimer.COUNT_UP)) }) { Text("Count-up + manual score") }
                OutlinedButton(onClick = { onChange(acceptFallback(part, FallbackTimer.COUNT_DOWN)) }) { Text("Countdown + manual score") }
            }
        }
        if (part.fallback?.accepted == true) Hint("Manual timer: no automatic movement/interrupt/rest cues")
        TextButton(onClick = { onChange(confirmSplits(part)) }) { Text("Confirm M/F splits") }
    }
}

private fun acceptFallback(p: Part, timer: FallbackTimer): Part {
    val w = Workout(id = "x", parts = listOf(p))
    return ReviewActions.acceptFallback(w, p.id, timer).parts[0]
}

private fun confirmSplits(p: Part): Part = ReviewActions.confirmSplits(Workout(id = "x", parts = listOf(p))).parts[0]

@Composable
private fun FormatEditor(part: Part, onChange: (com.wodcapture.domain.model.Format) -> Unit) {
    when (val f = part.format) {
        is ForTime -> {
            Grid { TimeField("Time cap", f.capSec, Modifier) { onChange(f.copy(capSec = it)) } }
            Toggle("Each round for time", f.eachRoundForTime) { onChange(f.copy(eachRoundForTime = it)) }
        }
        is Amrap -> Grid { TimeField("Duration", f.durationSec, Modifier) { onChange(f.copy(durationSec = it)) } }
        is Emom -> Grid {
            TimeField("Every", f.intervalSec, Modifier) { onChange(f.copy(intervalSec = it)) }
            IntField("Intervals", f.intervalCount, Modifier) { onChange(f.copy(intervalCount = it)) }
        }
        is Tabata -> Grid {
            TimeField("Work", f.workSec, Modifier) { onChange(f.copy(workSec = it ?: 20)) }
            TimeField("Rest", f.restSec, Modifier) { onChange(f.copy(restSec = it ?: 10)) }
            IntField("Rounds", f.rounds, Modifier) { onChange(f.copy(rounds = it ?: 8)) }
        }
        is Intervals -> {
            Grid {
                IntField("Rounds", f.rounds, Modifier) { onChange(f.copy(rounds = it)) }
                TimeField("Work", f.workSec, Modifier) { onChange(f.copy(workSec = it)) }
                TimeField("Rest", f.restSec, Modifier) { onChange(f.copy(restSec = it ?: 0)) }
            }
            Toggle("Final rest", f.finalRest) { onChange(f.copy(finalRest = it)) }
        }
        is MaxEffort -> Grid { TimeField("Window", f.windowSec, Modifier) { onChange(f.copy(windowSec = it)) } }
        is BuildToMax -> Grid {
            IntField("Reps per attempt", f.repsPerSet, Modifier) { onChange(f.copy(repsPerSet = it ?: 1)) }
            TimeField("Window", f.windowSec, Modifier) { onChange(f.copy(windowSec = it)) }
        }
        is StrengthSets -> Hint("Sets × reps and rest are edited per lift below")
        else -> Hint("Format edited as text in notes (v1)")
    }
}

/** A soft box around one body node so rows don't run together. */
@Composable
private fun NodeBox(highlight: Boolean, depth: Int, content: @Composable () -> Unit) {
    val c = MaterialTheme.colorScheme
    Surface(
        Modifier.fillMaxWidth().padding(start = (depth * 12).dp), shape = MaterialTheme.shapes.medium,
        color = if (highlight) c.tertiaryContainer else c.surfaceContainer,
    ) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() } }
}

/** Recursive body editor: items, groups and rests (REQ-AE02); never flattens nesting. */
@Composable
private fun BodyEditor(vm: AppViewModel, part: Part, nodes: List<BodyNode>, low: Set<String>, depth: Int, onChange: (Part) -> Unit) {
    nodes.forEach { n ->
        NodeBox(highlight = n.id in low, depth = depth) {
            if (n.id in low) Hint("Low confidence: please check", color = MaterialTheme.colorScheme.onTertiaryContainer)
            when (n) {
                is Item -> ItemEditor(vm, part, n, onChange)
                is Group -> {
                    Grid {
                        Choice("Group", n.kind, GroupKind.entries, { pretty(it.name) }, Modifier) { k -> onChange(Editing.updateNode(part, n.id) { (it as Group).copy(kind = k) }) }
                        IntField("Repeat", n.repeat, Modifier) { r -> onChange(Editing.updateNode(part, n.id) { (it as Group).copy(repeat = r ?: 1) }) }
                        Field("Rep scheme", n.repScheme.joinToString("-"), Modifier, placeholder = "21-15-9") { s ->
                            val scheme = s.split('-', ' ', ',').mapNotNull { it.trim().toIntOrNull() }
                            onChange(Editing.updateNode(part, n.id) { (it as Group).copy(repScheme = scheme) })
                        }
                    }
                    BodyEditor(vm, part, n.children, low, depth + 1, onChange)
                    TextButton(onClick = { onChange(Editing.addItem(part, n.id, Item(vm.newId(), name = "New movement", measure = Measure(MeasureKind.REP_SCHEME)))) }) {
                        Icon(Icons.Filled.Add, contentDescription = null, Modifier.padding(end = 4.dp))
                        Text("Add to group")
                    }
                }
                is Rest -> {
                    Grid { TimeField("Rest", n.sec, Modifier) { s -> onChange(Editing.updateNode(part, n.id) { (it as Rest).copy(sec = s ?: 0) }) } }
                    Toggle("On the clock", n.inClock) { c -> onChange(Editing.updateNode(part, n.id) { (it as Rest).copy(inClock = c) }) }
                }
            }
            NodeActions(depth == 0, onUp = { onChange(Editing.move(part, n.id, -1)) }, onDown = { onChange(Editing.move(part, n.id, 1)) }, onDelete = {
                onChange(Editing.removeNode(part, n.id))
            })
        }
    }
}

@Composable
private fun NodeActions(reorder: Boolean, onUp: () -> Unit, onDown: () -> Unit, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        if (reorder) {
            IconButton(onClick = onUp) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move up") }
            IconButton(onClick = onDown) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move down") }
        }
        IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
    }
}

@Composable
private fun ItemEditor(vm: AppViewModel, part: Part, item: Item, onChange: (Part) -> Unit) {
    fun set(f: (Item) -> Item) = onChange(Editing.updateNode(part, item.id) { f(it as Item) })
    val catalog = vm.catalog
    if (WorkoutSection.of(part) == WorkoutSection.WEIGHTS) {
        // Weights come from the lift list (owner request 2026-11-05); a parsed free-text lift is
        // flagged so it can be matched to the list.
        LiftField(catalog, item.name, item.movementId, Modifier.fillMaxWidth()) { m ->
            set { it.copy(movementId = m.id, name = m.name, displayName = null, implement = m.implement.takeIf { i -> i !in setOf("none", "bodyweight") }) }
        }
    } else {
        Field("Movement", item.name, Modifier.fillMaxWidth()) { v -> set { it.copy(name = v, displayName = null) } }
        Hint(if (item.movementId != null) "From the movement catalog" else "Free text")
        // Catalog autocomplete (REQ-AE02): names and unambiguous aliases.
        val suggestions = remember(item.name) {
            if (item.name.length < 2) emptyList() else catalog.movements.filter { m ->
                m.id != item.movementId && (m.name.contains(item.name, true) || m.aliases.any { it.equals(item.name, true) })
            }.take(3)
        }
        if (suggestions.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            suggestions.forEach { m -> AssistChip(onClick = { set { it.copy(movementId = m.id, name = m.name, implement = it.implement ?: m.implement) } }, label = { Text(m.name) }) }
        }
    }
    val strength = part.format is StrengthSets || item.sets.isNotEmpty()
    if (strength) Grid {
        val cell = Modifier
        IntField("Sets", item.sets.size, cell) { n -> set { Editing.reshapeSets(it, n ?: 0, null, null, vm::newId) } }
        IntField("Reps", item.sets.firstOrNull()?.reps?.value, cell) { r -> set { Editing.reshapeSets(it, it.sets.size, r, null, vm::newId) } }
        TimeField("Rest", item.sets.firstOrNull()?.restSec, cell) { s -> set { Editing.reshapeSets(it, it.sets.size, null, s, vm::newId) } }
    }
    Grid {
        val cell = Modifier
        val measure = item.measure ?: Measure(MeasureKind.REPS)
        if (!strength) {
            Choice("Measure", measure.kind, MeasureKind.entries, { pretty(it.name) }, cell) { k -> set { it.copy(measure = measure.copy(kind = k)) } }
            if (measure.kind !in setOf(MeasureKind.REP_SCHEME, MeasureKind.MAX)) {
                DoubleField("Value", measure.value, cell) { v -> set { it.copy(measure = measure.copy(value = v)) } }
                DoubleField("Female value", measure.female, cell) { v -> set { it.copy(measure = measure.copy(female = v)) } }
            }
        }
        var loadText by remember(item.id) { mutableStateOf(LoadText.format(item.load)) }
        Field("Load", loadText, cell, placeholder = "95/65 lb, 43 kg, 80%") { t -> loadText = t; set { it.copy(load = LoadText.parse(t)) } }
        IntField("Implements", item.count, cell) { n -> set { it.copy(count = n ?: 1) } }
    }
}

/** Exactly what the watch will display (REQ-AE04, REQ-DM18 preview of every line), on a watch-like dark screen. */
@Composable
private fun Preview(r: CompileResult, sections: Map<String, WorkoutSection>) {
    SectionTitle("Watch preview")
    when (r) {
        is CompileResult.Success -> {
            Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = Color(0xFF0B0C0E), contentColor = Color(0xFFE9EAEC)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    r.plan.p.forEach { p ->
                        Column {
                            val heading = when (sections[p.l]) {
                                WorkoutSection.WARMUP -> Color(0xFF9ED8C8)
                                WorkoutSection.WEIGHTS -> Color(0xFFA9BEFF)
                                WorkoutSection.METCON -> Color(0xFFFF9666)
                                else -> Color(0xFFC9C9D1)
                            }
                            Text("${p.l} · ${p.n}${if (p.fb) " (manual timer)" else ""}", style = MaterialTheme.typography.titleSmall, color = heading)
                            p.ln.forEach { Text(it, fontFamily = FontFamily.Monospace) }
                            p.sets?.forEach { s ->
                                val w = s.w
                                val load = w?.v?.let { v -> "${com.wodcapture.domain.load.MassMath.format(v)} ${w.u}" } ?: if (s.bw == true) "BW" else "enter load"
                                Text("${s.nm} ${s.rx} $load ${s.nt.orEmpty()}", fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }
            Hint("${r.encodedBytes} / 3584 bytes", Modifier.padding(top = 4.dp))
        }
        is CompileResult.Failure -> r.errors.forEach { Banner("Cannot send: ${it.message}", error = true, modifier = Modifier.padding(bottom = 6.dp)) }
    }
}
