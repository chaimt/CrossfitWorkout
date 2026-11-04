//
// StoreTest.mc
//
// Durable state: checkpoint generations (REQ-WO06), settings override mask
// (REQ-WG01), inbox revision / tombstone / conflict / generation rules
// (REQ-SY11/SY14), outbox ack-only delivery and retry budget (REQ-WO02,
// REQ-SY13), history retention (REQ-WO03) and admission (REQ-WO05).
//
import Toybox.Lang;
import Toybox.Test;

(:test)
function testCheckpointGenerationsSurviveTornWrite(logger as Test.Logger) as Boolean {
    var kv = new MemKv();
    Test.assert(Checkpoint.load(kv) == null);
    Test.assertEqual(1, Checkpoint.save(kv, {"seq" => 1}));
    Test.assertEqual(2, Checkpoint.save(kv, {"seq" => 2}));
    Test.assertEqual(2, (Checkpoint.load(kv) as Dictionary)["seq"]);
    // Corrupt the newest record: recovery falls back to generation 1.
    var ptr = kv.get("ckp") as Array;
    var slot = ptr[1] as Number;
    var rec = kv.get(Checkpoint.SLOTS[slot]) as Dictionary;
    rec["d"] = {"seq" => 99};
    Test.assertEqual(1, (Checkpoint.load(kv) as Dictionary)["seq"]);
    // A lost pointer is reconstructed by scanning the slots.
    kv.del("ckp");
    Test.assertEqual(3, Checkpoint.save(kv, {"seq" => 3}));
    Test.assertEqual(3, (Checkpoint.load(kv) as Dictionary)["seq"]);
    // A refused write keeps the previous generation.
    kv.failWrites = true;
    Test.assertEqual(-1, Checkpoint.save(kv, {"seq" => 4}));
    Test.assertEqual(3, (Checkpoint.load(kv) as Dictionary)["seq"]);
    kv.failWrites = false;
    Checkpoint.clear(kv);
    Test.assert(!Checkpoint.exists(kv));
    return true;
}

(:test)
function testSettingsOverrideMaskWinsUntilReset(logger as Test.Logger) as Boolean {
    var kv = new MemKv();
    Settings.setStore(kv);
    Test.assertEqual(10, Settings.prepSeconds());
    var r = Settings.applyProfile(1, {"prep" => 5, "snd" => false, "ws" => 2.5, "u" => "kg"});
    Fx.eqs("ok", r[0] as String);
    Test.assertEqual(5, Settings.prepSeconds());
    Test.assert(!Settings.sound());
    Test.assert(Settings.setLocal("prep", 15));
    var r2 = Settings.applyProfile(2, {"prep" => 3});
    Fx.eqs("ok", r2[0] as String);
    // Local override wins over the newer phone default.
    Test.assertEqual(15, Settings.prepSeconds());
    Test.assert(Settings.isOverridden("prep"));
    Settings.resetToPhone();
    Test.assertEqual(3, Settings.prepSeconds());
    // Revision rules: identical retry, stale, conflict, invalid.
    Fx.eqs("already_applied", Settings.applyProfile(2, {"prep" => 3})[0] as String);
    Fx.eqs("stale", Settings.applyProfile(1, {"prep" => 3})[0] as String);
    Fx.eqs("conflict", Settings.applyProfile(2, {"prep" => 4})[0] as String);
    Fx.eqs("error", Settings.applyProfile(3, {"prep" => 999})[0] as String);
    Test.assertEqual(3, Settings.prepSeconds());
    Test.assertEqual(5.0, Settings.weightStep("lb", false));
    Test.assertEqual(1.0, Settings.weightStep("lb", true));
    Test.assertEqual(10, (Settings.snapshot() as Dictionary).size());
    Settings.setStore(new Kv());
    return true;
}

