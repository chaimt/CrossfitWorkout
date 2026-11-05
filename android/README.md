# WOD Capture (Android)

Phone half of CrossfitWorkout: capture → parse → review → resolve loads → compile a watch plan →
sync with **WOD Coach** over Connect IQ. Spec: `../REQUIREMENTS.md`; wire contract:
`../docs/protocol.md`.

## Modules

| Module | What | Android deps |
|--------|------|--------------|
| `:domain` | Pure Kotlin/JVM (REQ-AP02): canonical model (kotlinx.serialization), validation, load resolution, rep-scheme expansion, levels, plan compiler, scoring, protocol (envelope/codec/outbox/ingest/chunks/hello), Anthropic parser, parse-job lifecycle, eval harness | none |
| `:app` | Compose Material 3 single activity, Room, DataStore, WorkManager, Keystore secret store, ML Kit document scanner, Connect IQ adapter | yes |

`domain/src/main/kotlin/com/wodcapture/domain/`

```
model/       Workout, Part, BodyNode (item/group/rest), Format, LoadSpec, Scoring, Levels,
             ParseResult, AthleteProfile, Result/Session/Score
catalog/     MovementCatalog (reads shared/movements.json, bundled at build time)
validation/  WorkoutValidator (blocking vs warnings, REQ-AX05)
load/        LoadResolver, Appendix D Equivalents, MassMath (rounding ties down)
expand/      RepSchemeExpander (schemes, ladders, progressions, between-only rests)
levels/      LevelApplier (overrides, dangling detection)
plan/        WatchPlan wire model, PlanCompiler, display/limit rules
scoring/     ScoreCalculator (rounds+reps, cap ties, aggregates, volume), ClockPolicy (§6.12)
protocol/    Envelope + Map codec, payload codecs, Outbox, ResultIngest/SessionIngest,
             ChunkStager, HelloDiff, SyncEngine + WatchTransport interface
parser/      WorkoutParser, CloudLlmParser (+HttpTransport/OkHttp), StructuredSchema,
             SystemPrompt, IdNormalizer, ParseJobs, CostEstimator
review/      ReviewActions (confirm splits, accept fallback, revisions, duplicate), LoadText, TimeText
manual/      ManualWorkoutForm + ManualForms, ManualWorkoutBuilder (form → canonical Workout +
             live validation), ManualTemplates (REQ-AC09 guided "New workout")
eval/        EvalHarness + EvalCli (`:domain:eval`)
resources/   workout.schema.json (canonical schema; parser schema is derived from it)
```

Fixtures: inputs come from `../shared/fixtures/H*/input.txt` and `../shared/fixtures/wire/`.
Expected parses, golden plans and negative fixtures sit next to them in
`../shared/fixtures/` (`H1..H10/expected.workout.json`, `H1..H10/plan.json`,
`negative/*.workout.json`); the canonical schema is `../shared/workout.schema.json`. They are generated from the builders in
`domain/src/test/kotlin/com/wodcapture/domain/Fixtures.kt`; regenerate with
`./gradlew :domain:test -Dgolden.update=true` and review the diff.

## Toolchain

* **JDK 21** for the Gradle daemon (`gradle/gradle-daemon-jvm.properties`) and Kotlin toolchains.
  JDK 27 runs the wrapper client but AGP/Kotlin need 21. Install any JDK 21 where Gradle
  auto-detects it (e.g. Temurin under `~/Library/Java/JavaVirtualMachines/`), or set
  `JAVA_HOME` to it. On a TLS-intercepting network, import the corporate CA bundle into that
  JDK's `cacerts`, and run `./gradlew` with `JAVA_HOME` pointing at it so the wrapper download works.
* Gradle 9.8.2 (wrapper committed), AGP 9.5.0 (built-in Kotlin), Kotlin 2.4.21, KSP 2.3.13.
* Android SDK with platform `android-36`; `local.properties` → `sdk.dir=…` (gitignored).
  compileSdk/targetSdk 36 cap some library versions (newer AndroidX/Compose/okhttp-android
  releases declare `minCompileSdk=37`); see `gradle/libs.versions.toml`.

## Build and test

```bash
./gradlew :domain:test              # JVM unit tests (no device)
./gradlew :app:testDebugUnitTest    # app JVM tests
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
ANTHROPIC_API_KEY=… ./gradlew :domain:eval   # parser eval over shared/fixtures (skips without a key)
```

Eval options: `EVAL_MODEL` (default `claude-opus-5-5`), `EVAL_EFFORT` (default `medium`).

## Connect IQ simulator (REQ-SY08)

