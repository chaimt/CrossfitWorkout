package com.wodcapture.app.ciq

import android.content.Context
import android.util.Log
import com.garmin.android.connectiq.ConnectIQ
import com.garmin.android.connectiq.IQApp
import com.garmin.android.connectiq.IQDevice
import com.wodcapture.domain.protocol.Protocol
import com.wodcapture.domain.protocol.SendStatus
import com.wodcapture.domain.protocol.WatchTarget
import com.wodcapture.domain.protocol.WatchTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** SDK lifecycle as shown to the athlete (REQ-AP03, REQ-AP06). */
sealed interface SdkState {
    data object Initializing : SdkState
    data object Ready : SdkState
    /** `GCM_NOT_INSTALLED`, `GCM_UPGRADE_NEEDED` or `SERVICE_ERROR`: "no watch" mode with a banner. */
    data class Unavailable(val reason: String) : SdkState
}

/** WOD Coach install state per app UUID (REQ-AW01): unknown is a valid answer for sideloads. */
enum class AppInstall { UNKNOWN, INSTALLED, NOT_INSTALLED }

data class WatchDevice(
    val id: String,
    val name: String,
    val status: String,
    val apps: Map<String, AppInstall> = emptyMap(),
    val appVersions: Map<String, Int> = emptyMap(),
)

/**
 * Connect IQ Mobile SDK adapter implementing the domain [WatchTransport] (REQ-SY01, AP06, AW01–AW05).
 * Nothing is called before `onSdkReady`; every SDK call runs on one background executor (sends
 * must not run on the main thread); every send status is mapped to a [SendStatus].
 */
