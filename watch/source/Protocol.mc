//
// Protocol.mc
//
// Wire envelope building and the phone -> watch message router
// (docs/protocol.md sections 2, 3, 7; REQ-SY02/SY03/SY07/SY11/SY14).
//
// Proto.handle() is pure apart from the Kv adapter: it validates the
// envelope, applies the message to Inbox / Settings / Outbox / History and
// returns the replies to send plus follow-up actions. The Comms adapter
// (Comms.mc) does the actual transmitting, so every rule here is
// unit-tested in ProtocolTest.mc without a phone.
//
import Toybox.Lang;

module Proto {

    const VERSION = 1;
    const APP_VERSION = "0.1.2";
    const DEVICE = "fr965";
    const CAPABILITIES = ["for_time", "amrap", "emom", "tabata", "intervals", "max_effort",
        "strength_sets", "build_to_max", "manual"] as Array<String>;

    //! Builds an envelope, omitting absent keys (no nulls on the wire).
    function envelope(t as String, mid as String, ts as Number, g as Number, oid as String?,
            rev as Number?, p as Dictionary?) as Dictionary {
        var env = {"v" => VERSION, "t" => t, "id" => mid, "ts" => ts, "g" => g};
        if (oid != null) {
            env.put("oid", oid);
        }
        if (rev != null) {
            env.put("rev", rev);
        }
        if (p != null) {
            env.put("p", p);
        }
        return env;
    }

    //! An acknowledgment of message `origMid` (section 3.1).
    function ack(t as String, origMid as String?, oid as String?, rev as Number?, st as String,
            rs as String?, g as Number, ts as Number) as Dictionary {
        var p = {"mid" => origMid == null ? "" : origMid, "st" => st};
        if (rs != null) {
            p.put("rs", rs);
        }
        return envelope(t, Uuid.v4(), ts, g < 1 ? 1 : g, oid, rev, p);
    }

    //! The hello inventory (section 3.2): stored plans, tombstones, free
    //! bytes, unacknowledged objects and supported modes. Informational
    //! only - it never marks anything delivered.
    function hello(kv as Kv, ts as Number, free as Number) as Dictionary {
        var g = Inbox.generation(kv);
        var tb = Inbox.tombstones(kv);
        var del = [];
        for (var i = 0; i < tb.size(); i++) {
            del.add(tb[i]);
        }
        var p = {
            "pv" => [VERSION],
            "app" => APP_VERSION,
            "dev" => DEVICE,
            "inv" => Inbox.inventory(kv),
            "del" => del,
            "free" => free,
            "rc" => History.unsent(kv),
            "cap" => CAPABILITIES
        };
        return envelope("hello", Uuid.v4(), ts, g < 1 ? 1 : g, null, null, p);
    }

    //! Data message for an outbox entry, rebuilt from the stored object so
    //! a retry carries the identical body (REQ-SY02). Null when the object
    //! is gone (should not happen: unsent objects are never pruned).
    function dataMessage(kv as Kv, entry as Array) as Dictionary? {
        var oid = entry[OB_OID] as String;
        var body = History.object(kv, oid);
        if (body == null) {
            return null;
        }
        var g = entry.size() > OB_GENERATION ? entry[OB_GENERATION] as Number : Outbox.maxGeneration(kv);
        return envelope(entry[OB_TYPE] as String, entry[OB_MID] as String, entry[OB_TS] as Number,
            g < 1 ? 1 : g, oid, entry[OB_REV] as Number, body);
    }

