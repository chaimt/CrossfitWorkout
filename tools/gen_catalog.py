"""Generate shared/movements.json (REQ-DM12/DM20) and shared/benchmarks.json (REQ-DM13).

Source of truth is REQUIREMENTS.md Appendix E (movements) and Appendix G (benchmarks),
transcribed here as data. Slash-separated families from Appendix E are expanded into
distinct stable IDs, because variants that change execution, measure or PR eligibility
must not share an ID (REQ-DM20). IDs are snake_case of the display name and must never
be renamed once shipped; edit display names or aliases instead.

Run: python3 tools/gen_catalog.py
"""

from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parent.parent
CATALOG_VERSION = 1

# Ambiguous abbreviations (Appendix E "*", Appendix F) — resolved from context (REQ-AX15).
AMBIGUOUS = {"SS", "HS", "SB", "SC", "PU", "SU", "BE", "CO", "DB", "R", "C", "cluster"}

# (name, aliases, category, measure, loadable, implement, extra)
# measure: reps | cal | m | ft | s | steps ; "measures" in extra lists alternatives.
# loadable: Y | N | optional
M: list[tuple[str, list[str], str, str, str, str, dict[str, Any]]] = [
    # E.1 Olympic lifting
    ("Snatch", ["SN", "Sn", "SQ SN", "squat snatch", "full snatch", "SS"], "olympic", "reps", "Y", "barbell", {}),
    ("Power snatch", ["PS", "PSN"], "olympic", "reps", "Y", "barbell", {}),
    ("Hang snatch", ["HSN", "HS"], "olympic", "reps", "Y", "barbell", {}),
    ("Hang power snatch", ["HPS"], "olympic", "reps", "Y", "barbell", {}),
    ("Hang squat snatch", ["HSS"], "olympic", "reps", "Y", "barbell", {}),
    ("Muscle snatch", ["MSN"], "olympic", "reps", "Y", "barbell", {}),
    ("Split snatch", [], "olympic", "reps", "Y", "barbell", {}),
    ("Snatch balance", ["SnB", "SB"], "olympic", "reps", "Y", "barbell", {}),
    ("Overhead squat", ["OHS"], "olympic", "reps", "Y", "barbell", {}),
    ("Snatch pull", ["SN pull"], "olympic", "reps", "Y", "barbell", {}),
    ("Snatch-grip deadlift", ["SGDL"], "olympic", "reps", "Y", "barbell", {}),
    ("Sots press", [], "olympic", "reps", "Y", "barbell", {}),
    ("Clean", ["CLN", "CL", "SQ CL", "squat clean", "SC"], "olympic", "reps", "Y", "barbell", {}),
    ("Power clean", ["PC"], "olympic", "reps", "Y", "barbell", {}),
    ("Hang clean", ["HC"], "olympic", "reps", "Y", "barbell", {}),
    ("Hang power clean", ["HPC"], "olympic", "reps", "Y", "barbell", {}),
    ("Hang squat clean", ["HSC"], "olympic", "reps", "Y", "barbell", {}),
    ("Muscle clean", ["MC"], "olympic", "reps", "Y", "barbell", {}),
    ("Split clean", [], "olympic", "reps", "Y", "barbell", {}),
    ("Clean pull", ["CP"], "olympic", "reps", "Y", "barbell", {}),
    ("Clean and jerk", ["C&J", "CJ"], "olympic", "reps", "Y", "barbell", {}),
    ("Power clean and push jerk", ["PC&J"], "olympic", "reps", "Y", "barbell", {}),
    ("Power clean and split jerk", [], "olympic", "reps", "Y", "barbell", {}),
    ("Squat-clean thruster", ["cluster"], "olympic", "reps", "Y", "barbell", {}),
    ("Thruster", ["TH", "THR", "thrusters"], "olympic", "reps", "Y", "barbell", {}),
    ("Ground-to-overhead", ["GTOH", "G2OH"], "olympic", "reps", "Y", "barbell", {}),
    ("Shoulder-to-overhead", ["STOH", "S2OH"], "olympic", "reps", "Y", "barbell", {}),
    ("Push press", ["PP"], "olympic", "reps", "Y", "barbell", {}),
    ("Push jerk", ["PJ"], "olympic", "reps", "Y", "barbell", {}),
    ("Split jerk", ["SJ"], "olympic", "reps", "Y", "barbell", {}),
    ("Squat jerk", [], "olympic", "reps", "Y", "barbell", {}),
    ("Behind-the-neck jerk", ["BTN jerk"], "olympic", "reps", "Y", "barbell", {}),
    ("Behind-the-neck press", ["BTN press", "BTN"], "olympic", "reps", "Y", "barbell", {}),
    # E.2 Barbell strength
    ("Back squat", ["BS"], "strength", "reps", "Y", "barbell", {}),
    ("Front squat", ["FS"], "strength", "reps", "Y", "barbell", {}),
    ("Box squat", [], "strength", "reps", "Y", "barbell", {}),
    ("Zercher squat", [], "strength", "reps", "Y", "barbell", {}),
    ("Deadlift", ["DL"], "strength", "reps", "Y", "barbell", {}),
    ("Sumo deadlift", ["SDL"], "strength", "reps", "Y", "barbell", {}),
    ("Romanian deadlift", ["RDL"], "strength", "reps", "Y", "barbell", {}),
    ("Stiff-leg deadlift", ["SLDL"], "strength", "reps", "Y", "barbell", {}),
    ("Sumo deadlift high pull", ["SDHP"], "strength", "reps", "Y", "barbell", {}),
    ("Good morning", ["GM"], "strength", "reps", "Y", "barbell", {}),
    ("Shoulder press", ["SP", "MP", "OHP", "strict press"], "strength", "reps", "Y", "barbell", {}),
    ("Bench press", ["BP", "bench"], "strength", "reps", "Y", "barbell", {}),
    ("Bent-over row", ["BOR", "Pendlay"], "strength", "reps", "Y", "barbell", {}),
    ("Hip thrust", ["HT"], "strength", "reps", "Y", "barbell", {}),
    ("Front-rack lunge", ["FRL"], "strength", "ft", "Y", "barbell", {"measures": ["ft", "m", "reps"]}),
    ("Back-rack lunge", ["BRL"], "strength", "ft", "Y", "barbell", {"measures": ["ft", "m", "reps"]}),
    ("Overhead lunge", ["OHL"], "strength", "ft", "Y", "barbell", {"measures": ["ft", "m", "reps"]}),
    ("Bulgarian split squat", ["BSS"], "strength", "reps", "optional", "dumbbell", {"side": "each"}),
    ("Box step-up", ["BSU", "box step-ups"], "strength", "reps", "optional", "box", {}),
    # E.3 Gymnastics and bodyweight
    ("Air squat", ["AS", "squat", "squats"], "gymnastics", "reps", "N", "bodyweight", {}),
    ("Pull-up", ["PU", "KPU", "kipping pull-up", "pull-ups", "pullups"], "gymnastics", "reps", "N", "bodyweight", {}),
    ("Strict pull-up", ["SPU"], "gymnastics", "reps", "optional", "bodyweight", {}),
    ("Butterfly pull-up", ["BF"], "gymnastics", "reps", "N", "bodyweight", {}),
    ("Chin-up", ["CU"], "gymnastics", "reps", "optional", "bodyweight", {}),
    ("Chest-to-bar pull-up", ["C2B", "CTB"], "gymnastics", "reps", "N", "bodyweight", {}),
    ("Strict chest-to-bar pull-up", ["strict C2B"], "gymnastics", "reps", "N", "bodyweight", {}),
    ("Jumping pull-up", ["JPU", "jumping PU"], "gymnastics", "reps", "N", "bodyweight", {}),
    ("Chin-over-bar pull-up", ["COB"], "gymnastics", "reps", "N", "bodyweight", {}),
    ("Bar muscle-up", ["BMU"], "gymnastics", "reps", "N", "bodyweight", {}),
    ("Ring muscle-up", ["RMU", "MU", "muscle-up"], "gymnastics", "reps", "N", "bodyweight", {}),
    ("Strict muscle-up", ["SMU"], "gymnastics", "reps", "N", "bodyweight", {}),
    ("Toes-to-bar", ["T2B", "TTB"], "gymnastics", "reps", "N", "bodyweight", {}),
    ("Knees-to-elbows", ["K2E", "KTE"], "gymnastics", "reps", "N", "bodyweight", {}),
    ("Hanging knee raise", ["HKR"], "gymnastics", "reps", "N", "bodyweight", {}),
    ("Push-up", ["PU", "push-ups", "pushups"], "gymnastics", "reps", "N", "bodyweight", {}),
    ("Hand-release push-up", ["HRPU"], "gymnastics", "reps", "N", "bodyweight", {}),
    ("Ring push-up", [], "gymnastics", "reps", "N", "bodyweight", {}),
    ("Ring dip", ["RD"], "gymnastics", "reps", "N", "bodyweight", {}),
    ("Bar dip", ["dip"], "gymnastics", "reps", "optional", "bodyweight", {}),
    ("Ring row", ["RR"], "gymnastics", "reps", "N", "bodyweight", {}),
    ("Handstand push-up", ["HSPU", "KHSPU", "kipping HSPU"], "gymnastics", "reps", "N", "bodyweight", {}),
    ("Strict handstand push-up", ["SHSPU"], "gymnastics", "reps", "N", "bodyweight", {}),
    ("Deficit handstand push-up", ["DHSPU"], "gymnastics", "reps", "N", "bodyweight", {}),
    ("Wall-facing handstand push-up", ["WFHSPU", "CTW", "chest-to-wall HSPU"], "gymnastics", "reps", "N", "bodyweight", {}),
    ("Box pike push-up", [], "gymnastics", "reps", "N", "bodyweight", {}),
    ("Handstand walk", ["HSW"], "gymnastics", "ft", "N", "bodyweight", {"measures": ["ft", "m"]}),
    ("Handstand hold", ["HSH"], "gymnastics", "s", "N", "bodyweight", {}),
    ("Wall walk", ["WW"], "gymnastics", "reps", "N", "bodyweight", {}),
    ("Rope climb", ["RC"], "gymnastics", "reps", "N", "rope", {"notes": "15 ft"}),
    ("Legless rope climb", ["LRC"], "gymnastics", "reps", "N", "rope", {}),
    ("Pistol", ["SLS", "pistols"], "gymnastics", "reps", "optional", "bodyweight", {}),
    ("L-sit", [], "gymnastics", "s", "N", "bodyweight", {}),
    ("GHD sit-up", ["GHDSU", "GHD SU"], "gymnastics", "reps", "N", "ghd", {}),
    ("AbMat sit-up", ["sit-up", "sit-ups", "SU"], "gymnastics", "reps", "N", "bodyweight", {}),
    ("Hollow rock", [], "gymnastics", "reps", "N", "bodyweight", {}),
    ("Hollow hold", [], "gymnastics", "s", "N", "bodyweight", {}),
    ("V-up", [], "gymnastics", "reps", "N", "bodyweight", {}),
    ("GHD hip extension", ["HE"], "gymnastics", "reps", "optional", "ghd", {}),
    ("GHD back extension", ["BE", "back extension"], "gymnastics", "reps", "optional", "ghd", {}),
    ("GHD hip-and-back extension", ["HBE"], "gymnastics", "reps", "optional", "ghd", {}),
    ("Burpee", ["BRP", "burpees"], "gymnastics", "reps", "N", "bodyweight", {}),
    ("Bar-facing burpee", ["BFB"], "gymnastics", "reps", "N", "bodyweight", {}),
    ("Lateral burpee over the bar", ["BOB", "LBOB", "burpee over the bar"], "gymnastics", "reps", "N", "bodyweight", {}),
    ("Lateral burpee over the dumbbell", ["LBO DB"], "gymnastics", "reps", "N", "bodyweight", {}),
    ("Burpee box jump-over", ["BBJO"], "gymnastics", "reps", "N", "box", {}),
    ("Burpee pull-up", ["BPU"], "gymnastics", "reps", "N", "bodyweight", {}),
    ("Walking lunge", ["WL", "lunges"], "gymnastics", "ft", "N", "bodyweight", {"measures": ["ft", "m", "steps"]}),
    ("Jumping jack", ["JJ"], "gymnastics", "reps", "N", "bodyweight", {}),
    ("Plank", [], "gymnastics", "s", "N", "bodyweight", {}),
    ("Bear crawl", [], "gymnastics", "ft", "N", "bodyweight", {"measures": ["ft", "reps"]}),
    ("Inchworm push-up", ["inchworm"], "gymnastics", "reps", "N", "bodyweight", {}),
    ("Skin the cat", [], "gymnastics", "reps", "N", "rings", {}),
    ("Pegboard", [], "gymnastics", "reps", "N", "pegboard", {}),
    # E.4 Monostructural
    ("Run", ["running"], "monostructural", "m", "optional", "none", {"measures": ["m", "km", "mi"]}),
    ("Shuttle run", [], "monostructural", "m", "N", "none", {"measures": ["m", "reps"]}),
    ("Row", ["C2", "ROW", "RowErg", "Echo rower"], "monostructural", "m", "N", "rower", {"measures": ["m", "cal"]}),
    ("SkiErg", ["SKI"], "monostructural", "m", "N", "skierg", {"measures": ["m", "cal"]}),
    ("Air bike", ["AB", "Echo", "Assault bike", "bike"], "monostructural", "cal", "N", "air_bike", {"measures": ["cal", "m"]}),
    ("BikeErg", ["BE"], "monostructural", "cal", "N", "bikeerg", {"measures": ["cal", "m"]}),
    ("Double-under", ["DU", "double-unders"], "monostructural", "reps", "N", "jump_rope", {}),
    ("Single-under", ["SU", "single-unders"], "monostructural", "reps", "N", "jump_rope", {}),
    ("Crossover", ["CO"], "monostructural", "reps", "N", "jump_rope", {}),
    ("Triple-under", ["TU"], "monostructural", "reps", "N", "jump_rope", {}),
    ("Swim", [], "monostructural", "m", "N", "none", {"measures": ["m", "yd"]}),
    ("Ruck", [], "monostructural", "mi", "Y", "ruck", {"measures": ["mi", "km"]}),
    # E.5 Kettlebell
    ("Russian kettlebell swing", ["RKBS"], "kettlebell", "reps", "Y", "kettlebell", {}),
    ("American kettlebell swing", ["AKBS", "KBS", "kettlebell swing"], "kettlebell", "reps", "Y", "kettlebell",
     {"rx": {"m": {"v": 53, "u": "lb"}, "f": {"v": 35, "u": "lb"}}}),
    ("Kettlebell snatch", ["KBSN"], "kettlebell", "reps", "Y", "kettlebell", {"side": "each"}),
    ("Kettlebell clean", ["KBC"], "kettlebell", "reps", "Y", "kettlebell", {}),
    ("Kettlebell clean and press", [], "kettlebell", "reps", "Y", "kettlebell", {}),
    ("Turkish get-up", ["TGU"], "kettlebell", "reps", "Y", "kettlebell", {"side": "each"}),
    ("Goblet squat", ["GS"], "kettlebell", "reps", "Y", "kettlebell", {}),
    ("Kettlebell deadlift", ["KBDL"], "kettlebell", "reps", "Y", "kettlebell", {}),
    ("Kettlebell front-rack carry", [], "kettlebell", "m", "Y", "kettlebell", {"measures": ["m", "ft"]}),
    # E.6 Dumbbell
    ("Dumbbell snatch", ["DBS", "DB SN", "DB snatch", "alt DB snatch"], "dumbbell", "reps", "Y", "dumbbell", {}),
    ("Dumbbell clean and jerk", ["DB C&J"], "dumbbell", "reps", "Y", "dumbbell", {}),
    ("Dumbbell hang clean-to-overhead", ["DB HC2OH", "HC2OH"], "dumbbell", "reps", "Y", "dumbbell", {}),
    ("Dumbbell power clean", ["DB PC"], "dumbbell", "reps", "Y", "dumbbell", {}),
    ("Dumbbell hang power clean", ["DB HPC"], "dumbbell", "reps", "Y", "dumbbell", {}),
    ("Devil press", ["DP"], "dumbbell", "reps", "Y", "dumbbell", {}),
    ("Man maker", [], "dumbbell", "reps", "Y", "dumbbell", {}),
    ("Dumbbell thruster", ["DB TH", "DB thruster"], "dumbbell", "reps", "Y", "dumbbell", {}),
    ("Dumbbell front squat", ["DB FS"], "dumbbell", "reps", "Y", "dumbbell", {}),
    ("Dumbbell push press", ["DB PP"], "dumbbell", "reps", "Y", "dumbbell", {}),
    ("Dumbbell push jerk", ["DB PJ"], "dumbbell", "reps", "Y", "dumbbell", {}),
    ("Dumbbell strict press", ["DB SP", "DB press"], "dumbbell", "reps", "Y", "dumbbell", {}),
    ("Dumbbell bench press", ["DB BP"], "dumbbell", "reps", "Y", "dumbbell", {}),
    ("Dumbbell row", [], "dumbbell", "reps", "Y", "dumbbell", {}),
    ("Renegade row", [], "dumbbell", "reps", "Y", "dumbbell", {}),
    ("Dumbbell deadlift", ["DB DL"], "dumbbell", "reps", "Y", "dumbbell", {}),
    ("Dumbbell box step-over", ["DBBSO"], "dumbbell", "reps", "Y", "dumbbell", {}),
    ("Dumbbell overhead walking lunge", ["DB OHWL"], "dumbbell", "ft", "Y", "dumbbell", {"measures": ["ft", "m"]}),
    ("Dumbbell front-rack lunge", ["DB FRL"], "dumbbell", "ft", "Y", "dumbbell", {"measures": ["ft", "m"]}),
    ("Farmer's carry", ["FC"], "dumbbell", "m", "Y", "dumbbell", {"measures": ["m", "ft"]}),
    ("Single-arm dumbbell overhead squat", ["SA DB OHS"], "dumbbell", "reps", "Y", "dumbbell", {}),
    ("Dumbbell Turkish get-up", ["DB TGU"], "dumbbell", "reps", "Y", "dumbbell", {"side": "each"}),
    # E.7 Other implements
    ("Wall-ball shot", ["WB", "WBS", "wall ball", "wall balls"], "other", "reps", "Y", "medicine_ball",
     {"rx": {"m": {"v": 20, "u": "lb"}, "f": {"v": 14, "u": "lb"}},
      "target": {"m": {"v": 10, "u": "ft"}, "f": {"v": 9, "u": "ft"}}}),
    ("Medicine-ball clean", ["MBC"], "other", "reps", "Y", "medicine_ball", {}),
    ("Medicine-ball box step-over", ["MBSO"], "other", "reps", "Y", "medicine_ball", {}),
    ("Slam ball", ["SB", "MB slam", "ball slam"], "other", "reps", "Y", "slam_ball", {}),
    ("Box jump", ["BJ", "BXJ", "box jumps"], "other", "reps", "N", "box",
     {"height": {"m": {"v": 24, "u": "in"}, "f": {"v": 20, "u": "in"}}}),
    ("Box jump-over", ["BJO"], "other", "reps", "N", "box", {}),
    ("Box step-over", ["BSO"], "other", "reps", "optional", "box", {}),
    ("Sled push", [], "other", "m", "Y", "sled", {"measures": ["m", "ft"]}),
    ("Sled pull", ["sled drag"], "other", "m", "Y", "sled", {"measures": ["m", "ft"]}),
    ("Sandbag carry", [], "other", "m", "Y", "sandbag", {"measures": ["m", "ft"]}),
    ("Sandbag over shoulder", [], "other", "reps", "Y", "sandbag", {}),
    ("D-ball over shoulder", [], "other", "reps", "Y", "d_ball", {}),
    ("Sandbag clean", [], "other", "reps", "Y", "sandbag", {}),
    ("Yoke carry", [], "other", "m", "Y", "yoke", {"measures": ["m", "ft"]}),
    ("Plate overhead walk", [], "other", "m", "Y", "plate", {"measures": ["m", "ft"]}),
    ("Plate ground-to-overhead", [], "other", "reps", "Y", "plate", {}),
    ("Tire flip", [], "other", "reps", "Y", "tire", {}),
    ("Battle rope", [], "other", "s", "N", "battle_rope", {}),
]


