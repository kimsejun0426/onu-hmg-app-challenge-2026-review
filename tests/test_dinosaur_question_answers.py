"""Published 0.8 question-answer revision remains exact after later content editions."""

import copy
import hashlib
import json
from pathlib import Path

import pytest

from carplay.catalog import ContentCatalog
from carplay.game import apply_action, interpret, new_game, present

ROOT = Path(__file__).resolve().parents[1]
FIXTURE_PATH = ROOT / "tests/fixtures/dinosaur_question_answers.json"
FIXTURE = json.loads(FIXTURE_PATH.read_text(encoding="utf-8"))
CASES = FIXTURE["cases"]
CATALOG = ContentCatalog()
PACK_ID = FIXTURE["pack_id"]
PLAYERS = [{"player_id": "p1", "nickname": "민준"}, {"player_id": "p2", "nickname": "서윤"}]


def walk(pack, path, count, players=None):
    state = new_game(copy.deepcopy(players or PLAYERS[:count]), pack)
    for action in path:
        state = apply_action(state, pack, state["expected_player_id"], action)
    return state


def named(state, text):
    name = next(p["nickname"] for p in state["players"] if p["player_id"] == state["expected_player_id"])
    return f"{name}, {text}"


def accepted(state, text, action, pack):
    result = interpret(text, state, pack)
    assert result == {"intent": "ACTION", "declared_player_id": state["expected_player_id"],
                      "action_id": action, "needs_clarification": False, "reason": None}, (text, result)
    return apply_action(state, pack, result["declared_player_id"], result["action_id"])


@pytest.mark.parametrize("case", CASES, ids=lambda c: c["id"])
@pytest.mark.parametrize("count", [1, 2])
def test_answers_follow_the_question_in_both_name_and_onu_address_positions(case, count):
    pack = CATALOG.load(PACK_ID, FIXTURE["current_version"])
    state = walk(pack, case["path"], count)
    assert state["scene_id"] == case["scene"]
    assert case["question"] in present(state, pack)["prompt"]
    before = copy.deepcopy(state)
    forms = [named(state, case["text"]), named(state, "오누야 " + case["text"]),
             "오누야 " + named(state, case["text"])]
    if count == 1:
        forms.extend([case["text"], "오누야 " + case["text"]])
    for text in forms:
        changed = accepted(state, text, case["action"], pack)
        assert changed == apply_action(state, pack, state["expected_player_id"], case["action"])
        assert len(changed["history"]) == len(case["path"]) + 1
    assert state == before
    old = CATALOG.load(PACK_ID, FIXTURE["prior_version"])
    old_state = walk(old, case["path"], count)
    candidate = interpret(named(old_state, case["text"]), old_state, old)
    assert candidate["action_id"] is None and candidate["needs_clarification"]


@pytest.mark.parametrize("case", CASES, ids=lambda c: c["id"])
@pytest.mark.parametrize("count", [1, 2])
def test_negative_both_repeated_reported_and_unfinished_answers_cannot_commit(case, count):
    pack = CATALOG.load(PACK_ID, FIXTURE["current_version"])
    state = walk(pack, case["path"], count)
    before = copy.deepcopy(state)
    text, other = case["text"], case["other"]
    rejected = [case["negative"], case["both"], "둘 다", "아무거나", "응", "첫 번째",
                f"{text} 하지 마", f"{text} 그리고 {other}", f"{text} {text}",
                f"어제 {text}라고 했어", f"{text} 말고", f"{text} 그만",
                f'오누야 "{text}"', f"오누야 오누야 {text}",
                f"오누야 어제 {text}라고 했어 말고 {other}"]
    for body in rejected:
        result = interpret(named(state, body), state, pack)
        assert result["action_id"] is None and result["needs_clarification"], (body, result)
    for body in [f"{text} 말고 {other}", f"오누야 {text} 말고 {other}"]:
        accepted(state, named(state, body), case["other_action"], pack)
    # Preserve the old unaddressed exact-quote normalization; do not expand its grammar.
    accepted(state, named(state, f'"{text}"'), case["action"], pack)
    for command, intent in [("그만", "PAUSE"), ("오누야 쉬자", "PAUSE"),
                            ("다시", "REPEAT"), ("기다려 줘", "WAIT"), ("몰라", "HINT")]:
        result = interpret(command, state, pack)
        assert result["intent"] == intent and result["action_id"] is None
    assert state == before


@pytest.mark.parametrize("case", CASES, ids=lambda c: c["id"])
def test_two_children_still_need_one_correct_declared_owner(case):
    pack = CATALOG.load(PACK_ID, FIXTURE["current_version"])
    state = walk(pack, case["path"], 2)
    other_name = next(p["nickname"] for p in state["players"] if p["player_id"] != state["expected_player_id"])
    for text in [case["text"], "오누야 " + case["text"]]:
        result = interpret(text, state, pack)
        assert result["reason"] == "missing_player" and result["needs_clarification"]
    for text in [f"{other_name}, {case['text']}", f"오누야 {other_name}, {case['text']}"]:
        result = interpret(text, state, pack)
        assert result["reason"] == "wrong_player" and result["needs_clarification"]
    for text in [named(state, f"{other_name}, {case['text']}"),
                 named(state, f"{case['text']} 말고 {other_name}, {case['other']}")]:
        result = interpret(text, state, pack)
        assert result["reason"] == "multiple_players" and result["needs_clarification"]


