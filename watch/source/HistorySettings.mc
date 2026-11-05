//
// HistorySettings.mc
//
// On-watch history "as recorded" (REQ-WO03, REQ-SY12) with delivery state,
// and the watch settings menu with the local override mask and "Reset to
// phone defaults" (REQ-WG01, REQ-WR04).
//
import Toybox.Lang;
import Toybox.WatchUi;

class HistoryMenu extends WatchUi.Menu2 {

    function initialize() {
        Menu2.initialize({:title => "History"});
        var rows = History.rows(CoachApp.instance().kv);
        for (var i = 0; i < rows.size(); i++) {
            var r = rows[i] as Array;
            var kind = r[HX_KIND] as String;
            var label = kind.equals(History.KIND_SESSION) ? "Session " + (r[HX_TITLE] as String)
                : (r[HX_LABEL] as String) + " " + (r[HX_TITLE] as String);
            var sub = (r[HX_SCORE] as String) + " - " + ((r[HX_SENT] as Boolean) ? "sent" : "pending");
            addItem(new WatchUi.MenuItem(label, sub, r[HX_ID] as String, null));
        }
        if (rows.size() == 0) {
            addItem(new WatchUi.MenuItem("No results yet", null, :none, null));
        }
    }
}

class HistoryDelegate extends WatchUi.Menu2InputDelegate {

    function initialize() {
        Menu2InputDelegate.initialize();
    }

    function onSelect(item as WatchUi.MenuItem) as Void {
        var id = item.getId();
        if (id instanceof String) {
            HistoryDetail.open(id);
        }
    }
}

module HistoryDetail {

    function open(id as String) as Void {
        var kv = CoachApp.instance().kv;
        var o = History.object(kv, id);
        if (o == null) {
            return;
        }
        CoachUi.showLines(History.isDelivered(kv, id) ? "Sent" : "Pending", lines(o));
    }

    //! Human-readable lines of a stored Result or SessionSummary.
    function lines(o as Dictionary) as Array {
        var out = [];
        var t0 = U.num(o, "t0");
        if (t0 != null) {
            out.add(Fmt.dateTime(t0));
        }
        var st = U.str(o, "st");
        if (st != null) {
            out.add("Status: " + st);
        }
        var sc = U.dict(o, "sc");
        if (sc != null) {
            out.add("Score: " + Score.text(sc) + (U.flag(sc, "man", false) ? " (manual)" : ""));
        }
        var lv = U.str(o, "lv");
        if (lv != null) {
            out.add("Level: " + lv);
        }
        var el = U.num(o, "el");
        var act = U.num(o, "act");
        if (el != null && act != null) {
            out.add("Time " + Fmt.clock(el) + ", active " + Fmt.clock(act));
        }
        var wk = U.num(o, "wk");
        if (wk != null) {
            out.add("Score time " + Fmt.clock(wk));
        }
        var hr = U.arr(o, "hr");
        out.add(hr == null ? "HR --" : "HR " + (hr[0] as Number).format("%d") + " / " + (hr[1] as Number).format("%d"));
        var sets = U.arr(o, "sets");
        if (sets != null) {
            for (var i = 0; i < sets.size(); i++) {
                out.add((i + 1).format("%d") + ". " + StrengthView.rowText(sets[i] as Array));
            }
        }
        var ev = U.arr(o, "ev");
        if (ev != null) {
            out.add(ev.size().format("%d") + " progress events");
        }
        var e = U.arr(o, "e");
        if (e != null) {
            for (var i = 0; i < e.size(); i++) {
                out.add("Part " + (i + 1).format("%d") + ": " + ((e[i] as Array)[2] as String));
            }
        }
        var fit = U.str(o, "fit");
        if (fit != null) {
            out.add("Activity: " + fit);
        }
        return out;
    }
}

class SettingsMenu extends WatchUi.Menu2 {

    function initialize() {
        Menu2.initialize({:title => "Settings"});
        addItem(new WatchUi.MenuItem("Countdown", prepLabel(Settings.prepSeconds()), :prep, null));
        addItem(new WatchUi.ToggleMenuItem("Sounds", null, "snd", Settings.sound(), null));
        addItem(new WatchUi.ToggleMenuItem("Vibration", null, "vib", Settings.vibrate(), null));
        addItem(new WatchUi.ToggleMenuItem("Record activity", {:enabled => "Garmin Connect", :disabled => "Off"},
            "rec", Settings.record(), null));
        addItem(new WatchUi.ToggleMenuItem("Touch", null, "tch", Settings.touch(), null));
        addItem(new WatchUi.ToggleMenuItem("Tap to log", null, "tap", Settings.tapToLog(), null));
        addItem(new WatchUi.MenuItem("Weight unit", Settings.unit(), :unit, null));
        addItem(new WatchUi.MenuItem("Weight step", _stepLabel(false), :ws, null));
        addItem(new WatchUi.MenuItem("Fine step", _stepLabel(true), :wsf, null));
        addItem(new WatchUi.MenuItem("Reset to phone defaults", null, :reset, null));
    }

    static function prepLabel(seconds as Number) as String {
        return seconds == 0 ? "Off" : seconds.format("%d") + " s";
    }

    static function _stepLabel(fine as Boolean) as String {
        var u = Settings.unit();
        return Fmt.load(Settings.weightStep(u, fine)) + " " + u;
    }
}

class SettingsDelegate extends WatchUi.Menu2InputDelegate {

    const STEPS = [1.0, 2.0, 2.5, 5.0, 10.0] as Array<Float>;
    const FINE = [0.25, 0.5, 1.0] as Array<Float>;

    function initialize() {
        Menu2InputDelegate.initialize();
    }

    function onSelect(item as WatchUi.MenuItem) as Void {
        var id = item.getId();
        if (id == :prep) {
            var next = Settings.nextPrep(Settings.prepSeconds());
            Settings.setLocal("prep", next);
            item.setSubLabel(SettingsMenu.prepLabel(next));
        } else if (id == :unit) {
            var u = Settings.unit().equals("kg") ? "lb" : "kg";
            Settings.setLocal("u", u);
            item.setSubLabel(u);
        } else if (id == :ws || id == :wsf) {
            var fine = id == :wsf;
            var list = fine ? FINE : STEPS;
            var cur = Settings.weightStep(Settings.unit(), fine);
            var next = list[0];
            for (var i = 0; i < list.size(); i++) {
                if (list[i] > cur) {
                    next = list[i];
                    break;
                }
            }
            Settings.setLocal(fine ? "wsf" : "ws", next);
            item.setSubLabel(Fmt.load(next) + " " + Settings.unit());
        } else if (id == :reset) {
            Settings.resetToPhone();
            WatchUi.popView(WatchUi.SLIDE_RIGHT);
            WatchUi.pushView(new SettingsMenu(), new SettingsDelegate(), WatchUi.SLIDE_IMMEDIATE);
            return;
        } else if (item instanceof WatchUi.ToggleMenuItem && id instanceof String) {
            // Local override per key until reset (REQ-WG01).
            Settings.setLocal(id, item.isEnabled());
        }
        WatchUi.requestUpdate();
    }
}
