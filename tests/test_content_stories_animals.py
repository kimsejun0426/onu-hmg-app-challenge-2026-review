"""Published story 0.7 / animal 0.8 consequences remain version-exact."""

import copy
import itertools
from pathlib import Path

import pytest

from carplay.catalog import ContentCatalog
from carplay.game import apply_action, apply_hint, interpret, load_pack, new_game, present, validate_pack
from content_release_expectations import RETIRED_ANIMAL_WAIT_WORDS


ROOT = Path(__file__).resolve().parents[1]
PLAYERS = [{"player_id": "p1", "nickname": "별"}, {"player_id": "p2", "nickname": "달"}]
SLUGS = ["starlight-post-office", "animal-footprint-garden"]
OBSERVATIONS = {
    ("POND", "MOVEMENT"): ("DUCK_WAKE", "오리 뒤에 남은 잔물결을", "오리가 지나간 뒤 잔물결이 남았어."),
    ("POND", "REST"): ("DUCK_STONE", "돌 옆에 멈춘 오리 모습을", "오리가 돌 옆에 멈춰 있어."),
    ("FLOWER_GARDEN", "MOVEMENT"): ("BUTTERFLY_FLIGHT", "꽃 사이로 날아간 나비 모습을", "나비가 하얀 꽃에서 노란 꽃으로 날아갔어."),
    ("FLOWER_GARDEN", "REST"): ("BUTTERFLY_FLOWER", "노란 꽃에 앉은 나비 모습을", "나비가 노란 꽃에 앉아 있어."),
}


def load(slug, legacy=False):
    if legacy:
        return load_pack(ROOT / "content/legacy/0.3.0" / slug / "episode.json")
    return ContentCatalog().load(slug, "0.8.0" if slug == "animal-footprint-garden" else "0.7.0")


def walk(pack, actions, count=2, help_each_turn=False):
    state = new_game(PLAYERS[:count], pack)
    for action in actions:
        if help_each_turn:
            state = apply_hint(state, pack)
        state = apply_action(state, pack, state["expected_player_id"], action)
    return state


def complete_paths(pack, count):
    stack = [(new_game(PLAYERS[:count], pack), [])]
    while stack:
        state, actions = stack.pop()
        if state["finished"]:
            yield state, actions
        else:
            for choice in present(state, pack)["actions"]:
                stack.append((apply_action(state, pack, state["expected_player_id"], choice["id"]), actions + [choice["id"]]))


def rules_contract(pack):
    """Fields that changing prose or adding a memory flag must not alter."""
    return {
        "pack": {key: pack.get(key) for key in ["pack_id", "start_scene", "roles", "solo_role", "max_turns",
                                                "initial_resources", "hint_score_penalty", "sources", "fiction_notice"]},
        "scenes": {key: {
            "kind": scene["kind"], "ending_id": scene.get("ending_id"), "source_ids": scene.get("source_ids"),
            "actions": [{field: copy.deepcopy(action.get(field)) for field in ["id", "cost", "next", "score", "add_clues", "set_flags"]}
                        | {"outcomes": [{field: outcome.get(field) for field in ["when", "next", "score", "add_clues"]}
                                        for outcome in action.get("outcomes", [])]}
                        for action in scene["actions"]],
        } for key, scene in pack["scenes"].items()},
    }


@pytest.mark.parametrize("slug", SLUGS)
def test_story_revision_keeps_rules_aliases_sources_and_existing_flags(slug):
    current, old = load(slug), load(slug, legacy=True)
    assert current["version"] == ("0.8.0" if slug == "animal-footprint-garden" else "0.7.0")
    assert validate_pack(current) == []
    assert rules_contract(current) == rules_contract(old)
    for scene_id, scene in old["scenes"].items():
        for old_action, action in zip(scene["actions"], current["scenes"][scene_id]["actions"], strict=True):
            assert action.get("set_flags", {}) == old_action.get("set_flags", {})
            old_words = {old_action["label"], *old_action["aliases"]}
            new_words = {action["label"], *action["aliases"]}
            if slug == "animal-footprint-garden" and scene_id == "finish_watch" and action["id"] == "WAIT_A_LITTLE":
                assert old_words - new_words == RETIRED_ANIMAL_WAIT_WORDS
            else:
                assert old_words <= new_words
            for outcome in action.get("outcomes", [action]):
                assert "{" not in outcome.get("feedback", ""), "Action feedback is literal, not template-rendered"


