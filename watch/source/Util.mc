//
// Util.mc
//
// Typed, null-safe readers for the Dictionaries that arrive from the phone
// (docs/protocol.md section 1: the wire has no nulls, an absent value is an
// omitted key, and Android numbers may arrive as Long or Double). Every
// decoder goes through these so a wrong type is treated as "absent" instead
// of throwing mid-validation. Pure; unit-tested in UtilTest.mc.
//
import Toybox.Lang;

module U {

    //! String value of `key`, or null when absent / not a String.
    function str(d as Dictionary?, key as String) as String? {
        if (d == null) {
            return null;
        }
        var v = d.get(key);
        return v instanceof String ? v : null;
    }

    //! Integer value of `key` (Number or a Long that fits), else null.
    function num(d as Dictionary?, key as String) as Number? {
        if (d == null) {
            return null;
        }
        return asNum(d.get(key));
    }

    //! Integer view of any wire value: Number, or a Long within 32 bits.
    function asNum(v) as Number? {
        if (v instanceof Number) {
            return v;
        }
        if (v instanceof Long) {
            if (v > 2147483647l || v < -2147483648l) {
                return null;
            }
            return v.toNumber();
        }
        return null;
    }

    //! Numeric value (integer or real) of `key` as a Float, else null.
    function real(d as Dictionary?, key as String) as Float? {
        if (d == null) {
            return null;
        }
        return asReal(d.get(key));
    }

    function asReal(v) as Float? {
        if (v instanceof Number || v instanceof Long || v instanceof Float || v instanceof Double) {
            return v.toFloat();
        }
        return null;
    }

    function isNumeric(v) as Boolean {
        return v instanceof Number || v instanceof Long || v instanceof Float || v instanceof Double;
    }

    function bool(d as Dictionary?, key as String) as Boolean? {
        if (d == null) {
            return null;
        }
        var v = d.get(key);
        return v instanceof Boolean ? v : null;
    }

    //! Boolean with a default for absent keys.
    function flag(d as Dictionary?, key as String, dflt as Boolean) as Boolean {
        var v = bool(d, key);
        return v == null ? dflt : v;
    }

    function arr(d as Dictionary?, key as String) as Array? {
        if (d == null) {
            return null;
        }
        var v = d.get(key);
        return v instanceof Array ? v : null;
    }

    function dict(d as Dictionary?, key as String) as Dictionary? {
        if (d == null) {
            return null;
        }
        var v = d.get(key);
        return v instanceof Dictionary ? v : null;
    }

    //! Null-safe string equality (== on objects compares references).
    function eq(a as String?, b as String?) as Boolean {
        if (a == null || b == null) {
            return a == null && b == null;
        }
        return a.equals(b);
    }

    //! True when `list` contains a String equal to `s`.
    function inList(list as Array, s as String?) as Boolean {
        if (s == null) {
            return false;
        }
        for (var i = 0; i < list.size(); i++) {
            var e = list[i];
            if (e instanceof String && e.equals(s)) {
                return true;
            }
        }
        return false;
    }

    //! Copy of `a` without the element at `index`.
    function removeAt(a as Array, index as Number) as Array {
        var out = [];
        for (var i = 0; i < a.size(); i++) {
            if (i != index) {
                out.add(a[i]);
            }
        }
        return out;
    }

    //! Shallow copy of a Dictionary (so a stored snapshot is never aliased).
    function copyDict(d as Dictionary) as Dictionary {
        var out = {};
        var keys = d.keys();
        for (var i = 0; i < keys.size(); i++) {
            out.put(keys[i], d.get(keys[i]));
        }
        return out;
    }

    //! Integer clamp.
    function clamp(v as Number, lo as Number, hi as Number) as Number {
        if (v < lo) {
            return lo;
        }
        if (v > hi) {
            return hi;
        }
        return v;
    }

    //! Number of display characters (not bytes) in `s`.
    function chars(s as String) as Number {
        return s.length();
    }

    //! First `n` characters of `s`.
    function head(s as String, n as Number) as String {
        if (s.length() <= n) {
            return s;
        }
        return s.substring(0, n) as String;
    }
}
