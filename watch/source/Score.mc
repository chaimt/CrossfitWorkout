//
// Score.mc
//
// Typed scores (docs/protocol.md sections 4.4 / 5, REQ-DM22/DM24, REQ-WE03).
// A score draft is a Dictionary {"ty", "rv", "man", value keys...}. Missing
// is never zero: a value the athlete has not entered is an absent key, and
// such a pending draft goes on the wire as ty "none" + man (the result
// schema requires the value keys for every typed score) - see the
// protocol proposal in watch/README.md.
//
// measured() derives the default score from the run (For Time finish time,
// AMRAP rounds, capped reps from the `rr` counting rule, best strength
// load); the result screen then lets the athlete edit it (cs = corrected).
// Pure; tested in ScoreTest.mc.
//
import Toybox.Lang;

module Score {

    const RULE_VERSION = 1;

    //! Score type from the part's rule, "none" when absent.
    function ruleType(part as Dictionary) as String {
        var t = U.str(U.dict(part, "sc"), "ty");
        return t == null ? "none" : t;
    }

    function _draft(ty as String, manual as Boolean, rv as Number) as Dictionary {
        return {"ty" => ty, "rv" => rv, "man" => manual};
    }

    function _rv(part as Dictionary) as Number {
        var rv = U.num(U.dict(part, "sc"), "rv");
        return rv == null ? RULE_VERSION : rv;
    }

    //! Default metcon score after the run ended with `status`. `scoreMs` =
    //! part score time at finish (work + in-clock rest, REQ-WR08).
    function measured(part as Dictionary, run as MetconRun, status as String, scoreMs as Number) as Dictionary {
        var sc = U.dict(part, "sc");
        var ty = ruleType(part);
        var manual = U.flag(sc, "man", false);
        var rv = _rv(part);
        // Unscored warm-ups remain unscored even when their cap expires.
        if (ty.equals("none")) {
            return _draft("none", false, rv);
        }
        if (status.equals("capped")) {
            // REQ-WM02/DM24: capped progress uses the declared counting rule,
            // else a typed manual score.
            if (U.eq(U.str(sc, "cap"), "reps") && run.completedRoundReps() >= 0) {
                var d = _draft("reps", false, rv);
                d.put("n", run.completedRoundReps());
                return d;
            }
            return _draft("text", true, rv);
        }
        if (ty.equals("time")) {
            var d = _draft("time", manual, rv);
            if (status.equals("completed") && !manual) {
                d.put("s", Fmt.countUpSeconds(scoreMs));
            }
            return d;
        }
        if (ty.equals("rounds_reps")) {
            var d = _draft("rounds_reps", manual || run.roundReps == null, rv);
            d.put("r", run.rounds);
            // Extra reps are entered on the result screen; 0 is the measured
            // default only because the LAP count says the round boundary.
            d.put("x", 0);
            return d;
        }
        if (ty.equals("completion")) {
            var d = _draft("completion", manual, rv);
            d.put("ok", status.equals("completed"));
            return d;
        }
        if (ty.equals("distance")) {
            var d = _draft("distance", true, rv);
            var du = U.str(sc, "du");
            d.put("u", du == null ? "m" : du);
            return d;
        }
        if (ty.equals("none")) {
            return _draft("none", false, rv);
        }
        // reps / calories / load / minutes_reps / text: athlete entry.
        return _draft(ty, true, rv);
    }

    //! Default strength score: best fully completed load (REQ-WS05).
    function measuredStrength(part as Dictionary, run as StrengthRun) as Dictionary {
        var ty = ruleType(part);
        var rv = _rv(part);
        if (ty.equals("load")) {
            var d = _draft("load", false, rv);
            var best = run.bestLoad();
            if (best >= 0.0) {
                d.put("v", best);
                d.put("u", run.bestUnit());
            }
            d.remove("man");
            return d;
        }
        if (ty.equals("none")) {
            return _draft("none", false, rv);
        }
        return _draft(ty, true, rv);
    }

    //! True when the draft carries every value its type requires.
    function complete(d as Dictionary) as Boolean {
        var ty = U.str(d, "ty");
        if (U.eq(ty, "time")) {
            return d.hasKey("s");
        }
        if (U.eq(ty, "rounds_reps")) {
            return d.hasKey("r") && d.hasKey("x");
        }
        if (U.eq(ty, "reps") || U.eq(ty, "calories")) {
            return d.hasKey("n");
        }
        if (U.eq(ty, "load") || U.eq(ty, "distance")) {
            return d.hasKey("v") && d.hasKey("u");
        }
        if (U.eq(ty, "minutes_reps")) {
            return d.hasKey("i") && d.hasKey("x");
        }
        if (U.eq(ty, "completion")) {
            return d.hasKey("ok");
        }
        if (U.eq(ty, "text")) {
            return d.hasKey("tx");
        }
        return true;
    }

    //! Wire form: a complete draft as is; a pending one as ty "none" with
    //! man = true (schema-valid, and never a fabricated zero).
    function toWire(d as Dictionary) as Dictionary {
        if (complete(d)) {
            return d;
        }
        var rv = U.num(d, "rv");
        return {"ty" => "none", "rv" => rv == null ? RULE_VERSION : rv, "man" => true};
    }

    //! Short display text ("4:05", "5 + 7", "83 reps", "100 kg", "--").
    function text(d as Dictionary) as String {
        if (!complete(d)) {
            return "--";
        }
        var ty = U.str(d, "ty");
        if (U.eq(ty, "time")) {
            return Fmt.clock(U.num(d, "s") as Number);
        }
        if (U.eq(ty, "rounds_reps")) {
            return Fmt.roundsReps(U.num(d, "r") as Number, U.num(d, "x") as Number);
        }
        if (U.eq(ty, "reps")) {
            return (U.num(d, "n") as Number).format("%d") + " reps";
        }
        if (U.eq(ty, "calories")) {
            return (U.num(d, "n") as Number).format("%d") + " cal";
        }
        if (U.eq(ty, "load") || U.eq(ty, "distance")) {
            return Fmt.load(U.real(d, "v") as Float) + " " + U.str(d, "u");
        }
        if (U.eq(ty, "minutes_reps")) {
            return (U.num(d, "i") as Number).format("%d") + " + " + (U.num(d, "x") as Number).format("%d");
        }
        if (U.eq(ty, "completion")) {
            return U.flag(d, "ok", false) ? "Done" : "Not done";
        }
        if (U.eq(ty, "text")) {
            return U.head(U.str(d, "tx") as String, 16);
        }
        return "-";
    }

    //! Records an athlete edit of a measured value (cs = corrected score;
    //! measured times stay in el / act / wk, REQ-WE04/DM25).
    function setValue(d as Dictionary, key as String, value, wasMeasured as Boolean) as Void {
        var before = d.get(key);
        d.put(key, value);
        if (wasMeasured && before != null && !_same(before, value)) {
            d.put("cs", true);
        }
    }

    function _same(a, b) as Boolean {
        if (U.isNumeric(a) && U.isNumeric(b)) {
            return U.asReal(a) == U.asReal(b);
        }
        return a == b;
    }

    //! REQ-WE04: a completed For Time score cannot exceed the cap.
    function timeAllowed(seconds as Number, capSec as Number) as Boolean {
        return seconds >= 0 && seconds <= 86399 && (capSec <= 0 || seconds <= capSec);
    }
}
