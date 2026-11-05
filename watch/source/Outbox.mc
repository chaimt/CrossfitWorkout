//
// Outbox.mc
//
// Durable watch -> phone data queue with ack-only delivery (REQ-WO02,
// REQ-SY04, REQ-SY13, docs/protocol.md section 7):
//
//   * one outstanding data message at a time; acks bypass this queue;
//   * an entry is removed only by the phone's application ack - SDK
//     transmit success is not delivery;
//   * a retry reuses the exact message id, timestamp and body;
//   * ack timeout 30 s; retries after 5, 15, 60 s, at most 3 per drain,
//     then the entry waits for an explicit Sync / hello / reconnect drain;
//   * automatic drains are rate-limited to one per 5 minutes;
//   * version / size / invalid / conflict errors block automatic retry.
//
// The payload is not stored here: it is the immutable History object with
// the same oid, rebuilt into the envelope at send time.
//
// Storage: "ob" = [[mid, type, oid, rev, ts, state, tries, nextAt, sentAt]].
// Time is epoch seconds supplied by the caller. Pure apart from Kv; tested
// in OutboxTest.mc.
//
import Toybox.Lang;

// Entry columns.
enum {
    OB_MID = 0,
    OB_TYPE = 1,
    OB_OID = 2,
    OB_REV = 3,
    OB_TS = 4,
    OB_STATE = 5,
    OB_TRIES = 6,
    OB_NEXT = 7,
    OB_SENT = 8
}

// Entry states.
enum {
    OS_PENDING = 0,
    OS_INFLIGHT = 1,
    OS_WAITING = 2,
    OS_BLOCKED = 3
}

module Outbox {

    const ACK_TIMEOUT = 30;
    const BACKOFF = [5, 15, 60] as Array<Number>;
    const MAX_RETRIES = 3;
    const AUTO_DRAIN_INTERVAL = 300;

    function entries(kv as Kv) as Array {
        var ob = kv.get("ob");
        return ob instanceof Array ? ob : [];
    }

    function _save(kv as Kv, ob as Array) as Boolean {
        return kv.put("ob", ob);
    }

    //! Queues a data message for object `oid`; idempotent per (type, oid).
    function enqueue(kv as Kv, type as String, oid as String, rev as Number, mid as String,
            nowSec as Number) as Boolean {
        var ob = entries(kv);
        for (var i = 0; i < ob.size(); i++) {
            var e = ob[i] as Array;
            if (U.eq(e[OB_TYPE] as String, type) && U.eq(e[OB_OID] as String, oid)) {
                return true;
            }
        }
        ob.add([mid, type, oid, rev, nowSec, OS_PENDING, 0, nowSec, 0]);
        return _save(kv, ob);
    }

    //! Next entry to transmit at `nowSec`, or null. Delivery is in queue
    //! order (results before their session summary): only the first
    //! non-blocked entry is eligible. Also expires an in-flight message
    //! whose ack did not arrive within ACK_TIMEOUT.
    function next(kv as Kv, nowSec as Number) as Array? {
        var ob = entries(kv);
        for (var i = 0; i < ob.size(); i++) {
            var e = ob[i] as Array;
            var state = e[OB_STATE];
            if (state == OS_BLOCKED) {
                continue;
            }
            if (state == OS_INFLIGHT) {
                if (nowSec - (e[OB_SENT] as Number) < ACK_TIMEOUT) {
                    return null;
                }
                _failed(e, nowSec);
                _save(kv, ob);
            }
            if (e[OB_STATE] == OS_PENDING && (e[OB_NEXT] as Number) <= nowSec) {
                return e;
            }
            return null;
        }
        return null;
    }

    //! The transmit for `mid` was started at `nowSec`.
    function markSent(kv as Kv, mid as String, nowSec as Number) as Void {
        var ob = entries(kv);
        var i = _find(ob, mid);
        if (i < 0) {
            return;
        }
        var e = ob[i] as Array;
        e[OB_STATE] = OS_INFLIGHT;
        e[OB_SENT] = nowSec;
        _save(kv, ob);
    }

    //! The SDK reported a transmit failure (or the ack timed out).
    function transmitFailed(kv as Kv, mid as String, nowSec as Number) as Void {
        var ob = entries(kv);
        var i = _find(ob, mid);
        if (i < 0) {
            return;
        }
        _failed(ob[i] as Array, nowSec);
        _save(kv, ob);
    }

    //! Schedules the next retry, or parks the entry until the next drain.
    function _failed(e as Array, nowSec as Number) as Void {
        var tries = (e[OB_TRIES] as Number) + 1;
        e[OB_TRIES] = tries;
        if (tries > MAX_RETRIES) {
            e[OB_STATE] = OS_WAITING;
            return;
        }
        e[OB_STATE] = OS_PENDING;
        e[OB_NEXT] = nowSec + BACKOFF[tries - 1];
    }

    //! Applies an ack for message `mid`. Returns the acked [type, oid] when
    //! the object counts as delivered, else null.
    function onAck(kv as Kv, mid as String?, st as String?) as Array? {
        if (mid == null) {
            return null;
        }
        var ob = entries(kv);
        var i = _find(ob, mid);
        if (i < 0) {
            return null;
        }
        var e = ob[i] as Array;
        if (U.eq(st, "ok") || U.eq(st, "already_applied") || U.eq(st, "stale")) {
            // stale: the phone already holds a newer revision of this object
            // (REQ-SY12) - nothing left to deliver.
            _save(kv, U.removeAt(ob, i));
            return [e[OB_TYPE], e[OB_OID]];
        }
        // error / conflict: stop automatic retry until corrected.
        e[OB_STATE] = OS_BLOCKED;
        _save(kv, ob);
        return null;
    }

    //! Starts a drain: every non-blocked entry becomes eligible now with a
    //! fresh retry budget. Automatic drains (app start, reconnect, hello)
    //! are rate-limited; explicit ones (Sync, sync.req, new result) are not.
    function startDrain(kv as Kv, nowSec as Number, explicit as Boolean) as Boolean {
        if (!explicit) {
            var last = U.asNum(kv.get("obd"));
            if (last != null && nowSec - last < AUTO_DRAIN_INTERVAL && nowSec >= last) {
                return false;
            }
        }
        kv.put("obd", nowSec);
        var ob = entries(kv);
        for (var i = 0; i < ob.size(); i++) {
            var e = ob[i] as Array;
            if (e[OB_STATE] == OS_PENDING || e[OB_STATE] == OS_WAITING) {
                e[OB_STATE] = OS_PENDING;
                e[OB_TRIES] = 0;
                e[OB_NEXT] = nowSec;
            }
        }
        _save(kv, ob);
        return true;
    }

    //! Explicit user Sync also unblocks errored entries for one more try.
    function unblockAll(kv as Kv, nowSec as Number) as Void {
        var ob = entries(kv);
        for (var i = 0; i < ob.size(); i++) {
            var e = ob[i] as Array;
            if (e[OB_STATE] == OS_BLOCKED) {
                e[OB_STATE] = OS_PENDING;
                e[OB_TRIES] = 0;
                e[OB_NEXT] = nowSec;
            }
        }
        _save(kv, ob);
    }

    function _find(ob as Array, mid as String) as Number {
        for (var i = 0; i < ob.size(); i++) {
            if (U.eq((ob[i] as Array)[OB_MID] as String, mid)) {
                return i;
            }
        }
        return -1;
    }

    function pendingCount(kv as Kv) as Number {
        return entries(kv).size();
    }
}
