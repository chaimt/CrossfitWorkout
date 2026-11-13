# Workout planning and watch menus

Updated 2026-11-13. Implements REQ-DM14, REQ-AE06, REQ-AW04, REQ-WN01–WN03,
REQ-SY11 and REQ-SY14. The wire contract remains in [protocol.md](protocol.md).

## Plan contents and run order

Android owns compilation. `Part.effectiveRunOnWatch` includes warm-up by default;
explicitly disabled parts stay excluded, and cool-down remains opt-in.
`WorkoutSection.runOrdered` puts warm-up/skill first, weights second, metcon third,
then accessory/other and cool-down. A warm-up with a lifting format still belongs
to warm-up. Relative order within a section remains stable.

The Garmin plan preview lists exactly the received parts. **Run all** runs those
indices in order. It does not invent a missing warm-up or reinterpret part labels.
An active session uses its frozen snapshot; an updated inbox plan affects later runs.

## Warm-up editing and timers

Warm-ups offer **For time** and **Rounds** in New Workout and Review. Both always
show **Rounds** and **Time cap**. Existing round counts and caps are preserved;
when absent, default to one round and a visible 10:00 cap. Switching formats keeps
the movement tree, round group and cap. No Score selector or Manual score toggle
is shown for warm-ups.

`Warmups.prepare` normalizes existing, parsed and hand-built warm-ups to score
`none` in validation and compilation as well as Review. For time uses the existing
ForTime cap plus a no-score countdown. Rounds uses NotForTime with an accepted
count-up timer capped by fallback.capSec. Round counts use the existing body
round group. These warm-up timers do not require manual-score fallback acceptance;
non-warm-up fallback requirements remain in effect. Legacy timed warm-up formats
retain their timer structure, but their score is still none; the editor only offers
the two warm-up choices for subsequent edits.

A warm-up reaching its cap stays score-none; it never becomes a pending manual text score.
The watch omits the Score menu row for score-none results and labels the discard
action **Discard result**. Saving still records completion/timing and session data.
No wire fields are added; existing mode, segments and score-none encode the behavior.

## Explicit Sync now on Android

`AppViewModel.requestSync` clears the notice log, then refreshes previously sent
plans for the selected pairing generation, device and production/beta app. It does
not automatically send library workouts that have never been sent to that target.

For each stored plan:

1. Keep deliberate removals and archived/missing/unreviewed workouts out of refresh.
2. Determine today in the existing plan's IANA schedule time zone. If the existing
   plan date or the saved workout's date is before today, leave its payload unchanged.
   Today is included; future dates are included. Rescheduling a historical workout
   requires explicit **Send to watch** rather than automatic Sync refresh.
3. Recompile the current saved workout and athlete profile using the existing plan
   UUID and saved per-part level choices. For an undated workout, retain its previously
   sent date/time zone instead of moving it forward every time Sync is tapped.
4. Compare the compiled content at the existing revision. Unchanged content creates
   no new revision. Changed content is compiled and size-checked at revision N+1,
   queued durably, and recorded in the plan lineage. An explicit warm-up opt-out is
   respected; an old plan that omitted a default warm-up gains it on recompilation.
5. A validation/size/level error is shown in the notice log. Keep the old plan intact
   and continue processing other eligible plans.

Every notice line carries its captured local HH:mm:ss event time. Delivery rows display
their latest send/queue time and omit the Production prefix (beta retains a Beta marker).
The notice log identifies each plan by name and revision, distinguishes new sends from
updates, and reports queued/sending/SDK-sent/watch-confirmed states. SDK success is
labeled as waiting for confirmation; only an application acknowledgment produces
"sent to watch" / "updated on watch". Unchanged and skipped historical plans are
logged too; repeated identical state snapshots add no duplicate entries.

After refreshing, request the usual result drain/inventory exchange (`sync.req`).
Retries and inventory reconciliation can resend an existing historical payload;
they do not recompile it or change its revision. Production and beta queues remain
separate. Refreshing a production plan never moves it to beta or vice versa.

`PlanRefresher` contains the date/content/revision decision and is covered by tests
for past/today/future dates, unchanged plans, preserved levels, invalid changes and
upgrading a strength/metcon-only plan to warm-up → weights → metcon.

## Garmin menu presentation

Use native `WatchUi.IconMenuItem` rows with a bitmap-backed `HomeMenuIcon` Drawable.
The standard MenuItem ignores the icon option. Labels and subtitles remain visible;
color is supplemental, and all actions retain their existing identifiers/delegates.

| Menu / action | Shape | Color |
| --- | --- | --- |
| Home: Today | Calendar/check | Green |
| Home: Future | Forward arrow | Blue |
| Home: History | Clock | Amber |
| Home: Settings | Gear | Purple |
| Home: Sync now | Circular arrows | Cyan |
| Home: Recovered | Recovery arrow | Orange |
| Plan: Run all; part: Start | Play | Green |
| Plan: strength_sets / build_to_max | Dumbbell | Purple |
| Plan: manual timer with score none (for-quality work) | Flame | Green |
| Plan: other manual work | Clipboard | Amber |
| Plan: timed work | Stopwatch | Orange |
| Plan: Delete plan | Trash | Red |
| Part: editable timer parameters | Gear | Purple |

Plan icons derive from existing `mode` and score fields, not from letter labels
such as S/M or inferred workout names. A timed warm-up therefore uses a stopwatch;
a for-quality warm-up uses the flame. Instruction-only rows stay text-only for
readability. No payload field or schema change is needed for these icons.

Delete retains its second-press confirmation. Icons do not change navigation,
run selection, score handling or deletion semantics.

## Android Watch screen

The Watch screen identifies production/beta targets, links to the store, shows the
installed app version and marks beta deliveries without a redundant Production label.
The removal action reads **Remove workouts from watch**; its confirmation explicitly
states that saved watch results/history and phone data remain. Prefer the human-readable
`hello.app` version; label an SDK integer as a Garmin version code rather than
pretending it is a semantic version. Device discovery retains received app metadata.
SDK callback app IDs are normalized to compact lowercase to match durable queues.