def _slug(name: str) -> str:
    """snake_case id from a display name (Appendix E rule)."""
    s = name.lower().replace("&", " and ").replace("'", "")
    return re.sub(r"[^a-z0-9]+", "_", s).strip("_")


def _dedupe(aliases: list[str]) -> list[str]:
    """Drop case-insensitive duplicates while keeping the first spelling."""
    seen: set[str] = set()
    out: list[str] = []
    for a in aliases:
        if a.lower() not in seen:
            seen.add(a.lower())
            out.append(a)
    return out


def build_movements() -> list[dict[str, Any]]:
    """Expand the Appendix E transcription into catalog entries with unique IDs."""
    out: list[dict[str, Any]] = []
    seen: set[str] = set()
    for name, aliases, category, measure, loadable, implement, extra in M:
        mid = _slug(name)
        if mid in seen:
            raise ValueError(f"duplicate movement id {mid}")
        seen.add(mid)
        entry: dict[str, Any] = {
            "id": mid,
            "name": name,
            "aliases": _dedupe(aliases),
            "ambiguousAliases": [a for a in aliases if a in AMBIGUOUS],
            "category": category,
            "measure": measure,
            "loadable": loadable,
            "implement": implement,
        }
        entry.update(extra)
        out.append(entry)
    return out


