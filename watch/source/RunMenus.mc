//
// RunMenus.mc
//
// Menus and screens around a running session:
//   * pause menu (REQ-WM12, WS04, WM09): Resume / Finish / Stop early /
//     Undo / tracking mode / strength set actions / Discard session (two
//     presses);
//   * exit dialog (REQ-WN07): Finish incomplete / Resume / Discard session;
//   * result screen (REQ-WE06): Save first (fewest presses, REQ-TS12),
//     editable score, level toggle, loads for loaded items, Discard score;
//   * transition screen between parts with the part summary and the
//     inter-part rest (REQ-WN02, WO04);
//   * Summary text for the session summary (REQ-WO04).
//
import Toybox.Graphics;
import Toybox.Lang;
import Toybox.WatchUi;

module RunMenus {

    function openPause(s as Session) as Void {
        var menu = new WatchUi.Menu2({:title => "Paused"});
        menu.addItem(new WatchUi.MenuItem("Resume", null, :resume, null));
        var m = s.metcon;
        if (m != null) {
            var forTime = U.eq(m.mode, "for_time") || m.isOpen;
            menu.addItem(new WatchUi.MenuItem("Finish", forTime ? "Done - save time" : "End part now", :finish, null));
            if (forTime) {
                menu.addItem(new WatchUi.MenuItem("Stop early", "Not completed", :early, null));
            }
            if (m.countsManually()) {
                menu.addItem(new WatchUi.MenuItem("Undo last", Fmt.rounds(m.rounds), :undo, null));
            }
            if (m.moves.size() > 0 && m.countsManually()) {
                menu.addItem(new WatchUi.MenuItem("Tracking", m.movementMode ? "Movement (LAP=next)" : "Round (LAP=+1)",
                    :track, null));
            }
        }
        var sr = s.strength;
        if (sr != null) {
            if (sr.lastLog() != null) {
                menu.addItem(new WatchUi.MenuItem("Edit last set", StrengthView.rowText(sr.lastLog() as Array), :edit, null));
                menu.addItem(new WatchUi.MenuItem("Status last set", (sr.lastLog() as Array)[SR_STATUS] as String,
                    :status, null));
                menu.addItem(new WatchUi.MenuItem("Undo last set", null, :undoSet, null));
            }
            menu.addItem(new WatchUi.MenuItem("Add set", sr.canAddSet() ? "Copy of last" : "Limit 30 reached", :extra, null));
            menu.addItem(new WatchUi.MenuItem("Skip set", null, :skip, null));
            menu.addItem(new WatchUi.MenuItem("End exercise", null, :finish, null));
        }
        if (s.frozen) {
            menu.addItem(new WatchUi.MenuItem("Retry storage", s.message, :retry, null));
        }
        menu.addItem(new WatchUi.MenuItem("Finish session", "Save incomplete", :incomplete, null));
        menu.addItem(new WatchUi.MenuItem("Discard session", "Press twice", :discard, null));
        WatchUi.pushView(menu, new PauseDelegate(s), WatchUi.SLIDE_UP);
    }

    //! Exit dialog for BACK outside a running timer (REQ-WN07).
    function openExit(s as Session) as Void {
        var menu = new WatchUi.Menu2({:title => "Leave workout?"});
        menu.addItem(new WatchUi.MenuItem("Finish incomplete", "Save what was done", :incomplete, null));
        menu.addItem(new WatchUi.MenuItem("Resume", null, :resume, null));
        menu.addItem(new WatchUi.MenuItem("Discard session", "Press twice", :discard, null));
        WatchUi.pushView(menu, new PauseDelegate(s), WatchUi.SLIDE_UP);
    }
}

class PauseDelegate extends WatchUi.Menu2InputDelegate {

    private var _s as Session;
    private var _discardArmed as Boolean = false;

    function initialize(s as Session) {
        Menu2InputDelegate.initialize();
        _s = s;
    }