@pytest.mark.parametrize("route,recipient,other", [
    ("MOON_BRIDGE", "달집의 작은 곰", "바람집의 토끼"),
    ("WIND_GARDEN", "바람집의 토끼", "달집의 작은 곰"),
])
def test_letter_keeps_the_found_route_recipient_and_exact_chosen_greeting(route, recipient, other):
    pack = load("starlight-post-office")
    for greeting, delivery in itertools.product(["HELLO", "TAKE_YOUR_TIME"], ["QUIET_MAILBOX", "READ_TOGETHER"]):
        state = walk(pack, [route, "LOOK_STAMP", "WALK_TOGETHER", "STAR_LANTERN", greeting, delivery])
        text = present(state, pack)["prompt"]
        chosen, unchosen = (("만나서 반가워", "천천히 열어도 괜찮아") if greeting == "HELLO"
                            else ("천천히 열어도 괜찮아", "만나서 반가워"))
        assert recipient in text and other not in text
        assert ("달다리" if route == "MOON_BRIDGE" else "바람정원") in text
        assert chosen in text and unchosen not in text
        assert ("편지함" in text) == (delivery == "QUIET_MAILBOX")


def test_letter_inspection_support_and_tool_choices_change_the_next_task():
    pack = load("starlight-post-office")
    for inspection, support in itertools.product(["LOOK_STAMP", "OPEN_NOTE"], ["WALK_TOGETHER", "CHECK_PATH"]):
        state = walk(pack, ["MOON_BRIDGE", inspection, support])
        text = present(state, pack)["prompt"]
        assert ("봉투의 그림" in text) == (inspection == "LOOK_STAMP")
        assert ("쪽지의 주소" in text) == (inspection == "OPEN_NOTE")
        assert ("나란히 걸어" in text) == (support == "WALK_TOGETHER")
        assert ("살펴둔 길" in text) == (support == "CHECK_PATH")
        for tool in ["STAR_LANTERN", "WIND_CHIME"]:
            after = apply_action(state, pack, state["expected_player_id"], tool)
            next_prompt = present(after, pack)["prompt"]
            assert ("별등을 끄고" in next_prompt) == (tool == "STAR_LANTERN")
            assert ("바람종이 잦아들고" in next_prompt) == (tool == "WIND_CHIME")
            assert after["resources"]["tool_tokens"] == 0


@pytest.mark.parametrize("place,observation,record,unchosen", [
    ("POND", "MOVEMENT", "오리 뒤에 남은 잔물결", "오리가 멈춘 돌 옆"),
    ("POND", "REST", "오리가 멈춘 돌 옆", "오리 뒤에 남은 잔물결"),
    ("FLOWER_GARDEN", "MOVEMENT", "나비가 하얀 꽃에서 노란 꽃으로 날아간 길", "나비가 앉은 노란 꽃"),
    ("FLOWER_GARDEN", "REST", "나비가 앉은 노란 꽃", "나비가 하얀 꽃에서 노란 꽃으로 날아간 길"),
])
def test_observation_records_only_the_selected_view_in_later_scenes_and_both_endings(place, observation, record, unchosen):
    pack = load("animal-footprint-garden")
    before_tool = walk(pack, [place, observation])
    assert record in present(before_tool, pack)["prompt"]
    assert unchosen not in present(before_tool, pack)["prompt"]
    for finish in ["WAIT_A_LITTLE", "SHARE_NOTE"]:
        state = walk(pack, [place, observation, "PHOTO_ZOOM", "SAY_ONE", "WEBBED_FEET", finish])
        text = present(state, pack)["prompt"]
        assert record in text and unchosen not in text
        assert ("오리" in text) == (place == "POND")
        assert ("나비" in text) == (place == "FLOWER_GARDEN")
        if finish == "WAIT_A_LITTLE":
            assert "새 움직임이 없어" in text
            assert "모르는 건 빈칸으로" in text
            assert state["feedback"] == "", "The ending delivers the observation result once."


