//
// Session.mc
//
// One run of a plan's selected parts (REQ-WR07 state machine):
//   preview -> prep -> run (work / prescribed rest, paused) -> part_result
//   -> [part rest] -> transition wait -> run next ... -> final -> finalized
//
// The session owns the frozen plan snapshot (REQ-SY11: inbox updates never
// alter an active timer), the SessionClock, the current part's runner
// (MetconRun / StrengthRun), FIT recording and checkpoints. Views only
// read state and forward button events here.
//
// Durability (REQ-WO01, REQ-NF01): every accepted round / set / finish is
// checkpointed BEFORE the UI confirms it; while running, a timer-only
// checkpoint is written at least every 5 s. A refused write freezes the
// logical timer (pause) and reports the event as not accepted (REQ-WO05).
// Part results are finalized into History + Outbox on Save; interrupted
// sessions are finalized by Recovery (Recovery.mc).
//
import Toybox.Activity;
import Toybox.Lang;
import Toybox.Sensor;
import Toybox.System;
import Toybox.Time;
import Toybox.Timer;
import Toybox.WatchUi;

// What the stage view should show.
enum {
    STAGE_RUN = 0,
    STAGE_RESULT = 1,
    STAGE_TRANSITION = 2,
    STAGE_SUMMARY = 3
}

class Session {

    const TICK_MS = 100;
    const CHECKPOINT_MS = 5000;

    var kv as Kv;
    var plan as Dictionary;
    var sid as String;
    var restartOf as String? = null;
    //! Indices into plan["p"] of the parts to run, in order.
    var selected as Array<Number>;
    //! Position in `selected` of the current part.
    var cur as Number = 0;
    var clock as SessionClock = new SessionClock();
    var recorder as Recorder;
    var settings as Dictionary;
    //! Per-part pre-start edits ({partIndex => {key => value}}, REQ-WN03).
    var edits as Dictionary;

    // Current part.
    var part as Dictionary = {};
    var prog as Program? = null;
    var metcon as MetconRun? = null;
    var strength as StrengthRun? = null;
    var seg as SegState = new SegState();
    var partStarted as Boolean = false;
    var partT0 as Number = 0;
    var partEl0 as Number = 0;
    var partAct0 as Number = 0;
    var resultId as String = "";
    var partHr as HrStats = new HrStats();
    var partCal0 as Number? = null;
    var timerEnded as Boolean = false;

    // Result entry draft (part_result state).
    var outcome as PartOutcome? = null;

    // Session-wide.
    var t0 as Number = 0;
    var entries as Array = [];
    var sessionHr as HrStats = new HrStats();
    var finalized as Boolean = false;
    var summary as Dictionary? = null;
    var message as String? = null;
    var frozen as Boolean = false;
    private var _stopAfterPart as Boolean = false;

    private var _timer as Timer.Timer? = null;
    private var _prevPhase as Number = -1;
    private var _prevSeg as Number = -1;
    private var _lastBeep as Number = 0;
    private var _lastSecond as Number = -1;
    private var _lastCheckpoint as Number = 0;
    private var _lastSample as Number = 0;
    private var _prevRunMs as Number = 0;
    private var _listener as WatchUi.View? = null;

    //! `selected` = part indices to run; `edits` per part index.
    function initialize(store as Kv, snapshot as Dictionary, selection as Array<Number>,
            partEdits as Dictionary, restart as String?) {
        kv = store;
        plan = snapshot;
        selected = selection;
        edits = partEdits;
        restartOf = restart;
        sid = Uuid.v4();
        settings = Settings.snapshot();
        Alerts.configure(settings["snd"] as Boolean, settings["vib"] as Boolean);
        recorder = new Recorder(settings["rec"] as Boolean);
    }

    //! REQ-WO05 admission. Null when the session can start, else a reason.
    static function admit(kv as Kv, plan as Dictionary, count as Number) as String? {
        if (Checkpoint.exists(kv)) {
            return "Finish the open session first";
        }
        if (!Budget.admit(kv, WireSize.size(plan), count)) {
            return "Storage full: sync results";
        }
        return null;
    }

    function parts() as Array {
        return plan["p"] as Array;
    }