    function onSelect(item as WatchUi.MenuItem) as Void {
        var id = item.getId();
        if (id == :resume) {
            WatchUi.popView(WatchUi.SLIDE_DOWN);
            _s.resume();
        } else if (id == :finish || id == :early) {
            WatchUi.popView(WatchUi.SLIDE_IMMEDIATE);
            _s.finishPart(id == :early);
        } else if (id == :undo) {
            _s.undoLap();
            var m = _s.metcon;
            item.setSubLabel(m == null ? "" : Fmt.rounds(m.rounds));
            WatchUi.requestUpdate();
        } else if (id == :track) {
            _s.toggleTracking();
            var m = _s.metcon as MetconRun;
            item.setSubLabel(m.movementMode ? "Movement (LAP=next)" : "Round (LAP=+1)");
            WatchUi.requestUpdate();
        } else if (id == :edit) {
            WatchUi.popView(WatchUi.SLIDE_IMMEDIATE);
            SetEntry.start(_s, true);
        } else if (id == :status) {
            var sr = _s.strength as StrengthRun;
            var row = sr.lastLog() as Array;
            var next = StrengthRun.nextStatus(row[SR_STATUS] as String);
            _s.editLastSet(row[SR_REPS] as Number, (row[SR_LOAD] as Number or Float).toFloat(), next);
            item.setSubLabel(next);
            WatchUi.requestUpdate();
        } else if (id == :undoSet) {
            _s.undoSet();
            WatchUi.popView(WatchUi.SLIDE_DOWN);
        } else if (id == :extra) {
            item.setSubLabel(_s.extraSet() ? "Added" : "Limit 30 reached");
            WatchUi.requestUpdate();
        } else if (id == :skip) {
            WatchUi.popView(WatchUi.SLIDE_IMMEDIATE);
            _s.skipSet();
        } else if (id == :retry) {
            item.setSubLabel(_s.retryStorage() ? "OK - resume" : "Still full");
            WatchUi.requestUpdate();
        } else if (id == :incomplete) {
            WatchUi.popView(WatchUi.SLIDE_IMMEDIATE);
            _s.finishIncomplete();
        } else if (id == :discard) {
            // Double confirmation for a destructive action (REQ-WN06/WM12).
            if (!_discardArmed) {
                _discardArmed = true;
                item.setSubLabel("Press again to discard");
                WatchUi.requestUpdate();
                return;
            }
            _s.discardSession();
            WatchUi.popView(WatchUi.SLIDE_IMMEDIATE);
            WatchUi.popView(WatchUi.SLIDE_DOWN);
        }
    }
}

//! Result entry after each part (REQ-WE05/WE06).
class ResultMenu extends WatchUi.Menu2 {

    var session as Session;

    function initialize(s as Session) {
        session = s;
        var o = s.outcome as PartOutcome;
        Menu2.initialize({:title => U.str(s.part, "l") + " result"});
        addItem(new WatchUi.MenuItem("Save", _statusLabel(o.status), :save, null));
        if (!U.eq(U.str(o.score, "ty"), "none")) {
            addItem(new WatchUi.MenuItem("Score", Score.text(o.score), :score, null));
        }
        var lvs = U.arr(s.part, "lvs");
        if (o.level != null || lvs != null) {
            addItem(new WatchUi.MenuItem("Level", o.level == null ? "-" : o.level as String, :level, null));
        }
        var ld = o.loads;
        var items = U.arr(s.part, "ld");
        if (items != null) {
            for (var i = 0; i < items.size(); i++) {
                var it = items[i] as Dictionary;
                addItem(new WatchUi.MenuItem(U.str(it, "nm") as String, _loadText(ld, U.str(it, "id") as String), i, null));
            }
        }
        addItem(new WatchUi.MenuItem("Time", Fmt.clock(Fmt.countUpSeconds(o.wkMs)) + " work, "
            + Fmt.clock(Fmt.countUpSeconds(o.actMs)) + " active", :none, null));
        addItem(new WatchUi.MenuItem(U.eq(U.str(o.score, "ty"), "none") ? "Discard result" : "Discard score", "Press twice", :discard, null));
    }

    static function _statusLabel(st as String) as String {
        if (st.equals("capped")) {
            return "Capped";
        }
        if (st.equals("stopped_early")) {
            return "Stopped early";
        }
        if (st.equals("interrupted")) {
            return "Incomplete";
        }
        return "Completed";
    }

    static function _loadText(ld as Array?, id as String) as String {
        if (ld != null) {
            for (var i = 0; i < ld.size(); i++) {
                var row = ld[i] as Array;
                if (U.eq(row[0] as String, id)) {
                    return Fmt.load(U.asReal(row[1]) as Float) + " " + (row[2] as String);
                }
            }
        }
        return "enter load";
    }

