package com.wodcapture.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.wodcapture.app.ciq.SdkState
import com.wodcapture.domain.DomainJson
import com.wodcapture.domain.load.MassMath
import com.wodcapture.domain.model.ConversionMode
import com.wodcapture.domain.model.Mass
import com.wodcapture.domain.model.MassUnit
import com.wodcapture.domain.model.PartScore
import com.wodcapture.domain.model.RepMax
import com.wodcapture.domain.model.ResultSource
import com.wodcapture.domain.model.ResultStatus
import com.wodcapture.domain.model.Score
import com.wodcapture.domain.model.SexSelection
import com.wodcapture.domain.model.WorkoutResult
import com.wodcapture.domain.parser.CostEstimator
import com.wodcapture.domain.protocol.Protocol
import com.wodcapture.domain.review.TimeText

/** Result history and manual entry (REQ-AL02, AL03). */
@Composable
fun ResultsScreen(vm: AppViewModel, back: () -> Unit) {
    val results by vm.results.collectAsState()
    val library by vm.library.collectAsState()
    var entering by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Bar("Results", back) { TextButton(onClick = { entering = true }) { Text("Add manual") } }
        LazyColumn(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(results, key = { it.id }) { r ->
                val title = library.firstOrNull { it.id == r.workoutId }?.title ?: "Workout"
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("$title · ${r.status.wire}${if (r.score?.manual == true) " · manual" else ""}", style = MaterialTheme.typography.titleSmall)
                        Text(r.scorePendingType?.let { "Score pending ($it): enter it here" } ?: scoreText(r.score), style = MaterialTheme.typography.headlineSmall)
                        Text(listOfNotNull(r.level, r.elapsedSec?.let { "elapsed ${TimeText.format(it)}" }, r.hrAvg?.let { "HR $it/${r.hrMax ?: "--"}" }, r.calories?.let { "$it kcal" }).joinToString(" · "))
                        r.sets.forEach { s ->
                            Text("Set ${s.index + 1}: ${s.actualReps ?: "--"}/${s.targetReps ?: "--"} @ ${s.load?.let(MassMath::formatMass) ?: "--"} ${s.status.wire}", style = MaterialTheme.typography.bodySmall)
                        }
                        if (r.source == ResultSource.WATCH) Text("As recorded on the watch", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { vm.deleteResult(r.id) }) { Text("Delete") }
                    }
                }
            }
        }
    }
    if (entering) ManualResultDialog(vm) { entering = false }
}

fun scoreText(s: PartScore?): String = when (val v = s?.score) {
    null -> "--"
    is Score.Time -> TimeText.format(v.seconds)
    is Score.RoundsReps -> "${v.rounds} + ${v.extraReps}"
    is Score.Reps -> "${v.count} reps"
    is Score.Load -> "${MassMath.format(v.value)} ${v.unit.wire}"
    is Score.TotalLoad -> "${MassMath.format(v.value)} ${v.unit.wire}"
    is Score.Volume -> "${MassMath.format(v.value)} ${v.massUnit.wire}"
    is Score.Calories -> "${v.count} cal"
    is Score.Distance -> "${MassMath.format(v.value)} ${v.unit.wire}"
    is Score.Points -> MassMath.format(v.value)
    is Score.MinutesReps -> "${v.completedIntervals} min + ${v.extraReps}"
    is Score.Completion -> if (v.completed) "done" else "not done"
    is Score.Text -> v.text
    Score.None -> "--"
}

@Composable
private fun ManualResultDialog(vm: AppViewModel, close: () -> Unit) {
    val library by vm.library.collectAsState()
    var workoutId by remember { mutableStateOf(library.firstOrNull()?.id) }
    var kind by remember { mutableStateOf("time") }
    var a by remember { mutableStateOf("") }
    var b by remember { mutableStateOf("") }
    var status by remember { mutableStateOf(ResultStatus.COMPLETED) }
    val entity = library.firstOrNull { it.id == workoutId }
    val workout = entity?.let { DomainJson.decodeWorkout(it.workoutJson) }
    var partId by remember(workoutId) { mutableStateOf(workout?.parts?.firstOrNull()?.id) }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Manual result") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Choice("Workout", workoutId, library.map { it.id }, { id -> library.firstOrNull { it.id == id }?.title ?: "-" }) { workoutId = it }
                workout?.let { w -> Choice("Part", partId, w.parts.map { it.id }, { id -> w.parts.firstOrNull { it.id == id }?.let { p -> "${p.label} ${p.title.orEmpty()}" } ?: "-" }) { partId = it } }
                Choice("Score", kind, listOf("time", "rounds_reps", "reps", "load", "text")) { kind = it }
                Choice("Status", status, ResultStatus.entries, { it.wire }) { status = it }
                when (kind) {
                    "time" -> Field("Time (m:ss)", a) { a = it }
                    "rounds_reps" -> Row { Field("Rounds", a, Modifier.width(120.dp), numeric = true) { a = it }; Field("Reps", b, Modifier.width(120.dp), numeric = true) { b = it } }
                    "reps" -> Field("Reps", a, numeric = true) { a = it }
                    "load" -> Field("Load (kg)", a, numeric = true) { a = it }
                    else -> Field("Score", a) { a = it }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val score: Score? = when (kind) {
                    "time" -> TimeText.parse(a)?.let { Score.Time(it) }
                    "rounds_reps" -> a.toIntOrNull()?.let { Score.RoundsReps(it, b.toIntOrNull() ?: 0) }
                    "reps" -> a.toIntOrNull()?.let { Score.Reps(it) }
                    "load" -> a.toDoubleOrNull()?.let { Score.Load(it, MassUnit.KG) }
                    else -> a.takeIf { it.isNotBlank() }?.let { Score.Text(it) }
                }
                if (workout != null && partId != null) {
                    vm.saveManualResult(
                        WorkoutResult(
                            id = vm.newId(), sessionId = vm.newId(), workoutId = workout.id, workoutRevision = workout.revision,
                            partId = partId!!, startedAt = System.currentTimeMillis() / 1000, status = status,
                            score = score?.let { PartScore(it, manual = true) }, source = ResultSource.MANUAL,
                        ),
                    )
                }
                close()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
    )
}

