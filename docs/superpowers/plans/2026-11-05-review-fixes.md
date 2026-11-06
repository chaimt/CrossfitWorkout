# Review Fixes Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement task by task.

**Goal:** Resolve the five findings from the repository Bugbot review.

**Architecture:** Preserve durable recovery state until writes succeed. Keep sync state on one executor, recover interrupted sends, commit parse completion against current database state, and persist only changed outbox rows.

**Tech Stack:** Kotlin, Room, JVM tests, Monkey C, Connect IQ simulator.

**Spec:** REQUIREMENTS.md (WO01/WO05/WO06, AX18, SY13/SY14, AP06).

## Global Constraints

- Preserve protocol v1 and stable retry message IDs and bodies.
- Never prune the latest authoritative plan/tombstone used by hello reconciliation.
- No dependency changes unless needed to exercise database behavior.
- Update requirements and run Android, contract, and watch tests.

## Review Focus

- Storage failures between history and outbox writes must remain retryable.
- Lost callbacks and process restart must not wedge a target.
- Late status callbacks must not overwrite an application acknowledgment.
- Cancellation/deletion/manual edits during HTTP must survive completion.
- Compaction must preserve failed-newer-revision fallback and target isolation.

## Tasks

1. **Outbox recovery and callback confinement**
   - [x] Add failing tests in `SyncEngineTest.kt` for restart, lost callbacks, late statuses, and executor dispatch.
   - [x] Recover persisted SENDING entries and time out live sends in `Outbox.kt`.
   - [x] Dispatch callbacks via the sync executor in `SyncEngine.kt` and `WodApp.kt`.
   - [x] Run Android tests.
2. **Parse completion**
   - [x] Add regression coverage for cancelled/deleted/edited drafts.
   - [x] Move completion into a Room transaction in `Repositories.kt`, reading the current job and draft; route `ParseWorker.kt` through it.
   - [x] Make cancellation atomic and reject late HTTP success after cancellation.
   - [x] Run Android tests.
3. **Outbox persistence**
   - [x] Test unchanged rows, changed rows, removals, and retained latest revisions.
   - [x] Update `OutboxStore` and DAO for incremental persistence; compact obsolete terminal entries safely.
   - [x] Run Android tests.
4. **Watch finalization**
   - [x] Add failing storage-write regressions for normal finalization and interrupted-part saving.
   - [x] Preserve retry state in `Session.mc`, including checkpoint and snapshot.
   - [x] Run watch simulator tests and build.
5. **Verification**
   - [x] Update `REQUIREMENTS.md` with source traceability and change log.
   - [x] Run full tests and Android build, then review the diff.

## Execution notes

The user explicitly authorized all five fixes. Execute in this workspace without a separate approval handoff or commits; preserve unrelated work. Record test evidence below.


## Validation evidence

- Android: 202 domain tests and 16 app tests pass. Reproduced missing send callbacks, restart recovery, callback dispatch, successful-request cancellation, compaction, and exhausted-drain persistence failures before their fixes.
- Watch: all 54 simulator tests pass, including three reproduced finalization failures. The standalone debug build passes with existing compiler warnings.
- Shared contracts: 38 tests pass under Poetry with Python 3.11.16.
- Inspected Room-generated transaction wrappers for start/cancel/complete parse and outbox deltas.
- Independent review: no new actionable correctness bugs. Pre-existing hello fallback behavior for failed newer revisions remains outside these fixes.
- Native UI inspection could not run because the computer-use tool timed out. The pending-save state is covered by simulator tests, not a visual assertion.
- Test signing used a temporary generated key because the checkout had no watch developer key; it is not a release signing identity.

- Final Android lint: 0 errors, 10 existing warnings in unchanged code/configuration. Final watch test also exercises the pending-save screen START delegate. Temporary signing key and link removed after validation.
