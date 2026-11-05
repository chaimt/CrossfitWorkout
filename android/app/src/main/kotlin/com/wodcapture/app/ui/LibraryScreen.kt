package com.wodcapture.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wodcapture.app.ciq.SdkState
import com.wodcapture.app.data.WorkoutEntity
import com.wodcapture.domain.DomainJson
import com.wodcapture.domain.model.Workout
import com.wodcapture.domain.model.WorkoutSection

/** Library, newest first, searchable by text and movement (REQ-AL01); Capture and New workout (REQ-AC09). */
@Composable
fun LibraryScreen(vm: AppViewModel, open: (String) -> Unit, capture: () -> Unit, newWorkout: () -> Unit, go: (String) -> Unit) {
    val library by vm.library.collectAsState()
    val sdk by vm.sdkState.collectAsState()
    var query by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf<WorkoutEntity?>(null) }
    val shown = library.filter { query.isBlank() || it.title.contains(query, true) || it.workoutJson.contains(query, true) }
    Column(Modifier.fillMaxSize()) {
        Bar("WOD Capture") {
            WatchChip(sdk) { go(Routes.DEVICE) }
            IconButton(onClick = { go(Routes.RESULTS) }) { Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Results") }
            IconButton(onClick = { go(Routes.SETTINGS) }) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = Gutter, end = Gutter, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (sdk is SdkState.Unavailable) item {
                // REQ-AP03: "no watch" mode with a clear banner; everything else keeps working.
                Banner("No watch connected. Capture, review and history still work.", error = true)
            }
            item { UpdateBanner(vm.updates) }
            item { StartActions(capture, newWorkout) }
            item {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    placeholder = { Text("Search workouts or movements") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
            }
            if (shown.isEmpty()) item {
                if (library.isEmpty()) EmptyState("No workouts yet", "Scan today's whiteboard, or build the weights and the metcon by hand.")
                else EmptyState("Nothing matches “$query”", "Try a movement name such as “thruster”.")
            }
            items(shown, key = { it.id }) { w ->
                WorkoutRow(w, open = { open(w.id) }, duplicate = { vm.duplicate(w.id, open) }, delete = { confirmDelete = w })
            }
        }
    }
    confirmDelete?.let { w ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete “${w.title.ifBlank { "Untitled" }}”?") },
            text = { Text("The workout leaves the library; recorded results and run snapshots are kept.") },
            confirmButton = { TextButton(onClick = { vm.archive(w.id); confirmDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

/** Watch connection at a glance; tapping opens the device screen. */
@Composable
private fun WatchChip(sdk: SdkState, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val (text, dot) = when (sdk) {
        SdkState.Ready -> "Watch" to c.tertiary
        SdkState.Initializing -> "Watch…" to c.outline
        is SdkState.Unavailable -> "No watch" to c.error
    }
    Surface(onClick = onClick, shape = RoundedCornerShape(50), color = c.surfaceContainerHigh, modifier = Modifier.padding(end = 4.dp)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
            Text(text, Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** The two ways to start: capture from the whiteboard, or build it by hand. */
@Composable
private fun StartActions(capture: () -> Unit, newWorkout: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StartTile("Capture", "Scan the whiteboard, a photo or text", c.primary, c.onPrimary, Modifier.weight(1f), capture)
        // REQ-AC09: guided form, no photo, text or network needed.
        StartTile("New workout", "Build the weights and the metcon", c.secondaryContainer, c.onSecondaryContainer, Modifier.weight(1f), newWorkout, plus = true)
    }
}

@Composable
private fun StartTile(title: String, body: String, bg: Color, fg: Color, modifier: Modifier, onClick: () -> Unit, plus: Boolean = false) {
    Surface(onClick = onClick, modifier = modifier.height(112.dp), shape = MaterialTheme.shapes.large, color = bg, contentColor = fg) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (plus) Icon(Icons.Filled.Add, contentDescription = null, Modifier.padding(end = 4.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Text(body, style = MaterialTheme.typography.bodySmall, color = fg.copy(alpha = 0.85f))
        }
    }
}

@Composable
private fun WorkoutRow(w: WorkoutEntity, open: () -> Unit, duplicate: () -> Unit, delete: () -> Unit) {
    val workout = remember(w.workoutJson) { runCatching { DomainJson.decodeWorkout(w.workoutJson) }.getOrNull() }
    var menu by remember { mutableStateOf(false) }
    Panel(onClick = open) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(w.title.ifBlank { "Untitled" }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Hint("${Summaries.day(w.date)} · ${Summaries.status(w.status)}")
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More for ${w.title}") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Open") }, onClick = { menu = false; open() })
                    DropdownMenuItem(text = { Text("Duplicate") }, onClick = { menu = false; duplicate() })
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; delete() })
                }
            }
        }
        if (workout != null && workout.parts.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            PartLines(workout)
        }
    }
}

/** Section pills, then one line per part with its letter in the section colour. */
@Composable
private fun PartLines(workout: Workout) {
    val sections = workout.parts.map(WorkoutSection::of)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { sections.distinct().sortedBy { it.ordinal }.forEach { SectionPill(it) } }
    Spacer(Modifier.height(8.dp))
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        workout.parts.zip(sections).forEach { (p, s) ->
            Row {
                Text(p.label.ifBlank { "W" }, Modifier.width(24.dp), color = sectionColors(s).accent, style = MaterialTheme.typography.labelLarge)
                Text(Summaries.part(p), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
