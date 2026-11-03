# Requirements review

Reviewed 2026-11-03 against `REQUIREMENTS.md`, including Appendices A–I.

**Update after repair:** the findings below are retained as the original audit. `REQUIREMENTS.md` now incorporates their specification resolutions, using explicitly marked defaults where owner preferences were unanswered. This does not mean the application or platform experiments are implemented or verified.

| Finding | Resolution in REQUIREMENTS.md |
|---|---|
| R01 recovery | REQ-WN07, REQ-NF01, REQ-WO06: incomplete recovery, durable events, bounded checkpoint target |
| R02 storage | REQ-WP04, REQ-WO03/05: byte-based admission, no automatic unsent eviction |
| R03 identity | REQ-DM15, REQ-SY11/12: stable plan lineage, immutable run snapshots, revisions |
| R04 sessions | REQ-WR07–10, REQ-SY16: state/clock rules, final session delivery |
| R05 protocol | REQ-SY02–06/11–16: bounded atomic plans, result chunking, acknowledgments and retry policy |
| R06 validation | REQ-AX05, REQ-DM18: structural blockers versus reviewed fallbacks |
| R07 interaction | REQ-WS02/03, REQ-TS12: one-press default, edit/undo, unresolved-value exception |
| R08 scoring | REQ-DM21–24: typed observations, aggregates, missing values, counting rules |
| R09 statuses | REQ-DM09/25, REQ-WM02: partial/interrupted/capped states and correction semantics |
| R10 loads | REQ-DM10/11/19: physical versus prescription conversion, inventory rounding, manual runtime loads |
| R11 schema/editor | REQ-DM04/16/17, REQ-AE02, §7.3: authoritative body, diagnostics and corrected example |
| R12 unusual formats | REQ-DM18: explicit loss-of-automation disclosure and fallback limits |
| R13 entry timing | REQ-WM14, REQ-WR08: running interval entry versus paused result entry |
| R14 bounds | REQ-WE03/04, H9: numeric/time bounds and pending phone entry |
| R15 parse jobs | REQ-AX04/16/18: consent recheck, revisions, cancellation and finite retries |
| R16 edit/delete | REQ-DM27, REQ-SY12: phone authority and durable tombstones |
| R17 PRs | REQ-AL05: prescription signatures, eligibility and measured rep-max policy |
| R18 dates/targets | REQ-DM02, REQ-WN01, REQ-SY11: schedule timezone and per-target lineage |
| R19 privacy | REQ-NF06, REQ-DM27/28: intended data flows, backup and deletion |
| R20 feasibility | REQ-TS07/11, Appendix I evidence note: explicit device/provider gates |
| R21 phases | Corrected quick-timer, strength, partner and fixture scope; §11 and H1–H10 |
| R22 acceptance | REQ-TS08–12, §6.12 clock example: reproducible and failure-oriented checks |
| R23 migrations | REQ-DM26/28, REQ-NF10: version/migration/import contracts |
| R24 movement IDs | REQ-DM20: stable variant identities and alias rules |

Current owner defaults are in §13.1 of the requirements. Hardware verification, schema artifacts, real-photo evaluation and application tests remain future implementation/release work. The verdict below describes the **pre-repair** document.

**Verdict: broad functional coverage, but not yet implementation-ready.** The intended product is understandable. Several observable behaviors, persistence contracts, and release boundaries admit incompatible implementations. The proposals below are recommendations, not approved changes to the requirements.

This is a specification review, not an implementation audit. This directory contains the requirements document and no application code. External research in the appendices is not comprehensively revalidated by this review; platform-dependent guarantees still need the device experiments listed below.

## Understood product contract

- Android owns capture, parsing, athlete review, load resolution, workout history, and compilation of executable watch plans.
- The Garmin app executes reviewed plans, accepts actual performance data, records an optional FIT activity, and retains results until Android durably acknowledges them.
- A workout can contain several separately scored parts. “Run all” normally produces one Garmin activity.
- Cloud parsing is optional and requires consent and an athlete-supplied key. Manual creation and execution of previously synced plans must work offline.
- Current defaults are personal use, FR965 first, English notation, per-implement gear units, and a separate watch app. These are assumptions pending Q1–Q4, not confirmed owner decisions.
- MVP includes the basic end-to-end workflow. Offline parsing, benchmarks/PRs, richer strength execution, and quick timers are mostly v1.

