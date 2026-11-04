//
// CodecTest.mc
//
// Tests for Util, Fmt, WireSize and PlanCodec: the golden plan.put fixture
// validates, and each shared/fixtures/wire/invalid/plan.*.json rule break
// (re-created here by the same single mutation) is rejected (REQ-TS03).
//
import Toybox.Lang;
import Toybox.Test;

(:test)
function testClockNeverNegative(logger as Test.Logger) as Boolean {
    Fx.eqs("0:00", Fmt.clock(-1));
    Fx.eqs("1:05", Fmt.clock(65));
    Fx.eqs("1:00:05", Fmt.clock(3605));
    Test.assertEqual(1, Fmt.countdownSeconds(1));
    Test.assertEqual(0, Fmt.countdownSeconds(0));
    Test.assertEqual(0, Fmt.countdownSeconds(-500));
    Test.assertEqual(60, Fmt.countdownSeconds(59001));
    Test.assertEqual(59, Fmt.countUpSeconds(59999));
    return true;
}

(:test)
function testScaledAndLoads(logger as Test.Logger) as Boolean {
    Fx.eqs("102.5", Fmt.scaled(1025, 1));
    Fx.eqs("100", Fmt.scaled(1000, 1));
    Fx.eqs("0.125", Fmt.scaled(125, 3));
    Fx.eqs("2", Fmt.scaled(2000, 3));
    Fx.eqs("102.5", Fmt.load(102.5));
    Fx.eqs("43", Fmt.load(43.0));
    Fx.eqs("2 x 22.5 kg", Fmt.loadWithUnit(22.5, "kg", 2));
    Fx.eqs("5 + 7", Fmt.roundsReps(5, 7));
    return true;
}

(:test)
function testUtilReadersTreatWrongTypesAsAbsent(logger as Test.Logger) as Boolean {
    var d = {"n" => 5, "l" => 7l, "s" => "x", "f" => 2.5, "b" => true, "big" => 9999999999l};
    Test.assertEqual(5, U.num(d, "n"));
    Test.assertEqual(7, U.num(d, "l"));
    Test.assert(U.num(d, "s") == null);
    Test.assert(U.num(d, "big") == null);
    Test.assert(U.num(d, "missing") == null);
    Test.assert(U.str(d, "n") == null);
    Test.assertEqual(2.5, U.real(d, "f"));
    Test.assertEqual(5.0, U.real(d, "n"));
    Test.assert(U.flag(d, "b", false));
    Test.assert(!U.flag(d, "missing", false));
    return true;
}

(:test)
function testWireSizeMatchesCompactJson(logger as Test.Logger) as Boolean {
    // Expected values from Python json.dumps(separators=(",", ":")).
    Test.assertEqual(1920, WireSize.size(Fx.planPut()));
    Test.assertEqual(1774, WireSize.size(Fx.plan()));
    Test.assertEqual(36, WireSize.size({"a" => "hé\"x", "b" => [1, true, null, 2.5]}));
    Test.assertEqual(4, WireSize.utf8Bytes("abcd"));
    Test.assertEqual(2, WireSize.utf8Bytes("é"));
    return true;
}

(:test)
function testHashIgnoresKeyOrderButNotContent(logger as Test.Logger) as Boolean {
    var a = {"x" => 1, "y" => [1, 2], "z" => "q"};
    var b = {"z" => "q", "y" => [1, 2], "x" => 1};
    Test.assertEqual(WireSize.hash(a), WireSize.hash(b));
    Test.assert(WireSize.hash(a) != WireSize.hash({"x" => 1, "y" => [2, 1], "z" => "q"}));
    Test.assert(WireSize.hash(Fx.plan()) != WireSize.hash(Fx.planWith("10000000-0000-4000-8000-000000000001", 2, Fx.franPart())));
    return true;
}

(:test)
function testGoldenPlanIsValid(logger as Test.Logger) as Boolean {
    Test.assert(PlanCodec.validate(Fx.plan()) == null);
    return true;
}

