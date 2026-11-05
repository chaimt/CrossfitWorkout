//
// EngineTest.mc
//
// Segment engine (REQ-WM01, REQ-NF02), pre-start edits (REQ-WN03) and the
// session clock including the REQUIREMENTS.md section 6.12 deterministic
// example (REQ-WR08, REQ-TS12).
//
import Toybox.Lang;
import Toybox.Test;


(:test)
function testPrepThenWork(logger as Test.Logger) as Boolean {
    var p = Fx.prog([Fx.seg("work", 600, 0)]);
    var st = Engine.stateAt(p, 10000, 9999);
    Test.assertEqual(PH_PREP, st.phase);
    Test.assertEqual(1, Fmt.countdownSeconds(st.remainingMs()));
    st = Engine.stateAt(p, 10000, 10000);
    Test.assertEqual(PH_WORK, st.phase);
    Test.assertEqual(600, Engine.clockSeconds(st));
    st = Engine.stateAt(p, 0, 610000);
    Test.assertEqual(PH_DONE, st.phase);
    Test.assertEqual(600000, st.partMs);
    Test.assertEqual(600000, st.scoreMs);
    return true;
}

(:test)
function testAmrapClockNeverShowsMinusOne(logger as Test.Logger) as Boolean {
    var p = Fx.prog([Fx.seg("work", 60, 0)]);
    // A late tick far past the end clamps to DONE, never a negative clock.
    var st = Engine.stateAt(p, 0, 59999);
    Test.assertEqual(1, Engine.clockSeconds(st));
    st = Engine.stateAt(p, 0, 75000);
    Test.assertEqual(PH_DONE, st.phase);
    Fx.eqs("1:00", Fmt.clock(Engine.clockSeconds(st)));
    return true;
}

(:test)
function testForTimeCapAndUncapped(logger as Test.Logger) as Boolean {
    var open = Fx.prog([Fx.seg("open", 0, 0)]);
    Test.assertEqual(0, open.totalMs);
    var st = Engine.stateAt(open, 0, 5400000);
    Test.assertEqual(PH_OPEN, st.phase);
    Test.assertEqual(5400, Engine.clockSeconds(st));
    Test.assertEqual(0, Engine.countdownBeep(st));

    var capped = Fx.prog([Fx.seg("open", 480, 0)]);
    Test.assertEqual(480000, capped.capMs);
    Test.assertEqual(PH_OPEN, Engine.stateAt(capped, 0, 479999).phase);
    Test.assertEqual(PH_DONE, Engine.stateAt(capped, 0, 480000).phase);
    return true;
}

(:test)
function testEmomStationsAndRounds(logger as Test.Logger) as Boolean {
    var a = Fx.seg("work", 60, 0);
    a["s"] = 0;
    var b = Fx.seg("work", 40, 1);
    b["s"] = 1;
    var br = Fx.seg("rest", 20, 1);
    br["ic"] = true;
    var p = Fx.prog([a, b, br]);
    Test.assertEqual(2, p.roundCount);
    var st = Engine.stateAt(p, 0, 61000);
    Test.assertEqual(PH_WORK, st.phase);
    Test.assertEqual(1, st.round);
    Test.assertEqual(1, st.station);
    st = Engine.stateAt(p, 0, 105000);
    Test.assertEqual(PH_REST, st.phase);
    Test.assertEqual(1, st.round);
    // In-clock rest counts towards score time.
    Test.assertEqual(105000, st.scoreMs);
    return true;
}

(:test)
function testTabataTransitionsAndRestExcludedFromScore(logger as Test.Logger) as Boolean {
    var segs = [];
    for (var i = 0; i < 8; i++) {
        segs.add(Fx.seg("work", 20, i));
        if (i < 7) {
            segs.add(Fx.seg("rest", 10, i));
        }
    }
    var p = Fx.prog(segs);
    Test.assertEqual(230000, p.totalMs);
    var prev = Engine.stateAt(p, 0, 19999);
    var next = Engine.stateAt(p, 0, 20000);
    Test.assertEqual(EV_REST, Engine.transition(prev.phase, prev.seg, next));
    Test.assertEqual(EV_NONE, Engine.transition(next.phase, next.seg, Engine.stateAt(p, 0, 25000)));
    var w = Engine.stateAt(p, 0, 30000);
    Test.assertEqual(EV_WORK, Engine.transition(next.phase, next.seg, w));
    Test.assertEqual(1, w.round);
    Test.assertEqual(3, Engine.countdownBeep(Engine.stateAt(p, 0, 47500)));
    // Off-clock rest: score time is work only.
    var done = Engine.stateAt(p, 0, 230000);
    Test.assertEqual(PH_DONE, done.phase);
    Test.assertEqual(160000, done.scoreMs);
    Test.assertEqual(EV_DONE, Engine.transition(PH_WORK, 14, done));
    return true;
}