## Decisions required before implementing the core contracts

### R01 — Crash recovery and the “never lose” guarantee disagree

**References:** G6, anti-goals, REQ-WN07, REQ-WO01, REQ-NF01.

NF01 promises at most one second of loss, but WO01 persists only at part completion or abandonment. WN07 relies on shutdown handling and restarts the part. No running checkpoint, restoration contract, or behavior after abrupt power loss is specified.

**Proposed resolution:** persist accepted user events and an active-run checkpoint; separately define recovery of workout data and recovery of a FIT recording. On reopening, offer recovery or an incomplete result. Any continuation that requires a new FIT activity must say so. Verify the one-second guarantee on hardware before retaining it as a release requirement.

**Acceptance:** terminate immediately after logging a set, during rest, during result entry, and between parts. Previously accepted data survives without duplicate sets or invented elapsed time. Owner must decide whether continuation is required or an incomplete result is sufficient.

### R02 — Storage-full behavior can violate result retention

**References:** REQ-WP04, REQ-WO01–03, REQ-SY06, G6.

The shared history/outbox limit is 50 results, but eviction and admission rules are absent. One result is budgeted at 1 KB despite potentially containing many sets, laps, interval scores, and actual loads. Open-ended build-to-max and extra sets have no result-size ceiling.

**Proposed resolution:** never evict an unacknowledged result automatically; prune acknowledged history first. Reserve capacity before starting a workout, define a tested maximum supported run, and provide a clear sync-required state when capacity is insufficient. Size the actual serialized records, including indices and active checkpoints.

**Acceptance:** with 50 unsent results, starting or saving cannot silently delete one. A maximum-size supported workout can always finish and persist.

### R03 — Editable workouts have no immutable execution identity

**References:** REQ-DM02/09/14, REQ-AL01/03, REQ-WN03, REQ-SY02–05.

Deduplication uses `id`, which is described as either a message or object ID. Editing and resending the same workout may be discarded as a duplicate; a late older send may replace a newer plan. Historical results have no immutable prescription snapshot or plan revision.

**Proposed resolution:** distinguish message ID, workout ID, plan revision, session/run ID, part ID, item ID, and result revision. Bind each run to a frozen plan snapshot, including local timer edits and load overrides. Define ordering and stale-message rejection.

**Acceptance:** send revision 2 and then delayed revision 1; revision 2 remains current. Editing the phone workout while a watch run is active cannot change that run or its history.

### R04 — Multi-part session boundaries are undefined

**References:** REQ-DM09, REQ-WN02, REQ-WM01/12, REQ-WE06, REQ-WR01–05, REQ-WO04.

There is no Session entity although one FIT activity spans several part results. It is unclear whether transition screens, result entry, prescribed inter-part rest, and pauses count toward FIT timer time, workout elapsed time, or scores. “Main metcon” is undefined when several metcons exist. Session HR/calories and part HR/calories are not distinguished.

**Proposed resolution:** define an explicit session state machine and separate wall time, active timer time, prescribed work/rest time, and corrected score time. Define when FIT starts, pauses, resumes, saves, and discards, and what happens if a part is skipped or its result discarded.

**Acceptance:** run strength → result entry → prescribed rest → metcon, pause once, skip an accessory, and finish. Specify exact expected clocks, part statuses, and FIT boundaries.

### R05 — Wire protocol cannot yet guarantee the specified delivery behavior

**References:** REQ-SY02–07, §7.2–7.3, REQ-WP04.

`plan.part` is required but absent from the message-type list. Chunk IDs, chunk acknowledgments, assembly expiry, duplicate/out-of-order handling, integrity checking, and atomic activation are missing. Reassembling an oversized plan conflicts with one-plan-per-key limits. `profile.put` and `plan.del` have no acknowledgment type despite all senders waiting for acknowledgments. One-message-at-a-time does not alone prevent duplicate retries accumulating after an acknowledgment is lost.

**Proposed resolution:** define envelopes and acknowledgments for every durable operation, per-device queues, bounded retries/backoff, message-versus-object identity, and an atomic chunked-transfer contract. Negotiate capabilities as well as protocol version. Measure bytes with the actual transport encoder.

**Acceptance:** disconnect after each transfer step; duplicate and reorder chunks; lose an acknowledgment; restart either app; deliver an oversized part. Only a complete validated plan becomes runnable.