    function partAt(i as Number) as Dictionary {
        return parts()[selected[i]] as Dictionary;
    }

    function hasNext() as Boolean {
        return cur + 1 < selected.size();
    }

    function nextPart() as Dictionary? {
        return hasNext() ? partAt(cur + 1) : null;
    }

    function setListener(view as WatchUi.View?) as Void {
        _listener = view;
    }

    function stage() as Number {
        var s = clock.state;
        if (s == CS_ENTRY) {
            return STAGE_RESULT;
        }
        if (s == CS_PART_REST || s == CS_WAIT) {
            return STAGE_TRANSITION;
        }
        if (s == CS_FINAL) {
            return STAGE_SUMMARY;
        }
        if (s == CS_PAUSED) {
            return clock.resumeState == CS_PART_REST ? STAGE_TRANSITION : STAGE_RUN;
        }
        return STAGE_RUN;
    }

    function now() as Number {
        return System.getTimer();
    }

    // ------------------------------------------------------------------
    // Lifecycle

    //! Start: frozen snapshot, HR sensor, prep countdown (once per session).
    function begin() as Boolean {
        if (!kv.put("snap", plan)) {
            message = "Storage full";
            return false;
        }
        if (Toybox has :Sensor && Sensor has :setEnabledSensors) {
            Sensor.setEnabledSensors([Sensor.SENSOR_HEARTRATE] as Array<Sensor.SensorType>);
        }
        _loadPart();
        var prep = U.asNum(settings["prep"]);
        var t = now();
        if (prep != null && prep > 0) {
            clock.enter(CS_PREP, t);
        } else {
            _startWork(t);
        }
        _checkpoint(t);
        _startTimer();
        return true;
    }

    function prepMs() as Number {
        var prep = U.asNum(settings["prep"]);
        return prep == null ? 0 : prep * 1000;
    }

    //! Sets up the runner for selected[cur] (edits applied to the snapshot).
    function _loadPart() as Void {
        part = partAt(cur);
        var mode = U.str(part, "mode");
        metcon = null;
        strength = null;
        prog = null;
        partStarted = false;
        timerEnded = false;
        outcome = null;
        resultId = Uuid.v4();
        _prevPhase = -1;
        _prevSeg = -1;
        if (PlanCodec.isStrength(mode)) {
            var u = U.str(plan, "u");
            strength = new StrengthRun(part, u == null ? Settings.unit() : u);
        } else {
            var e = edits.get(selected[cur]);
            var segs = e instanceof Dictionary ? Edits.apply(part, e) : U.arr(part, "seg");
            var p = new Program(segs == null ? [] : segs);
            prog = p;
            var m = new MetconRun(part, p);
            m.maxEvents = ResultBuilder.eventLimit(plan, part);
            metcon = m;
        }
    }

    function _startWork(t as Number) as Void {
        clock.startPart();
        clock.enter(CS_RUN, t);
        partStarted = true;
        partT0 = Time.now().value();
        if (t0 == 0) {
            t0 = partT0;
        }
        partEl0 = clock.wallAt(t);
        partAct0 = clock.activeAt(t);
        partHr = new HrStats();
        partCal0 = _calories();
        _prevRunMs = 0;
        if (!recorder.active()) {
            var modes = [];
            for (var i = 0; i < selected.size(); i++) {
                modes.add(U.str(partAt(i), "mode"));
            }
            var sport = FitSport.choose(modes);
            var title = U.str(plan, "ti");
            recorder.begin(title == null ? "WOD" : title, sport[0], sport[1]);
        } else {
            recorder.resume();
            recorder.lap();
        }
        Alerts.work();
    }

    function _startTimer() as Void {
        if (_timer == null) {
            _timer = new Timer.Timer();
            (_timer as Timer.Timer).start(method(:onTick), TICK_MS, true);
        }
    }

    function _stopTimer() as Void {
        if (_timer != null) {
            (_timer as Timer.Timer).stop();
            _timer = null;
        }
    }

