# CrossfitWorkout — Requirements

> Living spec for a two-app solution: an **Android** app that turns a photo of the day's workout (gym whiteboard, screenshot or text) into a structured workout, and a **Garmin Connect IQ watch app** that runs it, lets the athlete enter **weights, reps and times**, records a Garmin activity and sends the results back to the phone.
> Created 2026-11-03 from research — no code exists yet, so every requirement is planned (`[ ]`).
> Revised after requirements review: the contracts in §§4.4–4.6, 6.12, 7.4 and 9.1 resolve the review findings. New product defaults remain **my call**, not owner-confirmed. Appendix I is research input, not verified implementation evidence; REQ-TS07 gates platform assumptions.
>
> **How to keep it up to date** (same convention as `garmin_rakia/REQUIREMENTS.md`):
> 1. Give every new user-visible behavior a `REQ-xxx` ID (next free number in its section). Never reuse an ID.
> 2. `Status`: `[x]` shipped / `[ ]` planned / `[_]` deprecated. Keep deprecated rows, don't delete them.
> 3. `Phase`: `MVP` (first usable release) / `v1` (complete product) / `Later` (idea, not committed).
> 4. Once code exists, append the source file(s) to the requirement text so tests and code stay traceable.
> 5. Items marked **(my call)** were decided by the author of this spec, not by the owner — challenge them freely. Section 13 lists the questions only the owner can answer.

**Working names (my call — rename freely):** Android app **WOD Capture**, watch app **WOD Coach**, repo `CrossfitWorkout`. "CrossFit" is a registered trademark of CrossFit, LLC — keep it out of store listing names and icons.

---

## Contents

