"""Space 0.8 recalls actual choices without changing turns or published history."""

import copy
import hashlib
import json
from itertools import product
from pathlib import Path

import pytest

from carplay.catalog import ContentCatalog
from carplay.game import _render, apply_action, interpret, new_game, present

ROOT = Path(__file__).resolve().parents[1]
CATALOG = ContentCatalog()
CURRENT = CATALOG.load("space-return-signal", "0.8.0")
PREVIOUS = CATALOG.load("space-return-signal", "0.7.0")
PUBLISHED = json.loads((ROOT / "tests/fixtures/published_onu_014_space.json").read_text(encoding="utf-8"))
PLAYERS = [{"player_id": "p1", "nickname": "민준"}, {"player_id": "p2", "nickname": "서윤"}]


def walk(story, path, count):
    state = new_game(PLAYERS[:count], story)
    for action in path:
        state = apply_action(state, story, state["expected_player_id"], action)
    return state


def game_effects(state):
    result = copy.deepcopy(state)
    for field in ("pack_version", "feedback"):
        result.pop(field)
    for player in result["players"]:
        player.pop("role")
    return result


def test_delivered_014_space_bytes_metadata_and_exact_version_lookup_are_preserved():
    source = ROOT / PUBLISHED["content_asset"]
    raw = source.read_bytes()
    assert len(raw) == PUBLISHED["bytes"]
    assert hashlib.sha256(raw).hexdigest() == PUBLISHED["sha256"]
    old_entry = next(entry for entry in CATALOG.legacy_entries
                     if (entry["pack_id"], entry["version"]) == ("space-return-signal", "0.7.0"))
    assert CATALOG.source(old_entry) == source.resolve()
    assert {key: value for key, value in old_entry.items() if key != "content_asset"} == PUBLISHED["catalog_metadata"]
    assert PREVIOUS == json.loads(raw)
    assert CATALOG.load("space-return-signal", "0.8.0") == CURRENT
    current_entry = next(entry for entry in CATALOG.all_entries
                         if (entry["pack_id"], entry["version"]) == ("space-return-signal", "0.8.0"))
    assert {key: value for key, value in current_entry.items() if key not in {"version", "content_asset"}} == {
        key: value for key, value in old_entry.items() if key not in {"version", "content_asset"}
    }


def test_only_approved_narration_roles_flat_labels_and_two_exact_aliases_change():
    current, previous = copy.deepcopy(CURRENT), copy.deepcopy(PREVIOUS)
    assert current["roles"] == ["탐사 친구", "탐사 친구"]
    assert previous["roles"] == ["관측 대장", "귀환 대장"]
    for label in current["template_labels"].values():
        for text in [label["default"], *label["values"].values()]:
            assert "{" not in text and "}" not in text, "Labels must not require recursive rendering."
    changed_labels = {name for name in current["template_labels"]
                      if current["template_labels"].get(name) != previous["template_labels"].get(name)}
    assert changed_labels == {"record_object", "record_prefix", "record_suffix", "observation_object", "observation_style"}
    assert {current["template_labels"][name]["flag"] for name in changed_labels} == {"mission", "tool_used", "record"}
    assert set(previous["template_labels"]) - set(current["template_labels"]) == {"record_memory"}
    for field in ("version", "roles", "template_labels"):
        current.pop(field)
        previous.pop(field)
    allowed_text = {
        "return_order": ("prompt", "hint"), "share_record": ("prompt",),
        "ending_gallery": ("prompt",), "ending_story": ("prompt", "summary"),
    }
    for scene_id, fields in allowed_text.items():
        for field in fields:
            current["scenes"][scene_id].pop(field)
            previous["scenes"][scene_id].pop(field)
    for scene_id in ("prepare_return", "return_order"):
        for new_action, old_action in zip(current["scenes"][scene_id]["actions"],
                                          previous["scenes"][scene_id]["actions"], strict=True):
            new_action.pop("feedback")
            old_action.pop("feedback")
            if scene_id == "return_order":
                added = "문 닫기부터" if new_action["id"] == "CLOSE_DOOR" else "출발 신호부터"
                assert new_action["aliases"] == [*old_action["aliases"], added]
                new_action["aliases"].pop()
    assert current == previous


