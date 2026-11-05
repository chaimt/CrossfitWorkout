//
// Theme.mc
//
// Colors shared by all screens (from WOD Timer's WodTheme). Each phase has
// one color for its label and progress ring, and every phase is also
// labelled in text so color is never the only signal (REQ-NF08).
//
import Toybox.Graphics;
import Toybox.Lang;

module Theme {

    const BACKGROUND = Graphics.COLOR_BLACK;
    const TEXT = Graphics.COLOR_WHITE;
    const DIM = 0x8A8A8A;
    const TRACK = 0x2A2A2A;
    const HEART = 0xFF4060;

    const PREP = 0xFFB300;
    const WORK = 0x00D66E;
    const REST = 0xFF4040;
    const OPEN = 0x3D9BFF;
    const DONE = 0x3D9BFF;
    const PAUSED = 0xFFB300;
    const WARN = 0xFF7A00;

    function phaseColor(phase as Number) as Number {
        if (phase == PH_PREP) {
            return PREP;
        }
        if (phase == PH_REST) {
            return REST;
        }
        if (phase == PH_OPEN) {
            return WORK;
        }
        if (phase == PH_DONE) {
            return DONE;
        }
        return WORK;
    }
}
