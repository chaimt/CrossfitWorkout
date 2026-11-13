package com.wodcapture.app

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.wodcapture.app.ciq.ConnectIqAdapter
import com.wodcapture.app.data.AppSettings
import com.wodcapture.app.data.OutboxStore
import com.wodcapture.app.data.planSyncNotices
import com.wodcapture.app.data.syncLogTime
import com.wodcapture.app.data.RoomChunkStore
import com.wodcapture.app.data.RoomResultRepository
import com.wodcapture.app.data.RoomSessionRepository
import com.wodcapture.app.data.SecretStore
import com.wodcapture.app.data.SettingsStore
import com.wodcapture.app.data.WodDatabase
import com.wodcapture.app.data.WorkoutRepository
import com.wodcapture.app.update.AppUpdater
import com.wodcapture.domain.IdSource
import com.wodcapture.domain.catalog.MovementCatalog
import com.wodcapture.domain.plan.PlanCompiler
import com.wodcapture.domain.protocol.ChunkStager
import com.wodcapture.domain.protocol.OutboxEntry
import com.wodcapture.domain.protocol.Outbox
import com.wodcapture.domain.protocol.ResultIngest
import com.wodcapture.domain.protocol.SessionIngest
import com.wodcapture.domain.protocol.SyncEngine
import com.wodcapture.domain.protocol.SyncListener
import com.wodcapture.domain.protocol.Hello
import com.wodcapture.domain.protocol.WatchTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Application: owns the [AppContainer]. No analytics or crash SDKs (REQ-NF06). */
class WodApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

val Context.container: AppContainer get() = (applicationContext as WodApp).container

/**
 * Manual dependency container (UI → domain → data, REQ-AP02). The sync engine runs on one serial
 * executor because it is not thread-safe; outbox state is persisted after every change.
 */
class AppContainer(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val db: WodDatabase = Room.databaseBuilder(context, WodDatabase::class.java, "wod.db").build()
    val settings = SettingsStore(context)
    val secrets = SecretStore(context)
    val catalog: MovementCatalog by lazy { MovementCatalog.fromClasspath() }
    val compiler by lazy { PlanCompiler(catalog) }
    val workouts = WorkoutRepository(db)
    /** In-app updates from GitHub Releases (REQ-AP07). */
    val updater by lazy { AppUpdater(context.applicationContext) }
    val ids: IdSource = IdSource.RANDOM

    /** Serial executor for the sync engine and its Room writes. */
    val sync: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "sync") }

    private val initial: AppSettings = runBlocking { settings.current() }
    @Volatile var generation: Int = initial.generation
        private set

    private val _outbox = MutableStateFlow<List<OutboxEntry>>(emptyList())
    val outboxState: StateFlow<List<OutboxEntry>> = _outbox
    private val _messages = MutableStateFlow<List<String>>(emptyList())
    /** Recent protocol notices for the device screen. */
    val notices: StateFlow<List<String>> = _messages

    val ciq = ConnectIqAdapter(context.applicationContext, initial.tethered, { generation }) { target, map ->
        sync.execute { engine.onMessage(target, map) }
    }

    private val outboxStore = OutboxStore(db)
    private val results = RoomResultRepository(db)
    lateinit var engine: SyncEngine
        private set

    init {
        sync.execute {
            val ingest = ResultIngest(results) { generation }
            engine = SyncEngine(
                transport = ciq,
                outbox = Outbox(outboxStore.load()),
                results = ingest,
                sessions = SessionIngest(RoomSessionRepository(db)) { generation },
                chunks = ChunkStager(RoomChunkStore(db), ingest) { results.dedupe(it) },
                ids = ids,
                clockMs = System::currentTimeMillis,
                callbackExecutor = sync,
                listener = object : SyncListener {
                    override fun onOutboxChanged(outbox: List<OutboxEntry>) {
                        outboxStore.save(outbox)
                        planSyncNotices(_outbox.value, outbox).forEach(::notice)
                        _outbox.value = outbox
                    }

                    override fun onHello(target: WatchTarget, hello: Hello) {
                        ciq.recordAppVersion(target, hello.app)
                    }

                    override fun onProtocolError(target: WatchTarget, reason: String, detail: String) {
                        notice("$reason: $detail")
                    }
                },
            )
            _outbox.value = engine.outbox.snapshot()
        }
        ciq.onConnected = { t -> sync.execute { engine.onDeviceConnected(t) } }
        // Periodic retry checks while the app is alive (ack timeout / backoff, REQ-SY13).
        sync.scheduleWithFixedDelay({ runCatching { currentTarget()?.let { engine.tick(it) } } }, 5, 5, TimeUnit.SECONDS)
        ciq.start()
    }

    @Volatile private var selected: AppSettings = initial

    /** The athlete's chosen target (device + production or beta UUID, REQ-AW05). */
    fun currentTarget(): WatchTarget? {
        val s = selected
        val device = s.selectedDeviceId ?: ciq.devices.value.firstOrNull()?.id ?: return null
        val app = if (s.useBetaApp) com.wodcapture.domain.protocol.Protocol.APP_UUID_BETA else com.wodcapture.domain.protocol.Protocol.APP_UUID_PRODUCTION
        return WatchTarget(device, app, generation)
    }

    fun onSettingsChanged(s: AppSettings) {
        selected = s
        generation = s.generation
    }

    fun clearNotices() {
        _messages.value = emptyList()
    }

    fun notice(text: String) {
        val timestamp = syncLogTime(System.currentTimeMillis())
        val lines = text.lineSequence().map { "$timestamp · $it" }.toList()
        _messages.value = (lines + _messages.value).take(20)
    }

    /**
     * Delete all (REQ-DM27, AG04): cancel parse jobs, drop queues, wipe local data and start a new
     * pairing generation so old device traffic is quarantined, never auto-imported.
     */
    fun deleteAll(onDone: () -> Unit) {
        scope.launch {
            androidx.work.WorkManager.getInstance(appContext).cancelAllWork()
            val dao = db.dao()
            db.runInTransaction {
                dao.cancelAllJobs(); dao.wipeJobs(); dao.wipeWorkouts(); dao.wipeRevisions(); dao.wipeResults()
                dao.wipeSessions(); dao.wipePlans(); dao.wipeChunks(); dao.clearOutbox()
            }
            settings.update { it.copy(generation = it.generation + 1, profileRevision = it.profileRevision + 1) }
            secrets.clear()
            appContext.filesDir.resolve("captures").deleteRecursively()
            val next = settings.current()
            onSettingsChanged(next)
            sync.execute { engine.outbox.dropGeneration(next.generation); outboxStore.save(engine.outbox.snapshot()); _outbox.value = engine.outbox.snapshot() }
            onDone()
        }
    }

    private val appContext = context.applicationContext
}
