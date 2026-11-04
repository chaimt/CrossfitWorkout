//
// MetconView.mc
//
// Metcon running screen (REQ-WM11): format label, big clock, round /
// interval counter, current movement line, secondary clock (cap /
// remaining / total), live HR and the WOD Timer progress ring. Page 2
// (UP / DOWN) is the full movement list. Fonts and geometry are chosen
// once in onLayout; the 100 ms session tick only requests a redraw when
// the shown second changes (REQ-NF03).
//
// Controls (REQ-WN06, section 6.3): START pause (+menu), BACK = LAP only
// while the timer runs, UP / DOWN page, hold UP pause + menu, tap = +1
// round when touch and tap-to-log are enabled.
//
import Toybox.Activity;
import Toybox.Graphics;
import Toybox.Lang;
import Toybox.WatchUi;

class MetconView extends WatchUi.View {

    const CLOCK_FONTS = [Graphics.FONT_NUMBER_THAI_HOT, Graphics.FONT_NUMBER_HOT,
        Graphics.FONT_NUMBER_MEDIUM, Graphics.FONT_NUMBER_MILD] as Array<Graphics.FontDefinition>;

    var session as Session;
    var page as Number = 0;
    private var _w as Number = 0;
    private var _h as Number = 0;
    private var _clockFont as Graphics.FontDefinition = Graphics.FONT_NUMBER_HOT;
    private var _clockFontLong as Graphics.FontDefinition = Graphics.FONT_NUMBER_MEDIUM;
    private var _pen as Number = 4;
    private var _r as Number = 0;

    function initialize(s as Session) {
        View.initialize();
        session = s;
    }

    function onLayout(dc as Graphics.Dc) as Void {
        _w = dc.getWidth();
        _h = dc.getHeight();
        _clockFont = _fit(dc, "88:88");
        _clockFontLong = _fit(dc, "8:88:88");
        _pen = (_w * 0.035).toNumber();
        if (_pen < 4) {
            _pen = 4;
        }
        _r = _w / 2 - _pen / 2 - 1;
    }

    function onShow() as Void {
        session.setListener(self);
    }

    function onHide() as Void {
        session.setListener(null);
    }

    function _fit(dc as Graphics.Dc, text as String) as Graphics.FontDefinition {
        for (var i = 0; i < CLOCK_FONTS.size() - 1; i++) {
            var font = CLOCK_FONTS[i];
            if (dc.getTextWidthInPixels(text, font) <= _w * 0.78 && dc.getFontHeight(font) <= _h * 0.34) {
                return font;
            }
        }
        return CLOCK_FONTS[CLOCK_FONTS.size() - 1];
    }

    function togglePage() as Void {
        page = 1 - page;
        WatchUi.requestUpdate();
    }

