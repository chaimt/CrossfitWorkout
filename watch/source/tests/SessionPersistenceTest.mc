// Regression tests for normal-session durability, separate from crash recovery.
import Toybox.Lang;
import Toybox.Test;

(:test)
class PersistenceSession extends Session {
    var closed as Boolean = false;

    function initialize(store as Kv) {
        Session.initialize(store, Fx.plan(), [1] as Array<Number>, {}, null);
        part = partAt(0);
        recorder = new Recorder(false);
        resultId = "80000000-0000-4000-8000-000000000099";
        kv.put("snap", plan);
        clock.enter(CS_RUN, 100);
        _checkpoint(200);
    }

    function _notifyResultQueued() as Void {}
    function _notifyFinalized() as Void { closed = true; }
    function _showStage() as Void {}
}

(:test)
class FailingSessionKv extends MemKv {
    var failKey as String? = null;
    function initialize() { MemKv.initialize(); }
    function put(key as String, value) as Boolean {
        if (failKey != null && key.equals(failKey)) {
            return false;
        }
        return MemKv.put(key, value);
    }
}

(:test)
function testSessionSummaryFailureKeepsRecoveryState(logger as Test.Logger) as Boolean {
    var kv = new FailingSessionKv();
    var s = new PersistenceSession(kv);
    kv.failKey = "o_" + s.sid;
    s._finalize(300);
    Test.assert(!s.finalized);
    Test.assert(!s.closed);
    Test.assert(Checkpoint.exists(kv));
    Test.assert(kv.get("snap") != null);
    var view = CoachUi._stageView(s);
    Test.assert(view[1] instanceof FinalizationDelegate);
    kv.failKey = null;
    (view[1] as FinalizationDelegate).onSelect();
    Test.assert(s.finalized);
    Test.assert(s.closed);
    Test.assert(!Checkpoint.exists(kv));
    Test.assert(kv.get("snap") == null);
    Test.assertEqual(1, Outbox.pendingCount(kv));
    return true;
}

(:test)
function testSessionOutboxFailureKeepsSummaryAndRetriesIdempotently(logger as Test.Logger) as Boolean {
    var kv = new FailingSessionKv();
    var s = new PersistenceSession(kv);
    kv.failKey = "ob";
    s._finalize(300);
    Test.assert(!s.finalized);
    Test.assert(History.object(kv, s.sid) != null);
    Test.assert(Checkpoint.exists(kv));
    var first = History.object(kv, s.sid) as Dictionary;
    kv.failKey = null;
    s._finalize(900);
    Test.assert(s.finalized);
    Test.assertEqual(WireSize.hash(first), WireSize.hash(History.object(kv, s.sid) as Dictionary));
    Test.assertEqual(1, Outbox.pendingCount(kv));
    return true;
}

(:test)
function testInterruptedPartWriteFailureRetainsDraft(logger as Test.Logger) as Boolean {
    var kv = new FailingSessionKv();
    var s = new PersistenceSession(kv);
    var o = new PartOutcome();
    o.partId = U.str(s.part, "id") as String;
    o.resultId = s.resultId;
    o.status = "interrupted";
    s.outcome = o;
    s.clock.enter(CS_ENTRY, 300);
    kv.failKey = "o_" + o.resultId;
    s.savePartAndStop(300);
    Test.assert(s.outcome != null);
    Test.assert(!s.finalized);
    Test.assertEqual(0, s.entries.size());
    Test.assert(Checkpoint.exists(kv));
    kv.failKey = null;
    s.savePartAndStop(900);
    Test.assert(s.finalized);
    Test.assertEqual(2, Outbox.pendingCount(kv));
    Test.assert(s.outcome == null);
    return true;
}
