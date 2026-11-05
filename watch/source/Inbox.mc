//
// Inbox.mc
//
// Durable plan inbox with revision / tombstone / generation rules
// (REQ-SY11, REQ-SY14, docs/protocol.md section 3.1):
//
//   * validate fully BEFORE committing anything (never a partial plan);
//   * same oid + rev + identical content -> already_applied (safe re-ack);
//     same oid + rev + different content -> conflict; lower rev -> stale;
//   * plan.del stores a revisioned tombstone; a put with rev <= tombstone
//     rev is stale, so a delayed put cannot resurrect a deleted plan;
//   * a message from an older pairing generation is stale ("generation");
//   * at most 10 plans and the storage budget (REQ-WP04) -> "capacity".
//
// Commit order: plan record first, then the index. The index is the
// authority, so a crash between the two writes leaves an orphan record
// that is simply overwritten later - never a half-visible plan.
// A run in progress uses its own frozen snapshot (Session), so replacing or
// deleting an inbox plan never alters an active timer.
//
// Storage: "ix" = [[id, rev, receivedTs, title, date, parts, bytes, tz]],
// "tb" = [[id, rev]], "gen" = generation, "p_<id>" = {"rev", "h", "plan"}.
// Pure apart from Kv; tested in InboxTest.mc.
//
import Toybox.Lang;

// Index row columns.
enum {
    IX_ID = 0,
    IX_REV = 1,
    IX_RECV = 2,
    IX_TITLE = 3,
    IX_DATE = 4,
    IX_PARTS = 5,
    IX_BYTES = 6,
    IX_TZ = 7
}

module Inbox {

    const MAX_PLANS = 10;
    const MAX_TOMBSTONES = 20;

    //! Inbox rows, newest received first.
    function index(kv as Kv) as Array {
        var ix = kv.get("ix");
        return ix instanceof Array ? ix : [];
    }

    function tombstones(kv as Kv) as Array {
        var tb = kv.get("tb");
        return tb instanceof Array ? tb : [];
    }

    function generation(kv as Kv) as Number {
        var g = U.asNum(kv.get("gen"));
        return g == null ? 0 : g;
    }

    //! Checks the pairing generation `g`; adopts a newer one. True = accept.
    function acceptGeneration(kv as Kv, g as Number?) as Boolean {
        if (g == null || g < 1) {
            return false;
        }
        var cur = generation(kv);
        if (g < cur) {
            return false;
        }
        if (g > cur) {
            kv.put("gen", g);
        }
        return true;
    }

    function plan(kv as Kv, id as String) as Dictionary? {
        var rec = kv.get("p_" + id);
        if (!(rec instanceof Dictionary)) {
            return null;
        }
        return U.dict(rec, "plan");
    }

    function _row(kv as Kv, id as String) as Number {
        var ix = index(kv);
        for (var i = 0; i < ix.size(); i++) {
            if (U.eq((ix[i] as Array)[IX_ID] as String, id)) {
                return i;
            }
        }
        return -1;
    }

    function _tombRev(kv as Kv, id as String) as Number {
        var tb = tombstones(kv);
        for (var i = 0; i < tb.size(); i++) {
            var t = tb[i] as Array;
            if (U.eq(t[0] as String, id)) {
                return t[1] as Number;
            }
        }
        return 0;
    }

    //! Total bytes of stored plans (storage budget).
    function bytes(kv as Kv) as Number {
        var ix = index(kv);
        var n = 0;
        for (var i = 0; i < ix.size(); i++) {
            n += (ix[i] as Array)[IX_BYTES] as Number;
        }
        return n;
    }

