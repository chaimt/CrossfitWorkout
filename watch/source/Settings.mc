//
// Settings.mc
//
// Watch settings (REQ-WG01, docs/protocol.md section 6). Three layers, per
// key: a local override set on the watch wins; otherwise the phone profile
// (profile.put); otherwise the built-in default. "Reset to phone defaults"
// clears the override mask. A run captures snapshot() at Start, so a
// profile update during a run only affects future runs.
//
// Storage keys: "prof" = {"rev": n, "h": hash, "p": payload}, "ovr" = {key
// => value}. Pure apart from the Kv adapter; tested in SettingsTest.mc.
//
import Toybox.Lang;
import Toybox.System;

module Settings {

    const KEYS = ["prep", "snd", "vib", "rec", "tch", "ws", "wsf", "u", "rest", "tap"] as Array<String>;
    const PREP_CHOICES = [0, 3, 5, 10, 15, 20, 30] as Array<Number>;
    const PREP_MAX = 30;

    var _kv as Kv? = null;

    function setStore(kv as Kv) as Void {
        _kv = kv;
    }

    function _store() as Kv {
        if (_kv == null) {
            _kv = new Kv();
        }
        return _kv as Kv;
    }

    //! Built-in default of `key` (unit falls back to the device preference).
    function dflt(key as String) {
        if (key.equals("prep")) {
            return 10;
        }
        if (key.equals("ws")) {
            return 2.5;
        }
        if (key.equals("wsf")) {
            return 0.5;
        }
        if (key.equals("u")) {
            return System.getDeviceSettings().weightUnits == System.UNIT_STATUTE ? "lb" : "kg";
        }
        if (key.equals("rest")) {
            return 120;
        }
        return true;
    }

    //! True when `value` has the right type and range for `key`.
    function validValue(key as String, value) as Boolean {
        if (key.equals("prep")) {
            var n = U.asNum(value);
            return n != null && n >= 0 && n <= PREP_MAX;
        }
        if (key.equals("rest")) {
            var n = U.asNum(value);
            return n != null && n >= 0 && n <= 3600;
        }
        if (key.equals("ws") || key.equals("wsf")) {
            var f = U.asReal(value);
            return f != null && f > 0.0 && f <= 50.0;
        }
        if (key.equals("u")) {
            return value instanceof String && (value.equals("kg") || value.equals("lb"));
        }
        return value instanceof Boolean;
    }

    //! Effective value: override > phone profile > default.
    function get(key as String) {
        var v = explicit(key);
        return v == null ? dflt(key) : v;
    }

    //! Override or phone value of `key`, or null when neither is set.
    function explicit(key as String) {
        var ovr = _store().get("ovr");
        if (ovr instanceof Dictionary && ovr.hasKey(key) && validValue(key, ovr.get(key))) {
            return ovr.get(key);
        }
        var prof = _store().get("prof");
        if (prof instanceof Dictionary) {
            var p = U.dict(prof, "p");
            if (p != null && p.hasKey(key) && validValue(key, p.get(key))) {
                return p.get(key);
            }
        }
        return null;
    }

    function isOverridden(key as String) as Boolean {
        var ovr = _store().get("ovr");
        return ovr instanceof Dictionary && ovr.hasKey(key);
    }

    //! Local override from the on-watch settings menu.
    function setLocal(key as String, value) as Boolean {
        if (!validValue(key, value)) {
            return false;
        }
        var ovr = _store().get("ovr");
        var d = ovr instanceof Dictionary ? ovr : {};
        d.put(key, value);
        return _store().put("ovr", d);
    }

    function resetToPhone() as Void {
        _store().del("ovr");
    }

    //! Applies a profile.put. Returns [status, reason] for the ack
    //! (REQ-SY11/SY14: validate fully, then commit, stale / identical
    //! retries / conflicts detected by revision + content hash).
    function applyProfile(rev as Number, payload) as Array {
        if (!(payload instanceof Dictionary)) {
            return ["error", "invalid"];
        }
        var keys = payload.keys();
        for (var i = 0; i < keys.size(); i++) {
            var k = keys[i];
            if (!(k instanceof String) || !U.inList(KEYS, k) || !validValue(k, payload.get(k))) {
                return ["error", "invalid"];
            }
        }
        var h = WireSize.hash(payload);
        var cur = _store().get("prof");
        if (cur instanceof Dictionary) {
            var curRev = U.num(cur, "rev");
            if (curRev != null && rev < curRev) {
                return ["stale", "revision"];
            }
            if (curRev != null && rev == curRev) {
                return U.num(cur, "h") == h ? ["already_applied", null] : ["conflict", "conflict"];
            }
        }
        if (!_store().put("prof", {"rev" => rev, "h" => h, "p" => payload})) {
            return ["error", "capacity"];
        }
        return ["ok", null];
    }

    //! Effective settings captured into the run snapshot.
    function snapshot() as Dictionary {
        var out = {};
        for (var i = 0; i < KEYS.size(); i++) {
            out.put(KEYS[i], get(KEYS[i]));
        }
        return out;
    }

    // Typed accessors used by the UI and alerts.

    function prepSeconds() as Number {
        return U.asNum(get("prep")) as Number;
    }

    function sound() as Boolean {
        return get("snd") as Boolean;
    }

    function vibrate() as Boolean {
        return get("vib") as Boolean;
    }

    function record() as Boolean {
        return get("rec") as Boolean;
    }

    function touch() as Boolean {
        return get("tch") as Boolean;
    }

    function tapToLog() as Boolean {
        return get("tap") as Boolean;
    }

    function unit() as String {
        return get("u") as String;
    }

    function restSeconds() as Number {
        return U.asNum(get("rest")) as Number;
    }

    //! Coarse / fine weight step for `unit`. The phone's ws / wsf are in the
    //! profile unit; for the other unit the REQ-WE01 defaults apply.
    function weightStep(unit as String, fine as Boolean) as Float {
        var set = explicit(fine ? "wsf" : "ws");
        if (set != null && unit.equals(Settings.unit())) {
            return U.asReal(set) as Float;
        }
        if (unit.equals("lb")) {
            return fine ? 1.0 : 5.0;
        }
        return fine ? 0.5 : 2.5;
    }

    //! The countdown choice after `current` (wrapping), for the menu.
    function nextPrep(current as Number) as Number {
        for (var i = 0; i < PREP_CHOICES.size(); i++) {
            if (PREP_CHOICES[i] > current) {
                return PREP_CHOICES[i];
            }
        }
        return PREP_CHOICES[0];
    }
}
