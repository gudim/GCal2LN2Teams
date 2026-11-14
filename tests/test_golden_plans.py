"""Эталонные планы (spec/golden) совпадают с тем, что выдаёт Python-движок.

Эталоны — приёмочная спецификация для Kotlin-движка (docs/adr/0002-mobile-kmp-on-device.md).
"""
import json
from pathlib import Path

import pytest

from src.sync.golden import SCENARIOS, build, dumps, run_input

GOLDEN = Path(__file__).parent.parent / "spec" / "golden"
HINT = "эталон устарел: перегенерируйте `python scripts/export_golden_plans.py` и проверьте diff"


def load(name):
    return json.loads((GOLDEN / f"{name}.json").read_text(encoding="utf-8"))


def visible(plan):
    return [a for a in plan["actions"] if not a["silent"]]


@pytest.mark.parametrize("scenario", SCENARIOS, ids=lambda s: s.name)
def test_golden_file_matches_engine(scenario):
    path = GOLDEN / f"{scenario.name}.json"
    assert path.exists(), f"{path.name}: нет файла, {HINT}"
    assert path.read_text(encoding="utf-8") == dumps(build(scenario)), f"{path.name}: {HINT}"


@pytest.mark.parametrize("scenario", SCENARIOS, ids=lambda s: s.name)
def test_golden_input_alone_reproduces_expected(scenario):
    """Входа из JSON достаточно: Kotlin не увидит ничего, кроме файла."""
    doc = load(scenario.name)
    assert run_input(json.loads(json.dumps(doc["input"]))) == doc["expected"], HINT


def test_no_stale_golden_files():
    names = {s.name for s in SCENARIOS}
    assert len(names) == len(SCENARIOS)
    stale = sorted(p.stem for p in GOLDEN.glob("*.json") if p.stem not in names)
    assert stale == [], f"файлы без сценария: {stale}; {HINT}"


def test_golden_text_is_canonical():
    """Ни Python-repr, ни времени не в UTC."""
    for path in GOLDEN.glob("*.json"):
        text = path.read_text(encoding="utf-8")
        for bad in ("datetime", "System.", "None", "True", "False", "+03:00", "date.min"):
            assert bad not in text, f"{path.name}: {bad}"


# Сценарии действительно проверяют своё правило (а не пустой план)

def actions(name, kind=None):
    planned = load(name)["expected"]["plan"]["actions"]
    return [a for a in planned if kind is None or a["kind"] == kind]


def test_scenarios_exercise_their_rules():
    created = {(a["origin"], a["target"]) for a in actions("create_all_directions", "create")}
    assert len(created) == 6

    assert visible(load("legacy_match_by_marker")["expected"]["plan"]) == []

    [update] = actions("update_original_with_attendees", "update")
    assert (update["selected"], update["reason"]) == (False, "участники получат письмо")

    [winner] = actions("conflict_later_modified_wins", "update")
    assert winner["event"]["title"] == "Из Lotus"

    [silent] = actions("delete_copy_when_original_gone", "delete")
    assert (silent["silent"], silent["subject"]) == (True, "copy")

    [ask] = actions("copy_gone_asks_to_delete_original", "delete")
    assert (ask["selected"], ask["subject"]) == (False, "original")

    for name in ("mass_delete_by_count", "mass_delete_by_share"):
        deletes = actions(name, "delete")
        assert deletes and not any(a["selected"] or a["silent"] for a in deletes)
        assert load(name)["expected"]["plan"]["warnings"]
    assert load("mass_delete_below_threshold")["expected"]["plan"]["warnings"] == []

    assert [a["kind"] for a in actions("twins_by_uid")] == ["link"]
    assert actions("twin_question_by_title_and_start", "twin")[0]["selected"] is True
    assert actions("redundant_copy_next_to_twin", "delete")[0]["subject"] == "extra"

    ended = load("links_ended_before_window")["expected"]
    assert [f["id"] for f in ended["collect"]["fetched"]] == ["d-recent", "g-recent"]
    assert [a["kind"] for a in ended["plan"]["actions"]] == ["unlink"]

    holiday = load("all_day_exclusive_end")["expected"]["collect"]["listed"]["google"]
    assert holiday == ["g-holiday", "g-straddle"]