(:test)
function testInboxRevisionRules(logger as Test.Logger) as Boolean {
    var kv = new MemKv();
    var id = "10000000-0000-4000-8000-000000000001";
    var plan = Fx.plan();
    Fx.eqs("ok", Inbox.put(kv, id, 2, plan, 100, 100000)[0] as String);
    Test.assertEqual(1, Inbox.index(kv).size());
    // Identical retry -> already_applied, no mutation.
    var writes = kv.writes;
    Fx.eqs("already_applied", Inbox.put(kv, id, 2, Fx.plan(), 101, 100000)[0] as String);
    Test.assertEqual(writes, kv.writes);
    // Same revision, different content -> conflict.
    var changed = Fx.plan();
    changed["ti"] = "Fran v2";
    Fx.eqs("conflict", Inbox.put(kv, id, 2, changed, 102, 100000)[0] as String);
    // Lower revision -> stale.
    var older = Fx.plan();
    older["rev"] = 1;
    Fx.eqs("stale", Inbox.put(kv, id, 1, older, 103, 100000)[0] as String);
    // oid / rev must match the payload.
    Fx.eqs("invalid", Inbox.put(kv, id, 3, Fx.plan(), 104, 100000)[1] as String);
    // Newer revision replaces.
    var newer = Fx.plan();
    newer["rev"] = 3;
    newer["ti"] = "Fran v3";
    Fx.eqs("ok", Inbox.put(kv, id, 3, newer, 105, 100000)[0] as String);
    Fx.eqs("Fran v3", U.str(Inbox.plan(kv, id), "ti"));
    Test.assertEqual(1, Inbox.index(kv).size());
    return true;
}

(:test)
function testTombstoneBlocksDelayedPut(logger as Test.Logger) as Boolean {
    var kv = new MemKv();
    var id = "10000000-0000-4000-8000-000000000001";
    Inbox.put(kv, id, 2, Fx.plan(), 100, 100000);
    Fx.eqs("ok", Inbox.del(kv, id, 3)[0] as String);
    Test.assertEqual(0, Inbox.index(kv).size());
    Test.assert(Inbox.plan(kv, id) == null);
    Fx.eqs("already_applied", Inbox.del(kv, id, 3)[0] as String);
    // The delayed rev-2 put cannot resurrect the plan.
    Fx.eqs("stale", Inbox.put(kv, id, 2, Fx.plan(), 101, 100000)[0] as String);
    var p4 = Fx.plan();
    p4["rev"] = 4;
    Fx.eqs("ok", Inbox.put(kv, id, 4, p4, 102, 100000)[0] as String);
    // Local delete tombstones at the stored revision.
    Inbox.deleteLocal(kv, id);
    Fx.eqs("stale", Inbox.put(kv, id, 4, p4, 103, 100000)[0] as String);
    Test.assertEqual(4, (Inbox.tombstones(kv)[0] as Array)[1]);
    return true;
}

(:test)
function testInboxRejectsAtomicallyAndBoundsCapacity(logger as Test.Logger) as Boolean {
    var kv = new MemKv();
    var bad = Fx.plan();
    ((bad["p"] as Array)[1] as Dictionary).put("fb", true);
    Fx.eqs("invalid", Inbox.put(kv, "10000000-0000-4000-8000-000000000001", 2, bad, 1, 100000)[1] as String);
    // Nothing was written: no partial plan.
    Test.assertEqual(0, kv.writes);
    for (var i = 0; i < 10; i++) {
        var id = "10000000-0000-4000-8000-0000000000" + (10 + i).format("%d");
        Fx.eqs("ok", Inbox.put(kv, id, 1, Fx.planWith(id, 1, Fx.franPart()), i, 100000)[0] as String);
    }
    var id11 = "10000000-0000-4000-8000-000000000099";
    var r = Inbox.put(kv, id11, 1, Fx.planWith(id11, 1, Fx.franPart()), 11, 100000);
    Fx.eqs("capacity", r[1] as String);
    // Byte budget.
    var kv2 = new MemKv();
    Fx.eqs("capacity", Inbox.put(kv2, id11, 1, Fx.planWith(id11, 1, Fx.franPart()), 1, 100)[1] as String);
    // Generation: older pairing generation is refused.
    Test.assert(Inbox.acceptGeneration(kv2, 2));
    Test.assert(!Inbox.acceptGeneration(kv2, 1));
    Test.assert(Inbox.acceptGeneration(kv2, 3));
    Test.assertEqual(3, Inbox.generation(kv2));
    return true;
}