### R06 — Invalid structures are only warnings

**References:** anti-goals, REQ-AX04/05, REQ-AE05, §4.2.

AX05 says even schema and missing-duration failures do not block sending. That leaves the compiler's behavior undefined for a plan it cannot execute. Review confirmation cannot make malformed timer parameters executable.

**Proposed resolution:** distinguish blocking structural errors from advisory plausibility warnings. Block compilation of invalid executable structures. Offer an explicit, reviewed text-plus-timer fallback for unsupported formats; never silently drop content or truncate prescriptions.

**Acceptance:** a missing AMRAP duration, zero interval, or invalid group is either repaired or deliberately converted to a valid fallback before sending.

### R07 — One-press strength logging currently requires two presses

**References:** G3, S3, REQ-WN06, REQ-WS02, §6.3.

The athlete presses once to finish the set, then again to accept the confirmation. The requirement counts only the second press.

**Owner decision:** either one press immediately records the displayed values and starts rest, with correction/undo afterward, or routine logging requires two presses and the goal must change. Also define when rest begins: actual set completion or confirmation.

**Acceptance:** count physical presses from the running set screen through durable recording and rest start.

### R08 — Score representation does not cover the declared formats

**References:** REQ-DM05/07/09, REQ-WM03/05/06, Appendices C, G, H.

Rounds+reps assumes a countable round, but rounds may mix running distance, calories, timed holds, and reps. Progressive rounds have changing totals. The Result score fields do not explicitly represent text, minutes+reps, volume, or multi-lift totals. Tabata This! needs a sum of per-block minima, which is more specific than one aggregation enum.

**Proposed resolution:** define typed raw per-item/per-interval observations separately from a derived score; specify counting units, conversion rules, aggregation nesting, skipped versus zero entries, and progression. Unsupported score formulas must use a labeled manual score rather than a fabricated conversion.

**Acceptance:** define exact input and output for H4's progressive ladder, a run-plus-reps AMRAP, Tabata This!, and an interval workout with one missing interval score.

### R09 — Completion, failure, and corrections lack semantics

**References:** REQ-DM09, REQ-WM02/12, REQ-WS02/04/05, REQ-WE04/06.

`capped` cannot distinguish finished, stopped early, abandoned, skipped, or recovered runs. A partially completed strength set is not simply made/missed. An accidental LAP has no undo behavior. Finishing exactly at the cap has no event-order rule.

**Proposed resolution:** define run and set statuses, partial reps, no-rep corrections, undo, timer-boundary precedence, and validation of corrected scores. Preserve measured timing separately from edited score timing.

**Acceptance:** stop early before the cap; finish exactly at the cap; undo an extra round; record two of three target reps; correct a finish time without rewriting FIT timing.

### R10 — Load resolution needs an executable policy

**References:** REQ-DM06/10/11, §4.1, REQ-WS05/07, REQ-WE01, Appendix D.

Competition-equivalent loads and physical unit conversions serve different purposes but currently share a rule. Gear inventory, round-up/down ties, total versus per-side increments, and dumbbell-per-hand versus combined load are unspecified. Profile “board unit” is referenced but not declared. Percentage ranges lack a selection rule. Back-off loads relative to a top set completed on the watch cannot be precomputed by the phone.

**Proposed resolution:** preserve written load, resolved prescription, and actual load separately. Define implement-specific equivalence versus arithmetic conversion, rounding, minimum loadable bar, and per-hand semantics. For runtime-dependent back-off sets, allow manual entry in MVP or explicitly permit the required watch-side calculation.

**Acceptance:** mixed kg plates/lb dumbbells; two dumbbells; a target below bar weight; a percentage exactly between increments; no stored RM; a top set heavier than planned followed by −10%.

## Important gaps to close before feature acceptance

### R11 — The canonical schema and editor do not yet describe the same structures

**References:** REQ-DM03–08/14, REQ-AX01/03, REQ-AE02, §7.3.

Both `items[]` and an ordered body tree are described without a single authoritative relationship. Split calories/heights/targets, per-item rep schemes, stable references for level overrides, confidence/unparsed/assumption locations, and score checkpoint IDs are unspecified. The watch example has one global unit despite per-implement units. The editor does not explicitly support nested groups, timed rests, interval segments, or all load types.

