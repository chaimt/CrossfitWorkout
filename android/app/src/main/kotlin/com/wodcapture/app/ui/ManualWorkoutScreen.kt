package com.wodcapture.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.wodcapture.domain.IdSource
import com.wodcapture.domain.catalog.Movement
import com.wodcapture.domain.catalog.MovementCatalog
import com.wodcapture.domain.manual.MANUAL_SECTIONS
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
import com.wodcapture.domain.manual.StrengthScheme
import com.wodcapture.domain.manual.kindsFor
import com.wodcapture.domain.model.DistanceUnit
import com.wodcapture.domain.model.FallbackTimer
import com.wodcapture.domain.model.LengthUnit
import com.wodcapture.domain.model.MassUnit
import com.wodcapture.domain.model.MeasureKind
import com.wodcapture.domain.model.PartKind
import com.wodcapture.domain.model.WorkoutSection
import com.wodcapture.domain.validation.ValidationIssue

private typealias PartEdit = ((ManualPartForm) -> ManualPartForm) -> Unit

/**
 * Guided "New workout" form (REQ-AC09) in two sections, as a class runs them: **Weights**
 * (strength sets, build to a max) then **Metcon** (for time, AMRAP, EMOM, …). Each section has
 * its own quick-start templates and add menu, so picking "5 × 5 strength" and then "AMRAP 20"
 * builds the whole day. The domain builder runs on every edit, so the same validation as Review
 * (REQ-AX05) shows as you type and **Create** stays disabled while anything blocks. Create saves a
 * draft and opens Review; nothing is sent from here.
 */
@Composable
fun ManualWorkoutScreen(vm: AppViewModel, onCreated: (String) -> Unit, back: () -> Unit) {
    val current by vm.manualForm.collectAsState()
    LaunchedEffect(Unit) { if (vm.manualForm.value == null) vm.startManual() }
    val form = current ?: return
    val build = remember(form) { vm.buildManual(form) }
    val ids = remember { IdSource { vm.newId() } }
    fun edit(f: (ManualWorkoutForm) -> ManualWorkoutForm) = vm.updateManual(f)

    Column(Modifier.fillMaxSize()) {
        Bar("New workout", back)
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = Gutter, end = Gutter, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Panel {
                    Field("Title", form.title, Modifier.fillMaxWidth(), placeholder = "e.g. Monday strength + metcon") { v -> edit { it.copy(title = v) } }
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Field("Date", form.date, Modifier.weight(1f), placeholder = "YYYY-MM-DD") { v -> edit { it.copy(date = v) } }
                        Hint(form.timeZone, Modifier.padding(start = 12.dp))
                    }
                    Issues(build.report.issues.filter { it.partId == null })
                }
            }
            MANUAL_SECTIONS.forEach { section ->
                val parts = ManualForms.partsIn(form, section)
                item(key = "header-$section") {
                    SectionHeader(section, sectionSubtitle(section))
                    Templates(section) { t -> edit { ManualTemplates.apply(it, t, ids) } }
                }
                items(parts, key = { it.id }) { part ->
                    PartCard(
                        vm.catalog, section, ManualForms.effectiveLabel(form, part), part, ids,
                        build.report.issues.filter { it.partId == part.id },
                        onChange = { f -> edit { ManualForms.updatePart(it, part.id, f) } },
                        onRemove = { edit { ManualForms.removePart(it, part.id) } },
                    )
                }
                item(key = "add-$section") {
                    if (parts.isEmpty()) Hint(emptySectionText(section), Modifier.padding(bottom = 4.dp))
                    AddPart(section) { format -> edit { ManualForms.addPart(it, format, ids) } }
                }
            }
        }
        ActionBar(top = { if (!build.canCreate) Hint(blockingSummary(build), color = MaterialTheme.colorScheme.error) }) {
            Button(enabled = build.canCreate, onClick = { vm.createManual(onCreated) }, modifier = Modifier.weight(1f)) { Text("Create workout") }
        }
    }
}

private fun sectionSubtitle(s: WorkoutSection) = when (s) {
    WorkoutSection.WEIGHTS -> "Strength sets or build to a max"
    else -> "For time, AMRAP, EMOM, Tabata, intervals"
}

private fun emptySectionText(s: WorkoutSection) = when (s) {
    WorkoutSection.WEIGHTS -> "No lifting today? Leave this empty."
    else -> "No metcon yet."
}

private fun blockingSummary(b: ManualBuild): String {
    val n = b.report.blocking.distinctBy { it.message }.size
    return if (n == 1) "1 thing to fix before creating" else "$n things to fix before creating"
}

