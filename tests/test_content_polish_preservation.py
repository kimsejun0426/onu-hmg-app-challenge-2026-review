"""Published 0.30 stories remain recoverable; 0.31 prose never changes play rules."""

import copy
import hashlib
import json
from pathlib import Path

import pytest

from carplay.catalog import ContentCatalog
from carplay.game import apply_action, apply_hint, new_game, present

ROOT = Path(__file__).resolve().parents[1]
PUBLISHED = json.loads((ROOT / "tests/fixtures/published_onu_030_content.json").read_text(encoding="utf-8"))
PREFERENCE = json.loads((ROOT / "tests/fixtures/short_preference_answers.json").read_text(encoding="utf-8"))
CATALOG = ContentCatalog()
REVISIONS = {
    "dinosaur-lab-missing-egg": "0.9.0",
    "space-return-signal": "0.11.0",
    "animal-footprint-garden": "0.12.0",
    "little-car-workshop": "0.11.0",
}
PLAYERS = [{"player_id": "p1", "nickname": "별"}, {"player_id": "p2", "nickname": "달"}]


def state_without_narration(state):
    result = copy.deepcopy(state)
    result.pop("pack_version")
    result.pop("feedback")
    return result


@pytest.mark.parametrize("record", PUBLISHED["packs"], ids=lambda item: item["pack_id"])
def test_published_bytes_and_catalog_metadata_remain_exact(record):
    source = ROOT / record["content_asset"]
    raw = source.read_bytes()
    assert len(raw) == record["bytes"]
    assert hashlib.sha256(raw).hexdigest() == record["sha256"]
    entries = [item for item in CATALOG.legacy_entries
               if (item["pack_id"], item["version"]) == (record["pack_id"], record["version"])]
    assert len(entries) == 1
    assert {key: value for key, value in entries[0].items() if key != "content_asset"} == record["catalog_metadata"]
    assert CATALOG.source(entries[0]) == source.resolve()
    assert CATALOG.load(record["pack_id"], record["version"]) == json.loads(raw)


@pytest.mark.parametrize("record", PUBLISHED["packs"], ids=lambda item: item["pack_id"])
@pytest.mark.parametrize("count", [1, 2])
def test_every_route_keeps_choices_turns_resources_results_and_resolvable_speech(record, count):
    old = CATALOG.load(record["pack_id"], record["version"])
    new = CATALOG.load(record["pack_id"], REVISIONS[record["pack_id"]])
    pending = [(new_game(PLAYERS[:count], old), new_game(PLAYERS[:count], new))]
    completed = 0
    while pending:
        previous, current = pending.pop()
        before_previous, before_current = copy.deepcopy(previous), copy.deepcopy(current)
        assert state_without_narration(previous) == state_without_narration(current)
        previous_view, current_view = present(previous, old), present(current, new)
        for key in ("prompt", "hint", "feedback"):
            # Feedback is spoken without template interpolation. Placeholders must never leak.
            assert "{" not in current_view.get(key, "") and "}" not in current_view.get(key, "")
        assert len(previous_view["actions"]) == len(current_view["actions"])
        for before, after in zip(previous_view["actions"], current_view["actions"], strict=True):
            assert {k: v for k, v in before.items() if k != "aliases"} == {
                k: v for k, v in after.items() if k != "aliases"}
            old_aliases, new_aliases = before["aliases"], after["aliases"]
            additions = {case["text"] for case in PREFERENCE["cases"]
                         if record["pack_id"] == PREFERENCE["pack_id"]
                         and case["scene"] == current["scene_id"] and case["action"] == before["id"]}
            assert new_aliases[:len(old_aliases)] == old_aliases
            assert set(new_aliases[len(old_aliases):]) == additions
            assert len(new_aliases) == len(old_aliases) + len(additions)
        assert previous_view["hint"] == current_view["hint"]
        if current["finished"]:
            assert current["turn_index"] == 6
            completed += 1
        else:
            assert state_without_narration(apply_hint(previous, old)) == state_without_narration(apply_hint(current, new))
            for action in previous_view["actions"]:
                pending.append((
                    apply_action(previous, old, previous["expected_player_id"], action["id"]),
                    apply_action(current, new, current["expected_player_id"], action["id"]),
                ))
        assert previous == before_previous and current == before_current
    assert completed == 64
