"""Published 0.8 space/car causality remains reproducible after later prose revisions."""

import copy
from itertools import product
from pathlib import Path

import pytest

from carplay.catalog import ContentCatalog
from carplay.game import apply_action, interpret, load_pack, new_game, present

ROOT = Path(__file__).resolve().parents[1]
PLAYERS = [{"player_id": "p1", "nickname": "민준"}, {"player_id": "p2", "nickname": "서윤"}]


def pack(slug):
    return ContentCatalog().load(slug, "0.8.0")


def walk(story, actions, players=2):
    state = new_game(PLAYERS[:players], story)
    for action in actions:
        state = apply_action(state, story, state["expected_player_id"], action)
    return state


def advance(story, state, action):
    return apply_action(state, story, state["expected_player_id"], action)


def rules_only(value):
    # Text/labels can change; IDs, ordering, costs, facts and side effects retain
    # the exact published mechanics. Alias compatibility is checked separately.
    prose = {"version", "solo_prompt_style", "template_labels",
             "prompt", "hint", "feedback", "title", "ending_title", "summary", "label", "aliases", "roles"}
    if isinstance(value, dict):
        return {key: rules_only(item) for key, item in value.items() if key not in prose}
    if isinstance(value, list):
        return [rules_only(item) for item in value]
    return value


@pytest.mark.parametrize("slug", ["space-return-signal", "little-car-workshop"])
def test_story_revision_preserves_all_choices_rules_and_fact_sources(slug):
    current = pack(slug)
    previous = load_pack(ROOT / f"content/legacy/0.3.0/{slug}/episode.json")
    assert current["version"] == "0.8.0"
    assert rules_only(current) == rules_only(previous)
    assert current["roles"] == (["탐사 친구", "탐사 친구"] if slug == "space-return-signal" else previous["roles"])
    assert current["sources"] == previous["sources"]
    for scene_id, scene in current["scenes"].items():
        for action, old_action in zip(scene["actions"], previous["scenes"][scene_id]["actions"], strict=True):
            assert set([old_action["label"], *old_action["aliases"]]) <= set([action["label"], *action["aliases"]])


@pytest.mark.parametrize("mission", ["MOON", "MARS"])
def test_space_observation_tool_changes_the_next_record_prompt(mission):
    story = pack("space-return-signal")
    wide = walk(story, [mission, "PANORAMA"])
    close = walk(story, [mission, "CLOSE_CAMERA"])
    wide_text = present(wide, story)["prompt"]
    close_text = present(close, story)["prompt"]
    assert "한눈에 본 풍경" in wide_text and "가까이 본 모습" not in wide_text
    assert "가까이 본 모습" in close_text and "한눈에 본 풍경" not in close_text
    assert story["template_labels"]["world_label"]["values"][mission] in wide_text
    assert story["template_labels"]["world_label"]["values"][mission] in close_text
    assert wide["clues"] != close["clues"]
    assert wide["resources"]["tool_tokens"] == close["resources"]["tool_tokens"] == 0


@pytest.mark.parametrize("record,chosen,unchosen", [
    ("PHOTO", "풍경 사진", "관찰 노트"),
    ("NOTE", "관찰 노트", "풍경 사진"),
])
def test_space_return_preparation_names_the_record_actually_packed(record, chosen, unchosen):
    story = pack("space-return-signal")
    state = walk(story, ["MARS", "CLOSE_CAMERA", record])
    text = present(state, story)["prompt"]
    assert chosen in text and unchosen not in text
    assert state["scene_id"] == "prepare_return"


def test_space_return_order_reuses_the_selected_way_of_checking():
    story = pack("space-return-signal")
    prefix = ["MOON", "PANORAMA", "PHOTO"]
    checklist = present(walk(story, [*prefix, "READ_CHECKLIST"]), story)["prompt"]
    counting = present(walk(story, [*prefix, "COUNT_STEPS"]), story)["prompt"]
    assert "확인표로" in checklist and "하나둘 세며" not in checklist
    assert "하나둘 세며" in counting and "확인표로" not in counting
    for text in (checklist, counting):
        assert "문이 아직 열려" in text
        assert "문 닫기" in text and "출발 신호" in text