@pytest.mark.parametrize("count", [1, 2])
@pytest.mark.parametrize("mission,tool,record,sharing", list(product(
    ["MOON", "MARS"], ["PANORAMA", "CLOSE_CAMERA"], ["PHOTO", "NOTE"], ["MAKE_GALLERY", "TELL_STORY"],
)))
def test_each_ending_recalls_only_the_selected_observation_and_record(count, mission, tool, record, sharing):
    path = [mission, tool, record, "READ_CHECKLIST", "CLOSE_DOOR", sharing]
    state = walk(CURRENT, path, count)
    before = copy.deepcopy(state)
    text = present(state, CURRENT)["prompt"]
    subject, other_subject = ("달의 충돌구를", "화성") if mission == "MOON" else ("화성의 붉은 먼지를", "달")
    view, other_view = ("한눈에 본", "가까이 본") if tool == "PANORAMA" else ("가까이 본", "한눈에 본")
    if record == "PHOTO":
        expected = f"사진에는 {subject} {view} 모습이 담겼어."
        assert "노트" not in text
    else:
        expected = f"노트에는 {subject} {view} 이야기가 적혔어."
        assert "사진" not in text
    assert expected in text
    assert other_subject not in text and other_view not in text
    assert text.count(".") == 3 and "{" not in text and "}" not in text
    if sharing == "MAKE_GALLERY":
        assert state["ending"] == "MISSION_GALLERY" and "전시를 열었어" in text
        assert "들려줬어" not in text
    else:
        assert state["ending"] == "MISSION_STORY" and "들려줬어" in text
        assert "전시" not in text
    other_tool = "CLOSE_CAMERA" if tool == "PANORAMA" else "PANORAMA"
    contrasting_path = [mission, other_tool, record, "READ_CHECKLIST", "CLOSE_DOOR", sharing]
    assert text != present(walk(CURRENT, contrasting_path, count), CURRENT)["prompt"]
    # The delivered edition stays reproducible, including its original generic recall.
    assert present(walk(PREVIOUS, path, count), PREVIOUS)["prompt"] == present(
        walk(PREVIOUS, contrasting_path, count), PREVIOUS)["prompt"]
    assert state == before


@pytest.mark.parametrize("count", [1, 2])
def test_all_64_paths_keep_the_published_actions_scores_resources_clues_and_turns(count):
    pending = [(new_game(PLAYERS[:count], CURRENT), new_game(PLAYERS[:count], PREVIOUS))]
    completed = 0
    while pending:
        state, old = pending.pop()
        assert game_effects(state) == game_effects(old)
        if state["finished"]:
            completed += 1
            assert state["turn_index"] == 6
            assert state["score_by_player"] == ({"p1": 6} if count == 1 else {"p1": 3, "p2": 3})
            continue
        assert [action["id"] for action in present(state, CURRENT)["actions"]] == [
            action["id"] for action in present(old, PREVIOUS)["actions"]
        ]
        for action in present(state, CURRENT)["actions"]:
            pending.append((apply_action(state, CURRENT, state["expected_player_id"], action["id"]),
                            apply_action(old, PREVIOUS, old["expected_player_id"], action["id"])))
    assert completed == 64


