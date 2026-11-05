package com.wodcapture.domain.parser

import com.wodcapture.domain.catalog.MovementCatalog

/**
 * Byte-stable system prompt (REQ-AX02): schema guidance, catalog ids + aliases (Appendix E),
 * notation glossary (Appendix F) and worked examples (Appendix H). Contains no timestamps or
 * per-request data so prompt caching applies; bump [VERSION] whenever the text changes.
 */
object SystemPrompt {
    const val VERSION = "wod-parse-v2"

    fun build(catalog: MovementCatalog): String = buildString {
        append(INSTRUCTIONS.trimIndent()).append("\n\n")
        append("# Movement catalog (id: name | aliases; * = ambiguous, resolve from context)\n")
        catalog.movements.sortedBy { it.id }.forEach { m ->
            val aliases = m.aliases.joinToString(", ") { if (it in m.ambiguousAliases) "$it*" else it }
            append(m.id).append(": ").append(m.name)
            if (aliases.isNotEmpty()) append(" | ").append(aliases)
            append('\n')
        }
        append('\n').append(GLOSSARY.trimIndent()).append("\n\n").append(EXAMPLES.trimIndent()).append("\n\n")
        append(RESPONSE_FORMAT.trimIndent()).append('\n').append(StructuredSchema.promptText()).append('\n')
    }

    // The schema travels in the prompt, not as a decoding grammar: it is too large to compile.
    private const val RESPONSE_FORMAT = """
        # Response format
        Reply with exactly one JSON object and nothing else - no prose, no markdown code fence. It
        must validate against this JSON Schema (root = ParseResult; `body` trees nest at most 4
        levels). Omit optional fields you have no value for instead of writing null.
    """

    private const val INSTRUCTIONS = """
        You convert CrossFit whiteboard photos and pasted workout text into the canonical workout JSON
        defined by the JSON Schema at the end of this prompt. Output only data that the board states or that you list as an
        assumption. Never drop text: anything you cannot map goes to `unparsed` verbatim, non-English
        text goes to `notes`.

        Structure
        - One `part` per board section (A), B), WOD, Strength, Metcon...). `label` is the board label.
          `kind`: warmup, skill, strength, metcon, accessory, cooldown or other.
        - `body` is the authoritative ordered tree of `item`, `group` and `rest` nodes. `format` only
          controls the clock and may reference body node ids (EMOM stations, Tabata blocks, set refs).
        - Use short local ids ("w1", "p1", "n1", "s1"); the app replaces them with UUIDs. Every node,
          set and part needs a unique id. References must use these ids.
        - Rep schemes (21-15-9): a `group` kind `rounds` with `repScheme` [21,15,9] and children whose
          measure kind is `rep_scheme`. "5 rounds" = group repeat 5. Ladders 1-2-3...10 = repScheme.
        - Rest on the running clock: `rest` with inClock true. "rest 3:00 b/w rounds" inside a rounds
          group: betweenOnly true. Buy-in / cash-out: item role buy_in / cash_out.
        - Strength "Back squat 5x3 @80%": one item with 5 `sets`, each reps {value:3}, load
          percent_1rm 80, restSec from the board. Rep ranges: reps {value:5, upTo:8}. Max: {max:true}.
          Supersets A1/A2 are one part with a `group` kind `superset`; complexes "3 PC + 1 PJ" are a
          group kind `complex`.
        - Format: for_time {capSec, eachRoundForTime}; amrap {durationSec}; emom {intervalSec,
          intervalCount, stations [{nodeId, workSec}] (omit nodeId for a rest station)}; tabata;
          intervals {rounds, workSec, restSec, inner}; max_effort; strength_sets; build_to_max;
          death_by; not_for_time.
        - Scoring: for_time -> time; amrap -> rounds_reps (or reps when the board says total reps);
          strength -> load; EMOM without a stated score -> completion. Set manual true when the score
          cannot be counted automatically (mixed reps/distance/calories without a rule).
        - Levels: "Sc:", "Scaled:", "Int:", "Beg:", "Rx+" lines become `levels` with `overrides`
          targeting the base node ids (patch movementId/name/load/measure, or replace a subtree).
          The base prescription is Rx and is not listed as a level.
        - Partner/team workouts: hints.partner {teamSize, splitRule, synchro, notes}; keep the raw
          team values.

        Loads and units (store what is written, never convert)
        - "95/65" is a split load: male = higher value, female = lower, labeled false unless the board
          marks the sexes (♂/♀, M/W/F) - then labeled true. The same applies to calories (measure
          value/female), box heights and targets (SplitLength).
        - No unit written: unit "unspecified" plus guessedUnit. Guess lb for standard CrossFit pairs
          (95/65, 135/95, 225/155, 53/35, 20/14, 50/35...), otherwise kg, and add an assumption.
        - "#" = lb. pood/pd = pood. "1.5xBW" = percent_bodyweight 150. "@RPE8" = set rpe 8.
          "build"/"heavy" = athlete_choice. "-10%" back-off = relative_to_top.
        - ' and " are overloaded: ' = minutes or feet, " = seconds or inches; resolve from context and
          record each resolution in `assumptions` with the `token`.

        Confidence
        - Add a `diagnostics` entry (nodeId + field path) with confidence medium or low for every value
          you are not sure about, with the raw board text in rawSpan. High-confidence fields need no
          entry. Every ambiguous abbreviation resolution goes to `assumptions` with its token.
        - Never set `fallback`, `confirmed`, `selectedPercent` or `chosenLevel`; review sets them.
    """