**Resolution:** write a schema-level example for each H fixture and an editor operation for every editable node. Preserve unsupported fields on edits. Treat the wire example as illustrative until it carries stable IDs and per-value units.

### R12 — Sequential timer segments cannot alone express every listed benchmark

**References:** REQ-DM04/05, REQ-WM01/09, Appendix G Kalsu and Murph, Appendix B.

Kalsu requires periodic interruptions inside a continuing For Time task. Murph permits partitioning. Dynamic rest ratios and runtime-dependent loads are also not captured by a plain list of fixed work/rest segments.

**Resolution:** specify dedicated execution behavior or visibly mark these as text-plus-timer fallbacks. For each format, say exactly which cues, navigation, and scoring survive fallback.

### R13 — Timed execution while entering data is unspecified

**References:** REQ-WM04/06, REQ-WS03, §6.3.

An interval can end while its score picker is open. It is unclear whether the picker dismisses, the next interval starts behind it, or timing pauses. Strength rest “start next set” and overtime transitions also need explicit states.

**Resolution:** keep prescribed interval timing independent of entry UI, define boundary cues/navigation, and retain unfinished entry for later. Specify automatic versus manual next-set start.

### R14 — Picker bounds exclude listed use cases

**References:** REQ-WE03/04, Appendix G Chad, Jackie, Jerry.

The 0–999 picker cannot enter 1,000 step-ups or a 2,000 m distance. Integer-only distances cannot represent all displayed units. Time-field bounds and precision are absent.

**Resolution:** define bounds and steps per measure and unit, with explicit handling beyond the watch's supported maximum. Test 1,000 reps, 2,000 m, fractional kilometres, zero, and multi-hour durations.

### R15 — Deferred parsing can race with manual work or revoked consent

**References:** REQ-AX04/08/10/16, REQ-AC07, REQ-AE05.

A queued job may finish after the athlete manually edits/sends the draft, deletes its image, cancels parsing, or disables cloud parsing. “After a failed parse” also includes failures that connectivity will not resolve.

**Resolution:** define draft/job revisions and states; check current consent and source existence at execution; keep stale parser output as an optional alternative rather than overwriting edits. Bound deferred retries, distinguish retryable errors, and provide explicit retry/cancel actions.

### R16 — Phone result edits and deletes can be undone by retransmission

**References:** REQ-AL01/03, REQ-SY04, REQ-WO02.

No conflict policy exists when a result is edited or deleted on Android and the watch resends its original after a lost acknowledgment. Deleting a workout with historical or pending results is also undefined.

**Resolution:** define phone authority after ingestion, result revisions, durable deduplication/tombstones, cascading versus archival deletion, and whether corrected results return to the watch.

### R17 — PR comparisons need prescription identity and eligibility

**References:** REQ-AL05, REQ-DM10/13, REQ-AX06.

“Benchmark per level” can group materially different custom workouts, caps, movement substitutions, or loads. Corrected/deleted results must recalculate PRs and derived RMs. Multi-rep results are not automatically measured 1RMs.

**Resolution:** define comparison signatures, valid completed-set criteria, manual versus measured versus estimated RMs, and opt-in RM updates. Exclude incomplete or modified benchmark attempts from canonical PRs unless explicitly eligible.

### R18 — Date and multi-device semantics are missing

**References:** REQ-DM02/09, REQ-AX17, REQ-AW01/05, REQ-WN01.

“Today” has no timezone/travel rule. Delivery state is not scoped to device and app UUID. Multiple runs of the same workout, an unpaired/replaced watch, and different beta/production inboxes need distinct identities.

**Resolution:** distinguish scheduled local date from event timestamps, define device targeting and queue cleanup, and key delivery by device plus app UUID plus plan revision.

### R19 — Privacy and backup scope needs explicit wording

**References:** REQ-AC07, REQ-AX10/11, REQ-AG04, REQ-NF06.

“Only outbound data is the parse request” omits deliberate watch messaging and Garmin activity sync. App-private data retention and Android backup policy are not specified. Deletion of originals, processed images, pending jobs, and exported copies has no lifecycle contract.

**Resolution:** enumerate intended data flows, backup exclusions, retention, and deletion behavior. State what remains in Garmin or user-created exports after local deletion. Recheck consent before deferred uploads.

### R20 — External APIs need explicit readiness gates

