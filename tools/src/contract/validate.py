"""Validate wire messages and plans against the shared JSON Schemas (REQ-TS08, REQ-NF10).

Usage:
    poetry run validate-wire <file-or-dir> [...]

A directory is scanned for *.json. Envelopes are checked with message.schema.json (which
references the plan/result/session schemas) plus the encoded-size budget from
docs/protocol.md §1. Exit status is non-zero when any file is invalid.
"""

from __future__ import annotations

import json
import logging
import sys
from pathlib import Path
from typing import Any

from jsonschema import Draft202012Validator
from referencing import Registry, Resource

logger = logging.getLogger(__name__)

SHARED = Path(__file__).resolve().parents[3] / "shared"
SCHEMAS = ("message", "watch-plan", "result", "session")
PHONE_BUDGET_BYTES = 3584  # 4096 minus a 512 B margin for SDK encoding (protocol §1)
WIRE_LIMIT_BYTES = 4096


def _load(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def build_registry() -> Registry[Any]:
    """Register every shared schema under its relative file name so $refs resolve offline."""
    registry: Registry[Any] = Registry()
    for name in SCHEMAS:
        resource = Resource.from_contents(_load(SHARED / f"{name}.schema.json"))
        registry = registry.with_resource(f"{name}.schema.json", resource)
        registry = registry.with_resource(resource.id() or name, resource)
    return registry


def message_validator() -> Draft202012Validator:
    """Validator for a full wire envelope."""
    schema = _load(SHARED / "message.schema.json")
    return Draft202012Validator(schema, registry=build_registry())


def encoded_size(doc: Any) -> int:
    """Compact-JSON UTF-8 byte length — the phone's size estimate for SDK encoding."""
    return len(json.dumps(doc, separators=(",", ":"), ensure_ascii=False).encode("utf-8"))


def errors_for(doc: Any, validator: Draft202012Validator) -> list[str]:
    """All schema and size violations for one envelope; empty when valid."""
    problems = [f"{'/'.join(map(str, e.absolute_path)) or '<root>'}: {e.message}" for e in validator.iter_errors(doc)]
    size = encoded_size(doc)
    if size > WIRE_LIMIT_BYTES:
        problems.append(f"<size>: {size} bytes exceeds the {WIRE_LIMIT_BYTES} B message limit")
    return problems


def _files(args: list[str]) -> list[Path]:
    out: list[Path] = []
    for arg in args:
        path = Path(arg)
        out.extend(sorted(path.glob("*.json")) if path.is_dir() else [path])
    return out


def main(argv: list[str] | None = None) -> int:
    """CLI entry point; returns the process exit code."""
    logging.basicConfig(level=logging.INFO, format="%(message)s")
    validator = message_validator()
    failed = 0
    for path in _files(argv if argv is not None else sys.argv[1:]):
        problems = errors_for(_load(path), validator)
        if problems:
            failed += 1
            logger.info("INVALID %s", path)
            for problem in problems:
                logger.info("  %s", problem)
        else:
            logger.info("ok      %s", path)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
