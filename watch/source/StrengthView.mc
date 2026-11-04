//
// StrengthView.mc
//
// Strength set and rest screens (REQ-WS01..WS05, REQ-WC02).
//   Set screen:  movement, "SET 2/5", target reps x load, note, previous
//                actual. START / LAP / tap logs the displayed set with one
//                press (rest starts at that press); an unresolved target
//                opens entry first (REQ-WS02).
//   Rest screen: countdown, then "+0:12" overtime until START / LAP, and a
//                preview of the next set (REQ-WS03).
//   UP / DOWN:   set list page.  Hold UP: pause + strength menu.
//
import Toybox.Activity;
import Toybox.Graphics;
import Toybox.Lang;
import Toybox.WatchUi;

class StrengthView extends WatchUi.View {

    var session as Session;
    var page as Number = 0;
    private var _w as Number = 0;
    private var _h as Number = 0;

    function initialize(s as Session) {
        View.initialize();
        session = s;
    }

    function onLayout(dc as Graphics.Dc) as Void {
        _w = dc.getWidth();
        _h = dc.getHeight();
    }

    function onShow() as Void {
        session.setListener(self);
    }

    function onHide() as Void {
        session.setListener(null);
    }

    function togglePage() as Void {
        page = 1 - page;
        WatchUi.requestUpdate();
    }

    //! "3 x 100 kg", "5-8 x ? kg", "max x BW".
    static function targetText(set as Dictionary, unit as String) as String {
        var rx = U.str(set, "rx");
        var load = StrengthRun.targetLoad(set);
        var lt = "?";
        if (load != null) {
            lt = load < 0 ? "BW" : Fmt.loadWithUnit(load, StrengthRun.loadUnit(set, unit), StrengthRun.implementCount(set));
        } else {
            lt = "? " + StrengthRun.loadUnit(set, unit);
        }
        return (rx == null ? "?" : rx) + " x " + lt;
    }

    static function rowText(row as Array) as String {
        var load = (row[SR_LOAD] as Number or Float).toFloat();
        var lt = load < 0 ? "BW" : Fmt.load(load) + " " + (row[SR_UNIT] as String);
        return (row[SR_REPS] as Number).format("%d") + " x " + lt + " " + (row[SR_STATUS] as String);
    }