(:test)
function testOutboxAckOnlyDeliveryAndRetryBudget(logger as Test.Logger) as Boolean {
    var kv = new MemKv();
    Outbox.enqueue(kv, "result.put", "r1", 1, "m1", 1000);
    Outbox.enqueue(kv, "result.put", "r1", 1, "m-dup", 1000);
    Test.assertEqual(1, Outbox.pendingCount(kv));
    var e = Outbox.next(kv, 1000) as Array;
    Fx.eqs("m1", e[OB_MID] as String);
    Outbox.markSent(kv, "m1", 1000);
    // One outstanding data message: nothing else while waiting for the ack.
    Outbox.enqueue(kv, "session.put", "s1", 1, "m2", 1001);
    Test.assert(Outbox.next(kv, 1010) == null);
    // Ack timeout (30 s) -> retry after 5 s with the same message id.
    Test.assert(Outbox.next(kv, 1031) == null);
    var retry = Outbox.next(kv, 1036) as Array;
    Fx.eqs("m1", retry[OB_MID] as String);
    // Transmit failures: retries after 5, 15, 60 s, then wait for a drain.
    Outbox.markSent(kv, "m1", 1036);
    Outbox.transmitFailed(kv, "m1", 1036);
    Test.assert(Outbox.next(kv, 1050) == null);
    Fx.eqs("m1", (Outbox.next(kv, 1051) as Array)[OB_MID] as String);
    Outbox.transmitFailed(kv, "m1", 1060);
    Test.assertEqual(OS_PENDING, (Outbox.entries(kv)[0] as Array)[OB_STATE]);
    Outbox.transmitFailed(kv, "m1", 1130);
    var parked = Outbox.entries(kv)[0] as Array;
    Test.assertEqual(OS_WAITING, parked[OB_STATE]);
    // Automatic drain rate limit (5 min); explicit drain always runs.
    Test.assert(Outbox.startDrain(kv, 1100, false));
    Test.assert(!Outbox.startDrain(kv, 1200, false));
    Test.assert(Outbox.startDrain(kv, 1200, true));
    Test.assertEqual(OS_PENDING, (Outbox.entries(kv)[0] as Array)[OB_STATE]);
    // Only the phone's ack removes the entry.
    var done = Outbox.onAck(kv, "m1", "ok") as Array;
    Fx.eqs("r1", done[1] as String);
    Test.assertEqual(1, Outbox.pendingCount(kv));
    // Error acks block automatic retry.
    Test.assert(Outbox.onAck(kv, "m2", "error") == null);
    Test.assertEqual(OS_BLOCKED, (Outbox.entries(kv)[0] as Array)[OB_STATE]);
    Test.assert(Outbox.next(kv, 5000) == null);
    Outbox.unblockAll(kv, 5000);
    Test.assert(Outbox.next(kv, 5000) != null);
    return true;
}

(:test)
function testHistoryNeverPrunesUnsent(logger as Test.Logger) as Boolean {
    var kv = new MemKv();
    for (var i = 0; i < 52; i++) {
        History.add(kv, "r" + i.format("%d"), History.KIND_RESULT, "T", "A", i, "1:00", "completed", {"id" => i});
    }
    // Nothing delivered yet: all 52 kept (never prune unacknowledged).
    Test.assertEqual(52, History.rows(kv).size());
    Test.assertEqual(52, History.unsent(kv));
    History.markDelivered(kv, "r0");
    History.markDelivered(kv, "r1");
    History.markDelivered(kv, "r2");
    History.markDelivered(kv, "r3");
    History.add(kv, "r99", History.KIND_RESULT, "T", "A", 99, "1:00", "completed", {"id" => 99});
    Test.assertEqual(50, History.rows(kv).size());
    Test.assert(History.find(kv, "r0") < 0);
    Test.assert(History.find(kv, "r2") < 0);
    Test.assert(History.find(kv, "r3") >= 0);
    // Idempotent add (recovery may run twice).
    History.add(kv, "r99", History.KIND_RESULT, "T", "A", 99, "1:00", "completed", {"id" => 99});
    Test.assertEqual(50, History.rows(kv).size());
    return true;
}

(:test)
function testAdmissionEvictsOnlyAcknowledged(logger as Test.Logger) as Boolean {
    var kv = new MemKv();
    Test.assert(Budget.admit(kv, 2000, 6));
    // Fill storage with unsent history: Start must be blocked.
    var big = [];
    for (var i = 0; i < 300; i++) {
        big.add("1234567890123456789012345678901234567890");
    }
    for (var i = 0; i < 9; i++) {
        History.add(kv, "u" + i.format("%d"), History.KIND_RESULT, "T", "A", i, "", "completed", {"b" => big});
    }
    Test.assert(!Budget.admit(kv, 2000, 6));
    // Once acknowledged, old history may be evicted to admit the run.
    for (var i = 0; i < 9; i++) {
        History.markDelivered(kv, "u" + i.format("%d"));
    }
    Test.assert(Budget.admit(kv, 2000, 6));
    Test.assert(History.rows(kv).size() < 9);
    return true;
}
