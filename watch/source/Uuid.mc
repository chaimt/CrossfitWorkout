//
// Uuid.mc
//
// Random (version 4) UUIDs for message, result and session ids, in the
// lower-case hyphenated form docs/protocol.md section 0 requires.
// Cryptography.randomBytes (API 3.0.0) is used when present; Math.rand is
// the fallback so ids are still unique enough in the simulator.
//
import Toybox.Cryptography;
import Toybox.Lang;
import Toybox.Math;

module Uuid {

    const HEX = "0123456789abcdef";

    function v4() as String {
        var bytes = _random16();
        // Version 4 and the RFC 4122 variant bits.
        bytes[6] = (bytes[6] & 0x0F) | 0x40;
        bytes[8] = (bytes[8] & 0x3F) | 0x80;
        var out = "";
        for (var i = 0; i < 16; i++) {
            if (i == 4 || i == 6 || i == 8 || i == 10) {
                out += "-";
            }
            var b = bytes[i];
            out += HEX.substring(b >> 4, (b >> 4) + 1) + HEX.substring(b & 0x0F, (b & 0x0F) + 1);
        }
        return out;
    }

    function _random16() as Array<Number> {
        var out = new [16] as Array<Number>;
        if (Toybox has :Cryptography && Cryptography has :randomBytes) {
            var b = Cryptography.randomBytes(16);
            for (var i = 0; i < 16; i++) {
                out[i] = b[i];
            }
            return out;
        }
        for (var i = 0; i < 16; i++) {
            out[i] = Math.rand() & 0xFF;
        }
        return out;
    }
}
