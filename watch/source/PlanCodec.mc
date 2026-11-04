//
// PlanCodec.mc
//
// Validation of a received WatchPlan (docs/protocol.md section 4, mirrored
// by shared/watch-plan.schema.json). The plan is stored exactly as received
// once it passes, so validation must be complete: a plan that fails any rule
// is rejected as a whole and never becomes runnable (REQ-SY06/SY14, REQ-TS03
// "malformed or oversized input must not activate a partial plan").
//
// validate() returns null for a valid plan, or the ack reason:
//   "version" unknown plan schema version (REQ-SY07)
//   "size"    estimated encoded size above 4096 bytes (REQ-SY06)
//   "invalid" any structural / bound violation, including null values
// Pure; unit-tested in PlanCodecTest.mc against the shared wire fixtures.
//
import Toybox.Lang;

module PlanCodec {

    const SCHEMA_VERSION = 1;
    const MAX_PARTS = 6;
    const MAX_LINES = 12;
    const MAX_LINE = 28;
    const MAX_SETS = 30;
    const MAX_SEGMENTS = 200;
    const MAX_MOVES = 30;
    const MAX_ROUNDS = 200;
    const MAX_LOAD_ITEMS = 12;

    const MODES = ["for_time", "amrap", "emom", "tabata", "intervals", "max_effort",
        "strength_sets", "build_to_max", "manual"] as Array<String>;
    const SCORE_TYPES = ["time", "rounds_reps", "reps", "load", "calories", "distance",
        "minutes_reps", "completion", "text", "none"] as Array<String>;
    const SET_TYPES = ["warmup", "working", "top", "backoff", "amrap"] as Array<String>;
    const MASS_UNITS = ["kg", "lb"] as Array<String>;
    const DIST_UNITS = ["m", "km", "mi", "ft", "yd"] as Array<String>;

    //! Validates a WatchPlan payload; null = valid, else the ack reason.
    function validate(plan) as String? {
        if (!(plan instanceof Dictionary)) {
            return "invalid";
        }
        var sv = U.num(plan, "sv");
        if (sv == null) {
            return "invalid";
        }
        if (sv != SCHEMA_VERSION) {
            return "version";
        }
        if (WireSize.size(plan) > WireSize.MAX_MESSAGE) {
            return "size";
        }
        return _plan(plan) ? null : "invalid";
    }

    //! True for strength modes, which carry `sets` instead of `seg`.
    function isStrength(mode as String?) as Boolean {
        return U.eq(mode, "strength_sets") || U.eq(mode, "build_to_max");
    }

    function _plan(p as Dictionary) as Boolean {
        if (_hasNull(p)) {
            return false;
        }
        if (!_reqStr(p, "id", 36) || !_posInt(p, "rev", true) || !_reqStr(p, "wid", 36)
                || !_posInt(p, "wr", true) || !_reqStr(p, "ti", MAX_LINE)
                || !_reqStr(p, "date", 10) || !_reqStr(p, "tz", 64)
                || !_enum(p, "u", MASS_UNITS, true)) {
            return false;
        }
        var parts = U.arr(p, "p");
        if (parts == null || parts.size() < 1 || parts.size() > MAX_PARTS) {
            return false;
        }
        for (var i = 0; i < parts.size(); i++) {
            if (!_part(parts[i])) {
                return false;
            }
        }
        return true;
    }

    function _part(part) as Boolean {
        if (!(part instanceof Dictionary) || _hasNull(part)) {
            return false;
        }
        var mode = U.str(part, "mode");
        if (!_reqStr(part, "id", 36) || !_reqStr(part, "l", 3) || U.str(part, "l").length() < 1
                || !_reqStr(part, "n", MAX_LINE) || !U.inList(MODES, mode)) {
            return false;
        }
        var fb = U.bool(part, "fb");
        if (fb == null || !_lines(part, "ln", MAX_LINES, MAX_LINE, true)) {
            return false;
        }
        var sc = U.dict(part, "sc");
        if (sc == null || !_score(sc)) {
            return false;
        }
        // REQ-DM18: a reviewed fallback is always a manual-scored manual timer.
        if (fb && (!U.eq(mode, "manual") || !U.flag(sc, "man", false))) {
            return false;
        }
        if (isStrength(mode)) {
            if (!_sets(part)) {
                return false;
            }
        } else if (!_segments(part)) {
            return false;
        }
        if (!_enum(part, "trk", ["round", "mv"] as Array<String>, false)
                || !_lines(part, "mv", MAX_MOVES, MAX_LINE, false)
                || !_intList(part, "rr", MAX_ROUNDS, 1)
                || !_optBool(part, "rrc")
                || !_lines(part, "rl", MAX_ROUNDS, 16, false)
                || !_posInt(part, "rnd", false)
                || !_optRange(part, "att", 1, MAX_SETS)
                || !_optRange(part, "ra", 0, 7200)
                || !_optStr(part, "lv", 12)
                || !_lines(part, "lvs", 6, 12, false)) {
            return false;
        }
        return _loadItems(part);
    }

    function _score(sc as Dictionary) as Boolean {
        return !_hasNull(sc) && U.inList(SCORE_TYPES, U.str(sc, "ty")) && _posInt(sc, "rv", true)
            && _enum(sc, "cap", ["reps", "text"] as Array<String>, false)
            && _enum(sc, "du", DIST_UNITS, false)
            && _enum(sc, "agg", ["sum", "min", "each"] as Array<String>, false)
            && _optBool(sc, "man");
    }

