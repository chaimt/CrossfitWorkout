//
// Edits.mc
//
// Timer parameters the athlete may change before starting a part
// (REQ-WN03): duration, cap, interval, rounds, work / rest. Edits apply to
// this run only; they rewrite the part's segment list for the run snapshot
// and are reported in the Result's `edt` (docs/protocol.md section 5).
//
// Only shapes the watch can rebuild unambiguously are editable: a single
// work segment (AMRAP, max effort, count-down manual), a single open
// segment (For Time / count-up manual cap), a uniform EMOM (equal work-only
// intervals) and a single-block Tabata. Anything else is run as compiled.
// Pure; unit-tested in EditsTest.mc.
//
import Toybox.Lang;

// How a field value is displayed.
enum {
    KIND_CLOCK = 0,
    KIND_CAP = 1,
    KIND_COUNT = 2
}

//! Range, step and label of one editable field (WOD Timer's FieldSpec).
class FieldSpec {
    var key as String;
    var label as String;
    var min as Number;
    var max as Number;
    var step as Number;
    var kind as Number;
    var value as Number;

    function initialize(key as String, label as String, min as Number, max as Number,
            step as Number, kind as Number, value as Number) {
        self.key = key;
        self.label = label;
        self.min = min;
        self.max = max;
        self.step = step;
        self.kind = kind;
        self.value = clamp(value);
    }

    //! Display form of a value of this field ("20:00", "None", "8").
    function format(v as Number) as String {
        if (kind == KIND_COUNT) {
            return v.format("%d");
        }
        if (kind == KIND_CAP && v == 0) {
            return "None";
        }
        return Fmt.clock(v);
    }

    function clamp(v as Number) as Number {
        return U.clamp(v, min, max);
    }
}

module Edits {

    const MAX_SEC = 7200;

    //! Editable fields of a metcon part, in menu order (empty when none).
    function fields(part as Dictionary) as Array<FieldSpec> {
        var segs = U.arr(part, "seg");
        var out = [] as Array<FieldSpec>;
        if (segs == null || segs.size() == 0) {
            return out;
        }
        var first = segs[0] as Dictionary;
        if (segs.size() == 1 && U.eq(U.str(first, "k"), "work")) {
            out.add(new FieldSpec("d", "Time", 15, MAX_SEC, 15, KIND_CLOCK, U.num(first, "d")));
        } else if (segs.size() == 1 && U.eq(U.str(first, "k"), "open")) {
            var cap = U.num(first, "cap");
            out.add(new FieldSpec("cap", "Time cap", 0, MAX_SEC, 60, KIND_CAP, cap == null ? 0 : cap));
        } else if (_uniformEmom(segs)) {
            out.add(new FieldSpec("iv", "Every", 15, 600, 15, KIND_CLOCK, U.num(first, "d")));
            out.add(new FieldSpec("rnd", "Rounds", 1, 99, 1, KIND_COUNT, segs.size()));
        } else if (_tabata(segs)) {
            var rest = segs[1] as Dictionary;
            out.add(new FieldSpec("wk", "Work", 5, 600, 5, KIND_CLOCK, U.num(first, "d")));
            out.add(new FieldSpec("rs", "Rest", 5, 600, 5, KIND_CLOCK, U.num(rest, "d")));
            out.add(new FieldSpec("rnd", "Rounds", 1, 99, 1, KIND_COUNT, (segs.size() + 1) / 2));
        }
        return out;
    }

    //! Segment list of `part` with `edt` applied ({key => value}); the
    //! original list when there is nothing to apply.
    function apply(part as Dictionary, edt as Dictionary) as Array {
        var segs = U.arr(part, "seg");
        if (segs == null || segs.size() == 0 || edt.size() == 0) {
            return segs == null ? [] : segs;
        }
        var first = segs[0] as Dictionary;
        if (segs.size() == 1 && U.eq(U.str(first, "k"), "work") && edt.hasKey("d")) {
            var s = U.copyDict(first);
            s.put("d", edt.get("d"));
            return [s];
        }
        if (segs.size() == 1 && U.eq(U.str(first, "k"), "open") && edt.hasKey("cap")) {
            var s = U.copyDict(first);
            var cap = U.asNum(edt.get("cap"));
            if (cap == null || cap <= 0) {
                s.remove("cap");
            } else {
                s.put("cap", cap);
            }
            return [s];
        }
        if (_uniformEmom(segs)) {
            return _emom(segs, _get(edt, "iv", U.num(first, "d")), _get(edt, "rnd", segs.size()));
        }
        if (_tabata(segs)) {
            var rest = segs[1] as Dictionary;
            return _tabataSegs(first, rest, _get(edt, "wk", U.num(first, "d")),
                _get(edt, "rs", U.num(rest, "d")), _get(edt, "rnd", (segs.size() + 1) / 2));
        }
        return segs;
    }

    function _get(edt as Dictionary, key as String, dflt as Number?) as Number {
        var v = U.asNum(edt.get(key));
        if (v != null) {
            return v;
        }
        return dflt == null ? 0 : dflt;
    }

    //! Every segment is a work interval of the same length.
    function _uniformEmom(segs as Array) as Boolean {
        if (segs.size() < 2) {
            return false;
        }
        var d = U.num(segs[0] as Dictionary, "d");
        for (var i = 0; i < segs.size(); i++) {
            var s = segs[i] as Dictionary;
            if (!U.eq(U.str(s, "k"), "work") || U.num(s, "d") != d) {
                return false;
            }
        }
        return true;
    }

    //! One block of alternating equal work / rest, no rest after the last work.
    function _tabata(segs as Array) as Boolean {
        if (segs.size() < 3 || segs.size() % 2 == 0) {
            return false;
        }
        var w = U.num(segs[0] as Dictionary, "d");
        var r = U.num(segs[1] as Dictionary, "d");
        for (var i = 0; i < segs.size(); i++) {
            var s = segs[i] as Dictionary;
            var want = i % 2 == 0 ? "work" : "rest";
            if (!U.eq(U.str(s, "k"), want) || U.num(s, "d") != (i % 2 == 0 ? w : r)
                    || U.num(s, "ix") != i / 2) {
                return false;
            }
        }
        return true;
    }

    //! `count` work intervals of `sec`, cycling the original stations.
    function _emom(orig as Array, sec as Number, count as Number) as Array {
        var out = [];
        for (var i = 0; i < count; i++) {
            var s = U.copyDict(orig[i % orig.size()] as Dictionary);
            s.put("d", sec);
            s.put("ix", i);
            s.put("lb", "MIN " + (i + 1).format("%d"));
            out.add(s);
        }
        return out;
    }

    function _tabataSegs(work as Dictionary, rest as Dictionary, workSec as Number,
            restSec as Number, rounds as Number) as Array {
        var out = [];
        for (var i = 0; i < rounds; i++) {
            var w = U.copyDict(work);
            w.put("d", workSec);
            w.put("ix", i);
            out.add(w);
            if (i < rounds - 1) {
                var r = U.copyDict(rest);
                r.put("d", restSec);
                r.put("ix", i);
                out.add(r);
            }
        }
        return out;
    }
}
