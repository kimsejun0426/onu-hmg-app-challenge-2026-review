"""Short authored preferences stay scoped to two subjective questions and one edition."""

import copy
import hashlib
import json
from pathlib import Path

import pytest

from carplay.catalog import ContentCatalog
from carplay.game import apply_action, interpret, new_game, present

ROOT = Path(__file__).resolve().parents[1]
FIXTURE = json.loads((ROOT / "tests/fixtures/short_preference_answers.json").read_text(encoding="utf-8"))
CASES = FIXTURE["cases"]
CATALOG = ContentCatalog()
PACK_ID = FIXTURE["pack_id"]
PREVIOUS = CATALOG.load(PACK_ID, FIXTURE["prior_version"])
REVISED = CATALOG.load(PACK_ID, FIXTURE["current_version"])
PLAYERS = [{"player_id": "p1", "nickname": "민준"}, {"player_id": "p2", "nickname": "서윤"}]


def walk(pack, path, count, players=None):
    state = new_game(copy.deepcopy(players or PLAYERS[:count]), pack)
    for action in path:
        state = apply_action(state, pack, state["expected_player_id"], action)
    return state


def named(state, text):
    player = next(p for p in state["players"] if p["player_id"] == state["expected_player_id"])
    return f"{player['nickname']}, {text}"


def accepted(state, text, action):
    candidate = interpret(text, state, REVISED)
    assert candidate == {"intent": "ACTION", "declared_player_id": state["expected_player_id"],
                         "action_id": action, "needs_clarification": False, "reason": None}, (text, candidate)
    changed = apply_action(state, REVISED, candidate["declared_player_id"], candidate["action_id"])
    assert changed == apply_action(state, REVISED, state["expected_player_id"], action)
    return changed


@pytest.mark.parametrize("case", CASES, ids=lambda c: c["id"])
@pytest.mark.parametrize("count", [1, 2])
def test_authored_preferences_select_the_declared_action_without_changing_the_prior_edition(case, count):
    state = walk(REVISED, case["path"], count)
    previous = walk(PREVIOUS, case["path"], count)
    before = copy.deepcopy(state)
    assert state["scene_id"] == case["scene"]
    assert case["question"] in present(state, REVISED)["prompt"]
    assert REVISED["scenes"][case["scene"]]["kind"] == "choice"
    forms = [named(state, case["text"]), named(state, "오누야 " + case["text"]),
             "오누야 " + named(state, case["text"])]
    if count == 1:
        forms += [case["text"], "오누야 " + case["text"]]
    for text in forms:
        changed = accepted(state, text, case["action"])
        assert len(changed["history"]) == len(case["path"]) + 1
        old = interpret(text, previous, PREVIOUS)
        assert old["needs_clarification"] and old["action_id"] is None
    assert state == before


@pytest.mark.parametrize("case", CASES, ids=lambda c: c["id"])
@pytest.mark.parametrize("count", [1, 2])
def test_negation_multiple_choices_reports_quotes_and_unfinished_corrections_never_commit(case, count):
    state = walk(REVISED, case["path"], count)
    before = copy.deepcopy(state)
    text, other = case["text"], case["other"]
    rejected = [case["negative"], case["both"], "둘 다", "아무거나", "응", "첫 번째",
                f"{text} 하지 마", f"{text} 그리고 {other}", f"{text} {text}",
                f"어제 {text}라고 했어", f"{text} 말고", f"{text} 그만",
                f'오누야 "{text}"', f"오누야 오누야 {text}",
                f"오누야 어제 {text}라고 했어 말고 {other}"]
    for body in rejected:
        candidate = interpret(named(state, body), state, REVISED)
        assert candidate["needs_clarification"] and candidate["action_id"] is None, (body, candidate)
    for body in [f"{text} 말고 {other}", f"오누야 {text} 말고 {other}"]:
        accepted(state, named(state, body), case["other_action"])
    # Unaddressed complete quotes keep the old normalization; the address guard stays strict.
    accepted(state, named(state, f'"{text}"'), case["action"])
    for body, intent in [("그만", "PAUSE"), ("오누야 쉬자", "PAUSE"), ("다시", "REPEAT"),
                         ("기다려 줘", "WAIT"), ("몰라", "HINT")]:
        candidate = interpret(body, state, REVISED)
        assert candidate["intent"] == intent and candidate["action_id"] is None
    assert state == before


