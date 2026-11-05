//
// Kv.mc
//
// Key/value persistence adapter. The app uses Kv (Application.Storage);
// tests use MemKv so inbox / outbox / checkpoint logic runs without
// touching the simulator's real object store.
//
// put() reports failure instead of throwing: a full store
// (StorageFullException) must freeze the logical timer and tell the athlete
// the attempted event was not accepted (REQ-WO05), never crash.
//
import Toybox.Application;
import Toybox.Lang;

class Kv {

    function initialize() {
    }

    function get(key as String) {
        return Application.Storage.getValue(key);
    }

    //! Writes `value`; false when the store refused it.
    function put(key as String, value) as Boolean {
        try {
            Application.Storage.setValue(key, value as Application.PropertyValueType);
            return true;
        } catch (e) {
            return false;
        }
    }

    function del(key as String) as Void {
        Application.Storage.deleteValue(key);
    }
}

//! In-memory Kv for unit tests; `failWrites` simulates a full store.
class MemKv extends Kv {

    var data as Dictionary = {};
    var failWrites as Boolean = false;
    var writes as Number = 0;

    function initialize() {
        Kv.initialize();
    }

    function get(key as String) {
        return data.get(key);
    }

    function put(key as String, value) as Boolean {
        if (failWrites) {
            return false;
        }
        writes += 1;
        data.put(key, value);
        return true;
    }

    function del(key as String) as Void {
        data.remove(key);
    }
}