    function setSub(id, text as String) as Void {
        var i = findItemById(id);
        if (i >= 0) {
            var item = getItem(i);
            if (item != null) {
                item.setSubLabel(text);
            }
        }
        WatchUi.requestUpdate();
    }
}

class ResultDelegate extends WatchUi.Menu2InputDelegate {

    private var _s as Session;
    private var _menu as ResultMenu;
    private var _discardArmed as Boolean = false;
    private var _loadIndex as Number = -1;
    private var _rounds as Number = 0;

    function initialize(s as Session, menu as ResultMenu) {
        Menu2InputDelegate.initialize();
        _s = s;
        _menu = menu;
    }

    function _o() as PartOutcome {
        return _s.outcome as PartOutcome;
    }

    function onSelect(item as WatchUi.MenuItem) as Void {
        var id = item.getId();
        if (id == :save) {
            if (!_s.savePart()) {
                item.setSubLabel(_s.message == null ? "Not saved" : _s.message as String);
                WatchUi.requestUpdate();
            }
        } else if (id == :score) {
            _editScore();
        } else if (id == :level) {
            _cycleLevel();
        } else if (id == :discard) {
            if (!_discardArmed) {
                _discardArmed = true;
                item.setSubLabel("Press again to discard");
                WatchUi.requestUpdate();
                return;
            }
            _s.discardPart();
        } else if (id instanceof Number) {
            _editLoad(id);
        }
    }

    //! BACK on the result screen asks how to leave (REQ-WN07).
    function onBack() as Void {
        RunMenus.openExit(_s);
    }

    function _cycleLevel() as Void {
        var lvs = U.arr(_s.part, "lvs");
        if (lvs == null || lvs.size() == 0) {
            return;
        }
        var o = _o();
        var next = 0;
        for (var i = 0; i < lvs.size(); i++) {
            if (U.eq(lvs[i] as String, o.level)) {
                next = (i + 1) % lvs.size();
            }
        }
        o.level = lvs[next] as String;
        _s.saveDraft();
        _menu.setSub(:level, o.level as String);
    }

    //! Opens the picker(s) for the score type (REQ-WE03/04/05).
    function _editScore() as Void {
        var sc = _o().score;
        var ty = U.str(sc, "ty") as String;
        if (ty.equals("time")) {
            var s = U.num(sc, "s");
            NumPicker.open(PickerSpec.time("Time"), s == null ? Fmt.countUpSeconds(_o().wkMs) : s, method(:onTime));
        } else if (ty.equals("rounds_reps")) {
            var r = U.num(sc, "r");
            NumPicker.open(PickerSpec.count("Rounds", 9999), r == null ? 0 : r, method(:onRounds));
        } else if (ty.equals("reps") || ty.equals("calories")) {
            var n = U.num(sc, "n");
            var m = _s.metcon;
            var start = n != null ? n : (m != null && m.completedRoundReps() >= 0 ? m.completedRoundReps() : 0);
            NumPicker.open(PickerSpec.count(ty.equals("reps") ? "Reps" : "Calories", 99999), start, method(:onCount));
        } else if (ty.equals("load")) {
            var v = U.real(sc, "v");
            var u = U.str(sc, "u");
            var unit = u == null ? Settings.unit() : u as String;
            NumPicker.open(PickerSpec.weight("Load", unit), v == null ? 0 : (v * 100).toNumber(), method(:onLoadScore));
        } else if (ty.equals("distance")) {
            var u = U.str(sc, "u");
            var unit = u == null ? "m" : u as String;
            var spec = PickerSpec.distance("Distance", unit);
            var v = U.real(sc, "v");
            var scale = spec.decimals == 3 ? 1000 : 1;
            NumPicker.open(spec, v == null ? 0 : (v * scale).toNumber(), method(:onDistance));
        } else if (ty.equals("minutes_reps")) {
            var i = U.num(sc, "i");
            NumPicker.open(PickerSpec.count("Intervals", 999), i == null ? 0 : i, method(:onIntervals));
        } else if (ty.equals("completion")) {
            Score.setValue(sc, "ok", !U.flag(sc, "ok", false), false);
            _changed();
        } else if (ty.equals("text")) {
            // No keyboard on the watch: text scores are entered on the
            // phone and the result is marked pending (REQ-WE03).
            _menu.setSub(:score, "Enter on phone");
        }
    }

