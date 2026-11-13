//
// ProtocolTest.mc
//
// Message routing and ack shapes (docs/protocol.md sections 2-3, REQ-SY03/
// SY07/SY11/SY14) and Result / SessionSummary key shapes against the
// golden fixtures result.put.capped.json, result.put.strength.json and
// session.put.json (REQ-TS03).
//
import Toybox.Lang;
import Toybox.Test;


(:test)
function testPlanPutAckAndRetry(logger as Test.Logger) as Boolean {
    var kv = new MemKv();
    var msg = Fx.planPut();
    var res = Proto.handle(kv, msg, 1793700001, 100000);
    var ack = Fx.ackOf(res);
    Fx.eqs("plan.ack", ack["t"] as String);
    Fx.eqs("10000000-0000-4000-8000-000000000001", ack["oid"] as String);
    Test.assertEqual(2, ack["rev"]);
    Test.assertEqual(1, ack["v"]);
    Test.assertEqual(1, ack["g"]);
    var p = ack["p"] as Dictionary;
    Fx.eqs("60000000-0000-4000-8000-000000000001", p["mid"] as String);
    Fx.eqs("ok", p["st"] as String);
    Test.assert(!p.hasKey("rs"));
    Test.assert(res["inbox"] as Boolean);
    // A lost ack -> the phone retries the same message: safe re-ack.
    var again = Fx.ackOf(Proto.handle(kv, Fx.planPut(), 1793700002, 100000));
    Fx.eqs("already_applied", (again["p"] as Dictionary)["st"] as String);
    return true;
}

(:test)
function testUnknownVersionAndGenerationRules(logger as Test.Logger) as Boolean {
    var kv = new MemKv();
    var msg = Fx.planPut();
    msg["v"] = 2;
    var ack = Fx.ackOf(Proto.handle(kv, msg, 1, 100000));
    Fx.eqs("error", (ack["p"] as Dictionary)["st"] as String);
    Fx.eqs("version", (ack["p"] as Dictionary)["rs"] as String);
    Test.assertEqual(0, Inbox.index(kv).size());
    // Plan schema version 2 -> also an error "version" (REQ-SY07).
    msg = Fx.planPut();
    (msg["p"] as Dictionary).put("sv", 2);
    ack = Fx.ackOf(Proto.handle(kv, msg, 1, 100000));
    Fx.eqs("version", (ack["p"] as Dictionary)["rs"] as String);
    // Generation 2 adopted; a late generation-1 message is stale.
    msg = Fx.planPut();
    msg["g"] = 2;
    Fx.eqs("ok", ((Fx.ackOf(Proto.handle(kv, msg, 1, 100000)))["p"] as Dictionary)["st"] as String);
    msg = Fx.planPut();
    ack = Fx.ackOf(Proto.handle(kv, msg, 1, 100000));
    Fx.eqs("stale", (ack["p"] as Dictionary)["st"] as String);
    Fx.eqs("generation", (ack["p"] as Dictionary)["rs"] as String);
    // envelope.missing-generation.json (sync.req without g): ignored.
    var res = Proto.handle(kv, {"v" => 1, "t" => "sync.req", "id" => "x", "ts" => 1}, 1, 100000);
    Test.assertEqual(0, (res["out"] as Array).size());
    return true;
}

