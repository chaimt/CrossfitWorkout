package com.wodcapture.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wodcapture.domain.catalog.Movement
import com.wodcapture.domain.load.MassMath
import com.wodcapture.domain.scoring.TopLift
import com.wodcapture.domain.scoring.TopLifts
import java.time.LocalDate

/**
 * The 1RM list (REQ-AL07, owner request 2026-11-06): top weight per lift with the day it was
 * lifted. A workout row opens that workout; a manual row offers to remove it. "Add manual" enters
 * a lift from the lift list with its weight — no workout, part or score.
 */
@Composable
fun TopLiftsScreen(vm: AppViewModel, back: () -> Unit, open: (String) -> Unit) {
    val lifts by vm.topLifts.collectAsState()
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<TopLift?>(null) }
    Column(Modifier.fillMaxSize()) {
        Bar("1RM", back) { TextButton(onClick = { adding = true }) { Text("Add manual") } }
        if (lifts.isEmpty()) {
            Hint(
                "No lifts yet. The weights of the strength sets in your saved workouts show up here, or add one with Add manual.",
                Modifier.padding(horizontal = Gutter),
            )
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = Gutter, end = Gutter, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(lifts, key = { it.key }) { lift ->
                TopLiftRow(lift) { lift.workoutId?.let(open) ?: run { removing = lift } }
            }
        }
    }
    if (adding) ManualLiftDialog(vm) { adding = false }
    removing?.let { lift ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove ${lift.name}?") },
            text = { Text("Removes the manually entered ${liftWeight(lift)}. Workout weights are not affected.") },
            confirmButton = { TextButton(onClick = { vm.removeRepMax(lift); removing = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TopLiftRow(lift: TopLift, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(lift.name, style = MaterialTheme.typography.titleSmall)
                Hint(listOfNotNull(lift.date?.let(Summaries::longDay), lift.workoutTitle?.ifBlank { "Workout" } ?: "Manual").joinToString(" · "))
            }
            Text(liftWeight(lift), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** "120 kg × 5". */
private fun liftWeight(lift: TopLift): String = MassMath.formatMass(lift.load) + (lift.reps?.let { " × $it" } ?: "")

/** Lift from the lift list, weight in Settings → Weight unit, reps (default 1) and date (default today). */
@Composable
private fun ManualLiftDialog(vm: AppViewModel, close: () -> Unit) {
    val settings by vm.settings.collectAsState()
    val unit = settings.profile.displayUnit
    var lift by remember { mutableStateOf<Movement?>(null) }
    var weight by remember { mutableStateOf("") }
    var reps by remember { mutableStateOf("1") }
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    val entry = TopLifts.manualEntry(lift?.id, weight, reps, date, unit, LocalDate.now())
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Add lift") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LiftField(vm.catalog, lift?.name.orEmpty(), lift?.id, Modifier.fillMaxWidth()) { lift = it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field("Weight (${unit.wire})", weight, Modifier.weight(1f), numeric = true) { weight = it }
                    Field("Reps", reps, Modifier.weight(1f), numeric = true) { reps = it }
                }
                Field("Date", date, Modifier.fillMaxWidth(), placeholder = "YYYY-MM-DD") { date = it }
            }
        },
        confirmButton = { TextButton(enabled = entry != null, onClick = { entry?.let(vm::addRepMax); close() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
    )
}