    function _changed() as Void {
        _s.saveDraft();
        _menu.setSub(:score, Score.text(_o().score));
    }

    function onTime(v as Number) as Void {
        var cap = _s.prog != null ? (_s.prog as Program).capMs / 1000 : 0;
        // REQ-WE04: a completed time above the cap is impossible.
        if (!Score.timeAllowed(v, cap)) {
            _menu.setSub(:score, "Over cap - use capped");
            return;
        }
        Score.setValue(_o().score, "s", v, true);
        _changed();
    }

    function onRounds(v as Number) as Void {
        _rounds = v;
        var m = _s.metcon;
        var max = m != null && m.maxExtraReps() >= 0 && v == m.rounds ? m.maxExtraReps() : 99999;
        var x = U.num(_o().score, "x");
        NumPicker.open(PickerSpec.count("Extra reps", max), x == null ? 0 : x, method(:onExtra));
    }

    function onExtra(v as Number) as Void {
        Score.setValue(_o().score, "r", _rounds, true);
        Score.setValue(_o().score, "x", v, true);
        _changed();
    }

    function onCount(v as Number) as Void {
        Score.setValue(_o().score, "n", v, U.num(_o().score, "n") != null);
        _changed();
    }

    function onLoadScore(v as Number) as Void {
        var sc = _o().score;
        if (!sc.hasKey("u")) {
            sc.put("u", Settings.unit());
        }
        Score.setValue(sc, "v", _num(v / 100.0), sc.hasKey("v"));
        _changed();
    }

    function onDistance(v as Number) as Void {
        var sc = _o().score;
        var unit = U.str(sc, "u");
        var km = U.eq(unit, "km") || U.eq(unit, "mi");
        Score.setValue(sc, "v", km ? _num(v / 1000.0) : v, false);
        _changed();
    }

    function onIntervals(v as Number) as Void {
        _rounds = v;
        NumPicker.open(PickerSpec.count("Extra reps", 99999), 0, method(:onIntervalReps));
    }

    function onIntervalReps(v as Number) as Void {
        Score.setValue(_o().score, "i", _rounds, false);
        Score.setValue(_o().score, "x", v, false);
        _changed();
    }

    function _editLoad(index as Number) as Void {
        _loadIndex = index;
        var it = (U.arr(_s.part, "ld") as Array)[index] as Dictionary;
        var v = U.real(it, "v");
        NumPicker.open(PickerSpec.weight(U.str(it, "nm") as String, U.str(it, "u") as String),
            v == null ? 0 : (v * 100).toNumber(), method(:onItemLoad));
    }

    //! Actual load of a loaded metcon item (REQ-WE06).
    function onItemLoad(v as Number) as Void {
        var it = (U.arr(_s.part, "ld") as Array)[_loadIndex] as Dictionary;
        var id = U.str(it, "id") as String;
        var o = _o();
        var ld = o.loads == null ? [] : o.loads as Array;
        var c = U.num(it, "c");
        var row = [id, _num(v / 100.0), U.str(it, "u"), c == null ? 1 : c];
        var replaced = false;
        for (var i = 0; i < ld.size(); i++) {
            if (U.eq((ld[i] as Array)[0] as String, id)) {
                ld[i] = row;
                replaced = true;
            }
        }
        if (!replaced) {
            ld.add(row);
        }
        o.loads = ld;
        _s.saveDraft();
        _menu.setSub(_loadIndex, Fmt.load(v / 100.0) + " " + U.str(it, "u"));
    }

    function _num(f as Float) as Number or Float {
        return f == f.toNumber().toFloat() ? f.toNumber() : f;
    }
}

//! Between parts: summary of the last part, prescribed rest countdown,
//! then "Next: B - AMRAP 12 - START when ready" (REQ-WN02, WO04).
class TransitionView extends WatchUi.View {

    var session as Session;

    function initialize(s as Session) {
        View.initialize();
        session = s;
    }

    function onShow() as Void {
        session.setListener(self);
    }

