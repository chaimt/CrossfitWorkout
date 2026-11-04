//
// Alerts.mc
//
// Sound and vibration cues (REQ-WC01/WC02), reused from WOD Timer. Each cue
// has a distinct pitch and a distinct vibration pulse count / length -
// Forerunners play every vibration profile at one duty cycle, so cues must
// differ by pulses, not intensity. Honors the Sound / Vibration settings of
// the run snapshot and degrades to built-in tones where custom tone
// profiles are unsupported. Phase changes wake the display once (never
// hold the backlight, REQ-NF04).
//
import Toybox.Attention;
import Toybox.Lang;

module Alerts {

    var sound as Boolean = true;
    var vibrate as Boolean = true;

    //! Uses the run snapshot's settings (REQ-WG01).
    function configure(snd as Boolean, vib as Boolean) as Void {
        sound = snd;
        vibrate = vib;
    }

    //! 3-2-1 countdown tick before a phase change.
    function tick() as Void {
        _play(Attention.TONE_KEY, [[1300, 120]]);
        _vibrate([[60, 120]]);
    }

    //! A work interval starts (first interval, every EMOM minute, after rest).
    function work() as Void {
        _play(Attention.TONE_START, [[2000, 550]]);
        _vibrate([[100, 600]]);
        _wake();
    }

    //! A rest interval starts.
    function rest() as Void {
        _play(Attention.TONE_STOP, [[800, 550]]);
        _vibrate([[100, 300], [0, 150], [100, 300]]);
        _wake();
    }

    //! The athlete logged a round / movement / set.
    function round() as Void {
        _play(Attention.TONE_LAP, null);
        _vibrate([[80, 150]]);
    }

    //! Strength rest: 10 s left (REQ-WC02).
    function restWarning() as Void {
        _play(Attention.TONE_INTERVAL_ALERT, [[1500, 150], [1500, 150]]);
        _vibrate([[80, 150], [0, 120], [80, 150]]);
    }

    //! Strength rest reached zero: next set (REQ-WC02).
    function restOver() as Void {
        _play(Attention.TONE_START, [[2000, 400], [2400, 400]]);
        _vibrate([[100, 400], [0, 150], [100, 400], [0, 150], [100, 400]]);
        _wake();
    }

    //! Time cap reached (REQ-WM02).
    function timeCap() as Void {
        _play(Attention.TONE_TIME_ALERT, [[2400, 300], [1600, 300], [2400, 600]]);
        _vibrate([[100, 800], [0, 200], [100, 800]]);
        _wake();
    }

    //! A request could not be accepted (event bound, storage full).
    function refused() as Void {
        _play(Attention.TONE_ERROR, [[400, 400]]);
        _vibrate([[100, 200], [0, 100], [100, 200], [0, 100], [100, 200], [0, 100], [100, 200]]);
    }

    //! The workout / part finished.
    function done() as Void {
        _play(Attention.TONE_SUCCESS, [[1200, 180], [1600, 180], [2000, 180], [2400, 600]]);
        _vibrate([[100, 400], [0, 200], [100, 400], [0, 200], [100, 800]]);
        _wake();
    }

    //! Plays a custom [frequency Hz, duration ms] sequence where supported,
    //! else the built-in `fallback` tone.
    function _play(fallback as Attention.Tone, notes as Array<Array<Number>>?) as Void {
        if (!sound || !(Attention has :playTone)) {
            return;
        }
        if (notes != null && Attention has :ToneProfile) {
            var profile = [] as Array<Attention.ToneProfile>;
            for (var i = 0; i < notes.size(); i++) {
                profile.add(new Attention.ToneProfile(notes[i][0], notes[i][1]));
            }
            Attention.playTone({:toneProfile => profile});
            return;
        }
        Attention.playTone(fallback);
    }

    //! Turns the display on. AMOLED devices throw if the backlight is held
    //! on too long; a one-off wake per phase change never should, but a
    //! missed wake must not crash the workout.
    function _wake() as Void {
        if (!(Attention has :backlight)) {
            return;
        }
        try {
            Attention.backlight(true);
        } catch (e) {
            // Burn-in protection refused; the cue still played.
        }
    }

    //! Runs a [duty %, duration ms] vibration sequence (at most 8 entries).
    function _vibrate(pattern as Array<Array<Number>>) as Void {
        if (!vibrate || !(Attention has :vibrate)) {
            return;
        }
        var profile = [] as Array<Attention.VibeProfile>;
        for (var i = 0; i < pattern.size() && i < 8; i++) {
            profile.add(new Attention.VibeProfile(pattern[i][0], pattern[i][1]));
        }
        Attention.vibrate(profile);
    }
}
