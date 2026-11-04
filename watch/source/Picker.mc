//
// Picker.mc
//
// Full-screen numeric entry in the style of WOD Timer's ValuePicker, used
// for weight, reps / calories, distance, time and timer edits (REQ-WE01,
// WE03, WE04, WE07). The native NumberPicker is deprecated and absent on
// every target (Appendix I.1).
//
//   UP / DOWN, swipe, upper / lower third tap  change by the step
//   hold DOWN, or rapid UP / DOWN presses      accelerate (x10)
//   MENU (hold UP) / touch hold                toggle fine <-> coarse step
//
// On the fr965 a held UP *is* the MENU key (simulator.json "isHold"), so
// upward acceleration uses rapid presses: six presses in the same direction
// less than 400 ms apart switch to x10 steps.
//   START / middle tap                         accept;  BACK  cancel
//
// Values are scaled integers (weight in 1/100 units, km in 1/1000) so all
// stepping is exact. The range is clamped - never wrapped - and reaching a
// bound is shown on screen, not applied silently (REQ-WE03).
//
import Toybox.Graphics;
import Toybox.Lang;
import Toybox.System;
import Toybox.Timer;
import Toybox.WatchUi;

// Picker value kinds.
enum {
    PK_COUNT = 0,
    PK_TIME = 1,
    PK_WEIGHT = 2,
    PK_DIST = 3
}

class PickerSpec {
    var title as String;
    var kind as Number;
    var min as Number;
    var max as Number;
    var step as Number;
    var alt as Number;
    var decimals as Number;
    var unit as String;
    var zeroText as String? = null;

    function initialize(t as String, k as Number, lo as Number, hi as Number, s as Number, a as Number,
            dec as Number, u as String) {
        title = t;
        kind = k;
        min = lo;
        max = hi;
        step = s;
        alt = a;
        decimals = dec;
        unit = u;
    }

    function format(v as Number) as String {
        if (v == 0 && zeroText != null) {
            return zeroText as String;
        }
        if (kind == PK_TIME) {
            return Fmt.clock(v);
        }
        return Fmt.scaled(v, decimals);
    }

    //! Weight picker (REQ-WE01): 0-500 kg / 0-1100 lb, coarse + fine step.
    static function weight(title as String, unit as String) as PickerSpec {
        var coarse = Settings.weightStep(unit, false);
        var fine = Settings.weightStep(unit, true);
        var max = unit.equals("lb") ? 110000 : 50000;
        return new PickerSpec(title, PK_WEIGHT, 0, max, (coarse * 100).toNumber(), (fine * 100).toNumber(), 2, unit);
    }

    //! Reps / calories (REQ-WE03): 0-99,999.
    static function count(title as String, max as Number) as PickerSpec {
        return new PickerSpec(title, PK_COUNT, 0, max, 1, 10, 0, "");
    }

    //! Time (REQ-WE04): 0-23:59:59, 1 s with optional 10 s step.
    static function time(title as String) as PickerSpec {
        return new PickerSpec(title, PK_TIME, 0, 86399, 1, 10, 0, "");
    }

    //! Distance (REQ-WE03): m/ft/yd 0-999,999 step 1; km/mi step 0.001.
    static function distance(title as String, unit as String) as PickerSpec {
        if (unit.equals("km") || unit.equals("mi")) {
            return new PickerSpec(title, PK_DIST, 0, 999999, 1, 100, 3, unit);
        }
        return new PickerSpec(title, PK_DIST, 0, 999999, 1, 10, 0, unit);
    }
}

module NumPicker {

    function open(spec as PickerSpec, value as Number, onAccept as Method) as Void {
        var v = new NumPickerView(spec, value);
        WatchUi.pushView(v, new NumPickerDelegate(v, onAccept), WatchUi.SLIDE_UP);
    }
}

class NumPickerView extends WatchUi.View {

    var spec as PickerSpec;
    var value as Number;
    var useAlt as Boolean = false;
    var atBound as Number = 0;
    private var _w as Number = 0;
    private var _h as Number = 0;
    private var _up as Array = [];
    private var _down as Array = [];

    function initialize(s as PickerSpec, v as Number) {
        View.initialize();
        spec = s;
        value = U.clamp(v, s.min, s.max);
    }

    //! Geometry is baked once (REQ-WP03: nothing computed per frame).
    function onLayout(dc as Graphics.Dc) as Void {
        _w = dc.getWidth();
        _h = dc.getHeight();
        var cx = _w / 2;
        var a = _w * 0.05;
        _up = [[cx - a, _h * 0.31], [cx + a, _h * 0.31], [cx, _h * 0.31 - a]];
        _down = [[cx - a, _h * 0.69], [cx + a, _h * 0.69], [cx, _h * 0.69 + a]];
    }

    function currentStep() as Number {
        return useAlt ? spec.alt : spec.step;
    }

    //! Moves by `direction` x step x `mult`, clamped (never wrapped).
    function step(direction as Number, mult as Number) as Void {
        var next = value + direction * currentStep() * mult;
        atBound = 0;
        if (next >= spec.max) {
            next = spec.max;
            atBound = 1;
        } else if (next <= spec.min) {
            next = spec.min;
            atBound = -1;
        }
        value = next;
        WatchUi.requestUpdate();
    }

    function toggleStep() as Void {
        useAlt = !useAlt;
        WatchUi.requestUpdate();
    }

