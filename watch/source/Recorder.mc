//
// Recorder.mc
//
// FIT recording for one uninterrupted session (REQ-WR01/02/04/05/09/10):
//   * created + started at the first work start (prep is not recorded);
//   * timer stopped for explicit pause, part-result entry and unprescribed
//     transition waits; resumed for work and prescribed rest;
//   * one lap per logical completion / interval / part boundary;
//   * every create / start / save failure is caught and reported through
//     outcome ("failed") - workout results never depend on recording.
// Sport selection is pure (FitSport.choose) and unit-tested; HrStats is the
// per-part / per-session HR accumulator (part HR is never the session
// total, REQ-WR10).
//
import Toybox.Activity;
import Toybox.ActivityRecording;
import Toybox.Lang;

class Recorder {

    //! "off" · "saved" · "failed" · "unknown" (protocol `fit`).
    var outcome as String = "off";
    var error as String? = null;
    private var _enabled as Boolean;
    private var _session as ActivityRecording.Session? = null;

    function initialize(enabled as Boolean) {
        _enabled = enabled && (Toybox has :ActivityRecording);
        outcome = _enabled ? "unknown" : "off";
    }

    function active() as Boolean {
        return _session != null;
    }

    function failed() as Boolean {
        return outcome.equals("failed");
    }

    //! Creates and starts the session at the first work start.
    function begin(name as String, sport as Number, subSport as Number) as Void {
        if (!_enabled || _session != null || failed()) {
            return;
        }
        try {
            _session = ActivityRecording.createSession({
                :name => U.head(name, 15),
                :sport => sport as Activity.Sport,
                :subSport => subSport as Activity.SubSport
            });
            (_session as ActivityRecording.Session).start();
        } catch (e) {
            _fail("start");
        }
    }

    //! Stops the recording timer (pause, result entry, transition wait).
    function hold() as Void {
        var s = _session;
        if (s == null) {
            return;
        }
        try {
            if (s.isRecording()) {
                s.stop();
            }
        } catch (e) {
            _fail("stop");
        }
    }

    //! Restarts the recording timer (work, prescribed rest).
    function resume() as Void {
        var s = _session;
        if (s == null) {
            return;
        }
        try {
            if (!s.isRecording()) {
                s.start();
            }
        } catch (e) {
            _fail("start");
        }
    }

    function lap() as Void {
        var s = _session;
        if (s == null) {
            return;
        }
        try {
            if (s.isRecording()) {
                s.addLap();
            }
        } catch (e) {
            // A missed lap is not fatal to the recording.
        }
    }

    //! Saves the activity; returns the final outcome.
    function save() as String {
        var s = _session;
        if (s == null) {
            return outcome.equals("unknown") ? "off" : outcome;
        }
        try {
            if (s.isRecording()) {
                s.stop();
            }
            outcome = s.save() ? "saved" : "failed";
        } catch (e) {
            outcome = "failed";
        }
        _session = null;
        return outcome;
    }

    function discard() as Void {
        var s = _session;
        if (s == null) {
            return;
        }
        try {
            if (s.isRecording()) {
                s.stop();
            }
            s.discard();
        } catch (e) {
            // Nothing else to do; the local data is gone either way.
        }
        _session = null;
        outcome = "off";
    }

    function _fail(what as String) as Void {
        outcome = "failed";
        error = what;
        _session = null;
    }
}

module FitSport {

    //! [sport, subSport] for a session over part `modes` in run order
    //! (REQ-WR01): the first metcon's AMRAP / EMOM / Tabata sub-sport, else
    //! HIIT; strength-only -> training / strength; otherwise generic
    //! training. Constants per Appendix I.1 (verify on device, REQ-TS07).
    function choose(modes as Array) as Array<Number> {
        var strength = false;
        for (var i = 0; i < modes.size(); i++) {
            var m = modes[i] as String;
            if (m.equals("amrap")) {
                return [Activity.SPORT_HIIT as Number, Activity.SUB_SPORT_AMRAP as Number];
            }
            if (m.equals("emom")) {
                return [Activity.SPORT_HIIT as Number, Activity.SUB_SPORT_EMOM as Number];
            }
            if (m.equals("tabata")) {
                return [Activity.SPORT_HIIT as Number, Activity.SUB_SPORT_TABATA as Number];
            }
            if (m.equals("for_time") || m.equals("intervals") || m.equals("max_effort")) {
                return [Activity.SPORT_HIIT as Number, Activity.SUB_SPORT_HIIT as Number];
            }
            if (PlanCodec.isStrength(m)) {
                strength = true;
            }
        }
        if (strength) {
            return [Activity.SPORT_TRAINING as Number, Activity.SUB_SPORT_STRENGTH_TRAINING as Number];
        }
        return [Activity.SPORT_TRAINING as Number, Activity.SUB_SPORT_GENERIC as Number];
    }
}

//! Running HR average / max from 1 Hz samples (equal time weighting).
class HrStats {
    var sum as Number = 0;
    var count as Number = 0;
    var max as Number = 0;

    function initialize() {
    }

    function add(hr as Number?) as Void {
        if (hr == null || hr <= 0) {
            return;
        }
        sum += hr;
        count += 1;
        if (hr > max) {
            max = hr;
        }
    }

    //! [avg, max] or null when no sample was taken (never fabricated).
    function pair() as Array<Number>? {
        if (count == 0) {
            return null;
        }
        return [(sum + count / 2) / count, max];
    }

    function toArray() as Array<Number> {
        return [sum, count, max];
    }

    function restore(a as Array?) as Void {
        if (a != null && a.size() == 3) {
            sum = a[0] as Number;
            count = a[1] as Number;
            max = a[2] as Number;
        }
    }
}
