"""Shared-contract gate (REQ-TS08): golden wire fixtures vs the shared schemas."""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from contract import gen_catalog_check, validate
from contract.gen_wire_fixtures import WIRE, invalid_fixtures, valid_fixtures

VALID = sorted((WIRE / "valid").glob("*.json"))
INVALID = sorted((WIRE / "invalid").glob("*.json"))


@pytest.fixture(scope="module", name="validator")
def _validator() -> validate.Draft202012Validator:
    return validate.message_validator()


def test_fixtures_are_up_to_date() -> None:
    """Checked-in fixtures must equal what the generator produces."""
    valid = valid_fixtures()
    expected = {**valid, **invalid_fixtures(valid)}
    on_disk = {p.name: json.loads(p.read_text()) for p in VALID + INVALID}
    assert on_disk == expected


@pytest.mark.parametrize("path", VALID, ids=lambda p: p.name)
def test_valid_fixture_passes(path: Path, validator: validate.Draft202012Validator) -> None:
    assert validate.errors_for(json.loads(path.read_text()), validator) == []


@pytest.mark.parametrize("path", INVALID, ids=lambda p: p.name)
def test_invalid_fixture_fails(path: Path, validator: validate.Draft202012Validator) -> None:
    assert validate.errors_for(json.loads(path.read_text()), validator)


def test_plan_fits_phone_budget() -> None:
    """The two-part sample plan must fit the phone's conservative 3584 B budget."""
    doc = json.loads((WIRE / "valid" / "plan.put.json").read_text())
    assert validate.encoded_size(doc) <= validate.PHONE_BUDGET_BYTES


def test_no_nulls_anywhere_in_valid_fixtures() -> None:
    """The Connect IQ Android SDK cannot serialize null (protocol §1)."""

    def has_null(node: object) -> bool:
        if node is None:
            return True
        if isinstance(node, dict):
            return any(has_null(v) for v in node.values())
        if isinstance(node, list):
            return any(has_null(v) for v in node)
        return False

    assert not any(has_null(json.loads(p.read_text())) for p in VALID)


GOLDEN_PLANS = sorted((WIRE.parent).glob("H*/plan.json"))


def test_every_parser_fixture_has_a_golden_plan() -> None:
    """The Android compiler must publish a golden plan for each Appendix H fixture."""
    assert [p.parent.name for p in GOLDEN_PLANS] == sorted(f"H{i}" for i in range(1, 11))


@pytest.mark.parametrize("path", GOLDEN_PLANS, ids=lambda p: p.parent.name)
def test_compiled_golden_plan_matches_watch_contract(path: Path, validator: validate.Draft202012Validator) -> None:
    """Cross-app gate: the phone compiler's output is a valid plan.put for the watch decoder."""
    doc = json.loads(path.read_text())
    envelope = {
        "v": 1,
        "t": "plan.put",
        "id": "60000000-0000-4000-8000-0000000000ff",
        "ts": 1793700000,
        "g": 1,
        "oid": doc["id"],
        "rev": doc["rev"],
        "p": doc,
    }
    assert validate.errors_for(envelope, validator) == []
    assert validate.encoded_size(envelope) <= validate.PHONE_BUDGET_BYTES


def test_movement_catalog_ids_are_unique_snake_case() -> None:
    assert gen_catalog_check.catalog_problems() == []
