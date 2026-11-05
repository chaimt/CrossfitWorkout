package com.wodcapture.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

/** Single activity (REQ-AP01). Receives share-sheet images and text (REQ-AC02, AC03). */
class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Status-bar icons follow the theme (dark icons on the light background).
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleShare(intent)
        setContent { WodTheme { AppRoot(vm) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    override fun onResume() {
        super.onResume()
        // REQ-AW04: on opening the app, request pending results from the watch.
        vm.requestSync()
        // REQ-AP07: look for an app update (throttled) or finish one waiting for permission.
        vm.updates.onAppResumed()
    }

    @Suppress("DEPRECATION")
    private fun handleShare(intent: Intent?) {
        intent ?: return
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(
                if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableExtra(Intent.EXTRA_STREAM),
            )
            Intent.ACTION_SEND_MULTIPLE -> (
                if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                ).orEmpty()
            else -> return
        }
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (uris.isNotEmpty() || !text.isNullOrBlank()) vm.shared.value = SharedContent(uris, text)
    }
}

object Routes {
    const val LIBRARY = "library"
    const val CAPTURE = "capture"
    const val REVIEW = "review/{id}"
    const val RESULTS = "results"
    const val RESULTS_ROUTE = "results?log={log}"
    const val TOP_LIFTS = "lifts"
    const val MANUAL = "manual"
    const val DEVICE = "device"
    const val SETTINGS = "settings"
    fun review(id: String) = "review/$id"

    /** Results with manual entry open for workout [id] (REQ-AL03). */
    fun logResult(id: String) = "results?log=$id"
}

@Composable
fun AppRoot(vm: AppViewModel) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val message by vm.message.collectAsState()
    val shared by vm.shared.collectAsState()
    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); vm.message.value = null }
    }
    LaunchedEffect(shared) { if (shared != null) nav.navigate(Routes.CAPTURE) }
    UpdateDialog(vm.updates)
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { pad ->
        NavHost(nav, startDestination = Routes.LIBRARY, modifier = Modifier.padding(pad)) {
            composable(Routes.LIBRARY) {
                LibraryScreen(
                    vm,
                    open = { nav.navigate(Routes.review(it)) },
                    capture = { nav.navigate(Routes.CAPTURE) },
                    newWorkout = { vm.startManual(); nav.navigate(Routes.MANUAL) },
                    go = { nav.navigate(it) },
                )
            }
            composable(Routes.MANUAL) {
                ManualWorkoutScreen(vm, onCreated = { id -> nav.popBackStack(); nav.navigate(Routes.review(id)) }, back = { nav.popBackStack() })
            }
            composable(Routes.CAPTURE) {
                CaptureScreen(vm, onDraft = { id -> nav.popBackStack(); nav.navigate(Routes.review(id)) }, back = { nav.popBackStack() })
            }
            composable(Routes.REVIEW, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                ReviewScreen(
                    vm, entry.arguments?.getString("id").orEmpty(), back = { nav.popBackStack() },
                    logResult = { id -> nav.navigate(Routes.logResult(id)) },
                )
            }
            composable(
                Routes.RESULTS_ROUTE,
                arguments = listOf(navArgument("log") { type = NavType.StringType; nullable = true; defaultValue = null }),
            ) { entry ->
                ResultsScreen(vm, back = { nav.popBackStack() }, logFor = entry.arguments?.getString("log"))
            }
            composable(Routes.TOP_LIFTS) { TopLiftsScreen(vm, back = { nav.popBackStack() }, open = { nav.navigate(Routes.review(it)) }) }
            composable(Routes.DEVICE) { DeviceScreen(vm, back = { nav.popBackStack() }) }
            composable(Routes.SETTINGS) { SettingsScreen(vm, back = { nav.popBackStack() }) }
        }
    }
}
