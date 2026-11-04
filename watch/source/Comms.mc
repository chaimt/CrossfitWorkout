//
// Comms.mc
//
// The Toybox.Communications adapter (REQ-SY01/SY04/SY05/SY13). Everything
// protocol-related is in Proto / Inbox / Outbox; this class only moves
// Dictionaries over the link:
//
//   * registerForPhoneAppMessages() in onStart, so every message waiting
//     in the mailbox is delivered when the app opens (REQ-SY05);
//   * control messages (acks, hello, sync.ack) go first and bypass the
//     data queue; one transmit is in flight at a time;
//   * data messages come from the Outbox one at a time; SDK onComplete is
//     NOT delivery - only the phone's ack removes an entry;
//   * a 5 s pump timer drives retries / ack timeouts from Outbox.next().
//
import Toybox.Communications;
import Toybox.Lang;
import Toybox.Time;
import Toybox.Timer;

class PhoneLink {

    const PUMP_MS = 5000;

    private var _kv as Kv;
    private var _control as Array<Dictionary> = [] as Array<Dictionary>;
    private var _busy as Boolean = false;
    private var _timer as Timer.Timer? = null;
    var lastError as String? = null;
    var onInbox as Method? = null;

    function initialize(kv as Kv) {
        _kv = kv;
    }

    function start() as Void {
        if (!(Toybox has :Communications)) {
            return;
        }
        Communications.registerForPhoneAppMessages(method(:onPhoneMessage));
        sendHello();
        Outbox.startDrain(_kv, _nowSec(), false);
        _timer = new Timer.Timer();
        (_timer as Timer.Timer).start(method(:pump), PUMP_MS, true);
        pump();
    }

    function stop() as Void {
        if (_timer != null) {
            (_timer as Timer.Timer).stop();
            _timer = null;
        }
    }

    function _nowSec() as Number {
        return Time.now().value();
    }

    //! hello at app start and after sync.req (docs/protocol.md 3.2).
    function sendHello() as Void {
        _control.add(Proto.hello(_kv, _nowSec(), Budget.free(_kv)));
        _flush();
    }

    //! Explicit "Sync now" from the home screen.
    function syncNow() as Void {
        var t = _nowSec();
        Outbox.unblockAll(_kv, t);
        Outbox.startDrain(_kv, t, true);
        sendHello();
        pump();
    }

    //! A new result / session was queued: drain now (REQ-WO02).
    function kick() as Void {
        Outbox.startDrain(_kv, _nowSec(), true);
        pump();
    }

    function onPhoneMessage(msg as Communications.PhoneAppMessage) as Void {
        var res = Proto.handle(_kv, msg.data, _nowSec(), Budget.free(_kv));
        var out = res["out"] as Array;
        for (var i = 0; i < out.size(); i++) {
            _control.add(out[i] as Dictionary);
        }
        if (res["hello"] as Boolean) {
            _control.add(Proto.hello(_kv, _nowSec(), Budget.free(_kv)));
        }
        if ((res["inbox"] as Boolean) && onInbox != null) {
            (onInbox as Method).invoke();
        }
        _flush();
        if (res["drain"] as Boolean) {
            pump();
        }
    }

    //! Sends the next control message, else the next due data message.
    function pump() as Void {
        if (_busy) {
            return;
        }
        if (_control.size() > 0) {
            _flush();
            return;
        }
        var t = _nowSec();
        var e = Outbox.next(_kv, t);
        if (e == null) {
            return;
        }
        var msg = Proto.dataMessage(_kv, e);
        if (msg == null) {
            // The object is gone; drop the entry instead of retrying forever.
            Outbox.onAck(_kv, e[OB_MID] as String, "ok");
            return;
        }
        Outbox.markSent(_kv, e[OB_MID] as String, t);
        _transmit(msg, e[OB_MID] as String);
    }

    function _flush() as Void {
        if (_busy || _control.size() == 0) {
            return;
        }
        var msg = _control[0];
        _control = _control.slice(1, null);
        _transmit(msg, null);
    }

    function _transmit(msg as Dictionary, dataMid as String?) as Void {
        _busy = true;
        try {
            Communications.transmit(msg, null, new TxListener(self, dataMid));
        } catch (e) {
            onSent(dataMid, false);
        }
    }

    //! Transmit finished (success = handed to the phone, not delivered).
    function onSent(dataMid as String?, ok as Boolean) as Void {
        _busy = false;
        if (!ok) {
            lastError = "Phone not reachable";
            if (dataMid != null) {
                Outbox.transmitFailed(_kv, dataMid, _nowSec());
            }
        } else {
            lastError = null;
        }
        if (_control.size() > 0) {
            _flush();
        } else if (ok) {
            pump();
        }
    }
}

class TxListener extends Communications.ConnectionListener {

    private var _link as PhoneLink;
    private var _mid as String?;

    function initialize(link as PhoneLink, mid as String?) {
        ConnectionListener.initialize();
        _link = link;
        _mid = mid;
    }

    function onComplete() as Void {
        _link.onSent(_mid, true);
    }

    function onError() as Void {
        _link.onSent(_mid, false);
    }
}