**References:** §3.3, REQ-AP06, REQ-AX02/09, REQ-SY08/10, Appendix I.

The document includes precise versions, prices, and platform behavior alongside acknowledged inferences. Those references are useful research, but are not evidence that this app's end-to-end path works.

**Resolution:** before committing the build architecture, demonstrate physical FR965 messaging with app open/closed, acknowledgment/retry behavior, recording interruption behavior, and worst-case storage. Pin a tested parser request/schema and record measured latency/cost. Keep unresolved SDK behaviors labeled as experiments.

## Release and validation inconsistencies

### R21 — Phase labels conflict

| Area | Conflicting requirements | Required clarification |
|---|---|---|
| Quick timers | REQ-WP05 includes them in MVP; REQ-WQ01 and §11 place them in v1 | Exclude from MVP promise or promote them |
| Complexes/supersets | REQ-WS01 includes them in MVP; REQ-WS06 and §11 say v1 | Specify MVP fallback explicitly |
| Partner support | REQ-DM04 labels partner v1; REQ-WM13 and Appendix A say Later | Separate representation from execution/logging |
| Offline behavior | Summary describes OCR fallback; parser requirements put it in v1 | State MVP offline path is manual entry/saved plans |
| Format testing | REQ-TS03/05 imply every format in MVP, including deferred formats | Phase the tests and state fallback expectations |
| Fixtures | MVP requires 10; Appendix H provides 8 text cases; S1 requires ≥30 real photos | Identify missing fixtures and phase S1 |
| Planning and history | G4 promises PRs; §11 defers them | Mark product goals versus MVP guarantees |

### R22 — Acceptance measures are not reproducible yet

**References:** S1–S5, REQ-AX08/12, REQ-NF02/03/05, REQ-TS01–06.

Define timing start/end points, representative phone/network/board complexity, cold versus cached parser runs, repeated stochastic evaluation, and the meaning of one manual fix. S3's six presses needs a concrete score-entry scenario. Battery comparison needs matching settings, duration, and repeated runs.

Add boundary, malformed-message, storage-full, process-death, lost-acknowledgment, stale-update, migration, and clock-change scenarios. A 30-session soak is useful evidence, not proof of an unrestricted “never lose” claim.

### R23 — Schema upgrades and backup restore have no migration contract

**References:** REQ-DM01, REQ-NF10, REQ-AL06, REQ-SY07.

Integer schema versions are described using “major” terminology without compatibility rules. Rejecting unknown versions does not define migrations of stored workouts, results, phone queues, or watch checkpoints. Import needs merge/duplicate and incompatible-data behavior.

**Resolution:** specify supported readers/writers, durable migrations, recoverable failure behavior, and restore conflict rules. Do not drop unsent results during upgrades.

### R24 — Catalog aliases can merge distinct movements

**References:** REQ-DM12, Appendix E.

Several rows combine variants under one name/ID, such as hollow rock/hold, dumbbell presses, and GHD extension variants. This can lose distinctions needed for measures, display, and lift PRs.

**Resolution:** define separate canonical movement identities or explicit variant fields; keep true synonyms as aliases. Derive IDs from stable curated data rather than mutable display names.

## Owner decisions still open

The existing Q1–Q4 remain unanswered. In addition:

1. **Routine strength logging:** one press saves displayed values, or always confirm?
2. **Interruption recovery:** continuation required, or retain an incomplete result and restart?
3. **Storage exhaustion:** recommended behavior is to require syncing before admitting a run that cannot be retained.
4. **Unsupported formats:** is a clearly labeled text-plus-timer/manual-score fallback acceptable in MVP?
5. **Workout clocks:** which rests/transitions should count in the recorded activity and score?

Questions 1 and 2 were raised during this review; no answers are assumed here. Remaining technical details can generally be resolved by the implementer, provided the resulting contracts are written and tested.

## Minimum evidence for implementation readiness

1. Resolve R01–R10 and reconcile the phase matrix.
2. Add canonical workout, watch-plan, session/result, and message examples for every H fixture, plus at least two failure/boundary fixtures.
3. Define the watch/session state machine and durable delivery/update contract.
4. Verify the physical phone/watch path and maximum supported plan/result sizes.
5. Convert the acceptance scenarios above into the phased test plan.

The functional intent is understood. Claims that all requirements are unambiguous or that there are no gaps would be premature until these decisions are recorded.