(:test)
function testInvalidFixtureMutationsAreRejected(logger as Test.Logger) as Boolean {
    // plan.unknown-schema-version.json: sv 1 -> 2.
    var p = Fx.plan();
    p["sv"] = 2;
    Fx.eqs("version", PlanCodec.validate(p));

    // plan.fallback-not-manual.json: fb true on a for_time part.
    p = Fx.plan();
    ((p["p"] as Array)[1] as Dictionary).put("fb", true);
    Fx.eqs("invalid", PlanCodec.validate(p));

    // plan.line-too-long.json: a 29-character display line.
    p = Fx.plan();
    (((p["p"] as Array)[1] as Dictionary)["ln"] as Array).add("12345678901234567890123456789");
    Fx.eqs("invalid", PlanCodec.validate(p));

    // plan.null-load.json: "w": null.
    p = Fx.plan();
    ((((p["p"] as Array)[0] as Dictionary)["sets"] as Array)[0] as Dictionary).put("w", null);
    Fx.eqs("invalid", PlanCodec.validate(p));

    // plan.strength-without-sets.json.
    p = Fx.plan();
    ((p["p"] as Array)[0] as Dictionary).remove("sets");
    Fx.eqs("invalid", PlanCodec.validate(p));

    // plan.work-without-duration.json: open -> work, cap removed.
    p = Fx.plan();
    var seg = (((p["p"] as Array)[1] as Dictionary)["seg"] as Array)[0] as Dictionary;
    seg["k"] = "work";
    seg["lb"] = "WORK";
    seg.remove("cap");
    Fx.eqs("invalid", PlanCodec.validate(p));

    // plan.seven-parts.json: 7 parts.
    p = Fx.plan();
    var parts = p["p"] as Array;
    for (var i = 0; i < 5; i++) {
        parts.add(Fx.franPart());
    }
    Test.assertEqual(7, parts.size());
    Test.assert(PlanCodec.validate(p) != null);
    return true;
}

(:test)
function testOversizedPlanIsRejectedWithSize(logger as Test.Logger) as Boolean {
    var p = Fx.plan();
    var parts = p["p"] as Array;
    // 6 parts with 12 full lines each pushes the plan past 4096 bytes.
    for (var i = 0; i < 4; i++) {
        var part = Fx.franPart();
        var ln = [];
        for (var j = 0; j < 12; j++) {
            ln.add("1234567890123456789012345678");
        }
        part["ln"] = ln;
        parts.add(part);
    }
    Test.assert(WireSize.size(p) > 4096);
    Fx.eqs("size", PlanCodec.validate(p));
    return true;
}

(:test)
function testSegmentRules(logger as Test.Logger) as Boolean {
    // An open segment that is not last can never end: invalid.
    var part = Fx.metcon("manual", [Fx.seg("open", 0, 0), Fx.seg("work", 60, 0)]);
    Test.assert(PlanCodec.validate(Fx.planWith("10000000-0000-4000-8000-000000000003", 1, part)) != null);
    // work with a cap is invalid.
    var w = Fx.seg("work", 60, 0);
    w["cap"] = 30;
    part = Fx.metcon("amrap", [w]);
    Test.assert(PlanCodec.validate(Fx.planWith("10000000-0000-4000-8000-000000000003", 1, part)) != null);
    // A plain AMRAP is valid.
    part = Fx.metcon("amrap", [Fx.seg("work", 720, 0)]);
    Test.assert(PlanCodec.validate(Fx.planWith("10000000-0000-4000-8000-000000000003", 1, part)) == null);
    // Unknown mode.
    part = Fx.metcon("death_by", [Fx.seg("work", 60, 0)]);
    Test.assert(PlanCodec.validate(Fx.planWith("10000000-0000-4000-8000-000000000003", 1, part)) != null);
    return true;
}
