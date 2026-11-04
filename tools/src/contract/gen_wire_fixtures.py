"""Generate golden wire-message fixtures for docs/protocol.md (REQ-TS08).

Positive fixtures land in shared/fixtures/wire/valid and must pass the shared schemas;
negative fixtures land in shared/fixtures/wire/invalid and must each fail them. Both the
Android protocol tests and the watch decoder tests can load these files, so the two apps
are checked against one artifact rather than each other's assumptions.
"""

from __future__ import annotations

import copy
import json
from pathlib import Path
from typing import Any, Callable

ROOT = Path(__file__).resolve().parents[3]
WIRE = ROOT / "shared" / "fixtures" / "wire"

PLAN = "10000000-0000-4000-8000-000000000001"
WID = "20000000-0000-4000-8000-000000000001"
P_FRAN = "30000000-0000-4000-8000-000000000001"
P_SQUAT = "30000000-0000-4000-8000-000000000002"
THRUSTER = "50000000-0000-4000-8000-000000000001"
SID = "70000000-0000-4000-8000-000000000001"
RID_FRAN = "80000000-0000-4000-8000-000000000001"
RID_SQUAT = "80000000-0000-4000-8000-000000000002"
MSG = "60000000-0000-4000-8000-0000000000{:02d}"
PROFILE_OID = "00000000-0000-4000-8000-000000000000"  # singleton profile object (protocol §6)

Json = dict[str, Any]


def _set_id(i: int) -> str:
    return f"4000000{i}-0000-4000-8000-000000000001"


def fran_part() -> Json:
    """H1 Fran for a male athlete in kg: 95 lb -> 43 kg by the Appendix D equivalent."""
    return {
        "id": P_FRAN,
        "l": "W",
        "n": "Fran 21-15-9",
        "mode": "for_time",
        "fb": False,
        "ln": ["For time, cap 8:00", "21-15-9 reps of:", "Thruster 43 kg (Rx 95 lb)", "Pull-up"],
        "seg": [{"k": "open", "cap": 480, "lb": "FOR TIME", "ix": 0}],
        "trk": "round",
        "mv": ["Thruster 43 kg", "Pull-up"],
        "rr": [42, 30, 18],
        "rl": ["21 reps", "15 reps", "9 reps"],
        "rnd": 3,
        "sc": {"ty": "time", "rv": 1, "cap": "reps", "man": False},
        "ld": [{"id": THRUSTER, "nm": "Thruster", "v": 43, "u": "kg", "c": 1}],
        "lv": "Rx",
        "lvs": ["Rx", "Scaled"],
    }


def squat_part() -> Json:
    """H2 part A: 5x3 @80% of a stored 125 kg 1RM -> 100 kg; no rest after the last set."""
    sets = [
        {
            "id": _set_id(i),
            "nm": "Back squat",
            "mv": "back_squat",
            "r": 3,
            "rx": "3",
            "w": {"v": 100, "u": "kg", "c": 1},
            "rest": 120 if i < 5 else 0,
            "ty": "working",
            "nt": "80% -> 100 kg",
        }
        for i in range(1, 6)
    ]
    return {
        "id": P_SQUAT,
        "l": "A",
        "n": "Back squat 5x3",
        "mode": "strength_sets",
        "fb": False,
        "ln": ["Back squat 5x3 @80%", "Rest 2:00"],
        "sets": sets,
        "sc": {"ty": "load", "rv": 1, "man": False},
        "lv": "Rx",
    }


def plan() -> Json:
    """Two-part plan: H2 strength part A, then H1 Fran."""
    return {
        "sv": 1,
        "id": PLAN,
        "rev": 2,
        "wid": WID,
        "wr": 3,
        "ti": "Fran",
        "date": "2026-11-03",
        "tz": "Asia/Jerusalem",
        "u": "kg",
        "p": [squat_part(), fran_part()],
    }


def envelope(t: str, n: int, oid: str | None = None, rev: int | None = None, p: Json | None = None) -> Json:
    """Build an envelope; absent values are omitted keys, never nulls (protocol §1)."""
    env: Json = {"v": 1, "t": t, "id": MSG.format(n), "ts": 1793700000, "g": 1}
    if oid is not None:
        env["oid"] = oid
    if rev is not None:
        env["rev"] = rev
    if p is not None:
        env["p"] = p
    return env


def fran_result() -> Json:
    """Capped Fran: 2 rounds (42+30) plus 11 reps of the 18-rep round = 83 reps."""
    return {
        "sv": 1,
        "id": RID_FRAN,
        "rr": 1,
        "sid": SID,
        "pl": PLAN,
        "pr": 2,
        "wid": WID,
        "wr": 3,
        "pt": P_FRAN,
        "st": "capped",
        "t0": 1793700000,
        "el": 480,
        "act": 480,
        "wk": 480,
        "sc": {"ty": "reps", "n": 83, "rv": 1, "man": False},
        "lv": "Rx",
        "ev": [[0, "rd", 201000], [1, "rd", 395000], [2, "cap", 480000]],
        "ld": [[THRUSTER, 43, "kg", 1]],
        "hr": [151, 182],
        "fit": "saved",
        "dev": "fr965",
        "app": "0.1.0",
    }


def squat_result() -> Json:
    """Four full sets and one partial set; the partial never counts as a successful set."""
    sets = [
        [_set_id(i), i - 1, 3, 3 if i < 5 else 2, 100, "kg", 1, "done" if i < 5 else "partial", i * 180000]
        for i in range(1, 6)
    ]
    return {
        "sv": 1,
        "id": RID_SQUAT,
        "rr": 1,
        "sid": SID,
        "pl": PLAN,
        "pr": 2,
        "wid": WID,
        "wr": 3,
        "pt": P_SQUAT,
        "st": "completed",
        "t0": 1793699000,
        "el": 900,
        "act": 900,
        "wk": 60,
        "sc": {"ty": "load", "v": 100, "u": "kg", "rv": 1},
        "lv": "Rx",
        "sets": sets,
        "fit": "saved",
        "dev": "fr965",
        "app": "0.1.0",
    }


