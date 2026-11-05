package com.wodcapture.app.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File

/**
 * Installs a downloaded update through a [PackageInstaller] session (REQ-AP07). Android shows
 * its own confirmation; the outcome arrives in [InstallResultReceiver] and then [InstallEvents].
 */
object ApkInstaller {
    const val ACTION_RESULT = "com.wodcapture.app.update.INSTALL_RESULT"

    /** Whether the user allowed WOD Capture to install apps ("Install unknown apps"). */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** The system screen where the user grants "Install unknown apps" to WOD Capture. */
    fun permissionIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /** Copies [apk] into a new session and commits it. Blocking: call off the main thread. */
    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("wod-capture", 0, apk.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            val result = Intent(context, InstallResultReceiver::class.java).setAction(ACTION_RESULT)
            // Mutable: the system adds the status extras to this intent.
            val pending = PendingIntent.getBroadcast(
                context, sessionId, result, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(pending.intentSender)
        }
    }
}

/** Outcome of an install attempt. */
sealed interface InstallEvent {
    data object Success : InstallEvent

    /** [status] is a `PackageInstaller.STATUS_*` code. */
    data class Failure(val status: Int, val message: String?) : InstallEvent
}

/** Install outcomes from [InstallResultReceiver] to the view model. */
object InstallEvents {
    private val flow = MutableSharedFlow<InstallEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<InstallEvent> = flow.asSharedFlow()

    fun emit(event: InstallEvent) {
        flow.tryEmit(event)
    }
}

/** Receives the session result: shows Android's confirmation, then reports the outcome. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ApkInstaller.ACTION_RESULT) return
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = confirmIntent(intent)
                if (confirm == null) {
                    InstallEvents.emit(InstallEvent.Failure(status, "Android did not ask for confirmation"))
                    return
                }
                // The app is on screen (the user just tapped Install), so this start is allowed.
                runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { InstallEvents.emit(InstallEvent.Failure(status, it.message)) }
            }
            PackageInstaller.STATUS_SUCCESS -> InstallEvents.emit(InstallEvent.Success)
            else -> InstallEvents.emit(InstallEvent.Failure(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)))
        }
    }

    @Suppress("DEPRECATION")
    private fun confirmIntent(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_INTENT)
}