(:test)
function testPlanDelSyncReqAndResultAck(logger as Test.Logger) as Boolean {
    var kv = new MemKv();
    Proto.handle(kv, Fx.planPut(), 1, 100000);
    var del = {"v" => 1, "t" => "plan.del", "id" => "60000000-0000-4000-8000-000000000003", "ts" => 2,
        "g" => 1, "oid" => "10000000-0000-4000-8000-000000000001", "rev" => 3};
    var ack = Fx.ackOf(Proto.handle(kv, del, 2, 100000));
    Fx.eqs("plan.ack", ack["t"] as String);
    Fx.eqs("ok", (ack["p"] as Dictionary)["st"] as String);
    // hello reflects the tombstone and nothing in the inventory.
    var hello = Proto.hello(kv, 3, Budget.free(kv));
    var hp = hello["p"] as Dictionary;
    Test.assertEqual(0, (hp["inv"] as Array).size());
    Test.assertEqual(1, (hp["del"] as Array).size());
    Test.assert(!hello.hasKey("oid"));
    Test.assertEqual(9, (hp["cap"] as Array).size());
    // result.ack marks delivered; sync.req answers sync.ack + drain.
    History.add(kv, "r1", History.KIND_RESULT, "T", "A", 1, "", "completed", {"id" => "r1"});
    Outbox.enqueue(kv, "result.put", "r1", 1, "m1", 1);
    var sync = Proto.handle(kv, {"v" => 1, "t" => "sync.req", "id" => "s", "ts" => 4, "g" => 1}, 4, 1);
    var sa = Fx.ackOf(sync);
    Fx.eqs("sync.ack", sa["t"] as String);
    Test.assertEqual(1, (sa["p"] as Dictionary)["rc"]);
    Test.assert(sync["drain"] as Boolean);
    var body = Proto.dataMessage(kv, Outbox.next(kv, 5) as Array) as Dictionary;
    Fx.eqs("m1", body["id"] as String);
    Fx.eqs("r1", body["oid"] as String);
    var rack = {"v" => 1, "t" => "result.ack", "id" => "a", "ts" => 6, "g" => 1, "oid" => "r1", "rev" => 1,
        "p" => {"mid" => "m1", "st" => "ok"}};
    var res = Proto.handle(kv, rack, 6, 1);
    // No acknowledgment of an acknowledgment.
    Test.assertEqual(0, (res["out"] as Array).size());
    Test.assert(History.isDelivered(kv, "r1"));
    Test.assertEqual(0, Outbox.pendingCount(kv));
    return true;
}

(:test)
function testProfilePutAck(logger as Test.Logger) as Boolean {
    var kv = new MemKv();
    Settings.setStore(kv);
    var msg = {"v" => 1, "t" => "profile.put", "id" => "p1", "ts" => 1, "g" => 1, "oid" => "profile", "rev" => 1,
        "p" => {"prep" => 5, "rec" => false}};
    var ack = Fx.ackOf(Proto.handle(kv, msg, 1, 1));
    Fx.eqs("profile.ack", ack["t"] as String);
    Fx.eqs("ok", (ack["p"] as Dictionary)["st"] as String);
    Test.assert(!Settings.record());
    Settings.setStore(new Kv());
    return true;
}

//! Builds the capped Fran result of result.put.capped.json from a run.
(:test)
function testCappedFranResultMatchesFixtureShape(logger as Test.Logger) as Boolean {
    var plan = Fx.plan();
    var part = Fx.franPart();
    var run = new MetconRun(part, new Program(part["seg"] as Array));
    run.lap(201000, 0);
    run.lap(395000, 0);
    run.markCap(480000);
    var o = new PartOutcome();
    o.resultId = "80000000-0000-4000-8000-000000000001";
    o.partId = U.str(part, "id") as String;
    o.status = run.status(true);
    o.t0 = 1793700000;
    o.elMs = 480000;
    o.actMs = 480000;
    o.wkMs = 480000;
    var sc = Score.measured(part, run, o.status, 480000);
    Score.setValue(sc, "n", 83, true);
    sc.remove("cs");
    o.score = sc;
    o.level = "Rx";
    o.events = run.events;
    o.loads = [["50000000-0000-4000-8000-000000000001", 43, "kg", 1]];
    o.hrAvg = 151;
    o.hrMax = 182;
    o.fit = "saved";
    var r = ResultBuilder.result(plan, "70000000-0000-4000-8000-000000000001", o);
    // Same keys as the fixture payload, nothing else.
    var keys = ["sv", "id", "rr", "sid", "pl", "pr", "wid", "wr", "pt", "st", "t0", "el", "act", "wk",
        "sc", "lv", "ev", "ld", "hr", "fit", "dev", "app"];
    Test.assertEqual(keys.size(), r.size());
    for (var i = 0; i < keys.size(); i++) {
        Test.assertMessage(r.hasKey(keys[i]), "missing " + keys[i]);
    }
    Fx.eqs("capped", r["st"] as String);
    Test.assertEqual(480, r["el"]);
    var wsc = r["sc"] as Dictionary;
    Fx.eqs("reps", wsc["ty"] as String);
    Test.assertEqual(83, wsc["n"]);
    var ev = r["ev"] as Array;
    Test.assertEqual(3, ev.size());
    Fx.eqs("cap", (ev[2] as Array)[1] as String);
    Test.assertEqual(480000, (ev[2] as Array)[2]);
    Test.assertEqual(1, (ev[1] as Array)[0]);
    // The compact payload is the fixture's size class (fits one message).
    Test.assert(WireSize.size(r) < 1100);
    return true;
}

