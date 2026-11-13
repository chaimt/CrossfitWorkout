# CrossfitWorkout — instructions for Claude

Two apps: **WOD Capture** (Android, `android/`) and **WOD Coach** (Connect IQ watch, `watch/`),
sharing contracts in `shared/`. The specification is [`REQUIREMENTS.md`](REQUIREMENTS.md); see
`README.md` for layout and `task --list` for build/test/run commands.

## Every code change updates the requirements

For **every** request that changes code or behaviour, update `REQUIREMENTS.md` in the same
commit — no exceptions, even for small UI tweaks. Follow the "How to keep it up to date" rules
at the top of `REQUIREMENTS.md`:

1. **Find the requirement** the change touches and edit its text so it describes the new
   behaviour. Note the owner's request and date, e.g. "(owner request 2026-11-05)".
2. **New user-visible behaviour** with no existing requirement gets a new `REQ-xxx` ID — the next
   free number in its section. Never reuse or renumber an ID.
3. **Removed behaviour** is marked deprecated (`[_]`); keep the row, don't delete it.
4. **Status / phase**: keep `[x]` / `[ ]` / `[_]` and `MVP` / `v1` / `Later` accurate.
5. **Traceability**: once code exists, name the source file(s) in the requirement text.
6. **Change log**: add a dated row to the "Change log" table at the end of `REQUIREMENTS.md`
   summarising what changed and which `REQ-xxx` IDs were touched.

Before finishing, check: if the diff touches `android/`, `watch/`, `shared/` or `tools/` but not
`REQUIREMENTS.md`, the work is not done. Pure refactors with no behaviour change still get a
change-log row saying so.

## Other conventions

- Run the tests before committing: `cd android && ./gradlew :domain:test :app:testDebugUnitTest`
  (JDK 21; the taskfile picks it up), plus `task test` for contract and watch tests when touched.
- `shared/movements.json` and `shared/benchmarks.json` are generated: edit
  `tools/gen_catalog.py` and run `python3 tools/gen_catalog.py`. Catalog IDs never change once
  shipped.
- Bump `versionCode` / `versionName` in `android/app/build.gradle.kts` for each build handed to
  the owner to install.