    //! 100 ms tick: cues fire within the same second as the transition
    //! (REQ-NF02); the screen redraws only when the shown second changes.
    function onTick() as Void {
        var t = now();
        var s = clock.state;
        if (s == CS_PREP) {
            var left = prepMs() - clock.prepAt(t);
            var beep = Fmt.countdownSeconds(left);
            if (beep >= 1 && beep <= 3 && beep != _lastBeep) {
                Alerts.tick();
            }
            _lastBeep = beep;
            if (left <= 0) {
                _startWork(t);
                _checkpoint(t);
            }
        } else if (s == CS_RUN) {
            _tickRun(t);
        } else if (s == CS_PART_REST) {
            var ra = U.num(partAt(cur), "ra");
            if (ra == null || clock.inStateAt(t) >= ra * 1000) {
                clock.enter(CS_WAIT, t);
                recorder.hold();
                Alerts.work();
                _checkpoint(t);
                _refresh(true);
            }
        }
        if (clock.isRunning() && t - _lastSample >= 1000) {
            _lastSample = t;
            var hr = _heartRate();
            partHr.add(hr);
            sessionHr.add(hr);
        }
        if (clock.isRunning() && t - _lastCheckpoint >= CHECKPOINT_MS) {
            _checkpoint(t);
        }
        var second = clock.partRunAt(t) / 1000 + clock.inStateAt(t) / 1000;
        if (second != _lastSecond) {
            _lastSecond = second;
            _refresh(false);
        }
    }

    function _tickRun(t as Number) as Void {
        var runMs = clock.partRunAt(t);
        var m = metcon;
        if (m != null && prog != null) {
            Engine.stateInto(seg, prog as Program, 0, runMs);
            var ev = Engine.transition(_prevPhase, _prevSeg, seg);
            if (ev == EV_DONE) {
                _prevPhase = seg.phase;
                _prevSeg = seg.seg;
                if ((prog as Program).isOpen() && (prog as Program).capMs > 0) {
                    m.markCap((prog as Program).capMs);
                    Alerts.timeCap();
                } else {
                    Alerts.done();
                }
                timerEnded = true;
                _endPart(t);
                return;
            }
            if (ev == EV_WORK && _prevPhase != -1) {
                recorder.lap();
                Alerts.work();
            } else if (ev == EV_REST) {
                recorder.lap();
                Alerts.rest();
            }
            var beep = Engine.countdownBeep(seg);
            if (beep != 0 && beep != _lastBeep && ev == EV_NONE) {
                Alerts.tick();
            }
            _lastBeep = beep;
            _prevPhase = seg.phase;
            _prevSeg = seg.seg;
        }
        var sr = strength;
        if (sr != null) {
            var cue = sr.restCue(_prevRunMs, runMs);
            if (cue == RC_TEN) {
                Alerts.restWarning();
            } else if (cue == RC_ZERO) {
                Alerts.restOver();
                _refresh(true);
            }
        }
        _prevRunMs = runMs;
    }

    function _refresh(force as Boolean) as Void {
        WatchUi.requestUpdate();
    }

    // ------------------------------------------------------------------
    // Controls (called by the delegates)

    function isPaused() as Boolean {
        return clock.state == CS_PAUSED;
    }

    function pause() as Void {
        if (!clock.isRunning()) {
            return;
        }
        var t = now();
        clock.enter(CS_PAUSED, t);
        recorder.hold();
        _checkpoint(t);
        WatchUi.requestUpdate();
    }

    function resume() as Void {
        if (clock.state != CS_PAUSED || frozen) {
            return;
        }
        var t = now();
        clock.resume(t);
        if (clock.state == CS_RUN || clock.state == CS_PART_REST) {
            recorder.resume();
        }
        _checkpoint(t);
        WatchUi.requestUpdate();
    }

    //! LAP / tap in a metcon. Returns LAP_OK / LAP_IGNORED / LAP_LIMIT.
    function lap() as Number {
        var m = metcon;
        if (m == null || clock.state != CS_RUN) {
            return LAP_IGNORED;
        }
        var t = now();
        var r = m.lap(clock.partRunAt(t), seg.round);
        if (r == LAP_OK) {
            if (!_checkpoint(t)) {
                // Not durable -> not accepted (REQ-WO05).
                m.restore(m.events.slice(0, m.events.size() - 1));
                _storageFailure();
                return LAP_LIMIT;
            }
            recorder.lap();
            Alerts.round();
        } else if (r == LAP_LIMIT) {
            message = "Event limit: finish part";
            Alerts.refused();
        }
        WatchUi.requestUpdate();
        return r;
    }

