# CrossfitWorkout

Two apps that get the day's workout from the gym whiteboard onto a Garmin watch and the
results back to the phone. Specification: [`REQUIREMENTS.md`](REQUIREMENTS.md).

| Path | What |
|------|------|
| `android/` | **WOD Capture** — Android app (Kotlin, Compose): capture → Claude parse → review → load resolution → compile → send; history. See `android/README.md`. |
| `watch/` | **WOD Coach** — Connect IQ watch app (Monkey C, fr965): inbox, timers, set logging, FIT recording, results outbox. See `watch/README.md`. |
| `shared/` | Contracts: wire schemas, movement catalog, benchmarks, parser + wire fixtures. See `shared/README.md`. |
| `docs/protocol.md` | Normative phone ↔ watch wire contract (protocol v1). |
| `tools/` | Contract checks (Poetry): schema validation, fixture generator, catalog checks. |

## Quick start

```bash
task --list                 # all tasks
task contract:test          # shared schemas + golden wire fixtures + catalogs
task android:test           # JVM unit tests (domain + app)
task android:build          # debug APK
task watch:build            # fr965 debug .prg (needs Connect IQ SDK + watch/developer_key)
task watch:test             # watch unit tests in the simulator (simulator running)
```

Secrets are never committed: `watch/developer_key`, `android/local.properties`, keystores
and API keys are git-ignored (REQ-BT03). The Anthropic API key is entered in the app and
stored with the Android Keystore (REQ-AX11).
