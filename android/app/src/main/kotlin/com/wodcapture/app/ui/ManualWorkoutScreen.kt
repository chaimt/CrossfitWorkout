package com.wodcapture.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.wodcapture.domain.IdSource
import com.wodcapture.domain.catalog.MovementCatalog
import com.wodcapture.domain.manual.ManualBuild
import com.wodcapture.domain.manual.ManualForms
import com.wodcapture.domain.manual.ManualFormat
import com.wodcapture.domain.manual.ManualMovementForm
import com.wodcapture.domain.manual.ManualPartForm
import com.wodcapture.domain.manual.ManualStrengthForm
import com.wodcapture.domain.manual.ManualTemplates
import com.wodcapture.domain.manual.ManualWorkoutForm
import com.wodcapture.domain.manual.MaxEffortMeasure
import com.wodcapture.domain.manual.StrengthLoadMode
import com.wodcapture.domain.model.DistanceUnit
import com.wodcapture.domain.model.FallbackTimer
import com.wodcapture.domain.model.LengthUnit
import com.wodcapture.domain.model.MassUnit
import com.wodcapture.domain.model.MeasureKind
import com.wodcapture.domain.model.PartKind
import com.wodcapture.domain.validation.Severity
import com.wodcapture.domain.validation.ValidationIssue

/**
 * Guided "New workout" form (REQ-AC09): templates, title and date, then parts with a format,
 * its parameters and movement rows. The domain builder runs on every edit, so the same
 * validation as Review (REQ-AX05) shows as you type and **Create** stays disabled while anything
 * blocks. Create saves a draft and opens Review; nothing is sent from here.
 */