    function undoLap() as Boolean {
        var m = metcon;
        if (m == null) {
            return false;
        }
        var t = now();
        var ok = m.undo(clock.partRunAt(t));
        if (ok) {
            _checkpoint(t);
        }
        return ok;
    }

    //! Switches round <-> movement tracking (REQ-WM09) for the rest of the part.
    function toggleTracking() as Void {
        var m = metcon;
        if (m != null && m.moves.size() > 0) {
            m.movementMode = !m.movementMode;
            m.restore(m.events);
        }
    }

    //! Ends the current part from the pause / exit menu. `early` = the
    //! athlete stopped before completing (never "completed").
    function finishPart(early as Boolean) as Void {
        var t = now();
        var m = metcon;
        var runMs = clock.state == CS_PAUSED || clock.state == CS_RUN ? clock.partRunAt(t) : clock.partRunMs;
        if (m != null) {
            if (early) {
                m.stoppedEarly = true;
            } else {
                m.finish(runMs);
            }
            if (prog != null) {
                Engine.stateInto(seg, prog as Program, 0, runMs);
            }
        }
        var sr = strength;
        if (sr != null && !sr.isFinished()) {
            sr.endExercise();
        }
        _endPart(t);
    }

    //! Part ends -> result entry; FIT timer stops (REQ-WR09).
    function _endPart(t as Number) as Void {
        clock.enter(CS_ENTRY, t);
        recorder.hold();
        recorder.lap();
        outcome = _buildOutcome(t, false);
        _checkpoint(t);
        CoachUi.showStage(self);
    }

    //! Draft PartOutcome from the current runner (also used for recovery
    //! of an interrupted part).
    function _buildOutcome(t as Number, interrupted as Boolean) as PartOutcome {
        var o = new PartOutcome();
        o.resultId = resultId;
        o.partId = U.str(part, "id") as String;
        o.t0 = partT0;
        o.elMs = clock.wallAt(t) - partEl0;
        o.actMs = clock.activeAt(t) - partAct0;
        o.level = U.str(part, "lv");
        var e = edits.get(selected[cur]);
        if (e instanceof Dictionary && e.size() > 0) {
            o.edits = e;
        }
        var m = metcon;
        if (m != null) {
            o.status = interrupted ? "interrupted" : m.status(timerEnded);
            o.wkMs = seg.scoreMs;
            o.score = Score.measured(part, m, o.status, m.finishMs >= 0 ? _scoreAt(m.finishMs) : seg.scoreMs);
            o.events = m.events;
            o.loads = _plannedLoads();
        }
        var sr = strength;
        if (sr != null) {
            o.status = interrupted ? "interrupted" : (sr.current >= sr.total() || sr.buildToMax ? "completed" : "stopped_early");
            o.wkMs = clock.partRunMs;
            o.score = Score.measuredStrength(part, sr);
            o.sets = sr.logs;
        }
        var hr = partHr.pair();
        if (hr != null) {
            o.hrAvg = hr[0];
            o.hrMax = hr[1];
        }
        var c1 = _calories();
        var c0 = partCal0;
        // REQ-WR10: part calories only from a valid cumulative delta.
        if (c0 != null && c1 != null && c1 >= c0 && recorder.active()) {
            o.calories = c1 - c0;
        }
        o.fit = recorder.outcome;
        return o;
    }

    //! Part score time at a finish timestamp (work + in-clock rest).
    function _scoreAt(ms as Number) as Number {
        if (prog == null) {
            return ms;
        }
        var st = Engine.stateAt(prog as Program, 0, ms);
        return st.scoreMs;
    }

    //! Planned loads for loaded metcon items, editable on the result screen.
    function _plannedLoads() as Array {
        var ld = U.arr(part, "ld");
        var out = [];
        if (ld == null) {
            return out;
        }
        for (var i = 0; i < ld.size(); i++) {
            var it = ld[i] as Dictionary;
            var v = U.real(it, "v");
            var c = U.num(it, "c");
            // Unresolved planned load: no row until the athlete enters one.
            if (v != null) {
                out.add([U.str(it, "id"), _num(v), U.str(it, "u"), c == null ? 1 : c]);
            }
        }
        return out;
    }

