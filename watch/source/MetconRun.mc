//
// MetconRun.mc
//
// Progress of one metcon part: the athlete's LAP / undo / finish events on
// top of the segment engine. Counts are always *replayed* from the event
// list (never mutated in place), so undo is a compensating event
// (REQ-DM25), a checkpoint only needs the events, and any event after the
// cap is ignored by construction (REQ-DM24: freeze progress at cap).
//
// Tracking (REQ-WM09, protocol `trk`): "round" mode = LAP adds a round;
// "mv" (movement) mode = LAP advances to the next movement and a full pass
// of the movement list counts one round. Timer-driven formats (EMOM,
// Tabata, intervals) count rounds from the clock; LAP there only records
// a split marker.
//
// Finish / cap tie-break (REQ-WM02, REQ-DM24): a finish whose timestamp is
// <= the cap wins, an exact tie included. Pure; tested in MetconRunTest.mc.
//
import Toybox.Lang;

// Result of a LAP / event request.
enum {
    LAP_OK = 0,
    LAP_IGNORED = 1,
    LAP_LIMIT = 2
}

class MetconRun {

    var mode as String;
    var movementMode as Boolean;
    var moves as Array = [];
    //! Reps per round (docs/protocol.md `rr`), or null without a counting rule.
    var roundReps as Array? = null;
    var repeatLastRound as Boolean = false;
    var plannedRounds as Number = 0;
    var capMs as Number = 0;
    //! [index, kind, ms] progress events, in acceptance order.
    var events as Array = [];
    //! Bound on accepted events: REQ-WP04's 200, lowered when needed so the
    //! finished Result still fits one result.put (see ResultBuilder).
    var maxEvents as Number = 200;

    // Replayed counts.
    var rounds as Number = 0;
    var moveIndex as Number = 0;
    var finishMs as Number = -1;
    var cappedAtMs as Number = -1;
    //! The part ends in an open count-up segment (Finish = completion).
    var isOpen as Boolean = false;
    //! The athlete chose "Stop early" (not a completion, REQ-DM24).
    var stoppedEarly as Boolean = false;

    function initialize(part as Dictionary, prog as Program) {
        var m = U.str(part, "mode");
        mode = m == null ? "manual" : m;
        var mv = U.arr(part, "mv");
        moves = mv == null ? [] : mv;
        movementMode = U.eq(U.str(part, "trk"), "mv") && moves.size() > 0;
        roundReps = U.arr(part, "rr");
        repeatLastRound = U.bool(part, "rrc") == true;
        var rnd = U.num(part, "rnd");
        plannedRounds = rnd == null ? 0 : rnd;
        capMs = prog.capMs;
        isOpen = prog.isOpen();
    }

    //! True when LAP changes the athlete's own count (vs a clock-driven one).
    function countsManually() as Boolean {
        return U.eq(mode, "for_time") || U.eq(mode, "amrap") || U.eq(mode, "manual")
            || U.eq(mode, "max_effort");
    }

    //! LAP at part time `ms`. Returns LAP_OK, LAP_IGNORED (after finish /
    //! cap) or LAP_LIMIT (event bound reached; REQ-WO05 never wraps).
    function lap(ms as Number, segRound as Number) as Number {
        if (finishMs >= 0 || cappedAtMs >= 0) {
            return LAP_IGNORED;
        }
        if (events.size() >= maxEvents) {
            return LAP_LIMIT;
        }
        if (!countsManually()) {
            events.add([segRound, "rd", ms]);
            return LAP_OK;
        }
        if (movementMode) {
            events.add([moveIndex, "mv", ms]);
        } else {
            events.add([rounds, "rd", ms]);
        }
        _replay();
        return LAP_OK;
    }

    //! Compensating undo of the latest round / movement (REQ-DM25).
    function undo(ms as Number) as Boolean {
        if (finishMs >= 0 || cappedAtMs >= 0 || events.size() >= maxEvents) {
            return false;
        }
        if (_lastUndoable() < 0) {
            return false;
        }
        events.add([movementMode ? moveIndex : rounds, "undo", ms]);
        _replay();
        return true;
    }

    //! Explicit Finish (For Time completion, or early stop).
    function finish(ms as Number) as Void {
        if (finishMs >= 0) {
            return;
        }
        events.add([rounds, "fin", ms]);
        _replay();
    }

    //! The open segment's cap was reached at `ms` (normally == capMs).
    function markCap(ms as Number) as Void {
        if (cappedAtMs >= 0 || finishMs >= 0) {
            return;
        }
        events.add([rounds, "cap", ms]);
        _replay();
    }

    //! Restores state from checkpointed events.
    function restore(saved as Array) as Void {
        events = saved;
        _replay();
    }