@Composable
private fun Templates(section: WorkoutSection, onPick: (com.wodcapture.domain.manual.ManualTemplate) -> Unit) {
    Hint("Quick start", Modifier.padding(top = 4.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ManualTemplates.of(section).forEach { t -> SuggestionChip(onClick = { onPick(t) }, label = { Text(t.name) }) }
    }
}

@Composable
private fun AddPart(section: WorkoutSection, onAdd: (ManualFormat) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val accent = sectionColors(section).accent
    Box {
        OutlinedButton(onClick = { open = true }, colors = ButtonDefaults.outlinedButtonColors(contentColor = accent)) {
            Icon(Icons.Filled.Add, contentDescription = null, Modifier.padding(end = 6.dp))
            Text(if (section == WorkoutSection.WEIGHTS) "Add weights" else "Add metcon")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ManualFormat.of(section).forEach { f -> DropdownMenuItem(text = { Text(f.label) }, onClick = { open = false; onAdd(f) }) }
        }
    }
}

@Composable
private fun PartCard(
    catalog: MovementCatalog,
    section: WorkoutSection,
    label: String,
    part: ManualPartForm,
    ids: IdSource,
    issues: List<ValidationIssue>,
    onChange: PartEdit,
    onRemove: () -> Unit,
) {
    SectionCard(section) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LabelBadge(label, section)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(part.name.ifBlank { part.format.label }, style = MaterialTheme.typography.titleMedium)
                Hint(kindName(part.kind))
            }
            IconButton(onClick = onRemove) { Icon(Icons.Filled.Delete, contentDescription = "Remove part $label") }
        }
        FormatChips(section, part.format) { f -> onChange { ManualForms.withFormat(it, f, ids) } }
        Grid {
            Field("Label", part.label, Modifier.weight(1f), placeholder = label.ifBlank { "auto" }) { v -> onChange { it.copy(label = v) } }
            Field("Name (optional)", part.name, Modifier.weight(1f), placeholder = "e.g. Fran") { v -> onChange { it.copy(name = v) } }
            Choice("Type", part.kind, kindsFor(section), ::kindName, Modifier.weight(1f)) { k -> onChange { it.copy(kind = k) } }
        }
        FormatFields(part, onChange)
        when (part.format) {
            ManualFormat.STRENGTH -> StrengthRows(catalog, part, ids, onChange)
            else -> MovementRows(catalog, part, ids, onChange)
        }
        Issues(issues)
    }
}

@Composable
private fun FormatChips(section: WorkoutSection, current: ManualFormat, onPick: (ManualFormat) -> Unit) {
    val colors = sectionColors(section)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ManualFormat.of(section).forEach { f ->
            FilterChip(
                selected = f == current, onClick = { onPick(f) }, label = { Text(f.label) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = colors.container, selectedLabelColor = colors.onContainer),
            )
        }
    }
}

private fun kindName(k: PartKind): String = when (k) {
    PartKind.WARMUP -> "Warm-up"
    PartKind.SKILL -> "Skill"
    PartKind.STRENGTH -> "Strength"
    PartKind.METCON -> "Metcon"
    PartKind.ACCESSORY -> "Accessory"
    PartKind.COOLDOWN -> "Cool-down"
    PartKind.OTHER -> "Other"
}

