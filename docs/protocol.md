# Phone ↔ watch wire contract (protocol v1)

Normative contract between **WOD Capture** (Android, `android/`) and **WOD Coach**
(Connect IQ, `watch/`). Implements REQUIREMENTS.md §7 (REQ-SY01–SY16), REQ-DM14 and
REQ-WP04. JSON Schemas in `shared/*.schema.json` mirror this file; if the two
disagree, fix both in the same change.

## 0. Identities

| Item | Value |
|------|-------|
| Watch app UUID — production (sideload + store) | `0508f6cf6f0040e59d7ed9f432688c46` |
| Watch app UUID — beta (Connect IQ beta upload) | `a930ecf2337f4023a7b446ce221a73cf` |
| Protocol version `v` | `1` |
| Plan schema version `sv` | `1` |
| Result / session schema version `sv` | `1` |

Android talks to both UUIDs as separate targets (REQ-AW05). Hyphenated UUIDs are used
for all object IDs (`10000000-0000-4000-8000-000000000001` style, lower-case).

## 1. Encoding rules (both directions)

* A message is one **Dictionary/Map** (the Connect IQ SDK maps Android `Map`↔`Dictionary`,
  `List`↔`Array`). Allowed leaf types: Integer/Long, Float/Double, Boolean, String.
* **No nulls on the wire.** The Android SDK cannot serialize `null`; an absent value is an
  *omitted key*. "Missing" ≠ `0` (REQ-WE03): omit the key, never send 0 for unknown.
* Keys are short (below). Strings are UTF-8. Display strings are pre-rendered by the
  phone; the watch never parses notation or does load maths (D2, REQ-WS07).
* Timestamps `ts`, `t0`, `t1`, `at` are UTC epoch **seconds** (Integer). Event offsets
  inside a run (`ms`) are **milliseconds of monotonic run time since first work start**.
* **Size budget:** every encoded message ≤ 4096 bytes (REQ-SY06/SY15). The phone measures
  the compact-JSON UTF-8 length of the whole envelope and requires ≤ **3584** (512 B
  safety margin for SDK encoding overhead until REQ-TS07 measures the real factor). The
  watch rejects a received plan whose estimated size (same JSON-length rule, see
  `watch/source/WireSize.mc`) exceeds 4096 with `plan.ack{st:"error", rs:"size"}`.

## 2. Envelope

```json
{ "v": 1, "t": "plan.put", "id": "<message uuid>", "ts": 1793700000,
  "g": 1, "oid": "<object uuid>", "rev": 2, "p": { ... } }
```

| Key | Meaning |
|-----|---------|
| `v` | protocol version (Integer). Watch rejects `v != 1` with an error ack `rs:"version"` (REQ-SY07). |
| `t` | message type (§3). |
| `id` | message UUID. Retries reuse the exact `id` + body; changed content = new `id` + higher `rev` (REQ-SY02). |
| `ts` | send time, epoch seconds. |
| `g` | pairing generation (Integer ≥ 1, phone-owned, REQ-DM27/SY11). The watch stores the latest `g` it accepted; messages with a lower `g` are acked `st:"stale"`. Results/sessions arriving with an older `g` are **not acknowledged** by the phone: they are quarantined until the athlete chooses import or discard (REQ-DM27), so the watch keeps its copy and keeps them pending. |
| `oid` | object ID the message concerns (plan / result / session ID). Omitted for `hello`, `sync.req`, `sync.ack`. |
| `rev` | object revision (Integer ≥ 1). |
| `p` | payload Dictionary (per type). Omitted when empty. |

## 3. Message types

Phone → watch: `plan.put`, `plan.del`, `profile.put`, `result.ack`, `result.chunk.ack`,
`session.ack`, `sync.req`.
Watch → phone: `hello`, `plan.ack`, `profile.ack`, `result.put`, `result.chunk`,
`session.put`, `sync.ack`.
No acknowledgments of acknowledgments.

### 3.1 Acks (`plan.ack`, `profile.ack`, `result.ack`, `result.chunk.ack`, `session.ack`)

