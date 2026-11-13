# WOD Coach (Connect IQ watch app)

The watch half of CrossfitWorkout (REQUIREMENTS.md §6). "Phone thinks, watch
executes" (D2): WOD Capture compiles each workout into a small WatchPlan
(docs/protocol.md); WOD Coach validates it, runs it part by part, records one
FIT activity and sends typed results back. Seeded from WOD Timer (D5): engine,
Alerts, ValuePicker, theme and scripts were copied and generalised.

MVP target: **Forerunner 965 only** (REQ-WP01), `minSdkVersion` 5.2.0.
App UUIDs: production `0508f6cf6f0040e59d7ed9f432688c46` (`manifest.xml`),
beta `a930ecf2337f4023a7b446ce221a73cf` (`manifest-beta.xml`, `--beta`).

## Build, test, run

You need the Connect IQ SDK (9.2.0 used) and `watch/developer_key`
(git-ignored, never committed).

```bash
cd watch
task build                 # bash scripts/build.sh      -> bin/wodcoach.prg  (-l 2 type check)
task build -- --beta       # beta UUID                  -> bin/wodcoach-beta.prg
task test                  # bash scripts/test.sh       (starts the simulator if needed, runs Run No Evil)
TEST_NAME=testFoo bash scripts/test.sh   # one test
task deploy-simulator      # build + simulator + run
task deploy-watch          # copy to a USB-connected watch (libmtp)
task package               # bin/wodcoach.iq (store / beta upload)
```

`monkey.jungle` restricts the build to `source/` + `resources/` — this matters
because worktrees live under `.claude/worktrees` (REQ-BT01).

## Layout

```
source/
  CoachApp.mc        entry: recovery, phone registration + hello, clean-exit finalize
  Comms.mc           Communications adapter (PhoneLink, TxListener)
  Protocol.mc        envelope, acks, hello, phone->watch router (pure)
  PlanCodec.mc       WatchPlan validation (pure, atomic reject)
  WireSize.mc        compact-JSON size estimator + order-independent hash (pure)
  Inbox.mc           plans, revisions, tombstones, generation, capacity (pure + Kv)
  Outbox.mc          ack-only delivery, retry/backoff budget (pure + Kv)
  History.mc         finalized results/sessions, retention (pure + Kv)
  Checkpoint.mc      two-slot checkpoint generations (pure + Kv)
  Recovery.mc        finalize an interrupted session from the checkpoint (pure + Kv)
  Settings.mc        phone profile + local override mask (pure + Kv)
  Kv.mc              Storage adapter; MemKv for tests
  Engine.mc          segment engine: Program / SegState (pure)
  Edits.mc           pre-start timer edits (pure)
  MetconRun.mc       rounds / movements / undo / finish / cap tie-break (pure)
  StrengthRun.mc     sets, rest, undo / edit / extra / skip / build-to-max (pure)
  SessionClock.mc    wall / active / part clocks, session states (pure)
  Score.mc           typed score drafts, missing != 0 (pure)
  ResultBuilder.mc   Result / SessionSummary payloads, event limit, Budget admission (pure)
  Session.mc         session state machine, FIT, cues, checkpointing (device)
  Recorder.mc        ActivityRecording wrapper, FitSport (pure), HrStats (pure)
  Alerts.mc Theme.mc Fmt.mc Util.mc Uuid.mc
  CoachUi.mc HomeViews.mc MetconView.mc StrengthView.mc RunMenus.mc
  Picker.mc HistorySettings.mc
  tests/             Fixtures (golden plan.put embedded) + *Test.mc (Run No Evil)
scripts/             build / test / run-simulator / deploy-watch / package / lib-sdk
```

## Controls (FR965, REQUIREMENTS §6.3)

| Context | START | BACK / LAP | UP / DOWN | Hold UP | Tap |
|---|---|---|---|---|---|
| Preview / menus | select | back | scroll | — | select |
| Metcon running | pause + menu | **+1 round** / next movement | timer ↔ movement list | pause + menu | +1 round (touch + tap-to-log) |
| Strength set | **log displayed set** → rest | log displayed set | set list | pause + menu | log set |
| Rest | next set now | skip rest | set list | pause + menu | next set |
| Paused | resume | pause menu | — | menu | — |
| Picker | accept | cancel | ± step (6 fast presses = ×10, hold DOWN accelerates) | fine/coarse step | thirds ± / middle accept, swipe ± |
| Result | Save is the first item | exit dialog | scroll | — | select |
| Transition | start next part | exit dialog | — | exit dialog | — |

BACK is LAP only while a timer runs; everywhere else BACK is back (or the
exit dialog inside a session). Destructive actions (discard session, discard
score, delete plan) need a second press.

Deviation: on the fr965 a held UP *is* the MENU key (`simulator.json`
`isHold`), so the weight/rep pickers accelerate upward with rapid presses
rather than a hold (REQ-WE01 "holding a button accelerates" holds for DOWN).

## Requirement traceability

Legend: **I** implemented + unit-tested · **i** implemented, not unit-testable
(UI/device), checked by build only · **H** needs hardware (REQ-TS07) · **N** not
in this build.

