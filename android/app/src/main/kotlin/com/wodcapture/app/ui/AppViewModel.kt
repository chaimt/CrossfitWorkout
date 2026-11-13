package com.wodcapture.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wodcapture.app.container
import com.wodcapture.app.data.AppSettings
import com.wodcapture.app.data.ParseJobEntity
import com.wodcapture.app.data.PlanEntity
import com.wodcapture.app.data.WorkoutEntity
import com.wodcapture.app.image.ImagePreprocessor
import com.wodcapture.app.update.UpdateController
import com.wodcapture.app.work.ParseWorker
import com.wodcapture.domain.DomainJson
import com.wodcapture.domain.manual.ManualBuild
import com.wodcapture.domain.manual.ManualForms
import com.wodcapture.domain.manual.ManualWorkoutBuilder
import com.wodcapture.domain.manual.ManualWorkoutForm
import com.wodcapture.domain.model.MassUnit
import com.wodcapture.domain.model.ParseResult
import com.wodcapture.domain.model.RepMax
import com.wodcapture.domain.model.Workout
import com.wodcapture.domain.model.WorkoutResult
import com.wodcapture.domain.parser.CloudLlmParser
import com.wodcapture.domain.parser.CloudParserConfig
import com.wodcapture.domain.parser.JobState
import com.wodcapture.domain.parser.KeyTestResult
import com.wodcapture.domain.parser.OkHttpTransport
import com.wodcapture.domain.plan.CompileOptions
import com.wodcapture.domain.plan.CompileResult
import com.wodcapture.domain.plan.PlanIdentity
import com.wodcapture.domain.plan.PlanRefresher
import com.wodcapture.domain.plan.PlanRefreshResult
import com.wodcapture.domain.protocol.MessageType
import com.wodcapture.domain.plan.WatchPlan
import com.wodcapture.domain.plan.WireJson
import com.wodcapture.domain.protocol.ProfilePayload
import com.wodcapture.domain.scoring.TopLift
import com.wodcapture.domain.scoring.TopLifts
import com.wodcapture.domain.validation.ValidationReport
import com.wodcapture.domain.validation.WorkoutValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/** Content shared into the app (REQ-AC02/AC03). */
data class SharedContent(val uris: List<Uri> = emptyList(), val text: String? = null)

/** Review-screen derivations: validation plus the exact watch preview (REQ-AE01, AE04). */
data class ReviewState(val report: ValidationReport, val compile: CompileResult)

