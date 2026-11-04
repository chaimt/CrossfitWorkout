package com.wodcapture.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.dp
import com.wodcapture.app.ciq.SdkState
import com.wodcapture.app.data.WorkoutEntity

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
            TextButton(onClick = { go(Routes.RESULTS) }) { Text("Results") }
            TextButton(onClick = { go(Routes.DEVICE) }) { Text("Watch") }
            TextButton(onClick = { go(Routes.SETTINGS) }) { Text("Settings") }
        }
        if (sdk is SdkState.Unavailable) {
            // REQ-AP03: "no watch" mode with a clear banner; everything else keeps working.
            Text(
                "No watch connection (${(sdk as SdkState.Unavailable).reason}). Capture, review and history still work.",
                color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp),
            )
        }
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = capture) { Text("Capture") }
            // REQ-AC09: guided form, no photo, text or network needed.
            OutlinedButton(onClick = newWorkout) { Text("New workout") }
        }
        Field("Search text or movement", query, Modifier.fillMaxWidth().padding(horizontal = 12.dp)) { query = it }
        LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(shown, key = { it.id }) { w ->
                Card(Modifier.fillMaxWidth().clickable { open(w.id) }) {
                    Column(Modifier.padding(12.dp)) {
                        Text(w.title.ifBlank { "Untitled" }, style = MaterialTheme.typography.titleMedium)
                        Text("${w.date ?: "no date"} · ${w.status} · rev ${w.revision}", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { open(w.id) }) { Text("Open") }
                            TextButton(onClick = { vm.duplicate(w.id, open) }) { Text("Duplicate") }
                            TextButton(onClick = { confirmDelete = w }) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }
    confirmDelete?.let { w ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete '${w.title}'?") },
            text = { Text("The workout leaves the library; recorded results and run snapshots are kept.") },
            confirmButton = { TextButton(onClick = { vm.archive(w.id); confirmDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}
