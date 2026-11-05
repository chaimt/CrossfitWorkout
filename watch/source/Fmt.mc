//
// Fmt.mc
//
// Pure formatting helpers shared by every screen ("12:34", "1:02:03",
// "102.5 kg", "5 + 7"). Seeded from WOD Timer's WodFormat (D5). No device
// state is read here, so it is unit-tested in FmtTest.mc.
//
import Toybox.Lang;
import Toybox.Math;
import Toybox.Time;
import Toybox.Time.Gregorian;

module Fmt {

    //! Whole seconds as "m:ss", or "h:mm:ss" from one hour up. Negative
    //! input clamps to "0:00" so a late tick never shows "-0:01" (REQ-NF02).
    function clock(seconds as Number) as String {
        if (seconds < 0) {
            seconds = 0;
        }
        var h = seconds / 3600;
        var m = (seconds % 3600) / 60;
        var s = seconds % 60;
        if (h > 0) {
            return h.format("%d") + ":" + m.format("%02d") + ":" + s.format("%02d");
        }
        return m.format("%d") + ":" + s.format("%02d");
    }

    //! Seconds shown by a count-down: rounds up so "0:00" appears only when
    //! the phase has really ended, and the last second reads "0:01".
    function countdownSeconds(ms as Number) as Number {
        if (ms <= 0) {
            return 0;
        }
        return (ms + 999) / 1000;
    }

    //! Seconds shown by a count-up: whole seconds already elapsed.
    function countUpSeconds(ms as Number) as Number {
        if (ms <= 0) {
            return 0;
        }
        return ms / 1000;
    }

    //! Overtime after a rest reached zero: "+0:12".
    function overtime(ms as Number) as String {
        return "+" + clock(countUpSeconds(ms));
    }

    //! A scaled integer (value / 10^decimals) without trailing zeros:
    //! (1025, 1) -> "102.5", (1000, 1) -> "100", (125, 3) -> "0.125".
    function scaled(value as Number, decimals as Number) as String {
        if (decimals <= 0) {
            return value.format("%d");
        }
        var div = 1;
        for (var i = 0; i < decimals; i++) {
            div *= 10;
        }
        var neg = value < 0;
        var v = neg ? -value : value;
        var whole = v / div;
        var frac = v % div;
        var text = whole.format("%d");
        if (frac != 0) {
            var f = frac.format("%0" + decimals.format("%d") + "d");
            while (f.length() > 0 && f.substring(f.length() - 1, f.length()).equals("0")) {
                f = f.substring(0, f.length() - 1) as String;
            }
            text = text + "." + f;
        }
        return neg ? "-" + text : text;
    }

    //! A load value as sent by the phone (Number or Float): "100", "102.5".
    function load(v as Float) as String {
        return scaled(Math.round(v * 100).toNumber(), 2);
    }

    //! "100 kg", "2 x 22.5 kg" for an implement count above one.
    function loadWithUnit(v as Float, unit as String, count as Number) as String {
        var text = load(v) + " " + unit;
        if (count > 1) {
            return count.format("%d") + " x " + text;
        }
        return text;
    }

    //! "5 + 7" for rounds plus extra reps.
    function roundsReps(rounds as Number, reps as Number) as String {
        return rounds.format("%d") + " + " + reps.format("%d");
    }

    //! "1 round" / "7 rounds".
    function rounds(count as Number) as String {
        return count.format("%d") + (count == 1 ? " round" : " rounds");
    }

    //! Local start time of a logged workout, e.g. "Nov 3 18:05".
    function dateTime(epochSeconds as Number) as String {
        var info = Gregorian.info(new Time.Moment(epochSeconds), Time.FORMAT_MEDIUM);
        return info.month.toString() + " " + info.day.format("%d") + " "
            + info.hour.format("%02d") + ":" + info.min.format("%02d");
    }

    //! Today's local date as "YYYY-MM-DD" (REQ-WN01 compares plan dates).
    function today() as String {
        var info = Gregorian.info(Time.now(), Time.FORMAT_SHORT);
        return isoDate(info.year as Number, info.month as Number, info.day as Number);
    }

    function isoDate(year as Number, month as Number, day as Number) as String {
        return year.format("%04d") + "-" + month.format("%02d") + "-" + day.format("%02d");
    }
}