    //! Routes one phone message. Returns {"out" => [replies], "drain" =>
    //! Boolean, "hello" => Boolean, "inbox" => Boolean (plans changed),
    //! "delivered" => Number}. `free` = admissible bytes for new plans.
    function handle(kv as Kv, msg, nowSec as Number, free as Number) as Dictionary {
        var res = {"out" => [], "drain" => false, "hello" => false, "inbox" => false, "delivered" => 0};
        if (!(msg instanceof Dictionary)) {
            return res;
        }
        var t = U.str(msg, "t");
        var mid = U.str(msg, "id");
        var oid = U.str(msg, "oid");
        var rev = U.num(msg, "rev");
        var g = U.num(msg, "g");
        var v = U.num(msg, "v");
        var ackType = _ackTypeFor(t);
        var myG = Inbox.generation(kv);
        if (v == null || v != VERSION) {
            // REQ-SY07: unknown protocol major -> error ack in the minimal
            // envelope, so the phone can ask for a watch app update.
            if (ackType != null) {
                (res["out"] as Array).add(ack(ackType, mid, oid, rev, "error", "version", myG, nowSec));
            }
            return res;
        }
        if (t == null || mid == null) {
            return res;
        }
        if (g == null || g < 1) {
            if (ackType != null) {
                (res["out"] as Array).add(ack(ackType, mid, oid, rev, "error", "invalid", myG, nowSec));
            }
            return res;
        }
        if (g < myG) {
            // Older pairing generation (REQ-DM27/SY11): never applied.
            if (ackType != null) {
                (res["out"] as Array).add(ack(ackType, mid, oid, rev, "stale", "generation", myG, nowSec));
            }
            return res;
        }
        if (t.equals("plan.put")) {
            _planPut(kv, msg, mid, oid, rev, g, nowSec, free, res);
        } else if (t.equals("plan.del")) {
            var r = Inbox.acceptGeneration(kv, g) ? Inbox.del(kv, oid, rev) : ["error", "capacity"];
            _reply(res, "plan.ack", mid, oid, rev, r, g, nowSec);
            res["inbox"] = U.eq(r[0] as String, "ok");
        } else if (t.equals("profile.put")) {
            var r = !Inbox.acceptGeneration(kv, g) ? ["error", "capacity"]
                : (rev == null ? ["error", "invalid"] : Settings.applyProfile(rev, msg.get("p")));
            _reply(res, "profile.ack", mid, oid, rev, r, g, nowSec);
        } else if (t.equals("result.ack") || t.equals("session.ack")) {
            var p = U.dict(msg, "p");
            var done = Outbox.onAck(kv, U.str(p, "mid"), U.str(p, "st"));
            if (done != null) {
                History.markDelivered(kv, done[1] as String);
                res["delivered"] = 1;
            }
            // The queue moved on: send the next entry right away.
            res["drain"] = true;
        } else if (t.equals("sync.req")) {
            Outbox.startDrain(kv, nowSec, true);
            var p = {"rc" => History.unsent(kv)};
            (res["out"] as Array).add(envelope("sync.ack", Uuid.v4(), nowSec, g, null, null, p));
            res["drain"] = true;
            res["hello"] = true;
        }
        // result.chunk.ack and unknown types: ignored (MVP watch never
        // sends result.chunk, docs/protocol.md section 3.8).
        return res;
    }

    function _planPut(kv as Kv, msg as Dictionary, mid as String, oid as String?, rev as Number?,
            g as Number, nowSec as Number, free as Number, res as Dictionary) as Void {
        var r = null;
        if (WireSize.size(msg) > WireSize.MAX_MESSAGE) {
            r = ["error", "size"];
        } else {
            r = Inbox.acceptGeneration(kv, g)
                ? Inbox.put(kv, oid, rev, msg.get("p"), nowSec, free) : ["error", "capacity"];
        }
        _reply(res, "plan.ack", mid, oid, rev, r, g, nowSec);
        res["inbox"] = U.eq(r[0] as String, "ok");
    }

    function _reply(res as Dictionary, t as String, mid as String, oid as String?, rev as Number?,
            r as Array, g as Number, nowSec as Number) as Void {
        (res["out"] as Array).add(ack(t, mid, oid, rev, r[0] as String, r[1] as String?, g, nowSec));
    }

    //! Ack type for a phone message type; null for acks/control messages
    //! (no acknowledgments of acknowledgments).
    function _ackTypeFor(t as String?) as String? {
        if (U.eq(t, "plan.put") || U.eq(t, "plan.del")) {
            return "plan.ack";
        }
        if (U.eq(t, "profile.put")) {
            return "profile.ack";
        }
        return null;
    }
}
