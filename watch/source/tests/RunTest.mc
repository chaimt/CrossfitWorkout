//
// RunTest.mc
//
// Metcon progress (REQ-WM02/03/09, REQ-DM24/25), strength sets (REQ-WS01..05,
// REQ-WC02) and score derivation (REQ-WE03/05) incl. REQ-TS12 cases: exact
// cap tie, failed / partial set, undo, 1,000 reps.
//
import Toybox.Lang;
import Toybox.Test;


(:test)
function testForTimeFinishBeatsCapOnExactTie(logger as Test.Logger) as Boolean {
    var run = Fx.fran();
    run.lap(201000, 0);
    run.lap(395000, 0);
    run.finish(480000);
    run.markCap(480000);
    Fx.eqs("completed", run.status(true));
    Test.assertEqual(480000, run.finishMs);
    return true;
}

(:test)
function testCapFreezesProgressAndCountsReps(logger as Test.Logger) as Boolean {
    var run = Fx.fran();
    Test.assertEqual(LAP_OK, run.lap(201000, 0));
    Test.assertEqual(LAP_OK, run.lap(395000, 0));
    run.markCap(480000);
    // A late LAP / finish after the cap is not accepted.
    Test.assertEqual(LAP_IGNORED, run.lap(481000, 0));
    run.finish(482000);
    Test.assertEqual(2, run.rounds);
    Fx.eqs("capped", run.status(true));
    // Extra reps bound: current round (18 reps) minus one.
    Test.assertEqual(17, run.maxExtraReps());
    var sc = Score.measured(Fx.franPart(), run, "capped", 480000);
    Fx.eqs("reps", sc["ty"] as String);
    Test.assertEqual(72, sc["n"]);
    // Athlete enters 11 extra reps on the result screen -> 83 (fixture).
    Score.setValue(sc, "n", 72 + 11, true);
    Test.assertEqual(83, sc["n"]);
    return true;
}

(:test)
function testForTimeCompletedScoreIsFinishTime(logger as Test.Logger) as Boolean {
    var run = Fx.fran();
    run.lap(100000, 0);
    run.lap(180000, 0);
    run.lap(245900, 0);
    run.finish(245900);
    Fx.eqs("completed", run.status(false));
    var sc = Score.measured(Fx.franPart(), run, "completed", 245900);
    Test.assertEqual(245, sc["s"]);
    Fx.eqs("4:05", Score.text(sc));
    return true;
}

(:test)
function testUndoIsCompensatingEvent(logger as Test.Logger) as Boolean {
    var run = Fx.fran();
    run.lap(1000, 0);
    run.lap(2000, 0);
    Test.assert(run.undo(2500));
    Test.assertEqual(1, run.rounds);
    Test.assertEqual(3, run.events.size());
    Fx.eqs("undo", (run.events[2] as Array)[1] as String);
    // Replaying the checkpointed events gives the same counts.
    var again = Fx.fran();
    again.restore(run.events);
    Test.assertEqual(1, again.rounds);
    return true;
}

(:test)
function testMovementModeChipper(logger as Test.Logger) as Boolean {
    var part = Fx.metcon("for_time", [Fx.seg("open", 0, 0)]);
    part["trk"] = "mv";
    part["mv"] = ["50 cal row", "40 wall balls", "30 T2B"];
    var run = new MetconRun(part, new Program(part["seg"] as Array));
    Fx.eqs("50 cal row", run.currentMove());
    run.lap(60000, 0);
    Fx.eqs("40 wall balls", run.currentMove());
    Fx.eqs("30 T2B", run.nextMove());
    run.lap(120000, 0);
    run.lap(180000, 0);
    // A full pass of the movement list is one round.
    Test.assertEqual(1, run.rounds);
    Test.assertEqual(0, run.moveIndex);
    run.undo(190000);
    Test.assertEqual(0, run.rounds);
    Test.assertEqual(2, run.moveIndex);
    Fx.eqs("mv", (run.events[0] as Array)[1] as String);
    return true;
}

(:test)
function testConstantAmrapRepeatsLastRound(logger as Test.Logger) as Boolean {
    // H10-style AMRAP: the phone compiles one 30-rep template plus rrc:true.
    var part = Fx.metcon("amrap", [Fx.seg("work", 600, 0)]);
    part["rr"] = [30];
    part["rrc"] = true;
    var run = new MetconRun(part, new Program(part["seg"] as Array));
    run.lap(100000, 0);
    run.lap(200000, 0);
    run.lap(300000, 0);
    Test.assertEqual(30, run.currentRoundReps());
    Test.assertEqual(29, run.maxExtraReps());
    Test.assertEqual(90, run.completedRoundReps());
    // Without rrc the rule ends after the listed rounds (progressive AMRAP).
    part.remove("rrc");
    var open = new MetconRun(part, new Program(part["seg"] as Array));
    open.lap(100000, 0);
    open.lap(200000, 0);
    Test.assertEqual(-1, open.maxExtraReps());
    Test.assertEqual(-1, open.completedRoundReps());
    return true;
}