/** Parameters of the chosen format; times are m:ss / h:mm:ss text (REQ-AE02). */
@Composable
private fun FormatFields(part: ManualPartForm, onChange: PartEdit) {
    when (part.format) {
        ManualFormat.STRENGTH -> Unit
        ManualFormat.CUSTOM -> CustomFields(part, onChange)
        else -> Grid {
            val cell = Modifier
            when (part.format) {
                ManualFormat.FOR_TIME -> {
                    Field("Rep scheme", part.repSchemeText, cell, placeholder = "21-15-9") { v -> onChange { ManualForms.withRepScheme(it, v) } }
                    IntField("Rounds", part.rounds, cell) { v -> onChange { it.copy(rounds = v) } }
                    Field("Time cap (optional)", part.capText, cell, placeholder = "m:ss") { v -> onChange { it.copy(capText = v) } }
                }
                ManualFormat.AMRAP -> Field("Duration", part.durationText, cell, placeholder = "20:00") { v -> onChange { it.copy(durationText = v) } }
                ManualFormat.EMOM -> {
                    Field("Every", part.intervalText, cell, placeholder = "1:00") { v -> onChange { it.copy(intervalText = v) } }
                    IntField("Intervals", part.intervalCount, cell) { v -> onChange { it.copy(intervalCount = v) } }
                }
                ManualFormat.TABATA -> {
                    Field("Work", part.workText, cell, placeholder = "0:20") { v -> onChange { it.copy(workText = v) } }
                    Field("Rest", part.restText, cell, placeholder = "0:10") { v -> onChange { it.copy(restText = v) } }
                    IntField("Rounds (default 8)", part.rounds, cell) { v -> onChange { it.copy(rounds = v) } }
                }
                ManualFormat.INTERVALS -> {
                    IntField("Rounds", part.rounds, cell) { v -> onChange { it.copy(rounds = v) } }
                    Field("Work", part.workText, cell, placeholder = "3:00") { v -> onChange { it.copy(workText = v) } }
                    Field("Rest", part.restText, cell, placeholder = "1:00") { v -> onChange { it.copy(restText = v) } }
                }
                ManualFormat.BUILD_TO_MAX -> {
                    IntField("Reps per attempt", part.repsPerSet, cell) { v -> onChange { it.copy(repsPerSet = v) } }
                    IntField("Attempt limit", part.attempts, cell) { v -> onChange { it.copy(attempts = v) } }
                    Field("Window (optional)", part.durationText, cell, placeholder = "15:00") { v -> onChange { it.copy(durationText = v) } }
                }
                ManualFormat.MAX_EFFORT -> {
                    Choice("Measure", part.maxMeasure, MaxEffortMeasure.entries, { it.wire }, cell) { v -> onChange { it.copy(maxMeasure = v) } }
                    Field("Window", part.durationText, cell, placeholder = "m:ss") { v -> onChange { it.copy(durationText = v) } }
                    IntField("Attempts (no window)", part.attempts, cell) { v -> onChange { it.copy(attempts = v) } }
                }
                ManualFormat.STRENGTH, ManualFormat.CUSTOM -> Unit
            }
        }
    }
    if (part.format == ManualFormat.EMOM) Hint("One movement per interval, in order")
}

@Composable
private fun CustomFields(part: ManualPartForm, onChange: PartEdit) {
    OutlinedTextField(
        value = part.customText, onValueChange = { v -> onChange { it.copy(customText = v) } },
        label = { Text("Instructions (one watch line each)") }, minLines = 3, modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
    )
    Grid {
        val cell = Modifier
        Choice("Timer", part.customTimer, FallbackTimer.entries, { if (it == FallbackTimer.COUNT_UP) "Count up" else "Countdown" }, cell) { t ->
            onChange { it.copy(customTimer = t) }
        }
        val label = if (part.customTimer == FallbackTimer.COUNT_DOWN) "Length" else "Cap (optional)"
        Field(label, part.durationText, cell, placeholder = "m:ss") { v -> onChange { it.copy(durationText = v) } }
    }
    // REQ-DM18: the fallback is labelled wherever it is chosen.
    Hint("Manual timer: no automatic movement/interrupt/rest cues")
}