/** Single app ViewModel; screens are thin (REQ-AP02: UI → domain → data). */
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val c = app.container
    private val dao = c.db.dao()
    private fun nowSec() = System.currentTimeMillis() / 1000

    val library: StateFlow<List<WorkoutEntity>> = c.workouts.library().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val settings: StateFlow<AppSettings> = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val results: StateFlow<List<WorkoutResult>> = dao.observeResults()
        .map { l -> l.mapNotNull { runCatching { DomainJson.lenient.decodeFromString(WorkoutResult.serializer(), it.resultJson) }.getOrNull() } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Heaviest weight per lift across the saved workouts, their results and the manual rep maxes, in the display unit (REQ-AL07). */
    val topLifts: StateFlow<List<TopLift>> = combine(results, library, settings) { r, l, s ->
        // Drafts and unreviewed parses are not the owner's workouts yet (REQ-AE01).
        val workouts = l.filter { it.status == "saved" }.mapNotNull { e ->
            runCatching { DomainJson.decodeWorkout(e.workoutJson) }.getOrNull()?.let { it.copy(createdAt = it.createdAt ?: e.createdAt) }
        }
        TopLifts.compute(workouts, r, c.catalog, s.profile.displayUnit, repMaxes = s.profile.repMaxes)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val outbox = c.outboxState
    val devices = c.ciq.devices
    val sdkState = c.ciq.state
    val notices = c.notices
    val shared = MutableStateFlow<SharedContent?>(null)
    val message = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch { settings.collect { c.onSettingsChanged(it) } }
    }

    fun observe(id: String) = c.workouts.observe(id)
    fun observeJob(draftId: String) = dao.observeLatestJob(draftId)

    /** Stores captured images (pre-processed, REQ-AC05) and/or text as a pending draft (REQ-AX16). */
    fun capture(uris: List<Uri>, text: String?, onDraft: (String) -> Unit) = viewModelScope.launch {
        val id = withContext(Dispatchers.IO) {
            val pre = ImagePreprocessor(getApplication())
            val files = uris.take(4).map { pre.prepare(it).file.absolutePath }
            c.workouts.createDraft(files, text?.takeIf { it.isNotBlank() }, nowSec()).id
        }
        onDraft(id)
    }

    /** Explicit Parse intent with current consent queues a cloud job (REQ-AX16, AX10). */
    fun parse(draftId: String) = viewModelScope.launch(Dispatchers.IO) {
        val s = c.settings.current()
        if (!s.cloudParsing || !s.consentGiven) { message.value = "Cloud parsing is off or not consented"; return@launch }
        if (!c.secrets.hasApiKey()) { message.value = "Add your Anthropic API key in Settings"; return@launch }
        val draft = dao.workout(draftId) ?: return@launch
        val jobId = c.ids.newId()
        dao.upsertJob(ParseJobEntity(jobId, draftId, draft.revision, JobState.QUEUED.name, 0, null, nowSec()))
        c.workouts.setStatus(draft, "parsing")
        ParseWorker.enqueue(getApplication(), jobId, draftId)
    }

    /** Cancel: prevents future sends; a late response is ignored (REQ-AX18). */
    fun cancelParse(job: ParseJobEntity) = viewModelScope.launch(Dispatchers.IO) {
        dao.cancelParse(job.jobId)
    }

    fun giveConsent() = viewModelScope.launch { c.settings.update { it.copy(consentGiven = true) } }

    fun save(entity: WorkoutEntity, edited: Workout) = viewModelScope.launch(Dispatchers.IO) {
        c.workouts.saveRevision(entity, edited, nowSec())
        message.value = "Saved revision ${entity.revision + 1}"
    }

    fun applyAlternative(entity: WorkoutEntity) = viewModelScope.launch(Dispatchers.IO) {
        val alt = entity.alternativeJson?.let(DomainJson::decodeParseResult) ?: return@launch
        c.workouts.saveRevision(entity.copy(alternativeJson = null, parseJson = DomainJson.encodeParseResult(alt)), alt.workout.copy(id = entity.id), nowSec())
    }

    fun parseResultOf(entity: WorkoutEntity): ParseResult? = entity.parseJson?.let { runCatching { DomainJson.decodeParseResult(it) }.getOrNull() }

    fun review(w: Workout, levels: Map<String, String>): ReviewState {
        val s = settings.value
        val report = WorkoutValidator.validate(w)
        val compile = c.compiler.compile(w, s.profile, PlanIdentity("00000000-0000-4000-8000-000000000000", 1), options(levels))
        return ReviewState(report, compile)
    }

    private fun options(levels: Map<String, String>) = CompileOptions(
        levels = levels, defaultDate = LocalDate.now().toString(), defaultTimeZone = ZoneId.systemDefault().id,
    )

    /**
     * "Send to watch" (REQ-AE05): save first, compile with the chosen levels, keep a stable plan
     * lineage per workout + target and queue `plan.put`; then prompt to open WOD Coach (REQ-AW03).
     */
    fun sendToWatch(entity: WorkoutEntity, edited: Workout, levels: Map<String, String>) = viewModelScope.launch(Dispatchers.IO) {
        val saved = if (DomainJson.encodeWorkout(edited) != entity.workoutJson) c.workouts.saveRevision(entity, edited, nowSec()) else entity
        val workout = DomainJson.decodeWorkout(saved.workoutJson)
        val target = c.currentTarget() ?: run { message.value = "No watch selected (Device screen)"; return@launch }
        c.sync.execute {
            val stored = dao.plan(workout.id, target.key)
            val latest = stored?.let { c.engine.outbox.stateOf(target, it.planId) }
            val prior = stored?.reconciled(latest)
            val planId = prior?.planId ?: c.ids.newId()
            val first = c.compiler.compile(workout, settings.value.profile, PlanIdentity(planId, prior?.revision ?: 1), options(levels))
            if (first !is CompileResult.Success) { message.value = "Cannot send: " + (first as CompileResult.Failure).errors.joinToString { it.message }; return@execute }
            val firstJson = WireJson.encodeToString(WatchPlan.serializer(), first.plan)
            val unchanged = prior != null && prior.planJson == firstJson
            val plan = if (unchanged || prior == null) first.plan else first.plan.copy(rev = prior.revision + 1)
            val json = WireJson.encodeToString(WatchPlan.serializer(), plan)
            dao.upsertPlan(PlanEntity(workout.id, target.key, planId, plan.rev, workout.revision, json))
            if (unchanged) c.engine.requestSync(target) else c.engine.sendPlan(target, plan)
            c.ciq.openApp(target) { status -> if (status == "PROMPT_SHOWN_ON_DEVICE") message.value = "Confirm on your watch to open WOD Coach" }
            message.value = "Queued for the watch (${first.encodedBytes} bytes)"
        }
    }

    /** Profile defaults to the watch (REQ-AG02, protocol §6). */
    fun sendProfile() = viewModelScope.launch(Dispatchers.IO) {
        val s = c.settings.current()
        val target = c.currentTarget() ?: return@launch
        val p = ProfilePayload.toJson(s.profile.watch, if (s.profile.displayUnit.wire == "lb") "lb" else "kg", s.profile.defaultRestSec)
        c.sync.execute { runCatching { c.engine.sendProfile(target, PROFILE_ID, s.profileRevision, p) } }
    }

    fun requestSync() {
        c.sync.execute {
            c.clearNotices()
            val target = c.currentTarget() ?: run { c.notice("Sync not started: no watch selected"); return@execute }
            c.notice("Sync started")
            val refresher = PlanRefresher(c.compiler)
            dao.plansForTarget(target.key).forEach { stored ->
                runCatching {
                    val latest = c.engine.outbox.stateOf(target, stored.planId)
                    val prior = stored.reconciled(latest)
                    // Removal is deliberate: Sync must not restore a cleared/archived plan.
                    if (prior.planJson.isBlank()) return@runCatching
                    val entity = dao.workout(prior.workoutId) ?: return@runCatching
                    if (entity.archived || entity.status !in setOf("saved", "ready")) return@runCatching
                    // Use a newer durable queued payload if a prior send was interrupted
                    // between enqueueing and saving its lineage row.
                    val previous = if (latest?.type == MessageType.PLAN_PUT && latest.revision > prior.revision) {
                        WireJson.decodeFromJsonElement(WatchPlan.serializer(), latest.body.getValue("p"))
                    } else WireJson.decodeFromString(WatchPlan.serializer(), prior.planJson)
                    val workout = DomainJson.decodeWorkout(entity.workoutJson)
                    val today = LocalDate.now(ZoneId.of(previous.tz))
                    when (val result = refresher.refresh(previous, workout, settings.value.profile, today)) {
                        is PlanRefreshResult.Updated -> {
                            c.engine.sendPlan(target, result.plan)
                            dao.upsertPlan(prior.copy(revision = result.plan.rev, workoutRevision = workout.revision,
                                planJson = WireJson.encodeToString(WatchPlan.serializer(), result.plan)))
                        }
                        is PlanRefreshResult.Rejected -> c.notice("Cannot update ${entity.title}: " + result.errors.joinToString { it.message })
                        PlanRefreshResult.Unchanged -> {
                            // Repair an interrupted Send that saved the lineage before enqueueing.
                            if (latest == null || latest.revision < previous.rev) c.engine.sendPlan(target, previous)
                            else c.notice("Plan unchanged: ${previous.ti} (rev ${previous.rev})")
                        }
                        PlanRefreshResult.Historical -> c.notice("Skipped past plan: ${previous.ti} (${previous.date})")
                    }
                }.onFailure { c.notice("Cannot update ${stored.planId.take(8)}: ${it.message ?: "invalid saved plan"}") }
            }
            c.engine.requestSync(target)
        }
    }

    fun refreshDevices() = c.ciq.refreshDevices()
    fun openOnWatch() { c.currentTarget()?.let { t -> c.ciq.openApp(t) { message.value = "Open app: $it" } } }

    /** Delete = archive the library entry and queue `plan.del` so sync removes it from the watch too. */
    fun archive(id: String) = viewModelScope.launch(Dispatchers.IO) {
        val target = c.currentTarget()
        val prior = target?.let { dao.plan(id, it.key) }
        c.workouts.archive(id)
        if (target != null && prior != null) {
            c.sync.execute {
                val deletion = (dao.plan(id, target.key) ?: prior).deletion()
                val existing = c.engine.outbox.stateOf(target, deletion.planId)
                if (existing?.type != com.wodcapture.domain.protocol.MessageType.PLAN_DEL || existing.revision != deletion.revision) {
                    c.engine.deletePlan(target, deletion.planId, deletion.revision)
                }
                dao.upsertPlan(deletion)
            }
        }
    }

    /** Remove every sent plan from the watch (queues one `plan.del` per known plan lineage). */
    fun clearWatch() = viewModelScope.launch(Dispatchers.IO) {
        val target = c.currentTarget() ?: run { message.value = "No watch selected (Device screen)"; return@launch }
        val plans = dao.plansForTarget(target.key)
        if (plans.isEmpty()) { message.value = "Nothing on watch to remove"; return@launch }
        c.sync.execute {
            c.engine.requestSync(target)
            plans.forEach {
                val deletion = (dao.plan(it.workoutId, target.key) ?: it).deletion()
                val existing = c.engine.outbox.stateOf(target, deletion.planId)
                if (existing?.type != com.wodcapture.domain.protocol.MessageType.PLAN_DEL || existing.revision != deletion.revision) {
                    c.engine.deletePlan(target, deletion.planId, deletion.revision)
                }
                dao.upsertPlan(deletion)
            }
        }
        message.value = "Removing ${plans.size} workout${if (plans.size == 1) "" else "s"} from watch"
    }
    fun duplicate(id: String, onDone: (String) -> Unit) = viewModelScope.launch {
        val e = withContext(Dispatchers.IO) { c.workouts.duplicate(id, nowSec()) }
        e?.let { onDone(it.id) }
    }

    /** The open "New workout" form (REQ-AC09); kept here so it survives rotation and navigation. */
    val manualForm = MutableStateFlow<ManualWorkoutForm?>(null)

    /** In-app update check, download and install (REQ-AP07). */
    val updates = UpdateController(app, c.updater, viewModelScope)
    private val manualBuilder by lazy { ManualWorkoutBuilder(c.ids) }

    // Declared after manualForm: the collector starts immediately and must see it initialised.
    init {
        // Weights in the form follow Settings → Units, also when it changes while the form is open.
        viewModelScope.launch {
            settings.map { loadUnitAndStep(it) }.distinctUntilChanged().collect { (unit, step) ->
                manualForm.update { f -> f?.copy(loadUnit = unit, loadStep = step) }
            }
        }
    }

    /** Starts a fresh guided form: today, in the phone's time zone, one For-time part, Settings unit. */
    fun startManual() {
        val (unit, step) = loadUnitAndStep(settings.value)
        manualForm.value = ManualForms.blank(c.ids, LocalDate.now().toString(), ZoneId.systemDefault().id, unit, step)
    }

    /** Settings → weight unit and its smallest total increment (rounding of computed weights). */
    private fun loadUnitAndStep(s: AppSettings): Pair<MassUnit, Double> {
        val p = s.profile
        return p.displayUnit to if (p.displayUnit == MassUnit.LB) p.barIncrementLb else p.barIncrementKg
    }

    fun updateManual(f: (ManualWorkoutForm) -> ManualWorkoutForm) {
        manualForm.value = manualForm.value?.let(f)
    }

    /** Canonical workout + live validation for the form, rebuilt on every edit (REQ-AX05). */
    fun buildManual(form: ManualWorkoutForm): ManualBuild = manualBuilder.build(form, nowSec())

    /**
     * Create (REQ-AC09): stores the built workout as a draft and hands its ID to Review; nothing is
     * sent from here. Refuses forms that still have blocking findings.
     */
    fun createManual(onCreated: (String) -> Unit) = viewModelScope.launch {
        val form = manualForm.value ?: return@launch
        val built = buildManual(form)
        if (!built.canCreate) { message.value = "Fix the highlighted fields first"; return@launch }
        val id = withContext(Dispatchers.IO) { c.workouts.createManual(built.workout, nowSec()).id }
        manualForm.value = null
        onCreated(id)
    }

    /** "Save & log result" (REQ-AC09, REQ-AL03): saves the reviewed workout, then opens manual entry. */
    fun saveAndLog(entity: WorkoutEntity, edited: Workout, onSaved: (String) -> Unit) = viewModelScope.launch {
        withContext(Dispatchers.IO) { c.workouts.saveRevision(entity, edited, nowSec()) }
        onSaved(entity.id)
    }

    fun saveManualResult(r: WorkoutResult) = viewModelScope.launch(Dispatchers.IO) { c.workouts.saveManualResult(r); message.value = "Result saved" }
    fun deleteResult(id: String) = viewModelScope.launch(Dispatchers.IO) { c.workouts.deleteResult(id) }

    fun updateSettings(f: (AppSettings) -> AppSettings) = viewModelScope.launch { c.settings.update(f) }

    /** A lift entered by hand on the 1RM screen; stored as a profile rep max so % 1RM loads use it too (REQ-AL07, DM10). */
    fun addRepMax(rm: RepMax) = updateRepMaxes { TopLifts.withEntry(it, rm) }.also { message.value = "Lift saved" }
    fun removeRepMax(lift: TopLift) = updateRepMaxes { TopLifts.withoutEntry(it, lift) }

    private fun updateRepMaxes(f: (List<RepMax>) -> List<RepMax>) = updateSettings {
        it.copy(profile = it.profile.copy(repMaxes = f(it.profile.repMaxes)), profileRevision = it.profileRevision + 1)
    }

    fun saveApiKey(key: String) = viewModelScope.launch(Dispatchers.IO) { c.secrets.saveApiKey(key.trim()); message.value = "API key stored encrypted" }
    fun hasApiKey() = c.secrets.hasApiKey()

    fun testKey() = viewModelScope.launch(Dispatchers.IO) {
        val key = c.secrets.apiKey() ?: run { message.value = "No API key stored"; return@launch }
        val r = CloudLlmParser(CloudParserConfig(apiKey = key, model = settings.value.model, effort = settings.value.effort), c.catalog, OkHttpTransport(30)).testKey()
        message.value = when (r) {
            KeyTestResult.Ok -> "Key works"
            KeyTestResult.InvalidKey -> "Key rejected (401)"
            KeyTestResult.UnknownModel -> "Key OK but model ${settings.value.model} not found"
            is KeyTestResult.Error -> "Test failed: ${r.message}"
        }
    }

    fun deleteAll() = c.deleteAll { message.value = "All local data deleted; new pairing generation started" }

    fun newId(): String = c.ids.newId()

    val catalog get() = c.catalog

    companion object {
        /** Fixed object id of the profile singleton on the wire (protocol §6). */
        const val PROFILE_ID = "00000000-0000-4000-8000-000000000000"
    }
}