    function onUpdate(dc as Graphics.Dc) as Void {
        dc.setColor(Theme.BACKGROUND, Theme.BACKGROUND);
        dc.clear();
        if (page == 1) {
            _drawList(dc);
            return;
        }
        var s = session;
        var t = s.now();
        var st = s.seg;
        var prep = s.clock.state == CS_PREP || (s.clock.state == CS_PAUSED && s.clock.resumeState == CS_PREP);
        var paused = s.isPaused();
        var phase = prep ? PH_PREP : st.phase;
        var color = Theme.phaseColor(phase);
        var cx = _w / 2;

        // Progress ring.
        var frac = prep ? 1.0 - (s.prepMs() - s.clock.prepAt(t)).toFloat() / (s.prepMs() > 0 ? s.prepMs() : 1)
            : st.progress();
        if (!prep && st.phaseMs == 0) {
            frac = (st.phaseElapsedMs % 60000) / 60000.0;
        }
        _ring(dc, frac, color);

        var part = s.part;
        dc.setColor(Theme.DIM, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, _h * 0.13, Graphics.FONT_XTINY, U.str(part, "l") + " " + U.str(part, "n"),
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        var label = prep ? "GET READY" : st.label;
        if (paused) {
            label = "PAUSED";
        } else if (U.flag(part, "fb", false) && !prep) {
            label = "MANUAL TIMER";
        }
        dc.setColor(paused ? Theme.PAUSED : color, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, _h * 0.22, Graphics.FONT_SMALL, label,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);

        var secs = prep ? Fmt.countdownSeconds(s.prepMs() - s.clock.prepAt(t)) : Engine.clockSeconds(st);
        var clock = prep ? secs.format("%d") : Fmt.clock(secs);
        dc.setColor(paused ? Theme.DIM : Theme.TEXT, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, _h * 0.42, clock.length() > 5 ? _clockFontLong : _clockFont, clock,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);

        dc.setColor(Theme.TEXT, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, _h * 0.60, Graphics.FONT_MEDIUM, _counter(s),
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        dc.setColor(Theme.OPEN, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, _h * 0.69, Graphics.FONT_TINY, _movement(s),
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        dc.setColor(Theme.DIM, Graphics.COLOR_TRANSPARENT);
        var footer = s.message != null ? s.message as String : _secondary(s);
        if (s.recorder.failed()) {
            footer = "REC FAILED - results kept";
        }
        dc.drawText(cx, _h * 0.77, Graphics.FONT_XTINY, footer,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        _drawHr(dc);
    }

    //! "ROUND 2/3", "ROUNDS 5", "MIN 4/10".
    function _counter(s as Session) as String {
        var m = s.metcon;
        var prog = s.prog;
        if (m == null || prog == null) {
            return "";
        }
        if (m.countsManually()) {
            var planned = m.plannedRounds;
            if (planned > 0) {
                var shown = m.rounds + 1 > planned ? planned : m.rounds + 1;
                return "ROUND " + shown.format("%d") + "/" + planned.format("%d");
            }
            return "ROUNDS " + m.rounds.format("%d");
        }
        var total = prog.roundCount;
        return "ROUND " + (s.seg.round + 1).format("%d") + "/" + total.format("%d");
    }

    //! Current movement / station line with this round's reps.
    function _movement(s as Session) as String {
        var m = s.metcon;
        if (m == null) {
            return "";
        }
        var part = s.part;
        if (s.seg.station >= 0 && s.seg.station < m.moves.size()) {
            var next = s.seg.station + 1 < m.moves.size() ? s.seg.station + 1 : 0;
            var line = m.moves[s.seg.station] as String;
            return m.moves.size() > 1 ? line + "  > " + U.head(m.moves[next] as String, 10) : line;
        }
        var rl = U.arr(part, "rl");
        if (rl != null && m.rounds < rl.size() && !m.movementMode) {
            return (rl[m.rounds] as String) + "  " + m.currentMove();
        }
        return m.currentMove();
    }

    //! Secondary clock: cap left, total left, or elapsed.
    function _secondary(s as Session) as String {
        var prog = s.prog;
        var st = s.seg;
        if (prog == null) {
            return "";
        }
        if (prog.capMs > 0 && st.phase == PH_OPEN) {
            return "Cap " + Fmt.clock(Fmt.countdownSeconds(prog.capMs - st.partMs)) + " left";
        }
        if (prog.totalMs > 0 && prog.size() > 1) {
            return "Total left " + Fmt.clock(Fmt.countdownSeconds(prog.totalMs - st.partMs));
        }
        if (st.phase == PH_WORK) {
            return "Elapsed " + Fmt.clock(Fmt.countUpSeconds(st.partMs));
        }
        return "LAP = +1";
    }

    function _ring(dc as Graphics.Dc, frac as Float, color as Number) as Void {
        dc.setPenWidth(_pen);
        dc.setColor(Theme.TRACK, Graphics.COLOR_TRANSPARENT);
        dc.drawCircle(_w / 2, _h / 2, _r);
        if (frac <= 0.0) {
            return;
        }
        dc.setColor(color, Graphics.COLOR_TRANSPARENT);
        if (frac >= 1.0) {
            dc.drawCircle(_w / 2, _h / 2, _r);
            return;
        }
        // Degrees counter-clockwise from 3 o'clock: sweep clockwise from 12.
        var end = 90 - (frac * 360).toNumber();
        if (end == 90) {
            return;
        }
        dc.drawArc(_w / 2, _h / 2, _r, Graphics.ARC_CLOCKWISE, 90, end < 0 ? end + 360 : end);
    }

    //! Live HR (REQ-WR05); "--" while there is no reading.
    function _drawHr(dc as Graphics.Dc) as Void {
        var info = Activity.getActivityInfo();
        var hr = info != null ? info.currentHeartRate : null;
        var text = hr != null ? hr.format("%d") : "--";
        dc.setColor(Theme.HEART, Graphics.COLOR_TRANSPARENT);
        dc.fillCircle(_w / 2 - _w * 0.06, _h * 0.87, _w * 0.012);
        dc.setColor(Theme.TEXT, Graphics.COLOR_TRANSPARENT);
        dc.drawText(_w / 2, _h * 0.87, Graphics.FONT_TINY, text,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
    }

    //! Page 2: the full movement list (current one highlighted).
    function _drawList(dc as Graphics.Dc) as Void {
        var s = session;
        var ln = U.arr(s.part, "ln");
        var lines = ln == null ? [] : ln;
        var cx = _w / 2;
        dc.setColor(Theme.OPEN, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, _h * 0.12, Graphics.FONT_SMALL, U.str(s.part, "n") as String,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        var lh = dc.getFontHeight(Graphics.FONT_XTINY);
        var y = _h * 0.2;
        var cur = s.metcon != null ? (s.metcon as MetconRun).currentMove() : "";
        for (var i = 0; i < lines.size() && y < _h * 0.86; i++) {
            var line = lines[i] as String;
            dc.setColor(U.eq(line, cur) ? Theme.WORK : Theme.TEXT, Graphics.COLOR_TRANSPARENT);
            dc.drawText(cx, y, Graphics.FONT_XTINY, line, Graphics.TEXT_JUSTIFY_CENTER);
            y += lh;
        }
        dc.setColor(Theme.DIM, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, _h * 0.9, Graphics.FONT_XTINY, Fmt.clock(Engine.clockSeconds(s.seg)),
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
    }
}

class MetconDelegate extends WatchUi.BehaviorDelegate {

    private var _s as Session;
    private var _view as MetconView;

    function initialize(s as Session, view as MetconView) {
        BehaviorDelegate.initialize();
        _s = s;
        _view = view;
    }

    //! START: pause and open the menu; on the paused screen, resume.
    function onSelect() as Boolean {
        if (_s.isPaused()) {
            _s.resume();
        } else {
            _s.pause();
            RunMenus.openPause(_s);
        }
        return true;
    }

    //! BACK = LAP only while the timer runs (section 6.3); when paused it
    //! opens the pause menu (Resume / Finish / Discard).
    function onBack() as Boolean {
        if (_s.clock.state == CS_RUN) {
            _s.lap();
            return true;
        }
        if (_s.isPaused()) {
            RunMenus.openPause(_s);
        }
        return true;
    }

    function onMenu() as Boolean {
        _s.pause();
        RunMenus.openPause(_s);
        return true;
    }

    function onNextPage() as Boolean {
        _view.togglePage();
        return true;
    }

    function onPreviousPage() as Boolean {
        _view.togglePage();
        return true;
    }

    function onTap(evt as WatchUi.ClickEvent) as Boolean {
        if ((_s.settings["tch"] as Boolean) && (_s.settings["tap"] as Boolean) && _s.clock.state == CS_RUN) {
            _s.lap();
        }
        return true;
    }
}
