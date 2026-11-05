package com.wodcapture.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.wodcapture.app.update.ApkInstaller
import com.wodcapture.app.update.UpdateController
import com.wodcapture.app.update.UpdateState
import com.wodcapture.app.update.pending
import com.wodcapture.domain.update.AvailableUpdate

/** Home banner when an update is waiting (REQ-AP07); tapping it opens [UpdateDialog]. */
@Composable
fun UpdateBanner(updates: UpdateController) {
    val state by updates.state.collectAsState()
    val update = state.pending ?: return
    val c = MaterialTheme.colorScheme
    Surface(
        Modifier.fillMaxWidth().clickable { updates.dialogOpen.value = true },
        shape = MaterialTheme.shapes.medium,
        color = c.primaryContainer,
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Update available: ${update.version}", style = MaterialTheme.typography.titleSmall, color = c.onPrimaryContainer)
                Text(bannerLine(state), style = MaterialTheme.typography.bodySmall, color = c.onPrimaryContainer)
            }
            Text("View", style = MaterialTheme.typography.labelLarge, color = c.onPrimaryContainer)
        }
    }
}

private fun bannerLine(state: UpdateState): String = when (state) {
    is UpdateState.Downloading -> "Downloading… ${percent(state.done, state.total)}"
    is UpdateState.Installing -> "Confirm the installation when Android asks"
    is UpdateState.NeedsPermission -> "Allow WOD Capture to install updates"
    is UpdateState.Failed -> "Update didn't finish: tap for details"
    else -> "Tap to see what's new and install"
}

/** Settings → App: installed version and a manual check. */
@Composable
fun UpdateSettings(updates: UpdateController) {
    val state by updates.state.collectAsState()
    Text("WOD Capture ${updates.installedVersion}", style = MaterialTheme.typography.bodyMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(enabled = state !is UpdateState.Checking, onClick = { updates.check(manual = true) }) { Text("Check for updates") }
        val line = when (val s = state) {
            UpdateState.Checking -> "Checking…"
            UpdateState.UpToDate -> "You have the latest version"
            is UpdateState.Failed -> if (s.update == null) s.message else null
            else -> s.pending?.let { "Version ${it.version} is available" }
        }
        line?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
    state.pending?.let { OutlinedButton(onClick = { updates.dialogOpen.value = true }) { Text("Update to ${it.version}") } }
}

/** What's new, then download → install, with Android's own confirmation at the end. */
@Composable
fun UpdateDialog(updates: UpdateController) {
    val open by updates.dialogOpen.collectAsState()
    val state by updates.state.collectAsState()
    val update = state.pending
    if (!open || update == null) return
    val context = LocalContext.current
    fun close() { updates.dialogOpen.value = false }
    AlertDialog(
        onDismissRequest = ::close,
        title = { Text("WOD Capture ${update.version}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("You have ${updates.installedVersion} · download ${megabytes(update.apkSize)}", style = MaterialTheme.typography.bodySmall)
                when (val s = state) {
                    is UpdateState.Downloading -> {
                        if (s.total > 0) LinearProgressIndicator(progress = { (s.done.toFloat() / s.total).coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
                        else LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("${megabytes(s.done)} of ${megabytes(s.total)} · ${percent(s.done, s.total)}", style = MaterialTheme.typography.bodySmall)
                    }
                    is UpdateState.Installing -> Text("Confirm the installation when Android asks.")
                    is UpdateState.NeedsPermission -> Text(
                        "Android needs your permission for WOD Capture to install updates. Turn on \"Allow from this source\", then come back.",
                    )
                    is UpdateState.Failed -> Text(s.message, color = MaterialTheme.colorScheme.error)
                    else -> Unit
                }
                ReleaseNotes(update)
            }
        },
        confirmButton = {
            when (state) {
                is UpdateState.Available -> TextButton(onClick = updates::start) { Text("Download & install") }
                is UpdateState.Downloading -> TextButton(onClick = updates::cancel) { Text("Cancel download") }
                is UpdateState.NeedsPermission -> TextButton(onClick = { context.startActivity(ApkInstaller.permissionIntent(context)) }) { Text("Open settings") }
                is UpdateState.Failed -> TextButton(onClick = updates::start) { Text("Try again") }
                else -> Unit
            }
        },
        dismissButton = {
            Row {
                if (state is UpdateState.Failed) TextButton(onClick = { openPage(context, update) }) { Text("Release page") }
                TextButton(onClick = ::close) { Text("Later") }
            }
        },
    )
}

/** The release notes, lightly cleaned of Markdown markers. */
@Composable
private fun ReleaseNotes(update: AvailableUpdate) {
    val notes = update.notes.lines()
        .map { it.trim().removePrefix("### ").removePrefix("## ").removePrefix("# ").replace(Regex("^[*-] "), "• ") }
        .filter { it.isNotEmpty() && !it.startsWith("**Full Changelog**") }
        .joinToString("\n")
    if (notes.isEmpty()) return
    Text(
        notes,
        Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
        style = MaterialTheme.typography.bodySmall,
    )
}

private fun openPage(context: Context, update: AvailableUpdate) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(update.pageUrl))) }
}

private fun megabytes(bytes: Long): String = if (bytes <= 0) "?" else "%.1f MB".format(bytes / 1_048_576.0)

private fun percent(done: Long, total: Long): String = if (total <= 0) "" else "${(done * 100 / total).coerceIn(0, 100)}%"
