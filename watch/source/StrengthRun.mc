//
// StrengthRun.mc
//
// One strength part: a flat ordered list of sets (strength_sets) or an open
// sequence of attempts (build_to_max) - REQ-WS01..WS05.
//
//  * One-press logging (REQ-WS02): a set whose reps and load are numeric
//    (`r` present, and `w.v` present or bodyweight) is logged as displayed;
//    otherwise needsEntry() is true and the UI collects values first.
//  * Rest (REQ-WS03): starts at the completion *button event* time, even
//    when entry delays the log; counts down, then overtime until START/LAP.
//    Cues at 10 s and 0 (REQ-WC02). No rest after the final set unless the
//    plan prescribes one (the phone omits it).
//  * Undo removes the last logged set and returns to it; edit corrects
//    reps / load / status without restarting rest.
//  * Extra set (copies the last), skip set, end exercise (REQ-WS04).
//
// All times are part running ms (pauses excluded) supplied by the caller.
// Logs use the protocol section 5 `sets` row layout. Pure; tested in
// StrengthRunTest.mc.
//
import Toybox.Lang;

// Set row columns (docs/protocol.md section 5 `sets`).
enum {
    SR_ID = 0,
    SR_INDEX = 1,
    SR_TARGET = 2,
    SR_REPS = 3,
    SR_LOAD = 4,
    SR_UNIT = 5,
    SR_COUNT = 6,
    SR_STATUS = 7,
    SR_MS = 8
}

// Rest cue returned by restCue().
enum {
    RC_NONE = 0,
    RC_TEN = 1,
    RC_ZERO = 2
}

class StrengthRun {

    const MAX_SETS = 30;
    const REST_WARN_MS = 10000;

    var buildToMax as Boolean;
    //! Planned + extra sets (Dictionaries from the plan).
    var sets as Array = [];
    //! Logged rows, protocol `sets` layout.
    var logs as Array = [];
    //! Index of the set on screen.
    var current as Number = 0;
    //! Attempt limit for build_to_max.
    var attemptLimit as Number = 30;
    //! Rest start (part ms) and length; restStartMs < 0 = no rest running.
    var restStartMs as Number = -1;
    var restMs as Number = 0;
    var ended as Boolean = false;
    var defaultUnit as String = "kg";

    function initialize(part as Dictionary, unit as String) {
        buildToMax = U.eq(U.str(part, "mode"), "build_to_max");
        var s = U.arr(part, "sets");
        sets = s == null ? [] : s.slice(0, null);
        var att = U.num(part, "att");
        attemptLimit = att == null ? MAX_SETS : U.clamp(att, 1, MAX_SETS);
        defaultUnit = unit;
    }

    //! The set on screen (build_to_max reuses the last template set).
    function currentSet() as Dictionary? {
        if (sets.size() == 0) {
            return null;
        }
        if (current < sets.size()) {
            return sets[current] as Dictionary;
        }
        return buildToMax ? sets[sets.size() - 1] as Dictionary : null;
    }

    //! Planned number of sets (attempt limit for build_to_max).
    function total() as Number {
        return buildToMax ? attemptLimit : sets.size();
    }

    function isFinished() as Boolean {
        return ended || current >= total();
    }

    //! Target reps of a set, or -1 when unresolved (range / max).
    static function targetReps(set as Dictionary) as Number {
        var r = U.num(set, "r");
        return r == null ? -1 : r;
    }

    //! Target load of a set, or null when unresolved; bodyweight is -1.0.
    static function targetLoad(set as Dictionary) as Float? {
        if (U.flag(set, "bw", false)) {
            return -1.0;
        }
        var w = U.dict(set, "w");
        return w == null ? null : U.real(w, "v");
    }

    static function loadUnit(set as Dictionary, dflt as String) as String {
        if (U.flag(set, "bw", false)) {
            return "";
        }
        var u = U.str(U.dict(set, "w"), "u");
        return u == null ? dflt : u;
    }

    static function implementCount(set as Dictionary) as Number {
        var c = U.num(U.dict(set, "w"), "c");
        return c == null ? 1 : c;
    }