    function _segments(part as Dictionary) as Boolean {
        var segs = U.arr(part, "seg");
        if (segs == null || segs.size() < 1 || segs.size() > MAX_SEGMENTS) {
            return false;
        }
        for (var i = 0; i < segs.size(); i++) {
            var s = segs[i];
            if (!(s instanceof Dictionary) || _hasNull(s)) {
                return false;
            }
            var k = U.str(s, "k");
            if (!_reqStr(s, "lb", 12) || U.num(s, "ix") == null || U.num(s, "ix") < 0
                    || !_optRange(s, "s", 0, 1000) || !_optBool(s, "ic")) {
                return false;
            }
            if (U.eq(k, "work") || U.eq(k, "rest")) {
                var d = U.num(s, "d");
                if (d == null || d < 1 || d > 7200 || s.hasKey("cap")) {
                    return false;
                }
            } else if (U.eq(k, "open")) {
                // Open segments count up until Finish or cap; only the last
                // segment can be open or the following ones would never run.
                if (s.hasKey("d") || !_optRange(s, "cap", 1, 7200) || i != segs.size() - 1) {
                    return false;
                }
            } else {
                return false;
            }
        }
        return true;
    }

    function _sets(part as Dictionary) as Boolean {
        var sets = U.arr(part, "sets");
        if (sets == null || sets.size() < 1 || sets.size() > MAX_SETS) {
            return false;
        }
        for (var i = 0; i < sets.size(); i++) {
            var s = sets[i];
            if (!(s instanceof Dictionary) || _hasNull(s)) {
                return false;
            }
            if (!_reqStr(s, "id", 36) || !_reqStr(s, "nm", 20) || !_reqStr(s, "rx", 8)
                    || !_optStr(s, "mv", 40) || !_optRange(s, "r", 1, 99999)
                    || !_optRange(s, "rest", 0, 3600) || !_enum(s, "ty", SET_TYPES, false)
                    || !_optStr(s, "nt", MAX_LINE) || !_optBool(s, "bw")) {
                return false;
            }
            if (s.hasKey("w")) {
                if (s.hasKey("bw") || !_load(s.get("w"))) {
                    return false;
                }
            }
        }
        return true;
    }

    function _load(w) as Boolean {
        if (!(w instanceof Dictionary) || _hasNull(w)) {
            return false;
        }
        if (!_enum(w, "u", MASS_UNITS, true) || !_optRange(w, "c", 1, 4) || U.num(w, "c") == null) {
            return false;
        }
        return _optReal(w, "v", 0.0, 1100.0);
    }

    function _loadItems(part as Dictionary) as Boolean {
        if (!part.hasKey("ld")) {
            return true;
        }
        var items = U.arr(part, "ld");
        if (items == null || items.size() > MAX_LOAD_ITEMS) {
            return false;
        }
        for (var i = 0; i < items.size(); i++) {
            var it = items[i];
            if (!(it instanceof Dictionary) || _hasNull(it) || !_reqStr(it, "id", 36)
                    || !_reqStr(it, "nm", 20) || !_load(it)) {
                return false;
            }
        }
        return true;
    }

    //! The wire forbids nulls (docs/protocol.md section 1): a present key
    //! with a null value means the sender broke the contract.
    function _hasNull(d as Dictionary) as Boolean {
        var keys = d.keys();
        for (var i = 0; i < keys.size(); i++) {
            if (d.get(keys[i]) == null) {
                return true;
            }
        }
        return false;
    }

    function _reqStr(d as Dictionary, key as String, maxLen as Number) as Boolean {
        var s = U.str(d, key);
        return s != null && s.length() <= maxLen;
    }

    function _optStr(d as Dictionary, key as String, maxLen as Number) as Boolean {
        return !d.hasKey(key) || _reqStr(d, key, maxLen);
    }

    function _optBool(d as Dictionary, key as String) as Boolean {
        return !d.hasKey(key) || U.bool(d, key) != null;
    }

    function _posInt(d as Dictionary, key as String, required as Boolean) as Boolean {
        if (!d.hasKey(key)) {
            return !required;
        }
        var n = U.num(d, key);
        return n != null && n >= 1;
    }

    function _optRange(d as Dictionary, key as String, lo as Number, hi as Number) as Boolean {
        if (!d.hasKey(key)) {
            return true;
        }
        var n = U.num(d, key);
        return n != null && n >= lo && n <= hi;
    }

    function _optReal(d as Dictionary, key as String, lo as Float, hi as Float) as Boolean {
        if (!d.hasKey(key)) {
            return true;
        }
        var f = U.real(d, key);
        return f != null && f >= lo && f <= hi;
    }

    function _enum(d as Dictionary, key as String, values as Array<String>, required as Boolean) as Boolean {
        if (!d.hasKey(key)) {
            return !required;
        }
        return U.inList(values, U.str(d, key));
    }

    //! Optional array of strings, each at most `maxLen` characters.
    function _lines(d as Dictionary, key as String, maxCount as Number, maxLen as Number,
            required as Boolean) as Boolean {
        if (!d.hasKey(key)) {
            return !required;
        }
        var a = U.arr(d, key);
        if (a == null || a.size() > maxCount) {
            return false;
        }
        for (var i = 0; i < a.size(); i++) {
            var s = a[i];
            if (!(s instanceof String) || s.length() > maxLen) {
                return false;
            }
        }
        return true;
    }

    //! Optional array of integers >= `min`.
    function _intList(d as Dictionary, key as String, maxCount as Number, min as Number) as Boolean {
        if (!d.hasKey(key)) {
            return true;
        }
        var a = U.arr(d, key);
        if (a == null || a.size() > maxCount) {
            return false;
        }
        for (var i = 0; i < a.size(); i++) {
            var n = U.asNum(a[i]);
            if (n == null || n < min) {
                return false;
            }
        }
        return true;
    }
}
