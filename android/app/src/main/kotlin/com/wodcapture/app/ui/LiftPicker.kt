package com.wodcapture.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.wodcapture.domain.catalog.Movement
import com.wodcapture.domain.catalog.MovementCatalog
import com.wodcapture.domain.manual.ManualForms

/**
 * Lift chooser for the weights section (owner request 2026-11-05): lifts are picked from the
 * catalog list (after crossfit.com/crossfit-movements), never typed. Looks like the other
 * outlined fields; tapping opens [LiftPickerDialog].
 */
@Composable
fun LiftField(catalog: MovementCatalog, name: String, movementId: String?, modifier: Modifier = Modifier, onPick: (Movement) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val listed = movementId != null && catalog.byId(movementId) != null
    Column(modifier) {
        Box {
            OutlinedTextField(
                value = name, onValueChange = {}, readOnly = true, singleLine = true,
                label = { Text("Lift") }, placeholder = { Text("Choose a lift") },
                trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
                isError = name.isNotBlank() && !listed,
                shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth(),
            )
            Box(
                Modifier.matchParentSize().clip(MaterialTheme.shapes.small)
                    .clickable(role = Role.DropdownList, onClickLabel = "Choose a lift") { open = true },
            )
        }
        if (name.isNotBlank() && !listed) Hint("Not in the list: choose the lift", Modifier.padding(top = 4.dp), color = MaterialTheme.colorScheme.error)
    }
    if (open) LiftPickerDialog(catalog, onDismiss = { open = false }) { m -> open = false; onPick(m) }
}

/** Full-height searchable list of lifts, grouped (squats/presses/deadlifts, Olympic, dumbbell, …). */
@Composable
fun LiftPickerDialog(catalog: MovementCatalog, onDismiss: () -> Unit, onPick: (Movement) -> Unit) {
    var query by remember { mutableStateOf("") }
    val groups = remember(query) { ManualForms.liftGroups(catalog, query) }
    val accent = sectionColors(com.wodcapture.domain.model.WorkoutSection.WEIGHTS)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.88f), shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
        ) {
            Column {
                Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Choose a lift", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Close") }
                }
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    placeholder = { Text("Search, e.g. squat, DB, KBS") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
                if (groups.isEmpty()) EmptyState("No lift matches “$query”", "Try the movement name, such as “snatch” or “press”.")
                LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                    groups.forEach { g ->
                        item(key = "h-${g.title}") {
                            Text(
                                g.title, style = MaterialTheme.typography.titleSmall, color = accent.accent,
                                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
                            )
                            HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        }
                        items(g.lifts, key = { it.id }) { m ->
                            ListItem(
                                headlineContent = { Text(m.name) },
                                supportingContent = liftDetail(m)?.let { d -> { Text(d) } },
                                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                                modifier = Modifier.clickable { onPick(m) }.padding(horizontal = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** "Barbell · PC" — the implement and the board abbreviation athletes know it by. */
private fun liftDetail(m: Movement): String? {
    val implement = m.implement.takeIf { it !in setOf("none", "bodyweight") }?.replace('_', ' ')?.replaceFirstChar { it.uppercase() }
    val alias = m.aliases.firstOrNull { it !in m.ambiguousAliases && it.length <= 6 }
    return listOfNotNull(implement, alias).joinToString(" · ").ifBlank { null }
}
