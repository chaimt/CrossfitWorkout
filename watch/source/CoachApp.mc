//
// CoachApp.mc
//
// Application entry point for WOD Coach - the watch half of CrossfitWorkout
// (REQUIREMENTS.md section 6). "Phone thinks, watch executes" (D2): plans
// arrive pre-compiled from WOD Capture; the watch validates, runs, records
// and reports results back.
//
// onStart: recover any session that did not finish (REQ-WO06), register
// for phone messages and send hello (REQ-SY05). onStop: a session that is
// still open is checkpointed, its FIT saved and finalized as interrupted
// (REQ-WN07) - never left only in memory.
//
import Toybox.Application;
import Toybox.Lang;
import Toybox.WatchUi;

class CoachApp extends Application.AppBase {

    var kv as Kv = new Kv();
    var link as PhoneLink? = null;
    var session as Session? = null;
    //! Set when onStart recovered an interrupted session (shown on Home).
    var recovered as Dictionary? = null;
    private var _home as HomeMenu? = null;

    function initialize() {
        AppBase.initialize();
    }

    static function instance() as CoachApp {
        return Application.getApp() as CoachApp;
    }

    function onStart(state as Dictionary?) as Void {
        Settings.setStore(kv);
        recovered = Recovery.run(kv, "unknown");
        var l = new PhoneLink(kv);
        l.onInbox = method(:onInboxChanged);
        link = l;
        l.start();
    }

    function onStop(state as Dictionary?) as Void {
        var s = session;
        if (s != null) {
            s.onAppStop();
            session = null;
        }
        if (link != null) {
            (link as PhoneLink).stop();
        }
    }

    function getInitialView() as [WatchUi.Views] or [WatchUi.Views, WatchUi.InputDelegates] {
        var home = new HomeMenu();
        _home = home;
        return [home, new HomeDelegate(home)];
    }

    //! Plans changed (plan.put / plan.del applied): refresh menus.
    function onInboxChanged() as Void {
        if (_home != null) {
            (_home as HomeMenu).refresh();
        }
        WatchUi.requestUpdate();
    }

    //! A finalized result was queued: retry delivery now (REQ-WO02).
    function onResultQueued() as Void {
        if (link != null) {
            (link as PhoneLink).kick();
        }
    }

    function onSessionClosed() as Void {
        session = null;
        if (link != null) {
            (link as PhoneLink).kick();
        }
        onInboxChanged();
    }
}
