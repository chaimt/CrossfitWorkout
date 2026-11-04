package com.wodcapture.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import java.io.File
import java.util.UUID

/**
 * Capture (REQ-AC01–AC04): ML Kit Document Scanner, plain photo via the system camera, Photo
 * Picker, share sheet and pasted text; up to 4 ordered images per workout. Parsing needs explicit
 * intent and first-use consent (REQ-AX10, AX16); "Save draft" works offline.
 */
@Composable
fun CaptureScreen(vm: AppViewModel, onDraft: (String) -> Unit, back: () -> Unit) {
    val context = LocalContext.current
    val images = remember { mutableStateListOf<Uri>() }
    var text by remember { mutableStateOf("") }
    var askConsent by remember { mutableStateOf(false) }
    val settings by vm.settings.collectAsState()
    val shared by vm.shared.collectAsState()
    LaunchedEffect(shared) {
        shared?.let { s -> images.addAll(s.uris.take(4 - images.size)); s.text?.let { text = it }; vm.shared.value = null }
    }
    fun add(uris: List<Uri>) { images.addAll(uris.take((4 - images.size).coerceAtLeast(0))) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(4)) { add(it) }
    var pendingCamera by remember { mutableStateOf<Uri?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) pendingCamera?.let { add(listOf(it)) } }
    val scanner = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        GmsDocumentScanningResult.fromActivityResultIntent(r.data)?.pages?.map { it.imageUri }?.let(::add)
    }

    fun startScan() {
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(4)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        val activity = context.findActivity() ?: return
        GmsDocumentScanning.getClient(options).getStartScanIntent(activity)
            .addOnSuccessListener { scanner.launch(IntentSenderRequest.Builder(it).build()) }
            // Scanner unavailable (no Play services / low RAM): plain photo is always offered too.
            .addOnFailureListener { vm.message.value = "Scanner unavailable: use Plain photo" }
    }

    fun startCamera() {
        val dir = File(context.cacheDir, "camera").apply { mkdirs() }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", File(dir, "${UUID.randomUUID()}.jpg"))
        pendingCamera = uri
        camera.launch(uri)
    }

    fun proceed(parse: Boolean) {
        vm.capture(images.toList(), text) { id -> if (parse) vm.parse(id); onDraft(id) }
    }

    Column(Modifier.fillMaxSize()) {
        Bar("Capture", back)
        Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = ::startScan) { Text("Scan whiteboard") }
                OutlinedButton(onClick = ::startCamera) { Text("Plain photo") }
                OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("From gallery") }
            }
            Text("${images.size}/4 images (order is kept)")
            images.forEachIndexed { i, u ->
                FlowRow {
                    Text("${i + 1}. ${u.lastPathSegment}", Modifier.padding(4.dp))
                    TextButton(onClick = { images.removeAt(i) }) { Text("Remove") }
                }
            }
            OutlinedTextField(text, { text = it }, label = { Text("Or paste workout text") }, modifier = Modifier.fillMaxWidth().height(180.dp))
            val hasInput = images.isNotEmpty() || text.isNotBlank()
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = hasInput && settings.cloudParsing, onClick = { if (settings.consentGiven) proceed(true) else askConsent = true }) { Text("Parse") }
                OutlinedButton(enabled = hasInput, onClick = { proceed(false) }) { Text("Save draft") }
            }
            if (!settings.cloudParsing) Text("Cloud parsing is off: save the draft and edit it manually.")
        }
    }
    if (askConsent) {
        AlertDialog(
            onDismissRequest = { askConsent = false },
            title = { Text("Send to Anthropic for parsing?") },
            text = { Text("The photo(s) or text are sent to Anthropic's API with your own key to read the workout. Location and device data are stripped first. Nothing else leaves the phone. You can turn cloud parsing off in Settings.") },
            confirmButton = { TextButton(onClick = { vm.giveConsent(); askConsent = false; proceed(true) }) { Text("Agree and parse") } },
            dismissButton = { TextButton(onClick = { askConsent = false }) { Text("Not now") } },
        )
    }
}

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
