package com.wodcapture.app.update

import android.content.Context
import android.content.pm.PackageInstaller
import com.wodcapture.domain.update.AvailableUpdate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Where the in-app update is (REQ-AP07). */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    /** Only after a manual check, so the user sees an answer. */
    data object UpToDate : UpdateState
    data class Available(val update: AvailableUpdate) : UpdateState
    data class Downloading(val update: AvailableUpdate, val done: Long, val total: Long) : UpdateState
    /** Downloaded, waiting for "Install unknown apps" to be granted. */
    data class NeedsPermission(val update: AvailableUpdate, val apk: File) : UpdateState
    data class Installing(val update: AvailableUpdate) : UpdateState
    /** [update] is null when the check itself failed. */
    data class Failed(val update: AvailableUpdate?, val message: String, val pageUrl: String?) : UpdateState
}

/** The update an [UpdateState] is about, if any (drives the Home banner). */
val UpdateState.pending: AvailableUpdate?
    get() = when (this) {
        is UpdateState.Available -> update
        is UpdateState.Downloading -> update
        is UpdateState.NeedsPermission -> update
        is UpdateState.Installing -> update
        is UpdateState.Failed -> update
        UpdateState.Idle, UpdateState.Checking, UpdateState.UpToDate -> null
    }

/**
 * Checks on app open (at most every [AUTO_CHECK_MS]) and on demand, then downloads and installs
 * when the user asks. A silent check never shows an error; only a found update shows a banner.
 */
class UpdateController(
    private val context: Context,
    private val updater: AppUpdater,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    /** The update dialog is open (the banner opens it; the app never pops it by itself). */
    val dialogOpen = MutableStateFlow(false)

    private var lastCheckMs = 0L
    private var job: Job? = null
    private var cleared = false

    init {
        scope.launch { InstallEvents.events.collect(::onInstallEvent) }
    }

    val installedVersion: String get() = updater.installedVersion()

    /** App came to the foreground: finish a pending install, else maybe check. */
    fun onAppResumed() {
        val s = _state.value
        if (s is UpdateState.NeedsPermission && ApkInstaller.canInstall(context)) install(s.update, s.apk)
        else if (nowMs() - lastCheckMs >= AUTO_CHECK_MS) check(manual = false)
    }

    /** Checks GitHub; [manual] checks also report "up to date" and errors. */
    fun check(manual: Boolean) {
        if (busy()) return
        lastCheckMs = nowMs()
        if (manual) _state.value = UpdateState.Checking
        job = scope.launch {
            if (!cleared) {
                // Old APKs are only needed until their install finishes.
                withContext(Dispatchers.IO) { updater.clearDownloads() }
                cleared = true
            }
            _state.value = try {
                updater.check()?.let { UpdateState.Available(it) } ?: if (manual) UpdateState.UpToDate else UpdateState.Idle
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A silent check that fails (offline, rate limit) leaves things as they were.
                if (manual) UpdateState.Failed(null, "Couldn't check for updates: ${e.message ?: e.javaClass.simpleName}", null) else _state.value
            }
        }
    }

    /** Download the offered update, then install it (asking for the permission if needed). */
    fun start() {
        val update = _state.value.pending ?: return
        if (busy()) return
        job = scope.launch {
            _state.value = UpdateState.Downloading(update, 0, update.apkSize)
            val apk = try {
                updater.download(update) { done, total -> _state.value = UpdateState.Downloading(update, done, total) }
            } catch (e: CancellationException) {
                _state.value = UpdateState.Available(update)
                throw e
            } catch (e: Exception) {
                _state.value = UpdateState.Failed(update, "Download failed: ${e.message ?: e.javaClass.simpleName}", update.pageUrl)
                return@launch
            }
            if (ApkInstaller.canInstall(context)) install(update, apk)
            else _state.value = UpdateState.NeedsPermission(update, apk)
        }
    }

    /** Stop a running download. */
    fun cancel() {
        job?.cancel()
    }

    private fun install(update: AvailableUpdate, apk: File) {
        _state.value = UpdateState.Installing(update)
        job = scope.launch {
            try {
                withContext(Dispatchers.IO) { ApkInstaller.install(context, apk) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = UpdateState.Failed(update, "Couldn't start the installation: ${e.message ?: e.javaClass.simpleName}", update.pageUrl)
            }
        }
    }

    private fun onInstallEvent(event: InstallEvent) {
        val update = _state.value.pending
        _state.value = when (event) {
            // Android replaces the app; this only shows if the process survives.
            InstallEvent.Success -> UpdateState.Idle
            is InstallEvent.Failure -> UpdateState.Failed(update, failureMessage(event), update?.pageUrl)
        }
    }

    private fun busy(): Boolean = _state.value.let {
        it is UpdateState.Checking || it is UpdateState.Downloading || it is UpdateState.Installing
    }

    companion object {
        /** A silent check runs at most this often (also covers rotation and quick app switches). */
        const val AUTO_CHECK_MS = 30 * 60 * 1000L

        /** What went wrong, in words the user can act on. */
        fun failureMessage(event: InstallEvent.Failure): String = when (event.status) {
            PackageInstaller.STATUS_FAILURE_ABORTED -> "Installation cancelled."
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                "This update is signed with a different key than the installed app, so Android won't install it over it. " +
                    "Uninstall WOD Capture (this deletes its data on this phone), then install the APK from the release page."
            PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough storage to install the update."
            PackageInstaller.STATUS_FAILURE_INVALID -> "The downloaded file is not a valid app package."
            else -> "Installation failed${event.message?.let { ": $it" } ?: "."}"
        }
    }
}
