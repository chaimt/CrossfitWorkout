//
// SessionClock.mc
//
// The session state machine's clocks (REQ-WR07 / WR08). Every state change
// is reported with a monotonic timestamp (System.getTimer() in the app,
// literal numbers in tests), and the time spent in the state being left is
// added to the right buckets:
//
//   wall    everything since the first work start
//   active  part running time (work + prescribed / in-part rest) and
//           inter-part prescribed rest; NOT prep, pause, result entry or
//           waiting at a transition screen
//   partRun running time of the current part (pauses excluded) - the
//           engine derives the part's phase and score time from it
//
// Score time (work + in-clock rest only) comes from the segment engine, so
// it is not tracked here. Wall-clock / timezone changes never affect these
// numbers because only monotonic deltas are summed (REQ-TS12).
// Pure; the §6.12 deterministic example is in SessionClockTest.mc.
//
import Toybox.Lang;

// Session states (REQ-WR07).
enum {
    CS_PREVIEW = 0,
    CS_PREP = 1,
    CS_RUN = 2,
    CS_PAUSED = 3,
    CS_ENTRY = 4,
    CS_PART_REST = 5,
    CS_WAIT = 6,
    CS_FINAL = 7
}

class SessionClock {

    var state as Number = CS_PREVIEW;
    //! State to return to on resume (RUN or PART_REST).
    var resumeState as Number = CS_RUN;
    var firstWorkMs as Number = -1;
    var activeMs as Number = 0;
    var partRunMs as Number = 0;
    var prepElapsedMs as Number = 0;
    private var _since as Number = 0;
    private var _endMs as Number = -1;

    function initialize() {
    }

    //! Moves to `next` at monotonic time `nowMs`.
    function enter(next as Number, nowMs as Number) as Void {
        _accumulate(nowMs);
        if (next == CS_PAUSED && (state == CS_RUN || state == CS_PART_REST || state == CS_PREP)) {
            resumeState = state;
        }
        if (next == CS_RUN && firstWorkMs < 0) {
            firstWorkMs = nowMs;
        }
        if (next == CS_FINAL) {
            _endMs = nowMs;
        }
        state = next;
    }

    //! Resume after a pause: back to the interrupted logical state.
    function resume(nowMs as Number) as Void {
        if (state == CS_PAUSED) {
            enter(resumeState, nowMs);
        }
    }

    //! A new part begins (its running time restarts from zero).
    function startPart() as Void {
        partRunMs = 0;
    }

    function _accumulate(nowMs as Number) as Void {
        var d = nowMs - _since;
        if (d < 0) {
            d = 0;
        }
        if (state == CS_RUN || state == CS_PART_REST) {
            activeMs += d;
        }
        if (state == CS_RUN) {
            partRunMs += d;
        }
        if (state == CS_PREP) {
            prepElapsedMs += d;
        }
        _since = nowMs;
    }

    //! Wall time since the first work start (frozen at the final state).
    function wallAt(nowMs as Number) as Number {
        if (firstWorkMs < 0) {
            return 0;
        }
        var end = _endMs >= 0 ? _endMs : nowMs;
        return end - firstWorkMs;
    }

    function activeAt(nowMs as Number) as Number {
        if (state == CS_RUN || state == CS_PART_REST) {
            return activeMs + (nowMs - _since);
        }
        return activeMs;
    }

    function partRunAt(nowMs as Number) as Number {
        if (state == CS_RUN) {
            return partRunMs + (nowMs - _since);
        }
        return partRunMs;
    }

    function prepAt(nowMs as Number) as Number {
        if (state == CS_PREP) {
            return prepElapsedMs + (nowMs - _since);
        }
        return prepElapsedMs;
    }

    //! Time spent in the current state so far (inter-part rest countdown).
    function inStateAt(nowMs as Number) as Number {
        return nowMs - _since;
    }

    function isRunning() as Boolean {
        return state == CS_RUN || state == CS_PART_REST || state == CS_PREP;
    }

    //! Checkpoint form: [state, resumeState, active, partRun, prep, wall].
    function toArray(nowMs as Number) as Array {
        return [state, resumeState, activeAt(nowMs), partRunAt(nowMs), prepAt(nowMs), wallAt(nowMs)];
    }
}