    //! True when the displayed set cannot be logged with one press
    //! (REQ-WS02). Build-to-max attempts always confirm the load (REQ-WS05),
    //! but a prefilled numeric value may be accepted with one press.
    function needsEntry() as Boolean {
        var set = currentSet();
        if (set == null) {
            return false;
        }
        if (buildToMax) {
            return true;
        }
        return targetReps(set) < 0 || targetLoad(set) == null;
    }

    //! Load to prefill in entry: last actual for build-to-max, else planned,
    //! else the previous set's actual; -1.0 when nothing is known.
    function prefillLoad() as Float {
        var set = currentSet();
        if (buildToMax && logs.size() > 0) {
            return ((logs[logs.size() - 1] as Array)[SR_LOAD] as Number or Float).toFloat();
        }
        if (set != null) {
            var t = targetLoad(set);
            if (t != null) {
                return t;
            }
        }
        if (logs.size() > 0) {
            return ((logs[logs.size() - 1] as Array)[SR_LOAD] as Number or Float).toFloat();
        }
        return -1.0;
    }

    function prefillReps() as Number {
        var set = currentSet();
        if (set != null && targetReps(set) >= 0) {
            return targetReps(set);
        }
        if (logs.size() > 0) {
            return (logs[logs.size() - 1] as Array)[SR_REPS] as Number;
        }
        return 0;
    }

    //! Logs the displayed set as prescribed (one press). `pressMs` is the
    //! button event time, which also starts rest.
    function logAsPrescribed(pressMs as Number) as Boolean {
        var set = currentSet();
        if (set == null || needsEntry()) {
            return false;
        }
        return logWith(pressMs, targetReps(set), targetLoad(set) as Float, "done");
    }

    //! Logs the displayed set with entered values. `pressMs` is when the
    //! athlete pressed "set done" (rest is measured from it, REQ-WM14).
    function logWith(pressMs as Number, reps as Number, load as Float, status as String) as Boolean {
        var set = currentSet();
        if (set == null || isFinished()) {
            return false;
        }
        var target = targetReps(set);
        if (status.equals("done") && target > 0 && reps < target) {
            // Fewer reps than prescribed is recorded as partial, never as a
            // successful full set (REQ-WS02).
            status = "partial";
        }
        logs.add([U.str(set, "id"), current, target, reps, _wireLoad(load),
            loadUnit(set, defaultUnit), implementCount(set), status, pressMs]);
        var rest = U.num(set, "rest");
        current += 1;
        if (rest != null && rest > 0 && !isFinished()) {
            restStartMs = pressMs;
            restMs = rest * 1000;
        } else {
            restStartMs = -1;
        }
        return true;
    }

    //! -1 for bodyweight / unknown, else the value as Number when whole.
    function _wireLoad(load as Float) as Number or Float {
        if (load < 0) {
            return -1;
        }
        if (load == load.toNumber().toFloat()) {
            return load.toNumber();
        }
        return load;
    }

    //! Rest left at `nowMs` (negative = overtime), or null without rest.
    function restLeftMs(nowMs as Number) as Number? {
        if (restStartMs < 0) {
            return null;
        }
        return restMs - (nowMs - restStartMs);
    }

    function resting() as Boolean {
        return restStartMs >= 0;
    }

    //! Cue when rest crosses 10 s or 0 between two ticks.
    function restCue(prevMs as Number, nowMs as Number) as Number {
        if (restStartMs < 0 || nowMs <= prevMs) {
            return RC_NONE;
        }
        var before = restMs - (prevMs - restStartMs);
        var after = restMs - (nowMs - restStartMs);
        if (before > 0 && after <= 0) {
            return RC_ZERO;
        }
        if (restMs > REST_WARN_MS && before > REST_WARN_MS && after <= REST_WARN_MS) {
            return RC_TEN;
        }
        return RC_NONE;
    }

    //! START / LAP during rest: next set now (skips the remaining rest).
    function startNext() as Void {
        restStartMs = -1;
    }

