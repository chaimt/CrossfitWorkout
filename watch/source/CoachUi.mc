//
// CoachUi.mc
//
// Navigation glue: starting a session (admission first, REQ-WO05) and
// showing the view that matches the session's stage. Stage views replace
// each other with switchToView, so the stack stays Home -> Workouts ->
// Plan -> [Part] -> stage, and leaving the summary returns to the plan.
// Also the shared TextView used for messages, details and summaries.
//
import Toybox.Graphics;
import Toybox.Lang;
import Toybox.WatchUi;

module CoachUi {

    //! Starts `selection` (part indices) of `plan`. False with a message
    //! view when admission or storage refused it.
    function start(plan as Dictionary, selection as Array<Number>, edits as Dictionary,
            restartOf as String?) as Boolean {
        var app = CoachApp.instance();
        if (app.session != null) {
            showMessage("Busy", ["A session is open."]);
            return false;
        }
        var reason = Session.admit(app.kv, plan, selection.size());
        if (reason != null) {
            showMessage("Can't start", [reason, "Sync results or free", "acknowledged history."]);
            return false;
        }
        var s = new Session(app.kv, plan, selection, edits, restartOf);
        if (!s.begin()) {
            showMessage("Can't start", [s.message == null ? "Storage error" : s.message as String]);
            return false;
        }
        app.session = s;
        var v = _stageView(s);
        WatchUi.pushView(v[0], v[1], WatchUi.SLIDE_LEFT);
        return true;
    }

    //! Replaces the current stage view with the one for `s.stage()`.
    function showStage(s as Session) as Void {
        var v = _stageView(s);
        WatchUi.switchToView(v[0], v[1], WatchUi.SLIDE_IMMEDIATE);
    }

    function _stageView(s as Session) as Array {
        var st = s.stage();
        if (st == STAGE_RESULT) {
            var menu = new ResultMenu(s);
            return [menu, new ResultDelegate(s, menu)];
        }
        if (st == STAGE_TRANSITION) {
            var tv = new TransitionView(s);
            return [tv, new TransitionDelegate(s)];
        }
        if (st == STAGE_SUMMARY) {
            if (!s.finalized) {
                var pending = new TextView("Save pending",
                    ["Storage full", "Sync results, then retry"], "START to retry");
                return [pending, new FinalizationDelegate(s, pending)];
            }
            var lines = Summary.sessionLines(s);
            var sv = new TextView("Session done", lines, "START to close");
            return [sv, new TextDelegate(sv, null)];
        }
        if (s.strength != null) {
            var v = new StrengthView(s);
            return [v, new StrengthDelegate(s, v)];
        }
        var mv = new MetconView(s);
        return [mv, new MetconDelegate(s, mv)];
    }

    function showMessage(title as String, lines as Array) as Void {
        var v = new TextView(title, lines, "BACK to return");
        WatchUi.pushView(v, new TextDelegate(v, null), WatchUi.SLIDE_UP);
    }

    //! Pushes a menu-style item list as non-actionable text.
    function showLines(title as String, lines as Array) as Void {
        var v = new TextView(title, lines, null);
        WatchUi.pushView(v, new TextDelegate(v, null), WatchUi.SLIDE_LEFT);
    }
}

//! Keep failed finalization reachable until its durable writes succeed.
class FinalizationDelegate extends TextDelegate {
    private var _session as Session;

    function initialize(s as Session, view as TextView) {
        TextDelegate.initialize(view, null);
        _session = s;
    }

    function onSelect() as Boolean {
        _session.retryStorage();
        return true;
    }

    function onBack() as Boolean {
        // Leaving the view would strand the open session behind the Busy check.
        return true;
    }
}

//! Scrollable title + lines screen (summaries, details, messages).
class TextView extends WatchUi.View {

    var title as String;
    var lines as Array;
    var footer as String?;
    var top as Number = 0;
    private var _rows as Number = 6;

    function initialize(t as String, l as Array, f as String?) {
        View.initialize();
        title = t;
        lines = l;
        footer = f;
    }

    function onLayout(dc as Graphics.Dc) as Void {
        var h = dc.getHeight();
        _rows = ((h * 0.56) / dc.getFontHeight(Graphics.FONT_TINY)).toNumber();
        if (_rows < 3) {
            _rows = 3;
        }
    }

    function scroll(delta as Number) as Void {
        var max = lines.size() - _rows;
        top = U.clamp(top + delta, 0, max < 0 ? 0 : max);
        WatchUi.requestUpdate();
    }

    function onUpdate(dc as Graphics.Dc) as Void {
        var w = dc.getWidth();
        var h = dc.getHeight();
        dc.setColor(Theme.BACKGROUND, Theme.BACKGROUND);
        dc.clear();
        dc.setColor(Theme.OPEN, Graphics.COLOR_TRANSPARENT);
        dc.drawText(w / 2, h * 0.14, Graphics.FONT_SMALL, title,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        var lh = dc.getFontHeight(Graphics.FONT_TINY);
        var y = h * 0.24;
        dc.setColor(Theme.TEXT, Graphics.COLOR_TRANSPARENT);
        for (var i = top; i < lines.size() && i < top + _rows; i++) {
            dc.drawText(w / 2, y, Graphics.FONT_TINY, lines[i] as String, Graphics.TEXT_JUSTIFY_CENTER);
            y += lh;
        }
        if (footer != null) {
            dc.setColor(Theme.DIM, Graphics.COLOR_TRANSPARENT);
            dc.drawText(w / 2, h * 0.88, Graphics.FONT_XTINY, footer as String,
                Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        }
    }
}

class TextDelegate extends WatchUi.BehaviorDelegate {

    private var _view as TextView;
    private var _onSelect as Method? = null;

    function initialize(view as TextView, onSelect as Method?) {
        BehaviorDelegate.initialize();
        _view = view;
        _onSelect = onSelect;
    }

    function onNextPage() as Boolean {
        _view.scroll(1);
        return true;
    }

    function onPreviousPage() as Boolean {
        _view.scroll(-1);
        return true;
    }

    function onSelect() as Boolean {
        if (_onSelect != null) {
            (_onSelect as Method).invoke();
        } else {
            WatchUi.popView(WatchUi.SLIDE_RIGHT);
        }
        return true;
    }
}
