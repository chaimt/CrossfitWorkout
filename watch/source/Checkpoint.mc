//
// Checkpoint.mc
//
// Versioned checkpoint generations for the active session (REQ-WO06,
// REQ-NF01). Two slots ("ck0" / "ck1") alternate; each record carries its
// generation number and a content hash. A write goes to the slot the
// pointer does NOT reference, is read back and validated, and only then is
// the pointer ("ckp") moved - so a crash mid-write leaves the previous
// generation intact. If the pointer itself is lost or stale, load() scans
// both slots and takes the highest valid generation.
//
// Record: {"g": generation, "h": WireSize.hash(data), "v": 1, "d": data}.
// Pure apart from the Kv adapter; tested in CheckpointTest.mc.
//
import Toybox.Lang;

module Checkpoint {

    const VERSION = 1;
    const SLOTS = ["ck0", "ck1"] as Array<String>;
    const POINTER = "ckp";

    //! Writes a new generation of `data`. Returns the generation, or -1
    //! when the store refused the write (the old generation stays valid).
    function save(kv as Kv, data as Dictionary) as Number {
        // Overwrite the slot that does not hold the latest valid record;
        // number above every generation ever written (valid or not) so a
        // corrupted newer record can never outrank the new one.
        var best = _pointer(kv);
        if (best == null) {
            best = _scan(kv);
        }
        var slot = best == null ? 0 : 1 - (best[1] as Number);
        var gen = _maxRaw(kv) + 1;
        var record = {"g" => gen, "h" => WireSize.hash(data), "v" => VERSION, "d" => data};
        if (!kv.put(SLOTS[slot], record)) {
            return -1;
        }
        // Read back before retiring the old generation (REQ-WO06).
        if (_valid(kv.get(SLOTS[slot])) != gen) {
            return -1;
        }
        if (!kv.put(POINTER, [gen, slot])) {
            // The new record is valid; a later scan still finds it.
            return gen;
        }
        return gen;
    }

    //! Latest valid checkpoint data, or null when there is none.
    function load(kv as Kv) as Dictionary? {
        var best = _scan(kv);
        if (best == null) {
            return null;
        }
        var rec = kv.get(SLOTS[best[1] as Number]) as Dictionary;
        return rec.get("d") as Dictionary;
    }

    //! Generation of the latest valid checkpoint (0 = none).
    function generation(kv as Kv) as Number {
        var best = _scan(kv);
        return best == null ? 0 : best[0] as Number;
    }

    function clear(kv as Kv) as Void {
        kv.del(POINTER);
        kv.del(SLOTS[0]);
        kv.del(SLOTS[1]);
    }

    function exists(kv as Kv) as Boolean {
        return _scan(kv) != null;
    }

    //! [generation, slot] of the pointer, when it references a valid record.
    function _pointer(kv as Kv) as Array? {
        var p = kv.get(POINTER);
        if (!(p instanceof Array) || p.size() != 2) {
            return null;
        }
        var slot = U.asNum(p[1]);
        if (slot == null || slot < 0 || slot > 1) {
            return null;
        }
        if (_valid(kv.get(SLOTS[slot])) != U.asNum(p[0])) {
            return null;
        }
        return p;
    }

    //! [generation, slot] of the highest valid record across both slots.
    function _scan(kv as Kv) as Array? {
        var g0 = _valid(kv.get(SLOTS[0]));
        var g1 = _valid(kv.get(SLOTS[1]));
        if (g0 <= 0 && g1 <= 0) {
            return null;
        }
        return g0 >= g1 ? [g0, 0] : [g1, 1];
    }

    //! Highest generation number present in either slot, valid or not.
    function _maxRaw(kv as Kv) as Number {
        var m = 0;
        for (var i = 0; i < 2; i++) {
            var rec = kv.get(SLOTS[i]);
            if (rec instanceof Dictionary) {
                var g = U.num(rec, "g");
                if (g != null && g > m) {
                    m = g;
                }
            }
        }
        return m;
    }

    //! Generation of a well-formed record whose hash matches, else 0.
    function _valid(rec) as Number {
        if (!(rec instanceof Dictionary)) {
            return 0;
        }
        var g = U.num(rec, "g");
        var h = U.num(rec, "h");
        var d = rec.get("d");
        if (g == null || h == null || g <= 0 || !(d instanceof Dictionary) || U.num(rec, "v") != VERSION) {
            return 0;
        }
        return WireSize.hash(d) == h ? g : 0;
    }
}