(:test)
function testAmrapLadderAndEarlyStop(logger as Test.Logger) as Boolean {
    var part = Fx.metcon("amrap", [Fx.seg("work", 720, 0)]);
    part["rr"] = [6, 12, 18, 24];
    var run = new MetconRun(part, new Program(part["seg"] as Array));
    run.lap(100000, 0);
    run.lap(250000, 0);
    // Third round prescribes 18 reps: extra reps 0..17 (REQ-WM03).
    Test.assertEqual(18, run.currentRoundReps());
    Test.assertEqual(17, run.maxExtraReps());
    Test.assertEqual(18, run.completedRoundReps());
    Fx.eqs("completed", run.status(true));
    var sc = Score.measured(part, run, "completed", 720000);
    Test.assertEqual(2, sc["r"]);
    Test.assertEqual(0, sc["x"]);
    // Finish before the countdown ends = stopped early.
    var early = new MetconRun(part, new Program(part["seg"] as Array));
    early.finish(300000);
    Fx.eqs("stopped_early", early.status(false));
    // Beyond the compiled ladder there is no counting rule.
    run.lap(400000, 0);
    run.lap(600000, 0);
    Test.assertEqual(-1, run.maxExtraReps());
    return true;
}

(:test)
function testEventLimitNeverWraps(logger as Test.Logger) as Boolean {
    var run = Fx.fran();
    run.maxEvents = 3;
    run.lap(1, 0);
    run.lap(2, 0);
    run.lap(3, 0);
    Test.assertEqual(LAP_LIMIT, run.lap(4, 0));
    Test.assertEqual(3, run.rounds);
    Test.assert(!run.canLog());
    // The plan-derived bound keeps a full Result inside one result.put.
    var limit = ResultBuilder.eventLimit(Fx.plan(), Fx.franPart());
    Test.assert(limit > 100 && limit <= 200);
    return true;
}

(:test)
function testTimerDrivenLapIsMarkerOnly(logger as Test.Logger) as Boolean {
    var part = Fx.metcon("emom", [Fx.seg("work", 60, 0), Fx.seg("work", 60, 1)]);
    var run = new MetconRun(part, new Program(part["seg"] as Array));
    Test.assertEqual(LAP_OK, run.lap(61000, 1));
    Test.assertEqual(0, run.rounds);
    Test.assertEqual(1, run.events.size());
    return true;
}


(:test)
function testOnePressSetStartsRestAtPress(logger as Test.Logger) as Boolean {
    var run = Fx.squats();
    Test.assert(!run.needsEntry());
    Test.assert(run.logAsPrescribed(30000));
    Test.assertEqual(1, run.current);
    Test.assert(run.resting());
    Test.assertEqual(120000, run.restLeftMs(30000));
    var row = run.logs[0] as Array;
    Fx.eqs("40000001-0000-4000-8000-000000000001", row[SR_ID] as String);
    Test.assertEqual(3, row[SR_REPS]);
    Test.assertEqual(100, row[SR_LOAD]);
    Fx.eqs("done", row[SR_STATUS] as String);
    // Rest cues at 10 s and at 0, then overtime.
    Test.assertEqual(RC_NONE, run.restCue(30000, 139000));
    Test.assertEqual(RC_TEN, run.restCue(139000, 140500));
    Test.assertEqual(RC_ZERO, run.restCue(149000, 150000));
    Test.assertEqual(-5000, run.restLeftMs(155000));
    Fx.eqs("+0:05", Fmt.overtime(-run.restLeftMs(155000)));
    run.startNext();
    Test.assert(!run.resting());
    return true;
}

(:test)
function testNoRestAfterFinalSetAndBestLoad(logger as Test.Logger) as Boolean {
    var run = Fx.squats();
    for (var i = 0; i < 4; i++) {
        run.logAsPrescribed(i * 150000);
        run.startNext();
    }
    // Final set: 2 of 3 reps -> partial, and no rest follows it.
    Test.assert(run.logWith(700000, 2, 100.0, "done"));
    Fx.eqs("partial", (run.lastLog() as Array)[SR_STATUS] as String);
    Test.assert(!run.resting());
    Test.assert(run.isFinished());
    Test.assertEqual(100.0, run.bestLoad());
    Test.assertEqual(5, run.doneCount());
    var sc = Score.measuredStrength(Fx.strengthPart(), run);
    Test.assertEqual(100.0, sc["v"]);
    Fx.eqs("kg", sc["u"] as String);
    return true;
}