    function _num(v as Float) as Number or Float {
        return v == v.toNumber().toFloat() ? v.toNumber() : v;
    }

    //! Save on the result screen: finalize, queue, move on (REQ-WO01/02).
    function savePart() as Boolean {
        if (_stopAfterPart) {
            return savePartAndStop(now());
        }
        var o = outcome;
        if (o == null) {
            return false;
        }
        var t = now();
        if (!hasNext()) {
            // Last part: save the FIT activity first so the final result
            // carries the real recording outcome.
            o.fit = recorder.save();
        } else if (!recorder.failed() && recorder.active()) {
            o.fit = "unknown";
        }
        if (!_storeResult(o)) {
            message = "Storage full: not saved";
            Alerts.refused();
            return false;
        }
        entries.add([o.partId, o.resultId, o.status]);
        _afterPart(t);
        return true;
    }

    //! Discard this part's score (REQ-WR09): recorded activity stays, the
    //! part is reported as skipped without a fabricated result.
    function discardPart() as Void {
        var t = now();
        entries.add([U.str(part, "id"), "", "skipped"]);
        if (!hasNext()) {
            recorder.save();
        }
        _afterPart(t);
    }

    function _afterPart(t as Number) as Void {
        outcome = null;
        if (!hasNext()) {
            _finalize(t);
            return;
        }
        var ra = U.num(partAt(cur), "ra");
        cur += 1;
        _loadPart();
        if (ra != null && ra > 0) {
            // Inter-part prescribed rest counts as active time (REQ-WR08).
            clock.enter(CS_PART_REST, t);
            recorder.resume();
        } else {
            clock.enter(CS_WAIT, t);
        }
        _checkpoint(t);
        CoachUi.showStage(self);
    }

    //! START on the transition screen: next part (no second prep).
    function startNextPart() as Void {
        if (clock.state != CS_WAIT && clock.state != CS_PART_REST) {
            return;
        }
        var t = now();
        _startWork(t);
        _checkpoint(t);
        CoachUi.showStage(self);
    }

    function _storeResult(o as PartOutcome) as Boolean {
        var r = ResultBuilder.result(plan, sid, o);
        var title = U.str(plan, "ti");
        var label = U.str(part, "l");
        if (!History.add(kv, o.resultId, History.KIND_RESULT, title == null ? "" : title,
                label == null ? "" : label, o.t0, Score.text(o.score), o.status, r)) {
            return false;
        }
        if (!Outbox.enqueue(kv, "result.put", o.resultId, 1, Uuid.v4(), Time.now().value())) {
            return false;
        }
        _notifyResultQueued();
        return true;
    }

    function _notifyResultQueued() as Void {
        CoachApp.instance().onResultQueued();
    }

    function _notifyFinalized() as Void {
        CoachApp.instance().onSessionClosed();
    }

    function _showStage() as Void {
        CoachUi.showStage(self);
    }

    //! Final summary (REQ-WR07 final_result -> finalized, REQ-SY16).
    function _finalize(t as Number) as Boolean {
        if (finalized) {
            return true;
        }
        if (summary == null) {
            summary = _buildSummary(t);
        }
        var s = summary as Dictionary;
        var title = U.str(plan, "ti");
        // Checkpoint the committed-part list before attempting either summary write.
        if (!_checkpoint(t)
                || !History.add(kv, sid, History.KIND_SESSION, title == null ? "" : title, "",
                    U.num(s, "t0") as Number, entries.size().format("%d") + " parts", "completed", s)
                || !Outbox.enqueue(kv, "session.put", sid, 1, Uuid.v4(), Time.now().value())) {
            _storageFailure();
            _showStage();
            return false;
        }
        Checkpoint.clear(kv);
        kv.del("snap");
        frozen = false;
        message = null;
        finalized = true;
        _notifyFinalized();
        _showStage();
        return true;
    }