@Composable
fun ManualWorkoutScreen(vm: AppViewModel, onCreated: (String) -> Unit, back: () -> Unit) {
    val current by vm.manualForm.collectAsState()
    LaunchedEffect(Unit) { if (vm.manualForm.value == null) vm.startManual() }
    val form = current ?: return
    val build = remember(form) { vm.buildManual(form) }
    val ids = remember { IdSource { vm.newId() } }
    fun edit(f: (ManualWorkoutForm) -> ManualWorkoutForm) = vm.updateManual(f)
    fun editPart(id: String, f: (ManualPartForm) -> ManualPartForm) =
        edit { w -> w.copy(parts = w.parts.map { if (it.id == id) f(it) else it }) }

    Column(Modifier.fillMaxSize()) {
        Bar("New workout", back) {
            TextButton(enabled = build.canCreate, onClick = { vm.createManual(onCreated) }) { Text("Create") }
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Templates { t -> edit { ManualTemplates.apply(it, t, ids) } } }
            item {
                Field("Title", form.title, Modifier.fillMaxWidth()) { v -> edit { it.copy(title = v) } }
                Row {
                    Field("Date (YYYY-MM-DD)", form.date, Modifier.weight(1f)) { v -> edit { it.copy(date = v) } }
                    Text(form.timeZone, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                }
                Issues(build.report.issues.filter { it.partId == null })
            }
            items(form.parts, key = { it.id }) { part ->
                PartCard(
                    vm.catalog, part, ids, build.report.issues.filter { it.partId == part.id },
                    onChange = { f -> editPart(part.id, f) },
                    onRemove = { edit { w -> w.copy(parts = w.parts.filter { it.id != part.id }) } },
                )
            }
            item {
                AddPart { format -> edit { w -> w.copy(parts = w.parts + ManualForms.newPart(ids, w.parts.size, format)) } }
            }
            item {
                Row(Modifier.padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = build.canCreate, onClick = { vm.createManual(onCreated) }) { Text("Create") }
                    if (!build.canCreate) Text(blockingSummary(build), Modifier.padding(8.dp), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

private fun blockingSummary(b: ManualBuild): String {
    val n = b.report.blocking.distinctBy { it.message }.size
    return if (n == 1) "1 thing to fix" else "$n things to fix"
}

@Composable
private fun Templates(onPick: (com.wodcapture.domain.manual.ManualTemplate) -> Unit) {
    Column {
        SectionTitle("Start from a template")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ManualTemplates.all.forEach { t -> SuggestionChip(onClick = { onPick(t) }, label = { Text(t.name) }) }
        }
    }
}

/** Live findings: blocking first, each message once (sets repeat the same message per set). */
@Composable
private fun Issues(issues: List<ValidationIssue>) {
    issues.sortedBy { it.severity }.distinctBy { it.severity to it.message }.forEach { i ->
        val blocking = i.severity == Severity.BLOCKING
        Text(
            (if (blocking) "Fix: " else "Note: ") + i.message,
            color = if (blocking) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun AddPart(onAdd: (ManualFormat) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text("Add part") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ManualFormat.entries.forEach { f -> DropdownMenuItem(text = { Text(f.label) }, onClick = { open = false; onAdd(f) }) }
        }
    }
}

@Composable
private fun PartCard(
    catalog: MovementCatalog,
    part: ManualPartForm,
    ids: IdSource,
    issues: List<ValidationIssue>,
    onChange: ((ManualPartForm) -> ManualPartForm) -> Unit,
    onRemove: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row {
                Field("Label", part.label, Modifier.width(90.dp)) { v -> onChange { it.copy(label = v) } }
                Field("Name (optional)", part.name, Modifier.weight(1f).padding(start = 8.dp)) { v -> onChange { it.copy(name = v) } }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice("Format", part.format, ManualFormat.entries, { it.label }) { f -> onChange { ManualForms.withFormat(it, f, ids) } }
                Choice("Kind", part.kind, PartKind.entries, { it.name.lowercase() }) { k -> onChange { it.copy(kind = k) } }
            }
            FormatFields(part, onChange)
            when (part.format) {
                ManualFormat.STRENGTH -> StrengthRows(catalog, part, ids, onChange)
                else -> MovementRows(catalog, part, ids, onChange)
            }
            Issues(issues)
            TextButton(onClick = onRemove) { Text("Remove part") }
        }
    }
}

/** Parameters of the chosen format; times are m:ss / h:mm:ss text (REQ-AE02). */
@Composable
private fun FormatFields(part: ManualPartForm, onChange: ((ManualPartForm) -> ManualPartForm) -> Unit) {
    val m = Modifier.width(160.dp)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when (part.format) {
            ManualFormat.FOR_TIME -> {
                Field("Cap (m:ss, optional)", part.capText, m) { v -> onChange { it.copy(capText = v) } }
                IntField("Rounds", part.rounds, m) { v -> onChange { it.copy(rounds = v) } }
                Field("Rep scheme (21-15-9)", part.repSchemeText, m) { v -> onChange { ManualForms.withRepScheme(it, v) } }
            }
            ManualFormat.AMRAP -> Field("Duration (m:ss)", part.durationText, m) { v -> onChange { it.copy(durationText = v) } }
            ManualFormat.EMOM -> {
                Field("Every (m:ss, default 1:00)", part.intervalText, m) { v -> onChange { it.copy(intervalText = v) } }
                IntField("Intervals", part.intervalCount, m) { v -> onChange { it.copy(intervalCount = v) } }
                Text("One movement per interval, in order", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
            }
            ManualFormat.TABATA -> {
                Field("Work (default 0:20)", part.workText, m) { v -> onChange { it.copy(workText = v) } }
                Field("Rest (default 0:10)", part.restText, m) { v -> onChange { it.copy(restText = v) } }
                IntField("Rounds (default 8)", part.rounds, m) { v -> onChange { it.copy(rounds = v) } }
            }
            ManualFormat.INTERVALS -> {
                IntField("Rounds", part.rounds, m) { v -> onChange { it.copy(rounds = v) } }
                Field("Work (m:ss)", part.workText, m) { v -> onChange { it.copy(workText = v) } }
                Field("Rest (m:ss)", part.restText, m) { v -> onChange { it.copy(restText = v) } }
            }
            ManualFormat.STRENGTH -> Unit
            ManualFormat.BUILD_TO_MAX -> {
                IntField("Reps per attempt", part.repsPerSet, m) { v -> onChange { it.copy(repsPerSet = v) } }
                Field("Window (m:ss, optional)", part.durationText, m) { v -> onChange { it.copy(durationText = v) } }
                IntField("Attempt limit", part.attempts, m) { v -> onChange { it.copy(attempts = v) } }
            }
            ManualFormat.MAX_EFFORT -> {
                Choice("Measure", part.maxMeasure, MaxEffortMeasure.entries, { it.wire }) { v -> onChange { it.copy(maxMeasure = v) } }
                Field("Window (m:ss)", part.durationText, m) { v -> onChange { it.copy(durationText = v) } }
                IntField("Attempts (no window)", part.attempts, m) { v -> onChange { it.copy(attempts = v) } }
            }
            ManualFormat.CUSTOM -> CustomFields(part, onChange)
        }
    }
}

@Composable
private fun CustomFields(part: ManualPartForm, onChange: ((ManualPartForm) -> ManualPartForm) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = part.customText, onValueChange = { v -> onChange { it.copy(customText = v) } },
            label = { Text("Instructions (one watch line each)") }, minLines = 3, modifier = Modifier.fillMaxWidth(),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Choice("Timer", part.customTimer, FallbackTimer.entries, { if (it == FallbackTimer.COUNT_UP) "count up" else "countdown" }) { t ->
                onChange { it.copy(customTimer = t) }
            }
            val label = if (part.customTimer == FallbackTimer.COUNT_DOWN) "Length (m:ss)" else "Cap (m:ss, optional)"
            Field(label, part.durationText, Modifier.width(160.dp)) { v -> onChange { it.copy(durationText = v) } }
        }
        // REQ-DM18: the fallback is labelled wherever it is chosen.
        Text("Manual timer: no automatic movement/interrupt/rest cues", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun MovementRows(catalog: MovementCatalog, part: ManualPartForm, ids: IdSource, onChange: ((ManualPartForm) -> ManualPartForm) -> Unit) {
    fun editRow(id: String, f: (ManualMovementForm) -> ManualMovementForm) =
        onChange { p -> p.copy(movements = p.movements.map { if (it.id == id) f(it) else it }) }
    SectionTitle(if (part.format == ManualFormat.CUSTOM) "Movements (optional)" else "Movements")
    part.movements.forEachIndexed { i, row ->
        if (i > 0) HorizontalDivider()
        MovementRow(
            catalog, row, schemePart = ManualForms.usesScheme(part),
            onChange = { f -> editRow(row.id, f) },
            onRemove = { onChange { p -> p.copy(movements = p.movements.filter { it.id != row.id }) } },
        )
    }
    OutlinedButton(onClick = { onChange { p -> p.copy(movements = p.movements + ManualForms.newMovementFor(p, ids)) } }) { Text("Add movement") }
}

/** One movement: catalog autocomplete, measure, amount, load (single or M/F) and height. */
@Composable
private fun MovementRow(
    catalog: MovementCatalog,
    row: ManualMovementForm,
    schemePart: Boolean,
    onChange: ((ManualMovementForm) -> ManualMovementForm) -> Unit,
    onRemove: () -> Unit,
) {
    val catalogMovement = catalog.byId(row.movementId)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        MovementName(catalog, row.name, row.movementId, onName = { v ->
            onChange { it.copy(name = v, movementId = it.movementId.takeIf { id -> catalog.byId(id)?.name == v }) }
        }, onPick = { m -> onChange { ManualForms.pick(it, m, schemePart) } })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Choice("Measure", row.measure, MeasureKind.entries, ::measureName) { k -> onChange { it.copy(measure = k) } }
            val w = Modifier.width(110.dp)
            when (row.measure) {
                MeasureKind.REPS, MeasureKind.CALORIES, MeasureKind.DISTANCE -> {
                    val label = measureName(row.measure).replaceFirstChar { it.uppercase() }
                    DoubleField(if (row.maleFemale) "$label M" else label, row.amount, w) { v -> onChange { it.copy(amount = v) } }
                    if (row.maleFemale) DoubleField("$label F", row.amountFemale, w) { v -> onChange { it.copy(amountFemale = v) } }
                    if (row.measure == MeasureKind.DISTANCE) {
                        Choice("Unit", row.distanceUnit, DistanceUnit.entries, { it.wire }) { u -> onChange { it.copy(distanceUnit = u) } }
                    }
                }
                MeasureKind.DURATION -> Field("Time (m:ss)", row.durationText, Modifier.width(140.dp)) { v -> onChange { it.copy(durationText = v) } }
                MeasureKind.REP_SCHEME, MeasureKind.MAX -> Unit
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (catalogMovement?.loadable != "N") {
                DoubleField(if (row.maleFemale) "Load M" else "Load", row.load, Modifier.width(110.dp)) { v -> onChange { it.copy(load = v) } }
                if (row.maleFemale) DoubleField("Load F", row.loadFemale, Modifier.width(110.dp)) { v -> onChange { it.copy(loadFemale = v) } }
                Choice("Unit", row.loadUnit, listOf(MassUnit.KG, MassUnit.LB, MassUnit.POOD), { it.wire }) { u -> onChange { it.copy(loadUnit = u) } }
                if (row.load != null) Choice("Implements", row.implementCount, listOf(1, 2)) { n -> onChange { it.copy(implementCount = n) } }
            }
            FilterChip(
                selected = row.maleFemale, onClick = { onChange { it.copy(maleFemale = !it.maleFemale) } },
                label = { Text("M/F values") }, modifier = Modifier.semantics { contentDescription = "Separate male and female values" },
            )
        }
        val wantsHeight = catalogMovement?.height != null || catalogMovement?.target != null || row.height != null
        if (wantsHeight) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val label = if (row.heightIsTarget) "Target" else "Box height"
            DoubleField(if (row.maleFemale) "$label M" else label, row.height, Modifier.width(120.dp)) { v -> onChange { it.copy(height = v) } }
            if (row.maleFemale) DoubleField("$label F", row.heightFemale, Modifier.width(120.dp)) { v -> onChange { it.copy(heightFemale = v) } }
            Choice("Unit", row.heightUnit, LengthUnit.entries, { it.wire }) { u -> onChange { it.copy(heightUnit = u) } }
        }
        TextButton(onClick = onRemove) { Text("Remove movement") }
    }
}

private fun measureName(k: MeasureKind): String = when (k) {
    MeasureKind.REPS -> "reps"
    MeasureKind.REP_SCHEME -> "rep scheme"
    MeasureKind.MAX -> "max"
    MeasureKind.CALORIES -> "calories"
    MeasureKind.DISTANCE -> "distance"
    MeasureKind.DURATION -> "time"
}

/** Name field plus catalog suggestions (Appendix E); free text is kept when nothing is picked. */
@Composable
private fun MovementName(
    catalog: MovementCatalog,
    name: String,
    movementId: String?,
    onName: (String) -> Unit,
    onPick: (com.wodcapture.domain.catalog.Movement) -> Unit,
) {
    Row {
        Field("Movement", name, Modifier.weight(1f), onChange = onName)
        Text(if (movementId != null) "catalog" else "free text", Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall)
    }
    val suggestions = remember(name, movementId) { ManualForms.suggestions(catalog, name, movementId) }
    if (suggestions.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        suggestions.forEach { m -> AssistChip(onClick = { onPick(m) }, label = { Text(m.name) }) }
    }
}

@Composable
private fun StrengthRows(catalog: MovementCatalog, part: ManualPartForm, ids: IdSource, onChange: ((ManualPartForm) -> ManualPartForm) -> Unit) {
    fun editRow(id: String, f: (ManualStrengthForm) -> ManualStrengthForm) =
        onChange { p -> p.copy(strength = p.strength.map { if (it.id == id) f(it) else it }) }
    SectionTitle("Sets")
    part.strength.forEachIndexed { i, row ->
        if (i > 0) HorizontalDivider()
        StrengthRow(catalog, row, onChange = { f -> editRow(row.id, f) }, onRemove = {
            onChange { p -> p.copy(strength = p.strength.filter { it.id != row.id }) }
        })
    }
    OutlinedButton(onClick = { onChange { p -> p.copy(strength = p.strength + ManualForms.newStrengthRow(ids)) } }) { Text("Add strength movement") }
}

/** Strength row: sets × reps @ load (absolute or % 1RM), rest (REQ-DM06). */
@Composable
private fun StrengthRow(
    catalog: MovementCatalog,
    row: ManualStrengthForm,
    onChange: ((ManualStrengthForm) -> ManualStrengthForm) -> Unit,
    onRemove: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        MovementName(catalog, row.name, row.movementId, onName = { v ->
            onChange { it.copy(name = v, movementId = it.movementId.takeIf { id -> catalog.byId(id)?.name == v }) }
        }, onPick = { m -> onChange { ManualForms.pick(it, m) } })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val w = Modifier.width(90.dp)
            IntField("Sets", row.sets, w) { v -> onChange { it.copy(sets = v) } }
            IntField("Reps", row.reps, w) { v -> onChange { it.copy(reps = v) } }
            Choice("Load", row.loadMode, StrengthLoadMode.entries, ::loadModeName) { mode -> onChange { it.copy(loadMode = mode) } }
            when (row.loadMode) {
                StrengthLoadMode.ABSOLUTE -> {
                    DoubleField("Load", row.load, Modifier.width(110.dp)) { v -> onChange { it.copy(load = v) } }
                    Choice("Unit", row.loadUnit, listOf(MassUnit.KG, MassUnit.LB), { it.wire }) { u -> onChange { it.copy(loadUnit = u) } }
                }
                StrengthLoadMode.PERCENT_1RM -> DoubleField("% of 1RM", row.percent, Modifier.width(110.dp)) { v -> onChange { it.copy(percent = v) } }
                StrengthLoadMode.NONE -> Unit
            }
            Field("Rest (m:ss)", row.restText, Modifier.width(120.dp)) { v -> onChange { it.copy(restText = v) } }
        }
        TextButton(onClick = onRemove) { Text("Remove movement") }
    }
}

private fun loadModeName(m: StrengthLoadMode): String = when (m) {
    StrengthLoadMode.ABSOLUTE -> "weight"
    StrengthLoadMode.PERCENT_1RM -> "% 1RM"
    StrengthLoadMode.NONE -> "none"
}
