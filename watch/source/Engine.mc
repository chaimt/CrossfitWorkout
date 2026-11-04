//
// Engine.mc
//
// The segment engine (REQ-WM01), generalised from WOD Timer's WodEngine.
// Each metcon part compiles (on the phone) to a list of segments - work /
// rest countdowns and an optional final open count-up with a cap. A running
// part only stores its accumulated running milliseconds (pauses excluded);
// the phase, segment, round and progress are recomputed from that on every
// tick, so a delayed or skipped tick can never make the clock drift
// (REQ-NF02). The get-ready countdown precedes the first segment.
//
// Program is built once per part (never per frame, REQ-WP03); stateInto()
// fills a caller-owned SegState so the running screen allocates nothing.
// Unit-tested in EngineTest.mc.
//
import Toybox.Lang;

// Timer phases.
enum {
    PH_PREP = 0,
    PH_WORK = 1,
    PH_REST = 2,
    PH_OPEN = 3,
    PH_DONE = 4
}

// Cue to play on a state change (Engine.transition()).
enum {
    EV_NONE = 0,
    EV_WORK = 1,
    EV_REST = 2,
    EV_DONE = 3
}

// Segment kinds inside a Program.
enum {
    SK_WORK = 0,
    SK_REST = 1,
    SK_OPEN = 2
}

//! A part's segment list, flattened into parallel arrays with cumulative
//! start times so a lookup is a short scan instead of a re-summation.
class Program {
    var kinds as Array<Number> = [] as Array<Number>;
    var startMs as Array<Number> = [] as Array<Number>;
    //! Segment length; for the open segment, its cap (0 = uncapped).
    var durMs as Array<Number> = [] as Array<Number>;
    var labels as Array<String> = [] as Array<String>;
    var rounds as Array<Number> = [] as Array<Number>;
    var stations as Array<Number> = [] as Array<Number>;
    //! True when the segment counts towards part score time (work, open,
    //! in-clock rest - REQ-WR08).
    var scored as Array<Boolean> = [] as Array<Boolean>;
    //! Score time accumulated before each segment starts.
    var scoreStartMs as Array<Number> = [] as Array<Number>;
    //! Length of the whole program; 0 when it ends in an uncapped open.
    var totalMs as Number = 0;
    //! Cap of the final open segment in ms (0 = none / no open segment).
    var capMs as Number = 0;
    //! Highest round index + 1 (for "Round N/M").
    var roundCount as Number = 0;

    //! Builds a Program from the wire `seg` list (already validated).
    function initialize(segs as Array) {
        var t = 0;
        var score = 0;
        for (var i = 0; i < segs.size(); i++) {
            var s = segs[i] as Dictionary;
            var k = U.str(s, "k");
            var kind = U.eq(k, "rest") ? SK_REST : (U.eq(k, "open") ? SK_OPEN : SK_WORK);
            var d = 0;
            if (kind == SK_OPEN) {
                var cap = U.num(s, "cap");
                d = cap == null ? 0 : cap * 1000;
                capMs = d;
            } else {
                var sec = U.num(s, "d");
                d = sec == null ? 0 : sec * 1000;
            }
            var ix = U.num(s, "ix");
            var st = U.num(s, "s");
            var lb = U.str(s, "lb");
            var counts = kind != SK_REST || U.flag(s, "ic", false);
            kinds.add(kind);
            startMs.add(t);
            durMs.add(d);
            labels.add(lb == null ? "" : lb);
            rounds.add(ix == null ? 0 : ix);
            stations.add(st == null ? -1 : st);
            scored.add(counts);
            scoreStartMs.add(score);
            if (ix != null && ix + 1 > roundCount) {
                roundCount = ix + 1;
            }
            t += d;
            if (counts) {
                score += d;
            }
        }
        // An uncapped open end means the program has no fixed length.
        var n = kinds.size();
        totalMs = (n > 0 && kinds[n - 1] == SK_OPEN && durMs[n - 1] == 0) ? 0 : t;
    }

    function size() as Number {
        return kinds.size();
    }

    //! True when the program ends with an open (count-up) segment.
    function isOpen() as Boolean {
        var n = kinds.size();
        return n > 0 && kinds[n - 1] == SK_OPEN;
    }
}

//! Snapshot of a part's timer at one instant (reused, never per frame).
class SegState {
    var phase as Number = PH_PREP;
    var seg as Number = -1;
    //! Length of the current phase; 0 when open-ended.
    var phaseMs as Number = 0;
    var phaseElapsedMs as Number = 0;
    //! Part time since the end of prep, clamped to the program length.
    var partMs as Number = 0;
    //! Work + open + in-clock rest time so far (part score time).
    var scoreMs as Number = 0;
    var round as Number = 0;
    var station as Number = -1;
    var label as String = "";

