# CrossfitWorkout

Two apps that get the day's workout from the gym whiteboard onto a Garmin watch and the
results back to the phone. Specification: [`REQUIREMENTS.md`](REQUIREMENTS.md).

| Path | What |
|------|------|
| `android/` | **WOD Capture** — Android app (Kotlin, Compose): capture → Claude parse → review → load resolution → compile → send; history. See `android/README.md`. |
| `watch/` | **WOD Coach** — Connect IQ watch app (Monkey C, fr965): inbox, timers, set logging, FIT recording, results outbox. See `watch/README.md`. |
| `shared/` | Contracts: wire schemas, movement catalog, benchmarks, parser + wire fixtures. See `shared/README.md`. |
| `docs/design.md` | Plan run order, scheduled Sync refresh, and Android/Garmin menu design. |
| `docs/protocol.md` | Normative phone ↔ watch wire contract (protocol v1). |
| `tools/` | Contract checks (Poetry): schema validation, fixture generator, catalog checks. |

## Quick start

```bash
task --list                 # all tasks
task build                  # Android debug APK + fr965 debug .prg
task test                   # contract + Android JVM + watch simulator tests
task release                # unsigned release APK + store .iq package

task emulator               # WOD Capture in the Android emulator (AVD created on first run, ~2 GB)
task watch:deploy-simulator # WOD Coach in the Connect IQ simulator
task sim                    # phone app + simulator tethered together (adb forward 7381)
task deploy                 # USB phone (install + launch) and USB watch (copy .prg)
```

Prerequisites: JDK 21 for Gradle (auto-detected; JDK 27 cannot run AGP), the Android SDK
(`android/local.properties` → `sdk.dir`), the Connect IQ SDK, and `watch/developer_key`.
The Connect IQ simulator cannot run tests while the macOS screen is locked.

Secrets are never committed: `watch/developer_key`, `android/local.properties`, keystores
and API keys are git-ignored (REQ-BT03). The Anthropic API key is entered in the app and
stored with the Android Keystore (REQ-AX11).
