package com.wodcapture.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wodcapture.domain.model.WorkoutSection
import com.wodcapture.domain.review.TimeText
import com.wodcapture.domain.validation.Severity
import com.wodcapture.domain.validation.ValidationIssue

/** Horizontal gutter shared by every screen. */
val Gutter = 16.dp

/**
 * Top bar. Window insets are zero because the root Scaffold already pads for the status bar;
 * letting the bar pad again doubled the gap above every title.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Bar(title: String, back: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(
        title = { Text(title, maxLines = 1) },
        navigationIcon = {
            if (back != null) IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        },
        actions = actions,
        windowInsets = WindowInsets(0),
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

@Composable
fun SectionTitle(text: String) =
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))

/** Secondary explanatory text. */
@Composable
fun Hint(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) =
    Text(text, modifier, style = MaterialTheme.typography.bodySmall, color = color)

/** Text field committing on every change; [label] doubles as the TalkBack label (REQ-NF08). */
@Composable
fun Field(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    numeric: Boolean = false,
    placeholder: String? = null,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label, maxLines = 1) }, singleLine = true, modifier = modifier,
        placeholder = placeholder?.let { p -> { Text(p) } },
        shape = MaterialTheme.shapes.small,
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
    Field(label, text, modifier, placeholder = "m:ss") { t -> text = t; onChange(TimeText.parse(t)) }
}

/**
 * Dropdown that looks like the text fields around it (outlined, label on the border) instead of
 * a loose "Label: value" button. A transparent overlay takes the tap so the field never focuses.
 */
@Composable
fun <T> Choice(
    label: String,
    current: T,
    options: List<T>,
    name: (T) -> String = { it.toString() },
    modifier: Modifier = Modifier.width(150.dp),
    onPick: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedTextField(
            value = name(current), onValueChange = {}, readOnly = true, singleLine = true,
            label = { Text(label, maxLines = 1) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        )
        Box(Modifier.matchParentSize().clip(MaterialTheme.shapes.small).clickable(role = Role.DropdownList) { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o -> DropdownMenuItem(text = { Text(name(o)) }, onClick = { open = false; onPick(o) }) }
        }
    }
}

/** Labelled switch; the whole row is one toggleable node so TalkBack reads the label with the state. */
@Composable
fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** Plain white card with a hairline border; the base of every list row and form block. */
@Composable
fun Panel(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    val inner: @Composable ColumnScope.() -> Unit = { Column(Modifier.padding(16.dp), content = content) }
    if (onClick != null) Card(onClick, modifier.fillMaxWidth(), colors = colors, border = border, content = inner)
    else Card(modifier.fillMaxWidth(), colors = colors, border = border, content = inner)
}

/**
 * A workout part card: a coloured strip on the left marks its section (warm-up, blue weights,
 * orange metcon, trailing), so a long day stays readable while scrolling.
 */
@Composable
fun SectionCard(section: WorkoutSection, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val accent = sectionColors(section).accent
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            Modifier.fillMaxWidth()
                .drawBehind { drawRect(accent, size = Size(5.dp.toPx(), size.height)) }
                .padding(start = 21.dp, end = 16.dp, top = 16.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

/** Big section heading ("Warm-up" / "Weights" / "Metcon" / "Cool-down & other") with a short explanation. */
@Composable
fun SectionHeader(section: WorkoutSection, subtitle: String? = null, trailing: @Composable RowScope.() -> Unit = {}) {
    val colors = sectionColors(section)
    Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 4.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 11.dp).size(12.dp).clip(CircleShape).background(colors.accent))
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(section.label, style = MaterialTheme.typography.titleLarge, color = colors.accent)
            if (subtitle != null) Hint(subtitle)
        }
        trailing()
    }
}

/** Round part letter ("A", "B", "W") in its section colour. */
@Composable
fun LabelBadge(text: String, section: WorkoutSection) {
    val colors = sectionColors(section)
    Box(Modifier.size(32.dp).clip(CircleShape).background(colors.accent), contentAlignment = Alignment.Center) {
        Text(text.ifBlank { "W" }.take(3), color = colors.onAccent, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
    }
}

/** Small rounded tag. */
@Composable
fun Pill(text: String, container: Color, content: Color) {
    Text(
        text, color = content, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(container).padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

@Composable
fun SectionPill(section: WorkoutSection) = sectionColors(section).let { Pill(section.label, it.container, it.onContainer) }

/** Status or problem message in a soft rounded box. */
@Composable
fun Banner(text: String, error: Boolean = false, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    val c = MaterialTheme.colorScheme
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = if (error) c.errorContainer else c.surfaceContainerHigh) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = if (error) c.onErrorContainer else c.onSurface)
            action?.invoke()
        }
    }
}

/** Live validation: blocking first, each message once (sets repeat the same message per set). */
@Composable
fun Issues(issues: List<ValidationIssue>, blockingPrefix: String = "Fix", warningPrefix: String = "Note") {
    val shown = issues.sortedBy { it.severity }.distinctBy { it.severity to it.message }
    if (shown.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        shown.forEach { i ->
            val blocking = i.severity == Severity.BLOCKING
            Hint(
                "${if (blocking) blockingPrefix else warningPrefix}: ${i.message}",
                color = if (blocking) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

/** Centered message for an empty list. */
@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Hint(body, Modifier.widthIn(max = 320.dp).padding(horizontal = 8.dp))
    }
}

/** Sticky bottom bar for a screen's main actions (Create, Send to watch, …). */
@Composable
fun ActionBar(top: (@Composable () -> Unit)? = null, content: @Composable RowScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, shadowElevation = 8.dp) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(Modifier.padding(horizontal = Gutter, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                top?.invoke()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = content)
            }
        }
    }
}

/**
 * Two-column form grid: every child gets exactly half the width (a lone last child too, so
 * columns line up). Exact widths matter: text fields carry a 280dp default minimum that would
 * otherwise push each one onto its own row on a phone.
 */
@Composable
fun Grid(content: @Composable () -> Unit) {
    Layout(content, Modifier.fillMaxWidth()) { measurables, c ->
        val gap = 8.dp.roundToPx()
        val half = ((c.maxWidth - gap) / 2).coerceAtLeast(0)
        val cell = Constraints(minWidth = half, maxWidth = half)
        val rows = measurables.chunked(2).map { row -> row.map { it.measure(cell) } }
        val heights = rows.map { row -> row.maxOf { it.height } }
        val total = heights.sum() + gap * (rows.size - 1).coerceAtLeast(0)
        layout(c.maxWidth, total) {
            var y = 0
            rows.forEachIndexed { i, row ->
                row.forEachIndexed { j, p -> p.placeRelative(j * (half + gap), y) }
                y += heights[i] + gap
            }
        }
    }
}