    function initialize() {
    }

    function remainingMs() as Number {
        return phaseMs - phaseElapsedMs;
    }

    //! Fraction of the current phase completed, 0.0 - 1.0; 0.0 if open-ended.
    function progress() as Float {
        if (phaseMs <= 0) {
            return 0.0;
        }
        var p = phaseElapsedMs.toFloat() / phaseMs;
        return p > 1.0 ? 1.0 : p;
    }

    function copyFrom(o as SegState) as Void {
        phase = o.phase;
        seg = o.seg;
        phaseMs = o.phaseMs;
        phaseElapsedMs = o.phaseElapsedMs;
        partMs = o.partMs;
        scoreMs = o.scoreMs;
        round = o.round;
        station = o.station;
        label = o.label;
    }
}

module Engine {

    const COUNTDOWN_BEEPS = 3;

    //! New state of `prog` after `runMs` of running time (pauses excluded),
    //! with `prepMs` of get-ready first. For tests and one-off queries.
    function stateAt(prog as Program, prepMs as Number, runMs as Number) as SegState {
        var st = new SegState();
        stateInto(st, prog, prepMs, runMs);
        return st;
    }

    //! Fills `st` with the state after `runMs` of running time.
    function stateInto(st as SegState, prog as Program, prepMs as Number, runMs as Number) as Void {
        if (runMs < prepMs) {
            _set(st, PH_PREP, -1, prepMs, runMs, 0, 0, 0, -1, "GET READY");
            return;
        }
        var t = runMs - prepMs;
        var n = prog.size();
        if (n == 0) {
            _set(st, PH_DONE, -1, 0, 0, 0, 0, 0, -1, "DONE");
            return;
        }
        if (prog.totalMs > 0 && t >= prog.totalMs) {
            var last = n - 1;
            var score = prog.scoreStartMs[last] + (prog.scored[last] ? prog.durMs[last] : 0);
            _set(st, PH_DONE, last, 0, 0, prog.totalMs, score, prog.rounds[last], prog.stations[last], "DONE");
            return;
        }
        // Linear scan from the end: programs are at most 200 segments.
        var i = n - 1;
        while (i > 0 && prog.startMs[i] > t) {
            i--;
        }
        var kind = prog.kinds[i];
        var elapsed = t - prog.startMs[i];
        var phase = kind == SK_REST ? PH_REST : (kind == SK_OPEN ? PH_OPEN : PH_WORK);
        var score = prog.scoreStartMs[i] + (prog.scored[i] ? elapsed : 0);
        _set(st, phase, i, prog.durMs[i], elapsed, t, score, prog.rounds[i], prog.stations[i], prog.labels[i]);
    }

    function _set(st as SegState, phase as Number, seg as Number, phaseMs as Number,
            phaseElapsedMs as Number, partMs as Number, scoreMs as Number, round as Number,
            station as Number, label as String) as Void {
        st.phase = phase;
        st.seg = seg;
        st.phaseMs = phaseMs;
        st.phaseElapsedMs = phaseElapsedMs;
        st.partMs = partMs;
        st.scoreMs = scoreMs;
        st.round = round;
        st.station = station;
        st.label = label;
    }

    //! The cue to play when the timer moves from (`prevPhase`, `prevSeg`)
    //! to `next`: a new work / open segment, a rest, or the finish.
    function transition(prevPhase as Number, prevSeg as Number, next as SegState) as Number {
        if (prevPhase == next.phase && prevSeg == next.seg) {
            return EV_NONE;
        }
        if (next.phase == PH_WORK || next.phase == PH_OPEN) {
            return EV_WORK;
        }
        if (next.phase == PH_REST) {
            return EV_REST;
        }
        if (next.phase == PH_DONE) {
            return EV_DONE;
        }
        return EV_NONE;
    }

    //! 3, 2 or 1 during the last three seconds of a timed phase (the ticks
    //! before each change, REQ-WC01), otherwise 0.
    function countdownBeep(st as SegState) as Number {
        if (st.phase == PH_DONE || st.phaseMs <= 0) {
            return 0;
        }
        var seconds = Fmt.countdownSeconds(st.remainingMs());
        if (seconds >= 1 && seconds <= COUNTDOWN_BEEPS) {
            return seconds;
        }
        return 0;
    }

    //! Seconds shown by the big clock: prep and timed phases count down,
    //! the open segment counts up.
    function clockSeconds(st as SegState) as Number {
        if (st.phase == PH_OPEN) {
            return Fmt.countUpSeconds(st.phaseElapsedMs);
        }
        if (st.phase == PH_DONE) {
            return Fmt.countUpSeconds(st.partMs);
        }
        return Fmt.countdownSeconds(st.remainingMs());
    }
}