/** A soft box around one movement or lift so rows don't run together. */
@Composable
private fun RowBox(content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
private fun MovementRows(catalog: MovementCatalog, part: ManualPartForm, ids: IdSource, onChange: PartEdit) {
    fun editRow(id: String, f: (ManualMovementForm) -> ManualMovementForm) =
        onChange { p -> p.copy(movements = p.movements.map { if (it.id == id) f(it) else it }) }
    val single = part.format == ManualFormat.BUILD_TO_MAX
    Text(
        when {
            single -> "Lift"
            part.format == ManualFormat.CUSTOM -> "Movements (optional)"
            else -> "Movements"
        },
        style = MaterialTheme.typography.titleSmall,
    )
    part.movements.forEach { row ->
        MovementRow(
            catalog, row, schemePart = ManualForms.usesScheme(part), nameOnly = single,
            onChange = { f -> editRow(row.id, f) },
            onRemove = { onChange { p -> p.copy(movements = p.movements.filter { it.id != row.id }) } },
        )
    }
    if (!single || part.movements.isEmpty()) {
        TextButton(onClick = { onChange { p -> p.copy(movements = p.movements + ManualForms.newMovementFor(p, ids)) } }) {
            Icon(Icons.Filled.Add, contentDescription = null, Modifier.padding(end = 4.dp))
            Text("Add movement")
        }
    }
}

/** One movement: catalog autocomplete, measure, amount, load (single or M/F) and height. */
@Composable
private fun MovementRow(
    catalog: MovementCatalog,
    row: ManualMovementForm,
    schemePart: Boolean,
    nameOnly: Boolean,
    onChange: ((ManualMovementForm) -> ManualMovementForm) -> Unit,
    onRemove: () -> Unit,
) {
    RowBox {
        if (nameOnly) {
            // Build to max is a weights part: the lift comes from the list, and the attempts
            // carry the reps and load, so nothing else is asked for.
            LiftRow(catalog, row.name, row.movementId, onRemove) { m -> onChange { ManualForms.pick(it, m, schemePart) } }
        } else {
            MovementName(catalog, row.name, row.movementId, onName = { v ->
                onChange { it.copy(name = v, movementId = it.movementId.takeIf { id -> catalog.byId(id)?.name == v }) }
            }, onPick = { m -> onChange { ManualForms.pick(it, m, schemePart) } }, onRemove = onRemove)
            MovementDetails(catalog.byId(row.movementId), row, onChange)
        }
    }
}

/** Measure, amount, load and height of a movement row. */
@Composable
private fun MovementDetails(
    catalogMovement: Movement?,
    row: ManualMovementForm,
    onChange: ((ManualMovementForm) -> ManualMovementForm) -> Unit,
) {
    Grid {
        val cell = Modifier
        Choice("Measure", row.measure, MeasureKind.entries, ::measureName, cell) { k -> onChange { it.copy(measure = k) } }
        when (row.measure) {
            MeasureKind.REPS, MeasureKind.CALORIES, MeasureKind.DISTANCE -> {
                val label = measureName(row.measure)
                DoubleField(if (row.maleFemale) "$label (M)" else label, row.amount, cell) { v -> onChange { it.copy(amount = v) } }
                if (row.maleFemale) DoubleField("$label (F)", row.amountFemale, cell) { v -> onChange { it.copy(amountFemale = v) } }
                if (row.measure == MeasureKind.DISTANCE) {
                    Choice("Unit", row.distanceUnit, DistanceUnit.entries, { it.wire }, cell) { u -> onChange { it.copy(distanceUnit = u) } }
                }
            }
            MeasureKind.DURATION -> Field("Time", row.durationText, cell, placeholder = "m:ss") { v -> onChange { it.copy(durationText = v) } }
            MeasureKind.REP_SCHEME, MeasureKind.MAX -> Unit
        }
    }
    if (catalogMovement?.loadable != "N") Grid {
        val cell = Modifier
        DoubleField(if (row.maleFemale) "Load (M)" else "Load", row.load, cell) { v -> onChange { it.copy(load = v) } }
        if (row.maleFemale) DoubleField("Load (F)", row.loadFemale, cell) { v -> onChange { it.copy(loadFemale = v) } }
        Choice("Unit", row.loadUnit, listOf(MassUnit.KG, MassUnit.LB, MassUnit.POOD), { it.wire }, cell) { u -> onChange { it.copy(loadUnit = u) } }
        if (row.load != null) Choice("Implements", row.implementCount, listOf(1, 2), { if (it == 1) "Single" else "Double" }, cell) { n ->
            onChange { it.copy(implementCount = n) }
        }
    }
    val wantsHeight = catalogMovement?.height != null || catalogMovement?.target != null || row.height != null
    if (wantsHeight) Grid {
        val cell = Modifier
        val label = if (row.heightIsTarget) "Target" else "Box height"
        DoubleField(if (row.maleFemale) "$label (M)" else label, row.height, cell) { v -> onChange { it.copy(height = v) } }
        if (row.maleFemale) DoubleField("$label (F)", row.heightFemale, cell) { v -> onChange { it.copy(heightFemale = v) } }
        Choice("Unit", row.heightUnit, LengthUnit.entries, { it.wire }, cell) { u -> onChange { it.copy(heightUnit = u) } }
    }
    FilterChip(
        selected = row.maleFemale, onClick = { onChange { it.copy(maleFemale = !it.maleFemale) } },
        label = { Text("Separate M/F values") }, modifier = Modifier.semantics { contentDescription = "Separate male and female values" },
    )
}

private fun measureName(k: MeasureKind): String = when (k) {
    MeasureKind.REPS -> "Reps"
    MeasureKind.REP_SCHEME -> "Rep scheme"
    MeasureKind.MAX -> "Max"
    MeasureKind.CALORIES -> "Calories"
    MeasureKind.DISTANCE -> "Distance"
    MeasureKind.DURATION -> "Time"
}

/** Name field plus catalog suggestions (Appendix E); free text is kept when nothing is picked. */
@Composable
private fun MovementName(
    catalog: MovementCatalog,
    name: String,
    movementId: String?,
    onName: (String) -> Unit,
    onPick: (Movement) -> Unit,
    onRemove: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Field("Movement", name, Modifier.weight(1f), placeholder = "Start typing…", onChange = onName)
        IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, contentDescription = "Remove ${name.ifBlank { "movement" }}") }
    }
    if (name.isNotBlank()) Hint(if (movementId != null) "From the movement catalog" else "Free text: pick a suggestion for smarter cues")
    val suggestions = remember(name, movementId) { ManualForms.suggestions(catalog, name, movementId) }
    if (suggestions.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        suggestions.forEach { m -> AssistChip(onClick = { onPick(m) }, label = { Text(m.name) }) }
    }
}

