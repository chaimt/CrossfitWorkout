//
// History.mc
//
// Finalized Results and SessionSummaries kept on the watch: the outbox's
// payload source and the on-watch history (REQ-WO03). Objects are
// immutable once stored (REQ-SY12 "as recorded"); `delivered` flips only on
// the phone's ack. Retention: rows newer than 14 days plus at most
// ROW_CAP rows total, pruning the oldest *delivered* rows first;
// unacknowledged objects are never pruned.
//
// Storage: "hx" = [[id, kind, title, label, t0, scoreText, delivered,
// bytes, status]] newest first, "o_<id>" = payload Dictionary.
// Pure apart from Kv; tested in HistoryTest.mc.
//
import Toybox.Lang;
import Toybox.Time;

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

    const ROW_CAP = 50;
    const RETENTION_SEC = 14 * 86400;
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

    //! Drops the oldest delivered rows beyond the retention window / cap.
    //! Rows with a t0 newer than RETENTION_SEC survive regardless of the
    //! cap; anything older than that is evicted once delivered (new row
    //! first, then oldest-first down to the cap). Unsent rows never drop.
    function _pruned(kv as Kv, hx as Array) as Array {
        var now = Time.now().value();
        hx = _dropDeliveredOlder(kv, hx, now);
        var i = hx.size() - 1;
        while (hx.size() > ROW_CAP && i >= 0) {
            var r = hx[i] as Array;
            if (r[HX_SENT] as Boolean) {
                kv.del("o_" + (r[HX_ID] as String));
                hx = U.removeAt(hx, i);
            }
            i--;
        }
        return hx;
    }

    function _dropDeliveredOlder(kv as Kv, hx as Array, now as Number) as Array {
        var i = 0;
        while (i < hx.size()) {
            var r = hx[i] as Array;
            var t0 = r[HX_T0] as Number;
            if ((r[HX_SENT] as Boolean) && t0 != null && now - t0 > RETENTION_SEC) {
                kv.del("o_" + (r[HX_ID] as String));
                hx = U.removeAt(hx, i);
            } else {
                i++;
            }
        }
        return hx;
    }

    //! Explicit time source for tests (Time.now is unavailable in :test code).
    function prune(kv as Kv, now as Number) as Void {
        kv.put("hx", _dropDeliveredOlder(kv, rows(kv), now));
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