`oid`/`rev` = the acknowledged object. Payload:

| Key | Meaning |
|-----|---------|
| `mid` | original message `id` being acknowledged |
| `st`  | `"ok"` · `"already_applied"` · `"stale"` · `"conflict"` · `"error"` |
| `rs`  | reason when `st` is `error`/`conflict`/`stale`: `"version"`, `"size"`, `"invalid"`, `"capacity"`, `"conflict"`, `"generation"`; for `result.chunk.ack` also `"restart"` (no staging state — resend from chunk 0), `"busy"` (another transfer active) and `"hash"` (length/hash mismatch — restart) |
| `ix`  | (`result.chunk.ack` only) chunk index |

Receiver semantics (REQ-SY11/SY14): validate fully **before** committing; commit content
+ revision/dedupe state together; then ack. Same `oid`+`rev` with identical content →
`already_applied`; same `oid`+`rev` with different content → `conflict`; lower `rev`
than stored → `stale`; a tombstone (`plan.del`) at rev N blocks any `plan.put` with rev ≤ N.

### 3.2 `hello` (watch → phone, sent at every app start and after `sync.req`)

```json
{ "v":1, "t":"hello", "id":"…", "ts":…, "g":1,
  "p": { "pv":[1], "app":"0.1.0", "dev":"fr965",
         "inv":[["<planId>",2],["<planId>",1]],
         "del":[["<planId>",3]],
         "free":61440, "rc":3, "cap":["strength_sets","amrap", …] } }
```

`inv` = stored plans `[id, rev]`; `del` = tombstones; `free` = bytes the watch believes it
can still admit; `rc` = count of unacknowledged results+sessions; `cap` = supported
`mode` values. The phone resends only missing/newer plan revisions, never older ones.
`hello` never marks anything delivered.

### 3.3 `plan.put` — payload is a **WatchPlan** (§4). `oid` = plan id, `rev` = plan rev.
### 3.4 `plan.del` — no payload. Watch stores tombstone `[oid, rev]`; a run in progress keeps its frozen snapshot.
### 3.5 `profile.put` — payload = watch settings defaults (§6).
### 3.6 `sync.req` — no payload. Watch replies `sync.ack` (`p: {"rc": n}`) then drains its outbox one message at a time.
### 3.7 `result.put` — payload is a **Result** (§5) when the encoded envelope fits 4096 bytes.
### 3.8 `result.chunk` — larger results (REQ-SY15)

Payload: `{ "xid": "<transfer uuid>", "ix": 0, "n": 3, "len": 9012, "sha": "<hex sha256 of canonical JSON>", "d": "<base64 chunk>" }`, `oid`/`rev` = result id/rev. Canonical JSON = the Result payload serialised compactly with keys in sorted order. Phone stages chunks durably, acks each with `result.chunk.ack{ix}`, verifies length + hash + schema after the last, ingests, then sends `result.ack`. Only `result.ack` allows watch cleanup. Bounds: ≤ 64 chunks, ≤ 64 KiB decoded.
**MVP watch build:** results are kept small enough for `result.put` by design (compact arrays, §5); `result.chunk` is implemented on the phone and is optional on the watch until a result exceeds the budget.

### 3.9 `session.put` — payload is a **SessionSummary** (§5.4), `oid` = session id.

## 4. WatchPlan (payload of `plan.put`)

```json
{
  "sv": 1, "id": "<plan uuid>", "rev": 2, "wid": "<workout uuid>", "wr": 3,
  "ti": "Fran", "date": "2026-11-03", "tz": "Asia/Jerusalem",
  "u": "kg",
  "p": [ <Part>, ... ]
}
```

| Key | Meaning |
|-----|---------|
| `ti` | title, ≤ 28 chars |
| `date`/`tz` | scheduled local date + IANA zone (REQ-WN01) |
| `u` | athlete display unit for weights (`"kg"`/`"lb"`) — default for pickers only; every load carries its own unit |
| `p` | 1–6 parts, in run order, only parts with `runOnWatch = true` |