    //! Freeze the summary once so retry latency never changes its payload.
    function _buildSummary(t as Number) as Dictionary {
        clock.enter(CS_FINAL, t);
        _stopTimer();
        // Unreached parts are skipped, never fabricated (REQ-SY16).
        for (var i = entries.size(); i < selected.size(); i++) {
            entries.add([U.str(partAt(i), "id"), "", "skipped"]);
        }
        var fit = recorder.outcome;
        if (fit.equals("unknown")) {
            fit = recorder.save();
        }
        var hr = sessionHr.pair();
        var info = Activity.getActivityInfo();
        if (info != null && info.averageHeartRate != null && info.maxHeartRate != null && fit.equals("saved")) {
            hr = [info.averageHeartRate as Number, info.maxHeartRate as Number];
        }
        var cal = null;
        if (info != null && info.calories != null && fit.equals("saved")) {
            cal = info.calories;
        }
        var start = t0 == 0 ? Time.now().value() : t0;
        return ResultBuilder.session(plan, sid, entries, start, start + clock.wallAt(t) / 1000,
            clock.wallAt(t), clock.activeAt(t), hr, cal, restartOf, fit);
    }

    //! Exit dialog "Finish incomplete" (REQ-WN07): the current part (when it
    //! has progress) is saved as interrupted, unreached parts skipped.
    function finishIncomplete() as Void {
        var t = now();
        if (clock.state == CS_ENTRY && outcome != null) {
            (outcome as PartOutcome).status = "interrupted";
            savePartAndStop(t);
            return;
        }
        if (partStarted && (clock.state == CS_RUN || clock.state == CS_PAUSED)) {
            clock.enter(CS_ENTRY, t);
            var o = _buildOutcome(t, true);
            outcome = o;
            savePartAndStop(t);
            return;
        }
        _finalize(t);
    }

    function savePartAndStop(t as Number) as Boolean {
        _stopAfterPart = true;
        var o = outcome as PartOutcome;
        o.fit = recorder.save();
        if (!_checkpoint(t) || !_storeResult(o)) {
            _storageFailure();
            _showStage();
            return false;
        }
        entries.add([o.partId, o.resultId, o.status]);
        outcome = null;
        return _finalize(t);
    }

    //! Discard session (after double confirmation): drafts and recording
    //! go; already finalized part results are kept (REQ-WR09).
    function discardSession() as Void {
        var t = now();
        _stopTimer();
        recorder.discard();
        if (entries.size() > 0) {
            _finalize(t);
            return;
        }
        clock.enter(CS_FINAL, t);
        Checkpoint.clear(kv);
        kv.del("snap");
        finalized = true;
        CoachApp.instance().onSessionClosed();
    }

    //! App is closing with the session open (REQ-WN07 clean exit):
    //! checkpoint, save the FIT activity, finalize as interrupted.
    function onAppStop() as Void {
        if (finalized) {
            return;
        }
        var t = now();
        if (clock.state == CS_FINAL) {
            // A failed finalization must retain its checkpoint on app exit too.
            _checkpoint(t);
            return;
        }
        _stopTimer();
        if (clock.state != CS_PAUSED) {
            clock.enter(CS_PAUSED, t);
        }
        _checkpoint(t);
        var fit = recorder.save();
        Recovery.run(kv, fit);
        finalized = !Checkpoint.exists(kv);
    }

    // ------------------------------------------------------------------
    // Strength controls

    //! START / LAP / tap on the set screen. Returns true when logged with
    //! one press; false when entry is needed (the caller opens pickers).
    function logSet() as Boolean {
        var sr = strength;
        if (sr == null || clock.state != CS_RUN) {
            return false;
        }
        if (sr.resting()) {
            sr.startNext();
            WatchUi.requestUpdate();
            return true;
        }
        var t = now();
        if (sr.needsEntry()) {
            pendingPressMs = clock.partRunAt(t);
            return false;
        }
        var before = sr.logs.size();
        if (!sr.logAsPrescribed(clock.partRunAt(t))) {
            return false;
        }
        return _acceptSet(t, before);
    }

    //! Button time of a set awaiting entry (rest starts there, REQ-WS03).
    var pendingPressMs as Number = -1;

