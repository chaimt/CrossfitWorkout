//
// Fixtures.mc
//
// Test data: shared/fixtures/wire/valid/plan.put.json embedded verbatim as
// a Monkey C Dictionary (the golden phone -> watch message), plus small
// builders for metcon / strength parts. Excluded from device builds by the
// (:test) annotation.
//
import Toybox.Lang;
import Toybox.Test;

(:test)
module Fx {

    //! String assertion with a readable failure message.
    function eqs(expected as String?, actual as String?) as Void {
        Test.assertMessage(U.eq(expected, actual), "expected '" + expected + "' got '" + actual + "'");
    }

    //! shared/fixtures/wire/valid/plan.put.json (compact size 1920 bytes,
    //! payload 1774 bytes).
    function planPut() as Dictionary {
        return {
            "v" => 1,
            "t" => "plan.put",
            "id" => "60000000-0000-4000-8000-000000000001",
            "ts" => 1793700000,
            "g" => 1,
            "oid" => "10000000-0000-4000-8000-000000000001",
            "rev" => 2,
            "p" => {
                "sv" => 1,
                "id" => "10000000-0000-4000-8000-000000000001",
                "rev" => 2,
                "wid" => "20000000-0000-4000-8000-000000000001",
                "wr" => 3,
                "ti" => "Fran",
                "date" => "2026-11-03",
                "tz" => "Asia/Jerusalem",
                "u" => "kg",
                "p" => [
                    {
                        "id" => "30000000-0000-4000-8000-000000000002",
                        "l" => "A",
                        "n" => "Back squat 5x3",
                        "mode" => "strength_sets",
                        "fb" => false,
                        "ln" => ["Back squat 5x3 @80%", "Rest 2:00"],
                        "sets" => [
                            _squat("40000001-0000-4000-8000-000000000001", 120),
                            _squat("40000002-0000-4000-8000-000000000001", 120),
                            _squat("40000003-0000-4000-8000-000000000001", 120),
                            _squat("40000004-0000-4000-8000-000000000001", 120),
                            _squat("40000005-0000-4000-8000-000000000001", 0)
                        ],
                        "sc" => {"ty" => "load", "rv" => 1, "man" => false},
                        "lv" => "Rx"
                    },
                    {
                        "id" => "30000000-0000-4000-8000-000000000001",
                        "l" => "W",
                        "n" => "Fran 21-15-9",
                        "mode" => "for_time",
                        "fb" => false,
                        "ln" => ["For time, cap 8:00", "21-15-9 reps of:", "Thruster 43 kg (Rx 95 lb)", "Pull-up"],
                        "seg" => [{"k" => "open", "cap" => 480, "lb" => "FOR TIME", "ix" => 0}],
                        "trk" => "round",
                        "mv" => ["Thruster 43 kg", "Pull-up"],
                        "rr" => [42, 30, 18],
                        "rl" => ["21 reps", "15 reps", "9 reps"],
                        "rnd" => 3,
                        "sc" => {"ty" => "time", "rv" => 1, "cap" => "reps", "man" => false},
                        "ld" => [{"id" => "50000000-0000-4000-8000-000000000001", "nm" => "Thruster", "v" => 43,
                            "u" => "kg", "c" => 1}],
                        "lv" => "Rx",
                        "lvs" => ["Rx", "Scaled"]
                    }
                ]
            }
        };
    }

    function _squat(id as String, rest as Number) as Dictionary {
        return {
            "id" => id, "nm" => "Back squat", "mv" => "back_squat", "r" => 3, "rx" => "3",
            "w" => {"v" => 100, "u" => "kg", "c" => 1}, "rest" => rest, "ty" => "working",
            "nt" => "80% -> 100 kg"
        };
    }

    function prog(segs as Array) as Program {
        return new Program(segs);
    }

    function fran() as MetconRun {
        var part = franPart();
        return new MetconRun(part, new Program(part["seg"] as Array));
    }

    function squats() as StrengthRun {
        return new StrengthRun(strengthPart(), "kg");
    }

    //! The single reply of a Proto.handle() result.
    function ackOf(res as Dictionary) as Dictionary {
        var out = res["out"] as Array;
        Test.assertEqual(1, out.size());
        return out[0] as Dictionary;
    }

    function plan() as Dictionary {
        return planPut()["p"] as Dictionary;
    }

    function parts() as Array {
        return plan()["p"] as Array;
    }

    function strengthPart() as Dictionary {
        return parts()[0] as Dictionary;
    }

    function franPart() as Dictionary {
        return parts()[1] as Dictionary;
    }

    function seg(k as String, d as Number, ix as Number) as Dictionary {
        var s = {"k" => k, "lb" => k.toUpper(), "ix" => ix};
        if (k.equals("open")) {
            if (d > 0) {
                s.put("cap", d);
            }
        } else {
            s.put("d", d);
        }
        return s;
    }

    //! A metcon part with the given mode and segments.
    function metcon(mode as String, segs as Array) as Dictionary {
        return {
            "id" => "30000000-0000-4000-8000-0000000000aa", "l" => "B", "n" => mode, "mode" => mode,
            "fb" => false, "ln" => [mode], "seg" => segs,
            "sc" => {"ty" => mode.equals("amrap") ? "rounds_reps" : "time", "rv" => 1}
        };
    }

    //! A one-part plan around `part`, with id / rev.
    function planWith(id as String, rev as Number, part as Dictionary) as Dictionary {
        return {
            "sv" => 1, "id" => id, "rev" => rev, "wid" => "20000000-0000-4000-8000-000000000009", "wr" => 1,
            "ti" => "Test " + rev.format("%d"), "date" => "2026-11-03", "tz" => "Asia/Jerusalem", "u" => "kg",
            "p" => [part]
        };
    }

    //! plan.put envelope around `plan`.
    function putOf(plan as Dictionary, g as Number) as Dictionary {
        return {
            "v" => 1, "t" => "plan.put", "id" => Uuid.v4(), "ts" => 1793700000, "g" => g,
            "oid" => plan["id"], "rev" => plan["rev"], "p" => plan
        };
    }
}