    //! Applies plan.put for (`oid`, `rev`) with payload `p`. `freeBytes`
    //! is what the storage budget can still admit. Returns [st, rs].
    function put(kv as Kv, oid as String?, rev as Number?, p, nowSec as Number,
            freeBytes as Number) as Array {
        if (oid == null || rev == null || rev < 1) {
            return ["error", "invalid"];
        }
        var reason = PlanCodec.validate(p);
        if (reason != null) {
            return ["error", reason];
        }
        var plan = p as Dictionary;
        if (!U.eq(U.str(plan, "id"), oid) || U.num(plan, "rev") != rev) {
            return ["error", "invalid"];
        }
        if (rev <= _tombRev(kv, oid)) {
            return ["stale", "deleted"];
        }
        var h = WireSize.hash(plan);
        var row = _row(kv, oid);
        var ix = index(kv);
        var oldBytes = 0;
        if (row >= 0) {
            var r = ix[row] as Array;
            var storedRev = r[IX_REV] as Number;
            if (rev < storedRev) {
                return ["stale", "revision"];
            }
            if (rev == storedRev) {
                var rec = kv.get("p_" + oid);
                var same = rec instanceof Dictionary && U.num(rec, "h") == h;
                return same ? ["already_applied", null] : ["conflict", "conflict"];
            }
            oldBytes = r[IX_BYTES] as Number;
        } else if (ix.size() >= MAX_PLANS) {
            return ["error", "capacity"];
        }
        var size = WireSize.size(plan);
        if (size - oldBytes > freeBytes) {
            return ["error", "capacity"];
        }
        if (!kv.put("p_" + oid, {"rev" => rev, "h" => h, "plan" => plan})) {
            return ["error", "capacity"];
        }
        var parts = U.arr(plan, "p") as Array;
        var newRow = [oid, rev, nowSec, U.str(plan, "ti"), U.str(plan, "date"), parts.size(), size,
            U.str(plan, "tz")];
        var next = [newRow];
        for (var i = 0; i < ix.size(); i++) {
            if (i != row) {
                next.add(ix[i]);
            }
        }
        if (!kv.put("ix", next)) {
            return ["error", "capacity"];
        }
        // A newer put supersedes an older tombstone of the same plan.
        _dropTombstone(kv, oid);
        return ["ok", null];
    }

    //! Applies plan.del (`oid`, `rev`): tombstone + removal. Returns [st, rs].
    function del(kv as Kv, oid as String?, rev as Number?) as Array {
        if (oid == null || rev == null || rev < 1) {
            return ["error", "invalid"];
        }
        var tomb = _tombRev(kv, oid);
        if (tomb >= rev) {
            return tomb == rev ? ["already_applied", null] : ["stale", "revision"];
        }
        var row = _row(kv, oid);
        if (row >= 0 && ((index(kv)[row] as Array)[IX_REV] as Number) > rev) {
            return ["stale", "revision"];
        }
        if (!_addTombstone(kv, oid, rev)) {
            return ["error", "capacity"];
        }
        _remove(kv, oid);
        return ["ok", null];
    }

    //! Watch-side delete (REQ-WN04): tombstoned at the stored revision so a
    //! resend of the same revision cannot bring it back; hello reports it.
    function deleteLocal(kv as Kv, id as String) as Void {
        var row = _row(kv, id);
        if (row < 0) {
            return;
        }
        _addTombstone(kv, id, (index(kv)[row] as Array)[IX_REV] as Number);
        _remove(kv, id);
    }

    function _remove(kv as Kv, id as String) as Void {
        var row = _row(kv, id);
        if (row >= 0) {
            kv.put("ix", U.removeAt(index(kv), row));
        }
        kv.del("p_" + id);
    }

    function _addTombstone(kv as Kv, id as String, rev as Number) as Boolean {
        var tb = tombstones(kv);
        var out = [[id, rev]];
        for (var i = 0; i < tb.size() && out.size() < MAX_TOMBSTONES; i++) {
            if (!U.eq((tb[i] as Array)[0] as String, id)) {
                out.add(tb[i]);
            }
        }
        return kv.put("tb", out);
    }

    function _dropTombstone(kv as Kv, id as String) as Void {
        var tb = tombstones(kv);
        for (var i = 0; i < tb.size(); i++) {
            if (U.eq((tb[i] as Array)[0] as String, id)) {
                kv.put("tb", U.removeAt(tb, i));
                return;
            }
        }
    }

    //! hello `inv`: [[id, rev], ...].
    function inventory(kv as Kv) as Array {
        var ix = index(kv);
        var out = [];
        for (var i = 0; i < ix.size(); i++) {
            var r = ix[i] as Array;
            out.add([r[IX_ID], r[IX_REV]]);
        }
        return out;
    }

    //! REQ-WN01 Today: most recently received plan dated `today`.
    function todayRow(kv as Kv, today as String) as Array? {
        var ix = index(kv);
        for (var i = 0; i < ix.size(); i++) {
            var r = ix[i] as Array;
            if (U.eq(r[IX_DATE] as String?, today)) {
                return r;
            }
        }
        return null;
    }
}
