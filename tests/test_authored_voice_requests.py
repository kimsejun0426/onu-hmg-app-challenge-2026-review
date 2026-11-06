"""Finite authored requests agree with Onu's questions without relaxing interpretation."""

import copy
import hashlib
import json
from pathlib import Path

import pytest

from carplay.catalog import ContentCatalog
from carplay.game import apply_action, interpret, new_game

ROOT = Path(__file__).resolve().parents[1]
CATALOG = ContentCatalog()
CASES = json.loads((ROOT / "tests/fixtures/authored_request_cases.json").read_text(encoding="utf-8"))["cases"]
PUBLISHED = json.loads((ROOT / "tests/fixtures/published_onu_010_content.json").read_text(encoding="utf-8"))
PLAYERS = [{"player_id": "p1", "nickname": "민준"}, {"player_id": "p2", "nickname": "서윤"}]


def walk(pack, path, count):
    state = new_game(PLAYERS[:count], pack)
    for action in path:
        state = apply_action(state, pack, state["expected_player_id"], action)
    return state


def owner(state):
    return next(p["nickname"] for p in state["players"] if p["player_id"] == state["expected_player_id"])


@pytest.mark.parametrize("case", CASES, ids=lambda case: case["id"])
@pytest.mark.parametrize("count", [1, 2])
def test_explicit_requests_match_the_question_and_only_the_current_edition(case, count):
    pack = CATALOG.current(case["pack_id"])
    state = walk(pack, case["path"], count)
    before = copy.deepcopy(state)
    for text in {case["text"], case["text"].replace(" 줘", "줘")}:
        candidate = interpret(f"{owner(state)}, {text}", state, pack)
        assert candidate == {"intent": "ACTION", "declared_player_id": state["expected_player_id"],
                             "action_id": case["action"], "needs_clarification": False, "reason": None}
        result = apply_action(state, pack, candidate["declared_player_id"], candidate["action_id"])
        assert result["turn_index"] == state["turn_index"] + 1
        assert result["history"][-1]["action_id"] == case["action"]
    assert state == before
    old = CATALOG.load(case["pack_id"], case["prior_version"])
    prior = walk(old, case["path"], count)
    rejected = interpret(f"{owner(prior)}, {case['text']}", prior, old)
    assert rejected["needs_clarification"] is True and rejected["action_id"] is None


@pytest.mark.parametrize("case", CASES, ids=lambda case: case["id"])
@pytest.mark.parametrize("count", [1, 2])
def test_requests_do_not_absorb_competing_negative_control_or_repeated_words(case, count):
    pack = CATALOG.current(case["pack_id"])
    state = walk(pack, case["path"], count)
    before = copy.deepcopy(state)
    text, other = case["text"], case["other"]
    for body in (f"{text} 그리고 {other}", f"{text} 하지 마", f"{text} 말고",
                 f"{text} 다시", f"{text} 그만", f"{text} {text}", f"어제 {text}라고 했어"):
        candidate = interpret(f"{owner(state)}, {body}", state, pack)
        assert candidate["needs_clarification"] is True, (body, candidate)
        assert candidate["action_id"] is None, (body, candidate)
        assert state == before
    corrected = interpret(f"{owner(state)}, {text} 말고 {other}", state, pack)
    assert corrected["needs_clarification"] is False and corrected["action_id"] == case["other_action"]
    for command, intent in [("그만", "PAUSE"), ("다시", "REPEAT"), ("기다려 줘", "WAIT")]:
        candidate = interpret(command, state, pack)
        assert candidate["intent"] == intent and candidate["action_id"] is None
    assert state == before


@pytest.mark.parametrize("case", CASES, ids=lambda case: case["id"])
def test_two_children_still_declare_one_owner_and_cannot_mix_names(case):
    pack = CATALOG.current(case["pack_id"])
    state = walk(pack, case["path"], 2)
    name = owner(state)
    other_name = next(p["nickname"] for p in state["players"] if p["nickname"] != name)
    missing = interpret(case["text"], state, pack)
    assert missing["needs_clarification"] is True and missing["reason"] == "missing_player"
    wrong = interpret(f"{other_name}, {case['text']}", state, pack)
    assert wrong["needs_clarification"] is True and wrong["reason"] == "wrong_player"
    for text in (f"{name}, {other_name}, {case['text']}",
                 f"{name}, {case['text']} 말고 {other_name}, {case['other']}"):
        mixed = interpret(text, state, pack)
        assert mixed["needs_clarification"] is True and mixed["reason"] == "multiple_players"


@pytest.mark.parametrize("published", PUBLISHED, ids=lambda item: item["pack_id"])
def test_published_request_editions_keep_010_bytes_metadata_and_all_non_alias_fields(published):
    source = ROOT / published["content_asset"]
    assert hashlib.sha256(source.read_bytes()).hexdigest() == published["sha256"]
    archived = next(e for e in CATALOG.legacy_entries
                    if (e["pack_id"], e["version"]) == (published["pack_id"], published["version"]))
    assert {k: v for k, v in archived.items() if k != "content_asset"} == published["catalog_metadata"]
    old = copy.deepcopy(CATALOG.load(published["pack_id"], published["version"]))
    # The published 0.11 request-only editions remain checkable after later prose edits.
    request_version = "0.2.0" if published["pack_id"] == "onu-little-picnic" else "0.6.0"
    current = copy.deepcopy(CATALOG.load(published["pack_id"], request_version))
    old.pop("version")
    current.pop("version")
    for scene_id, scene in old["scenes"].items():
        for previous, latest in zip(scene["actions"], current["scenes"][scene_id]["actions"], strict=True):
            previous_aliases = previous.pop("aliases", [])
            current_aliases = latest.pop("aliases", [])
            assert current_aliases[:len(previous_aliases)] == previous_aliases
    assert current == old