def session() -> Json:
    """Session summary referencing both part results."""
    return {
        "sv": 1,
        "id": SID,
        "rev": 1,
        "pl": PLAN,
        "pr": 2,
        "e": [[P_SQUAT, RID_SQUAT, "completed"], [P_FRAN, RID_FRAN, "capped"]],
        "t0": 1793699000,
        "t1": 1793700600,
        "el": 1600,
        "act": 1400,
        "hr": [140, 182],
        "fit": "saved",
    }


def hello() -> Json:
    """Watch inventory announcing one stored plan and two pending results."""
    modes = [
        "for_time",
        "amrap",
        "emom",
        "tabata",
        "intervals",
        "max_effort",
        "strength_sets",
        "build_to_max",
        "manual",
    ]
    return {
        "pv": [1],
        "app": "0.1.0",
        "dev": "fr965",
        "inv": [[PLAN, 2]],
        "del": [],
        "free": 61440,
        "rc": 2,
        "cap": modes,
    }


def valid_fixtures() -> dict[str, Json]:
    """Positive envelopes covering every MVP message family."""
    ack = {"mid": MSG.format(1), "st": "ok"}
    return {
        "plan.put.json": envelope("plan.put", 1, PLAN, 2, plan()),
        "plan.ack.json": envelope("plan.ack", 2, PLAN, 2, ack),
        "plan.ack.stale.json": envelope("plan.ack", 3, PLAN, 1, {"mid": MSG.format(9), "st": "stale", "rs": "stale"}),
        "plan.del.json": envelope("plan.del", 4, PLAN, 3),
        "hello.json": envelope("hello", 5, p=hello()),
        "sync.req.json": envelope("sync.req", 6),
        "result.put.capped.json": envelope("result.put", 7, RID_FRAN, 1, fran_result()),
        "result.put.strength.json": envelope("result.put", 8, RID_SQUAT, 1, squat_result()),
        "result.ack.json": envelope("result.ack", 10, RID_FRAN, 1, {"mid": MSG.format(7), "st": "ok"}),
        "session.put.json": envelope("session.put", 11, SID, 1, session()),
        "profile.put.json": envelope("profile.put", 12, PROFILE_OID, 4, profile()),
        "result.put.pending-score.json": envelope("result.put", 13, RID_FRAN, 1, pending_result()),
    }


def profile() -> Json:
    """Phone defaults for the watch settings (protocol §6)."""
    return {
        "prep": 10,
        "snd": True,
        "vib": True,
        "rec": True,
        "tch": True,
        "ws": 2.5,
        "wsf": 0.5,
        "u": "kg",
        "rest": 120,
        "tap": True,
    }


def pending_result() -> Json:
    """Score not entered on the watch: intended type kept, no value keys (protocol §5)."""
    doc = fran_result()
    doc["st"] = "completed"
    doc["sc"] = {"ty": "time", "rv": 1, "pd": True}
    doc["ev"] = [[0, "rd", 201000], [1, "rd", 395000], [2, "fin", 452000]]
    return doc


def _mutate(base: Json, change: Callable[[Json], None]) -> Json:
    doc = copy.deepcopy(base)
    change(doc)
    return doc


def invalid_fixtures(valid: dict[str, Json]) -> dict[str, Json]:
    """Each negative fixture breaks exactly one rule from protocol §4–§8."""
    put = valid["plan.put.json"]
    res = valid["result.put.capped.json"]
    return {
        "plan.seven-parts.json": _mutate(put, lambda d: d["p"]["p"].extend(fran_part() for _ in range(5))),
        "plan.line-too-long.json": _mutate(put, lambda d: d["p"]["p"][1]["ln"].append("x" * 29)),
        "plan.work-without-duration.json": _mutate(
            put, lambda d: d["p"]["p"][1].update(seg=[{"k": "work", "lb": "WORK", "ix": 0}])
        ),
        "plan.fallback-not-manual.json": _mutate(put, lambda d: d["p"]["p"][1].update(fb=True)),
        "plan.null-load.json": _mutate(put, lambda d: d["p"]["p"][0]["sets"][0].update(w=None)),
        "plan.strength-without-sets.json": _mutate(put, lambda d: d["p"]["p"][0].pop("sets")),
        "plan.unknown-schema-version.json": _mutate(put, lambda d: d["p"].update(sv=2)),
        "result.missing-score-value.json": _mutate(res, lambda d: d["p"]["sc"].pop("n")),
        "result.null-hr.json": _mutate(res, lambda d: d["p"].update(hr=None)),
        "result.pending-with-value.json": _mutate(res, lambda d: d["p"]["sc"].update(pd=True)),
        "envelope.missing-generation.json": _mutate(valid["sync.req.json"], lambda d: d.pop("g")),
    }


def _write(folder: Path, docs: dict[str, Json]) -> None:
    folder.mkdir(parents=True, exist_ok=True)
    for name, doc in docs.items():
        (folder / name).write_text(json.dumps(doc, indent=2) + "\n", encoding="utf-8")


def main() -> None:
    """Regenerate all wire fixtures."""
    valid = valid_fixtures()
    _write(WIRE / "valid", valid)
    _write(WIRE / "invalid", invalid_fixtures(valid))


if __name__ == "__main__":
    main()
