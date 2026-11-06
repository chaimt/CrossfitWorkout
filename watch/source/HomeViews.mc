//
// HomeViews.mc
//
// Home (Today / Workouts / History / Settings / Sync, REQ-WN01), the inbox
// list, the workout preview with its part list, Run all and Delete
// (REQ-WN02/WN04), and the part screen with Start and the pre-start timer
// edits (REQ-WN03, WOD Timer's setup-menu + ValuePicker pattern).
// BACK on Home exits the app.
//
import Toybox.Lang;
import Toybox.WatchUi;

class HomeMenu extends WatchUi.Menu2 {

    function initialize() {
        Menu2.initialize({:title => "WOD Coach"});
        var app = CoachApp.instance();
        if (app.recovered != null) {
            addItem(new WatchUi.IconMenuItem("Recovered", "Interrupted - saved", :recovered,
            WatchUi.loadResource(Rez.Drawables.MenuRecovered), null));
        }
        addItem(new WatchUi.IconMenuItem("Today", _today(), :today,
            WatchUi.loadResource(Rez.Drawables.MenuToday), null));
        addItem(new WatchUi.IconMenuItem("Workouts", _inbox(), :workouts,
            WatchUi.loadResource(Rez.Drawables.MenuWorkouts), null));
        addItem(new WatchUi.IconMenuItem("History", _history(), :history,
            WatchUi.loadResource(Rez.Drawables.MenuHistory), null));
        addItem(new WatchUi.IconMenuItem("Settings", null, :settings,
            WatchUi.loadResource(Rez.Drawables.MenuSettings), null));
        addItem(new WatchUi.IconMenuItem("Sync now", _sync(), :sync,
            WatchUi.loadResource(Rez.Drawables.MenuSync), null));
    }

    function refresh() as Void {
        _setSub(:today, _today());
        _setSub(:workouts, _inbox());
        _setSub(:history, _history());
        _setSub(:sync, _sync());
        WatchUi.requestUpdate();
    }

    function _setSub(id as Symbol, text as String) as Void {
        var i = findItemById(id);
        if (i >= 0) {
            var item = getItem(i);
            if (item != null) {
                item.setSubLabel(text);
            }
        }
    }

    function _today() as String {
        var row = Inbox.todayRow(CoachApp.instance().kv, Fmt.today());
        return row == null ? "No workout" : row[IX_TITLE] as String;
    }

    function _inbox() as String {
        var n = Inbox.index(CoachApp.instance().kv).size();
        return n == 0 ? "Send one from the phone" : n.format("%d") + (n == 1 ? " plan" : " plans");
    }

    function _history() as String {
        var kv = CoachApp.instance().kv;
        var n = History.rows(kv).size();
        var unsent = History.unsent(kv);
        if (n == 0) {
            return "No results yet";
        }
        return n.format("%d") + " saved" + (unsent > 0 ? ", " + unsent.format("%d") + " unsent" : "");
    }

    function _sync() as String {
        var app = CoachApp.instance();
        var n = Outbox.pendingCount(app.kv);
        var link = app.link;
        if (link != null && link.lastError != null && n > 0) {
            return n.format("%d") + " pending - " + link.lastError;
        }
        return n == 0 ? "All sent" : n.format("%d") + " pending";
    }
}

class HomeDelegate extends WatchUi.Menu2InputDelegate {

    private var _menu as HomeMenu;

    function initialize(menu as HomeMenu) {
        Menu2InputDelegate.initialize();
        _menu = menu;
    }

    function onSelect(item as WatchUi.MenuItem) as Void {
        var id = item.getId();
        var app = CoachApp.instance();
        if (id == :today) {
            var row = Inbox.todayRow(app.kv, Fmt.today());
            if (row != null) {
                PlanMenu.open(row[IX_ID] as String);
            }
        } else if (id == :workouts) {
            var m = new WorkoutsMenu();
            WatchUi.pushView(m, new WorkoutsDelegate(), WatchUi.SLIDE_LEFT);
        } else if (id == :history) {
            var h = new HistoryMenu();
            WatchUi.pushView(h, new HistoryDelegate(), WatchUi.SLIDE_LEFT);
        } else if (id == :settings) {
            WatchUi.pushView(new SettingsMenu(), new SettingsDelegate(), WatchUi.SLIDE_LEFT);
        } else if (id == :sync) {
            if (app.link != null) {
                (app.link as PhoneLink).syncNow();
            }
            item.setSubLabel("Syncing...");
            WatchUi.requestUpdate();
        } else if (id == :recovered) {
            _recovered();
        }
    }