@pytest.mark.parametrize("mission,record,sharing", list(product(
    ["MOON", "MARS"], ["PHOTO", "NOTE"], ["MAKE_GALLERY", "TELL_STORY"],
)))
def test_space_ending_keeps_the_chosen_record_and_does_not_invent_other_sharing(mission, record, sharing):
    story = pack("space-return-signal")
    state = walk(story, [mission, "CLOSE_CAMERA", record, "COUNT_STEPS", "CLOSE_DOOR", sharing])
    text = present(state, story)["prompt"]
    assert story["template_labels"]["world_label"]["values"][mission] in text
    if record == "PHOTO":
        assert "사진에는" in text and "담겼어" in text and "노트에는" not in text
    else:
        assert "노트에는" in text and "적혔어" in text and "사진에는" not in text
    if sharing == "MAKE_GALLERY":
        assert state["ending"] == "MISSION_GALLERY" and "전시를 열었어" in text
    else:
        assert state["ending"] == "MISSION_STORY" and "들려줬어" in text
        assert "전시를 열었어" not in text
    assert state["finished"] and state["turn_index"] == 6
    assert state["score_by_player"] == {"p1": 3, "p2": 3}


@pytest.mark.parametrize("delivery,vehicle", list(product(
    ["PLANTS", "BOOKS"], ["OPEN_CART", "COVERED_VAN"],
)))
def test_vehicle_prepares_empty_cargo_space_and_checks_before_loading(delivery, vehicle):
    story = pack("little-car-workshop")
    prepared = walk(story, [delivery, "READ_ORDER", vehicle])
    assert prepared["scene_id"] == "check_vehicle"
    assert "준비했어" in prepared["feedback"]
    for premature_load in ("실었어", "넣었어", "세웠어"):
        assert premature_load not in prepared["feedback"]
    assert story["template_labels"]["vehicle_label"]["values"][vehicle] in present(prepared, story)["prompt"]
    checked = advance(story, prepared, "CHECK_LIGHT")
    assert checked["scene_id"] == "load_order" and len(checked["history"]) == 4
    assert "실었어" not in checked["feedback"] and "옆면 고리" in checked["feedback"]
    # Both answers genuinely load cargo only at the fifth choice; a mistaken
    # ordering is explained and rearranged without changing the completion cost.
    for first in ("STAR_FIRST", "MOON_FIRST"):
        loaded = advance(story, checked, first)
        assert loaded["scene_id"] == "handover"
        assert len(loaded["history"]) == 5 and "실었어" in loaded["feedback"]
        assert loaded["score_by_player"] == {"p1": 3, "p2": 2}


def test_vehicle_context_revision_only_changes_the_loading_question():
    current = copy.deepcopy(pack("little-car-workshop"))
    previous = load_pack(ROOT / "content/legacy/0.7.0/little-car-workshop/episode.json")
    assert current["scenes"]["load_order"]["prompt"] != previous["scenes"]["load_order"]["prompt"]
    current["scenes"]["load_order"]["prompt"] = previous["scenes"]["load_order"]["prompt"]
    current["version"] = previous["version"]
    assert current == previous


@pytest.mark.parametrize("count", [1, 2])
@pytest.mark.parametrize("delivery,near,inside,matching", [
    ("PLANTS", "별 상자", "달 상자", "MOON_FIRST"),
    ("BOOKS", "달 상자", "별 상자", "STAR_FIRST"),
])
def test_vehicle_unload_goal_precedes_both_loading_choices_and_their_real_results(count, delivery, near, inside, matching):
    story = pack("little-car-workshop")
    before = walk(story, [delivery, "READ_ORDER", "OPEN_CART", "CHECK_LIGHT"], count)
    prompt = present(before, story)["prompt"]
    assert f"두리가 {near}부터 내릴 거야. 안쪽에는 별 상자를 둘까, 달 상자를 둘까?" in prompt
    assert "문 가까이에 둘게" not in prompt and "실었어" not in prompt
    assert "CARGO_ORDER_READY" not in before["clues"]
    assert prompt.startswith("민준, " if count == 1 else "민준 짐칸 설계사 차례야. ")
    for reply, action in [("별 상자", "STAR_FIRST"), ("달 상자", "MOON_FIRST")]:
        candidate = interpret(reply if count == 1 else f"민준, {reply}", before, story)
        assert candidate["intent"] == "ACTION" and candidate["action_id"] == action
        assert candidate["declared_player_id"] == "p1" and not candidate["needs_clarification"]
        loaded = advance(story, before, action)
        assert loaded["flags"]["load_order_recalled"] is (action == matching)
        if action == matching:
            assert f"두리가 {inside}는 안쪽, {near}는 문 가까이에 실었어." in loaded["feedback"]
            assert "다시 실었어" not in loaded["feedback"]
        else:
            assert f"{near}가 안쪽에 있으면 먼저 꺼내기 어렵네." in loaded["feedback"]
            assert f"두리가 {near}는 문 가까이, {inside}는 안쪽에 다시 실었어." in loaded["feedback"]
        assert loaded["history"][-1]["action_id"] == action
        assert loaded["turn_index"] == 5 and loaded["scene_id"] == "handover"
        assert "CARGO_ORDER_READY" in loaded["clues"]
        assert loaded["expected_player_id"] == ("p1" if count == 1 else "p2")