(:test)
function testUndoEditExtraSkipEnd(logger as Test.Logger) as Boolean {
    var run = Fx.squats();
    run.logAsPrescribed(1000);
    Test.assert(run.undo());
    Test.assertEqual(0, run.current);
    Test.assertEqual(0, run.logs.size());
    Test.assert(!run.resting());
    run.logAsPrescribed(2000);
    // Edit does not restart rest.
    Test.assert(run.editLast(3, 102.5, "failed"));
    Test.assertEqual(2000, run.restStartMs);
    Fx.eqs("failed", (run.lastLog() as Array)[SR_STATUS] as String);
    Test.assertEqual(102.5, (run.lastLog() as Array)[SR_LOAD]);
    // Failed sets never count towards the best load.
    Test.assertEqual(-1.0, run.bestLoad());
    Test.assert(run.addExtraSet());
    Test.assertEqual(6, run.total());
    Test.assert(run.skipSet(5000));
    Fx.eqs("skipped", (run.lastLog() as Array)[SR_STATUS] as String);
    Test.assertEqual(0, (run.lastLog() as Array)[SR_REPS]);
    run.endExercise();
    Test.assert(run.isFinished());
    return true;
}

(:test)
function testUnresolvedTargetsNeedEntry(logger as Test.Logger) as Boolean {
    var part = Fx.strengthPart();
    var set = ((part["sets"] as Array)[0] as Dictionary);
    set.remove("r");
    set["rx"] = "5-8";
    var run = new StrengthRun(part, "kg");
    Test.assert(run.needsEntry());
    Test.assert(!run.logAsPrescribed(1000));
    Test.assert(run.logWith(1000, 7, 100.0, "done"));
    Test.assertEqual(-1, (run.lastLog() as Array)[SR_TARGET]);
    // Unresolved load (no `w.v`).
    var p2 = Fx.strengthPart();
    (((p2["sets"] as Array)[0] as Dictionary)["w"] as Dictionary).remove("v");
    Test.assert(new StrengthRun(p2, "kg").needsEntry());
    return true;
}

(:test)
function testBuildToMaxHeaviestCompletedSet(logger as Test.Logger) as Boolean {
    var part = Fx.strengthPart();
    part["mode"] = "build_to_max";
    part["att"] = 4;
    part["sets"] = [((part["sets"] as Array)[0] as Dictionary)];
    var run = new StrengthRun(part, "kg");
    Test.assert(run.needsEntry());
    Test.assertEqual(4, run.total());
    run.logWith(1000, 3, 100.0, "done");
    Test.assertEqual(100.0, run.prefillLoad());
    run.logWith(2000, 3, 110.0, "done");
    run.logWith(3000, 3, 120.0, "failed");
    run.logWith(4000, 2, 115.0, "done");
    Test.assert(run.isFinished());
    // 120 failed and 115 was 2 of 3 reps (partial): best is 110.
    Test.assertEqual(110.0, run.bestLoad());
    return true;
}

(:test)
function testScoreDraftsMissingIsNotZero(logger as Test.Logger) as Boolean {
    var part = Fx.metcon("max_effort", [Fx.seg("work", 120, 0)]);
    part["sc"] = {"ty" => "reps", "rv" => 1};
    var run = new MetconRun(part, new Program(part["seg"] as Array));
    var sc = Score.measured(part, run, "completed", 120000);
    Test.assert(!Score.complete(sc));
    Fx.eqs("--", Score.text(sc));
    var wire = Score.toWire(sc);
    Fx.eqs("none", wire["ty"] as String);
    Test.assert(!wire.hasKey("n"));
    Score.setValue(sc, "n", 1000, false);
    Test.assert(Score.complete(sc));
    Fx.eqs("1000 reps", Score.text(sc));
    Test.assert(!sc.hasKey("cs"));
    // REQ-WE04: a completed time above the cap is rejected.
    Test.assert(!Score.timeAllowed(481, 480));
    Test.assert(Score.timeAllowed(480, 480));
    return true;
}

(:test)
function testCappedUnscoredWarmupNeverRequestsManualScore(logger as Test.Logger) as Boolean {
    var part = Fx.franPart();
    part["sc"] = {"ty" => "none", "rv" => 1, "man" => true};
    var run = new MetconRun(part, new Program(part["seg"] as Array));
    var score = Score.measured(part, run, "capped", 480000);
    var wire = Score.toWire(score);
    Fx.eqs("none", wire["ty"] as String);
    Test.assert(!wire.hasKey("pd"));
    Test.assert(!wire.hasKey("txt"));
    return true;
}
