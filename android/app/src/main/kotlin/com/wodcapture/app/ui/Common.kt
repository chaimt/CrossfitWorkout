package com.wodcapture.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.wodcapture.domain.review.TimeText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Bar(title: String, back: (() -> Unit)? = null, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = { if (back != null) TextButton(onClick = back) { Text("Back") } },
        actions = { actions() },
    )
}

@Composable
fun SectionTitle(text: String) = Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))

/** Text field committing on every change; [label] doubles as the TalkBack label (REQ-NF08). */
@Composable
fun Field(label: String, value: String, modifier: Modifier = Modifier, numeric: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true, modifier = modifier,
        keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
    )
}

/** Integer entry; keeps the text locally so partial input does not jump. */
@Composable
fun IntField(label: String, value: Int?, modifier: Modifier = Modifier, onChange: (Int?) -> Unit) {
    var text by remember(value) { mutableStateOf(value?.toString().orEmpty()) }
    Field(label, text, modifier, numeric = true) { t -> text = t; onChange(t.toIntOrNull()) }
}

@Composable
fun DoubleField(label: String, value: Double?, modifier: Modifier = Modifier, onChange: (Double?) -> Unit) {
    var text by remember(value) { mutableStateOf(value?.let { com.wodcapture.domain.load.MassMath.format(it) }.orEmpty()) }
    Field(label, text, modifier, numeric = true) { t -> text = t; onChange(t.toDoubleOrNull()) }
}

/** m:ss / h:mm:ss entry (REQ-AE02). */
@Composable
fun TimeField(label: String, sec: Int?, modifier: Modifier = Modifier, onChange: (Int?) -> Unit) {
    var text by remember(sec) { mutableStateOf(TimeText.format(sec)) }
    Field("$label (m:ss)", text, modifier) { t -> text = t; onChange(TimeText.parse(t)) }
}

@Composable
fun <T> Choice(label: String, current: T, options: List<T>, name: (T) -> String = { it.toString() }, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text("$label: ${name(current)}") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o -> DropdownMenuItem(text = { Text(name(o)) }, onClick = { open = false; onPick(o) }) }
        }
    }
}

@Composable
fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
