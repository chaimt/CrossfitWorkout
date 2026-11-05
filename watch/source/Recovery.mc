//
// Recovery.mc
//
// Finalizes a session that did not end normally (REQ-WO06, REQ-WN07):
// crash, power loss, or app exit with the session open. Driven only by the
// last valid checkpoint generation plus the frozen plan snapshot:
//
//   * already-finalized part results keep their ids / content / status
//     (they are in History and the Outbox already);
//   * the unfinished part, if it had started, becomes an "interrupted"
//     Result with the observations known at the checkpoint - the unknown
//     timer tail is NOT added (powered-off time is never work) and HR /
//     calories are omitted (unavailable, REQ-WR10);
//   * unreached parts are "skipped" in the summary, never fabricated;
//   * the session summary is written; the checkpoint is cleared only after
//     the interrupted result and the summary are both durable.
//
// Idempotent: the interrupted result id comes from the checkpoint, and
// History.add / Outbox.enqueue ignore ids they already hold, so a crash
// during recovery simply re-runs it. Tested in RecoveryTest.mc.
//
import Toybox.Lang;

module Recovery {

    //! Recovers an open session. Returns {"sid", "rid" (interrupted result
    //! id or ""), "title"} or null when there was nothing to recover.
    function run(kv as Kv, fit as String) as Dictionary? {
        var ck = Checkpoint.load(kv);
        if (ck == null) {
            return null;
        }
        var plan = kv.get("snap");
        if (!(plan instanceof Dictionary)) {
            // Without the snapshot nothing can be rebuilt; never keep a
            // checkpoint that blocks every future Start.
            Checkpoint.clear(kv);
            return null;
        }
        var sid = U.str(ck, "sid") as String;
        var sel = U.arr(ck, "sel") as Array;
        var cur = U.num(ck, "cur") as Number;
        var entries = U.arr(ck, "ent");
        entries = entries == null ? [] : entries.slice(0, null);
        var parts = plan["p"] as Array;
        var cl = U.arr(ck, "cl") as Array;
        var state = cl[0] as Number;
        var resume = cl[1] as Number;
        var activeMs = cl[2] as Number;
        var partRunMs = cl[3] as Number;
        var wallMs = cl[5] as Number;
        var t0 = U.num(ck, "t0") as Number;
        var rid = "";
        var title = U.str(plan, "ti");
        var nowSec = t0 + wallMs / 1000;

        var logical = state == CS_PAUSED ? resume : state;
        var midPart = U.flag(ck, "ps", false) && cur < sel.size() && entries.size() <= cur
            && (logical == CS_RUN || logical == CS_ENTRY || logical == CS_PREP);
        if (midPart) {
            var part = parts[sel[cur] as Number] as Dictionary;
            var o = _interrupted(ck, plan, part, partRunMs, activeMs, wallMs);
            var r = ResultBuilder.result(plan, sid, o);
            var label = U.str(part, "l");
            var ok = History.add(kv, o.resultId, History.KIND_RESULT, title == null ? "" : title,
                label == null ? "" : label, o.t0, Score.text(o.score), o.status, r);
            if (!ok || !Outbox.enqueue(kv, "result.put", o.resultId, 1, Uuid.v4(), nowSec)) {
                // Keep the checkpoint: nothing accepted is lost; retry later.
                return null;
            }
            entries.add([o.partId, o.resultId, o.status]);
            rid = o.resultId;
        }
        for (var i = entries.size(); i < sel.size(); i++) {
            entries.add([U.str(parts[sel[i] as Number] as Dictionary, "id"), "", "skipped"]);
        }
        var start = t0 == 0 ? nowSec : t0;
        var s = ResultBuilder.session(plan, sid, entries, start, start + wallMs / 1000, wallMs, activeMs,
            null, null, U.str(ck, "rso"), fit);
        if (!History.add(kv, sid, History.KIND_SESSION, title == null ? "" : title, "", start,
                "interrupted", "interrupted", s)
                || !Outbox.enqueue(kv, "session.put", sid, 1, Uuid.v4(), nowSec)) {
            return null;
        }
        Checkpoint.clear(kv);
        kv.del("snap");
        return {"sid" => sid, "rid" => rid, "title" => title == null ? "" : title};
    }

    //! The interrupted part's Result from checkpointed observations only.
    function _interrupted(ck as Dictionary, plan as Dictionary, part as Dictionary, partRunMs as Number,
            activeMs as Number, wallMs as Number) as PartOutcome {
        var o = new PartOutcome();
        o.resultId = U.str(ck, "rid") as String;
        o.partId = U.str(part, "id") as String;
        o.status = "interrupted";
        var pt0 = U.num(ck, "pt0");
        o.t0 = pt0 == null ? 0 : pt0;
        var pe0 = U.num(ck, "pe0");
        var pa0 = U.num(ck, "pa0");
        o.elMs = wallMs - (pe0 == null ? 0 : pe0);
        o.actMs = activeMs - (pa0 == null ? 0 : pa0);
        o.level = U.str(ck, "dlv") != null ? U.str(ck, "dlv") : U.str(part, "lv");
        o.fit = "unknown";
        o.edits = U.dict(ck, "edt");
        var draft = U.dict(ck, "dsc");
        if (PlanCodec.isStrength(U.str(part, "mode"))) {
            var u = U.str(plan, "u");
            var sr = new StrengthRun(part, u == null ? "kg" : u);
            var logs = U.arr(ck, "sl");
            if (logs != null) {
                sr.logs = logs;
            }
            o.sets = sr.logs;
            o.wkMs = partRunMs;
            o.score = draft != null ? draft : Score.measuredStrength(part, sr);
        } else {
            var segs = U.arr(part, "seg");
            var e = o.edits;
            if (e != null) {
                segs = Edits.apply(part, e);
            }
            var prog = new Program(segs == null ? [] : segs);
            var m = new MetconRun(part, prog);
            var ev = U.arr(ck, "ev");
            if (ev != null) {
                m.movementMode = U.flag(ck, "mvm", m.movementMode);
                m.restore(ev);
            }
            o.events = m.events;
            var wk = U.num(ck, "wk");
            o.wkMs = wk == null ? Engine.stateAt(prog, 0, partRunMs).scoreMs : wk;
            o.score = draft != null ? draft : Score.measured(part, m, "interrupted", o.wkMs);
        }
        return o;
    }
}