1. Start the Connect IQ simulator with WOD Coach (`watch/`).
2. `adb forward tcp:7381 tcp:7381`
3. In the app: Watch → enable **Simulator (TETHERED)**, restart the app (the SDK connect type is
   chosen at start-up).
4. Simulator: Connection → Start. The tethered device appears on the Watch screen.
5. Send a workout; delivery shows `sent` until WOD Coach acks (`on_watch`). In the ADB
   strategy the SDK may report SUCCESS and then FAILURE_UNKNOWN — the outbox keeps waiting
   for the application ack, which is what decides delivery.

## Privacy and permissions

`INTERNET` (parser + tethered simulator) and optional `POST_NOTIFICATIONS`. WorkManager merges
`WAKE_LOCK`, `ACCESS_NETWORK_STATE`, `RECEIVE_BOOT_COMPLETED` (normal, install-time) for queued
parses; `FOREGROUND_SERVICE` is removed. No camera permission (system camera intent / ML Kit),
no analytics. `allowBackup=false`, data-extraction rules exclude everything. The API key is
AES-GCM encrypted with an Android Keystore key and never logged.

## Requirement traceability

✅ implemented · 🟡 partial · ⬜ not in this build

| Area | Status | Notes |
|------|--------|-------|
| DM01–DM06 model, DM15–DM17 identity/body/ParseResult | ✅ | `model/`, `workout.schema.json` |
| DM07, DM21–DM25 scoring/observations | ✅ | `scoring/`; tiebreaks v1 |
| DM08 levels | ✅ | `levels/`, review level chips |
| DM09 results, DM10 profile, DM11/DM19 load policy, §4.1, App. D | ✅ | `load/` |
| DM12 catalog | ✅ | `shared/movements.json` bundled |
| DM13 benchmarks | ⬜ | v1 |
| DM14 watch plan / compiler | ✅ | goldens H1–H10, shared `plan.put.json` match |
| DM18 fallback | ✅ | explicit acceptance, `fb:true`, `mode:"manual"` |
| DM20 catalog identity | ✅ | free-text movements allowed |
| DM26 migration | 🟡 | unknown versions rejected/blocked; no migrations yet (v1 schema only) |
| DM27 history/tombstones/delete-all/new generation | ✅ | old-generation results quarantined (no import UI yet) |
| DM28 backup/restore | ⬜ | v1 |
| AP01–AP06 | ✅ | AP06 SDK hygiene in `ciq/ConnectIqAdapter.kt` |
| AC01 scanner + plain photo, AC02 picker/share, AC03 text, AC04 ≤4 images, AC05 preprocessing, AC07 private storage, AC08 manual/duplicate | ✅ | manual crop for plain photo ⬜ |
| AC09 guided manual workout | ✅ | `manual/` builder + templates (AMRAP 20, EMOM 10, For time 21-15-9, 5 × 5 strength, Tabata, Intervals, Custom); `ui/ManualWorkoutScreen.kt` (New workout next to Capture, catalog autocomplete, M/F loads, live validation, Create → Review); Review **Save & log result** → manual entry. Manual Fran compiles to the H1 golden part (levels aside); benchmarks (AC08) v1 |
| AX01 parser interface, AX02–AX04 cloud parser, AX05 validation, AX10 consent, AX11 key, AX16/AX18 jobs | ✅ | |
| AX03/AX15 confidence + assumptions in review | ✅ | low/medium nodes highlighted, assumption chips (no inline edit of chips yet) |
| AX09 cost estimate | 🟡 | estimate only; running total v1 |
| AX12/TS02 eval harness | ✅ | `:domain:eval`; real-API run not executed here (needs key) |
| AX06, AX13, AX14, AX17 | ⬜ | v1 |
| AE01–AE06 review/editor/levels/preview/send/run-on-watch | 🟡 | structured editor covers parts, recursive body, formats, measures, loads, sets, levels, splits, fallback; no drag-reorder of nested nodes, no per-item preview override |
| AL01–AL03 library/results/manual entry | ✅ | PRs/benchmarks/export v1 |
| AW01–AW05 device screen, delivery state, open app, sync on open, both UUIDs | ✅ | foreground receive only |
| AG01–AG04 profile/watch defaults/parser settings/delete-all | ✅ | |
| SY01–SY07, SY11–SY16 protocol | ✅ | outbox, acks, retries 30 s + 5/15/60, rate-limited restarts, dedupe, tombstones, chunks, sessions |
| SY08 simulator | 🟡 | TETHERED toggle; scripted round-trip (TS04) not automated here |
| TS01, TS08, TS09 (phone parts), TS12 (scoring/clock parts) | ✅ | `./gradlew :domain:test` |
| NF06/NF07 privacy/security | ✅ | |