@pytest.mark.parametrize("count", [1, 2])
def test_prepared_material_and_story_recording_method_remain_visible_without_fake_live_input(count):
    pack = load("animal-footprint-garden")
    for (place, observation), tool in itertools.product(OBSERVATIONS, ["PHOTO_ZOOM", "GUIDE_BOOK"]):
        record, object_text, scene_text = OBSERVATIONS[place, observation]
        material = walk(pack, [place, observation, tool], count)
        assert material["flags"]["record_note"] == record
        text = present(material, pack)["prompt"]
        assert f"{object_text} 오누가 문장으로 남길까, 이어서 그릴까?" in text
        assert "몸 설명" not in text + present(material, pack)["hint"]
        # The tool's existing factual explanation is still heard immediately
        # before this choice; the new question need not repeat the tool name.
        assert ("사진" in material["feedback"]) == (tool == "PHOTO_ZOOM")
        assert ("해설 수첩" in material["feedback"]) == (tool == "GUIDE_BOOK")
        if place == "FLOWER_GARDEN":
            assert ("가려질 수도" if tool == "PHOTO_ZOOM" else "모두 보이지 않을 수") in material["feedback"]
        actor = next(p for p in material["players"] if p["player_id"] == material["expected_player_id"])
        prefix = f"{actor['nickname']}, " if count == 1 else f"{actor['nickname']} {actor['role']} 차례야. "
        assert text.startswith(prefix)
        # Manually chosen short replies, not expectations generated from the
        # pack aliases: the spoken question still leads to supported answers.
        for reply, action in [("문장", "SAY_ONE"), ("문장으로 남길래", "SAY_ONE"),
                              ("그림", "DRAW_TOGETHER"), ("이어서 그릴래", "DRAW_TOGETHER")]:
            spoken = reply if count == 1 else f"{actor['nickname']}, {reply}"
            candidate = interpret(spoken, material, pack)
            assert candidate["action_id"] == action and candidate["intent"] == "ACTION"
            assert candidate["declared_player_id"] == material["expected_player_id"]
            assert not candidate["needs_clarification"]
        for style, confirmation in [("SAY_ONE", "문장으로 남겼어."), ("DRAW_TOGETHER", "그림을 이어 그렸어.")]:
            recorded = apply_action(material, pack, material["expected_player_id"], style)
            view = present(recorded, pack)
            assert view["feedback"] == confirmation
            assert "몸" not in view["feedback"]
            assert f"{scene_text} 몸 설명도 붙이자. 물갈퀴일까, 여섯 다리일까?" in view["prompt"]
            assert recorded["expected_player_id"] == "p1" and recorded["turn_index"] == 4
            assert view["prompt"].startswith("별, " if count == 1 else "별 모습 탐정 차례야. ")
            assert recorded["history"][-1]["player_id"] == material["expected_player_id"]
            for other_record, _, other_scene in OBSERVATIONS.values():
                if other_record != record:
                    assert other_scene not in view["prompt"]
            for forbidden in ["사진을 찍었", "네 그림", "네가 그린", "네 목소리를", "촬영했"]:
                assert forbidden not in view["feedback"] + material["feedback"] + view["prompt"]


def test_observation_context_revision_only_changes_two_flat_labels_and_the_named_prose():
    current = copy.deepcopy(load("animal-footprint-garden"))
    previous = load_pack(ROOT / "content/legacy/0.7.0/animal-footprint-garden/episode.json")
    assert set(current["template_labels"]) - set(previous["template_labels"]) == {"observation_object", "record_scene"}
    for name, index in [("observation_object", 1), ("record_scene", 2)]:
        label = current["template_labels"].pop(name)
        assert label["flag"] == "record_note"
        assert label["values"] == {row[0]: row[index] for row in OBSERVATIONS.values()}
        assert all("{" not in text and "}" not in text for text in [label["default"], *label["values"].values()])
    for scene, field in [("compare_notes", "prompt"), ("compare_notes", "hint"), ("match_feature", "prompt")]:
        current["scenes"][scene][field] = previous["scenes"][scene][field]
    for action, old in zip(current["scenes"]["compare_notes"]["actions"], previous["scenes"]["compare_notes"]["actions"], strict=True):
        action["feedback"] = old["feedback"]
    current["version"] = previous["version"]
    # Includes literal aliases, labels, facts, all outcome conditions and effects,
    # turn roles and existing ending/memory text, not just a selected rule subset.
    assert current == previous


@pytest.mark.parametrize("slug", SLUGS)
def test_every_revision_path_stays_short_complete_and_help_does_not_grade_preferences(slug):
    pack, old = load(slug), load(slug, legacy=True)
    recent_pack = load_pack(ROOT / "content/legacy/0.7.0/animal-footprint-garden/episode.json") if slug == "animal-footprint-garden" else None
    for count in [1, 2]:
        paths = list(complete_paths(pack, count))
        assert len(paths) == 64
        for state, actions in paths:
            previous = walk(old, actions, count)
            helped = walk(pack, actions, count, help_each_turn=True)
            assert state["turn_index"] == 6
            assert state["ending"] == previous["ending"] == helped["ending"]
            assert state["score_by_player"] == previous["score_by_player"] == helped["score_by_player"]
            assert state["resources"] == previous["resources"]
            assert state["clues"] == previous["clues"]
            assert state["history"] == previous["history"]
            assert len(present(state, pack)["prompt"].split(". ")) == 3
            current = new_game(PLAYERS[:count], pack)
            recent = new_game(PLAYERS[:count], recent_pack) if recent_pack else None
            for choice in actions:
                prompt = present(current, pack)["prompt"]
                assert prompt.count("?") == 1
                assert len(prompt) <= 160
                assert "{" not in prompt
                if recent_pack:
                    assert present(current, pack)["actions"] == present(recent, recent_pack)["actions"]
                current = apply_action(current, pack, current["expected_player_id"], choice)
                if recent_pack:
                    recent = apply_action(recent, recent_pack, recent["expected_player_id"], choice)
                    effects = copy.deepcopy(current)
                    effects["pack_version"] = recent["pack_version"]
                    if current["scene_id"] == "match_feature":
                        effects["feedback"] = recent["feedback"]
                    assert effects == recent