### 4.1 Part

| Key | Type | Meaning |
|-----|------|---------|
| `id` | String | stable part UUID |
| `l` | String | label, ≤ 3 chars ("A", "B", "WOD"→"W") |
| `n` | String | one-line summary ≤ 28 chars ("AMRAP 12:00", "Back squat 5x3") |
| `mode` | String | `for_time` · `amrap` · `emom` · `tabata` · `intervals` · `max_effort` · `strength_sets` · `build_to_max` · `manual` |
| `fb` | Boolean | `true` = reviewed fallback (REQ-DM18). The watch shows `"Manual timer: no automatic movement/interrupt/rest cues"` and uses `seg` as a plain clock plus `sc` manual entry. `mode` is then `manual`. |
| `ln` | Array<String> | ≤ 12 display lines, each ≤ 28 chars (movement list, cap, notes) |
| `seg` | Array<Segment> | timer program (§4.2). Omitted for `strength_sets` / `build_to_max` (they use `sets`). A build-to-max window countdown is v1; MVP sends no `seg` for it. |
| `trk` | String | metcon LAP meaning: `"round"` (LAP = +1 round) or `"mv"` (LAP = next movement). |
| `mv` | Array<String> | ordered movement display lines used by movement mode and the "current movement" line, each ≤ 28 chars |
| `rr` | Array<Integer> | reps per round for display/scoring, one entry per round of a fixed program (`[42,30,18]` for Fran) — or per round of a progressive AMRAP for as many rounds as were compiled. Omit when rounds have no rep-based counting rule. Rounds beyond the list have **no** rule unless `rrc` is set. |
| `rrc` | `true` | the last `rr` entry applies to every later round — a constant-round AMRAP compiled as one template (`rr:[30], rrc:true` for Cindy). Omitted otherwise. |
| `rl` | Array<String> | optional per-round label shown on the running screen, parallel to `rr` (`"21 reps"`). |
| `rnd` | Integer | planned number of rounds (for_time / intervals); omitted when open |
| `sets` | Array<Set> | strength / build-to-max sets (§4.3) |
| `att` | Integer | build_to_max: admitted attempt limit (≤ 30) |
| `sc` | Score | score rule (§4.4) |
| `ld` | Array<LoadItem> | loaded metcon items whose actual load is entered on the result screen (REQ-WE06): `{ "id": nodeId, "nm": "Thruster", "v": 43, "u": "kg", "c": 1 }` (`v` omitted when unresolved) |
| `ra` | Integer | prescribed rest after this part, seconds (counts as active time, REQ-WR08) |
| `lv` | String | chosen level label ("Rx", "Scaled", …) prefilled on the result screen |
| `lvs` | Array<String> | level choices offered on the watch toggle |

### 4.2 Segment

`{ "k": "work"|"rest"|"open", "d": 60, "lb": "WORK", "ix": 0, "s": 0, "ic": true }`

| Key | Meaning |
|-----|---------|
| `k` | `work` = countdown of `d` s; `rest` = countdown of `d` s; `open` = count up, ends only by Finish or by `cap` |
| `d` | duration seconds (> 0) for work/rest; omitted for open |
| `cap` | open only: cap seconds (omitted = uncapped) |
| `lb` | phase label ≤ 12 chars ("WORK", "REST", "MIN 3", "BLOCK 2") |
| `ix` | interval / round index (0-based) the segment belongs to, for "Round N/M" |
| `s` | station index into `mv` (EMOM stations, Tabata blocks) — omitted when n/a |
| `ic` | rest only: `true` = rest is on the running clock (`inClock`, counts in part score time) |

The engine (REQ-WM01) derives the current segment from accumulated running ms only.
Mode mapping the phone compiles:

* **for_time** → one `open` segment with optional `cap`. Rounds from `rr`/`rnd`.
  Multi-block "all for time" with in-clock rest (Open 24.3) still compiles to one `open`
  segment; the rest is shown in `ln`. Each-round-for-time (H5) compiles in MVP as a
  reviewed fallback (`fb:true`, `mode:"manual"`, one `open` segment, manual score text).