    function onUpdate(dc as Graphics.Dc) as Void {
        var w = dc.getWidth();
        var h = dc.getHeight();
        var cx = w / 2;
        var s = session;
        dc.setColor(Theme.BACKGROUND, Theme.BACKGROUND);
        dc.clear();
        var last = Summary.lastPartLine(s);
        dc.setColor(Theme.DIM, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, h * 0.17, Graphics.FONT_XTINY, last, Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        var resting = s.clock.state == CS_PART_REST || (s.isPaused() && s.clock.resumeState == CS_PART_REST);
        if (resting) {
            var ra = U.num(s.partAt(s.cur - 1 < 0 ? 0 : s.cur - 1), "ra");
            var left = (ra == null ? 0 : ra * 1000) - s.clock.inStateAt(s.now());
            if (s.isPaused()) {
                left = 0;
            }
            dc.setColor(Theme.REST, Graphics.COLOR_TRANSPARENT);
            dc.drawText(cx, h * 0.30, Graphics.FONT_SMALL, s.isPaused() ? "PAUSED" : "REST",
                Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
            dc.setColor(Theme.TEXT, Graphics.COLOR_TRANSPARENT);
            dc.drawText(cx, h * 0.45, Graphics.FONT_NUMBER_MEDIUM, Fmt.clock(Fmt.countdownSeconds(left)),
                Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        } else {
            dc.setColor(Theme.WORK, Graphics.COLOR_TRANSPARENT);
            dc.drawText(cx, h * 0.38, Graphics.FONT_MEDIUM, "Next:",
                Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        }
        var p = s.part;
        dc.setColor(Theme.TEXT, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, h * 0.60, Graphics.FONT_SMALL, U.str(p, "l") + " - " + U.str(p, "n"),
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        dc.setColor(Theme.OPEN, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, h * 0.75, Graphics.FONT_XTINY, "START when ready",
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
    }
}

class TransitionDelegate extends WatchUi.BehaviorDelegate {

    private var _s as Session;

    function initialize(s as Session) {
        BehaviorDelegate.initialize();
        _s = s;
    }

    function onSelect() as Boolean {
        if (_s.isPaused()) {
            _s.resume();
        } else {
            _s.startNextPart();
        }
        return true;
    }

    function onBack() as Boolean {
        RunMenus.openExit(_s);
        return true;
    }

    function onMenu() as Boolean {
        if (_s.clock.state == CS_PART_REST) {
            _s.pause();
        }
        RunMenus.openExit(_s);
        return true;
    }
}

//! Summary text (REQ-WO04): score, time, HR avg / max, calories.
module Summary {

    function lastPartLine(s as Session) as String {
        if (s.entries.size() == 0) {
            return "";
        }
        var e = s.entries[s.entries.size() - 1] as Array;
        var rid = e[1] as String;
        if (rid.length() == 0) {
            return "Previous part: skipped";
        }
        var r = History.object(s.kv, rid);
        return "Saved: " + _resultLine(r);
    }

    function _resultLine(r as Dictionary?) as String {
        if (r == null) {
            return "-";
        }
        var sc = U.dict(r, "sc");
        var text = sc == null ? "-" : Score.text(sc);
        var hr = U.arr(r, "hr");
        var line = text + "  " + Fmt.clock(U.num(r, "wk") as Number);
        if (hr != null) {
            line += "  HR " + (hr[0] as Number).format("%d") + "/" + (hr[1] as Number).format("%d");
        }
        return line;
    }

    function sessionLines(s as Session) as Array {
        var lines = [];
        var parts = s.parts();
        for (var i = 0; i < s.entries.size(); i++) {
            var e = s.entries[i] as Array;
            var label = "";
            for (var p = 0; p < parts.size(); p++) {
                if (U.eq(U.str(parts[p] as Dictionary, "id"), e[0] as String)) {
                    label = U.str(parts[p] as Dictionary, "l") as String;
                }
            }
            var rid = e[1] as String;
            lines.add(label + ": " + (rid.length() == 0 ? "skipped" : _resultLine(History.object(s.kv, rid))));
        }
        var sum = s.summary;
        if (sum != null) {
            lines.add("Wall " + Fmt.clock(U.num(sum, "el") as Number) + "  active " + Fmt.clock(U.num(sum, "act") as Number));
            var hr = U.arr(sum, "hr");
            lines.add(hr == null ? "HR --" : "HR avg " + (hr[0] as Number).format("%d") + " max " + (hr[1] as Number).format("%d"));
            var cal = U.num(sum, "cal");
            lines.add(cal == null ? "Calories --" : cal.format("%d") + " kcal");
            lines.add("Garmin activity: " + U.str(sum, "fit"));
        }
        return lines;
    }
}