/** Watch connection (REQ-AW01–AW05): devices, install state per UUID, delivery states, Sync. */
@Composable
fun DeviceScreen(vm: AppViewModel, back: () -> Unit) {
    val sdk by vm.sdkState.collectAsState()
    val devices by vm.devices.collectAsState()
    val outbox by vm.outbox.collectAsState()
    val settings by vm.settings.collectAsState()
    val notices by vm.notices.collectAsState()
    Column(Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Bar("Watch", back)
        Text(
            when (val s = sdk) {
                SdkState.Initializing -> "Connecting to Garmin Connect…"
                SdkState.Ready -> "Garmin Connect ready${if (settings.tethered) " (simulator / TETHERED)" else ""}"
                is SdkState.Unavailable -> "No watch mode: ${s.reason}"
            },
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::requestSync) { Text("Sync now") }
            OutlinedButton(onClick = vm::refreshDevices) { Text("Refresh devices") }
            OutlinedButton(onClick = vm::openOnWatch) { Text("Open WOD Coach") }
            OutlinedButton(onClick = vm::sendProfile) { Text("Send defaults") }
        }
        Toggle("Use beta app id", settings.useBetaApp) { b -> vm.updateSettings { it.copy(useBetaApp = b) } }
        Toggle("Simulator (TETHERED, restart app)", settings.tethered) { t -> vm.updateSettings { it.copy(tethered = t) } }
        SectionTitle("Devices")
        if (devices.isEmpty()) Text("No Garmin devices known to Garmin Connect.")
        devices.forEach { d ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("${d.name} · ${d.status}", style = MaterialTheme.typography.titleSmall)
                    listOf(Protocol.APP_UUID_PRODUCTION to "WOD Coach", Protocol.APP_UUID_BETA to "WOD Coach beta").forEach { (uuid, name) ->
                        val install = d.apps[uuid]
                        Text("$name: ${install?.name?.lowercase() ?: "unknown"}${d.appVersions[uuid]?.let { " v$it" } ?: ""}${if (install != com.wodcapture.app.ciq.AppInstall.INSTALLED) " — sideloaded builds may not be detected; try sending" else ""}", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { vm.updateSettings { it.copy(selectedDeviceId = d.id) } }) { Text(if (settings.selectedDeviceId == d.id) "Selected" else "Use this watch") }
                }
            }
        }
        SectionTitle("Deliveries")
        outbox.sortedByDescending { it.enqueuedAtMs }.take(30).forEach { e ->
            Text("${e.type} ${e.objectId.take(8)} rev ${e.revision}: ${e.state.name.lowercase()}${e.reason?.let { " ($it)" } ?: ""}", style = MaterialTheme.typography.bodySmall)
        }
        notices.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary) }
    }
}