(:test)
function testStrengthResultAndSessionSummaryShapes(logger as Test.Logger) as Boolean {
    var plan = Fx.plan();
    var part = Fx.strengthPart();
    var run = new StrengthRun(part, "kg");
    for (var i = 0; i < 4; i++) {
        run.logAsPrescribed((i + 1) * 180000);
        run.startNext();
    }
    run.logWith(900000, 2, 100.0, "done");
    var o = new PartOutcome();
    o.resultId = "80000000-0000-4000-8000-000000000002";
    o.partId = U.str(part, "id") as String;
    o.t0 = 1793699000;
    o.elMs = 900000;
    o.actMs = 900000;
    o.wkMs = 60000;
    o.score = Score.measuredStrength(part, run);
    o.level = "Rx";
    o.sets = run.logs;
    o.fit = "saved";
    var r = ResultBuilder.result(plan, "70000000-0000-4000-8000-000000000001", o);
    Test.assert(!r.hasKey("hr"));
    Test.assert(!r.hasKey("cal"));
    Test.assert(!r.hasKey("ev"));
    var sets = r["sets"] as Array;
    Test.assertEqual(5, sets.size());
    var last = sets[4] as Array;
    Test.assertEqual(9, last.size());
    Fx.eqs("partial", last[SR_STATUS] as String);
    Test.assertEqual(2, last[SR_REPS]);
    Test.assertEqual(900000, last[SR_MS]);
    var sc = r["sc"] as Dictionary;
    Fx.eqs("load", sc["ty"] as String);
    Test.assertEqual(100.0, U.real(sc, "v"));
    Test.assert(!sc.hasKey("man"));

    var entries = [[U.str(part, "id"), o.resultId, "completed"],
        [U.str(Fx.franPart(), "id"), "80000000-0000-4000-8000-000000000001", "capped"]];
    var s = ResultBuilder.session(plan, "70000000-0000-4000-8000-000000000001", entries, 1793699000,
        1793700600, 1600000, 1400000, [140, 182], null, null, "saved");
    var keys = ["sv", "id", "rev", "pl", "pr", "e", "t0", "t1", "el", "act", "hr", "fit"];
    Test.assertEqual(keys.size(), s.size());
    for (var i = 0; i < keys.size(); i++) {
        Test.assertMessage(s.hasKey(keys[i]), "missing " + keys[i]);
    }
    Test.assertEqual(1600, s["el"]);
    Test.assert(!s.hasKey("cal"));
    Test.assert(!s.hasKey("rso"));
    return true;
}

(:test)
function testQueuedObjectsKeepGenerationAfterPairingReset(logger as Test.Logger) as Boolean {
    var kv = new MemKv();
    Inbox.acceptGeneration(kv, 1);
    History.add(kv, "r1", History.KIND_RESULT, "T", "A", 1, "", "completed", {"id" => "r1"});
    History.add(kv, "s1", History.KIND_SESSION, "T", "", 1, "", "completed", {"id" => "s1"});
    Outbox.enqueue(kv, "result.put", "r1", 1, "m1", 1);
    Outbox.enqueue(kv, "session.put", "s1", 1, "m2", 1);
    // Simulate an installed version's nine-column queue row.
    var ob = Outbox.entries(kv);
    ob[1] = (ob[1] as Array).slice(0, 9);
    kv.put("ob", ob);
    Test.assert(Inbox.acceptGeneration(kv, 2));
    ob = Outbox.entries(kv);
    Test.assertEqual(1, (Proto.dataMessage(kv, ob[0] as Array) as Dictionary)["g"]);
    Test.assertEqual(1, (Proto.dataMessage(kv, ob[1] as Array) as Dictionary)["g"]);
    Outbox.enqueue(kv, "result.put", "r2", 1, "m3", 2);
    Test.assertEqual(2, (Outbox.entries(kv)[2] as Array)[OB_GENERATION]);
    return true;
}

(:test)
function testFailedQueueMigrationDoesNotAdoptGeneration(logger as Test.Logger) as Boolean {
    var kv = new MemKv();
    kv.put("gen", 1);
    kv.put("ob", [["m1", "result.put", "r1", 1, 1, OS_PENDING, 0, 1, 0]]);
    kv.failWrites = true;
    var msg = Fx.planPut();
    msg["g"] = 2;
    var ack = Fx.ackOf(Proto.handle(kv, msg, 2, 100000));
    Fx.eqs("capacity", (ack["p"] as Dictionary)["rs"] as String);
    Test.assertEqual(1, Inbox.generation(kv));
    Test.assertEqual(9, (Outbox.entries(kv)[0] as Array).size());
    return true;
}