# Appendix G: (name, category, format, scheme, rx, score, aliases)
B: list[tuple[str, str, str, str, str, str, list[str]]] = [
    ("Angie", "girl", "for_time", "100 pull-ups, 100 push-ups, 100 sit-ups, 100 squats", "bodyweight", "time", []),
    ("Barbara", "girl", "for_time", "5 rounds, 3:00 rest between: 20 pull-ups, 30 push-ups, 40 sit-ups, 50 squats", "bodyweight", "time", []),
    ("Chelsea", "girl", "emom", "EMOM 30: 5 pull-ups, 10 push-ups, 15 squats", "bodyweight", "rounds", []),
    ("Cindy", "girl", "amrap", "AMRAP 20: 5 pull-ups, 10 push-ups, 15 squats", "bodyweight", "rounds_reps", []),
    ("Diane", "girl", "for_time", "21-15-9 deadlift, handstand push-up", "225/155 lb", "time", []),
    ("Elizabeth", "girl", "for_time", "21-15-9 clean, ring dip", "135/95 lb", "time", []),
    ("Fran", "girl", "for_time", "21-15-9 thruster, pull-up", "95/65 lb", "time", []),
    ("Grace", "girl", "for_time", "30 clean and jerks", "135/95 lb", "time", []),
    ("Helen", "girl", "for_time", "3 RFT: 400 m run, 21 kettlebell swings, 12 pull-ups", "KB 53/35 lb", "time", []),
    ("Isabel", "girl", "for_time", "30 snatches", "135/95 lb", "time", []),
    ("Jackie", "girl", "for_time", "1000 m row, 50 thrusters, 30 pull-ups", "45/35 lb", "time", []),
    ("Karen", "girl", "for_time", "150 wall-ball shots", "20/14 lb to 10/9 ft", "time", []),
    ("Linda", "girl", "for_time", "10-9-...-1: deadlift 1.5xBW, bench press BW, squat clean 0.75xBW", "% bodyweight", "time", ["Three Bars of Death"]),
    ("Mary", "girl", "amrap", "AMRAP 20: 5 HSPU, 10 pistols, 15 pull-ups", "bodyweight", "rounds_reps", []),
    ("Nancy", "girl", "for_time", "5 RFT: 400 m run, 15 overhead squats", "95/65 lb", "time", []),
    ("Annie", "girl", "for_time", "50-40-30-20-10 double-under, sit-up", "bodyweight", "time", []),
    ("Eva", "girl", "for_time", "5 RFT: 800 m run, 30 kettlebell swings, 30 pull-ups", "KB 2/1.5 pood", "time", []),
    ("Kelly", "girl", "for_time", "5 RFT: 400 m run, 30 box jumps, 30 wall-ball shots", "box 24/20 in; ball 20/14 lb", "time", []),
    ("Lynne", "girl", "max_effort", "5 rounds for max reps: bodyweight bench press, pull-ups", "bodyweight", "reps", []),
    ("Nicole", "girl", "amrap", "AMRAP 20: 400 m run, max pull-ups", "bodyweight", "reps", []),
    ("Amanda", "girl", "for_time", "9-7-5 muscle-up, snatch", "135/95 lb", "time", []),
    ("Gwen", "girl", "strength_sets", "clean and jerk 15-12-9, touch-and-go, same load", "athlete's choice", "load", []),
    ("Marguerita", "girl", "for_time", "50 reps: burpee + push-up + jumping jack + sit-up + handstand", "bodyweight", "time", []),
    ("Candy", "girl", "for_time", "5 RFT: 20 pull-ups, 40 push-ups, 60 squats", "bodyweight", "time", []),
    ("Maggie", "girl", "for_time", "5 RFT: 20 HSPU, 40 pull-ups, 60 alternating pistols", "bodyweight", "time", []),
    ("Hope", "girl", "intervals", "3 rounds, 1:00 stations, 1:00 rest: burpees, power snatch, box jump, thruster, C2B", "75 lb, 24 in box", "reps", []),
    ("Grettel", "girl", "for_time", "10 RFT: 3 clean and jerks, 3 burpees over the bar", "135/95 lb", "time", []),
    ("Ingrid", "girl", "for_time", "10 RFT: 3 snatches, 3 burpees over the bar", "135/95 lb", "time", []),
    ("Barbara Ann", "girl", "for_time", "5 rounds each for time, 3:00 rest: 20 HSPU, 30 deadlifts, 40 sit-ups, 50 DU", "135/95 lb", "time", []),
    ("Lyla", "girl", "for_time", "10-9-...-1 muscle-up, bodyweight clean and jerk", "bodyweight", "time", []),
    ("Ellen", "girl", "for_time", "3 RFT: 20 burpees, 21 DB snatches, 12 DB thrusters", "50/35 lb", "time", []),
    ("Andi", "girl", "for_time", "100 hang power snatches, 100 push presses, 100 SDHP, 100 front squats", "65/45 lb", "time", []),
    ("Lane", "girl", "max_effort", "5 rounds for max reps: hang power snatch 3/4 BW, HSPU", "3/4 bodyweight", "reps", []),
    ("Nasty Girls", "girl", "for_time", "3 RFT: 50 squats, 7 muscle-ups, 10 hang power cleans", "135 lb", "time", []),
    ("Murph", "hero", "for_time", "1 mi run, 100 pull-ups, 200 push-ups, 300 squats, 1 mi run", "vest 20/14 lb", "time", []),
    ("DT", "hero", "for_time", "5 RFT: 12 deadlifts, 9 hang power cleans, 6 push jerks", "155/105 lb", "time", []),
    ("JT", "hero", "for_time", "21-15-9 HSPU, ring dip, push-up", "bodyweight", "time", []),
    ("Michael", "hero", "for_time", "3 RFT: 800 m run, 50 back extensions, 50 sit-ups", "bodyweight", "time", []),
    ("Badger", "hero", "for_time", "3 RFT: 30 squat cleans, 30 pull-ups, 800 m run", "95/65 lb", "time", []),
    ("Nate", "hero", "amrap", "AMRAP 20: 2 muscle-ups, 4 HSPU, 8 kettlebell swings", "KB 70/53 lb", "rounds_reps", []),
    ("Randy", "hero", "for_time", "75 power snatches", "75/55 lb", "time", []),
    ("Kalsu", "hero", "for_time", "100 thrusters; 5 burpees at the start and top of every minute", "135/95 lb", "time", []),
    ("Chad", "hero", "for_time", "1,000 box step-ups with a ruck", "ruck 45/35 lb, 20 in box", "time", []),
    ("Holleyman", "hero", "for_time", "30 RFT: 5 wall-ball shots, 3 HSPU, 1 power clean", "ball 20/14 lb; clean 225/155 lb", "time", []),
    ("Josh", "hero", "for_time", "21 OHS, 42 pull-ups, 15 OHS, 30 pull-ups, 9 OHS, 18 pull-ups", "95/65 lb", "time", []),
    ("Daniel", "hero", "for_time", "50 pull-ups, 400 m run, 21 thrusters, 800 m run, 21 thrusters, 400 m run, 50 pull-ups", "95/65 lb", "time", []),
    ("Jason", "hero", "for_time", "squats 100/75/50/25 alternating with muscle-ups 5/10/15/20", "bodyweight", "time", []),
    ("Griff", "hero", "for_time", "800 m run, 400 m run backward, 800 m run, 400 m run backward", "-", "time", []),
    ("Ryan", "hero", "for_time", "5 RFT: 7 muscle-ups, 21 burpees", "bodyweight", "time", []),
    ("Bull", "hero", "for_time", "2 RFT: 200 double-unders, 50 OHS, 50 pull-ups, 1 mi run", "135/95 lb", "time", []),
    ("The Seven", "hero", "for_time", "7 RFT: 7 each HSPU, thruster, K2E, deadlift, burpee, KB swing, pull-up", "thruster 135/95, DL 245/165, KB 70/53 lb", "time", []),
    ("Loredo", "hero", "for_time", "6 RFT: 24 squats, 24 push-ups, 24 walking-lunge steps, 400 m run", "bodyweight", "time", []),
    ("Lumberjack 20", "hero", "for_time", "20 each: DL, KBS, OHS, burpee, C2B, box jump, DB squat clean; 400 m run after each", "DL 275/185, KB 70/53, OHS 115/75 lb", "time", []),
    ("Glen", "hero", "for_time", "30 clean and jerks, 1 mi run, 10 rope climbs, 1 mi run, 100 burpees", "135/95 lb", "time", []),
    ("Whitten", "hero", "for_time", "5 RFT: 22 KBS, 22 box jumps, 400 m run, 22 burpees, 22 wall balls", "KB 72/53 lb, box 24/20 in, ball 20/14 lb", "time", []),
    ("Nutts", "hero", "for_time", "10 HSPU, 15 DL, 25 box jumps, 50 pull-ups, 100 wall balls, 200 DU, 400 m plate run", "DL 250/175 lb, box 30/24 in", "time", []),
    ("Tommy V", "hero", "for_time", "thrusters 21/15/9 alternating with rope climbs 12/9/6", "115/75 lb", "time", []),
    ("Luce", "hero", "for_time", "3 RFT, vest: 1 km run, 10 muscle-ups, 100 squats", "vest 20/14 lb", "time", []),
    ("Jerry", "hero", "for_time", "1 mi run, 2 km row, 1 mi run", "-", "time", []),
    ("Danny", "hero", "amrap", "AMRAP 20: 30 box jumps, 20 push presses, 30 pull-ups", "115/75 lb, box 24/20 in", "rounds_reps", []),
    ("Mr. Joshua", "hero", "for_time", "5 RFT: 400 m run, 30 GHD sit-ups, 15 deadlifts", "250/175 lb", "time", []),
    ("McGhee", "hero", "amrap", "AMRAP 30: 5 deadlifts, 13 push-ups, 9 box jumps", "275/185 lb, box 24/20 in", "rounds_reps", []),
    ("Fight Gone Bad", "other", "intervals", "3 rounds x 5 one-minute stations, 1:00 rest: wall ball, SDHP, box jump, push press, row (cal)", "ball 20/14 lb; SDHP/PP 75/55 lb; 20 in box", "reps", ["FGB"]),
    ("Filthy Fifty", "other", "for_time", "50 each: box jump, jumping pull-up, KBS, lunge step, K2E, push press, back extension, wall ball, burpee, DU", "box 24/20 in, KB 16/12 kg, PP 45/35 lb", "time", []),
    ("CrossFit Total", "other", "strength_sets", "1RM back squat, shoulder press, deadlift; 3 attempts each", "-", "load", ["CFT"]),
    ("Tabata Something Else", "other", "tabata", "32 x :20/:10: pull-ups, push-ups, sit-ups, squats (8 each)", "bodyweight", "reps", []),
    ("Tabata This!", "other", "tabata", "5 Tabatas, 1:00 rest between: row (cal), squat, pull-up, push-up, sit-up", "bodyweight", "reps", ["Tabata This"]),
    ("The Chief", "other", "intervals", "5 x 3:00 AMRAP of 3 power cleans, 6 push-ups, 9 squats; 1:00 rest", "135/95 lb", "rounds", ["Chief"]),
    ("24.1", "open", "for_time", "cap 15: 21-15-9 DB snatch (arm 1), LBO DB, DB snatch (arm 2), LBO DB", "DB 50/35 lb", "time", ["Open 24.1"]),
    ("24.2", "open", "amrap", "AMRAP 20: 300 m row, 10 deadlifts, 50 double-unders", "185/125 lb", "reps", ["Open 24.2"]),
    ("24.3", "open", "for_time", "cap 15: 5 rds 10 thrusters + 10 C2B; rest 1:00; 5 rds 7 thrusters + 7 BMU", "M 95,135 / F 65,95 lb", "time", ["Open 24.3"]),
    ("25.1", "open", "amrap", "AMRAP 15: 3 LBO DB, 3 DB HC2OH, 30 ft walking lunge; +3 each round", "DB 50/35 lb", "reps", ["Open 25.1"]),
    ("25.2", "open", "for_time", "cap 12: 21 PU, 42 DU, 21 thrusters; 18 C2B, 36 DU, 18 thrusters; 15 BMU, 30 DU, 15 thrusters", "M 95/115/135, F 65/75/85 lb", "time", ["Open 25.2", "22.3"]),
    ("25.3", "open", "for_time", "cap 20: WW / 50 cal row / WW / 25 DL / WW / 25 cleans / WW / 25 snatches / WW / 50 cal row", "DL 225/155, clean 135/85, snatch 95/65 lb", "time", ["Open 25.3"]),
    ("26.1", "open", "for_time", "cap 12: wall balls 20-30-40-66-40-30-20 with BJO / MB step-over sets", "ball 20/14 lb, box 24/20 in", "time", ["Open 26.1"]),
    ("26.2", "open", "for_time", "cap 15: 3 x (80 ft DB OH lunge, 20 alt DB snatches) with 20 PU / 20 C2B / 20 RMU", "DB 50/35 lb", "time", ["Open 26.2"]),
    ("26.3", "open", "for_time", "cap 16: 3 blocks x 2 rds: 12 BOB, 12 cleans, 12 BOB, 12 thrusters", "M 95/115/135, F 65/75/85 lb", "time", ["Open 26.3"]),
]


def build_benchmarks() -> list[dict[str, Any]]:
    """Benchmark seed entries; canonical Workout JSON per entry is a v1 addition (REQ-DM13)."""
    out: list[dict[str, Any]] = []
    for name, category, fmt, scheme, rx, score, aliases in B:
        out.append(
            {
                "id": ("open_" if category == "open" else "") + _slug(name),
                "name": name,
                "aliases": aliases,
                "category": category,
                "format": fmt,
                "scheme": scheme,
                "rx": rx,
                "score": score,
            }
        )
    return out


def _write(path: Path, items: list[dict[str, Any]], kind: str) -> None:
    doc = {"schemaVersion": CATALOG_VERSION, "kind": kind, "items": items}
    path.write_text(json.dumps(doc, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def main() -> None:
    """Regenerate both catalog files."""
    _write(ROOT / "shared" / "movements.json", build_movements(), "movements")
    _write(ROOT / "shared" / "benchmarks.json", build_benchmarks(), "benchmarks")


if __name__ == "__main__":
    main()