class ConnectIqAdapter(
    private val appContext: Context,
    private val tethered: Boolean,
    private val generation: () -> Int,
    private val onMessage: (WatchTarget, Map<*, *>) -> Unit,
) : WatchTransport {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "ciq") }
    private val ciq: ConnectIQ = ConnectIQ.getInstance(appContext, if (tethered) ConnectIQ.IQConnectType.TETHERED else ConnectIQ.IQConnectType.WIRELESS)
    private val known = mutableMapOf<String, IQDevice>()

    private val _state = MutableStateFlow<SdkState>(SdkState.Initializing)
    val state: StateFlow<SdkState> = _state
    private val _devices = MutableStateFlow<List<WatchDevice>>(emptyList())
    val devices: StateFlow<List<WatchDevice>> = _devices

    /** Called when a device reports CONNECTED (outbox restart, REQ-SY13). */
    var onConnected: (WatchTarget) -> Unit = {}

    private val apps = listOf(Protocol.APP_UUID_PRODUCTION, Protocol.APP_UUID_BETA)

    /** Initialises once with autoUI so Garmin Connect install/upgrade prompts appear (REQ-AP06). */
    fun start() {
        ciq.initialize(appContext, true, object : ConnectIQ.ConnectIQListener {
            override fun onSdkReady() {
                _state.value = SdkState.Ready
                executor.execute { refreshDevices() }
            }

            override fun onInitializeError(status: ConnectIQ.IQSdkErrorStatus) {
                _state.value = SdkState.Unavailable(status.name)
            }

            override fun onSdkShutDown() {
                _state.value = SdkState.Unavailable("shut down")
            }
        })
    }

    /** Known devices, connection status, app install state and event registration for both UUIDs. */
    fun refreshDevices() {
        if (_state.value != SdkState.Ready) return
        executor.execute {
            val list = try {
                ciq.knownDevices.orEmpty()
            } catch (e: Exception) {
                Log.w(TAG, "getKnownDevices failed: ${e.javaClass.simpleName}")
                emptyList()
            }
            list.forEach { d ->
                val id = d.deviceIdentifier.toString()
                known[id] = d
                upsert(WatchDevice(id, d.friendlyName ?: "Garmin $id", safeStatus(d)))
                runCatching {
                    ciq.registerForDeviceEvents(d) { dev, status ->
                        update(dev.deviceIdentifier.toString()) { it.copy(status = status.name) }
                        if (status.name == "CONNECTED") apps.forEach { onConnected(target(dev, it)) }
                    }
                }
                apps.forEach { uuid ->
                    runCatching {
                        ciq.registerForAppEvents(d, IQApp(uuid)) { dev, app, messages, status ->
                            if (status == ConnectIQ.IQMessageStatus.SUCCESS) {
                                messages.filterIsInstance<Map<*, *>>().forEach { onMessage(target(dev, app.applicationId), it) }
                            }
                        }
                    }
                    queryAppInfo(d, uuid)
                }
            }
        }
    }

    private fun queryAppInfo(d: IQDevice, uuid: String) = runCatching {
        ciq.getApplicationInfo(uuid, d, object : ConnectIQ.IQApplicationInfoListener {
            override fun onApplicationInfoReceived(app: IQApp) {
                update(d.deviceIdentifier.toString()) { it.copy(apps = it.apps + (uuid to AppInstall.INSTALLED), appVersions = it.appVersions + (uuid to app.version())) }
            }

            override fun onApplicationNotInstalled(applicationId: String) {
                // Sideloaded builds may not be detectable: show "unknown — try sending" in the UI.
                update(d.deviceIdentifier.toString()) { it.copy(apps = it.apps + (uuid to AppInstall.NOT_INSTALLED)) }
            }
        })
    }

    private fun safeStatus(d: IQDevice): String = runCatching { ciq.getDeviceStatus(d).name }.getOrDefault("UNKNOWN")

    private fun target(d: IQDevice, appId: String) = WatchTarget(d.deviceIdentifier.toString(), appId, generation())

    override fun send(target: WatchTarget, message: Map<String, Any>, onStatus: (SendStatus) -> Unit) {
        executor.execute {
            val device = known[target.deviceId]
            if (_state.value != SdkState.Ready || device == null) {
                onStatus(SendStatus.FAILURE_DEVICE_NOT_CONNECTED)
                return@execute
            }
            try {
                ciq.sendMessage(device, IQApp(target.appUuid), message) { _, _, status -> onStatus(map(status)) }
            } catch (e: Exception) {
                Log.w(TAG, "sendMessage failed: ${e.javaClass.simpleName}")
                onStatus(SendStatus.FAILURE_UNKNOWN)
            }
        }
    }

    /** Asks the watch to open WOD Coach; it only shows a prompt on the wrist (REQ-AW03). */
    fun openApp(target: WatchTarget, onResult: (String) -> Unit) {
        executor.execute {
            val device = known[target.deviceId] ?: return@execute onResult("NO_DEVICE")
            runCatching {
                ciq.openApplication(device, IQApp(target.appUuid)) { _, _, status -> onResult(status.name) }
            }.onFailure { onResult("UNKNOWN_FAILURE") }
        }
    }

    private fun upsert(d: WatchDevice) {
        _devices.value = _devices.value.filter { it.id != d.id } + d
    }

    private fun update(id: String, f: (WatchDevice) -> WatchDevice) {
        _devices.value = _devices.value.map { if (it.id == id) f(it) else it }
    }

    companion object {
        private const val TAG = "ConnectIq"

        /** Every SDK status maps to a domain status; the outbox turns it into a delivery state. */
        fun map(s: ConnectIQ.IQMessageStatus): SendStatus = when (s) {
            ConnectIQ.IQMessageStatus.SUCCESS -> SendStatus.SUCCESS
            ConnectIQ.IQMessageStatus.FAILURE_INVALID_FORMAT -> SendStatus.FAILURE_INVALID_FORMAT
            ConnectIQ.IQMessageStatus.FAILURE_MESSAGE_TOO_LARGE -> SendStatus.FAILURE_MESSAGE_TOO_LARGE
            ConnectIQ.IQMessageStatus.FAILURE_UNSUPPORTED_TYPE -> SendStatus.FAILURE_UNSUPPORTED_TYPE
            ConnectIQ.IQMessageStatus.FAILURE_DURING_TRANSFER -> SendStatus.FAILURE_DURING_TRANSFER
            ConnectIQ.IQMessageStatus.FAILURE_INVALID_DEVICE -> SendStatus.FAILURE_INVALID_DEVICE
            ConnectIQ.IQMessageStatus.FAILURE_DEVICE_NOT_CONNECTED -> SendStatus.FAILURE_DEVICE_NOT_CONNECTED
            else -> SendStatus.FAILURE_UNKNOWN
        }
    }
}