/** Lift picker plus remove button, for weights rows. */
@Composable
private fun LiftRow(catalog: MovementCatalog, name: String, movementId: String?, onRemove: () -> Unit, onPick: (Movement) -> Unit) {
    Row(verticalAlignment = Alignment.Top) {
        LiftField(catalog, name, movementId, Modifier.weight(1f), onPick)
        IconButton(onClick = onRemove, modifier = Modifier.padding(top = 8.dp)) { Icon(Icons.Filled.Close, contentDescription = "Remove ${name.ifBlank { "lift" }}") }
    }
}

@Composable
private fun StrengthRows(catalog: MovementCatalog, part: ManualPartForm, ids: IdSource, onChange: PartEdit) {
    fun editRow(id: String, f: (ManualStrengthForm) -> ManualStrengthForm) =
        onChange { p -> p.copy(strength = p.strength.map { if (it.id == id) f(it) else it }) }
    SchemeFields(part.scheme) { f -> onChange { it.copy(scheme = f(it.scheme)) } }
    Text("Lifts", style = MaterialTheme.typography.titleSmall)
    part.strength.forEach { row ->
        StrengthRow(catalog, part.scheme, row, onChange = { f -> editRow(row.id, f) }, onRemove = {
            onChange { p -> p.copy(strength = p.strength.filter { it.id != row.id }) }
        })
    }
    TextButton(onClick = { onChange { p -> p.copy(strength = p.strength + ManualForms.newStrengthRow(ids)) } }) {
        Icon(Icons.Filled.Add, contentDescription = null, Modifier.padding(end = 4.dp))
        Text("Add lift")
    }
}

/**
 * Sets × reps, load type and rest for the whole part, before the lifts (owner request
 * 2026-11-05): every lift, including one added later, follows it.
 */
@Composable
private fun SchemeFields(scheme: StrengthScheme, onChange: ((StrengthScheme) -> StrengthScheme) -> Unit) {
    Text("Sets & reps (every lift)", style = MaterialTheme.typography.titleSmall)
    Grid {
        val cell = Modifier
        IntField("Sets", scheme.sets, cell) { v -> onChange { it.copy(sets = v) } }
        IntField("Reps", scheme.reps, cell) { v -> onChange { it.copy(reps = v) } }
        Choice("Load type", scheme.loadMode, StrengthLoadMode.entries, ::loadModeName, cell) { mode -> onChange { it.copy(loadMode = mode) } }
        if (scheme.loadMode == StrengthLoadMode.ABSOLUTE) {
            Choice("Unit", scheme.loadUnit, listOf(MassUnit.KG, MassUnit.LB), { it.wire }, cell) { u -> onChange { it.copy(loadUnit = u) } }
        }
        Field("Rest", scheme.restText, cell, placeholder = "2:00") { v -> onChange { it.copy(restText = v) } }
    }
}

/** A lift of a strength part: the lift from the list and its load in the part's load type (REQ-DM06). */
@Composable
private fun StrengthRow(
    catalog: MovementCatalog,
    scheme: StrengthScheme,
    row: ManualStrengthForm,
    onChange: ((ManualStrengthForm) -> ManualStrengthForm) -> Unit,
    onRemove: () -> Unit,
) {
    RowBox {
        LiftRow(catalog, row.name, row.movementId, onRemove) { m -> onChange { ManualForms.pick(it, m) } }
        when (scheme.loadMode) {
            StrengthLoadMode.ABSOLUTE -> Grid {
                DoubleField("Weight (${scheme.loadUnit.wire})", row.load, Modifier) { v -> onChange { it.copy(load = v) } }
            }
            StrengthLoadMode.PERCENT_1RM -> Grid {
                DoubleField("% of 1RM", row.percent, Modifier) { v -> onChange { it.copy(percent = v) } }
            }
            StrengthLoadMode.NONE -> Unit
        }
    }
}

private fun loadModeName(m: StrengthLoadMode): String = when (m) {
    StrengthLoadMode.ABSOLUTE -> "Weight"
    StrengthLoadMode.PERCENT_1RM -> "% of 1RM"
    StrengthLoadMode.NONE -> "None"
}
