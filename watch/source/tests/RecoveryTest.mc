//
// RecoveryTest.mc
//
// Crash / exit recovery from checkpoints (REQ-WO06, REQ-WN07, REQ-TS09):
// finalized parts preserved, the unfinished part interrupted with known
// observations only, unreached parts skipped, idempotent, checkpoint
// cleared only after the result and summary are durable. Plus FIT sport
// selection (REQ-WR01) and HR stats (REQ-WR10).
//
import Toybox.Activity;
import Toybox.Lang;
import Toybox.Test;

(:test)
function testRecoveryInterruptsCurrentPartAndSkipsUnreached(logger as Test.Logger) as Boolean {
    var kv = new MemKv();
    kv.put("snap", Fx.plan());
    // Part A was saved before the crash; Fran (B) was running, 2 rounds in.
    History.add(kv, "r-a", History.KIND_RESULT, "Fran", "A", 1, "100 kg", "completed", {"id" => "r-a"});
    var ck = {
        "v" => 1, "sid" => "70000000-0000-4000-8000-000000000009", "sel" => [0, 1], "cur" => 1,
        "cl" => [CS_RUN, CS_RUN, 700000, 300000, 10000, 760000], "t0" => 1793700000, "pt0" => 1793700460,
        "pe0" => 460000, "pa0" => 400000, "ps" => true, "rid" => "80000000-0000-4000-8000-00000000000b",
        "ent" => [["30000000-0000-4000-8000-000000000002", "r-a", "completed"]],
        "ev" => [[0, "rd", 120000], [1, "rd", 250000]], "wk" => 300000, "rso" => "70000000-0000-4000-8000-000000000001"
    };
    Checkpoint.save(kv, ck);
    var info = Recovery.run(kv, "unknown") as Dictionary;
    Fx.eqs("80000000-0000-4000-8000-00000000000b", info["rid"] as String);
    Test.assert(!Checkpoint.exists(kv));
    Test.assert(kv.get("snap") == null);
    var r = History.object(kv, "80000000-0000-4000-8000-00000000000b") as Dictionary;
    Fx.eqs("interrupted", r["st"] as String);
    // Known observations only; the powered-off tail is not added.
    Test.assertEqual(300, r["el"]);
    Test.assertEqual(300, r["act"]);
    Test.assertEqual(2, (r["ev"] as Array).size());
    Test.assert(!r.hasKey("hr"));
    Fx.eqs("unknown", r["fit"] as String);
    var s = History.object(kv, "70000000-0000-4000-8000-000000000009") as Dictionary;
    var e = s["e"] as Array;
    Test.assertEqual(2, e.size());
    Fx.eqs("r-a", (e[0] as Array)[1] as String);
    Fx.eqs("interrupted", (e[1] as Array)[2] as String);
    Fx.eqs("70000000-0000-4000-8000-000000000001", s["rso"] as String);
    // Both queued for delivery; a second run is a no-op.
    Test.assertEqual(2, Outbox.pendingCount(kv));
    Test.assert(Recovery.run(kv, "unknown") == null);
    return true;
}

(:test)
function testRecoveryBetweenPartsDoesNotFabricate(logger as Test.Logger) as Boolean {
    var kv = new MemKv();
    kv.put("snap", Fx.plan());
    var ck = {
        "v" => 1, "sid" => "70000000-0000-4000-8000-00000000000a", "sel" => [0, 1], "cur" => 1,
        "cl" => [CS_WAIT, CS_RUN, 900000, 0, 10000, 905000], "t0" => 1793700000, "ps" => false,
        "rid" => "80000000-0000-4000-8000-00000000000c",
        "ent" => [["30000000-0000-4000-8000-000000000002", "r-a", "completed"]]
    };
    Checkpoint.save(kv, ck);
    var info = Recovery.run(kv, "saved") as Dictionary;
    Fx.eqs("", info["rid"] as String);
    Test.assert(History.object(kv, "80000000-0000-4000-8000-00000000000c") == null);
    var s = History.object(kv, "70000000-0000-4000-8000-00000000000a") as Dictionary;
    var e = s["e"] as Array;
    Fx.eqs("", (e[1] as Array)[1] as String);
    Fx.eqs("skipped", (e[1] as Array)[2] as String);
    Fx.eqs("saved", s["fit"] as String);
    Test.assertEqual(905, s["el"]);
    return true;
}

(:test)
function testRecoveryKeepsCheckpointWhenStorageRefuses(logger as Test.Logger) as Boolean {
    var kv = new MemKv();
    kv.put("snap", Fx.plan());
    Checkpoint.save(kv, {
        "v" => 1, "sid" => "s", "sel" => [1], "cur" => 0, "cl" => [CS_PAUSED, CS_RUN, 5000, 5000, 0, 5000],
        "t0" => 1, "ps" => true, "rid" => "r", "ent" => [], "ev" => []
    });
    kv.failWrites = true;
    Test.assert(Recovery.run(kv, "unknown") == null);
    kv.failWrites = false;
    Test.assert(Checkpoint.exists(kv));
    Test.assert(Recovery.run(kv, "unknown") != null);
    return true;
}

(:test)
function testFitSportSelection(logger as Test.Logger) as Boolean {
    var a = FitSport.choose(["strength_sets", "amrap", "emom"]);
    Test.assertEqual(Activity.SPORT_HIIT as Number, a[0]);
    Test.assertEqual(Activity.SUB_SPORT_AMRAP as Number, a[1]);
    var f = FitSport.choose(["for_time"]);
    Test.assertEqual(Activity.SUB_SPORT_HIIT as Number, f[1]);
    var s = FitSport.choose(["strength_sets", "build_to_max"]);
    Test.assertEqual(Activity.SPORT_TRAINING as Number, s[0]);
    Test.assertEqual(Activity.SUB_SPORT_STRENGTH_TRAINING as Number, s[1]);
    var g = FitSport.choose(["manual"]);
    Test.assertEqual(Activity.SUB_SPORT_GENERIC as Number, g[1]);
    return true;
}

(:test)
function testHrStatsNeverFabricates(logger as Test.Logger) as Boolean {
    var h = new HrStats();
    Test.assert(h.pair() == null);
    h.add(null);
    h.add(140);
    h.add(161);
    var p = h.pair() as Array;
    Test.assertEqual(151, p[0]);
    Test.assertEqual(161, p[1]);
    return true;
}