    //! Entry accepted for the displayed set.
    function logSetWith(reps as Number, load as Float, status as String) as Boolean {
        var sr = strength;
        if (sr == null) {
            return false;
        }
        var t = now();
        var press = pendingPressMs >= 0 ? pendingPressMs : clock.partRunAt(t);
        pendingPressMs = -1;
        var before = sr.logs.size();
        if (!sr.logWith(press, reps, load, status)) {
            return false;
        }
        return _acceptSet(t, before);
    }

    function _acceptSet(t as Number, before as Number) as Boolean {
        var sr = strength as StrengthRun;
        if (!_checkpoint(t)) {
            sr.undo();
            _storageFailure();
            return false;
        }
        recorder.lap();
        Alerts.round();
        if (sr.isFinished()) {
            _endPart(t);
        }
        WatchUi.requestUpdate();
        return true;
    }

    function undoSet() as Boolean {
        var sr = strength;
        if (sr == null || !sr.undo()) {
            return false;
        }
        _checkpoint(now());
        return true;
    }

    function editLastSet(reps as Number, load as Float, status as String) as Boolean {
        var sr = strength;
        if (sr == null || !sr.editLast(reps, load, status)) {
            return false;
        }
        _checkpoint(now());
        return true;
    }

    function extraSet() as Boolean {
        var sr = strength;
        if (sr == null || !sr.addExtraSet()) {
            message = "Set limit reached (30)";
            return false;
        }
        _checkpoint(now());
        return true;
    }

    function skipSet() as Boolean {
        var sr = strength;
        if (sr == null) {
            return false;
        }
        var t = now();
        if (!sr.skipSet(clock.partRunAt(t))) {
            return false;
        }
        _checkpoint(t);
        if (sr.isFinished()) {
            _endPart(t);
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Durability

    function _storageFailure() as Void {
        message = "Storage full: not accepted";
        frozen = true;
        Alerts.refused();
        if (clock.isRunning()) {
            var t = now();
            clock.enter(CS_PAUSED, t);
            recorder.hold();
        }
    }

    //! Clears the storage-failure freeze so the athlete may retry.
    function retryStorage() as Boolean {
        if (clock.state == CS_FINAL && !finalized) {
            return _finalize(now());
        }
        frozen = false;
        message = null;
        return _checkpoint(now());
    }

    //! Writes a checkpoint generation (REQ-WO06). False if refused.
    function _checkpoint(t as Number) as Boolean {
        _lastCheckpoint = t;
        var d = {
            "v" => 1,
            "sid" => sid,
            "sel" => selected,
            "cur" => cur,
            "cl" => clock.toArray(t),
            "t0" => t0,
            "pt0" => partT0,
            "pe0" => partEl0,
            "pa0" => partAct0,
            "ps" => partStarted,
            "rid" => resultId,
            "ent" => entries,
            "hr" => partHr.toArray(),
            "shr" => sessionHr.toArray(),
            "fit" => recorder.outcome,
            "end" => timerEnded
        };
        if (restartOf != null) {
            d.put("rso", restartOf);
        }
        var e = edits.get(selected[cur]);
        if (e instanceof Dictionary) {
            d.put("edt", e);
        }
        var m = metcon;
        if (m != null) {
            d.put("ev", m.events);
            d.put("mvm", m.movementMode);
            d.put("se", m.stoppedEarly);
            d.put("wk", seg.scoreMs);
        }
        var sr = strength;
        if (sr != null) {
            d.put("sl", sr.logs);
            d.put("sc", sr.current);
            d.put("sn", sr.sets.size());
            d.put("en", sr.ended);
        }
        var o = outcome;
        if (o != null) {
            d.put("dsc", o.score);
            d.put("dst", o.status);
            if (o.level != null) {
                d.put("dlv", o.level);
            }
        }
        return Checkpoint.save(kv, d) > 0;
    }

    //! Persists an edit of the result draft (REQ-WO01 durable drafts).
    function saveDraft() as Void {
        _checkpoint(now());
    }

    function _heartRate() as Number? {
        var info = Activity.getActivityInfo();
        return info == null ? null : info.currentHeartRate;
    }

    function _calories() as Number? {
        var info = Activity.getActivityInfo();
        return info == null ? null : info.calories;
    }

    function close() as Void {
        _stopTimer();
    }
}