    function onUpdate(dc as Graphics.Dc) as Void {
        var cx = _w / 2;
        dc.setColor(Theme.BACKGROUND, Theme.BACKGROUND);
        dc.clear();
        dc.setColor(Theme.TEXT, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, _h * 0.17, Graphics.FONT_SMALL, spec.title,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        var text = spec.format(value);
        var font = text.length() > 6 ? Graphics.FONT_NUMBER_MEDIUM : Graphics.FONT_NUMBER_HOT;
        if (value == 0 && spec.zeroText != null) {
            font = Graphics.FONT_LARGE;
        }
        dc.drawText(cx, _h * 0.48, font, text, Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        if (spec.unit.length() > 0) {
            dc.setColor(Theme.DIM, Graphics.COLOR_TRANSPARENT);
            dc.drawText(cx, _h * 0.61, Graphics.FONT_SMALL, spec.unit,
                Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        }
        dc.setColor(atBound > 0 ? Theme.WARN : Theme.WORK, Graphics.COLOR_TRANSPARENT);
        dc.fillPolygon(_up);
        dc.setColor(atBound < 0 ? Theme.WARN : Theme.WORK, Graphics.COLOR_TRANSPARENT);
        dc.fillPolygon(_down);
        var hint = "step " + spec.format(currentStep()).toString() + "  MENU: " + (useAlt ? "normal" : "alt");
        if (spec.kind == PK_TIME) {
            hint = "step " + currentStep().format("%d") + " s  MENU: toggle";
        }
        if (atBound != 0) {
            hint = atBound > 0 ? "MAX reached" : "MIN reached";
        }
        dc.setColor(atBound != 0 ? Theme.WARN : Theme.DIM, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, _h * 0.80, Graphics.FONT_XTINY, hint,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        dc.setColor(Theme.DIM, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, _h * 0.89, Graphics.FONT_XTINY, "START to set",
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
    }
}

class NumPickerDelegate extends WatchUi.BehaviorDelegate {

    const HOLD_DELAY_MS = 450;
    const REPEAT_MS = 120;

    private var _view as NumPickerView;
    private var _onAccept as Method;
    private var _timer as Timer.Timer? = null;
    private var _dir as Number = 0;
    private var _repeats as Number = 0;

    function initialize(view as NumPickerView, onAccept as Method) {
        BehaviorDelegate.initialize();
        _view = view;
        _onAccept = onAccept;
    }

    const STREAK_MS = 400;
    const STREAK_FAST = 6;
    private var _lastPress as Number = 0;
    private var _lastDir as Number = 0;
    private var _streak as Number = 0;

    function onPreviousPage() as Boolean {
        _press(1);
        return true;
    }

    function onNextPage() as Boolean {
        _press(-1);
        return true;
    }

    //! One press; a fast run of presses in one direction accelerates.
    function _press(dir as Number) as Void {
        var t = System.getTimer();
        if (dir == _lastDir && t - _lastPress < STREAK_MS) {
            _streak += 1;
        } else {
            _streak = 0;
        }
        _lastDir = dir;
        _lastPress = t;
        _view.step(dir, _streak >= STREAK_FAST ? 10 : 1);
    }

    //! Hold acceleration: after HOLD_DELAY_MS keep stepping; x10 after 8.
    function onKeyPressed(evt as WatchUi.KeyEvent) as Boolean {
        var key = evt.getKey();
        // Only DOWN: a held UP becomes the MENU key on this device.
        if (key == WatchUi.KEY_DOWN) {
            _dir = -1;
            _repeats = 0;
            _stop();
            _timer = new Timer.Timer();
            (_timer as Timer.Timer).start(method(:onHoldStart), HOLD_DELAY_MS, false);
        }
        return false;
    }

    function onKeyReleased(evt as WatchUi.KeyEvent) as Boolean {
        _stop();
        return false;
    }

    function onHoldStart() as Void {
        _timer = new Timer.Timer();
        (_timer as Timer.Timer).start(method(:onRepeat), REPEAT_MS, true);
    }

    function onRepeat() as Void {
        _repeats += 1;
        _view.step(_dir, _repeats > 8 ? 10 : 1);
    }

    function _stop() as Void {
        if (_timer != null) {
            (_timer as Timer.Timer).stop();
            _timer = null;
        }
    }

    function onMenu() as Boolean {
        _view.toggleStep();
        return true;
    }

    function onSelect() as Boolean {
        _stop();
        var v = _view.value;
        WatchUi.popView(WatchUi.SLIDE_DOWN);
        _onAccept.invoke(v);
        return true;
    }

    function onBack() as Boolean {
        _stop();
        WatchUi.popView(WatchUi.SLIDE_DOWN);
        return true;
    }

    function onTap(evt as WatchUi.ClickEvent) as Boolean {
        var y = evt.getCoordinates()[1];
        var h = System.getDeviceSettings().screenHeight;
        if (y < h / 3) {
            _view.step(1, 1);
        } else if (y > h * 2 / 3) {
            _view.step(-1, 1);
        } else {
            return onSelect();
        }
        return true;
    }

    function onSwipe(evt as WatchUi.SwipeEvent) as Boolean {
        var d = evt.getDirection();
        if (d == WatchUi.SWIPE_UP) {
            _view.step(1, 1);
            return true;
        }
        if (d == WatchUi.SWIPE_DOWN) {
            _view.step(-1, 1);
            return true;
        }
        return false;
    }

    function onHold(evt as WatchUi.ClickEvent) as Boolean {
        _view.toggleStep();
        return true;
    }
}