(:test)
function testIntervalsSkippedTicksDoNotDrift(logger as Test.Logger) as Boolean {
    // 3 x [4:00 work, 2:00 rest], final rest omitted.
    var p = Fx.prog([Fx.seg("work", 240, 0), Fx.seg("rest", 120, 0), Fx.seg("work", 240, 1),
        Fx.seg("rest", 120, 1), Fx.seg("work", 240, 2)]);
    // Jumping straight to any time gives the same answer as ticking there.
    var st = Engine.stateAt(p, 0, 360500);
    Test.assertEqual(PH_WORK, st.phase);
    Test.assertEqual(1, st.round);
    Test.assertEqual(240000 - 500, st.remainingMs());
    var reused = new SegState();
    Engine.stateInto(reused, p, 0, 360500);
    Test.assertEqual(PH_REST, Engine.stateAt(p, 0, 600500).phase);
    Test.assertEqual(st.seg, reused.seg);
    Test.assertEqual(st.phaseElapsedMs, reused.phaseElapsedMs);
    return true;
}

(:test)
function testEditsRewriteOnlySupportedShapes(logger as Test.Logger) as Boolean {
    var amrap = Fx.metcon("amrap", [Fx.seg("work", 720, 0)]);
    var f = Edits.fields(amrap);
    Test.assertEqual(1, f.size());
    Fx.eqs("d", f[0].key);
    Test.assertEqual(720, f[0].value);
    var segs = Edits.apply(amrap, {"d" => 900});
    Test.assertEqual(900, (segs[0] as Dictionary)["d"]);
    // The plan's own segment is not modified (frozen snapshot).
    Test.assertEqual(720, ((amrap["seg"] as Array)[0] as Dictionary)["d"]);

    var ft = Fx.metcon("for_time", [Fx.seg("open", 480, 0)]);
    segs = Edits.apply(ft, {"cap" => 0});
    Test.assert(!(segs[0] as Dictionary).hasKey("cap"));

    var tabata = [];
    for (var i = 0; i < 8; i++) {
        tabata.add(Fx.seg("work", 20, i));
        if (i < 7) {
            tabata.add(Fx.seg("rest", 10, i));
        }
    }
    var tp = Fx.metcon("tabata", tabata);
    Test.assertEqual(3, Edits.fields(tp).size());
    segs = Edits.apply(tp, {"rnd" => 4, "wk" => 30});
    Test.assertEqual(7, segs.size());
    Test.assertEqual(30, (segs[6] as Dictionary)["d"]);

    var emom = Fx.metcon("emom", [Fx.seg("work", 60, 0), Fx.seg("work", 60, 1)]);
    segs = Edits.apply(emom, {"rnd" => 5, "iv" => 90});
    Test.assertEqual(5, segs.size());
    Test.assertEqual(90, (segs[4] as Dictionary)["d"]);
    Test.assertEqual(4, (segs[4] as Dictionary)["ix"]);
    return true;
}

//! REQUIREMENTS.md section 6.12: work 60 s -> in-clock rest 20 s -> work
//! 40 s -> part-result entry 15 s -> inter-part prescribed rest 30 s ->
//! wait 10 s -> work 50 s -> pause 10 s -> work 10 s. At final work end:
//! wall 245 s, active 210 s, part 1 score 120 s, part 2 score 60 s.
(:test)
function testSessionClockDeterministicExample(logger as Test.Logger) as Boolean {
    var rest = Fx.seg("rest", 20, 0);
    rest["ic"] = true;
    var part1 = Fx.prog([Fx.seg("work", 60, 0), rest, Fx.seg("work", 40, 1)]);
    var part2 = Fx.prog([Fx.seg("work", 60, 0)]);
    var c = new SessionClock();
    c.enter(CS_PREP, -10000);
    c.startPart();
    c.enter(CS_RUN, 0);
    // The engine ends part 1 by itself after 120 s of running time.
    var st1 = Engine.stateAt(part1, 0, c.partRunAt(120000));
    Test.assertEqual(PH_DONE, st1.phase);
    c.enter(CS_ENTRY, 120000);
    Test.assertEqual(120000, Engine.stateAt(part1, 0, c.partRunMs).scoreMs);
    c.enter(CS_PART_REST, 135000);
    c.enter(CS_WAIT, 165000);
    c.startPart();
    c.enter(CS_RUN, 175000);
    c.enter(CS_PAUSED, 225000);
    // Pause freezes the part clock.
    Test.assertEqual(50000, c.partRunAt(230000));
    c.resume(235000);
    Test.assertEqual(CS_RUN, c.state);
    var end = 245000;
    Test.assertEqual(245000, c.wallAt(end));
    Test.assertEqual(210000, c.activeAt(end));
    var st2 = Engine.stateAt(part2, 0, c.partRunAt(end));
    Test.assertEqual(PH_DONE, st2.phase);
    Test.assertEqual(60000, st2.scoreMs);
    // Final score entry does not add activity or score time.
    c.enter(CS_ENTRY, end);
    Test.assertEqual(210000, c.activeAt(end + 30000));
    Test.assertEqual(60000, c.partRunAt(end + 30000));
    // Prep is not active or wall time.
    Test.assertEqual(10000, c.prepElapsedMs);
    return true;
}
