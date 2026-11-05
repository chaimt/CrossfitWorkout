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
import com.wodcapture.app.work.ParseWorker
import com.wodcapture.domain.DomainJson
import com.wodcapture.domain.manual.ManualBuild
import com.wodcapture.domain.manual.ManualForms
import com.wodcapture.domain.manual.ManualWorkoutBuilder
import com.wodcapture.domain.manual.ManualWorkoutForm
import com.wodcapture.domain.model.ParseResult
import com.wodcapture.domain.model.SexSelection
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
import com.wodcapture.domain.plan.WatchPlan
import com.wodcapture.domain.plan.WireJson
import com.wodcapture.domain.protocol.ProfilePayload
import com.wodcapture.domain.validation.ValidationReport
import com.wodcapture.domain.validation.WorkoutValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
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
        dao.upsertJob(job.copy(state = JobState.CANCELLED.name))
        dao.workout(job.draftId)?.let { c.workouts.setStatus(it, "draft") }
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

    fun review(w: Workout, levels: Map<String, String>, sex: SexSelection?): ReviewState {
        val s = settings.value
        val report = WorkoutValidator.validate(w)
        val compile = c.compiler.compile(w, s.profile, PlanIdentity("00000000-0000-4000-8000-000000000000", 1), options(levels, sex))
        return ReviewState(report, compile)
    }

    private fun options(levels: Map<String, String>, sex: SexSelection?) = CompileOptions(
        levels = levels, sexOverride = sex, defaultDate = LocalDate.now().toString(), defaultTimeZone = ZoneId.systemDefault().id,
    )

    /**
     * "Send to watch" (REQ-AE05): save first, compile with the chosen levels, keep a stable plan
     * lineage per workout + target and queue `plan.put`; then prompt to open WOD Coach (REQ-AW03).
     */
    fun sendToWatch(entity: WorkoutEntity, edited: Workout, levels: Map<String, String>, sex: SexSelection?) = viewModelScope.launch(Dispatchers.IO) {
        val saved = if (DomainJson.encodeWorkout(edited) != entity.workoutJson) c.workouts.saveRevision(entity, edited, nowSec()) else entity
        val workout = DomainJson.decodeWorkout(saved.workoutJson)
        val target = c.currentTarget() ?: run { message.value = "No watch selected (Device screen)"; return@launch }
        val prior = dao.plan(workout.id, target.key)
        val planId = prior?.planId ?: c.ids.newId()
        val first = c.compiler.compile(workout, settings.value.profile, PlanIdentity(planId, prior?.revision ?: 1), options(levels, sex))
        if (first !is CompileResult.Success) { message.value = "Cannot send: " + (first as CompileResult.Failure).errors.joinToString { it.message }; return@launch }
        val firstJson = WireJson.encodeToString(WatchPlan.serializer(), first.plan)
        val unchanged = prior != null && prior.planJson == firstJson
        val plan = if (unchanged || prior == null) first.plan else first.plan.copy(rev = prior.revision + 1)
        val json = WireJson.encodeToString(WatchPlan.serializer(), plan)
        dao.upsertPlan(PlanEntity(workout.id, target.key, planId, plan.rev, workout.revision, json))
        c.sync.execute {
            if (unchanged) c.engine.requestSync(target) else c.engine.sendPlan(target, plan)
        }
        c.ciq.openApp(target) { status -> if (status == "PROMPT_SHOWN_ON_DEVICE") message.value = "Confirm on your watch to open WOD Coach" }
        message.value = "Queued for the watch (${first.encodedBytes} bytes)"
    }

    /** Profile defaults to the watch (REQ-AG02, protocol §6). */
    fun sendProfile() = viewModelScope.launch(Dispatchers.IO) {
        val s = c.settings.current()
        val target = c.currentTarget() ?: return@launch
        val p = ProfilePayload.toJson(s.profile.watch, if (s.profile.displayUnit.wire == "lb") "lb" else "kg", s.profile.defaultRestSec)
        c.sync.execute { runCatching { c.engine.sendProfile(target, PROFILE_ID, s.profileRevision, p) } }
    }

    fun requestSync() {
        val t = c.currentTarget() ?: return
        c.sync.execute { c.engine.requestSync(t) }
    }

    fun refreshDevices() = c.ciq.refreshDevices()
    fun openOnWatch() { c.currentTarget()?.let { t -> c.ciq.openApp(t) { message.value = "Open app: $it" } } }

    fun archive(id: String) = viewModelScope.launch(Dispatchers.IO) { c.workouts.archive(id) }
    fun duplicate(id: String, onDone: (String) -> Unit) = viewModelScope.launch {
        val e = withContext(Dispatchers.IO) { c.workouts.duplicate(id, nowSec()) }
        e?.let { onDone(it.id) }
    }

    /** The open "New workout" form (REQ-AC09); kept here so it survives rotation and navigation. */
    val manualForm = MutableStateFlow<ManualWorkoutForm?>(null)
    private val manualBuilder by lazy { ManualWorkoutBuilder(c.ids) }

    // Declared after manualForm: the collector starts immediately and must see it initialised.
    init {
        // Weights in the form follow Settings → Units, also when it changes while the form is open.
        viewModelScope.launch {
            settings.map { it.profile.displayUnit }.distinctUntilChanged().collect { unit ->
                manualForm.update { f -> f?.copy(loadUnit = unit) }
            }
        }
    }

    /** Starts a fresh guided form: today, in the phone's time zone, one For-time part, Settings unit. */
    fun startManual() {
        manualForm.value = ManualForms.blank(c.ids, LocalDate.now().toString(), ZoneId.systemDefault().id, settings.value.profile.displayUnit)
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

    fun saveApiKey(key: String) = viewModelScope.launch(Dispatchers.IO) { c.secrets.saveApiKey(key.trim()); message.value = "API key stored encrypted" }
    fun hasApiKey() = c.secrets.hasApiKey()

    fun testKey() = viewModelScope.launch(Dispatchers.IO) {
        val key = c.secrets.apiKey() ?: run { message.value = "No API key stored"; return@launch }
        val r = CloudLlmParser(CloudParserConfig(apiKey = key, model = settings.value.model), c.catalog, OkHttpTransport(30)).testKey()
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