@pytest.mark.parametrize("case", CASES, ids=lambda c: c["id"])
def test_a_full_registered_name_wins_over_a_new_answer_alias(case):
    pack = CATALOG.load(PACK_ID, FIXTURE["current_version"])
    players = copy.deepcopy(PLAYERS)
    players[len(case["path"]) % 2]["nickname"] = case["text"]
    state = walk(pack, case["path"], 2, players)
    for text in [case["text"], "오누야 " + case["text"]]:
        result = interpret(text, state, pack)
        assert result["action_id"] is None and result["needs_clarification"]
    accepted(state, named(state, case["text"]), case["action"], pack)
    # Repeating a full registered name after an address remains address-only; do not
    # loosen the safeguard merely because that name is also a new complete answer.
    repeated = interpret("오누야 " + named(state, case["text"]), state, pack)
    assert repeated["reason"] == "address_only" and repeated["action_id"] is None
    label = next(a["label"] for a in present(state, pack)["actions"] if a["id"] == case["action"])
    accepted(state, "오누야 " + named(state, label), case["action"], pack)


@pytest.mark.parametrize("nickname", ["오누", "오누야", "오 누야"])
def test_colliding_onu_names_keep_the_registered_name_grammar(nickname):
    pack = CATALOG.load(PACK_ID, FIXTURE["current_version"])
    state = walk(pack, [], 2, [{"player_id": "p1", "nickname": "민준"}, {"player_id": "p2", "nickname": nickname}])
    accepted(state, "민준, 물가에서 찾을래", "RIVER", pack)
    result = interpret("민준, 오누야 물가에서 찾을래", state, pack)
    assert result["action_id"] is None and result["needs_clarification"]


def test_box_named_child_does_not_turn_a_shortened_answer_into_an_owner_guess():
    pack = CATALOG.load(PACK_ID, FIXTURE["current_version"])
    state = walk(pack, ["RIVER"], 2,
                 [{"player_id": "p1", "nickname": "상자"}, {"player_id": "p2", "nickname": "서윤"}])
    accepted(state, "서윤, 상자 표지를 볼래", "READ_LABEL", pack)
    for text in ["상자 표지를 볼래", "서윤, 표지를 볼래"]:
        result = interpret(text, state, pack)
        assert result["action_id"] is None and result["needs_clarification"]


def test_original_bytes_metadata_and_only_the_eight_authored_aliases_change():
    old = CATALOG.load(PACK_ID, FIXTURE["prior_version"])
    archived = next(e for e in CATALOG.legacy_entries if (e["pack_id"], e["version"]) == (PACK_ID, "0.7.0"))
    raw = CATALOG.source(archived).read_bytes()
    assert len(raw) == FIXTURE["published_source_bytes"]
    assert hashlib.sha256(raw).hexdigest() == FIXTURE["published_source_sha256"]
    metadata = {k: v for k, v in archived.items() if k != "content_asset"}
    encoded = json.dumps(metadata, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()
    assert hashlib.sha256(encoded).hexdigest() == FIXTURE["published_metadata_sha256"]
    active = next(e for e in CATALOG.all_entries if (e["pack_id"], e["version"]) ==
                  (PACK_ID, FIXTURE["current_version"]))
    assert active["version"] == FIXTURE["current_version"]
    assert {k: v for k, v in active.items() if k not in {"version", "content_asset"}} == {
        k: v for k, v in metadata.items() if k != "version"}
    new = copy.deepcopy(CATALOG.load(PACK_ID, FIXTURE["current_version"]))
    assert new["version"] == "0.8.0" and len(CASES) == 8 and len({c["text"] for c in CASES}) == 6
    new["version"] = old["version"]
    for case in CASES:
        before = next(a for a in old["scenes"][case["scene"]]["actions"] if a["id"] == case["action"])
        after = next(a for a in new["scenes"][case["scene"]]["actions"] if a["id"] == case["action"])
        assert after["aliases"] == before["aliases"] + [case["text"]]
        after["aliases"] = before["aliases"]
    assert new == old


@pytest.mark.parametrize("count", [1, 2])
def test_all_paths_preserve_turns_scores_resources_endings_and_old_aliases(count):
    old, new = CATALOG.load(PACK_ID, "0.7.0"), CATALOG.load(PACK_ID, FIXTURE["current_version"])
    pending = [(new_game(PLAYERS[:count], old), new_game(PLAYERS[:count], new))]
    completed = 0
    while pending:
        previous, state = pending.pop()
        assert {k: v for k, v in previous.items() if k != "pack_version"} == {
            k: v for k, v in state.items() if k != "pack_version"}
        old_view, view = present(previous, old), present(state, new)
        for field in ["prompt", "hint", "feedback"]:
            assert view[field] == old_view[field]
        assert [(a["id"], a["label"]) for a in view["actions"]] == [(a["id"], a["label"]) for a in old_view["actions"]]
        if state["finished"]:
            completed += 1
            assert state["turn_index"] == 6
            continue
        for action in old_view["actions"]:
            for text in [action["id"], action["label"], *action["aliases"]]:
                assert interpret(named(state, text), state, new) == interpret(named(previous, text), previous, old)
            pending.append((apply_action(previous, old, previous["expected_player_id"], action["id"]),
                            apply_action(state, new, state["expected_player_id"], action["id"])))
    assert completed == 64