    function onUpdate(dc as Graphics.Dc) as Void {
        dc.setColor(Theme.BACKGROUND, Theme.BACKGROUND);
        dc.clear();
        var s = session;
        var sr = s.strength as StrengthRun;
        if (page == 1) {
            _drawList(dc, sr);
            return;
        }
        var cx = _w / 2;
        var t = s.now();
        var prep = s.clock.state == CS_PREP || (s.clock.state == CS_PAUSED && s.clock.resumeState == CS_PREP);
        if (prep) {
            dc.setColor(Theme.PREP, Graphics.COLOR_TRANSPARENT);
            dc.drawText(cx, _h * 0.3, Graphics.FONT_SMALL, "GET READY",
                Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
            dc.setColor(Theme.TEXT, Graphics.COLOR_TRANSPARENT);
            dc.drawText(cx, _h * 0.5, Graphics.FONT_NUMBER_HOT,
                Fmt.countdownSeconds(s.prepMs() - s.clock.prepAt(t)).format("%d"),
                Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
            return;
        }
        var set = sr.currentSet();
        var unit = sr.defaultUnit;
        var title = set == null ? "" : U.str(set, "nm") as String;
        var idx = sr.current + 1 > sr.total() ? sr.total() : sr.current + 1;
        dc.setColor(Theme.DIM, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, _h * 0.13, Graphics.FONT_XTINY, U.str(s.part, "l") + " " + title,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        var head = (sr.buildToMax ? "ATTEMPT " : "SET ") + idx.format("%d") + "/" + sr.total().format("%d");
        if (s.isPaused()) {
            head = "PAUSED";
        }
        var left = sr.restLeftMs(s.clock.partRunAt(t));
        if (left != null) {
            _drawRest(dc, left, head, set, unit);
        } else {
            dc.setColor(s.isPaused() ? Theme.PAUSED : Theme.WORK, Graphics.COLOR_TRANSPARENT);
            dc.drawText(cx, _h * 0.23, Graphics.FONT_SMALL, head,
                Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
            if (set != null) {
                dc.setColor(Theme.TEXT, Graphics.COLOR_TRANSPARENT);
                dc.drawText(cx, _h * 0.40, Graphics.FONT_LARGE, targetText(set, unit),
                    Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
                var nt = U.str(set, "nt");
                if (nt != null) {
                    dc.setColor(Theme.DIM, Graphics.COLOR_TRANSPARENT);
                    dc.drawText(cx, _h * 0.52, Graphics.FONT_XTINY, nt,
                        Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
                }
            }
            var hint = sr.needsEntry() ? "START: enter reps/load" : "START / LAP: set done";
            dc.setColor(Theme.OPEN, Graphics.COLOR_TRANSPARENT);
            dc.drawText(cx, _h * 0.64, Graphics.FONT_XTINY, hint,
                Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        }
        var last = sr.lastLog();
        dc.setColor(Theme.DIM, Graphics.COLOR_TRANSPARENT);
        var footer = last != null ? "Last: " + rowText(last) : "Elapsed " + Fmt.clock(Fmt.countUpSeconds(s.clock.partRunAt(t)));
        if (s.message != null) {
            footer = s.message as String;
        } else if (s.recorder.failed()) {
            footer = "REC FAILED - results kept";
        }
        dc.drawText(cx, _h * 0.76, Graphics.FONT_XTINY, footer,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        var info = Activity.getActivityInfo();
        var hr = info != null ? info.currentHeartRate : null;
        dc.setColor(Theme.HEART, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, _h * 0.87, Graphics.FONT_TINY, hr != null ? hr.format("%d") + " bpm" : "-- bpm",
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
    }

    function _drawRest(dc as Graphics.Dc, left as Number, head as String, next as Dictionary?, unit as String) as Void {
        var cx = _w / 2;
        var over = left <= 0;
        dc.setColor(over ? Theme.WARN : Theme.REST, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, _h * 0.23, Graphics.FONT_SMALL, session.isPaused() ? "PAUSED" : (over ? "REST OVER" : "REST"),
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        var text = over ? Fmt.overtime(-left) : Fmt.clock(Fmt.countdownSeconds(left));
        dc.setColor(Theme.TEXT, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, _h * 0.42, Graphics.FONT_NUMBER_HOT, text,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        if (next != null) {
            dc.setColor(Theme.DIM, Graphics.COLOR_TRANSPARENT);
            dc.drawText(cx, _h * 0.62, Graphics.FONT_XTINY, "Next " + head + ": " + targetText(next, unit),
                Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        }
    }

    function _drawList(dc as Graphics.Dc, sr as StrengthRun) as Void {
        var cx = _w / 2;
        dc.setColor(Theme.OPEN, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, _h * 0.12, Graphics.FONT_SMALL, "Sets",
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        var lh = dc.getFontHeight(Graphics.FONT_XTINY);
        var y = _h * 0.2;
        var n = sr.buildToMax ? sr.logs.size() + 1 : sr.sets.size();
        var first = sr.current > 4 ? sr.current - 4 : 0;
        for (var i = first; i < n && y < _h * 0.86; i++) {
            var text = (i + 1).format("%d") + ". ";
            var color = Theme.TEXT;
            var row = _rowFor(sr, i);
            if (row != null) {
                text += rowText(row);
                color = Theme.DIM;
            } else {
                var set = i < sr.sets.size() ? sr.sets[i] as Dictionary : sr.currentSet();
                text += set == null ? "" : targetText(set, sr.defaultUnit);
                if (i == sr.current) {
                    color = Theme.WORK;
                }
            }
            dc.setColor(color, Graphics.COLOR_TRANSPARENT);
            dc.drawText(cx, y, Graphics.FONT_XTINY, text, Graphics.TEXT_JUSTIFY_CENTER);
            y += lh;
        }
    }

    function _rowFor(sr as StrengthRun, index as Number) as Array? {
        for (var i = sr.logs.size() - 1; i >= 0; i--) {
            var row = sr.logs[i] as Array;
            if ((row[SR_INDEX] as Number) == index) {
                return row;
            }
        }
        return null;
    }
}

class StrengthDelegate extends WatchUi.BehaviorDelegate {

    private var _s as Session;
    private var _view as StrengthView;

    function initialize(s as Session, view as StrengthView) {
        BehaviorDelegate.initialize();
        _s = s;
        _view = view;
    }

    //! START: set done / next set; resume when paused.
    function onSelect() as Boolean {
        if (_s.isPaused()) {
            _s.resume();
            return true;
        }
        _done();
        return true;
    }

    //! BACK (LAP) while the part runs = set done / skip rest (section 6.3).
    function onBack() as Boolean {
        if (_s.isPaused()) {
            RunMenus.openPause(_s);
            return true;
        }
        if (_s.clock.state == CS_RUN) {
            _done();
        }
        return true;
    }

    function onTap(evt as WatchUi.ClickEvent) as Boolean {
        if ((_s.settings["tch"] as Boolean) && (_s.settings["tap"] as Boolean) && _s.clock.state == CS_RUN) {
            _done();
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

    function _done() as Void {
        if (_s.clock.state != CS_RUN) {
            return;
        }
        if (!_s.logSet()) {
            var sr = _s.strength;
            if (sr != null && sr.needsEntry()) {
                SetEntry.start(_s, false);
            }
        }
        WatchUi.requestUpdate();
    }
}

//! Rep / load entry for an unresolved set, or Edit last set (REQ-WS02).
//! Reps first (when needed), then load; the set is logged with the
//! original button time so rest is not shortened by entry (REQ-WM14).
class SetEntry {

    var session as Session;
    var editing as Boolean;
    var reps as Number = 0;
    var load as Float = -1.0;
    var status as String = "done";
    var unit as String = "kg";
    private var _needLoad as Boolean = true;

    static function start(s as Session, edit as Boolean) as Void {
        var e = new SetEntry(s, edit);
        e.begin();
    }

    function initialize(s as Session, edit as Boolean) {
        session = s;
        editing = edit;
    }

    function begin() as Void {
        var sr = session.strength as StrengthRun;
        unit = sr.defaultUnit;
        if (editing) {
            var row = sr.lastLog();
            if (row == null) {
                return;
            }
            reps = row[SR_REPS] as Number;
            load = (row[SR_LOAD] as Number or Float).toFloat();
            status = row[SR_STATUS] as String;
            var u = row[SR_UNIT] as String;
            unit = u.length() > 0 ? u : unit;
            _needLoad = load >= 0;
            NumPicker.open(PickerSpec.count("Reps", 99999), reps, method(:onReps));
            return;
        }
        var set = sr.currentSet() as Dictionary;
        unit = StrengthRun.loadUnit(set, unit);
        reps = sr.prefillReps();
        load = sr.prefillLoad();
        var tl = StrengthRun.targetLoad(set);
        _needLoad = tl == null || sr.buildToMax;
        if (tl != null && tl < 0) {
            _needLoad = false;
            load = -1.0;
        }
        if (unit.length() == 0) {
            unit = sr.defaultUnit;
        }
        if (StrengthRun.targetReps(set) < 0 || sr.buildToMax) {
            NumPicker.open(PickerSpec.count("Reps", 99999), reps, method(:onReps));
        } else {
            _askLoad();
        }
    }

    function onReps(value as Number) as Void {
        reps = value;
        if (_needLoad) {
            _askLoad();
        } else {
            _finish();
        }
    }

    function _askLoad() as Void {
        var start = load < 0 ? 0 : (load * 100).toNumber();
        NumPicker.open(PickerSpec.weight("Load", unit), start, method(:onLoad));
    }

    function onLoad(value as Number) as Void {
        load = value / 100.0;
        _finish();
    }

    function _finish() as Void {
        if (editing) {
            session.editLastSet(reps, load, status);
        } else {
            session.logSetWith(reps, load, "done");
        }
        WatchUi.requestUpdate();
    }
}
