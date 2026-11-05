//
// History.mc
//
// Finalized Results and SessionSummaries kept on the watch: the outbox's
// payload source and the on-watch history (REQ-WO03). Objects are
// immutable once stored (REQ-SY12 "as recorded"); `delivered` flips only on
// the phone's ack. Retention: at most 50 rows, pruning the oldest
// *delivered* rows first; unacknowledged objects are never pruned.
//
// Storage: "hx" = [[id, kind, title, label, t0, scoreText, delivered,
// bytes, status]] newest first, "o_<id>" = payload Dictionary.
// Pure apart from Kv; tested in HistoryTest.mc.
//
import Toybox.Lang;

// History row columns.
enum {
    HX_ID = 0,
    HX_KIND = 1,
    HX_TITLE = 2,
    HX_LABEL = 3,
    HX_T0 = 4,
    HX_SCORE = 5,
    HX_SENT = 6,
    HX_BYTES = 7,
    HX_STATUS = 8
}

module History {

    const MAX_ROWS = 50;
    const KIND_RESULT = "r";
    const KIND_SESSION = "s";

    function rows(kv as Kv) as Array {
        var hx = kv.get("hx");
        return hx instanceof Array ? hx : [];
    }

    function object(kv as Kv, id as String) as Dictionary? {
        var o = kv.get("o_" + id);
        return o instanceof Dictionary ? o : null;
    }

    function find(kv as Kv, id as String) as Number {
        var hx = rows(kv);
        for (var i = 0; i < hx.size(); i++) {
            if (U.eq((hx[i] as Array)[HX_ID] as String, id)) {
                return i;
            }
        }
        return -1;
    }

    //! Stores an immutable object; idempotent for an id already stored
    //! (recovery may run twice). Returns false when storage refused it.
    function add(kv as Kv, id as String, kind as String, title as String, label as String,
            t0 as Number, scoreText as String, status as String, payload as Dictionary) as Boolean {
        if (find(kv, id) >= 0) {
            return true;
        }
        if (!kv.put("o_" + id, payload)) {
            return false;
        }
        var row = [id, kind, title, label, t0, scoreText, false, WireSize.size(payload), status];
        var next = [row];
        next.addAll(rows(kv));
        next = _pruned(kv, next);
        return kv.put("hx", next);
    }

    //! Drops the oldest delivered rows beyond MAX_ROWS.
    function _pruned(kv as Kv, hx as Array) as Array {
        var i = hx.size() - 1;
        while (hx.size() > MAX_ROWS && i >= 0) {
            var r = hx[i] as Array;
            if (r[HX_SENT] as Boolean) {
                kv.del("o_" + (r[HX_ID] as String));
                hx = U.removeAt(hx, i);
            }
            i--;
        }
        return hx;
    }

    function markDelivered(kv as Kv, id as String) as Void {
        var hx = rows(kv);
        var i = find(kv, id);
        if (i < 0) {
            return;
        }
        var row = hx[i] as Array;
        row[HX_SENT] = true;
        kv.put("hx", hx);
    }

    function isDelivered(kv as Kv, id as String) as Boolean {
        var i = find(kv, id);
        return i >= 0 && ((rows(kv)[i] as Array)[HX_SENT] as Boolean);
    }

    //! Bytes held by stored objects (storage budget).
    function bytes(kv as Kv) as Number {
        var hx = rows(kv);
        var n = 0;
        for (var i = 0; i < hx.size(); i++) {
            n += (hx[i] as Array)[HX_BYTES] as Number;
        }
        return n;
    }

    //! Count of objects not yet acknowledged by the phone (hello `rc`).
    function unsent(kv as Kv) as Number {
        var hx = rows(kv);
        var n = 0;
        for (var i = 0; i < hx.size(); i++) {
            if (!((hx[i] as Array)[HX_SENT] as Boolean)) {
                n += 1;
            }
        }
        return n;
    }

    //! Frees at least `need` bytes by deleting the oldest delivered objects
    //! (REQ-WO05: evict only acknowledged history). Returns bytes freed.
    function freeUp(kv as Kv, need as Number) as Number {
        var hx = rows(kv);
        var freed = 0;
        var i = hx.size() - 1;
        while (freed < need && i >= 0) {
            var r = hx[i] as Array;
            if (r[HX_SENT] as Boolean) {
                freed += r[HX_BYTES] as Number;
                kv.del("o_" + (r[HX_ID] as String));
                hx = U.removeAt(hx, i);
            }
            i--;
        }
        if (freed > 0) {
            kv.put("hx", hx);
        }
        return freed;
    }

    //! Bytes of delivered (evictable) history.
    function evictable(kv as Kv) as Number {
        var hx = rows(kv);
        var n = 0;
        for (var i = 0; i < hx.size(); i++) {
            var r = hx[i] as Array;
            if (r[HX_SENT] as Boolean) {
                n += r[HX_BYTES] as Number;
            }
        }
        return n;
    }
}