    //! Recomputes counts from `events`, processed by timestamp with finish
    //! winning an exact tie against the cap.
    function _replay() as Void {
        rounds = 0;
        moveIndex = 0;
        finishMs = -1;
        cappedAtMs = -1;
        _stack = [] as Array<Number>;
        var limit = capMs > 0 ? capMs : 0x7FFFFFFF;
        for (var i = 0; i < events.size(); i++) {
            var e = events[i] as Array;
            var kind = e[1] as String;
            var ms = e[2] as Number;
            if (kind.equals("fin")) {
                if (ms <= limit && cappedAtMs < 0) {
                    finishMs = ms;
                }
            } else if (kind.equals("cap")) {
                if (finishMs < 0) {
                    cappedAtMs = ms;
                }
            } else if (finishMs >= 0 || cappedAtMs >= 0 || ms > limit) {
                // Progress after finish / cap is not part of the score.
            } else if (kind.equals("undo")) {
                _undoOne();
            } else if (kind.equals("rd") && countsManually()) {
                rounds += 1;
                _stack.add(0);
            } else if (kind.equals("mv")) {
                moveIndex += 1;
                _stack.add(1);
                if (moveIndex >= moves.size()) {
                    moveIndex = 0;
                    rounds += 1;
                }
            }
        }
    }

    //! One entry per counted event during replay (0 = round, 1 = movement).
    private var _stack as Array<Number> = [] as Array<Number>;

    //! Reverts the latest still-counted round/movement.
    function _undoOne() as Void {
        var n = _stack.size();
        if (n == 0) {
            return;
        }
        var kind = _stack[n - 1];
        // Array.remove() drops the first equal value, not the last element.
        _stack = _stack.slice(0, n - 1);
        if (kind == 0) {
            rounds -= 1;
        } else if (moveIndex > 0) {
            moveIndex -= 1;
        } else if (rounds > 0) {
            rounds -= 1;
            moveIndex = moves.size() - 1;
        }
    }

    //! Index of the latest round/movement event not yet undone, or -1.
    function _lastUndoable() as Number {
        if (!countsManually()) {
            return -1;
        }
        return (rounds > 0 || moveIndex > 0) ? 0 : -1;
    }

    //! Status for the Result (protocol section 5 `st`). `timerEnded` is true
    //! when a fixed-length program ran to its end.
    function status(timerEnded as Boolean) as String {
        if (stoppedEarly) {
            return "stopped_early";
        }
        if (finishMs >= 0) {
            // Count-up (For Time / open manual): Finish is completion. On a
            // countdown format an early Finish is an early stop.
            return (isOpen || timerEnded) ? "completed" : "stopped_early";
        }
        if (cappedAtMs >= 0) {
            return "capped";
        }
        return timerEnded ? "completed" : "stopped_early";
    }

    //! Reps the current (unfinished) round prescribes, or -1 without a rule.
    function currentRoundReps() as Number {
        var rr = roundReps;
        if (rr == null || rr.size() == 0) {
            return -1;
        }
        var rule = _roundRule(rounds);
        // Progressive AMRAP compiled for N rounds: beyond that the rule is
        // unknown, so extra reps are not bounded by it.
        return rule == null ? -1 : rule;
    }

    //! Reps in all completed rounds, or -1 when any round lacks a rule.
    function completedRoundReps() as Number {
        if (roundReps == null) {
            return -1;
        }
        var total = 0;
        for (var i = 0; i < rounds; i++) {
            var rule = _roundRule(i);
            if (rule == null) {
                return -1;
            }
            total += rule;
        }
        return total;
    }

    //! Reps prescribed for round i, or null without a rule. With `rrc` the last
    //! entry repeats (constant-round AMRAP compiled as one template, protocol §4.1).
    hidden function _roundRule(i as Number) as Number? {
        var rr = roundReps;
        if (rr == null || rr.size() == 0) {
            return null;
        }
        if (i < rr.size()) {
            return U.asNum(rr[i]);
        }
        return repeatLastRound ? U.asNum(rr[rr.size() - 1]) : null;
    }

    //! Largest extra-rep entry allowed (REQ-WM03/DM24: current-round total
    //! minus one); -1 = no counting rule, entry is unbounded/manual.
    function maxExtraReps() as Number {
        var cur = currentRoundReps();
        return cur < 0 ? -1 : cur - 1;
    }

    //! Display line of the current movement ("" when there is none).
    function currentMove() as String {
        if (moves.size() == 0) {
            return "";
        }
        var i = movementMode ? moveIndex : 0;
        return moves[i] as String;
    }

    //! Display line of the movement after the current one.
    function nextMove() as String {
        if (moves.size() < 2 || !movementMode) {
            return "";
        }
        return moves[(moveIndex + 1) % moves.size()] as String;
    }

    function canLog() as Boolean {
        return events.size() < maxEvents;
    }
}