    //! REQ-WO06: offer View result or Start new attempt after recovery.
    function _recovered() as Void {
        var app = CoachApp.instance();
        var info = app.recovered as Dictionary;
        var menu = new WatchUi.Menu2({:title => "Recovered"});
        menu.addItem(new WatchUi.MenuItem("View result", info["title"] as String, :view, null));
        menu.addItem(new WatchUi.MenuItem("Start new attempt", null, :restart, null));
        menu.addItem(new WatchUi.MenuItem("Dismiss", null, :dismiss, null));
        WatchUi.pushView(menu, new RecoveredDelegate(info), WatchUi.SLIDE_LEFT);
    }

    function onBack() as Void {
        WatchUi.popView(WatchUi.SLIDE_RIGHT);
    }
}

class RecoveredDelegate extends WatchUi.Menu2InputDelegate {

    private var _info as Dictionary;

    function initialize(info as Dictionary) {
        Menu2InputDelegate.initialize();
        _info = info;
    }

    function onSelect(item as WatchUi.MenuItem) as Void {
        var id = item.getId();
        var app = CoachApp.instance();
        if (id == :view) {
            var rid = _info["rid"] as String;
            HistoryDetail.open(rid.length() > 0 ? rid : _info["sid"] as String);
            return;
        }
        if (id == :restart) {
            var kv = app.kv;
            var sum = History.object(kv, _info["sid"] as String);
            var planId = U.str(sum, "pl");
            app.recovered = null;
            if (planId != null && Inbox.plan(kv, planId) != null) {
                WatchUi.popView(WatchUi.SLIDE_IMMEDIATE);
                PlanMenu.openRestart(planId, _info["sid"] as String);
            } else {
                CoachUi.showMessage("Plan gone", ["The plan is no longer", "on the watch."]);
            }
            return;
        }
        app.recovered = null;
        WatchUi.popView(WatchUi.SLIDE_RIGHT);
    }
}

//! Inbox, newest received first (REQ-WN01).
class WorkoutsMenu extends WatchUi.Menu2 {

    function initialize() {
        Menu2.initialize({:title => "Workouts"});
        var ix = Inbox.index(CoachApp.instance().kv);
        for (var i = 0; i < ix.size(); i++) {
            var r = ix[i] as Array;
            var n = r[IX_PARTS] as Number;
            addItem(new WatchUi.MenuItem(r[IX_TITLE] as String,
                (r[IX_DATE] as String) + " - " + n.format("%d") + (n == 1 ? " part" : " parts"),
                r[IX_ID] as String, null));
        }
        if (ix.size() == 0) {
            addItem(new WatchUi.MenuItem("No workouts", "Send one from the phone", :none, null));
        }
    }
}

class WorkoutsDelegate extends WatchUi.Menu2InputDelegate {

    function initialize() {
        Menu2InputDelegate.initialize();
    }

    function onSelect(item as WatchUi.MenuItem) as Void {
        var id = item.getId();
        if (id instanceof String) {
            PlanMenu.open(id);
        }
    }
}

//! Workout preview: parts, Run all, Delete (REQ-WN02/WN04).
class PlanMenu extends WatchUi.Menu2 {

    var planId as String;
    var plan as Dictionary;
    //! Pre-start edits per part index, shared with PartMenu (REQ-WN03).
    var edits as Dictionary = {};
    var restartOf as String? = null;

    static function open(id as String) as Void {
        var plan = Inbox.plan(CoachApp.instance().kv, id);
        if (plan == null) {
            return;
        }
        var m = new PlanMenu(id, plan);
        WatchUi.pushView(m, new PlanDelegate(m), WatchUi.SLIDE_LEFT);
    }

    //! Restart after an interruption: new session linked by rso (REQ-WN07).
    static function openRestart(id as String, previousSession as String) as Void {
        var plan = Inbox.plan(CoachApp.instance().kv, id);
        if (plan == null) {
            return;
        }
        var m = new PlanMenu(id, plan);
        m.restartOf = previousSession;
        WatchUi.pushView(m, new PlanDelegate(m), WatchUi.SLIDE_LEFT);
    }

    function initialize(id as String, p as Dictionary) {
        planId = id;
        plan = p;
        var title = U.str(p, "ti");
        Menu2.initialize({:title => title == null ? "Workout" : title});
        var parts = p["p"] as Array;
        addItem(new WatchUi.MenuItem("Run all", parts.size().format("%d") + " parts - "
            + U.str(p, "date") + " " + U.str(p, "tz"), :all, null));
        for (var i = 0; i < parts.size(); i++) {
            var part = parts[i] as Dictionary;
            addItem(new WatchUi.MenuItem(U.str(part, "l") + " - " + U.str(part, "n"), _modeLabel(part), i, null));
        }
        addItem(new WatchUi.MenuItem("Delete plan", null, :delete, null));
    }