@pytest.mark.parametrize("case", CASES, ids=lambda c: c["id"])
def test_two_children_need_one_declared_owner_and_keep_the_current_turn(case):
    state = walk(REVISED, case["path"], 2)
    before = copy.deepcopy(state)
    other_name = next(p["nickname"] for p in state["players"] if p["player_id"] != state["expected_player_id"])
    for text in [case["text"], "오누야 " + case["text"]]:
        candidate = interpret(text, state, REVISED)
        assert candidate["reason"] == "missing_player" and candidate["needs_clarification"]
    for text in [f"{other_name}, {case['text']}", f"오누야 {other_name}, {case['text']}"]:
        candidate = interpret(text, state, REVISED)
        assert candidate["reason"] == "wrong_player" and candidate["needs_clarification"]
    for text in [named(state, f"{other_name}, {case['text']}"),
                 named(state, f"{case['text']} 말고 {other_name}, {case['other']}")]:
        candidate = interpret(text, state, REVISED)
        assert candidate["reason"] == "multiple_players" and candidate["needs_clarification"]
    assert state == before


@pytest.mark.parametrize("case", CASES, ids=lambda c: c["id"])
def test_a_full_registered_nickname_is_not_treated_as_the_new_preference(case):
    players = copy.deepcopy(PLAYERS)
    players[len(case["path"]) % 2]["nickname"] = case["text"]
    state = walk(REVISED, case["path"], 2, players)
    for text in [case["text"], "오누야 " + case["text"]]:
        candidate = interpret(text, state, REVISED)
        assert candidate["needs_clarification"] and candidate["action_id"] is None
    accepted(state, named(state, case["text"]), case["action"])
    repeated = interpret("오누야 " + named(state, case["text"]), state, REVISED)
    assert repeated["reason"] == "address_only" and repeated["action_id"] is None


@pytest.mark.parametrize("path, scene, text", [
    (["RIVER", "FOLLOW_TRACKS"], "diet", "나뭇잎이 좋아"),
    (["RIVER", "FOLLOW_TRACKS", "LEAVES", "MAGNIFIER"], "identity", "트리케라톱스가 좋아"),
    (["RIVER", "FOLLOW_TRACKS", "LEAVES", "MAGNIFIER", "TRICERATOPS"], "deduction", "온실이 좋아"),
    (["RIVER", "FOLLOW_TRACKS", "LEAVES"], "tool", "물가가 좋아"),
])
def test_preference_support_does_not_leak_into_facts_evidence_or_other_scenes(path, scene, text):
    state = walk(REVISED, path, 2)
    before = copy.deepcopy(state)
    assert state["scene_id"] == scene
    candidate = interpret(named(state, text), state, REVISED)
    assert candidate["needs_clarification"] and candidate["action_id"] is None
    assert state == before


def test_exact_published_bytes_metadata_and_only_eight_authored_aliases_are_changed():
    assert FIXTURE["synthetic_inputs_only"] and len(CASES) == 8
    assert len({c["text"] for c in CASES}) == 8
    assert {c["scene"] for c in CASES} == {"choose_path", "tool"}
    prior = next(e for e in CATALOG.all_entries if (e["pack_id"], e["version"]) == (PACK_ID, "0.8.0"))
    revised = next(e for e in CATALOG.all_entries if (e["pack_id"], e["version"]) == (PACK_ID, "0.9.0"))
    raw = CATALOG.source(prior).read_bytes()
    assert len(raw) == FIXTURE["published_source_bytes"]
    assert hashlib.sha256(raw).hexdigest() == FIXTURE["published_source_sha256"]
    metadata = {k: v for k, v in prior.items() if k != "content_asset"}
    encoded = json.dumps(metadata, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()
    assert hashlib.sha256(encoded).hexdigest() == FIXTURE["published_metadata_sha256"]
    assert {k: v for k, v in revised.items() if k not in {"version", "content_asset"}} == {
        k: v for k, v in metadata.items() if k != "version"}
    stripped = copy.deepcopy(REVISED)
    stripped["version"] = PREVIOUS["version"]
    expected = {}
    for case in CASES:
        expected.setdefault((case["scene"], case["action"]), []).append(case["text"])
    for (scene, action_id), additions in expected.items():
        old_action = next(a for a in PREVIOUS["scenes"][scene]["actions"] if a["id"] == action_id)
        new_action = next(a for a in stripped["scenes"][scene]["actions"] if a["id"] == action_id)
        assert new_action["aliases"] == old_action["aliases"] + additions
        new_action["aliases"] = old_action["aliases"]
    assert stripped == PREVIOUS
