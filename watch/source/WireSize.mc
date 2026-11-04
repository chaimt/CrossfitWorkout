//
// WireSize.mc
//
// Size estimator and content hash for wire / storage objects.
//
// size(): the compact-JSON UTF-8 length of a value - the rule both apps use
// for the 4096-byte message budget (docs/protocol.md section 1, REQ-SY06/
// SY15). It is computed without building the string so a 4 KiB plan never
// needs a second 4 KiB copy in memory.
//
// hash(): a 32-bit content hash that ignores Dictionary key order (the
// order of keys read back from Application.Storage or received from the
// phone is not guaranteed). Used to tell an identical retry from a
// conflicting one (REQ-SY11/SY14) and to validate checkpoint records
// (REQ-WO06). Not cryptographic.
//
import Toybox.Lang;

module WireSize {

    const MAX_MESSAGE = 4096;

    //! Compact-JSON UTF-8 byte length of `v`.
    function size(v) as Number {
        if (v == null) {
            return 4;
        }
        if (v instanceof String) {
            return stringSize(v);
        }
        if (v instanceof Boolean) {
            return v ? 4 : 5;
        }
        if (v instanceof Number) {
            return v.format("%d").length();
        }
        if (v instanceof Long) {
            return v.toString().length();
        }
        if (v instanceof Float || v instanceof Double) {
            return realText(v.toFloat()).length();
        }
        if (v instanceof Array) {
            var n = 2;
            for (var i = 0; i < v.size(); i++) {
                n += size(v[i]) + (i > 0 ? 1 : 0);
            }
            return n;
        }
        if (v instanceof Dictionary) {
            var keys = v.keys();
            var n = 2;
            for (var i = 0; i < keys.size(); i++) {
                n += size(keys[i]) + 1 + size(v.get(keys[i])) + (i > 0 ? 1 : 0);
            }
            return n;
        }
        // Unknown types are not allowed on the wire; count them generously.
        return 16;
    }

    //! Quoted, escaped UTF-8 length of a JSON string.
    function stringSize(s as String) as Number {
        var n = 2;
        var chars = s.toCharArray();
        for (var i = 0; i < chars.size(); i++) {
            var c = chars[i].toNumber();
            if (c == 0x22 || c == 0x5C || c == 0x0A || c == 0x0D || c == 0x09 || c == 0x08 || c == 0x0C) {
                n += 2;
            } else if (c < 0x20) {
                n += 6;
            } else if (c < 0x80) {
                n += 1;
            } else if (c < 0x800) {
                n += 2;
            } else if (c < 0x10000) {
                n += 3;
            } else {
                n += 4;
            }
        }
        return n;
    }

    //! UTF-8 byte length of a raw string (free-text bound, REQ-WO05).
    function utf8Bytes(s as String) as Number {
        return stringSize(s) - 2;
    }

    //! JSON-ish text of a real number: "100.0", "102.5", "0.125".
    function realText(f as Float) as String {
        if (f == f.toNumber().toFloat()) {
            return f.toNumber().format("%d") + ".0";
        }
        var t = f.format("%.4f");
        while (t.length() > 1 && t.substring(t.length() - 1, t.length()).equals("0")) {
            t = t.substring(0, t.length() - 1) as String;
        }
        return t;
    }

    //! Order-independent 32-bit content hash.
    function hash(v) as Number {
        if (v == null) {
            return 0x1F;
        }
        if (v instanceof String) {
            return _fnv(v, 0x811C9DC5);
        }
        if (v instanceof Boolean) {
            return v ? 0x2B : 0x3D;
        }
        if (v instanceof Number) {
            return _mix(v, 0x51);
        }
        if (v instanceof Long) {
            return _mix((v & 0x7FFFFFFFl).toNumber() ^ (v >> 31).toNumber(), 0x53);
        }
        if (v instanceof Float || v instanceof Double) {
            return _mix((v.toFloat() * 1000).toNumber(), 0x55);
        }
        if (v instanceof Array) {
            var h = 0x61 + v.size();
            for (var i = 0; i < v.size(); i++) {
                h = h * 31 + hash(v[i]);
            }
            return h;
        }
        if (v instanceof Dictionary) {
            // Sum of per-entry hashes: independent of iteration order.
            var keys = v.keys();
            var h = 0x71 + keys.size();
            for (var i = 0; i < keys.size(); i++) {
                h += _mix(hash(keys[i]), hash(v.get(keys[i])));
            }
            return h;
        }
        return 0x7F;
    }

    function _fnv(s as String, seed as Number) as Number {
        var h = seed;
        var chars = s.toCharArray();
        for (var i = 0; i < chars.size(); i++) {
            h = (h ^ chars[i].toNumber()) * 16777619;
        }
        return h;
    }

    function _mix(a as Number, b as Number) as Number {
        var h = (a ^ 0x5BD1E995) * 16777619;
        h = (h ^ b) * 16777619;
        return h ^ (h >> 15);
    }
}