/** Profile and settings (REQ-AG01–AG04, AX09–AX11). */
@Composable
fun SettingsScreen(vm: AppViewModel, back: () -> Unit) {
    val s by vm.settings.collectAsState()
    val p = s.profile
    var apiKey by remember { mutableStateOf("") }
    var confirmWipe by remember { mutableStateOf(false) }
    var rmMovement by remember { mutableStateOf("back_squat") }
    var rmLoad by remember { mutableStateOf("") }
    fun profile(f: (com.wodcapture.domain.model.AthleteProfile) -> com.wodcapture.domain.model.AthleteProfile) =
        vm.updateSettings { it.copy(profile = f(it.profile), profileRevision = it.profileRevision + 1) }
    Column(Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Bar("Settings", back)
        SectionTitle("Profile")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Choice("Units", p.displayUnit, listOf(MassUnit.KG, MassUnit.LB), { it.wire }) { u -> profile { it.copy(displayUnit = u) } }
            Choice("Board unit", p.boardUnit, listOf(MassUnit.KG, MassUnit.LB), { it.wire }) { u -> profile { it.copy(boardUnit = u) } }
            Choice("Split loads", p.sex, SexSelection.entries, { it.name.lowercase() }) { x -> profile { it.copy(sex = x) } }
            Choice("Conversion", p.conversionMode, ConversionMode.entries, { it.name.lowercase() }) { m -> profile { it.copy(conversionMode = m) } }
        }
        DoubleField("Bodyweight (${p.displayUnit.wire})", p.bodyweight?.value) { v -> profile { it.copy(bodyweight = v?.let { x -> Mass(x, it.displayUnit) }) } }
        DoubleField("Bar weight (kg)", p.selectedBar?.weight?.value) { v -> v?.let { w -> profile { pr -> pr.copy(bars = pr.bars.map { b -> if (b.id == pr.selectedBarId) b.copy(weight = Mass(w, MassUnit.KG)) else b }) } } }
        DoubleField("Smallest total increment kg", p.barIncrementKg) { v -> v?.let { x -> profile { it.copy(barIncrementKg = x) } } }
        DoubleField("Smallest total increment lb", p.barIncrementLb) { v -> v?.let { x -> profile { it.copy(barIncrementLb = x) } } }
        TimeField("Default strength rest", p.defaultRestSec) { v -> v?.let { x -> profile { it.copy(defaultRestSec = x) } } }
        SectionTitle("Rep maxes (measured, never guessed)")
        p.repMaxes.forEach { rm ->
            Row {
                Text("${rm.movementId} ${rm.reps}RM: ${MassMath.formatMass(rm.load)}", Modifier.weight(1f).padding(8.dp))
                TextButton(onClick = { profile { it.copy(repMaxes = it.repMaxes - rm) } }) { Text("Remove") }
            }
        }
        Row {
            Field("Movement id", rmMovement, Modifier.weight(1f)) { rmMovement = it }
            Field("1RM (${p.displayUnit.wire})", rmLoad, Modifier.width(140.dp), numeric = true) { rmLoad = it }
            TextButton(onClick = { rmLoad.toDoubleOrNull()?.let { v -> profile { it.copy(repMaxes = it.repMaxes.filter { r -> r.movementId != rmMovement } + RepMax(rmMovement, 1, Mass(v, it.displayUnit))) } } }) { Text("Add") }
        }
        SectionTitle("Watch defaults")
        IntField("Get-ready countdown (s)", p.watch.prepSec) { v -> v?.let { x -> profile { it.copy(watch = it.watch.copy(prepSec = x)) } } }
        Toggle("Sound", p.watch.sound) { b -> profile { it.copy(watch = it.watch.copy(sound = b)) } }
        Toggle("Vibration", p.watch.vibration) { b -> profile { it.copy(watch = it.watch.copy(vibration = b)) } }
        Toggle("Record activity", p.watch.record) { b -> profile { it.copy(watch = it.watch.copy(record = b)) } }
        SectionTitle("Parser")
        Toggle("Cloud parsing (Anthropic)", s.cloudParsing) { b -> vm.updateSettings { it.copy(cloudParsing = b) } }
        Field("Model", s.model, Modifier.fillMaxWidth()) { m -> vm.updateSettings { it.copy(model = m) } }
        Choice("Effort", s.effort, listOf("low", "medium", "high", "xhigh", "max")) { e -> vm.updateSettings { it.copy(effort = e) } }
        androidx.compose.material3.OutlinedTextField(
            apiKey, { apiKey = it }, label = { Text(if (vm.hasApiKey()) "API key (stored; paste to replace)" else "Anthropic API key") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = apiKey.isNotBlank(), onClick = { vm.saveApiKey(apiKey); apiKey = "" }) { Text("Save key") }
            OutlinedButton(onClick = vm::testKey) { Text("Test key") }
        }
        Text("Estimated cost: about $%.2f per photo parse, $%.2f per text parse (your own key).".format(CostEstimator.typicalParseUsd(1), CostEstimator.typicalParseUsd(0)), style = MaterialTheme.typography.bodySmall)
        SectionTitle("Data")
        Button(onClick = { confirmWipe = true }) { Text("Delete all data") }
    }
    if (confirmWipe) AlertDialog(
        onDismissRequest = { confirmWipe = false },
        title = { Text("Delete everything?") },
        text = { Text("Deletes workouts, photos, results, queued parses and deliveries, and your API key on this phone. Garmin Connect activities and anything already on the watch are not deleted. The watch is treated as newly paired; its old pending results will need an explicit import-or-discard choice.") },
        confirmButton = { TextButton(onClick = { vm.deleteAll(); confirmWipe = false }) { Text("Delete all") } },
        dismissButton = { TextButton(onClick = { confirmWipe = false }) { Text("Cancel") } },
    )
}