@pytest.mark.parametrize("count", [1, 2])
@pytest.mark.parametrize("mission", ["MOON", "MARS"])
def test_six_questions_name_the_actual_child_with_neutral_roles_and_correct_record_particle(count, mission):
    state = new_game(PLAYERS[:count], CURRENT)
    path = [mission, "PANORAMA", "NOTE", "COUNT_STEPS", "CLOSE_DOOR", "TELL_STORY"]
    for index, action in enumerate(path):
        player = PLAYERS[index % count]
        assert state["expected_player_id"] == player["player_id"]
        text = present(state, CURRENT)["prompt"]
        prefix = f"{player['nickname']}, " if count == 1 else f"{player['nickname']} 탐사 친구 차례야. "
        assert text.startswith(prefix) and text.count("?") == 1
        assert "관측 대장" not in text and "귀환 대장" not in text
        if state["scene_id"] == "share_record":
            assert "관찰 노트를 토리가" in text and "노트을" not in text
        state = apply_action(state, CURRENT, player["player_id"], action)
    summary = CURRENT["scenes"][state["scene_id"]]["summary"]
    assert "관찰 노트를 보며" in _render(summary, state, CURRENT)


@pytest.mark.parametrize("preparation", ["READ_CHECKLIST", "COUNT_STEPS"])
@pytest.mark.parametrize("order", ["CLOSE_DOOR", "SEND_SIGNAL"])
def test_both_return_paths_board_the_spaceship_before_closing_its_door(preparation, order):
    state = walk(CURRENT, ["MOON", "PANORAMA", "PHOTO", preparation], 2)
    feedback = state["feedback"]
    assert feedback.startswith("돌아갈 우주선에 토리와 오누가 함께 탔어.")
    assert feedback.index("함께 탔어") < feedback.index("문 닫기") < feedback.index("출발 신호")
    view = present(state, CURRENT)
    assert "우주선의 문이 아직 열려" in view["prompt"] and "토리의 문" not in view["prompt"]
    assert "함께 탔으니" in view["hint"]
    result = apply_action(state, CURRENT, state["expected_player_id"], order)
    assert "탔어" not in result["feedback"]
    assert ("문을 닫고 출발 신호" if order == "CLOSE_DOOR" else "문부터 닫고 신호") in result["feedback"]
    assert result["scene_id"] == "share_record" and "정거장에 도착했어" in present(result, CURRENT)["prompt"]


@pytest.mark.parametrize("count", [1, 2])
@pytest.mark.parametrize("text,action,other", [
    ("문 닫기부터", "CLOSE_DOOR", "출발 신호부터"),
    ("출발 신호부터", "SEND_SIGNAL", "문 닫기부터"),
])
def test_precise_question_answers_are_new_only_and_preserve_interpretation_boundaries(count, text, action, other):
    path = ["MOON", "PANORAMA", "PHOTO", "READ_CHECKLIST"]
    state, old = walk(CURRENT, path, count), walk(PREVIOUS, path, count)
    before = copy.deepcopy(state)
    utterances = [f"민준, {text}", f"오누야 민준, {text}"]
    if count == 1:
        utterances.extend([text, f"오누야 {text}"])
    for utterance in utterances:
        candidate = interpret(utterance, state, CURRENT)
        assert candidate == {"intent": "ACTION", "declared_player_id": "p1", "action_id": action,
                             "needs_clarification": False, "reason": None}
    previous_candidate = interpret(f"민준, {text}", old, PREVIOUS)
    assert previous_candidate["needs_clarification"] and previous_candidate["action_id"] is None
    for body in (f"{text} 그리고 {other}", f"{text} 하지 마", f"{text} 말고",
                 f"{text} {text}", f"어제 {text}라고 했어", f"{text} 다시"):
        candidate = interpret(f"민준, {body}", state, CURRENT)
        assert candidate["needs_clarification"] and candidate["action_id"] is None, (body, candidate)
    for command, intent in (("그만", "PAUSE"), ("다시", "REPEAT"), ("기다려 줘", "WAIT"), ("도와줘", "HINT")):
        candidate = interpret(command, state, CURRENT)
        assert candidate["intent"] == intent and candidate["action_id"] is None
    if count == 2:
        assert interpret(text, state, CURRENT)["reason"] == "missing_player"
        assert interpret(f"서윤, {text}", state, CURRENT)["reason"] == "wrong_player"
    assert state == before