0. [Summary](#0-summary)
1. [Intent, goals, non-goals, anti-goals](#1-intent-goals-non-goals-anti-goals)
2. [User stories](#2-user-stories)
3. [Architecture and key decisions](#3-architecture-and-key-decisions)
4. [Workout domain model (shared contract)](#4-workout-domain-model-shared-contract)
5. [Android app — WOD Capture](#5-android-app--wod-capture)
6. [Watch app — WOD Coach](#6-watch-app--wod-coach)
7. [Phone ↔ watch sync protocol](#7-phone--watch-sync-protocol)
8. [Non-functional requirements](#8-non-functional-requirements)
9. [Testing and quality](#9-testing-and-quality)
10. [Build, repo and tooling](#10-build-repo-and-tooling)
11. [Release phases](#11-release-phases)
12. [Assumptions (my calls)](#12-assumptions-my-calls)
13. [Open questions for the owner](#13-open-questions-for-the-owner)
14. [Risks](#14-risks)
- Appendices — CrossFit research: [A. Metcon formats](#appendix-a--metcon-conditioning-formats) · [B. Strength formats](#appendix-b--strength--weightlifting-formats) · [C. Scoring and divisions](#appendix-c--scoring-divisions-and-tiebreaks) · [D. Units and load notation](#appendix-d--units-and-load-notation) · [E. Movement library](#appendix-e--movement-library) · [F. Whiteboard glossary](#appendix-f--whiteboard-glossary-non-movement) · [G. Benchmark workouts](#appendix-g--benchmark-workouts-seed-library) · [H. Whiteboard → parse examples](#appendix-h--whiteboard-examples--expected-parse-parser-test-cases) · [I. Platform research notes](#appendix-i--platform-research-notes)
- [Change log](#change-log)

---

## 0. Summary

```
  Whiteboard photo · screenshot · pasted / shared text
                     │
                     ▼
┌──────────────────────── Android: WOD Capture ────────────────────────┐
│ Capture ─► Parse (Claude vision → schema-valid JSON; manual MVP     │
│            fallback) ─► Review & edit next to the photo              │
│         ─► Resolve loads (Rx/Scaled, M/F, %1RM, kg↔lb)               │
│         ─► Library · history · PRs (local DB)                        │
│         ─► Compile to a compact watch plan                           │
└───────────────▲─────────────────────────────────────┬────────────────┘
   results      │  Connect IQ Mobile SDK              │  workout plans
   (watch       │  via the Garmin Connect app (BLE)   │  (phone sendMessage)
   transmit)    │                                     ▼
┌───────────────┴──────────────── Watch: WOD Coach ─────────────────────┐
│ Inbox ─► Preview ─► Run each part (metcon timers / strength sets)    │
│       ─► Enter weight · reps · time ─► FIT activity (HR, laps)       │
│       ─► Outbox (results kept until the phone acknowledges them)     │
└───────────────────────────────┬──────────────────────────────────────┘
                                │  Garmin's own activity sync
                                ▼
                          Garmin Connect
```

The phone does all the "intelligence" (parsing, normalising, load maths, history); the watch executes a small precompiled plan and collects results. That split keeps the watch app inside Connect IQ memory limits and lets the parser improve without watch releases.

---

## 1. Intent, goals, non-goals, anti-goals

### 1.1 Why

At the box, the day's workout is on a whiteboard. Today the athlete has to memorise it or re-key it into a timer, and the weights actually lifted and the finish times end up on a scrap of paper — or nowhere. Garmin's native HIIT and Strength activities can't express most CrossFit sessions (a strength part *and* a metcon, rep schemes like 21-15-9, male/female loads like 95/65, percentages of a 1RM, time caps with "reps at cap"), and entering loads on the watch is clumsy.

The owner already built **WOD Timer** (`../garmin_wod_timer`): a Connect IQ watch app with AMRAP / For Time / EMOM / Tabata timers, cues, a round counter, a log, and HIIT FIT recording. This project adds the two missing halves: **getting the real workout onto the wrist from a photo**, and **logging what was actually done** (weights, reps, times) back to a history on the phone.

### 1.2 Goals

| ID | Goal |
|----|------|
| G1 | Photo, screenshot or text → structured workout on the watch in **≤ 60 s** including a quick review. |
| G2 | Represent **every common CrossFit format** — conditioning (Appendix A) and strength (Appendix B) — without losing anything needed to run and score it. |
| G3 | On the watch: see the workout, run the right timer, track rounds/sets, and **enter weights, reps and times** with **one press per round or set** mid-workout and only a few presses for the final score. |
| G4 | Results flow back to the phone: history per workout in MVP; benchmark PRs and measured lift rep-maxes feeding future loads in v1. |
| G5 | When recording is enabled and succeeds, each uninterrupted session creates one Garmin activity with available HR and laps; recording failures never discard workout results. |
| G6 | **Retain durably accepted results** across app crashes, exits and disconnected phones. Interrupted runs become incomplete results from the last valid checkpoint (§6.12). Device loss, uninstall, hardware/storage failure and explicit confirmed deletion are outside this guarantee. |

### 1.3 Non-goals

- iOS, Wear OS or non-Garmin watches.
- Programming or generating workouts; coaching advice.
- Social features, leaderboards, and gym-platform sync (SugarWOD, Wodify, Beyond the Whiteboard, PushPress) — candidates for *Later*.
- Automatic rep counting from the accelerometer (research item, *Later*).
- Multi-athlete or coach mode; a web dashboard; a cloud backend (the only network call in MVP is the parser).
- Writing Garmin's native strength "sets" into Garmin Connect (not exposed to Connect IQ apps — see REQ-WR06).

### 1.4 Anti-goals — it fails even if it "works"

- **A wrong parse that looks right.** Loads, reps or time caps silently mis-read. Every parsed workout is reviewed next to its photo, uncertain values are highlighted, and nothing reaches the watch without the athlete confirming.
- **Fiddly mid-WOD interaction.** More than one press to log a round or a set, small touch targets for chalky hands, or screens that need reading for more than a glance.
- **Lost accepted data.** A crash, accidental BACK or disconnected phone must not delete durably accepted events or finished results. Power-loss recovery uses the last valid checkpoint; unsaved input and the timer tail may be lost (§6.12).
- **Timer drift or late cues.** WOD Timer's rule stays: all timer state is derived from accumulated elapsed time, never from tick counting.
- **Photos leave the phone without the athlete knowing.**

### 1.5 Success criteria

| ID | Measure |
|----|---------|
| S1 | v1: on ≥30 consented real-board photo fixtures, ≥90% have zero critical-field errors; each remaining fixture needs ≤1 manual field/group correction. MVP uses 10 fixed text/image fixtures and requires correct deterministic validation, compilation and scoring for all supported cases; live-parser accuracy is reported, not claimed from text-only fixtures. Evaluation protocol: §9.1. |
| S2 | Median capture → "ready on watch" ≤ 60 s; parser p50 ≤ 10 s, p95 ≤ 25 s on Wi-Fi/LTE. |
| S3 | From the running screen, logging a round or a normal set with resolved numeric targets takes **1 press**, including durable acceptance. Final-score flow takes ≤6 presses for the fixed scenarios in §9.1; unresolved targets and corrections are measured separately. |
| S4 | 0 lost results across a 30-session soak that includes the phone being out of range and the app being exited mid-workout. |
| S5 | Battery use per hour on the FR965 comparable to (≤ 1.2 ×) the native HIIT activity with the same HR settings. |

---

## 2. User stories

1. As an athlete, I want to photograph the whiteboard before class and have the workout on my watch within a minute, so that I don't have to memorise it.
2. As an athlete, I want to share a screenshot (Instagram, gym website) or paste text the night before, so that I can prepare without a photo of the board.
3. As an athlete, I want a board with several parts (A. strength, B. metcon, C. accessory) to arrive as separate parts in order, so that the watch walks me through the whole class.
4. As an athlete, I want split loads like "95/65" turned into *my* load in *my* units, so that I never do the conversion in my head.
5. As an athlete, I want "5×3 @ 80 %" turned into an actual weight from my stored 1RM, rounded to my plates, so that I can load the bar straight away.
6. As an athlete, I want to choose Rx, Scaled or my own version before sending, so that the watch shows what I'll actually do.
7. As an athlete, I want the watch to show "Set 2/5 · 3 reps · 100 kg" during strength work, so that I know what's next at a glance.
8. As an athlete, I want to confirm a finished set with one press, or adjust the weight/reps in a couple of presses, so that logging doesn't break my focus.
9. As an athlete, I want a rest timer to start automatically after each set and buzz when it's time, so that I rest as prescribed.
10. As an athlete, I want one press per round in an AMRAP and to enter the extra reps at the end, so that my score is right.
11. As an athlete, I want For Time workouts to show the current round's reps (21 → 15 → 9) and the time cap, and to capture my finish time when I stop, so that I don't need anyone else timing me.
12. As an athlete, I want to enter reps completed if I hit the time cap, so that capped scores are recorded properly.
13. As an athlete, I want EMOMs to beep every interval and tell me which station I'm on, so that alternating EMOMs aren't confusing.
14. As an athlete, I want to record the load I actually used in a metcon (e.g. scaled thrusters), so that my history reflects reality.
15. As an athlete, I want to correct a finish time on the watch if I forgot to stop the clock, so that the result is still usable.
16. As an athlete, I want every session saved to Garmin Connect with heart rate and laps, so that my training load stays complete.
17. As an athlete, I want results to wait on the watch if my phone is in my bag and sync later, so that nothing is lost.
18. As an athlete, I want my result history, benchmark PRs and lift PRs on the phone, so that I can see progress and the app can calculate percentages.
19. As an athlete, I want the app to recognise benchmark names ("Fran", "Murph"), so that it fills in the standard workout and compares with my previous attempts.
20. As an athlete, I want to fix a misread board quickly in a structured editor with the photo beside it, so that a bad photo doesn't cost me the workout.
21. As an athlete, I want to capture and run workouts without internet (offline parsing or manual entry), so that a bad gym signal doesn't stop me.
22. As an athlete, I want the existing quick timers (AMRAP / For Time / EMOM / Tabata) available without a phone, so that one watch app covers everything.
23. As an athlete, I want to type in a workout myself — pick the format, add movements, reps and loads — when there is no board to photograph, or to log one I already did without the watch, so that every session ends up in my history.

---

## 3. Architecture and key decisions

### 3.1 Components

| Component | Tech | Responsibility |
|-----------|------|----------------|
| `android/` — **WOD Capture** | Kotlin, Jetpack Compose | Capture, parse, review/edit, load resolution, library/history/PRs, plan compilation, sync. System of record. |
| `watch/` — **WOD Coach** | Monkey C (Connect IQ `watch-app`) | Inbox of plans, timers and set tracking, data entry, cues, FIT recording, results outbox. |
| `shared/` | JSON Schema + JSON data | `workout.schema.json` (canonical model), `result.schema.json`, `watch-plan.schema.json` (wire format), `movements.json` (catalog, Appendix E), `benchmarks.json` (Appendix G), `fixtures/` (photos + expected JSON, Appendix H). |
| Parser service | Anthropic Messages API | Vision + structured outputs. Called directly from the phone with the athlete's own API key (MVP). |

### 3.2 Key decisions

| # | Decision | Rationale | Who decided |
|---|----------|-----------|-------------|
| D1 | Two apps: Android phone app + Garmin Connect IQ watch app. | Stated requirement. | Owner |
| D2 | **Phone thinks, watch executes.** The phone compiles each workout into a small "watch plan" with display strings and resolved loads; the watch never parses notation or does load maths. | Watch memory/CPU limits; Kotlin is easier to test; parser and catalog changes don't need watch releases. | My call |
| D3 | Primary parser = multimodal LLM (`claude-opus-5-5`, structured outputs) reading the image directly; fallback = on-device OCR + rule grammar. | Whiteboards are handwritten marker; classic OCR is weak on handwriting and still leaves the notation (21-15-9, 95/65, E2MOM) to be interpreted. One LLM call does both. | My call |
| D4 | Transport = Connect IQ Mobile SDK messaging (phone) ↔ `Toybox.Communications` (watch), through the Garmin Connect app. | The supported phone ↔ watch-app channel on Android. | Platform |
| D5 | **New** watch app (new app id) in this repo, seeded by copying WOD Timer's pure engine, alerts, picker and settings patterns and generalising them to multi-part plans. WOD Timer stays as it is until WOD Coach includes its quick timers (REQ-WQ01). | Leaves a working app untouched; avoids Monkey C barrel overhead for two apps. | My call (Q4) |
| D6 | Local-first on the phone (Room DB), no backend. | Personal app; privacy; nothing to operate. | My call |
| D7 | One Garmin activity per session (all parts run together), laps per round/set/interval. | One Garmin Connect entry per class. | My call |
| D8 | Loads are stored as written (value + unit + kind); conversion happens only for display and planning. | Avoids drift (95 lb is "43 kg" by convention, not 43.09). | My call |
| D9 | Monorepo `android/ watch/ shared/ docs/`. | The schema, catalog and fixtures are shared contracts. | My call |

### 3.3 Alternatives considered

| Alternative | Why not (for MVP) |
|-------------|-------------------|
| Push Garmin **native workouts** via Garmin's Training API, then use the native HIIT/Strength activity | Since the 2026-10-21 program revamp the self-serve **Personal** tier (individuals, own data) is pull-only and has **no Training API**; Training needs the business **Starter** tier (request form, push-only). Even then, native workouts can't express multi-part classes, M/F loads or capped scoring, and offer no custom weight/time entry. |
| Watch fetches plans itself with `makeWebRequest` (cloud endpoint or phone-local server) | HTTPS is mandatory (since SDK 2.3.0); plain-HTTP `localhost` to a server on the phone is undocumented and has broken across Garmin Connect app versions; a cloud HTTPS endpoint works but means running a backend (against D6). Kept as the fallback transport (REQ-SY09). |
| On-device OCR only | Weak on handwriting; still needs a full notation parser. Kept as the offline fallback (REQ-AX13). |
| On-device generative model (Gemini Nano via the ML Kit GenAI Prompt API) | Beta (1.0.0-beta5, 2026-10-26): image + text input (≤ 10 images, < 4,000 input tokens), structured output since 2026-07, foreground-only with a per-app quota, and only on recent flagships (Pixel 9/10/11, Galaxy S26, Z Fold7/8, OnePlus 13/15, Xiaomi 15/17 — not the Galaxy S25). Free, private and offline but lower quality → a v1 offline option (REQ-AX13), not the primary parser. |
| Gemini via Firebase AI Logic as the cloud parser | A real alternative: structured output works with image input, no API key inside the app (Firebase project), a no-cost tier, images up to 3072 × 3072. Not the default (D3, my call), but the parser interface (REQ-AX01) and the eval harness (REQ-AX12) let it be added and compared on the same fixtures. |
| Extend WOD Timer instead of a new app | Possible (Q4). Would keep one app id and its log, but mixes a standalone timer with a phone-dependent app in one release train. |

---

## 4. Workout domain model (shared contract)

The canonical model lives in `shared/workout.schema.json` and is used by the Android app (kotlinx.serialization), the parser's structured-output schema, the editor, the plan compiler and the fixtures. Appendix A/B list every format it must cover; the coverage matrix in §4.3 maps each to a representation, a watch timer and a score.

| ID | Requirement | Phase | Status |
|----|-------------|-------|--------|
| REQ-DM01 | Entities: **Workout** → ordered **Parts** → body tree, format and scoring; **Session**, **Result**, **AthleteProfile**, **Movement**, **Benchmark**. All schemas use integer `schemaVersion` starting at 1; every increment is a compatibility version, not semantic-version notation. Supported versions, migration and rejection rules are in §4.6. | MVP | [ ] |
| REQ-DM02 | **Workout**: UUID `id`, `revision` (positive integer), `schemaVersion`, title, scheduled local `date` (YYYY-MM-DD), `scheduleTimeZone` (IANA), source {kind, image refs, raw text, parser/model/prompt versions}, ordered `parts`, notes, tags, UTC created/updated timestamps. Parser diagnostics are a separate ParseResult wrapper (§4.4). Editing creates a new revision; prior run snapshots remain immutable. | MVP | [ ] |
| REQ-DM03 | **Part**: stable UUID `id`, label, kind (warmup/skill/strength/metcon/accessory/cooldown/other), format, ordered `body` (§4.4), scoring, optional cap, level overrides, chosen level, `restAfterSec`, notes and `runOnWatch` (false by default for warmup/cooldown). `body` is authoritative; no parallel editable `items[]` collection. | MVP | [ ] |
| REQ-DM04 | **Format** is a discriminated union: for_time {optional cap, eachRoundForTime}; amrap {durationSec}; emom {intervalSec, intervalCount, station node references, optional station workSec, remainder behavior}; tabata {workSec, restSec, rounds, block node references, restBetweenBlocksSec}; intervals {ordered work/rest segments referencing body nodes, inner mode}; death_by {intervalSec, startReps, increment, maxRounds}; max_effort {windowSec or attempts, measure}; strength_sets {set node references}; build_to_max {repsPerSet, windowSec, attemptLimit}; not_for_time; custom_segments. The authoritative body owns round/group counts, rep schemes and prescriptions; format parameters reference it rather than duplicate it. Death By and not-for-time dedicated execution is v1; partner hints preserve team instructions now but dedicated logging is Later. Per-item progression is explicit. Repeated segment templates are expanded and validated before compilation. | MVP model; dedicated death_by/not_for_time v1; partner logging Later | [ ] |
| REQ-DM05 | **Item** (movement prescription): `movementId` (catalog, nullable) + `name` + `rawText`; one measure of `reps` / `repSchemeIndex` / `max` / `calories` / `distance` {value, unit m·km·mi·ft·yd} / `durationSec`; `load` (REQ-DM06); `height` (box, in/cm); `target` (wall-ball height, ft/m); `implement` + `count` (single / double DB or KB); `side` (each / alternating / total); `modifiers` (unbroken, strict, kipping, butterfly, touch-and-go, deficit, weighted, partitioned); `role` (buy-in / main / cash-out); `notes`. **Body tree**: a part's body is an ordered list of elements — an Item, a Group (`rounds` ×N with its own optional rep scheme; `complex` = one set of several movements without dropping the bar; `superset` = A1/A2 alternating per set; `sequence` = "then"), or a Rest {sec, inClock}. Multi-block "all for time" workouts (Open 24.3: 5 rounds, 1:00 rest on the running clock, 5 more rounds; 25.2; 26.3) are therefore one part with one clock and one score. | MVP | [ ] |
| REQ-DM06 | **LoadSpec**: `kind` = absolute {value, unit} / split {male, female} / percent1RM {percent or range, ofMovementId} / percentBodyweight / bodyweight / rpe {value, rir?} / athleteChoice ("heavy", "moderate", "build") / none. Units: kg, lb, pood, plus `unspecified` (parser could not tell). **Set** (strength): reps (number, a range such as 5–8, `max`, or `1RM`; per side when `side` = each), load (absolute, or relative to the top set such as −10 %), restSec, tempo (4 digits = eccentric, bottom pause, concentric, top pause in seconds; `X` = explode — e.g. `31X1`), pauseSec, rpe / rir, `cluster` {miniSets, intraRestSec} (e.g. `3 × (2.2.2) @85 %, :15 between`), `setType` (warm-up / working / top / back-off / drop / AMRAP set), prescribed target; actual made/partial/missed status belongs to the Result. | MVP | [ ] |
| REQ-DM07 | **Scoring:** typed union, aggregation, cap and observation rules are defined in §4.5. Capped rep scoring requires an explicit counting rule; otherwise retain typed progress/manual score. Finishers precede capped results only within the same comparable prescription. Tiebreaks reference stable checkpoint node IDs and are recorded in v1. Persist scoring-rule version with results; do not infer new totals from later catalog changes. | MVP; tiebreaks v1 | [ ] |
| REQ-DM08 | **Levels**: each Part may carry alternative prescriptions per level (Rx, Rx+, Scaled, Foundations, Masters, custom), expressed as overrides of items/loads/reps. The athlete's choice (`chosenLevel`) drives the plan sent to the watch and is stored on the Result. Boards write levels as separate lines (`Scaled:`, `Sc:`, `Int:`, `Beg:`, crossfit.com's `Intermediate option:` / `Beginner option:`), substitution chains ("jumping pull-ups, then pull-ups, then chest-to-bar") or tiered pairs (`Rx 95/65 \| Sc 65/45`). | MVP | [ ] |
| REQ-DM09 | **Result**: UUID id, sessionId, workoutId/revision, planId/revision, partId, resultRevision, UTC startedAt, status (completed/capped/stopped_early/interrupted/skipped), measured elapsed/active/work seconds, separately editable typed score (§4.5), level, immutable prescription snapshot/reference, set observations, typed item/interval observations, labeled lap events, actual loads per stable item ID, nullable part HR/calories, source, device/app version and notes. Sets record prescribed and actual reps/load, per-hand/side semantics, done/partial/failed/skipped status and optional RPE/RIR; missing differs from zero. | MVP | [ ] |
| REQ-DM10 | **AthleteProfile**: default display units; `boardUnit` for unmarked loads; gear units per implement; split selection M/F/always ask; bodyweight with unit; bar weights; total-load increments per bar and optional discrete gear inventory; default strength rest; manual measured rep-max table per movement and rep count. Default total bar increment 1 kg or 2.5 lb (my call). v1 can derive measured rep-max candidates from eligible results; automatic replacement requires opt-in. | MVP; result-derived rep-maxes: v1 | [ ] |
| REQ-DM11 | **Load resolution** on the phone follows §4.4: explicit labels first; ambiguous choices require review; preserve original, prescribed and actual quantities. Percentage/bodyweight calculations use arithmetic conversion, selected reference and loadable-inventory rounding. Published equivalents are an explicit implement-specific prescription option, never a physical-mass conversion for PRs. Missing references/ranges prompt for a value or arrive as unresolved manual entry. Runtime-dependent back-off loads remain manual on the watch in MVP. | MVP | [ ] |
| REQ-DM12 | **Movement catalog** `shared/movements.json` (Appendix E): `id` (snake_case), display name, aliases/abbreviations, category, default measure (reps / cal / m / s), loadable (Y / N / optional), implement, standard Rx loads M/F where one exists, Health Connect segment mapping (v1). Unknown movements are allowed as free text — they never block a workout. | MVP | [ ] |
| REQ-DM13 | **Benchmark library** `shared/benchmarks.json` (Appendix G): name, aliases, canonical Workout JSON, score type, category (Girl / Hero / Open / other). | v1 | [ ] |
| REQ-DM14 | **Watch plan** (wire format, §7.3): compiled from Workout + Profile + chosen level; contains only what the watch needs — precomputed display lines, resolved loads in display units, a timer program per part, entry prompts, score type. | MVP | [ ] |

### 4.1 Load and unit rules

- Store what was written; convert for display. `95/65` stays `split{male: 95 lb, female: 65 lb}`; the plan shows `43 kg` for a male athlete in kg.
- **Male/female order is not fixed.** Gym boards usually write male first ("95/65"); crossfit.com often prints the female line first and is inconsistent with itself ("Barbara Ann 95/135", "Murph 14/20-lb vest"). For an unlabeled pair, suggest the higher value for male and require review confirmation (§4.4); this applies to loads, box heights, calories and targets. Explicit labels (♂/♀, M/W/F) win.
- **Ambiguous units** (no `lb` / `kg` / `#` on the board): the parser records `unspecified` plus its guess and confidence; review highlights it. Default guess (my call): `lb` when the pair matches a standard CrossFit pound pair (95/65, 135/95, 225/155, 53/35, 20/14 … — Appendix D), otherwise the profile's board unit.
- **Conversions use CrossFit's published equivalents** (Games Rulebook Appendix D, reproduced in Appendix D), which are not plain rounding: 185 lb = 83 kg and 125 lb = 56 kg (confirmed on the official Open 24.2 page), 95 lb = 43 kg. These equivalents apply to prescription conversion only when that mode is selected (REQ-DM19); values absent from the table use arithmetic and loadable-inventory rounding. Actual measured loads and percentage calculations always use physical-mass conversion.
- **Kettlebells**: the Rulebook maps 35 / 53 / 70 lb to 16 / 24 / 32 kg (1 / 1.5 / 2 pood); crossfit.com's own Eva page says 2 pood = 72 lb (33 kg), and a true pood is 16.38 kg. Use the Rulebook mapping and show the board's value verbatim in review.
- **`'` and `"` are overloaded**: `'` = minutes or feet ("AMRAP 20'" vs "30' lunge"), `"` = seconds or inches (":30" vs a 24" box). Resolved from context; the resolution goes to `assumptions`.

### 4.2 What "every format" means here

Appendix A (conditioning) and Appendix B (strength) are the research inventory. The phone preserves every entry as structured data or source notes. Within validated size/event limits, the watch executes a dedicated mode or an explicitly reviewed manual-timer fallback, and the phone stores a derived or labeled manual score. Unsupported instructions are never silently dropped; plans that cannot fit must be edited or split explicitly before sending (REQ-SY06).

### 4.3 Coverage matrix

Every row of [Appendix A](#appendix-a--metcon-conditioning-formats) and [Appendix B](#appendix-b--strength--weightlifting-formats) ends with a **Model → watch** column: the REQ-DM04/05 representation, the watch execution mode (§6.4 / §6.5) and the phase. A format without a dedicated watch mode may use the reviewed count-up/count-down and manual-result fallback in REQ-DM18. Size limits still apply; the matrix does not promise automatic execution or scoring for fallback modes.

---

### 4.4 Identity, representation and load contract

The following rules are normative. Defaults marked **my call** can be changed by the owner; until then they are the implementation baseline.

| ID | Requirement | Phase | Status |
|----|-------------|-------|--------|
| REQ-DM15 | **Stable identity:** workouts, parts and body nodes have UUIDs. A workout edit increments its revision, retaining node IDs for unchanged nodes and assigning new IDs to new nodes. A stable plan UUID identifies the workout plus target device/app/pairing generation; recompiling that lineage increments its plan revision. The same workout may have different target lineages. Runs capture a frozen plan including profile-derived loads, local timer edits, chosen level and actual display units. A Session UUID groups its part Results and optional FIT recording outcome. Repeated attempts always get new Session/Result IDs. | MVP | [ ] |
| REQ-DM16 | **Authoritative body:** ordered recursive nodes discriminated as item/group/rest. Every node has an ID; groups have kind (rounds/sequence/complex/superset), ordered children, repeat count and optional per-child rep schemes. Rest has seconds and inClock. Maximum depth 4; repeats 1–200; expanded observation/event bounds follow REQ-WP04. Scalar prescriptions may be explicit M/F alternatives for loads, reps, calories, height, target or distance. Level overrides target node IDs and may replace a subtree; dangling overrides block Send. Format metadata controls the clock; it does not contain a second copy of body items. | MVP | [ ] |
| REQ-DM17 | **ParseResult:** workout plus diagnostics keyed by stable node ID and field path; each diagnostic has confidence, source image/text reference, raw span, reason and optional alternatives. Top-level unparsed spans and assumptions are preserved. Unknown text never disappears during edit/reparse. Deterministic validation, not model confidence, determines executability. | MVP | [ ] |
| REQ-DM18 | **Fallback:** dedicated modes not yet supported, recurring interruptions (e.g. Kalsu), user-partitioned work, dynamic rest ratios and over-complex programs require explicit review acceptance of text plus count-up or count-down and a typed/manual score. Display “Manual timer: no automatic movement/interrupt/rest cues”. Preserve full instructions on the phone, show a preview of every watch line and block Send if required instructions cannot fit. No claim of automated progression/scoring in fallback. Unknown movement names alone do not require fallback. | MVP | [ ] |
| REQ-DM19 | **Load policy (my call):** default prescription conversion uses the implement-specific Appendix D equivalent when available, otherwise arithmetic; athlete may select arithmetic instead. Actual results and PR comparisons always use physical-mass conversion, retaining entered unit/value. For percentages/bodyweight, calculate with physical mass then select nearest loadable total, ties downward. Bar increments are total on both sides; never prescribe less than the selected bar weight or an absent discrete gear value without a warning and manual choice. DB/KB load is per implement; count is explicit. Percentage ranges require a selected percent; absent RM/BW or runtime-dependent top sets require manual load entry. Never use a guessed RM. | MVP | [ ] |
| REQ-DM20 | **Catalog identity:** distinct movements/variants that change execution, measure or PR eligibility have distinct stable IDs (e.g. hollow rock versus hold, strict versus push press). Aliases are true synonyms; display-name edits never rename IDs. Appendix E's slash-separated families must be expanded before generating the catalog. Unknown movements retain name plus local UUID. | MVP | [ ] |

For unlabeled split pairs, “higher = male” is a **review suggestion**, not an unconditional fact: show both original values, require confirmation of the mapping, and allow explicit labels to override it. A mixed-team pool cannot resolve from one athlete's profile. “Always ask” blocks resolved preview until a choice or explicit manual fallback is selected. Box/target units are length units and are not inferred from weight units.

### 4.5 Score and observation contract

| ID | Requirement | Phase | Status |
|----|-------------|-------|--------|
| REQ-DM21 | **Typed observations:** record node ID, round/set/interval index, value, unit, status and event timestamp. Supported values: reps, calories, distance, duration, load, completion and text. Set observations include target versus actual, per-side count and implement count. Machine calories and activity energy calories are separate fields. Missing/pending/skipped is never zero. | MVP | [ ] |
| REQ-DM22 | **Score union:** time {seconds}; rounds_reps {rounds, extraReps, ruleId}; reps {count}; load/totalLoad {value, unit}; volume {value, massUnit, convention}; calories {count}; distance {value, unit}; points {value, ruleId}; minutes_reps {completedIntervals, extraReps}; completion {completed}; text {text}; none. Derived scores carry scoring-rule version and observation references. Manual scores are labeled manual and never imply measured splits. Volume convention is actual completed reps × per-implement physical load × implement count; per-side reps count both sides only when both were recorded completed. | MVP | [ ] |
| REQ-DM23 | **Aggregation:** define ordered aggregation stages with grouping (interval/block/part/session) and operation (sum/min/max/average/each). Convert only compatible units; mixed measures require an explicit points rule. Missing observations make derived aggregates incomplete; user may supply a separately labeled manual aggregate. Tabata This! = sum over blocks of minimum over that block's 8 intervals. Failed sets never contribute a successful maximum; partial completed reps may contribute volume. CrossFit Total aggregation is v1. | MVP; multi-lift totals v1 | [ ] |
| REQ-DM24 | **Progress and cap:** fixed/progressive rounds store per-round rep totals when every item has a declared scoring conversion. Extra reps are 0 through current-round total minus 1; a completed round is normalized into the round count. No automatic addition of metres, seconds, calories and reps. For mixed rounds without a rule, record completed rounds plus typed partial observations or a manual score. At cap, freeze progress and mark capped unless a finish event timestamp is ≤ the cap; events are processed by timestamp, with finish winning an exact tie. Early stop is stopped_early, not capped or completed. | MVP | [ ] |
| REQ-DM25 | **Corrections:** preserve immutable measured timing separately from edited score and correction timestamp/reason. Undo creates a compensating event for the latest logical round/set/movement; it never erases elapsed time or rewrites an existing FIT lap. Before final Save, drafts can be corrected on watch; after final Save, phone edits are authoritative (REQ-SY12). Skipped parts and interrupted attempts remain visible but do not count as completed PRs. | MVP | [ ] |

Score fixtures: a rep-only ladder of two movements at 3, 6, 9 reps each has 6/12/18 reps per round, so two rounds plus five reps = 23 total reps. H4 also has lunges measured in distance: in MVP retain typed lunge progress and manual score; v1 benchmark rules may explicitly assign section credit. H7's four interval values 2+0, 2+5, missing, 1+0 do **not** produce a complete sum; missing is not replaced by 0. Timed rounds with excluded rest sum work splits only; activity time may still include that rest.

### 4.6 Versions, history and restoration

| ID | Requirement | Phase | Status |
|----|-------------|-------|--------|
| REQ-DM26 | **Migration:** each release declares readable schema/protocol versions. Integer schema increments require an explicit migration or a recoverable unsupported-version state. Migrate transactionally/copy-on-write, retain original data until validation succeeds, and never delete unsent results/checkpoints on failure. Unknown wire versions are rejected with an error acknowledgment in the minimal supported envelope; unknown local versions remain retained and block destructive operations. | MVP | [ ] |
| REQ-DM27 | **History:** deleting a workout archives its visible library entry; referenced immutable run snapshots survive. Deleting a phone result retains a minimal local tombstone/deduplication record so watch retries cannot resurrect it. Delete all requires confirmation, explains Garmin/export limitations, cancels jobs/queues and establishes a fresh pairing generation before accepting old device traffic. A watch with old pending results requires explicit import-or-discard choice on re-pairing; never auto-delete them. | MVP | [ ] |
| REQ-DM28 | **Explicit backup/restore:** export contains versioned workouts, revisions, sessions, results and asset manifest, excludes credentials, and includes photos only by explicit selection. Validate the full import before commit. Matching ID/revision/content is a no-op; conflicting content gets a preview and explicit keep-local/import-as-copy choice, never silent replacement. Unsupported versions report a recoverable error. Restore does not send plans or publish results automatically. | v1 | [ ] |

## 5. Android app — WOD Capture

### 5.1 Platform

| ID | Requirement | Phase | Status |
|----|-------------|-------|--------|
| REQ-AP01 | Kotlin + Jetpack Compose (Material 3), single activity; minSdk 29 (Android 10), targetSdk = latest stable (my call). | MVP | [ ] |
| REQ-AP02 | Layers: UI (Compose + ViewModels) → domain (pure Kotlin: parser interface, validation, load resolution, plan compiler, scoring, PR logic) → data (Room, DataStore, Connect IQ adapter, Anthropic client). The domain layer has no Android dependencies so it runs in plain JVM tests. | MVP | [ ] |
| REQ-AP03 | Runtime dependencies: Google Play services (document scanner, ML Kit) and the Garmin Connect app (watch messaging). If either is missing the app still works: gallery/text import and manual entry instead of the scanner; "no watch" mode instead of sync, with a clear banner. | MVP | [ ] |
| REQ-AP04 | Permissions limited to what features need: `INTERNET` (parser); `CAMERA` only if the CameraX fallback is used; notifications for sync status (optional). No location, contacts or storage permissions. The Connect IQ SDK (`com.garmin.connectiq:ciq-companion-app-sdk:2.4.0`, minSdk 21) merges the `<queries>` entry for the Garmin Connect package and its binding service automatically and needs no Bluetooth permissions (all traffic goes through Garmin Connect); `INTERNET` also covers its tethered simulator mode. | MVP | [ ] |
| REQ-AP05 | Offline-first: everything except cloud parsing works without a network connection. | MVP | [ ] |
| REQ-AP06 | **Connect IQ SDK hygiene**: initialise once with `autoUI` so Garmin Connect install/upgrade prompts appear (`GCM_NOT_INSTALLED`, `GCM_UPGRADE_NEEDED`, `SERVICE_ERROR` handled); call nothing before `onSdkReady`; send off the main thread (a `NetworkOnMainThreadException` is reported); map every `sendMessage` status (`FAILURE_MESSAGE_TOO_LARGE`, `FAILURE_DEVICE_NOT_CONNECTED`, …) to a visible delivery state (REQ-AW02). | MVP | [ ] |

### 5.2 Capture

| ID | Requirement | Phase | Status |
|----|-------------|-------|--------|
| REQ-AC01 | **Scan whiteboard**: launch Google's ML Kit Document Scanner (`play-services-mlkit-document-scanner`, GA 16.0.0): auto-capture with document and edge detection, auto-rotation, crop / rotate / reorder, optional filters and stain/finger removal (`SCANNER_MODE_FULL`), gallery import, page limit set to 4 (REQ-AC04), JPEG output. No CAMERA permission (it runs under Play services); needs ≥ 1.7 GB RAM and may download its model on first use. Its UI is fixed and how well it finds a whiteboard's edges is unverified, so a **Plain photo** option is always offered too: CameraX (or the system camera intent) plus a manual crop. | MVP | [ ] |
| REQ-AC02 | **Import image**: Android Photo Picker, and the system share sheet (`image/*`) so a screenshot from Instagram/WhatsApp/a gym website can be shared straight into the app. | MVP | [ ] |
| REQ-AC03 | **Text**: paste, or share `text/plain` (gym website, SugarWOD/Wodify copy, WhatsApp) → same parser, text-only request (cheaper, more accurate). | MVP | [ ] |
| REQ-AC04 | **Several images per workout** (board split across panels, strength and metcon on different boards): up to 4, ordered, sent together in one parse request. | MVP | [ ] |
| REQ-AC05 | **Pre-processing before upload**: apply EXIF rotation; downscale so the long edge is ≤ 2576 px and the image stays within the model's visual-token limit (≈ 3.75 MP); JPEG quality ≥ 85 with a single encode pass; strip EXIF (location, device). | MVP | [ ] |
| REQ-AC06 | Optional crop/redact before sending (e.g. hide a leaderboard with other members' names). | v1 | [ ] |
| REQ-AC07 | The original image is kept in app-private storage attached to the workout (viewable in review and history, deletable). In MVP its only upload is the consented parser request; v1 explicit exports may include photos only when selected. | MVP | [ ] |
| REQ-AC08 | **Manual builder**: create a workout from scratch (guided form, REQ-AC09), from a benchmark (v1) or by duplicating a past workout — the fallback when parsing fails. | MVP | [ ] |
| REQ-AC09 | **Add a manual workout** (owner request 2026-11-04): a **New workout** action next to Capture opens a guided form that needs no photo, text or network. Title and scheduled date (default today, phone time zone), then one or more parts. Each part picks a kind and a format with its parameters: For Time (optional cap, rounds, optional rep scheme such as 21-15-9), AMRAP (duration), EMOM / E#MOM (interval × count, one station per minute), Tabata (work / rest / rounds), Intervals (work / rest × rounds), Strength sets, Build to max, Max effort, or Custom (text plus a reviewed manual-timer fallback, REQ-DM18). Movements come from catalog autocomplete (Appendix E) or free text; each has a measure (reps, rep scheme, calories, distance, time) and an optional load — a single value or an explicit M/F pair, with unit — following REQ-DM05/DM06. Strength parts take sets × reps @ load (absolute or % 1RM) and rest. Format templates prefill a one-tap skeleton ("AMRAP 20", "EMOM 10", "For time 21-15-9", "5 × 5 strength"). Time fields accept m:ss / h:mm:ss. The form writes the canonical Workout (source `manual`, no parser diagnostics), runs the same validation (REQ-AX05) as you type, and finishes in Review (REQ-AE01–AE06): **Send to watch**, **Save only**, or **Save & log result**, which opens manual result entry (REQ-AL03) for a session already done without the watch. Nothing reaches the watch without that review. Manual workouts edit, duplicate, delete and re-send like parsed ones. **Weights and metcon are separate sections** (owner request 2026-11-05): the form shows a Weights section (Strength sets, Build to max) above a Metcon section (For time, AMRAP, EMOM, Tabata, Intervals, Max effort, Custom), each with its own templates and add action; a weights template and a metcon template combine into one day (lift = A, metcon = B), and Review and the library group parts the same way. **Lifts are chosen from a list** (owner request 2026-11-05): in the Weights section the lift is picked from a searchable, grouped list of loadable catalog movements (squats/presses/deadlifts, Olympic, dumbbell, kettlebell, weighted gymnastics, other), covering the loaded movements of crossfit.com/crossfit-movements; a weights lift without a catalog ID blocks Create. | MVP | [ ] |

### 5.3 Parsing

| ID | Requirement | Phase | Status |
|----|-------------|-------|--------|
| REQ-AX01 | `WorkoutParser` interface with implementations: **CloudLlmParser** (default), **OnDeviceLlmParser** (Gemini Nano where the phone supports it), **OfflineOcrParser** (on-device OCR + rule grammar), **TextRuleParser** (rule grammar for pasted text when offline). All return the canonical Workout plus per-field confidence, `unparsed` lines and `assumptions`. | MVP (cloud), v1 (offline) | [ ] |
| REQ-AX02 | **CloudLlmParser**: Anthropic Messages API, model `claude-opus-5-5` (configurable in settings), image blocks (or text) + instructions, response constrained to the workout JSON schema via structured outputs (`output_config.format`), adaptive thinking with an explicit effort level (start at `medium`, tune on the eval set). The system prompt holds the schema guidance, movement catalog ids + aliases (Appendix E), the notation glossary (Appendix F) and worked examples (Appendix H); it is kept byte-stable so prompt caching applies. | MVP | [ ] |
| REQ-AX03 | Confidence: every numeric field and movement mapping carries `high` / `medium` / `low`; anything the model had to guess is listed in `assumptions` (e.g. "assumed 95/65 means lb"); unreadable text goes to `unparsed` verbatim. | MVP | [ ] |
| REQ-AX04 | Check stop_reason before consuming structured output. Timeout/network/429/5xx errors get at most two retries per requested parse, exponential backoff and Retry-After where supplied. Refusal, invalid credentials, unsupported configuration and truncated/schema-invalid output stop with a specific error and manual edit/retry options; never pass partial output as a complete parse. Offline parse is offered only when the v1 provider is available. | MVP | [ ] |
| REQ-AX05 | Validate after parse and every edit. **Blocking:** invalid schema/references, missing or nonpositive required duration/interval, invalid bounds/rep scheme, cyclic or over-limit body, or an unexecutable selected mode. Block Send until repaired or explicitly converted to a valid fallback (§4.4). **Warnings:** plausible-but-unusual loads/caps, free-text movements, ambiguous units and unresolved manual loads. Show warnings and assumptions before confirmation; never silently clamp or discard source content. | MVP | [ ] |
| REQ-AX06 | **Benchmark recognition**: a part named like a benchmark (Appendix G, incl. aliases) gets an offer to apply the canonical definition, showing any differences from what the board says (boxes often modify benchmarks). | v1 | [ ] |
| REQ-AX07 | **Language**: whiteboard notation is English shorthand. Non-English text (e.g. Hebrew notes) is preserved as notes, never dropped. Full Hebrew support depends on Q2. | MVP | [ ] |
| REQ-AX08 | **Latency**: p50 ≤ 10 s, p95 ≤ 25 s from "Parse" to the review screen; progress indicator; cancellable. | MVP | [ ] |
| REQ-AX09 | **Cost visibility**: an image costs ⌈w/28⌉ × ⌈h/28⌉ visual tokens, capped at 4,784 at the 2576-px limit — ≈ $0.02 per full-size photo at Opus 5.5's $4 / MTok input price. A typical parse including output and thinking is estimated at **$0.05–0.10**; re-baseline with token counting on the fixtures. Settings shows the estimate and a running monthly total. | MVP (estimate), v1 (running total) | [ ] |
| REQ-AX10 | **Consent**: on first use, explain that the image/text is sent to Anthropic for parsing; a "Cloud parsing" toggle (off → offline parser or manual only). | MVP | [ ] |
| REQ-AX11 | **API key**: the athlete pastes their own Anthropic API key; stored encrypted with an Android Keystore key, excluded from backups, never logged, with a "Test key" button. Distributing the app to others requires a small proxy service so no key ships in the APK (*Later*). | MVP | [ ] |
| REQ-AX12 | **Eval harness**: `shared/fixtures/` holds photos/text with expected Workout JSON; a JVM test/CLI runs a parser over them and reports field-level accuracy (format, movements, reps, loads, caps, levels). Run before any prompt, schema or model change; ≥ 30 real board photos by v1. | MVP (harness + 10 fixtures), v1 (30) | [ ] |
| REQ-AX13 | **Offline parsing**: (a) on phones that support it, **OnDeviceLlmParser** — Gemini Nano through the ML Kit GenAI Prompt API (beta: image + structured output, < 4,000 input tokens, so a compact prompt without the full catalog, foreground-only); otherwise (b) **OfflineOcrParser** — ML Kit Text Recognition v2 (`com.google.mlkit:text-recognition`, on-device, Latin script, API 23+) + the rule grammar from Appendix F/H. ML Kit's documented handwriting support is weak ("only on handwritten text that is very well written and sparse"), so (b) mainly serves printed screenshots. Both flag results low-confidence. | v1 | [ ] |
| REQ-AX14 | **Re-parse with a hint**: the athlete types a hint ("line 3 is T2B", "loads are kg") and re-parses; the hint is sent with the image. | v1 | [ ] |
| REQ-AX15 | **Ambiguous shorthand** (Appendix F): PU (pull-up / push-up), SC (squat clean / scaled), SS, HS, SU (single-under / sit-up), BE (BikeErg / back extension), SB, DB (dumbbell / Death By), CO, R, C and "cluster" (squat-clean thruster vs cluster set) are resolved from context (neighbouring movements, whether a load is present, the section). Every resolution is listed in `assumptions` and shown in review as a tappable chip ("PU = pull-up ▾"). | MVP | [ ] |
| REQ-AX16 | **Capture now, parse later:** save a pending draft offline. Queue a cloud job only after explicit Parse intent and current cloud consent. Job states and race handling follow REQ-AX18. WorkManager retries eligible transient failures when connected; completing a draft never sends it to a watch without review. Notify if permission allows, otherwise show an in-app ready state. (my call) | MVP | [ ] |
| REQ-AX17 | **One capture, several workouts**: a weekly post (text or screenshot) is split into dated workouts, each reviewed and sent separately; each dated workout retains its schedule timezone and *Today* follows REQ-WN01; inbox ordering remains newest received first. (my call, Q3) | v1 | [ ] |
| REQ-AX18 | **Parse job lifecycle:** draft and job UUIDs plus draft revision; queued/running/ready/failed/cancelled states. At execution recheck cloud consent, source existence and job cancellation. A manual edit increments draft revision; late output is stored as a reviewable alternative and never overwrites it. Cancel/delete/disable-cloud prevents future sends; an already submitted request cannot be recalled and its late response is ignored. At most 3 total attempts per user-requested job across foreground and background; manual Retry creates a new job. Authentication/refusal/schema/config errors do not auto-requeue on connectivity changes. | MVP | [ ] |

### 5.4 Review and edit

| ID | Requirement | Phase | Status |
|----|-------------|-------|--------|
| REQ-AE01 | **Review screen**: the photo (zoomable) and the parsed workout together (split view on large phones, tabs on small). One card per part with a summary ("B · AMRAP 12:00"), items with reps and loads; low-confidence values highlighted; validation warnings at the top. | MVP | [ ] |
| REQ-AE02 | **Structured editor:** edit/reorder parts and recursive body items/groups/rests, format parameters, rep schemes, levels, units, load kinds, side/count, scoring and in-clock flags. Offer catalog autocomplete. Unsupported dedicated watch modes remain editable and use the explicitly selected fallback. Preserve untouched fields, stable IDs and source diagnostics; never flatten away nested semantics. Time entry supports m:ss and h:mm:ss. | MVP | [ ] |
| REQ-AE03 | **Level choice** per part (Rx / Scaled / Foundations / custom) — decides what the watch shows. | MVP | [ ] |
| REQ-AE04 | **Resolved preview**: exactly what the watch will display ("Thruster 43 kg (Rx 95 lb)", "Back squat 80 % → 100 kg"), with per-item override. | MVP | [ ] |
| REQ-AE05 | Primary action **Send to watch**; secondary **Save only**. Sending saves first. | MVP | [ ] |
| REQ-AE06 | Per-part "run on watch" toggle; warm-up and cool-down default off. | MVP | [ ] |

### 5.5 Library, history and PRs

| ID | Requirement | Phase | Status |
|----|-------------|-------|--------|
| REQ-AL01 | Workout list (newest first) with search by text, movement and benchmark; open → view, edit, re-send, duplicate, delete. | MVP | [ ] |
| REQ-AL02 | Results per workout and per part; result detail shows score, level, sets with loads, splits, interval scores, HR and calories. | MVP | [ ] |
| REQ-AL03 | Manual result entry/edit on the phone (for sessions done without the watch, or to fix one). | MVP | [ ] |
| REQ-AL04 | **Benchmark library** (Appendix G): browse, start (send to watch), see previous results for that benchmark. | v1 | [ ] |
| REQ-AL05 | **PRs (v1):** group benchmark attempts by canonical benchmark revision plus resolved prescription signature (level/division, movements, quantities, loads, cap and scoring rules). Modified prescriptions form separate variants. Only eligible completed measured attempts count; interrupted/stopped/skipped or incomplete aggregate scores do not. Per-lift rep-maxes use completed sets of the exact rep count, stable movement/variant and physical mass; no inferred 1RM from a multi-rep set. Corrections/deletions recompute PRs. Auto RM updates are opt-in and manual overrides remain authoritative. | v1 | [ ] |
| REQ-AL06 | Export/import everything as JSON (backup/restore) and results as CSV. | v1 | [ ] |

### 5.6 Watch connection (phone side)

| ID | Requirement | Phase | Status |
|----|-------------|-------|--------|
| REQ-AW01 | Device screen: Garmin devices known to Garmin Connect, connection status, whether WOD Coach is installed (`getApplicationInfo` → installed + version / not installed / not supported; detection of sideloaded builds is undocumented, so "unknown — try sending" is a valid state), and a way to open/install it. Remember the chosen device; support more than one. | MVP | [ ] |
| REQ-AW02 | Send one or more workouts; per-workout delivery state (queued / sent / on watch / failed + reason); automatic resend when the watch reconnects or the watch app says hello (REQ-SY05). | MVP | [ ] |
| REQ-AW03 | Ask the watch to open WOD Coach when a send is pending and the app isn't running: `openApplication` only shows a prompt on the watch (`PROMPT_SHOWN_ON_DEVICE`, `APP_IS_ALREADY_RUNNING`, …), so the phone tells the athlete to confirm it on the wrist. | MVP | [ ] |
| REQ-AW04 | Receive results whenever the app is running; on opening the app, request pending results from the watch (`sync.req`). Background receiving is not officially supported on Android (a developer reports foreground services killed within hours; the only working route is unofficial Garmin Connect broadcasts), so it is only a *Later* experiment. | MVP (foreground), Later (background) | [ ] |
| REQ-AW05 | Talk to both watch-app UUIDs — production and beta (Connect IQ beta builds are uploaded under an alternate manifest id); the device screen shows which one is in use. Sideloaded builds use the production id. | MVP | [ ] |

### 5.7 Profile and settings

| ID | Requirement | Phase | Status |
|----|-------------|-------|--------|
| REQ-AG01 | Profile (REQ-DM10): units, sex for split loads, bodyweight, barbells, smallest increment, rep maxes. | MVP (rep maxes: manual) | [ ] |
| REQ-AG02 | Workout defaults sent to the watch: strength rest, get-ready countdown, weight step sizes, sound/vibration preferences (the watch can override locally). | MVP | [ ] |
| REQ-AG03 | Parser settings: cloud on/off, model id, effort, API key, cost estimate. | MVP | [ ] |
| REQ-AG04 | Data: confirmed delete-all follows REQ-DM27 in MVP; explicit export/import follows REQ-DM28 in v1. | MVP deletion; v1 export/import | [ ] |

### 5.8 Integrations

| ID | Requirement | Phase | Status |
|----|-------------|-------|--------|
| REQ-AI01 | Optional Health Connect export: an `ExerciseSessionRecord` of type HIGH_INTENSITY_INTERVAL_TRAINING or STRENGTH_TRAINING with laps and `ExerciseSegment`s mapped from the catalog (Health Connect has squat, deadlift, bench press, shoulder press, pull-up, sit-up, burpee, kettlebell swing, lunge, plank … but no thruster, wall ball, box jump, clean, snatch, toes-to-bar or muscle-up — those map to WEIGHTLIFTING / OTHER_WORKOUT). Repetitions are stable (1.1.0); weight, set index and RPE exist only in the 1.2.0 alphas. Garmin Connect has its own Health Connect sync (which types it writes is unverified), so default off to avoid duplicate sessions. | Later | [ ] |
| REQ-AI02 | Share a result as an image or text (WhatsApp, Instagram story). | Later | [ ] |

---

## 6. Watch app — WOD Coach

### 6.1 Platform

| ID | Requirement | Phase | Status |
|----|-------------|-------|--------|
| REQ-WP01 | Connect IQ `watch-app` in Monkey C. **MVP targets the fr965 only** (my call, pending Q1). v1 adds the rest of WOD Timer's list, keeping `fr965` first as the default build/simulator target: fr970, fr57047mm, fr265, fr265s, fr255, fr955, epix2pro47mm, epix2pro51mm, fenix7, fenix7pro, fenix843mm, fenix847mm, venu3, vivoactive5. `minSdkVersion` **5.2.0**: every listed device reports API ≥ 5.2.0 in SDK 9.2.0 (fr970, fr57047mm, fenix 8: 6.0.2), which covers the HIIT sub-sports (4.1.6) and notifications (5.1.0); API-6-only calls stay behind `has` checks. | MVP (fr965), v1 (other devices) | [ ] |
| REQ-WP02 | Permissions: `Fit` (recording), `Communications` (phone messages), `Sensor` (enable the HR sensor for the session). v1 adds `FitContributor` (developer fields, REQ-WR03) and `Background` + `Notifications` (receive a plan while the app is closed, REQ-SY10). Language `eng`. | MVP | [ ] |
| REQ-WP03 | Architecture as in WOD Timer: only views draw; plan decoding/validation, the timer engine, scoring, result building and formatting are pure modules unit-tested in the simulator (`*Test.mc`); geometry is fractional so one layout fits 260 px MIP and 360–454 px AMOLED screens; no allocation or trig per frame (bake in `onLayout`). | MVP | [ ] |
| REQ-WP04 | **Bounded storage:** MVP accepts encoded plans ≤4 KiB and at most 10 inbox entries; a session has ≤6 parts, ≤30 sets and ≤200 accepted progress/observation events per part, and ≤2 hours active duration. Extra-set entry is disabled at the bound with a visible explanation. Store values in records ≤8 KiB; results may span records and are not assumed to fit 1 KiB. Reserve space for the full admitted run, checkpoint generations, indices and retained plan snapshots before Start. Budget all data within 128 KiB conservatively; actual supported capacity may be less than 50 results. Verify by REQ-TS07. Never evict unsent data; §6.12 defines full-storage behavior. | MVP | [ ] |
| REQ-WP05 | Without a phone, run previously synced plans and view local history; queue results for later. Quick timers are added in v1 (REQ-WQ01), not part of the MVP offline promise. | MVP | [ ] |

### 6.2 Inbox and navigation

| ID | Requirement | Phase | Status |
|----|-------------|-------|--------|
| REQ-WN01 | **Home:** Today (most recently received plan with date equal to today in its scheduleTimeZone), Workouts (inbox, newest received first, title/date/part count), History, Settings. Quick timer appears in v1. Show the plan timezone when it differs from the watch/phone local zone; traveling does not rewrite scheduled dates. | MVP | [ ] |
| REQ-WN02 | **Workout preview**: list of parts ("A · Back squat 5×3", "B · AMRAP 12"); selecting a part shows its lines (movements, reps, loads, cap) and **Start**. **Run all** runs every `runOnWatch` part in order with a transition screen between parts ("Next: B · AMRAP 12 — START when ready"). | MVP | [ ] |
| REQ-WN03 | Edit timer parameters before starting (duration, cap, interval, rounds, rest) with WOD Timer's setup menu + `ValuePicker` pattern; edits apply to this run only and are reported in the result. | MVP | [ ] |
| REQ-WN04 | Delete a plan from the inbox (confirmation). Plans older than 14 days are removed automatically unless they have unsent results. | MVP (delete), v1 (auto-expiry) | [ ] |
| REQ-WN05 | Glance (where the device supports glances): "Today: <title>" or "No workout". | v1 | [ ] |

### 6.3 Controls

Consistent with WOD Timer on 5-button watches (FR965 mapping: START → `onSelect`, UP → `onPreviousPage`, hold UP → `onMenu`, DOWN → `onNextPage`, BACK → `onBack`). Garmin's UX guidance reserves the top-right button for start/stop and says not to repurpose BACK; native activities nevertheless use BACK as LAP while recording, and so does WOD Timer — WOD Coach does the same **only while a timer is running**, and BACK means back everywhere else (my call). Touch is optional and can be turned off (sweaty/chalky hands, accidental taps).

| Context | START | BACK / LAP | UP / DOWN | Hold UP (menu) | Tap |
|---------|-------|------------|-----------|----------------|-----|
| Preview | start part | back | scroll | edit part / delete plan | select |
| Metcon running | pause | **+1 round** (or next movement in movement mode) | previous/next page (timer ↔ full movement list) | pause + menu | +1 round (if touch enabled) |
| Strength — set in progress | **save displayed set** → rest | save displayed set → rest | show set list | pause + edit/menu | save displayed set → rest |
| Confirm set / entry screens | accept | cancel / back | change value (hold = accelerate) | — | upper/lower third ± , middle accept |
| Rest timer | start next set now | **skip rest and start next set** | show next set | pause + Edit last set / Undo / menu | start-next control |
| Paused | resume | menu: Resume · Finish · Discard | — | menu | — |

| ID | Requirement | Phase | Status |
|----|-------------|-------|--------|
| REQ-WN06 | The normal resolved round/set action is a **single press** (table above); unresolved targets and edits require explicit entry. Destructive actions (discard, delete) need a second confirmation. Every function is reachable with buttons only (fr255 has no touchscreen). Two-button touch devices (venu3, vivoactive5: top = start/stop, bottom = back, hold bottom = menu) use tap = +1 round / set done and swipes for paging. | MVP (5-button), v1 (two-button and no-touch devices) | [ ] |
| REQ-WN07 | Leaving an active session intentionally asks Finish incomplete / Resume / Discard session (discard needs a second confirmation). On a clean exit, persist an incomplete checkpoint/result and attempt to save the current FIT activity. On crash/power loss, recover accepted workout data from the last valid checkpoint (§6.12); MVP does not resume the old timer or promise FIT recovery. Starting again creates a new session linked by `restartOfSessionId`. Suspend/inactive/resume behavior must be classified and tested on the target device before release (REQ-TS07); lifecycle events are not assumed equivalent. | MVP | [ ] |

### 6.4 Metcon execution

| ID | Requirement | Phase | Status |
|----|-------------|-------|--------|
| REQ-WM01 | **Engine**: generalise `WodEngine` — each part compiles to a segment program (prep / work / rest / open-ended / manual). State is a pure function of accumulated running time plus manual events (round, set done, finish); pauses excluded; get-ready countdown before the first segment. | MVP | [ ] |
| REQ-WM02 | For Time counts up with optional secondary cap countdown; planned round and reps visible. LAP logs progress; explicit Finish captures completion. At cap, apply timestamp tie-breaking (REQ-DM24), cue TIME CAP and collect capped progress using the configured counting rule or typed/manual score. Pause alone is not Finish. | MVP | [ ] |
| REQ-WM03 | **AMRAP**: count down; LAP = +1 round; ladders show the current round's reps. At the end, enter extra reps bounded by the current round only when its score-counting rule is defined (§4.5); otherwise collect typed partial progress or a labeled manual score. | MVP | [ ] |
| REQ-WM04 | **EMOM / E2MOM / E#MOM / alternating**: interval countdown, "Round N/M", the current interval's station and a preview of the next, cue at every interval. Optional per-interval entry for loaded strength EMOMs (load and/or reps), done during the remainder of the interval or at the end. | MVP (timer), v1 (per-interval entry) | [ ] |
| REQ-WM05 | **Tabata**: work/rest alternation (default 20 s / 10 s × 8); multi-movement Tabatas run as consecutive blocks ("Tabata Something Else" = 4 × 8); score entry at the end as prescribed (lowest round / total reps). | MVP | [ ] |
| REQ-WM06 | **Intervals**: arbitrary work/rest lists (e.g. 3 × [4:00 AMRAP, 2:00 rest]; 5 × 3:00 on / 1:00 off). During each rest the watch offers to enter that interval's score (skippable; can be completed at the end). | MVP | [ ] |
| REQ-WM07 | **Death By**: interval N targets N reps (or start + increment); shows the target; runs until the athlete stops or the max rounds; score = completed intervals + reps in the failed one. | v1 | [ ] |
| REQ-WM08 | **Max effort** in a window (max reps / calories / distance / load / time): timer for the window, then a single entry of the measure. | MVP | [ ] |
| REQ-WM09 | **Chipper / buy-in / cash-out**: shown as ordered stations; default tracking mode = **movement mode** (LAP = next movement) so each movement gets a split; multi-round workouts default to **round mode** (LAP = round). Mode switchable from the pause menu. | MVP | [ ] |
| REQ-WM10 | **Not for time / for quality**: checklist of rounds or sets, no main clock, optional rest timer. | v1 | [ ] |
| REQ-WM11 | **Running screen**: format label, large clock, round/interval counter or target, current movement line(s) with reps and load, secondary clock (cap / remaining / total), live HR coloured by zone, progress ring as in WOD Timer. Second page: the full movement list. | MVP | [ ] |
| REQ-WM12 | Pause / resume / finish early / discard (double confirmation). A finished-early metcon still goes to result entry. | MVP | [ ] |
| REQ-WM13 | Partner/team formats: run the timer as written; the athlete logs their own share or the team score. | Later | [ ] |

### 6.5 Strength execution

| ID | Requirement | Phase | Status |
|----|-------------|-------|--------|
| REQ-WS01 | MVP strength execution supports a flat ordered list of sets and build-to-max attempts, displaying movement, set index/total, target reps/load, prior actuals and notes. Complexes, supersets and clusters are represented in the model but use reviewed text-plus-timer/manual-result fallback in MVP; dedicated execution is v1. | MVP | [ ] |
| REQ-WS02 | **One-press normal set logging (my call):** LAP or START durably records displayed numeric reps/load and starts rest immediately; no routine confirmation. An unresolved load, rep range or max-reps target opens entry instead, and requires acceptance before it is logged. During rest, Edit last set permits reps/load/status correction; Undo removes the last logical completion and returns to the set, retaining elapsed timing. Partial reps are recorded explicitly, not as a successful full set. | MVP | [ ] |
| REQ-WS03 | Rest begins at the set-completion button event (entry time consumes rest if unresolved values must be entered). Cue at 10 s and 0; LAP skips rest and starts the next set; START also starts the next set. At zero, count overtime until explicit START/LAP. Editing a previous observation does not restart rest. No rest follows the final set unless explicitly prescribed in the body. | MVP | [ ] |
| REQ-WS04 | Add an extra set (copies the last one), skip a set, or end the exercise early — from the pause menu. | MVP | [ ] |
| REQ-WS05 | Build to a heavy single/triple: open sequence up to the admitted attempt/event limit, optional window countdown. Each set requires actual load (prefill from previous/planned, editable); numeric prefilled values can use one-press acceptance. Heaviest fully completed set of the prescribed rep count is the score; partial/failed sets remain logged. | MVP | [ ] |
| REQ-WS06 | **Supersets** (A1/A2) alternate movements set by set; **complexes** are one set with one load. | v1 | [ ] |
| REQ-WS07 | %-based sets arrive with loads already resolved by the phone; the watch never computes percentages. | MVP | [ ] |
| REQ-WS08 | Optional RPE (1–10) per set, off by default. | v1 | [ ] |

### 6.6 Data entry — weight, reps, time

| ID | Requirement | Phase | Status |
|----|-------------|-------|--------|
| REQ-WE01 | **Weight picker**: large number + unit; UP/DOWN change by the step (default 2.5 kg / 5 lb), a toggle switches to the fine step (0.5 kg / 1 lb); holding a button accelerates; range 0–500 kg (0–1100 lb), clamped (no wrap); touch: upper/lower third ±, middle accepts, swipe ±; START accepts, BACK cancels. Custom view in the style of WOD Timer's `ValuePicker` — the native `NumberPicker` is deprecated and absent on every target. Unit from the phone profile, falling back to `System.DeviceSettings.weightUnits`. | MVP | [ ] |
| REQ-WE02 | Quick values on the weight picker: planned, previous set, last session's load for the same movement (sent in the plan). | v1 | [ ] |
| REQ-WE03 | Reps/calories picker: integer 0–99,999. Distance: 0–999,999 m/ft/yd with step 1, or 0–999.999 km/mi with step 0.001; unit fixed from prescription unless changed explicitly. Hold accelerates; never wrap/clamp silently. Values beyond supported bounds require phone entry and are marked pending on watch. Missing, skipped and zero are distinct. | MVP | [ ] |
| REQ-WE04 | Time picker: m:ss or h:mm:ss, 0–23:59:59, 1 s resolution with optional 10 s step and hold acceleration. Corrected score time is separate from measured timing/FIT. Reject impossible values for the selected score policy, including completed times greater than the cap; offer stopped/capped status instead. | MVP | [ ] |
| REQ-WE05 | **Rounds + reps** entry: rounds prefilled from the LAP count, extra reps second. | MVP | [ ] |
| REQ-WE06 | **Result screen** after each part: score (editable), level toggle (Rx / Scaled / custom, prefilled from the phone's choice), loads used for loaded metcon items (prefilled with plan), Save / Discard. | MVP | [ ] |
| REQ-WE07 | Every entry works with buttons only and with touch only. | MVP | [ ] |

### 6.7 Cues

| ID | Requirement | Phase | Status |
|----|-------------|-------|--------|
| REQ-WC01 | Reuse WOD Timer's `Alerts`: 3-2-1 ticks before phase changes, distinct work/rest tones, finish jingle, vibration patterns, display wake on phase change; honour sound/vibration settings; fall back to built-in tones where custom tone profiles are unsupported. vivoactive5 has no speaker (`playTone` absent) → vibration only; Forerunners play every vibration profile at one duty cycle, so cues must differ by pulse count and length, not intensity. | MVP | [ ] |
| REQ-WC02 | Strength rest: cue at 10 s left and at 0. | MVP | [ ] |
| REQ-WC03 | Time-cap warnings at 1:00 and 0:10 remaining. | v1 | [ ] |

### 6.8 Activity recording

| ID | Requirement | Phase | Status |
|----|-------------|-------|--------|
| REQ-WR01 | One FIT activity per uninterrupted session when enabled and recording succeeds. Sport uses the first selected metcon in run order: its AMRAP/EMOM/Tabata sub-sport, otherwise HIIT. Strength-only uses strength training; no metcon/strength uses generic training. Proposed constants from Appendix I must be verified in REQ-TS07. Name is workout title truncated for the platform. Restart after interruption is a new session/activity, linked in phone history. | MVP | [ ] |
| REQ-WR02 | Laps: one per round (round mode), per movement (movement mode), per set, per interval; a lap at every part boundary. The get-ready countdown is not recorded. | MVP | [ ] |
| REQ-WR03 | Developer fields via `FitContributor` — lap: set index, reps, load (kg, float), movement code (catalog index); session: score text and level. Limits: 256 bytes of developer data per message, STRING not allowed on RECORD, field ids 0–255. Garmin Connect only shows developer fields for beta/store installs; sideloaded builds keep them in the FIT file only. | v1 | [ ] |
| REQ-WR04 | Recording can be turned off (setting, default on). | MVP | [ ] |
| REQ-WR05 | Live HR, calories and elapsed time come from `Activity.getActivityInfo()` (`currentHeartRate`, `averageHeartRate`, `maxHeartRate`, `calories`, `timerTime` — all nullable, shown as `--`); the HR sensor is enabled with `Sensor.setEnabledSensors`. | MVP | [ ] |
| REQ-WR06 | Garmin Connect's native strength set table (exercise, reps, weight per set) cannot be written by a Connect IQ app (confirmed: `FitContributor` only adds developer fields to session/lap/record messages; no API writes FIT set or exercise messages) — WOD Capture is the system of record for sets and loads. | — | [ ] |

### 6.9 Results and outbox

| ID | Requirement | Phase | Status |
|----|-------------|-------|--------|
| REQ-WO01 | Persist every accepted set/round/progress event and active checkpoint before acknowledging success in UI. At part completion persist a draft Result before summary; edits are durable draft revisions. Save finalizes the Result for delivery; interrupted sessions finalize as interrupted on recovery. Do not transmit an editable draft as a finalized result. | MVP | [ ] |
| REQ-WO02 | Transmit results to the phone when connected; mark them delivered only on the phone's ack; retry on app start, on `sync.req`, and after each new result. | MVP | [ ] |
| REQ-WO03 | Offline history retains up to 50 results subject to measured byte capacity. Prune oldest acknowledged results first; never prune unacknowledged results automatically. Active and unsent results retain their referenced plan snapshots even if the inbox plan is deleted. | MVP | [ ] |
| REQ-WO04 | Summary after each part and after the session: score, time, HR avg/max, calories. | MVP | [ ] |

### 6.10 Quick timer

| ID | Requirement | Phase | Status |
|----|-------------|-------|--------|
| REQ-WQ01 | Port WOD Timer's quick AMRAP / For Time / EMOM / Tabata timers (last-used settings, same defaults) so WOD Coach can fully replace WOD Timer; their results are sent to the phone as ad-hoc workouts. | v1 | [ ] |

### 6.11 Watch settings

| ID | Requirement | Phase | Status |
|----|-------------|-------|--------|
| REQ-WG01 | On-watch settings: prep countdown, sound, vibration, recording, touch, weight steps and tap-to-log. Phone profile supplies defaults; a local override mask wins per setting until Reset to phone defaults. Capture effective settings in the run snapshot. Profile updates during a run apply only to future runs, never alter an active timer. | MVP | [ ] |

---

### 6.12 Session state, clocks and recovery

**Baseline defaults (my call):** recover interruptions as incomplete results, not continuation of an old timer; record prescribed rest in FIT activity time; pause FIT during unprescribed waits and part/final-result entry. Restarting creates a separate activity when recording succeeds. These choices are distinct from recovering the data itself.

| ID | Requirement | Phase | Status |
|----|-------------|-------|--------|
| REQ-WR07 | **Session:** states preview → prep → work/prescribed_rest → part_result → transition → next work → final_result → finalized. Paused may interrupt work/rest; resume returns to the same logical state. User exit or recovery produces interrupted unless all selected parts were complete. Record one FIT session per uninterrupted run if enabled; choose the first selected metcon as main sport, otherwise strength, otherwise generic training. Recording create/start/save failure is visible; workout results remain usable and include recording outcome. | MVP | [ ] |
| REQ-WR08 | **Clock policy:** wall elapsed includes everything since first work start. Active activity time includes work and prescribed rest, excluding prep, explicit pause, part/final-result entry and waiting-to-start transitions. Optional entry during an active timed interval or strength rest does not pause activity time (REQ-WM14). Part score time includes work plus only inClock rests; strength rest stays out of per-set duration. Pause freezes logical work/rest deadlines. Inter-part prescribed rest starts after prior part Save, before transition waiting; prep is once per session. Corrected score time never changes measured times or FIT. | MVP | [ ] |
| REQ-WR09 | **FIT boundaries:** start at first work; stop recording timer for explicit pause, part-result entry and unprescribed transition waits; resume for next work or prescribed rest. Add one labeled lap per logical completion/automatic interval boundary, coalescing a coincident part boundary into the same lap. Discarding one part's score does not discard already recorded activity; mark the part skipped/discarded-from-scoring. Explicit Discard session deletes the local draft/recording only after confirmation; previously finalized phone results are not implicitly deleted. | MVP | [ ] |
| REQ-WR10 | **Metrics:** session HR/calories come from session-level recording data where available. Part HR requires per-part samples/time weighting and part calories require a valid cumulative counter delta; otherwise null, never repeated session totals. Keep machine-calorie scores separate. If recording is off or metric unavailable, show -- and retain null. | MVP | [ ] |
| REQ-WO05 | **Admission/full storage:** before Start reserve worst-case bytes and result slots for all selected parts plus session metadata and two checkpoint generations. Admission must also prove the worst-case part results fit REQ-SY15 decoded-size/chunk bounds and the final session summary fits REQ-SY16. Bound free-text fields to 256 UTF-8 bytes each and include that maximum in admission; never silently truncate entered text. Evict only oldest acknowledged history/unreferenced plans. If capacity cannot be reserved, block Start with Sync results/free acknowledged history; no implicit loss. At a running event limit, preserve all accepted data and offer Finish incomplete; do not wrap counters. Storage write failure freezes the logical timer and reports that the attempted event was not accepted; allow retry or exit retaining the prior checkpoint. | MVP | [ ] |
| REQ-WO06 | **Recovery:** checkpoints contain session/plan snapshot reference, phase, active/score times, committed events, part drafts and next-event sequence. Write a new validated generation before retiring the old; use a generation pointer that can be reconstructed by scanning valid records. On reopening, idempotently finalize only the unfinished part/session as interrupted, preserve already-finalized part IDs/content/status (including already-delivered results), retain known observations, mark unknown timer tail/metrics unavailable, and offer View/Send result or Start new attempt. Never add powered-off elapsed time to work. Clear checkpoint only after any interrupted part result and final session summary are both durable. If interruption occurs between parts, finalize the session summary with completed parts preserved and unreached parts skipped; do not fabricate an interrupted part. FIT availability is reported independently. | MVP | [ ] |
| REQ-WM14 | **Entry during timed intervals:** interval timers continue while optional entry is open. On boundary, persist draft fields, cue and show the next interval; unfinished scores remain pending for final review. Explicit pause freezes timers. Strength set targets with unresolved actuals require user acceptance, but prescribed rest uses original completion timestamp; if it expires in entry, show overtime on return. Touch controls must expose pause/finish/menu/undo equivalents to button actions. | MVP | [ ] |

Deterministic example: work 60 s → in-clock rest 20 s → work 40 s → part-result entry 15 s → inter-part prescribed rest 30 s → wait 10 s → work 50 s → pause 10 s → work 10 s. At final work end, wall elapsed is 245 s, active activity time 210 s, first part score 120 s, second part score 60 s. Final score-entry time does not increase activity/score time. Tests simulate these transitions independent of real time.

## 7. Phone ↔ watch sync protocol

### 7.1 Requirements

| ID | Requirement | Phase | Status |
|----|-------------|-------|--------|
| REQ-SY01 | Transport: Connect IQ Mobile SDK for Android (`ciq-companion-app-sdk` 2.4.0: `sendMessage` to the watch app, `registerForAppEvents` for messages from it — one companion app per watch app; the SDK caps a serialized message at 16 KB, the watch mailbox at 8 KB) ↔ `Toybox.Communications` on the watch (`registerForPhoneAppMessages` callback; `transmit` + `ConnectionListener` back). All traffic goes through the Garmin Connect app; BLE throughput is about 400–800 bytes/s. | MVP | [ ] |
| REQ-SY02 | Envelope: `v` integer protocol version, `t` type, UUID `id` (message ID only), UTC epoch-second `ts`, `objectId`, `revision` and `payload`. Retries reuse the exact message ID/body; changed logical object content gets a new message ID and higher object revision. Different chunks/control messages have distinct message IDs but retain the same immutable object revision; transport metadata is not an object edit. Object/revision rules and targeting are in §7.4. | MVP | [ ] |
| REQ-SY03 | Phone → watch: plan.put, plan.del, profile.put, result.ack, result.chunk.ack, session.ack, sync.req. Watch → phone: hello, plan.ack, profile.ack, result.put, result.chunk, session.put, sync.ack. plan.ack acknowledges both put/delete and includes original message ID, object ID, revision and status; profile.ack does likewise. hello is informational; sync.req receives sync.ack then a bounded result drain. No acknowledgments of acknowledgments. MVP has no plan.part; all plans are atomic bounded objects. Larger results use REQ-SY15; session finalization uses REQ-SY16. | MVP | [ ] |
| REQ-SY04 | At-least-once delivery with durable idempotent receivers (§7.4). A durable operation stays queued until its corresponding application acknowledgment; SDK send success is not delivery. One outstanding data operation per target; retry/backoff budgets are in §7.4. Android receives results only while its registered receiver is active in MVP; background delivery is not guaranteed. | MVP | [ ] |
| REQ-SY05 | **Watch app not running** when the phone sends: documented behaviour is that the message persists until the user opens the app, and every waiting message is delivered when the app registers its callback — so WOD Coach registers in `onStart`. Because a 2026 Garmin Connect for Android bug (CIQQA-4631) silently dropped Mobile SDK messages, the phone still treats every un-acked plan as pending and resends on the watch's `hello` (sent at every app start). | MVP | [ ] |
| REQ-SY06 | **Atomic MVP size policy:** encoded envelope plus payload ≤4 KiB (actual SDK encoding, not JSON character count), ≤6 parts, ≤12 display lines per part, ≤28 displayed characters per line and ≤30 sets per part. Reject over-limit plans before send and on watch with a precise reason. Do not truncate or reassemble chunks in MVP. Review may explicitly shorten display text while keeping the original on the phone, or split into separately reviewed workouts/sessions with separate activities. A fallback plan must also fit; if it cannot, Send remains blocked. | MVP | [ ] |
| REQ-SY07 | **Versioning**: the watch rejects unknown protocol majors with `plan.ack{error: "version"}`; the phone then asks the athlete to update the watch app. | MVP | [ ] |
| REQ-SY08 | **Simulator testing**: the Android app talks to the Connect IQ simulator in `TETHERED` mode (`adb forward tcp:7381 tcp:7381`, then Connection → Start in the simulator) for automated round-trip tests. Known quirk: in the ADB strategy sends have been reported as `SUCCESS` and then `FAILURE_UNKNOWN`, so tests assert on the watch's ack, not on the send status. | MVP | [ ] |
| REQ-SY09 | **Fallback transport** (only if messaging proves unreliable): the watch pulls pending plans with `makeWebRequest` from a small HTTPS endpoint (plain-HTTP localhost on the phone is undocumented and fragile). Needs a backend, so it's a last resort. | Later | [ ] |
| REQ-SY10 | **Background receipt**: with `Background.registerForPhoneAppMessageEvent` (API 3.2.0) a background service receives a plan while WOD Coach is closed, writes it to Storage and posts a notification "Workout received — open?" (`Notifications`, API 5.1.0). Must fit the 64 KB / 30 s background limits. Whether a message consumed in the background is still queued for the foreground app is undocumented — spike before building. | v1 | [ ] |

### 7.2 Typical flow

1. Athlete reviews a workout and taps **Send to watch** → phone stores `plan.put` in its outbox and tries to send.
2. If WOD Coach isn't running, the phone asks the watch to open it (REQ-AW03).
3. WOD Coach starts → sends `hello` with stored plan IDs/revisions and target identity → the phone sends missing/newer revisions, never older ones.
4. Watch validates, stores, replies `plan.ack{ok}` → phone shows "On watch".
5. After the workout, the watch stores the Result, transmits `result.put` → phone saves it and replies `result.ack` → the watch marks it delivered (history keeps a copy).

### 7.3 Watch plan — illustrative shape

Final field names live in `shared/watch-plan.schema.json`; keys are short to fit the message budget.

```json
{
  "sv": 1,
  "id": "10000000-0000-4000-8000-000000000001",
  "rev": 2,
  "wid": "20000000-0000-4000-8000-000000000001",
  "wr": 3,
  "ti": "Mixed gear strength",
  "date": "2026-11-03",
  "tz": "Asia/Hebron",
  "p": [
    {
      "id": "30000000-0000-4000-8000-000000000001",
      "l": "A",
      "n": "Back squat 1x3",
      "mode": "strength_sets",
      "fallback": false,
      "tm": {
        "rest": 120
      },
      "st": [
        {
          "id": "40000000-0000-4000-8000-000000000001",
          "mv": "back_squat",
          "r": 3,
          "w": {
            "v": 100,
            "u": "kg",
            "count": 1
          }
        }
      ],
      "sc": {
        "type": "load",
        "ruleVersion": 1
      }
    },
    {
      "id": "30000000-0000-4000-8000-000000000002",
      "l": "B",
      "n": "DB press 1x5",
      "mode": "strength_sets",
      "fallback": false,
      "tm": {
        "rest": 120
      },
      "st": [
        {
          "id": "40000000-0000-4000-8000-000000000002",
          "mv": "dumbbell_strict_press",
          "r": 5,
          "w": {
            "v": 25,
            "u": "lb",
            "count": 2,
            "perImplement": true
          }
        }
      ],
      "sc": {
        "type": "load",
        "ruleVersion": 1
      }
    }
  ]
}
```

---

### 7.4 Durable messaging, edits and device targeting

| ID | Requirement | Phase | Status |
|----|-------------|-------|--------|
| REQ-SY11 | **Target and ordering:** queue keys include pairing generation, device identifier and watch app UUID (production/beta are separate targets). Scope hello inventory to plan ID plus revision, supported protocol/capabilities, free bytes and result count. Ignore older plan/profile revisions; re-ack identical retries; same ID/revision with different content is conflict. Delete is a revisioned tombstone and delayed puts cannot resurrect it. A running session keeps its frozen snapshot while newer inbox revisions are stored. Re-pair/replacement never silently migrates a target queue. | MVP | [ ] |
| REQ-SY12 | **Result authority:** watch sends each finalized result once logically, retrying the same immutable ID/content until ack. Phone commits raw result plus deduplication record transactionally before ack. Later phone edits create authoritative local revisions; old watch retries are acknowledged without replacing edits or resurrecting tombstones. Corrections do not sync back to watch in MVP; watch history is explicitly “as recorded”. Separate runs of the same workout are never deduplicated by workout ID. | MVP | [ ] |
| REQ-SY13 | **Flow control:** one outstanding data message per target/direction; acknowledgments/control responses may bypass the data queue. Ack timeout 30 s; retry after 5, 15 and 60 s (at most 3 retries per drain), then retain pending state and wait for explicit Sync or a new connected/hello lifecycle event. Prevent simultaneous drains and rate-limit automatic restart to one per 5 min. Do not claim this prevents transport mailbox duplication; handlers deduplicate and tolerate it. Disconnection suspends retries. Wrong-version/size/invalid/conflict errors stop automatic retry until corrected. | MVP | [ ] |
| REQ-SY14 | **Atomic delivery:** validate envelope, version, size, schema and capacity before committing plan/profile/delete. Commit content and revision/dedupe state together, then ack with original message ID, object/revision and ok/already_applied/stale/error plus reason. Lost acknowledgments cause safe re-ack, not repeated mutation. Persist queue state across process death. Object revisions/tombstones provide dedupe even after bounded per-message caches are pruned. hello and sync requests never themselves mark a plan delivered. | MVP | [ ] |
| REQ-SY15 | **Result transfer:** every encoded message, including acknowledgments, is ≤4 KiB. A result fitting one message uses result.put. A larger result uses ordered result.chunk messages carrying result ID/revision, transfer ID, zero-based index/count, total byte length, whole-object SHA-256 and a chunk of its canonical UTF-8 JSON. Use base64 strings and size the entire encoded envelope, not raw chunk bytes. The phone durably stages chunks and returns result.chunk.ack; duplicate identical chunks are re-acked, differing content is a conflict. After all chunks arrive, verify length/hash/schema and transactionally ingest the result before result.ack. Only result.ack permits watch retention cleanup. Staging survives restart and expires after 7 days; after missing-state/error, restart with chunk zero. If final ack is lost, resend the last chunk; a committed result is re-acked from its dedupe record. Accept out-of-order chunks by index; bound each transfer to 64 KiB decoded/64 chunks and one active transfer per target. These are transport ceilings, not guarantees that watch storage can admit that size. | MVP | [ ] |
| REQ-SY16 | **Session completion:** independently persist and send session.put/session.ack with session ID/revision, ordered entries {partId, resultId (nullable when skipped/unreached), status}, start/end timestamps, measured clock totals, nullable session HR/calories, restart linkage and FIT outcome (off/saved/failed/unknown). Keep the summary ≤4 KiB. Generate it after finalization or recovery; completed part results stay immutable. Parts not reached are marked skipped, not fabricated as completed results. Phone may receive summary before parts and shows pending details until referenced parts arrive. Acknowledge only after durable commit; dedupe/revision rules match other durable objects. Session history is complete only when summary and all referenced finalized parts are received. | MVP | [ ] |

The §7.3 payload is illustrative only, not the envelope or a golden fixture. The implemented schema must include stable plan/workout/part/node IDs and revisions, per-load units (no reliance on a single global unit for mixed gear), schedule timezone, execution capability/fallback flags and explicit scoring rules. Use one consistent schema-version field; compiler fixtures are authoritative before either app implementation starts.

## 8. Non-functional requirements

| ID | Requirement | Phase | Status |
|----|-------------|-------|--------|
| REQ-NF01 | **Reliability:** success feedback follows durable event/checkpoint commit; outboxes and accepted data survive process restart. Use versioned checkpoint generations with validity checks and recovery to the last valid committed generation; never rely solely on onStop. Timer-only checkpoint target ≤5 s while active, measured on FR965; exact abrupt-power-loss durability depends on the storage API and must be reported by REQ-TS07. No one-second or recoverable-FIT guarantee is claimed. | MVP | [ ] |
| REQ-NF02 | **Timer accuracy**: drift-free (elapsed-time based, as WOD Timer); cues fire within the same second as the transition; the clock never shows "-0:01". | MVP | [ ] |
| REQ-NF03 | **Watch responsiveness**: a button press updates the screen within 150 ms; 1 Hz redraw while running; nothing allocated per frame. | MVP | [ ] |
| REQ-NF04 | **Battery**: no backlight held on; display woken only on phase changes; GPS off (indoor); comparable to the native HIIT activity (S5). | MVP | [ ] |
| REQ-NF05 | **Phone performance**: cold start ≤ 2 s on a mid-range phone; scrolling at 60 fps; the parse request off the main thread. | MVP | [ ] |
| REQ-NF06 | **Privacy:** intended flows are cloud parsing after consent, reviewed plans/profile to selected watches, results back to Android, and optional FIT recording/sync through Garmin. No analytics/ads/crash SDKs in MVP. Strip EXIF before cloud upload. Disable Android automatic backup/device-transfer of app data and secrets in MVP; v1 explicit exports exclude keys. Local deletions cancel related parse jobs and remove originals/derivatives when unreferenced; they do not delete Garmin activities or user-created exports. | MVP | [ ] |
| REQ-NF07 | **Security**: API key encrypted with the Android Keystore; no secrets in logs, crash output or the repo; `developer_key` never committed. | MVP | [ ] |
| REQ-NF08 | **Accessibility**: watch text at glanceable sizes; colour never the only signal (phases also labelled); phone screens have TalkBack labels and support font scaling. | MVP | [ ] |
| REQ-NF09 | **Localisation**: English UI; kg and lb; 24 h and 12 h clocks; non-English board text preserved as notes. | MVP | [ ] |
| REQ-NF10 | Shared schemas are versioned contracts. Changes that alter persisted/wire meaning bump the relevant integer version, migrate supported local data, and update fixtures together (§4.6). Keep schema version separate from object revision, app version and protocol version. | MVP | [ ] |

---

## 9. Testing and quality

| ID | Requirement | Phase | Status |
|----|-------------|-------|--------|
| REQ-TS01 | **Android unit tests** (JVM, no device): load resolution and unit tables, rep-scheme expansion, validation rules, scoring and PR logic, plan compiler (golden JSON per fixture), protocol encode/decode and dedupe. | MVP | [ ] |
| REQ-TS02 | **Parser eval** (REQ-AX12): field-level accuracy report; a run is required, and must not regress, before changing the prompt, schema or model. | MVP | [ ] |
| REQ-TS03 | Watch tests cover each dedicated mode in its release phase plus labeled fallbacks for unsupported modes: engine transitions, decoder, score/result creation, storage admission/recovery and retry/deduplication. Malformed or oversized input must not activate a partial plan. | MVP | [ ] |
| REQ-TS04 | **Integration**: Android ↔ Connect IQ simulator round-trip (send plan → ack → run → result → ack), scripted; plus a manual phone ↔ FR965 round-trip after every Garmin Connect app update (R1). | MVP | [ ] |
| REQ-TS05 | FR965 release checklist covers all modes committed to that release and explicit fallbacks, buttons-only/touch-only input, disconnects, intentional exit, forced process death, low battery, >60 min session, max admitted data, storage exhaustion, clock changes and lost/stale/duplicate messages. Expected outcomes follow §9.1. | MVP | [ ] |
| REQ-TS06 | **Visual checks** in the simulator: the fr965 (454 px AMOLED) in MVP; the 260 px MIP and 360–416 px AMOLED families when v1 adds them. | MVP (fr965), v1 (others) | [ ] |

---

### 9.1 Readiness gates and acceptance cases

| ID | Requirement | Phase | Status |
|----|-------------|-------|--------|
| REQ-TS07 | **Platform gate:** before committing to release, demonstrate real Android ↔ FR965 send/ack/result/ack with watch open and closed, reconnect, duplicate retry and both chosen app UUIDs. Measure actual encoded plan/result/storage sizes, checkpoint latency/durability, abrupt interruption and FIT start/stop/save behavior. Record SDK/firmware/Garmin Connect versions and failures in docs. Do not mark research claims shipped without evidence; unsupported guarantees require a visible requirement amendment, not silent omission. | MVP | [ ] |
| REQ-TS08 | **Shared contract gate:** before app features consume schemas, provide schema-valid canonical workout, compiled plan, finalized session/result and message fixtures for H1–H10. H2/H4/H6/H8 include explicit MVP fallback expectations where needed. Enforce stable IDs, revisions, mixed units, size bounds and all score union cases. Add negative fixtures for missing duration, zero intervals, unknown versions, dangling overrides, depth/size overflow and mixed-measure sums without rules. | MVP | [ ] |
| REQ-TS09 | **Failure matrix:** kill/restart after event acceptance, during checkpoint write, during part result, after result persist before send, and after phone commit before ack. Include full outbox, stale plan overwrite, deleted/edited result retry, wrong-target traffic, cancelled parse, late reparse after manual edit, import conflict and failed migration. Assert no deletion/duplication of previously accepted data and no runnable partial plan; import tests ship with v1. | MVP; import v1 | [ ] |
| REQ-TS10 | **Reproducible performance:** publish device/OS/network/model/prompt/fixture versions. End-to-end time runs from capture action to application-level plan ack, includes review by a practiced user, and reports median over ≥20 runs. Parser latency runs from Parse intent to review-ready, separates cold/cached calls and reports p50/p95 over ≥40 calls. Cold-start time ends at usable library UI. Battery compares ≥3 matched one-hour native/app sessions, same device/HR/display settings and no GPS. Thresholds remain S2/S5. | MVP | [ ] |
| REQ-TS11 | **Parser eval:** fixed ground truth reviewed by a human, critical-field exact-match after documented normalization, plus separate field accuracy and manual correction counts. A manual fix is one field or explicitly linked group edit, not retyping a workout. Run each photo ≥3 times; report per-run and worst-fixture results. Prompt/schema/model changes may not regress critical-field accuracy on the same set; S1's real-photo gate is v1. Text-only fixtures cannot substantiate photo accuracy. Pin a working provider/model/request and measure cost rather than treating Appendix I prices/model availability as guarantees. | MVP; S1 photo target v1 | [ ] |
| REQ-TS12 | **Interaction/timing scenarios:** one press from running screen accepts a normal resolved set or round; final flow ≤6 presses for (a) For Time measured finish with no corrections, (b) AMRAP prefilled rounds with zero extra reps, (c) prefilled strength summary. Count Finish/navigation/Save, not only picker acceptance. Optional interval/rest entry inherits the running timer state. Test 1,000 reps, 2,000 m, 0.125 km, exact cap tie, failed/partial set, undo, interval boundary during entry and the §6.12 clock example. All accepted-event timing uses monotonic elapsed time; wall-clock/timezone changes must not alter running durations. | MVP | [ ] |

Hardware execution, real-photo collection and live parser benchmarking are implementation/release work, not claims made by this document. The spec fixes their expected behavior and evidence requirements now.

## 10. Build, repo and tooling

| ID | Requirement | Phase | Status |
|----|-------------|-------|--------|
| REQ-BT01 | Layout: `android/` (Gradle Kotlin DSL), `watch/` (`manifest.xml`, `monkey.jungle` restricted to `source/` + `resources/` — the WOD Timer/Rakia gotcha with `.claude/worktrees`), `shared/`, `docs/`, this file at the root. | MVP | [ ] |
| REQ-BT02 | Root `taskfile.yml`: `android:build`, `android:test`, `watch:build`, `watch:test`, `watch:deploy-simulator`, `watch:deploy-watch` (USB + libmtp, as WOD Timer), `watch:package`, `eval` (parser eval). Watch scripts copied from WOD Timer (`build.sh`, `test.sh`, `run-simulator.sh`, `lib-sdk.sh`, `deploy-watch.sh`). | MVP | [ ] |
| REQ-BT03 | `.gitignore`: `bin/`, `build/`, `developer_key`, `local.properties`, keystores, any `*.key`; never commit API keys. | MVP | [ ] |
| REQ-BT04 | CI (GitHub Actions): Android unit tests + lint on every push; watch build when the SDK is available in CI (otherwise local). | v1 | [ ] |

---

## 11. Release phases

| Phase | Scope |
|-------|-------|
| **MVP** | Capture (scan, import, share, paste) · manual workout builder (REQ-AC09) · cloud parse + validation + review/edit · level choice + load resolution · send to watch · watch inbox, preview, run all parts · For Time, AMRAP, EMOM/E#MOM, Tabata, intervals, max effort, chipper, strength sets with rest, build-to-max · weight/reps/time entry · cues · one HIIT/strength FIT activity with laps · results outbox → phone history · manual result entry · eval harness with 10 fixtures. |
| **v1** | Offline parsing (Gemini Nano where the phone supports it, else OCR + rules) · re-parse with hint · benchmark library + recognition · PRs and auto 1RM table · Death By, not-for-time, supersets/complexes, per-interval strength entry, RPE · quick timers (replaces WOD Timer) · glance · FIT developer fields · watch-side background plan receipt (REQ-SY10) · export/import · 30 fixtures · CI. |
| **Later** | Partner/team logging · phone-side background result receiving (unofficial on Android, REQ-AW04) · Health Connect export · share cards · proxy service for distributing the app · rep-counting research (accelerometer up to 100 Hz; `:sensorLogger` writes raw IMU data into the FIT) · spike: hand off to Garmin's native workout player (FIT workout download + `System.exitTo`, unverified for HIIT/strength) · gym-platform imports. |

---

## 12. Assumptions (my calls)

Decided by the spec author so the work can move; each is easy to reverse.

1. Personal use first: one athlete, one phone; **the MVP watch build targets the FR965 only** (REQ-WP01); store publishing is optional and later (Q1).
2. The athlete supplies an Anthropic API key, accepts cloud parsing of board photos (consent screen) and roughly $0.05–0.10 per parse (Q1).
3. Default display unit **kg**; gear units can differ per implement (REQ-DM10); split pairs that match CrossFit standards are read as lb unless marked (Q2).
4. A new watch app (WOD Coach) rather than extending WOD Timer (D5, Q4).
5. One Garmin activity per session (D7).
6. Strength rest default 2:00; weight steps 2.5 kg / 5 lb (fine 0.5 kg / 1 lb); smallest plate increment 1 kg / 2.5 lb.
7. No backend in MVP; data lives on the phone (automatic backup disabled in MVP; explicit export in v1).
8. English board notation; other languages preserved as notes (Q2).
9. The main capture path is a photo of the board shortly before class, one day per capture; captures made offline queue for parsing (REQ-AX16, Q3).
10. BACK acts as LAP only while a timer is running (§6.3).
11. Parser default = Claude (`claude-opus-5-5`); Gemini via Firebase AI Logic and on-device Gemini Nano stay pluggable alternatives (§3.3, REQ-AX01).

---

## 13. Open questions for the owner

From the builder review (a fresh reader planning the build from this document). Ranked by impact. Each has a default this spec already assumes, so work can start — but the answer changes what gets built.

| # | Question | Why the answer matters | Default until answered |
|---|----------|------------------------|------------------------|
| Q1 | **Who will use this — only you, or others too (gym-mates, the Connect IQ store, Google Play)?** And is ≈ $0.05–0.10 per parse on your own Anthropic key fine? | Others ⇒ a parsing proxy (a backend, against D6), a Play privacy policy and data-safety form (photos go to a third party), store review, onboarding for non-technical users, and the full 15-device watch list (two-button venu3 / vivoactive5, the no-touch 512 KB fr255, 260 px MIP screens). Only you ⇒ none of that, and the MVP watch build stays FR965-only. | Personal use, your own key, MVP on the FR965 only (REQ-WP01, REQ-AX11). |
| Q2 | **What does your box's whiteboard look like?** English shorthand, Hebrew, or mixed? Which units is the gear labelled in (plates, dumbbells, kettlebells, med balls)? Can you share 10–30 real board photos? | Hebrew ⇒ Hebrew aliases in the catalog, Hebrew prompt examples and fixtures, right-to-left review editing, a Hebrew-capable offline route, and a watch decision (the phone translates to English display text, or the watch draws Hebrew with device fonts as Rakia does). Mixed gear units ⇒ per-implement units (now in REQ-DM10). Real photos ⇒ the eval set (S1, REQ-AX12) reflects your coach's notation instead of generic examples (Appendix H). | English notation, Hebrew kept as notes (REQ-AX07); gear units default kg; fixtures start from Appendix H. |
| Q3 | **How does the workout usually reach you?** A photo of the board just before class, or text / screenshots posted earlier (gym app, WhatsApp, Instagram)? One day or a whole week at a time? Is there reliable signal inside the box? | Decides the MVP's critical path (text import is cheaper and more accurate than photos), whether one capture must split into several dated workouts (REQ-AX17 → MVP), and whether offline parsing must move from v1 into MVP if reception at the gym is poor. | Photo before class is primary; one day per capture; "capture now, parse when back online" in MVP (REQ-AX16). |
| Q4 | **Should WOD Coach be a new watch app, or should WOD Timer grow into it? Is WOD Timer published or used by anyone else?** | Decides where the watch code lives (this repo vs `garmin_wod_timer`), which app id the phone talks to, whether the quick timers come free in MVP or are ported in v1 (REQ-WQ01), and whether WOD Timer's settings and 50-entry log need migrating — or its users get an update that adds permissions and a phone dependency. WOD Timer is one commit with no beta id, so this is cheap to decide now and expensive after either app ships. | A new app in this repo (D5); WOD Timer left untouched. |

---

### 13.1 Review defaults awaiting owner override

These are explicit **my call** decisions, not additional blockers for drafting or implementation:

| Decision | Baseline | Impact if changed |
|----------|----------|-------------------|
| Set logging | One press commits displayed numeric values; edit/undo afterward | Always-confirm mode would change S3 and controls |
| Interrupted runs | Recover accepted data as incomplete; restart as a new session | True continuation requires new timing/FIT recovery design |
| Capacity | Block a new run if its worst-case data cannot be retained | Evicting unsent results would weaken G6 and is not the baseline |
| Unsupported formats | Explicit labeled timer/manual-score fallback within size bounds | Dedicated support may move from v1/Later into MVP |
| Activity time | Include prescribed rest; exclude score entry and unprescribed waits | Changes §6.12 expected clocks and FIT tests |

## 14. Risks

| # | Risk | Impact | Mitigation |
|---|------|--------|------------|
| R1 | Connect IQ Mobile SDK delivery is unreliable — Garmin acknowledged a bug (CIQQA-4631, Aug–Sep 2026) where Garmin Connect for Android 5.27.3 / 5.28.1 silently dropped Mobile SDK messages in both directions; and Android kills background receivers (a developer's foreground service died within hours) | Plans or results don't arrive | Outboxes with acks + `hello` resend (REQ-SY04/05); one message at a time (REQ-SY06); round-trip check after every Garmin Connect update (REQ-TS04); fallback transport (REQ-SY09). |
| R2 | Parse accuracy on messy, glare-covered or abbreviated boards | Wrong workout | Mandatory review next to the photo; confidence highlighting; validation; eval set; re-parse with hint. |
| R3 | Watch memory/message limits for big multi-part days | Plan rejected | Phone-side compilation, size budget, explicit review/edit/split before send (REQ-SY06). |
| R4 | API cost or key management friction | Owner stops using cloud parse | Cost display; text path for pasted workouts; offline parser (v1). |
| R5 | Garmin Connect can't show our sets/loads natively | Loads only visible in WOD Capture | Our history is the system of record; developer fields (v1). |
| R6 | Trademark ("CrossFit") in names | Store rejection | Neutral names (WOD Capture / WOD Coach). |
| R7 | Sideloaded watch builds: messaging works in practice (the SDK addresses the app by manifest UUID; developers use sideloaded builds with it — not officially stated), but phone-side app settings and Garmin Connect's developer-field display need a beta/store install | Testing friction | Publish a private beta (alternate UUID, REQ-AW05) once settings/developer fields matter. Never reuse a UUID after a failed sideload (stale `GARMINDEVICE.XML` entries made an FR965 reject a sideload with −400). |

---

## Appendix A — Metcon (conditioning) formats

Research inventory (2026-11-03). The last column is the **coverage matrix** (§4.3): model representation → watch behaviour (phase).

Sources: crossfit.com glossary <https://www.crossfit.com/essentials/crossfit-terms-explained>; benchmark pages `https://www.crossfit.com/benchmark/<name>` (tabata-this, tabata-something-else, the-chief, fight-gone-bad, chelsea, hope); Death By <https://www.crossfit.com/workout/2012/01/08>; daily WODs <https://www.crossfit.com/workout>; official Open data `https://cms-api.crossfit.com/competition-workouts?phase=open&year=YYYY` (same content as games.crossfit.com); RBST glossary <https://www.rbstgearco.com/blogs/athlete-foundations/crossfit-glossary-terms-abbreviations>; partner formats <https://www.thewodgenerator.com/resources/complete-guide-partner-wods>.

| Format | Typical whiteboard | Parameters | Score | Model → watch (phase) |
|--------|--------------------|------------|-------|-----------------------|
| For Time (FT) | `For time:` · `FT` | items, reps, cap | time (lower is better); capped → reps | `for_time` → count-up + cap; LAP per round or movement (MVP) |
| Rep scheme | `21-15-9` · `50-40-30-20-10` · `10-9-…-1` · `15-12-9` | list of reps applied to every movement, in order, per round | time | `for_time.repScheme` → shows the current round's reps (MVP) |
| Rounds For Time (RFT) | `5 RFT:` · `5 rds FT` · `3 rounds for time of:` | rounds, round body, cap | time | `for_time.rounds` → round mode (MVP) |
| Rounds, each for time | `5 rounds, each for time … rest 3:00` | rounds, rest between | per-round times + total | `for_time.eachRoundForTime` + Rest → one split per round, rest countdown (MVP) |
| AMRAP | `AMRAP 20` · `20' AMRAP` · `20:00 AMRAP` | duration, body; optional progression ("+3 reps each round", 25.1) | rounds+reps (`5+12`); the Open converts to total reps | `amrap` (+ item `progression`) → countdown, LAP = round, extra-reps entry (MVP) |
| EMOM | `EMOM 10` · `E1MOM` · `OTM x10` · `Every 1:00 x10` | 60-s interval, rounds, per-interval work ("if you fall behind, keep going" — Chelsea) | completion, rounds completed, or load | `emom` → cue every interval, round N/M (MVP) |
| E#MOM | `E2MOM x6` · `E3MOM 15` · `Every 2:30 x6` · `E90s x8` | interval seconds, rounds or total time | as EMOM | `emom.intervalSec` (MVP) |
| Alternating / multi-station EMOM | `EMOM 16: 1) 15/12 cal row 2) 12 T2B 3) :40 max DU 4) rest` · `odd/even` | stations cycled; total = stations × cycles; partial-minute work windows | per station (reps / cal) or completion | `emom.stations[]` with `workSec` and a rest station → current + next station (MVP); per-station entry (v1) |
| Tabata | `Tabata squats` · `:20/:10 x8` | 8 × (20 s on / 10 s off) = 4:00 per movement; several in sequence with rest between | lowest interval (Tabata This!) **or** total reps (Tabata Something Else) | `tabata` → work/rest, one block per item (MVP) |
| Death By | `Death by pull-ups` (`DB` is ambiguous) | start at 1 rep, +1 each minute, until failure | minutes completed + reps in the failed minute ("20 minutes + 18 reps") | `death_by` → target per minute, stop on failure (v1) |
| Chipper | one long single pass (Filthy Fifty) | items, cap | time, or reps at cap | `for_time` + `chipper` hint → movement mode (MVP) |
| Ladder | `1-2-3-4…` · `ascending by 2` · `10 to 1` · `+1 each round` | start, step, end (or open-ended) | fixed ladder → time; open ladder → reps / last rung | fixed = `for_time.repScheme`; open = `amrap` + `progression` (MVP) |
| Intervals (work : rest) | `3 x 4:00 on / 2:00 off` · `5 cycles: 3:00 AMRAP, rest 1:00` (The Chief) | work s, rest s, rounds, inner format | per-interval result + sum | `intervals` → score entry during each rest (MVP) |
| Every X for Y, with remainder | `Every 4:00 x5: 400m run + max BJ` | interval, rounds, fixed work, then AMRAP / max for the rest of the interval | split times (sum or slowest) or reps | `emom.remainder` = amrap / max (MVP) |
| Buy-in / cash-out | `Buy-in: 50 DU` · `Cash-out: 1000m row` | before / after; inside the clock or not | usually inside the time; cash-out often unscored | item `role` + Rest `inClock` (MVP) |
| Max reps / calories in a window | `1:00 max cal bike` · `15-second air bike, rest 45 seconds …` (crossfit.com 261102: "Your score is total calories. Log each set.") | windows, rests | sum of sets | `max_effort` sets or `intervals` (MVP) |
| For distance / calories | `max-distance row 10:00` · `2K row FT` · `50 cal row` | target or window, machine | distance / calories / time | `max_effort` or `for_time` (MVP) |
| For quality / not for time | `3 rds for quality` · `NFT` | rounds, no clock | checkbox / unscored | `not_for_time` → checklist (v1) |
| Station rotation (Fight Gone Bad) | `3 rds, 1:00 each: WB/SDHP/BJ/PP/row; rest 1:00` | stations, station time, rest, rounds; continuous clock; "rotate" call | points = total reps / calories | `emom` with 5 stations + a rest station, points (MVP) |
| Partner / team | `Teams of 2` · `YGIG` · `split as needed` · `synchro` · `P1 rows / P2 AMRAP` · `one works, one holds` | team size, split rule (free / even / alternate every N reps or rounds), synchro, switch trigger | team time or total reps | `partner` hint; timer as written, athlete logs own share (Later) |
| Time cap | `TC 12` · `cap 12:00` · `(12')` | cap seconds, tiebreak checkpoints | finishers by time ahead of capped athletes by reps; ties → checkpoint time | `timeCapSec` + `tiebreak` (MVP; tiebreak laps v1) |
| Multi-part | `A) … B) … C)` · `then` · `rest 3:00, then` · "All for time" | ordered parts with their own format and score; inter-part rest | per part — unless "all for time": one clock, rest included (Open 24.3) | Parts (MVP); body tree with in-clock Rest (MVP) |

**Partner sub-types**: you-go-I-go (one works while the other rests) · split reps (shared pool, partitioned freely) · synchro (reps in unison; an out-of-sync rep is a no-rep for both) · cardio-target swap (one hits a cal/m target while the other builds AMRAP rounds; swap at the target) · alternating EMOM (switch stations every minute) · "one works, one holds" (the hold must be kept for reps to count — **UNVERIFIED** as a standard rule).

**Descriptors**: singlet / couplet / triplet = 1 / 2 / 3 movements; *Girl*, *Hero*, *Benchmark* = named reference workouts (Appendix G).

**Class structure**: crossfit.com's class flow is whiteboard brief (workout, stimulus, scaling) → warm-up → skill practice → workout prep → workout → cleanup → scores written on the whiteboard ("time…, how many reps…, or the weight on your bar") — <https://www.crossfit.com/essentials/what-to-expect-at-your-first-crossfit-class>. Gym boards commonly show WARM-UP · SKILL · STRENGTH (A) · METCON (B) · ACCESSORY / CASH-OUT (C); the parser maps these to `Part.kind`. Typical minute splits per block: **UNVERIFIED**.

## Appendix B — Strength / weightlifting formats

Sources: CrossFit Total <https://www.crossfit.com/benchmark/crossfit-total>; RBST glossary ("5x3 @ 75%", tempo "3-1-1-0"). Cluster, wave and RPE details are general strength-coaching convention (**UNVERIFIED** as a single standard).

| Format | Typical whiteboard | Per-set fields | Score | Model → watch (phase) |
|--------|--------------------|----------------|-------|-----------------------|
| Sets × reps | `Back squat 5x5` · `3x10/side` · crossfit.com style `Deadlift 5-5-5-5-5` (build across sets) | reps, load, rest | heaviest set, or per-set log | `strength_sets` → set → one-press save → rest; unresolved targets require entry (MVP) |
| % of 1RM | `5x3 @ 80%` · `@70–75% of BS 1RM` | % (or range) + reference lift / RM → load rounded to available plates | completion; load | `percent1RM`, resolved on the phone (MVP) |
| Build to a rep max | `Build to heavy single` · `Find 1RM/3RM/5RM` · `1-1-1-1-1-1-1` · `15:00 to find…` | attempt #, load, made / miss, time window | heaviest made | `build_to_max` → open sets + window countdown (MVP) |
| EMOM strength | `EMOM 10: 2 snatch @70%` · `E2MOM x5: 3 FS` · `E90s x8: 1 C&J` | interval, sets, reps, % | heaviest / completion | `emom` with loaded items → timer (MVP), per-interval load entry (v1) |
| Complex | `1 PC + 1 FS + 1 J` · `(2 HPC + 1 PJ) x5` | ordered sub-movements with reps; one load per set | heaviest complex | Group `complex` → one set, one load (v1) |
| Cluster set | `3 x (2.2.2) @85%, :15 between` | mini-sets, intra-set rest | load | `Set.cluster` → mini-rest countdown (v1); text/timer fallback in MVP |
| Wave | `3-2-1-3-2-1` · `5/3/1 @75/82/88%` | reps and load per set | heaviest | `strength_sets` with per-set reps / % (MVP) |
| Pause / tempo | `Pause BS 4x3 (3s)` · `Front Squat @31X1, 4x6, rest 3 min` | tempo digits = eccentric, bottom pause, concentric, top pause (s); X = explode | load | `Set.tempo` / `pauseSec`, shown as text (MVP) |
| RPE / RIR | `3x5 @RPE 8` · `top set @9 then -10%` · `RIR 2` | target and actual RPE / RIR (RPE 8 ≈ 2 RIR — **UNVERIFIED** as a standard scale) | load | `Set.rpe`; optional RPE entry (v1) |
| Superset | `A1. DB bench 3x10 / A2. strict PU 3x8, rest 90s after A2` | group id + order; rest after the group | per exercise | Group `superset` → alternate set by set (v1) |
| Accessory | `3 sets: 12 GHD HE, 20 hollow rocks, :30 side plank/side` | sets, reps / time / side, optional load | completion | `strength_sets` (MVP) or `not_for_time` (v1) |
| Back-off / drop sets | `then 2x5 @ -10%` | load relative to the top set | — | relative `Set.load` preserved; actual runtime-dependent load entered manually (MVP) |
| Max-effort test | CrossFit Total: back squat + shoulder press + deadlift, 3 attempts each, finish one lift before starting the next | attempts, made / miss | total = sum of the best lifts | `build_to_max` × 3 + `totalLoad` (v1) |
| Max reps | `max UB DU` · `max strict PU` | — | reps | `max_effort` (MVP) |

**Per-set record** (stored on the Result): exercise, set number, target reps, reps done (per side), load + unit, implement count (1 or 2 DB / KB), % + reference RM, RPE, RIR, tempo, pause s, rest s, made (bool), side.
**Aggregates**: max load, total (sum of maxes), volume (Σ reps × load), total reps, completion.

## Appendix C — Scoring, divisions and tiebreaks

Sources: CrossFit Games Rulebook <https://games.crossfit.com/rules>; official Open data (Appendix A sources); SugarWOD scoring options <https://www.sugarwod.com/2018/05/advanced-scoring-capablities/>.

- **Score types** (SugarWOD's list): time · rounds+reps · reps · load · distance (ft / in / m) · calories · points · checkbox · other (free text) · unscored; multi-set aggregation = sum, lowest, highest or average. Added for this app: capped For Time → reps (Open), minutes + reps (Death By), total load (CrossFit Total), volume.
- **Capped For Time**: the Open ranks every finisher by time, then capped athletes by total reps, then by tiebreak time. The whiteboard notation "CAP+N" (N = reps remaining) and the local-comp rule "cap + 1 s per missing rep" are **UNVERIFIED** conventions — the app stores reps completed and the cap, and can display either.
- **Open conversions**: 24.2 — 10 m on the rower = 1 rep, rounded down; if time expires mid-row the score is read when the monitor comes to rest. 25.1 — no credit for a partial 15-ft lunge section. (This app stores raw values; Open-style conversions only matter for benchmark comparison — v1.)
- **Tiebreaks (Open)**: a time recorded at defined checkpoints, used only for capped athletes — 24.1 after the 21s and the 15s; 24.3 after the first 5 rounds; 25.2 and 26.3 after each thruster set; 25.3 after each wall-walk set; 26.1 after each box-jump-over / step-over set; 26.2 after each snatch set; none for 24.2 and 25.1. On the watch a tiebreak is a lap at that checkpoint (v1).
- **Overall leaderboard** (reference only): placements are summed and the lowest total wins; overall ties go to the better single-workout placement, then the next; ties on a single workout are not broken; for capped team scores the cap counts as each capped athlete's time.
- **Divisions**: Rx / Rx'd = as prescribed · Rx+ = heavier / harder (gym convention, not official) · Scaled (official Open tier, ranked below all Rx) · Foundations (official Open tier 2024–2026, ranked below Scaled; loads are a "suggested starting point") · age groups Teens 14–15, 16–17 and Masters 35–39, 40–44, 45–49, 50–54, 55–59, 60–64, 65–69, 70+ (22 individual divisions + Team). Teens 16–17 and Masters 35–54 do the Individual Rx; Teens 14–15 and Masters 55+ get a modified Rx (26.1: 14/10-lb ball for 14–15; 9-ft target and "may step up" for 55+). Adaptive divisions are run by WheelWOD. A missing score = 0, ranked last.
- **How scaled versions are written**: a separate line (`Scaled:` / `Sc:` / `Intermediate option:` / `Beginner option:`), a substitution chain ("jumping pull-ups, then pull-ups, then chest-to-bar" — 25.2 Scaled), or tiered load pairs (`Rx 95/65 | Sc 65/45`).

## Appendix D — Units and load notation

| Token | Meaning | Examples |
|-------|---------|----------|
| lb, lbs, `#` · kg · pood, pd | load units | `95#` · `43/29kg` · `1.5/1 pd` |
| `a/b` | possible male / female pair (higher-for-male is a suggestion requiring review, §4.1) | `95/65` · `24/20"` · `20/14 lb to 10/9 ft` · `15/12 cal` |
| ♂ / ♀, M / W / F | explicit sex | `♀ 65 lb ♂ 95 lb` |
| `@` | at a load / % / RPE / tempo | `5x3 @80%` · `@RPE8` · `@31X1` |
| %, BW, ×BW | % of 1RM; bodyweight multiple | `1½-bodyweight deadlift` · `¾ BW HPS` |
| `x` / `×` | sets × reps, or rounds | `5x5` · `x3` · `3x(...)` |
| `a-b-c` | rep scheme | `21-15-9` |
| `+` | complex link; rounds+reps score; progression | `1 PC + 1 J` · `5+12` · `+3/rd` |
| m, km, K, mi, ft, `'`, yd | distance | `400m` · `5K` · `1 mi` · `80-foot` · `30'` |
| cal, C | machine calories (not interchangeable between rower, bike and ski) | `20/15 cal row` |
| in, `"`, cm | heights / deficits | `24/20"` · `60/50cm` |
| `12:00`, `12'`, min, `:30`, s | time | `TC 15` · `R 2:00 b/w rds` · `rest 1:1` |
| ea, /side, /arm, e/s, alt, UB, TnG | qualifiers | `10/side` · `alt DB snatch` · `30 UB DU` |

**Official conversions** (CrossFit Games Rulebook, Appendix D — "All weights will be released in pounds"; DB 15 / 25 lb from the official 24.1 Foundations listing). These are a published table, not arithmetic rounding:

| Implement | lb = kg |
|-----------|---------|
| Empty barbell | men 45 = 20 · women 35 = 15 |
| Barbell loads | 55 = 25 · 65 = 29 · 75 = 34 · 85 = 38 · 95 = 43 · 100 = 45 · 105 = 47 · 115 = 52 · 125 = 56 · 135 = 61 · 145 = 66 · 155 = 70 · 165 = 75 · 175 = 79 · 185 = 83 · 195 = 88 · 205 = 93 · 215 = 98 · 225 = 102 · 275 = 125 · 315 = 143 |
| Dumbbell | 10 = 5 · 15 = 7.5 · 20 = 10 · 25 = 12.5 · 35 = 15 · 50 = 22.5 · 70 = 32.5 |
| Kettlebell | 26 = 12 · 35 = 16 (1 pood) · 53 = 24 (1.5 pood) · 70 = 32 (2 pood) |
| Medicine ball | 10 = 4 · 14 = 6 · 20 = 9 |

Other: wall-ball targets 10 / 9 ft ≈ 3.05 / 2.74 m (computed); box 24 / 20 in ≈ 60 / 50 cm (common metric, **UNVERIFIED** as official). Team calorie pools such as "100/80 cal" are ambiguous for mixed teams — keep the raw value. Calorie conversions between machines are not defined (<https://www.wodball.com/en/crossfit/glossary>).

## Appendix E — Movement library

Seed for `shared/movements.json` (REQ-DM12). The catalog `id` is the snake_case of the name (e.g. `hang_power_clean`); the aliases column feeds the parser prompt, autocomplete and the offline grammar. `*` = ambiguous abbreviation (REQ-AX15, Appendix F). Measure = default unit. Loaded: Y / N / opt.
Sources: <https://www.crossfit.com/crossfit-movements> (canonical names); Open 2024–26 movement standards; crossfit.com WOD 261103 (box pike push-up, inchworm); the glossaries in Appendix F.

**E.1 Olympic lifting** — all measured in reps, all loaded.

| Movement | Abbreviations / aliases |
|----------|-------------------------|
| Snatch (squat / full) | SN, Sn, SQ SN, SS* |
| Power snatch | PS, PSN |
| Hang snatch | HSN, HS* |
| Hang power snatch | HPS |
| Hang squat snatch | HSS |
| Muscle snatch | MSN |
| Split snatch | — |
| Snatch balance | SnB, SB* |
| Overhead squat | OHS |
| Snatch pull / snatch-grip deadlift | SN pull, SGDL |
| Sots press | — |
| Clean (squat) | CLN, CL, SQ CL, SC* |
| Power clean | PC |
| Hang clean | HC |
| Hang power clean | HPC |
| Hang squat clean | HSC |
| Muscle clean | MC |
| Split clean | — |
| Clean pull | CP |
| Clean and jerk | C&J, CJ |
| Power clean and push / split jerk | PC&J |
| Squat-clean thruster | cluster* |
| Thruster | TH, THR |
| Ground-to-overhead | GTOH, G2OH |
| Shoulder-to-overhead | STOH, S2OH |
| Push press | PP |
| Push jerk | PJ |
| Split jerk | SJ |
| Squat jerk | — |
| Behind-the-neck jerk / press | BTN |

**E.2 Barbell strength**

| Movement | Abbreviations / aliases | Measure | Loaded |
|----------|-------------------------|---------|--------|
| Back squat | BS | reps | Y |
| Front squat | FS | reps | Y |
| Box squat | — | reps | Y |
| Zercher squat | — | reps | Y |
| Deadlift | DL | reps | Y |
| Sumo deadlift | SDL | reps | Y |
| Romanian / stiff-leg deadlift | RDL, SLDL | reps | Y |
| Sumo deadlift high pull | SDHP | reps | Y |
| Good morning | GM | reps | Y |
| Shoulder (strict) press | SP, MP, OHP | reps | Y |
| Bench press | BP | reps | Y |
| Bent-over row | BOR, Pendlay | reps | Y |
| Hip thrust | HT | reps | Y |
| Front-rack lunge | FRL | ft / m / reps | Y |
| Back-rack lunge | BRL | ft / m / reps | Y |
| Overhead lunge | OHL | ft / m / reps | Y |
| Bulgarian split squat | BSS | reps / side | opt |
| Box step-up | BSU | reps | opt |

**E.3 Gymnastics and bodyweight**

| Movement | Abbreviations / aliases | Measure | Loaded |
|----------|-------------------------|---------|--------|
| Air squat | AS, squat | reps | N |
| Pull-up (kipping) | PU*, KPU | reps | N |
| Strict pull-up | SPU | reps | opt |
| Butterfly pull-up | BF | reps | N |
| Chin-up | CU | reps | opt |
| Chest-to-bar pull-up | C2B, CTB | reps | N |
| Jumping pull-up | JPU | reps | N |
| Chin-over-bar pull-up | COB | reps | N |
| Bar muscle-up | BMU | reps | N |
| Ring muscle-up | RMU, MU | reps | N |
| Strict muscle-up | SMU | reps | N |
| Toes-to-bar | T2B, TTB | reps | N |
| Knees-to-elbows | K2E, KTE | reps | N |
| Hanging knee raise | HKR | reps | N |
| Push-up | PU*, push-up | reps | N |
| Hand-release push-up | HRPU | reps | N |
| Ring push-up | — | reps | N |
| Ring dip | RD | reps | N |
| Bar dip | dip | reps | opt |
| Ring row | RR | reps | N |
| Handstand push-up (kipping) | HSPU, KHSPU | reps | N |
| Strict handstand push-up | SHSPU | reps | N |
| Deficit handstand push-up | DHSPU (+ in / cm) | reps | N |
| Wall-facing / chest-to-wall HSPU | WFHSPU, CTW | reps | N |
| Box pike push-up | — | reps | N |
| Handstand walk | HSW | ft / m | N |
| Handstand hold | HSH | s | N |
| Wall walk | WW | reps | N |
| Rope climb (15 ft) | RC | reps | N |
| Legless rope climb | LRC | reps | N |
| Pistol | SLS | reps | opt |
| L-sit | — | s | N |
| GHD sit-up | GHDSU | reps | N |
| AbMat sit-up | sit-up, SU* | reps | N |
| Hollow rock / hollow hold | — | reps / s | N |
| V-up | — | reps | N |
| GHD hip / back / hip-and-back extension | HE, BE*, HBE | reps | opt |
| Burpee | BRP | reps | N |
| Bar-facing burpee | BFB | reps | N |
| Burpee over the bar (lateral) | BOB, LBOB | reps | N |
| Lateral burpee over the dumbbell | LBO DB | reps | N |
| Burpee box jump-over | BBJO | reps | N |
| Burpee pull-up | BPU | reps | N |
| Walking lunge | WL | ft / m / steps | N |
| Jumping jack | JJ | reps | N |
| Plank | — | s | N |
| Bear crawl | — | ft / reps | N |
| Inchworm + push-up | — | reps | N |
| Skin the cat | — | reps | N |
| Pegboard | — | reps | N |

**E.4 Monostructural (cardio)**

| Movement | Abbreviations / aliases | Measure | Loaded |
|----------|-------------------------|---------|--------|
| Run | — | m / km / mi | opt (vest) |
| Shuttle run | — | m / reps | N |
| Row (RowErg / Echo rower) | C2, ROW | m / cal | N |
| SkiErg | SKI | m / cal | N |
| Air bike (Assault / Echo) | AB, Echo | cal / m | N |
| BikeErg | BE* | cal / m | N |
| Double-under | DU | reps | N |
| Single-under | SU* | reps | N |
| Crossover / triple-under | CO*, TU | reps | N |
| Swim | — | m / yd | N |
| Ruck | — | mi / km | Y |

**E.5 Kettlebell**

| Movement | Abbreviations / aliases | Measure | Loaded |
|----------|-------------------------|---------|--------|
| Russian kettlebell swing | RKBS | reps | Y |
| American kettlebell swing (overhead; CrossFit's default "KBS" — **UNVERIFIED** as default) | AKBS, KBS | reps | Y |
| Kettlebell snatch | KBSN | reps / arm | Y |
| Kettlebell clean / clean and press | KBC | reps | Y |
| Turkish get-up | TGU | reps / side | Y |
| Goblet squat | GS | reps | Y |
| Kettlebell deadlift | KBDL | reps | Y |
| Kettlebell front-rack carry | — | m / ft | Y |

**E.6 Dumbbell**

| Movement | Abbreviations / aliases | Measure | Loaded |
|----------|-------------------------|---------|--------|
| Dumbbell snatch (alternating) | DBS, DB SN | reps | Y |
| Dumbbell clean and jerk | DB C&J | reps | Y |
| Dumbbell hang clean-to-overhead | DB HC2OH | reps | Y |
| Dumbbell (hang) power clean | DB PC, DB HPC | reps | Y |
| Devil press | DP | reps | Y |
| Man maker | — | reps | Y |
| Dumbbell thruster | DB TH | reps | Y |
| Dumbbell front squat | DB FS | reps | Y |
| Dumbbell push press / push jerk / strict press | DB PP, DB PJ, DB SP | reps | Y |
| Dumbbell bench press | DB BP | reps | Y |
| Dumbbell row / renegade row | — | reps | Y |
| Dumbbell deadlift | DB DL | reps | Y |
| Dumbbell box step-over | DBBSO | reps | Y |
| Dumbbell overhead walking lunge | DB OHWL | ft / m | Y |
| Dumbbell front-rack lunge | DB FRL | ft / m | Y |
| Farmer's carry | FC | m / ft | Y |
| Single-arm dumbbell overhead squat | SA DB OHS | reps | Y |
| Dumbbell Turkish get-up | TGU | reps | Y |

**E.7 Other implements**

| Movement | Abbreviations / aliases | Measure | Loaded |
|----------|-------------------------|---------|--------|
| Wall-ball shot | WB, WBS | reps | Y (+ target ft) |
| Medicine-ball clean | MBC | reps | Y |
| Medicine-ball box step-over | MBSO | reps | Y |
| Slam ball | SB*, MB slam | reps | Y |
| Box jump | BJ, BXJ | reps | N (height) |
| Box jump-over | BJO | reps | N (height) |
| Box step-over | BSO | reps | opt |
| Sled push | — | m / ft | Y |
| Sled pull / drag | — | m / ft | Y |
| Sandbag carry | — | m / ft | Y |
| Sandbag / D-ball over shoulder | — | reps | Y |
| Sandbag clean | — | reps | Y |
| Yoke carry | — | m / ft | Y |
| Plate overhead walk / plate ground-to-overhead | — | m / reps | Y |
| Weighted vest (modifier) | vest | — | Y |
| Tire flip | — | reps | Y |
| Battle rope | — | s | N |

## Appendix F — Whiteboard glossary (non-movement)

Sources: crossfit.com terms page (Appendix A); archived crossfit.com FAQ §1.11, the official abbreviation list <https://web.archive.org/web/20130115092137/http://www.crossfit.com/cf-info/faq.html>; RBST glossary; <https://noexcusescrossfit.com/acronyms-abbreviations/>; <https://www.wodball.com/en/crossfit/glossary>. Movement abbreviations are in Appendix E.

- **Formats**: AMRAP = as many rounds/reps as possible · AFAP = as fast as possible · AHAP = as heavy as possible (**UNVERIFIED**) · FT = for time · RFT = rounds for time · NFT = not for time · EMOM / EMOTM / OTM = every minute on the minute · E2MOM, E3MOM = every 2 / 3 minutes · TC = time cap · BI = buy-in · CO* = cash-out · WU = warm-up · CD = cool-down · WOD = workout of the day · WO, W/O = workout · MetCon = metabolic conditioning · couplet / triplet / chipper / ladder / Tabata / Death By · YGIG = you go, I go · P1 / P2 = partner 1 / 2 · synchro.
- **Levels**: Rx / Rx'd = as prescribed · Rx+ · Sc* = scaled · Int / Beg = intermediate / beginner.
- **Load and effort**: RM = rep max (1RM, 3RM) · PR / PB = personal record / best · ME = max effort · UB = unbroken · TnG = touch-and-go · RPE = rate of perceived exertion · RIR = reps in reserve · ROM = range of motion · BW / BWT = bodyweight · ATG = ass to grass · GPP = general physical preparedness · GTG = grease the groove · CFT = CrossFit Total · CFWU = CrossFit warm-up · DNF = did not finish.
- **Notation**: ea / e/s / /side = each side · alt = alternating · b/w = between · w/ = with · rds = rounds · R* = rest · `@` = at · `x` = sets / rounds · `#` = lb · `'` = minutes / feet · `"` = seconds / inches.
- **Equipment**: BB = barbell · DB* = dumbbell · KB = kettlebell · MB = medicine ball · SB* = sandbag or slam ball · Pd = pood · C2 = Concept2 · GHD = glute-ham developer · AB = air bike · BE* = BikeErg · rig · PVC.

**Ambiguous tokens** — the parser must resolve these from context (REQ-AX15); the official CrossFit FAQ itself says PU means pull-up or push-up "depending on the context".

| Token | Possible meanings | Typical disambiguation |
|-------|-------------------|------------------------|
| PU | pull-up · push-up | neighbours (pull-ups pair with thrusters/HSPU; push-ups with squats/sit-ups); other gymnastics on the board |
| SC | squat clean · scaled | a load after it → squat clean; line prefix `Sc:` → scaled |
| SS | squat snatch · superset · Starting Strength | strength section vs metcon |
| HS | hang snatch · handstand | a load → hang snatch; followed by walk/hold → handstand |
| SU | single-under · sit-up | rep count and neighbours (DU) |
| BE | BikeErg · back extension | calories / metres → BikeErg; reps → back extension |
| SB | snatch balance · slam ball · sandbag | strength section, implement weight |
| DB | dumbbell · Death By | as a format header → Death By; before a movement → dumbbell |
| CO | cash-out · crossover (rope) | position (end of workout) vs a jump-rope count |
| R | rest · rounds · right | followed by a time → rest; by a count → rounds |
| C | calories · clean | number + machine → calories; barbell context → clean |
| cluster | squat-clean thruster · cluster set | metcon → movement; strength with mini-sets → cluster set |
| `'` | minutes · feet | AMRAP / cap → minutes; lunge / HSW → feet |
| `"` | seconds · inches | box / deficit → inches; `:30"` → seconds |

## Appendix G — Benchmark workouts (seed library)

Seed for `shared/benchmarks.json` (REQ-DM13). Loads are male / female. Sources: `https://www.crossfit.com/benchmark/<slug>` (the Girls, Heroes, notable); Kalsu from <https://wodwell.com/wod/kalsu/> (not in crossfit.com's 254-entry Hero list — WODwell lists it as a CrossFit Football benchmark); Open data from the CMS API (Appendix A).

**G.1 The Girls**

| Name | Format | Scheme and movements | Rx M / F | Score |
|------|--------|----------------------|----------|-------|
| Angie | For time (chipper) | 100 pull-ups, 100 push-ups, 100 sit-ups, 100 squats | bodyweight | time |
| Barbara | 5 rounds, 3:00 rest between | 20 pull-ups, 30 push-ups, 40 sit-ups, 50 squats | bodyweight | time (whether rest counts is unstated — **UNVERIFIED**) |
| Chelsea | EMOM 30 | 5 pull-ups, 10 push-ups, 15 squats | bodyweight | rounds completed |
| Cindy | AMRAP 20 | 5 pull-ups, 10 push-ups, 15 squats | bodyweight | rounds+reps |
| Diane | For time | 21-15-9 deadlift, handstand push-up | 225 / 155 lb | time |
| Elizabeth | For time | 21-15-9 clean, ring dip | 135 / 95 lb | time |
| Fran | For time | 21-15-9 thruster, pull-up | 95 / 65 lb | time |
| Grace | For time | 30 clean and jerks | 135 / 95 lb | time |
| Helen | 3 RFT | 400 m run, 21 kettlebell swings, 12 pull-ups | KB 53 / 35 lb | time |
| Isabel | For time | 30 snatches | 135 / 95 lb | time |
| Jackie | For time | 1000 m row, 50 thrusters, 30 pull-ups | 45 / 35 lb | time |
| Karen | For time | 150 wall-ball shots | 20 / 14 lb to 10 / 9 ft | time |
| Linda | For time | 10-9-…-1: deadlift 1.5 × BW, bench press BW, squat clean 0.75 × BW | % bodyweight | time |
| Mary | AMRAP 20 | 5 HSPU, 10 pistols, 15 pull-ups | bodyweight | rounds+reps |
| Nancy | 5 RFT | 400 m run, 15 overhead squats | 95 / 65 lb | time |
| Annie | For time | 50-40-30-20-10 double-under, sit-up | bodyweight | time |
| Eva | 5 RFT | 800 m run, 30 kettlebell swings, 30 pull-ups | KB 2 / 1.5 pood (page: 72 / 54 lb) | time |
| Kelly | 5 RFT | 400 m run, 30 box jumps, 30 wall-ball shots | box 24 / 20 in; ball 20 / 14 lb | time |
| Lynne | 5 rounds for max reps | bodyweight bench press, pull-ups | bodyweight | total reps (**UNVERIFIED**) |
| Nicole | AMRAP 20 | 400 m run, max pull-ups | bodyweight | total pull-ups (**UNVERIFIED**) |
| Amanda | For time | 9-7-5 muscle-up, snatch | 135 / 95 lb | time |
| Gwen | For load | clean and jerk 15-12-9, touch-and-go, same load, rest as needed | athlete's choice | load |
| Marguerita | For time | 50 reps; each rep = burpee + push-up + jumping jack + sit-up + handstand (**UNVERIFIED** interpretation) | bodyweight | time |
| Candy | 5 RFT | 20 pull-ups, 40 push-ups, 60 squats | bodyweight | time |
| Maggie | 5 RFT | 20 HSPU, 40 pull-ups, 60 alternating pistols | bodyweight | time |
| Hope | 3 rounds, 1:00 stations, 1:00 rest | burpees, power snatch, box jump, thruster, chest-to-bar pull-ups | 75 lb (single load), 24 in box | total reps |
| Grettel | 10 RFT | 3 clean and jerks, 3 burpees over the bar | 135 / 95 lb | time |
| Ingrid | 10 RFT | 3 snatches, 3 burpees over the bar | 135 / 95 lb | time |
| Barbara Ann | 5 rounds, each for time, 3:00 rest | 20 HSPU, 30 deadlifts, 40 sit-ups, 50 double-unders | 135 / 95 lb (page writes "95/135") | total time |
| Lyla | For time | 10-9-…-1 muscle-up, bodyweight clean and jerk | bodyweight | time |
| Ellen | 3 RFT | 20 burpees, 21 DB snatches (1 DB), 12 DB thrusters (2 DBs) | 50 / 35 lb | time |
| Andi | For time | 100 hang power snatches, 100 push presses, 100 SDHP, 100 front squats | 65 / 45 lb | time |
| Lane | 5 rounds for max reps, rest as needed | hang power snatch ¾ × BW, HSPU | ¾ bodyweight | total reps |
| Nasty Girls (tagged "Girls" on crossfit.com) | 3 RFT | 50 squats, 7 muscle-ups, 10 hang power cleans | 135 lb (female load not given) | time |

**G.2 Hero WODs**

| Name | Format | Scheme | Rx M / F | Score |
|------|--------|--------|----------|-------|
| Murph | For time | 1 mi run, 100 pull-ups, 200 push-ups, 300 squats (may partition), 1 mi run | vest 20 / 14 lb | time |
| DT | 5 RFT | 12 deadlifts, 9 hang power cleans, 6 push jerks | 155 / 105 lb | time |
| JT | For time | 21-15-9 HSPU, ring dip, push-up | bodyweight | time |
| Michael | 3 RFT | 800 m run, 50 back extensions, 50 sit-ups | bodyweight | time |
| Badger | 3 RFT | 30 squat cleans, 30 pull-ups, 800 m run | 95 / 65 lb | time |
| Nate | AMRAP 20 | 2 muscle-ups, 4 HSPU, 8 kettlebell swings | KB 70 / 53 lb | rounds+reps |
| Randy | For time | 75 power snatches | 75 / 55 lb | time |
| Kalsu | For time | 100 thrusters; 5 burpees at the start and at the top of every minute | 135 / 95 lb | time |
| Chad | For time | 1,000 box step-ups with a ruck | ruck 45 / 35 lb, 20 in box | time |
| Holleyman | 30 RFT | 5 wall-ball shots, 3 HSPU, 1 power clean | ball 20 / 14 lb to 10 / 9 ft; clean 225 / 155 lb | time |
| Josh | For time | 21 OHS, 42 pull-ups, 15 OHS, 30 pull-ups, 9 OHS, 18 pull-ups | 95 / 65 lb | time |
| Daniel | For time | 50 pull-ups, 400 m run, 21 thrusters, 800 m run, 21 thrusters, 400 m run, 50 pull-ups | 95 / 65 lb | time |
| Jason | For time | squats 100 / 75 / 50 / 25 alternating with muscle-ups 5 / 10 / 15 / 20 | bodyweight | time |
| Griff | For time | 800 m run, 400 m run backward, 800 m run, 400 m run backward | — | time |
| Ryan | 5 RFT | 7 muscle-ups, 21 burpees | bodyweight | time |
| Bull | 2 RFT | 200 double-unders, 50 OHS, 50 pull-ups, 1 mi run | 135 / 95 lb | time |
| The Seven | 7 RFT | 7 each: HSPU, thruster, knees-to-elbows, deadlift, burpee, kettlebell swing, pull-up | thruster 135 / 95, deadlift 245 / 165, KB 70 / 53 lb | time |
| Loredo | 6 RFT | 24 squats, 24 push-ups, 24 walking-lunge steps, 400 m run | bodyweight | time |
| Lumberjack 20 | For time | 20 each of deadlift, KB swing, OHS, burpee, chest-to-bar, box jump, DB squat clean — 400 m run after each | DL 275 / 185, KB 70 / 53, OHS 115 / 75 lb, box 24 / 20 in, DB 45 / 30 lb | time |
| Glen | For time | 30 clean and jerks, 1 mi run, 10 rope climbs (15 ft), 1 mi run, 100 burpees | 135 / 95 lb | time |
| Whitten | 5 RFT | 22 KB swings, 22 box jumps, 400 m run, 22 burpees, 22 wall-ball shots | KB 72 / 53 lb, box 24 / 20 in, ball 20 / 14 lb | time |
| Nutts | For time | 10 HSPU, 15 deadlifts, 25 box jumps, 50 pull-ups, 100 wall-ball shots, 200 double-unders, 400 m run with a plate | DL 250 / 175 lb, box 30 / 24 in, plate 45 / 25 lb | time |
| Tommy V | For time | thrusters 21 / 15 / 9 alternating with rope climbs 12 / 9 / 6 | 115 / 75 lb | time |
| Luce | 3 RFT, weighted vest | 1 km run, 10 muscle-ups, 100 squats | vest 20 / 14 lb | time |
| Jerry | For time | 1 mi run, 2 km row, 1 mi run | — | time |
| Danny | AMRAP 20 | 30 box jumps, 20 push presses, 30 pull-ups | 115 / 75 lb, box 24 / 20 in | rounds+reps |
| Mr. Joshua | 5 RFT | 400 m run, 30 GHD sit-ups, 15 deadlifts | 250 / 175 lb | time |
| McGhee | AMRAP 30 | 5 deadlifts, 13 push-ups, 9 box jumps | 275 / 185 lb, box 24 / 20 in | rounds+reps |

**G.3 Other notable benchmarks**

| Name | Format | Scheme | Rx M / F | Score |
|------|--------|--------|----------|-------|
| Fight Gone Bad | 3 rounds × 5 one-minute stations, 1:00 rest | wall ball, SDHP, box jump, push press, row (cal) | ball 20 / 14 lb to 10 / 9 ft; SDHP and PP 75 / 55 lb; 20 in box | total reps |
| Filthy Fifty | For time | 50 each: box jump, jumping pull-up, KB swing, walking-lunge step, knees-to-elbows, push press, back extension, wall ball, burpee, double-under | box 24 / 20 in, KB 16 / 12 kg, PP 45 / 35 lb, ball 20 / 14 lb | time |
| CrossFit Total | 1RM of three lifts | back squat, shoulder press, deadlift; 3 attempts each | — | total load |
| Tabata Something Else | 32 × :20 / :10 | 8 intervals each of pull-ups → push-ups → sit-ups → squats, no rest between | bodyweight | total reps |
| Tabata This! | 5 Tabatas, 1:00 rest between | row (cal), squat, pull-up, push-up, sit-up | bodyweight | sum of the lowest intervals |
| The Chief | 5 cycles | 3:00 AMRAP of 3 power cleans, 6 push-ups, 9 squats; 1:00 rest | 135 / 95 lb | rounds (summed across cycles — **UNVERIFIED**) |

**G.4 CrossFit Open 2024–2026** (official; Rx M / F, scaled in parentheses). Every Open workout also has a Foundations version (e.g. 25.3 uses bear crawls and "50 strokes on the rower").

| Workout | Format | Scheme | Loads | Score / tiebreak |
|---------|--------|--------|-------|------------------|
| 24.1 | For time, cap 15 | 21-15-9: DB snatch (arm 1), lateral burpee over the DB, DB snatch (arm 2), lateral burpee over the DB | DB 50 / 35 lb (22.5 / 15 kg) (Sc 35 / 20 lb) | time or reps; tiebreak after the 21s and the 15s |
| 24.2 | AMRAP 20 | 300 m row, 10 deadlifts, 50 double-unders | 185 / 125 lb (83 / 56 kg) (Sc 135 / 95 lb, single-unders) | reps (10 m = 1 rep); no tiebreak |
| 24.3 | All for time, cap 15 | 5 rounds: 10 thrusters (weight 1), 10 chest-to-bar; rest 1:00 (clock runs); 5 rounds: 7 thrusters (weight 2), 7 bar muscle-ups | M 95, 135 / F 65, 95 lb (Sc M 65, 95 / F 45, 65; jumping C2B, chin-over-bar pull-ups) | time or reps; tiebreak after the first 5 rounds |
| 25.1 | AMRAP 15 | 3 lateral burpees over the DB, 3 DB hang clean-to-overhead, 30 ft walking lunge (2 × 15 ft); add 3 burpees and 3 HC2OH each round | DB 50 / 35 lb (Sc 35 / 20) | total reps; no tiebreak |
| 25.2 (= 22.3) | For time, cap 12 | 21 pull-ups, 42 DU, 21 thrusters (w1); 18 C2B, 36 DU, 18 thrusters (w2); 15 bar muscle-ups, 30 DU, 15 thrusters (w3) | M 95 / 115 / 135, F 65 / 75 / 85 lb (Sc M 65 / 85 / 105, F 45 / 55 / 65; jumping pull-ups → pull-ups → C2B; single-unders) | time or reps; tiebreak after each thruster set |
| 25.3 | For time, cap 20 | 5 wall walks, 50 cal row, 5 WW, 25 deadlifts, 5 WW, 25 cleans, 5 WW, 25 snatches, 5 WW, 50 cal row | DL 225 / 155, clean 135 / 85, snatch 95 / 65 lb (Sc M 135 / 95 / 65, F 85 / 65 / 45; scaled wall walks) | time or reps; tiebreak after each wall-walk set |
| 26.1 | For time, cap 12 | wall balls 20-30-40-66-40-30-20, separated by 18 box jump-overs (×2), 18 med-ball box step-overs (×2), 18 box jump-overs (×2) | ball 20 / 14 lb (9 / 6 kg) to 10 / 9 ft, box 24 / 20 in (Sc 14 / 10 lb, may step up) | time or reps; tiebreak after each BJO / step-over set |
| 26.2 | For time, cap 15 | 3 × (80 ft DB overhead walking lunge, 20 alternating DB snatches) with 20 pull-ups / 20 C2B / 20 ring muscle-ups | DB 50 / 35 lb (22.5 / 15 kg) (Sc 35 / 20; jumping pull-ups / pull-ups / C2B) | time or reps; tiebreak after each snatch set |
| 26.3 | For time, cap 16 | 3 blocks × 2 rounds: 12 burpees over the bar, 12 cleans, 12 burpees over the bar, 12 thrusters; weight 1 → 2 → 3 | M 95 / 115 / 135, F 65 / 75 / 85 lb (Sc M 65 / 85 / 95, F 45 / 55 / 65; may step over the bar) | time or reps; tiebreak after each thruster set |

## Appendix H — Whiteboard examples → expected parse (parser test cases)

Each example becomes a text fixture in `shared/fixtures/` in MVP (REQ-AX12); photos of real boards are added alongside them over time. They deliberately cover the hazards from §4.1 and Appendix F.

**H1 — split loads, a cap and a scaled line**
```
"FRAN"  21-15-9
Thrusters 95/65#
Pull-ups
TC 8:00
Sc: 65/45, jumping PU
```
→ `for_time`, cap 480 s, rep scheme [21, 15, 9] × [thruster split M 95 / F 65 lb, pull-up]; Scaled level = thruster 65 / 45 lb and jumping pull-up; score = time, or reps if capped. Benchmark match: Fran (cap and scaled line differ from the canonical — show the diff).

**H2 — % strength, then an E2MOM complex**
```
A) Back Squat 5x3 @ 80% — rest 2:00
B) E2MOM x6: 3 PC + 1 PJ (build)
```
→ Part A: 5 sets × 3 reps at 0.8 × back-squat 1RM, rest 120 s, log every set. Part B: `emom` 120 s × 6, complex [power clean ×3, push jerk ×1], load rises each set; intended score = heaviest complex; MVP runs part B as an explicitly reviewed timer/manual-load-score fallback, with dedicated per-interval logging in v1.

**H3 — multi-station EMOM with a partial-minute window**
```
EMOM 16
1: 15/12 cal row
2: 12 T2B
3: :40 max DU
4: rest
```
→ `emom` 60 s, 4 stations × 4 cycles; row calories M 15 / F 12; station 3 has `workSec` 40 (20 s rest); station 4 is rest; intended score per station (cal / reps) or completion; MVP records completion or an entered manual aggregate, with per-station entry in v1.

**H4 — progressive AMRAP; `'` means minutes and feet in the same workout (= Open 25.1)**
```
15' AMRAP
3 LBO DB
3 DB HC2OH 50/35
30' WL
+3 each rd (not lunges)
```
→ `amrap` 900 s; lateral burpee over the DB; DB hang clean-to-overhead M 50 / F 35 lb; walking lunge 30 ft; `progression` +3 reps per round on items 1–2; intended benchmark score = total reps; MVP preserves typed progress/manual score, and automated Open section conversions are v1 (§4.5).

**H5 — rounds each for time, rest, a cash-out**
```
3 rds, each FT:
400m run / 21 KBS 24/16kg / 12 C2B
rest 3' b/w
CASH OUT: 50 GHD SU
```
→ `for_time` 3 rounds with `eachRoundForTime`, 2 × 180 s rest; KB M 24 / F 16 kg; score = three splits plus total (rest excluded by explicit app default for each-round-for-time; show the assumption in review); cash-out = unscored item after the clock.

**H6 — partner workout (team-total ambiguity)**
```
TEAMS OF 2 — 30 min cap — FT
100/80 cal bike (split)
60 synchro BBJO 24/20"
40 alt DB snatch 50/35 — switch every 10
P2 holds plank while P1 snatches
```
→ `partner` {size 2}; cap 1800 s; split calorie pool (100 / 80 is a team pool — mixed teams are ambiguous, keep the raw value); synchro burpee box jump-overs; alternate every 10 reps; hold constraint; intended score = team time, or reps at cap under a declared counting rule; MVP provides only a reviewed manual-timer/manual-score fallback. Dedicated partner logging is Later.

**H7 — work : rest intervals with an in-clock buy-in**
```
4 x 4:00 on / 2:00 off
200m run, then AMRAP rest of interval:
5 DL 225/155
10 burpees
```
→ `intervals` 4 × (240 s work + 120 s rest) — omit the final rest unless explicitly prescribed (my call), so 22 min total; a confirmed final rest makes it 24 min; each work segment = 200 m run buy-in, then AMRAP of [5 deadlifts M 225 / F 155 lb, 10 burpees]; score = rounds+reps per interval on the 15-rep AMRAP body, excluding the run buy-in; incomplete buy-in is recorded as typed progress and no completed AMRAP reps. Sum only complete interval observations under the same counting rule.

**H8 — superset, tempo, RPE, wave, then a ladder metcon**
```
A1) Tempo FS @32X1 4x5 @RPE7
A2) Strict C2B 4x5-8 — rest 90s after A2
B) Bench 3-2-1-3-2-1 (wave)
WOD TC 12: 1-2-3…10 DL 1.5xBW / BJ 30/24"
```
→ A1/A2 superset × 4 sets: tempo = 3 s down, 2 s pause, explode up, 1 s at the top; RPE 7; rep range 5–8; rest after the pair. B = six-set wave. WOD = ascending ladder 1 → 10 (55 reps per movement), deadlift at 1.5 × bodyweight (needs the profile's bodyweight), box 30 / 24 in; score = time, or reps at cap. A1/A2 uses explicit text/timer fallback in MVP, dedicated superset execution in v1.

**H9 — Numeric bounds and unresolved load (additional MVP contract fixture)**

```text
For time, cap 60:00: 1000 box step-ups, 2000 m row.
Then back squat 3x3 @75% (no stored 1RM).
```

→ Store 1000 and 2000 without picker overflow. The missing RM never becomes zero load or an invented RM; select a manual numeric load in review or require entry before logging the strength set. These are independent parts, each scored separately.

**H10 — Revision, cancellation and recovery (additional MVP contract fixture)**

```text
Draft v1: AMRAP 10, 5 pull-ups + 10 push-ups + 15 squats.
Manual edit v2: AMRAP 12. Old parse v1 completes late.
Send plan rev2; later retry rev1. Log one round; terminate app.
```

→ Late parse becomes an optional alternative, not an overwrite. Plan rev2 remains current. The accepted round survives as an interrupted result with unknown uncheckpointed timer tail. A new attempt gets a new session ID. A result retry after phone edit/deletion is re-acked without replacing/resurrecting it.

## Appendix I — Platform research notes

**Evidence status:** the following notes preserve the original research, including version/model/price claims and inferred behavior. They are not a fresh verification and do not override the normative contracts above. Recheck relevant primary documentation and record device/API experiments through REQ-TS07/11 before selecting dependencies or shipping.

### I.1 Garmin Connect IQ (SDK 9.2.0, researched 2026-11-03)

Sources: the cached mirror of developer.garmin.com/connect-iq used by the garmin-dev skill (`core-topics/`, `ux-guidelines/`, `device-reference/`), the installed SDK's API reference (`doc/Toybox/…`), release notes (`doc/docs/Readme/History.html`), FAQ, and device files (`Devices/<id>/compiler.json`, `simulator.json`, `<id>.api.debug.xml`). **F** = documented; **I** = inference or Garmin-forum material.

| Topic | Finding | Basis |
|-------|---------|-------|
| Phone → watch | `Communications.registerForPhoneAppMessages(callback)` (API 1.4.0); all waiting messages are delivered, one call each, when the callback is registered; a message "will persist until the app is opened by the user". Permission `Communications`. Android payload types: int/long, float/double, boolean, char, String, List → Array, Map → Dictionary (anything else throws). | F |
| Mailbox / throughput | 8 KB mailbox per app from a partner app (SDK 2.2.0; no newer figure). API 6.0 adds `registerForPhoneAppMessageErrors` (out of memory / storage) — only fr970, fr57047mm, fenix 8 here. BLE throughput "generally between 400 and 800 bytes/s". | F |
| Background receipt | `Background.registerForPhoneAppMessageEvent()` (3.2.0) wakes `ServiceDelegate.onPhoneAppMessage`; 64 KB pool, killed after 30 s, `Background.exit()` data ≤ ~8 KB (or write `Application.Storage`, 3.2.0+); then `Notifications.showNotification` (5.1.0, opens the app with `:launchedFromNotification`) or `Background.requestApplicationWake`. Whether the foreground app still gets a message consumed in the background: not documented. | F / I |
| Watch → phone | `Communications.transmit(content, {}, ConnectionListener)` (`onComplete` / `onError`); phone side `registerForAppEvents` → `onMessageReceived(device, app, List<Object>, status)`; one companion app per Connect IQ app. | F |
| Launch from phone | `openApplication()` shows a prompt on the watch (`PROMPT_SHOWN_ON_DEVICE`, `APP_IS_ALREADY_RUNNING`, …); no silent launch. | F |
| Known delivery bug | CIQQA-4631 (acknowledged Aug–Sep 2026): Garmin Connect for Android 5.27.3 / 5.28.1 accepted but never delivered Mobile SDK messages, both directions, store and sideloaded builds. | I (forum bug report) |
| `makeWebRequest` | HTTPS required since 2.3.0 (`SECURE_CONNECTION_REQUIRED` −1001; the simulator can disable the check for testing only). `http://localhost` to a phone server: not documented, broke across Garmin Connect versions. Response limits: errors −402 (too large) / −403 (out of memory); SDK 9.1.0 enforces a max JSON size over BLE. Background temporal events ≥ 5 min apart. | F / I |
| Recording | `ActivityRecording.createSession({:sport, :subSport, :name (required, ≤ 15 chars suggested), …})`; one session at a time; `start/stop/save/discard/isRecording/addLap`, `setTimerEventListener` (3.0.10). Sports: `SPORT_TRAINING`(10) + `SUB_SPORT_STRENGTH_TRAINING`(20) / `CARDIO_TRAINING`(26) (3.2.0); `SPORT_HIIT`(62) + `SUB_SPORT_HIIT`(70) / `AMRAP`(73) / `EMOM`(74) / `TABATA`(75) (4.1.6). The sport "is not guaranteed to change device behavior". | F |
| Strength sets | No API writes FIT set/exercise messages (searched every API page, `api.mir`, `api.debug.xml`). Garmin Connect's native set table is out of reach. | F |
| `FitContributor` | `createField(name, id, DATA_TYPE_*, {:count, :mesgType = SESSION / LAP / RECORD, :units})`; types SINT8…UINT32, FLOAT, DOUBLE, STRING (not on RECORD); 256 bytes of developer data per message; RECORD written ≤ 1 Hz; `fitField` resources (ids 0–255) with `displayInChart` / `displayInActivityLaps` / `displayInActivitySummary`. Garmin Connect display can be tested with a beta install. | F / I |
| Native workouts | `PersistedContent.getWorkouts()` exposes only name/id/intent; `makeWebRequest` with the FIT response type can download a workout FIT onto the device; `System.exitTo(workout.toIntent())` hands over to the native player after a confirmation. Acceptance of HIIT/strength workout FITs: not documented (*Later* spike). `DataField.setWorkout()` (5.2.0) is data-field only and missing on fr255/fr955. | F / I |
| Sensors | `Activity.getActivityInfo()`: `currentHeartRate`, `averageHeartRate`, `maxHeartRate`, `calories`, `elapsedTime`, `timerTime`, `timerState`, `trainingEffect` (all nullable). Enable HR with `Sensor.setEnabledSensors` (`Sensor` permission). Accelerometer via `registerSensorDataListener`, up to 100 Hz on these devices; `:sensorLogger` records raw IMU into the FIT. | F |
| Input | Native: `Picker` + `PickerFactory`, `Menu2`, `CustomMenu`, `ActionMenu` (3.4.0), `Confirmation`, `ProgressBar`, toasts (3.4.0), `ViewLoop`. `NumberPicker` (incl. weight mode) is deprecated and absent on all targets. `System.DeviceSettings.weightUnits` = kg/lb preference. Button holds via `onKeyPressed` / `onKeyReleased` timing; touch `onTap`, `onHold`, `onSwipe`, `onDrag`/`onFlick`. | F |
| UX guidance | Top-right button = start/stop; don't modify BACK; limit interaction during activity; use text entry sparingly; prefer native controls. Two-button touch models: top = start/stop, bottom = back (hold = menu). | F |
| Attention | Tones incl. `TONE_START`, `STOP`, `LAP`, `INTERVAL_ALERT`, `TIME_ALERT`, `SUCCESS`, `FAILURE`; custom `ToneProfile` (3.1.0). vivoactive5 has no `playTone`. ≤ 8 vibration profiles; Forerunners use one duty cycle. AMOLED: holding the backlight on > ~1 min throws `BacklightOnTooLongException`. | F |
| Storage | `Application.Storage` holds Number, Float, Long, Double, Char, String, Boolean, Array, Dictionary. Core topic: 8 KB per key/value, 128 KB total; newer API page: values up to 32 KB, device-dependent total, `StorageFullException`. Design to the lower numbers. | F |
| Lifecycle | An activity-list app runs until the user backs out of its first page; `onStop(:suspend)` / `onStart(:resume)` from 4.0; `onActive` / `onInactive` (4.2.3) only on fenix 8, venu3, vivoactive5 — while inactive, recording continues but Attention is denied. No documented way to resume a FIT session after the app exits. | F / I |
| Sideload vs store | The Mobile SDK addresses the app by its manifest UUID, so sideloaded builds should be reachable (I). App settings need a store/beta install (community wiki — I). Beta = upload under an alternate UUID, private to the account, can test settings and developer fields (F). Sideloads run at "Developer" trust. | F / I |

**Target devices** (SDK device files; API level as reported by SDK 9.2.0 — firmware updates can raise it):

| Device | Screen | Touch | Buttons | Watch-app memory | API |
|--------|--------|-------|---------|------------------|-----|
| fr965, fr970, fr57047mm | 454 px AMOLED | yes | 5 | 768 KB | 5.2.0 / 6.0.2 / 6.0.2 |
| fr265 / fr265s | 416 / 360 px AMOLED | yes | 5 | 768 KB | 5.2.0 |
| fr255 | 260 px MIP | **no** | 5 | **512 KB** | 5.2.0 |
| fr955, fenix7, fenix7pro | 260 px MIP | yes | 5 | 768 KB | 5.2.0 |
| epix2pro47mm / 51mm | 416 / 454 px AMOLED | yes | 5 | 768 KB | 5.2.0 |
| fenix843mm / 847mm | 416 / 454 px AMOLED | yes | 5 | 768 KB | 6.0.2 |
| venu3 | 454 px AMOLED | yes | 2 | 768 KB | 5.2.0 |
| vivoactive5 | 390 px AMOLED | yes | 2 | 768 KB | 5.2.0 |

### I.2 Android and ecosystem (researched 2026-11-03)

**UNVERIFIED** = could not be confirmed from a primary source.

| Topic | Finding | Source |
|-------|---------|--------|
| Connect IQ Mobile SDK | `com.garmin.connectiq:ciq-companion-app-sdk:2.4.0@aar` (2026-03-25; 2.3.0 2025-08-11, 2.2.0 2025-01-15). minSdk 21. Auto-merges `<queries>` for `com.garmin.android.apps.connectmobile`, an exported `IQGarminBindingService` and `AutoUIDialogHostActivity`; declares no permissions. The GitHub repo is the sample + a Maven pointer; Garmin-only commits (last 2026-04-01) — maintained at a low cadence. | <https://repo1.maven.org/maven2/com/garmin/connectiq/ciq-companion-app-sdk/>, <https://github.com/garmin/connectiq-android-sdk> |
| Garmin Connect dependency | "All communication for companion applications running on Android goes through a Garmin Connect Mobile service"; `initialize` fails with `GCM_NOT_INSTALLED` / `GCM_UPGRADE_NEEDED` / `SERVICE_ERROR`; `autoUI = true` shows an install/upgrade dialog. | <https://developer.garmin.com/connect-iq/core-topics/mobile-sdk-for-android/> |
| API surface | `getInstance(ctx, WIRELESS or TETHERED)`, `initialize(ctx, autoUI, listener)` (nothing before `onSdkReady`), `getKnownDevices` / `getConnectedDevices`, `registerForDeviceEvents` (CONNECTED / NOT_CONNECTED / NOT_PAIRED), `getApplicationInfo` (INSTALLED + version / NOT_INSTALLED / NOT_SUPPORTED), `openApplication` (prompt on the watch), `sendMessage` (SUCCESS, FAILURE_UNKNOWN, FAILURE_INVALID_FORMAT, FAILURE_MESSAGE_TOO_LARGE, FAILURE_UNSUPPORTED_TYPE, FAILURE_DURING_TRANSFER, FAILURE_INVALID_DEVICE, FAILURE_DEVICE_NOT_CONNECTED), `registerForAppEvents` (one companion per watch app). Payload types: Integer, Long, Float, Double, Boolean, Char, String, List, Map. Max serialized message 16 KB. Binder-service APIs: "DO NOT USE without prior approval from Garmin". | SDK 2.4.0 javadoc (inspected AAR) |
| Tethered testing | `adb forward tcp:7381 tcp:7381` (port via `setAdbPort`), then Connection → Start in the simulator; `openStore()` unavailable; reported quirk: SUCCESS then FAILURE_UNKNOWN in the ADB strategy (acknowledged). | <https://developer.garmin.com/connect-iq/core-topics/communicating-with-mobile-apps/>, Garmin forum |
| Reliability | CIQQA-4631 (2026-08-12, Acknowledged): GCM 5.27.3 accepts watch → phone `transmit` but never delivers it; on GCM 5.28.1 (Android 16, FR265) messaging was dead both ways, even with Garmin's own sample, for store and sideloaded builds. 2025-11-17 (Acknowledged): phone-app messages broken on SDK 8.3.0, fixed for one user by a firmware update. Background receiving: a foreground service killed within hours; only unofficial GCM broadcasts work. Main-thread sends: `NetworkOnMainThreadException` (open PR #3). | <https://forums.garmin.com/developer/connect-iq/i/bug-reports/gcm-5-27-3-android-accepts-communications-transmit-messages-from-watch-app-but-never-delivers-them-to-the-companion-app>, <https://forums.garmin.com/developer/connect-iq/i/bug-reports/phone-app-messages-communication-with-android-sdk-doesn-t-work>, <https://forums.garmin.com/developer/connect-iq/f/discussion/418746/> |
| Sideloading | Used with the SDK in practice (not officially documented). An FR965 rejected a sideload with −400 because of stale `GARMINDEVICE.XML` entries from an earlier UUID; a new UUID + renamed `.prg` fixed it. | <https://forums.garmin.com/developer/connect-iq/f/showcase/435218/> |
| Document Scanner | GA 16.0.0 (2025-09-29); API 21+, ≥ 1.7 GB RAM (else `MlKitException UNSUPPORTED`); "No camera permission is required"; modes BASE (crop/rotate/reorder) / BASE_WITH_FILTER (grayscale, auto-enhance) / FULL (default, adds stain/finger removal); `setGalleryImportAllowed`, `setPageLimit` (no documented maximum), JPEG and/or PDF results; fixed UI; ~300 KB; model via Play services; on-device. Whiteboard edge detection: UNVERIFIED. | <https://developers.google.com/ml-kit/vision/doc-scanner/android>, <https://developers.google.com/ml-kit/release-notes> |
| Text Recognition v2 | Bundled `com.google.mlkit:text-recognition:16.0.1` or unbundled `play-services-mlkit-text-recognition:19.0.2`; API 23+; Latin, Chinese, Devanagari, Japanese, Korean; Text → blocks → lines → elements → symbols with boxes, rotation, confidence; characters ≥ 16 × 16 px. Handwriting not documented; the ML Kit team (2018): "only on handwritten text that is very well written and sparse". | <https://developers.google.com/ml-kit/vision/text-recognition/v2/android>, <https://stackoverflow.com/questions/50462459> |
| Cloud OCR | Google Cloud Vision `DOCUMENT_TEXT_DETECTION` detects handwriting (hint `en-t-i0-handwrit`). | <https://docs.cloud.google.com/vision/docs/handwriting> |
| Gemini Nano | ML Kit GenAI Prompt API `com.google.mlkit:genai-prompt` 1.0.0-beta5 (2026-10-26): text or image + text, ≤ 10 images, < 4,000 input tokens, ≤ 4K output, structured output + system instructions (2026-07-14); API 26+, AICore, foreground-only (`BACKGROUND_USE_BLOCKED`), per-app quota; Pixel 9/10/11, Galaxy S26, Z Fold7/8, OnePlus 13/15, Xiaomi 15/17 (Galaxy S25: task APIs only). | <https://developers.google.com/ml-kit/genai/prompt/android/get-started> |
| Firebase AI Logic | Gemini with `responseMimeType = "application/json"` + `responseSchema` (OpenAPI subset) works with image input; PNG / JPEG / WebP, ≤ 20 MB per request, scaled to fit 3072 × 3072; models listed: gemini-4-argon, gemini-3.8-flash, gemini-3.5-flash-lite; Spark no-cost tier. | <https://firebase.google.com/docs/ai-logic/generate-structured-output>, <https://firebase.google.com/docs/ai-logic/analyze-images> |
| Claude API | Vision stable (JPEG / PNG / GIF / WebP, 10 MB per image, 8000 × 8000 px; high-resolution tier 2,576 px long edge / 4,784 visual tokens; tokens = ⌈w/28⌉ × ⌈h/28⌉). Structured outputs GA (`output_config.format` with a JSON schema); the Java SDK is usable from Kotlin. Opus 5.5 input $4 / MTok. | <https://platform.claude.com/docs/en/build-with-claude/vision>, <https://platform.claude.com/docs/en/build-with-claude/structured-outputs> |
| Health Connect | Session types incl. HIGH_INTENSITY_INTERVAL_TRAINING (36), STRENGTH_TRAINING (70), WEIGHTLIFTING (81), CALISTHENICS (13), BOOT_CAMP, EXERCISE_CLASS, ROWING_MACHINE, RUNNING, OTHER_WORKOUT. Segment types include squat, deadlift, bench press, (barbell) shoulder press, pull-up, sit-up, burpee, kettlebell swing, lunge, plank, jumping jack, jump rope, rowing machine — no thruster, wall ball, box jump, clean, snatch, T2B or muscle-up. `repetitions` since 1.1.0 (stable 2025-10-08); weight / setIndex / RPE only from 1.2.0-alpha03 (2026-03-25). Garmin → Health Connect sync announced for June 2025; which types Garmin writes: UNVERIFIED. | <https://developer.android.com/reference/kotlin/androidx/health/connect/client/records/ExerciseSegment>, <https://developer.android.com/jetpack/androidx/releases/health-connect> |
| Garmin Developer Program | Revamped 2026-10-21: **Personal** (individuals, own data, self-serve, pull-only: Activity, Health, Women's Health — no Training), **Starter** (business request form; adds Training + Courses; push-only; ~1,000 users), **Enterprise** (paid). Training API sport coverage (HIIT / strength): UNVERIFIED. | <https://developer.garmin.com/gc-developer-program/overview/>, <https://www.dcrainmaker.com/2026/10/garmin-connect-api-revamps-connect-developer.html> |
| FIT profile | Sub-sports hiit 70, amrap 73, emom 74, tabata 75; `workoutStep` carries exerciseCategory / exerciseName / exerciseWeight; the `set` message carries repetitions, weight, setType — written by Garmin's native strength activity (auto rep counting often wrong per forum reports), not reachable from Connect IQ (I.1). | <https://github.com/garmin/fit-javascript-sdk> (profile 21.218) |

**Prior art**

| App | What it does | Garmin support | Lesson for this design |
|-----|--------------|----------------|------------------------|
| Eat Train Feel (Connect IQ, 2026) | Plans built on the web; the watch runs AMRAP / rounds, LAP per exercise | Connect IQ app with 6-digit pairing; saves a Cardio activity with a lap per exercise | A lap per movement works on the wrist; its sync avoids the phone SDK — a point for keeping REQ-SY09 as the fallback |
| Hevy | Strength logger | Connect IQ app UNVERIFIED; reportedly can't push finished lifts to Garmin | Record on the watch so Garmin Connect gets the activity |
| SugarWOD | Gym whiteboard + logging | UNVERIFIED | Gyms already publish WODs digitally — text import (REQ-AC03) matters |
| Beyond the Whiteboard | WOD + benchmark logging | UNVERIFIED | Benchmark / PR history is a core feature (REQ-AL05) |
| Wodify / PushPress | Gym management + WOD | UNVERIFIED | Programming lives in gym software — import is *Later* |
| WODProof | WOD timer + video | Apple Watch-centric; Garmin UNVERIFIED | Timer UX matters |
| Strong | Strength logger | UNVERIFIED | Fast set/weight entry is the bar to beat (REQ-WS02, REQ-WE01) |
| Garmin native HIIT / Strength | Built-in activities | AMRAP / EMOM / Tabata sub-sports; native strength records sets | Map formats to native sub-sports (REQ-WR01) |
| Whiteboard photo → workout | — | none found (UNVERIFIED) | Likely a gap — this project's core idea |

---

## Change log

| Date | Change |
|------|--------|
| 2026-11-03 | Initial requirements from research: CrossFit workout taxonomy, Connect IQ platform capabilities, Android capture/parsing options, and the owner's existing WOD Timer and Rakia projects. |
| 2026-11-03 | Requirements repair: resolved R01–R24 from REQUIREMENTS_REVIEW.md with explicit defaults, durable run/session/result contracts, bounded atomic sync, scoring/load rules, phase alignment and acceptance gates. No code/hardware verification claimed. |
| 2026-11-04 | Owner request: REQ-AC09 guided manual workout builder (formats, catalog movements, loads, templates, Save & log result); REQ-AC08 and MVP scope reference it. |
| 2026-11-04 | MVP implementation landed on branch `worktree-implement-mvp`: `docs/protocol.md` (wire contract v1), `shared/` schemas/catalog/fixtures, `android/` (domain + app), `watch/` (fr965). Statuses stay `[ ]` until the REQ-TS07 platform gate and REQ-TS05 checklist (`docs/release-checklist.md`) record hardware evidence; per-requirement traceability lives in `android/README.md` and `watch/README.md`. |
| 2026-11-03 | Builder review: MVP watch target narrowed to the fr965 (REQ-WP01, REQ-WN06, REQ-TS06); gear units per implement (REQ-DM10); capture-now-parse-later (REQ-AX16) and multi-day captures (REQ-AX17); open questions Q1–Q4 (§13). |