| REQ | State | Where / notes |
|---|---|---|
| WP01/WP02 | I | manifest: fr965, 5.2.0, Fit/Communications/Sensor, eng |
| WP03 | I | pure modules + tests; fractional geometry; layout baked in onLayout |
| WP04 | I/H | ≤4 KiB plan, ≤10 plans, ≤6 parts, ≤30 sets, ≤200 events (lowered so a result fits one message); 128 KiB budget; real capacity H |
| WP05 | i | runs stored plans offline, history, outbox queued |
| WN01 | i/partial | Today/Workouts/Future/History/Settings/Sync; plan tz shown on the preview, but the watch cannot compare IANA zones (no API) |
| WN02 | i | preview, part list, Start, Run all, transition screen |
| WN03 | I | Edits.mc (time, cap, EMOM interval/rounds, Tabata work/rest/rounds) reported in `edt` |
| WN04 | I | delete with confirmation, tombstoned; auto-expiry is v1 |
| WN06/WN07 | i | single-press round/set, double-confirm destructive, exit dialog, clean-exit finalize |
| WM01 | I | Engine.mc (prep, work, rest, open, drift-free, never -0:01) |
| WM02 | I | for_time cap, finish-wins tie, capped reps via `rr`, Stop early |
| WM03 | I | AMRAP rounds, ladder `rr`, extra reps bounded by current round |
| WM04 | I/i | EMOM stations + next preview; per-interval entry is v1 |
| WM05/WM06 | I/i | Tabata blocks, intervals; per-interval score entry during rest not built (score at end) |
| WM08 | I | max effort window + single entry |
| WM09 | I | movement vs round tracking, switchable from the pause menu |
| WM11 | i/partial | running screen + movement list page; HR shown but **not zone-coloured** (needs UserProfile permission) |
| WM12/WM14 | i | pause/resume/finish early/discard; entry during strength rest keeps the press time |
| WS01–WS05, WS07 | I | StrengthRun.mc; one-press log, rest at press, 10 s/0 cues, overtime, undo/edit/extra/skip/end, build-to-max |
| WE01/WE03/WE04/WE05 | i | Picker.mc: clamped, bound shown, fine/coarse, time m:ss/h:mm:ss, rounds+reps; text scores → "enter on phone" |
| WE06 | i | result menu: Save, score, level toggle, item loads, discard |
| WE07 | i | touch: thirds/swipe/hold in pickers; buttons everywhere |
| WC01/WC02 | i/H | Alerts.mc (WOD Timer cues + rest 10 s/0, cap, refused) |
| WR01/WR02/WR04/WR09 | I/i/H | FitSport tested; Recorder: start at first work, hold on pause/entry/wait, laps, failures visible ("REC FAILED"), results kept |
| WR05/WR10 | I/i | live HR, HR sensor enabled; part HR from own 1 Hz samples, part calories only from a valid delta, else omitted |
| WR07/WR08 | I | SessionClock + §6.12 deterministic example test |
| WO01/WO02/WO03 | I | checkpoint before UI confirm, results finalized on Save, ack-only delivery, ≤50 rows never pruning unsent |
| WO04 | i | part summary on transition screen, session summary screen |
| WO05 | I | Budget.admit (evicts acknowledged only), storage-failure freeze + Retry |
| WO06 | I | Checkpoint generations + Recovery (idempotent) |
| WG01 | I | Settings: override mask, reset, profile.put revisions, run snapshot |
| SY01/SY05 | i/H | registerForPhoneAppMessages in onStart, hello at start, transmit + ConnectionListener |
| SY02/SY03/SY07/SY11/SY14 | I | Proto.handle, Inbox |
| SY13 | I | Outbox: one outstanding, 30 s timeout, 5/15/60 s, 3 per drain, 5 min auto-drain limit, blocking errors |
| SY15 | N | result.chunk not sent (protocol 3.8 allows); results are bounded to fit result.put instead |
| SY16 | I | session.put with skipped/unreached entries, rso |
| NF01/NF02/NF03/NF04 | I/H | checkpoint ≤5 s while active; elapsed-based timer; 1 Hz redraw; one wake per phase change. Durability/latency on device: H |
| TS03 | I | decoder, engine, score/result, admission/recovery, retry/dedupe tests |
| TS12 | I | 1,000 reps, exact cap tie, partial/failed set, undo, §6.12 example; 2,000 m / 0.125 km covered by picker spec only (UI) |
| TS05/TS07 | H | all real-device checks: send/ack with app open/closed, FIT start/stop/save, storage capacity, encoded sizes, checkpoint latency |

## Protocol notes / proposals (for docs/protocol.md)

1. **Pending score:** `result.schema.json` requires the value keys for every
   typed score, so an un-entered score is sent as `{"ty":"none","rv":1,"man":true}`.
   Proposal: allow `"pd": true` (pending) with the intended `ty` and no value.
2. **Event `ms` base:** the watch uses *part* running time (pauses excluded,
   in-part rests included) for `ev[*][2]` and `sets[*][8]`. Proposal: state this
   explicitly ("since the part's first work start").
3. **`fit` on part results:** the final outcome is only known when the session's
   activity is saved; results finalized before the last part carry `"unknown"`
   and the SessionSummary `fit` is authoritative.
4. **Skipped sets** use `actualReps = 0` + status `skipped` (schema minimum 0);
   status, not the number, distinguishes skipped from zero.
5. **Event bound:** the watch lowers the 200-event bound per part so the full
   Result fits one `result.put` (no watch-side chunking in MVP).
6. **profile.put `oid`:** the watch accepts any `oid`; suggest fixing it to `"profile"`.

## Remove or reinstall over USB

From the repository root:

```sh
task watch:remove-watch
task watch:reinstall-watch
```

`remove-watch` removes only `GARMIN/Apps/wodcoach.prg`; an already absent file is a
successful no-op. `reinstall-watch` builds first, then removes and copies the app
in sequence. These tasks require USB file-transfer mode and libmtp, like
`deploy-watch`. They do not erase saved watch data or promise a factory-reset install.
