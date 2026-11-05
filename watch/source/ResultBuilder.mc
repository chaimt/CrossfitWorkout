//
// ResultBuilder.mc
//
// Builds the Result (result.put payload) and SessionSummary (session.put
// payload) exactly in the key shapes of docs/protocol.md section 5 and the
// golden fixtures shared/fixtures/wire/valid/result.put.*.json /
// session.put.json. Absent values are omitted keys, never null or 0
// (REQ-WE03, REQ-WR10). Also the storage admission check (REQ-WO05).
// Pure; tested in ResultBuilderTest.mc.
//
import Toybox.Lang;

//! Everything known about one part attempt; filled by the session.
class PartOutcome {
    var resultId as String = "";
    var partId as String = "";
    var status as String = "completed";
    var t0 as Number = 0;
    var elMs as Number = 0;
    var actMs as Number = 0;
    var wkMs as Number = 0;
    var score as Dictionary = {"ty" => "none", "rv" => 1};
    var level as String? = null;
    var events as Array? = null;
    var sets as Array? = null;
    //! [[nodeId, value, unit, count]] actual loads of loaded metcon items.
    var loads as Array? = null;
    var edits as Dictionary? = null;
    var hrAvg as Number? = null;
    var hrMax as Number? = null;
    var calories as Number? = null;
    var fit as String = "off";
    var note as String? = null;

    function initialize() {
    }
}

module ResultBuilder {

    const SCHEMA_VERSION = 1;
    //! Phone-side envelope budget (docs/protocol.md section 1).
    const ENVELOPE_BUDGET = 3584;
    //! Envelope keys around a payload: v, t, id, ts, g, oid, rev, p.
    const ENVELOPE_OVERHEAD = 160;
    //! Worst-case bytes of one progress event [ix,"undo",7200000].
    const EVENT_BYTES = 21;
    //! Reserved for score, hr, cal, note (256 B max, REQ-WO05) and edits.
    const VARIABLE_RESERVE = 420;
    const MAX_EVENTS = 200;
    const MAX_NOTE_BYTES = 256;

    function result(plan as Dictionary, sessionId as String, o as PartOutcome) as Dictionary {
        var r = {
            "sv" => SCHEMA_VERSION,
            "id" => o.resultId,
            "rr" => 1,
            "sid" => sessionId,
            "pl" => U.str(plan, "id"),
            "pr" => U.num(plan, "rev"),
            "wid" => U.str(plan, "wid"),
            "wr" => U.num(plan, "wr"),
            "pt" => o.partId,
            "st" => o.status,
            "t0" => o.t0,
            "el" => Fmt.countUpSeconds(o.elMs),
            "act" => Fmt.countUpSeconds(o.actMs),
            "wk" => Fmt.countUpSeconds(o.wkMs),
            "sc" => Score.toWire(o.score),
            "fit" => o.fit,
            "dev" => Proto.DEVICE,
            "app" => Proto.APP_VERSION
        };
        if (o.level != null) {
            r.put("lv", o.level);
        }
        if (o.events != null && o.events.size() > 0) {
            r.put("ev", o.events);
        }
        if (o.sets != null && o.sets.size() > 0) {
            r.put("sets", o.sets);
        }
        if (o.loads != null && o.loads.size() > 0) {
            r.put("ld", o.loads);
        }
        if (o.edits != null && o.edits.size() > 0) {
            r.put("edt", o.edits);
        }
        if (o.hrAvg != null && o.hrMax != null) {
            r.put("hr", [o.hrAvg, o.hrMax]);
        }
        if (o.calories != null && o.calories >= 0) {
            r.put("cal", o.calories);
        }
        if (o.note != null && WireSize.utf8Bytes(o.note) <= MAX_NOTE_BYTES) {
            r.put("nt", o.note);
        }
        return r;
    }

    //! Session summary (section 5.4). `entries` = [[partId, resultId|"",
    //! status]] in run order; skipped / unreached parts have "".
    function session(plan as Dictionary, sessionId as String, entries as Array, t0 as Number,
            t1 as Number, elMs as Number, actMs as Number, hr as Array?, cal as Number?,
            restartOf as String?, fit as String) as Dictionary {
        var s = {
            "sv" => SCHEMA_VERSION,
            "id" => sessionId,
            "rev" => 1,
            "pl" => U.str(plan, "id"),
            "pr" => U.num(plan, "rev"),
            "e" => entries,
            "t0" => t0,
            "t1" => t1,
            "el" => Fmt.countUpSeconds(elMs),
            "act" => Fmt.countUpSeconds(actMs),
            "fit" => fit
        };
        if (hr != null) {
            s.put("hr", hr);
        }
        if (cal != null && cal >= 0) {
            s.put("cal", cal);
        }
        if (restartOf != null) {
            s.put("rso", restartOf);
        }
        return s;
    }

    //! Event bound for a part so its finished Result still fits one
    //! result.put envelope (the MVP watch does not chunk, protocol 3.8):
    //! min(200, (budget - fixed part of the result) / bytes per event).
    function eventLimit(plan as Dictionary, part as Dictionary) as Number {
        var o = new PartOutcome();
        o.resultId = "00000000-0000-4000-8000-000000000000";
        o.partId = U.str(part, "id") as String;
        o.status = "stopped_early";
        o.t0 = 1793700000;
        o.elMs = 7200000;
        o.actMs = 7200000;
        o.wkMs = 7200000;
        o.loads = _worstLoads(part);
        var lv = U.str(part, "lv");
        o.level = lv;
        var base = WireSize.size(result(plan, o.resultId, o)) + ENVELOPE_OVERHEAD + VARIABLE_RESERVE;
        var n = (ENVELOPE_BUDGET - base) / EVENT_BYTES;
        return U.clamp(n, 0, MAX_EVENTS);
    }

    function _worstLoads(part as Dictionary) as Array {
        var ld = U.arr(part, "ld");
        var out = [];
        if (ld == null) {
            return out;
        }
        for (var i = 0; i < ld.size(); i++) {
            out.add([U.str(ld[i] as Dictionary, "id"), 1100.25, "kg", 4]);
        }
        return out;
    }
}

//! Storage budget and admission (REQ-WP04, REQ-WO05).
module Budget {

    //! Conservative total for all app data (Appendix I.1: design to 128 KiB).
    const TOTAL = 131072;
    //! Indices, settings, outbox, tombstones.
    const RESERVE = 8192;
    //! Worst-case checkpoint generation (part draft + finished entries).
    const CHECKPOINT = 6144;

    function used(kv as Kv) as Number {
        return Inbox.bytes(kv) + History.bytes(kv);
    }

    function free(kv as Kv) as Number {
        var f = TOTAL - RESERVE - used(kv);
        return f < 0 ? 0 : f;
    }

    //! Worst-case bytes a session over `parts` (plan part dictionaries)
    //! needs before Start: every result at the single-message bound, the
    //! summary, two checkpoint generations and the frozen plan snapshot.
    function required(planBytes as Number, partCount as Number) as Number {
        var results = partCount * ResultBuilder.ENVELOPE_BUDGET;
        var summary = 400 + partCount * 110;
        return results + summary + 2 * CHECKPOINT + planBytes;
    }

    //! Admission: true when the session fits, evicting only acknowledged
    //! history if needed. False = block Start (Sync results first).
    function admit(kv as Kv, planBytes as Number, partCount as Number) as Boolean {
        var need = required(planBytes, partCount);
        var have = free(kv);
        if (have >= need) {
            return true;
        }
        if (History.evictable(kv) < need - have) {
            return false;
        }
        History.freeUp(kv, need - have);
        return free(kv) >= need;
    }
}