* **amrap** → one `work` segment of the duration.
* **emom** → `intervalCount` × `work` segments of `intervalSec`, `s` = station index,
  `lb` = `"MIN n"`; a station with `workSec` < interval becomes `work(workSec)` + `rest(remaining, ic:true)`.
* **tabata** → per block: `rounds` × (`work(workSec)`, `rest(restSec)`), no rest after the
  very last work; `restBetweenBlocksSec` rests between blocks.
* **intervals** → the explicit work/rest list; final rest omitted unless prescribed.
* **max_effort** → one `work` of the window (or `open` when only attempts are given).
* **manual** (fallback) → one `open` (count-up, optional `cap`) or one `work` (count-down).

### 4.3 Set (strength)

```json
{ "id": "<node uuid>", "nm": "Back squat", "mv": "back_squat",
  "r": 3, "rx": "3", "w": { "v": 100, "u": "kg", "c": 1 },
  "rest": 120, "ty": "working", "nt": "@80% → 100 kg" }
```

| Key | Meaning |
|-----|---------|
| `nm` | display name ≤ 20 chars; `mv` catalog id (optional) |
| `r` | target reps (Integer). **Omitted** when the target is unresolved (range, `max`) → watch opens rep entry before logging (REQ-WS02). |
| `rx` | rep display text ("3", "5-8", "max") |
| `w` | load `{v, u, c}`: `v` value (Number, per implement), `u` `"kg"`/`"lb"`, `c` implement count (1 barbell, 2 dumbbells). **Unresolved load** (missing 1RM, H9; runtime back-off): send `w` with `u` and `c` but **without `v`** → manual load entry in that unit is required before logging. Bodyweight-only sets carry `"bw": true` instead of `w`. The final set carries `rest: 0` unless rest is prescribed. |
| `rest` | rest after this set, seconds (omitted/0 = no rest; never after the final set unless prescribed) |
| `ty` | `warmup` · `working` · `top` · `backoff` · `amrap` |
| `nt` | note line ≤ 28 chars (shown under the target) |

### 4.4 Score

`{ "ty": "time", "rv": 1, "cnt": "reps", "man": false }`

| Key | Meaning |
|-----|---------|
| `ty` | `time` · `rounds_reps` · `reps` · `load` · `calories` · `distance` · `minutes_reps` · `completion` · `text` · `none` |
| `rv` | scoring-rule version (Integer, currently 1) |
| `cap` | for_time: if capped, the capped score type (`"reps"` when `rr` defines a counting rule, else `"text"` manual) |
| `du` | distance unit for `distance` scores (`m`, `km`, `mi`, `ft`, `yd`) |
| `man` | `true` = score is entered manually on the result screen (fallback / no counting rule) |
| `agg` | for multi-interval scores: `"sum"`, `"min"`, `"each"` — informational for display |

## 5. Result (payload of `result.put`, watch → phone)

One Result per part attempted. Compact by design.

```json
{
  "sv": 1, "id": "<result uuid>", "rr": 1, "sid": "<session uuid>",
  "pl": "<plan uuid>", "pr": 2, "wid": "<workout uuid>", "wr": 3, "pt": "<part uuid>",
  "st": "completed", "t0": 1793700000,
  "el": 512, "act": 480, "wk": 452,
  "sc": { "ty": "rounds_reps", "r": 5, "x": 7, "man": false, "rv": 1 },
  "lv": "Rx",
  "ev": [[0, "rd", 61200], [0, "rd", 125400]],
  "sets": [ ["<set node id>", 0, 3, 3, 100, "kg", 1, "done", 812300] ],
  "ld": [["<node id>", 43, "kg", 1]],
  "edt": {"cap": 600},
  "hr": [142, 178], "cal": 210,
  "fit": "saved", "dev": "fr965", "app": "0.1.0", "nt": "felt heavy"
}
```