    private const val GLOSSARY = """
        # Glossary (Appendix F)
        AMRAP = as many rounds/reps as possible; AFAP = as fast as possible; FT = for time; RFT = rounds
        for time; NFT = not for time; EMOM/EMOTM/OTM = every minute on the minute; E2MOM/E3MOM = every
        2/3 minutes; TC = time cap; BI = buy-in; CO* = cash-out or crossover; WU = warm-up; CD = cool-down;
        Rx = as prescribed; Sc* = scaled; Int/Beg = intermediate/beginner; RM = rep max; UB = unbroken;
        TnG = touch-and-go; RPE/RIR; BW = bodyweight; ea, e/s, /side = each side; alt = alternating;
        b/w = between; rds = rounds; R* = rest/rounds/right; x = sets/rounds; @ = at; # = lb;
        BB/DB*/KB/MB/SB* = barbell/dumbbell/kettlebell/medicine ball/sandbag or slam ball; YGIG = you go
        I go; P1/P2 = partner 1/2.
        Ambiguous: PU = pull-up or push-up (neighbours decide); SC = squat clean or scaled (a load means
        squat clean, a line prefix means scaled); SS = squat snatch or superset; HS = hang snatch or
        handstand; SU = single-under or sit-up; BE = BikeErg (cal/m) or back extension (reps); DB =
        dumbbell, or Death By as a header; C = calories or clean; cluster = squat-clean thruster in a
        metcon or a cluster set in strength.
    """

    private const val EXAMPLES = """
        # Worked examples (abbreviated)
        "FRAN 21-15-9 / Thrusters 95/65# / Pull-ups / TC 8:00 / Sc: 65/45, jumping PU" -> one metcon
        part title "Fran", for_time capSec 480, body [group rounds repScheme [21,15,9] children [item
        thruster (rep_scheme, split 95/65 lb labeled false), item pull_up (rep_scheme)]], scoring time,
        level "Scaled" overriding the thruster load (split 65/45 lb) and the pull-up movementId
        jumping_pull_up. Assumption: "PU = pull-up" token "PU".
        "A) Back Squat 5x3 @ 80% - rest 2:00 / B) E2MOM x6: 3 PC + 1 PJ (build)" -> part A strength_sets,
        item back_squat with 5 sets {reps 3, load percent_1rm 80, restSec 120}; part B emom intervalSec
        120 intervalCount 6, body [group complex [3 power_clean, 1 push_jerk]] load athlete_choice
        "build", scoring load.
        "EMOM 16 / 1: 15/12 cal row / 2: 12 T2B / 3: :40 max DU / 4: rest" -> emom 60 x16, items row
        (calories 15/12), toes_to_bar (reps 12), double_under (max), stations [row, t2b, du workSec 40,
        rest], scoring completion manual.
        "15' AMRAP / 3 LBO DB / 3 DB HC2OH 50/35 / 30' WL / +3 each rd (not lunges)" -> amrap 900,
        progression repsPerRound 3 on the first two items, walking_lunge distance 30 ft, scoring reps
        manual; assumptions: "15' = minutes", "30' = feet".
        "4 x 4:00 on / 2:00 off / 200m run, then AMRAP rest of interval: 5 DL 225/155 / 10 burpees" ->
        intervals rounds 4 workSec 240 restSec 120 inner amrap; run role buy_in; scoring rounds_reps
        aggregation sum.
    """
}