    static function _modeLabel(part as Dictionary) as String {
        if (U.flag(part, "fb", false)) {
            return "Manual timer";
        }
        var m = U.str(part, "mode");
        return m == null ? "" : m;
    }

    function allIndices() as Array<Number> {
        var parts = plan["p"] as Array;
        var out = [] as Array<Number>;
        for (var i = 0; i < parts.size(); i++) {
            out.add(i);
        }
        return out;
    }
}

class PlanDelegate extends WatchUi.Menu2InputDelegate {

    private var _menu as PlanMenu;
    private var _deleteArmed as Boolean = false;

    function initialize(menu as PlanMenu) {
        Menu2InputDelegate.initialize();
        _menu = menu;
    }

    function onSelect(item as WatchUi.MenuItem) as Void {
        var id = item.getId();
        if (id == :all) {
            CoachUi.start(_menu.plan, _menu.allIndices(), _menu.edits, _menu.restartOf);
        } else if (id == :delete) {
            // Destructive: second press confirms (REQ-WN06).
            if (!_deleteArmed) {
                _deleteArmed = true;
                item.setSubLabel("Press again to delete");
                WatchUi.requestUpdate();
                return;
            }
            Inbox.deleteLocal(CoachApp.instance().kv, _menu.planId);
            CoachApp.instance().onInboxChanged();
            WatchUi.popView(WatchUi.SLIDE_RIGHT);
        } else if (id instanceof Number) {
            var pm = new PartMenu(_menu, id);
            WatchUi.pushView(pm, new PartDelegate(pm), WatchUi.SLIDE_LEFT);
        }
    }
}

//! One part: Start, editable timer fields, and its display lines.
class PartMenu extends WatchUi.Menu2 {

    var owner as PlanMenu;
    var index as Number;
    var part as Dictionary;
    var fields as Array<FieldSpec>;

    function initialize(planMenu as PlanMenu, i as Number) {
        owner = planMenu;
        index = i;
        part = (planMenu.plan["p"] as Array)[i] as Dictionary;
        Menu2.initialize({:title => U.str(part, "l") + " - " + U.str(part, "n")});
        addItem(new WatchUi.MenuItem("Start", "START to begin", :start, null));
        if (U.flag(part, "fb", false)) {
            // REQ-DM18 label for reviewed fallbacks.
            addItem(new WatchUi.MenuItem("Manual timer", "no automatic movement/interrupt/rest cues", :none, null));
        }
        fields = Edits.fields(part);
        var e = planMenu.edits.get(i);
        for (var f = 0; f < fields.size(); f++) {
            var spec = fields[f];
            if (e instanceof Dictionary && e.hasKey(spec.key)) {
                spec.value = e[spec.key] as Number;
            }
            addItem(new WatchUi.MenuItem(spec.label, spec.format(spec.value), f, null));
        }
        var ln = U.arr(part, "ln");
        if (ln != null) {
            for (var l = 0; l < ln.size(); l++) {
                addItem(new WatchUi.MenuItem(ln[l] as String, null, :none, null));
            }
        }
    }

    //! Stores an edited field for this run only.
    function setField(f as Number, value as Number) as Void {
        var spec = fields[f];
        spec.value = value;
        var e = owner.edits.get(index);
        var d = e instanceof Dictionary ? e : {};
        d.put(spec.key, value);
        owner.edits.put(index, d);
        var i = findItemById(f);
        if (i >= 0) {
            var item = getItem(i);
            if (item != null) {
                item.setSubLabel(spec.format(value));
            }
        }
        WatchUi.requestUpdate();
    }
}

class PartDelegate extends WatchUi.Menu2InputDelegate {

    private var _menu as PartMenu;
    private var _field as Number = -1;

    function initialize(menu as PartMenu) {
        Menu2InputDelegate.initialize();
        _menu = menu;
    }

    function onSelect(item as WatchUi.MenuItem) as Void {
        var id = item.getId();
        if (id == :start) {
            CoachUi.start(_menu.owner.plan, [_menu.index] as Array<Number>, _menu.owner.edits,
                _menu.owner.restartOf);
        } else if (id instanceof Number) {
            _field = id;
            var spec = _menu.fields[id];
            var kind = spec.kind == KIND_COUNT ? PK_COUNT : PK_TIME;
            var ps = new PickerSpec(spec.label, kind, spec.min, spec.max, spec.step, spec.step * 4, 0, "");
            if (spec.kind == KIND_CAP) {
                ps.zeroText = "None";
            }
            NumPicker.open(ps, spec.value, method(:onValue));
        }
    }

    function onValue(value as Number) as Void {
        if (_field >= 0) {
            _menu.setField(_field, value);
        }
    }
}
