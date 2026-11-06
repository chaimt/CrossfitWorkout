package com.wodcapture.app.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.net.URLEncoder

/** Feedback by email to the owner (REQ-AG05). Pure so the mail it builds is unit-tested. */
object Feedback {
    const val ADDRESS = "cyturel@gmail.com"

    fun subject(appVersion: String): String = "WOD Capture feedback (v$appVersion)"

    /** Empty space to write in, then the context that makes a report actionable. */
    fun body(appVersion: String, androidVersion: String, device: String): String =
        "\n\n\n---\nWOD Capture $appVersion · Android $androidVersion · $device\n"

    /** `mailto:` URI with subject and body, for mail apps that ignore the intent extras. */
    fun mailto(subject: String, body: String): String = "mailto:$ADDRESS?subject=${enc(subject)}&body=${enc(body)}"

    // URLEncoder is form encoding: a space becomes "+", which mail apps show literally.
    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}

/** Settings → App: **Send feedback** opens the phone's email app addressed to the owner. */
@Composable
fun FeedbackSettings(appVersion: String) {
    val context = LocalContext.current
    var noMailApp by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = {
            val subject = Feedback.subject(appVersion)
            val body = Feedback.body(appVersion, Build.VERSION.RELEASE, "${Build.MANUFACTURER} ${Build.MODEL}")
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse(Feedback.mailto(subject, body)))
                .putExtra(Intent.EXTRA_EMAIL, arrayOf(Feedback.ADDRESS))
                .putExtra(Intent.EXTRA_SUBJECT, subject)
                .putExtra(Intent.EXTRA_TEXT, body)
            noMailApp = try {
                context.startActivity(intent)
                false
            } catch (_: ActivityNotFoundException) {
                true
            }
        }) { Text("Send feedback") }
        Text(if (noMailApp) "No email app found. Write to ${Feedback.ADDRESS}" else Feedback.ADDRESS, style = MaterialTheme.typography.bodySmall)
    }
}