| Key | Meaning |
|-----|---------|
| `rr` | result revision (watch always sends 1; phone edits create local revisions) |
| `st` | `completed` · `capped` · `stopped_early` · `interrupted` · `skipped` |
| `el`/`act`/`wk` | measured wall elapsed / active activity / part score (work + in-clock rest) seconds (REQ-WR08) |
| `sc` | typed score: `time`→`{"s":seconds}`; `rounds_reps`→`{"r","x"}`; `reps`/`calories`→`{"n"}`; `load`→`{"v","u"}`; `distance`→`{"v","u"}`; `minutes_reps`→`{"i","x"}`; `completion`→`{"ok":true}`; `text`→`{"tx"}`; plus `man` and `rv`. `cs`: corrected-score flag when the athlete edited a measured value (measured values stay in `el/act/wk`). **Pending score:** `{"ty":<intended type>,"rv":1,"pd":true}` with no value keys — the score was not entered on the watch (e.g. text scores, skipped entry) and must be completed on the phone; never read as 0. |
| `ev` | progress events `[segment-or-round index, kind, ms]`, kind: `rd` round, `mv` movement, `fin` finish, `cap` cap, `undo` compensating undo. `ms` (here and in `sets`) is **part running time**: milliseconds since the part's first work start, pauses excluded, in-part rests included. |
| `sets` | `[setId, setIndex, targetReps(-1 if none), actualReps, actualLoad(-1 if none/bodyweight), unit, implementCount, status, ms]`, status `done`·`partial`·`failed`·`skipped`. A skipped set has `actualReps = 0` and status `skipped`; the status, not the 0, is authoritative (missing ≠ zero). |
| `ld` | actual loads for loaded metcon items `[nodeId, value, unit, count]` |
| `edt` | timer parameters edited on the watch before start (REQ-WN03) |
| `hr` | `[avg, max]` for the part; omitted when unavailable (never session totals, REQ-WR10) |
| `cal` | activity calories for the part; omitted when unavailable |
| `fit` | recording outcome `off` · `saved` · `failed` · `unknown`. Parts finalized before the session ends report `unknown`; the SessionSummary `fit` is authoritative. |

**Size guarantee:** the watch lowers the per-part event admission limit (below 200 when
needed) so every finalized Result fits one `result.put`; `result.chunk` remains a phone
capability for future watch builds.

### 5.4 SessionSummary (payload of `session.put`)

```json
{ "sv":1, "id":"<session uuid>", "rev":1, "pl":"<plan uuid>", "pr":2,
  "e":[["<part uuid>","<result uuid>","completed"],["<part uuid>","","skipped"]],
  "t0":1793700000, "t1":1793702000, "el":2000, "act":1800,
  "hr":[140,182], "cal":540, "rso":"<previous session uuid>", "fit":"saved" }
```

`e[i][1]` is `""` when the part was skipped/unreached (no fabricated result). `rso` =
`restartOfSessionId`, omitted when not a restart.

## 6. Profile / settings (payload of `profile.put`)

```json
{ "prep": 10, "snd": true, "vib": true, "rec": true, "tch": true,
  "ws": 2.5, "wsf": 0.5, "u": "kg", "rest": 120, "tap": true }
```

Phone defaults; the watch keeps a local override mask per key (REQ-WG01). The profile is a
singleton object: `oid` is always `00000000-0000-4000-8000-000000000000`; `rev` increases
with every phone-side profile change and the watch ignores lower revisions.

## 7. Flow control (REQ-SY13)

One outstanding data message per target and direction; acks bypass the queue. Ack timeout
30 s; retries after 5, 15, 60 s (max 3 per drain), then wait for explicit Sync, `hello`, or
device-connected. Retries reuse message id + body. Errors `version`/`size`/`invalid`/
`conflict` stop automatic retry.

## 8. Bounds (REQ-WP04, REQ-SY06)

≤ 4096 B per message · ≤ 10 inbox plans · ≤ 6 parts · ≤ 12 lines/part · ≤ 28 chars/line ·
≤ 30 sets/part · ≤ 200 events/part · ≤ 2 h active · free text ≤ 256 UTF-8 bytes.
