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
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wodcapture.domain.load.MassMath
import com.wodcapture.domain.scoring.TopLift

/** Top weight per lift with the day it was lifted (REQ-AL07); a row opens that workout. */
@Composable
fun TopLiftsScreen(vm: AppViewModel, back: () -> Unit, open: (String) -> Unit) {
    val lifts by vm.topLifts.collectAsState()
    Column(Modifier.fillMaxSize()) {
        Bar("Top lifts", back)
        if (lifts.isEmpty()) {
            Hint(
                "No lifts yet. Weights sets you finish on the watch, or a load you log as a manual result, show up here.",
                Modifier.padding(horizontal = Gutter),
            )
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = Gutter, end = Gutter, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(lifts, key = { it.key }) { lift -> TopLiftRow(lift) { open(lift.workoutId) } }
        }
    }
}

@Composable
private fun TopLiftRow(lift: TopLift, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(lift.name, style = MaterialTheme.typography.titleSmall)
                Hint("${Summaries.longDay(lift.date)} · ${lift.workoutTitle.ifBlank { "Workout" }}")
            }
            Text(
                MassMath.formatMass(lift.load) + (lift.reps?.let { " × $it" } ?: ""),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