@pytest.mark.parametrize("tool,chosen,unchosen", [
    ("CHECK_LIGHT", "빛을 비춰", "거울에 비친"),
    ("CHECK_MIRROR", "거울에 비친", "빛을 비춰"),
])
def test_vehicle_handover_remembers_the_tool_used_without_advancing_again(tool, chosen, unchosen):
    story = pack("little-car-workshop")
    state = walk(story, ["BOOKS", "LOOK_CARGO", "OPEN_CART", tool, "STAR_FIRST"])
    before = copy.deepcopy(state)
    text = present(state, story)["prompt"]
    assert chosen in text and unchosen not in text
    assert "차례표를 읽어 줄까" in text and "확인표를 건넬까" in text
    assert state == before


@pytest.mark.parametrize("delivery,loading,handover", list(product(
    ["PLANTS", "BOOKS"], ["STAR_FIRST", "MOON_FIRST"], ["READ_DELIVERY_ORDER", "PASS_CHECKLIST"],
)))
def test_vehicle_endings_remember_real_order_after_either_loading_choice(delivery, loading, handover):
    story = pack("little-car-workshop")
    state = walk(story, [delivery, "LOOK_CARGO", "COVERED_VAN", "CHECK_MIRROR", loading, handover])
    text = present(state, story)["prompt"]
    if delivery == "PLANTS":
        for memory in ("마을 정원", "키 큰 화분", "별 상자부터"):
            assert memory in text
        assert "작은 도서관" not in text and "달 상자부터" not in text
    else:
        for memory in ("작은 도서관", "그림책 상자", "달 상자부터"):
            assert memory in text
        assert "마을 정원" not in text and "별 상자부터" not in text
    if handover == "READ_DELIVERY_ORDER":
        assert state["ending"] == "DELIVERY_READY" and "두리는" in text
        assert "준비가 끝났어" in text
    else:
        assert state["ending"] == "DELIVERY_HANDOVER" and "두리와 배달 친구" in text
        assert "말을 전했어" in text and "배웅" in text
    for invented_completion in ("배달을 마쳤어", "배달이 끝났어", "도착했어"):
        assert invented_completion not in text
    assert state["turn_index"] == 6 and state["score_by_player"] == {"p1": 3, "p2": 3}


@pytest.mark.parametrize("slug", ["space-return-signal", "little-car-workshop"])
@pytest.mark.parametrize("players", [1, 2])
def test_every_spoken_choice_is_one_question_and_endings_stay_short(slug, players):
    story = pack(slug)
    previous = load_pack(ROOT / "content/legacy/0.7.0/little-car-workshop/episode.json") if slug == "little-car-workshop" else None
    pending = [(new_game(PLAYERS[:players], story), new_game(PLAYERS[:players], previous) if previous else None)]
    finished = []
    while pending:
        state, old = pending.pop()
        if previous:
            effects = copy.deepcopy(state)
            effects["pack_version"] = old["pack_version"]
            assert effects == old
            assert present(state, story)["actions"] == present(old, previous)["actions"]
        view = present(state, story)
        assert "{" not in view["prompt"] and "}" not in view["prompt"]
        if state["finished"]:
            finished.append(state)
            assert view["prompt"].count(".") <= 3
            continue
        assert view["prompt"].count("?") == 1
        assert len(view["actions"]) == 2
        for action in view["actions"]:
            following = advance(story, state, action["id"])
            assert "{" not in following["feedback"] and "}" not in following["feedback"]
            pending.append((following, advance(previous, old, action["id"]) if previous else None))
    assert len(finished) == 64
    assert {state["turn_index"] for state in finished} == {6}
    assert len({state["ending"] for state in finished}) == 2
