"""Consistency checks for shared/movements.json and shared/benchmarks.json (REQ-DM12/DM20)."""

from __future__ import annotations

import json
import re
from pathlib import Path

SHARED = Path(__file__).resolve().parents[3] / "shared"
ID_PATTERN = re.compile(r"^[a-z0-9]+(_[a-z0-9]+)*$")
LOADABLE = {"Y", "N", "optional"}


def _items(name: str) -> list[dict[str, object]]:
    doc = json.loads((SHARED / name).read_text(encoding="utf-8"))
    return list(doc["items"])


def catalog_problems() -> list[str]:
    """Human-readable violations; empty when both catalogs are consistent."""
    problems: list[str] = []
    for file in ("movements.json", "benchmarks.json"):
        ids = [str(item["id"]) for item in _items(file)]
        problems += [f"{file}: duplicate id {i}" for i in sorted({i for i in ids if ids.count(i) > 1})]
        problems += [f"{file}: id {i} is not snake_case" for i in ids if not ID_PATTERN.match(i)]
    for item in _items("movements.json"):
        if item["loadable"] not in LOADABLE:
            problems.append(f"movements.json: {item['id']} has loadable={item['loadable']}")
        if "/" in str(item["name"]):
            problems.append(f"movements.json: {item['id']} is an unexpanded slash family")
    return problems
