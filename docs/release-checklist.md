# Platform gate and FR965 release checklist

Hardware evidence required by REQ-TS07 (platform gate), REQ-TS05 (FR965 release
checklist), REQ-TS09 (failure matrix) and REQ-TS10 (performance). None of this can be
proven by unit tests or the simulator; record every run in the evidence log at the bottom
and do not mark the related requirements `[x]` without an entry.

Record for every run: date, phone model + Android version, Garmin Connect app version,
FR965 firmware + Connect IQ API level, SDK version, WOD Capture / WOD Coach versions,
watch app UUID used (production `0508f6cf…` or beta `a930ecf2…`).

## 1. Platform gate (REQ-TS07) — before committing to a release

| # | Experiment | Pass when |
|---|------------|-----------|
| G1 | Send a plan with WOD Coach **open** | `plan.ack{ok}` arrives; phone shows "On watch" |
| G2 | Send a plan with WOD Coach **closed**, then open it | plan delivered on open, or resent after `hello`; one inbox entry |
| G3 | Phone out of range during send, then reconnect | plan stays pending; delivered after reconnect/`hello` without duplicates |
| G4 | Duplicate retry (same message id twice) | second ack is `already_applied`; inbox unchanged |
| G5 | Repeat G1–G4 with the **beta** UUID | same results; device screen shows which UUID is in use |
| G6 | Result round-trip: finish a part, phone in foreground | `result.put` → phone commit → `result.ack`; watch marks delivered |
| G7 | Result while phone app closed; open phone app | `sync.req` → `sync.ack` → drained results, each acked once |
| G8 | Measure encoded sizes | log SDK-reported size vs compact-JSON estimate for the largest plan/result; adjust the 512 B margin in `docs/protocol.md` §1 |
| G9 | Checkpoint latency/durability | time from LAP press to durable checkpoint ≤ 5 s target (REQ-NF01); pull power mid-run and record what survived |
| G10 | FIT start/stop/save | activity appears in Garmin Connect with laps; sport/sub-sport as REQ-WR01; pause/result entry excluded from timer time |
| G11 | Tethered simulator round-trip (REQ-SY08/TS04) | `adb forward tcp:7381 tcp:7381`, simulator Connection → Start, TETHERED mode: plan → ack → run → result → ack; assert on acks, not send status |

## 2. FR965 release checklist (REQ-TS05)

- [ ] Every mode committed for the release: for_time (with/without cap, cap tie), amrap (ladder, extra reps), emom (stations, partial-minute window), tabata (multi-block), intervals, max_effort, chipper movement mode, strength sets, build-to-max, manual fallback
- [ ] Buttons-only and touch-only operation of every screen (REQ-WE07, REQ-WN06)
- [ ] One press logs a resolved round/set; final-score flows ≤ 6 presses (REQ-TS12 a–c)
- [ ] Disconnect mid-workout; results queue and deliver later (S4)
- [ ] Intentional exit mid-session → Finish incomplete / Resume / Discard
- [ ] Forced process death → recovered as interrupted from last checkpoint (REQ-WO06)
- [ ] Low battery behaviour; > 60 min session; max admitted data (6 parts, 30 sets, 200 events)
- [ ] Storage exhaustion → Start blocked with "Sync results / free history", nothing evicted unsent (REQ-WO05)
- [ ] Watch clock / timezone change during a run does not change durations (REQ-TS12)
- [ ] Lost, stale and duplicate messages (REQ-SY11/SY14)
- [ ] Visual check of every screen on the 454 px AMOLED (REQ-TS06)
- [ ] Battery: ≥ 3 matched one-hour sessions vs native HIIT, ≤ 1.2× (S5, REQ-TS10)

## 3. Failure matrix (REQ-TS09) — manual part

Kill/restart: after event acceptance · during checkpoint write · during part result ·
after result persisted before send · after phone commit before ack. Plus: full outbox,
stale plan overwrite, retry of a result already edited/deleted on the phone, wrong-target
traffic (other UUID / old pairing generation), cancelled parse, late re-parse after manual
edit. Expected: no deletion or duplication of accepted data, no runnable partial plan.

## 4. Performance (REQ-TS10)

- End-to-end capture → plan ack: median over ≥ 20 runs, practiced user (S2 ≤ 60 s)
- Parser latency Parse → review-ready: p50/p95 over ≥ 40 calls, cold vs cached (≤ 10 s / ≤ 25 s)
- Cold start to usable library UI (≤ 2 s, REQ-NF05)

## Evidence log

| Date | Item | Versions | Result | Notes |
|------|------|----------|--------|-------|
| | | | | |