    //! Undo the last logged set: back to it, rest cleared (REQ-WS02).
    function undo() as Boolean {
        if (logs.size() == 0) {
            return false;
        }
        var last = logs[logs.size() - 1] as Array;
        logs = logs.slice(0, logs.size() - 1);
        current = last[SR_INDEX] as Number;
        restStartMs = -1;
        ended = false;
        return true;
    }

    //! Correct the last logged set; rest keeps running (REQ-WS03).
    function editLast(reps as Number, load as Float, status as String) as Boolean {
        if (logs.size() == 0) {
            return false;
        }
        var last = logs[logs.size() - 1] as Array;
        last[SR_REPS] = reps;
        last[SR_LOAD] = _wireLoad(load);
        last[SR_STATUS] = status;
        return true;
    }

    function lastLog() as Array? {
        return logs.size() == 0 ? null : logs[logs.size() - 1] as Array;
    }

    //! Appends a copy of the last set (REQ-WS04). False at the bound.
    function addExtraSet() as Boolean {
        if (buildToMax || sets.size() >= MAX_SETS || sets.size() == 0) {
            return false;
        }
        sets.add(U.copyDict(sets[sets.size() - 1] as Dictionary));
        ended = false;
        return true;
    }

    function canAddSet() as Boolean {
        return !buildToMax && sets.size() < MAX_SETS && sets.size() > 0;
    }

    //! Skips the displayed set, logged explicitly as skipped (not zero).
    function skipSet(ms as Number) as Boolean {
        var set = currentSet();
        if (set == null || isFinished()) {
            return false;
        }
        logs.add([U.str(set, "id"), current, targetReps(set), 0, -1,
            loadUnit(set, defaultUnit), implementCount(set), "skipped", ms]);
        current += 1;
        restStartMs = -1;
        return true;
    }

    //! Ends the exercise early; unlogged sets stay unlogged (missing).
    function endExercise() as Void {
        ended = true;
        restStartMs = -1;
    }

    //! Number of sets logged as done or partial.
    function doneCount() as Number {
        var n = 0;
        for (var i = 0; i < logs.size(); i++) {
            var st = (logs[i] as Array)[SR_STATUS] as String;
            if (st.equals("done") || st.equals("partial")) {
                n += 1;
            }
        }
        return n;
    }

    //! Heaviest fully completed set with at least the prescribed reps
    //! (REQ-WS05); failed / partial sets never count. -1.0 when none.
    function bestLoad() as Float {
        var best = -1.0;
        for (var i = 0; i < logs.size(); i++) {
            var row = logs[i] as Array;
            var load = (row[SR_LOAD] as Number or Float).toFloat();
            var target = row[SR_TARGET] as Number;
            if ((row[SR_STATUS] as String).equals("done") && load > best
                    && (target < 0 || (row[SR_REPS] as Number) >= target)) {
                best = load;
            }
        }
        return best;
    }

    //! Unit of the best set (falls back to the default unit).
    function bestUnit() as String {
        var best = -1.0;
        var unit = defaultUnit;
        for (var i = 0; i < logs.size(); i++) {
            var row = logs[i] as Array;
            var load = (row[SR_LOAD] as Number or Float).toFloat();
            if ((row[SR_STATUS] as String).equals("done") && load > best) {
                best = load;
                var u = row[SR_UNIT] as String;
                unit = u.length() > 0 ? u : defaultUnit;
            }
        }
        return unit;
    }

    //! "done" / "partial" / "failed" cycle for Edit last set.
    static function nextStatus(st as String) as String {
        if (st.equals("done")) {
            return "partial";
        }
        if (st.equals("partial")) {
            return "failed";
        }
        return "done";
    }

    //! Restores from checkpoint data.
    function restore(savedLogs as Array, savedSets as Array, cur as Number, restStart as Number,
            restLen as Number, wasEnded as Boolean) as Void {
        logs = savedLogs;
        sets = savedSets;
        current = cur;
        restStartMs = restStart;
        restMs = restLen;
        ended = wasEnded;
    }
}
