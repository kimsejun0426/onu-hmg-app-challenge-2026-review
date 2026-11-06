"""Chosen story actions are not evidence of a child's real-world performance.

These checks cover authored meaning, historical input compatibility and game
effects. They do not measure comprehension, engagement or educational outcomes.
"""

from pathlib import Path

import pytest

from carplay.catalog import ContentCatalog
from carplay.game import apply_action, interpret, load_pack, new_game, present
from content_release_expectations import NEW_PACK_ID, RETIRED_ANIMAL_WAIT_WORDS


ROOT = Path(__file__).resolve().parents[1]
CATALOG = ContentCatalog()
PLAYERS = [{"player_id": "p1", "nickname": "민준"}, {"player_id": "p2", "nickname": "서윤"}]


def pack(slug):
    return load_pack(ROOT / f"content/ko-KR/{slug}/episode.json")


def walk(story, actions):
    state = new_game(PLAYERS, story)
    for action in actions:
        state = apply_action(state, story, state["expected_player_id"], action)
    return state


@pytest.mark.parametrize("entry", [e for e in CATALOG.legacy_entries if e["version"] == "0.4.0"],
                         ids=lambda item: item["pack_id"])
@pytest.mark.parametrize("count", [1, 2])
def test_new_narration_preserves_every_published_effect_and_spoken_choice(entry, count):
    current = CATALOG.current(entry["pack_id"])
    previous = CATALOG.load(entry["pack_id"], entry["version"])
    assert current["sources"] == previous["sources"]
    assert current["fiction_notice"] == previous["fiction_notice"]
    pending = [(new_game(PLAYERS[:count], current), new_game(PLAYERS[:count], previous))]
    endings = 0
    expected_turns = 4 if entry["pack_id"] == NEW_PACK_ID else 6
    while pending:
        state, old_state = pending.pop()
        if entry["pack_id"] == "space-return-signal" and count == 2:
            assert [p["role"] for p in state["players"]] == ["탐사 친구", "탐사 친구"]
            assert [p["role"] for p in old_state["players"]] == ["관측 대장", "귀환 대장"]
            assert [{k: v for k, v in p.items() if k != "role"} for p in state["players"]] == [
                {k: v for k, v in p.items() if k != "role"} for p in old_state["players"]]
        else:
            assert state["players"] == old_state["players"]
        for key in ("expected_player_id", "scene_id", "turn_index", "resources",
                    "score_by_player", "clues", "flags", "hinted_scene_ids", "history", "finished", "ending"):
            assert state[key] == old_state[key], (entry["pack_id"], state["scene_id"], key)
        if state["finished"]:
            assert state["turn_index"] == old_state["turn_index"] == expected_turns
            endings += 1
            continue
        choices = present(state, current)["actions"]
        old_choices = present(old_state, previous)["actions"]
        assert [a["id"] for a in choices] == [a["id"] for a in old_choices]
        name = next(p["nickname"] for p in state["players"] if p["player_id"] == state["expected_player_id"])
        for action, old in zip(choices, old_choices, strict=True):
            old_words = {old["label"], *old["aliases"]}
            new_words = {action["label"], *action["aliases"]}
            retired = (RETIRED_ANIMAL_WAIT_WORDS if entry["pack_id"] == "animal-footprint-garden"
                       and state["scene_id"] == "finish_watch" and action["id"] == "WAIT_A_LITTLE" else set())
            assert old_words - new_words == retired
            for spoken in retired:
                old_candidate = interpret(f"{name}, {spoken}" if count == 2 else spoken, old_state, previous)
                assert old_candidate["action_id"] == action["id"] and not old_candidate["needs_clarification"]
                current_candidate = interpret(f"{name}, {spoken}" if count == 2 else spoken, state, current)
                assert current_candidate["action_id"] is None
            for spoken in (old_words | new_words) - retired:
                candidate = interpret(f"{name}, {spoken}" if count == 2 else spoken, state, current)
                assert candidate["intent"] == "ACTION", (entry["pack_id"], state["scene_id"], spoken, candidate)
                assert candidate["action_id"] == action["id"]
                assert candidate["needs_clarification"] is False
            pending.append((
                apply_action(state, current, state["expected_player_id"], action["id"]),
                apply_action(old_state, previous, old_state["expected_player_id"], old["id"]),
            ))
    assert endings == 2 ** expected_turns


@pytest.mark.parametrize("version,method,record", [
    ("0.8.0", "SAY_ONE", "문장으로 남겼어."),
    ("0.8.0", "DRAW_TOGETHER", "그림을 이어 그렸어."),
    ("0.9.0", "SAY_ONE", "오누가 옆에 문장을 더 남겼어."),
    ("0.9.0", "DRAW_TOGETHER", "오누가 옆에 그림을 이어 그렸어."),
])
@pytest.mark.parametrize("place,feature", [("POND", "물갈퀴"), ("FLOWER_GARDEN", "여섯 다리")])
def test_animal_recording_method_names_onu_before_any_help_is_requested(version, method, record, place, feature):
    story = CATALOG.load("animal-footprint-garden", version)
    before = walk(story, [place, "REST", "PHOTO_ZOOM"])
    prompt = present(before, story)["prompt"]
    assert "오누가 문장으로 남길까, 이어서 그릴까" in prompt
    assert "한 가지씩 말" not in prompt
    after = apply_action(before, story, before["expected_player_id"], method)
    assert after["feedback"] == record
    assert "몸 설명" not in after["feedback"]
    assert "몸 설명도 붙이자" in present(after, story)["prompt"]
    # Either answer still proceeds once; correction belongs to Onu and does not
    # fabricate another child attempt or overwrite whether the choice matched.
    for answer, matches in [("WEBBED_FEET", place == "POND"), ("SIX_LEGS", place == "FLOWER_GARDEN")]:
        annotated = apply_action(after, story, after["expected_player_id"], answer)
        assert annotated["flags"]["feature_matched"] is matches
        assert "오누가" in annotated["feedback"] and feature in annotated["feedback"]
        assert annotated["turn_index"] == 5
        assert annotated["history"][-1]["action_id"] == answer


@pytest.mark.parametrize("route,chosen,corrected", [
    ("CORAL_ARCH", "TRIANGLE", "동그라미"),
    ("SHELL_TUNNEL", "CIRCLE", "세모"),
])
def test_ocean_auto_repair_belongs_to_onu_and_keeps_the_childs_actual_choice(route, chosen, corrected):
    story = pack("undersea-lighthouse")
    observed = walk(story, [route, "READ_SIGN", "REPEAT_SIGNS"])
    assert "오누가 표지를 다시 살폈어" in observed["feedback"]
    assert "소리 내어 되짚었어" not in observed["feedback"]
    ready = apply_action(observed, story, observed["expected_player_id"], "ECHO_SHELL")
    result = apply_action(ready, story, ready["expected_player_id"], chosen)
    assert f"오누가 약속대로 {corrected}로 바꾸자" in result["feedback"]
    assert result["flags"]["pattern_recalled"] is False
    assert result["history"][-1]["action_id"] == chosen
    assert result["scene_id"] == "choose_destination" and result["turn_index"] == 5


def test_letter_reading_is_performed_by_momo_not_a_claim_that_the_child_read_aloud():
    story = pack("starlight-post-office")
    before = walk(story, ["MOON_BRIDGE", "LOOK_STAMP", "CHECK_PATH", "WIND_CHIME", "HELLO"])
    assert "모모가 읽어 줄까" in present(before, story)["prompt"]
    final = apply_action(before, story, before["expected_player_id"], "READ_TOGETHER")
    assert final["feedback"] == "모모가 친구 곁에서 별편지를 펼쳤어."
    ending = present(final, story)["prompt"]
    assert "달집의 작은 곰 곁에서 모모가 편지를 읽어 줬어" in ending and "만나서 반가워" in ending
    assert "한 문장씩 함께 읽었어" not in final["feedback"]


@pytest.mark.parametrize("method", ["READ_CHECKLIST", "COUNT_STEPS"])
@pytest.mark.parametrize("version", ["0.8.0", "0.9.0"])
def test_space_checking_and_sharing_name_the_actual_story_actors(method, version):
    story = CATALOG.load("space-return-signal", version)
    before = walk(story, ["MARS", "PANORAMA", "NOTE"])
    prompt = present(before, story)["prompt"]
    assert "오누가 확인표로 읽어 줄까, 하나둘 세어 줄까" in prompt
    assert "문 닫기" not in prompt and "출발 신호" not in prompt
    checked = apply_action(before, story, before["expected_player_id"], method)
    assert "오누가" in checked["feedback"] and "문 닫기" in checked["feedback"]
    if version == "0.8.0":
        assert "오누가" in present(checked, story)["prompt"]
    else:
        # The actor remains in delivered feedback; the next question no longer
        # repeats the preparation or claims that the child performed it.
        assert present(checked, story)["prompt"] == (
            "민준 탐사 친구 차례야. 문이 아직 열려 있네. 문 닫기부터 할까, 출발 신호부터 보낼까?"
        )
    assert "순서를 맞췄어" not in present(checked, story)["prompt"]
    shared = walk(story, ["MARS", "PANORAMA", "NOTE", method, "SEND_SIGNAL", "TELL_STORY"])
    assert shared["flags"]["order_recalled"] is False
    assert shared["feedback"].startswith("토리가")
    assert present(shared, story)["prompt"].startswith(
        "토리가 친구들에게 탐사 이야기를 들려줬어. 노트에는 화성의 붉은 먼지를 한눈에 본 이야기가 적혔어.")


@pytest.mark.parametrize("delivery,near,inside,chosen", [
    ("PLANTS", "별 상자", "달 상자", "STAR_FIRST"),
    ("BOOKS", "달 상자", "별 상자", "MOON_FIRST"),
])
def test_vehicle_uses_one_spatial_question_and_names_duri_when_rearranging(delivery, near, inside, chosen):
    story = pack("little-car-workshop")
    before = walk(story, [delivery, "READ_ORDER", "OPEN_CART", "CHECK_MIRROR"])
    prompt = present(before, story)["prompt"]
    assert f"두리가 {near}부터 내릴 거야" in prompt
    assert "문 가까이에 둘게" not in prompt
    assert "안쪽에는 별 상자를 둘까, 달 상자를 둘까" in prompt and prompt.count("?") == 1
    assert "먼저 실을까" not in prompt
    loaded = apply_action(before, story, before["expected_player_id"], chosen)
    assert f"두리가 {near}는 문 가까이, {inside}는 안쪽에 다시 실었어" in loaded["feedback"]
    assert loaded["flags"]["load_order_recalled"] is False
    assert loaded["history"][-1]["action_id"] == chosen
    assert "오누가 두리에게 차례표를 읽어 줄까" in present(loaded, story)["prompt"]
    for handover in ("READ_DELIVERY_ORDER", "PASS_CHECKLIST"):
        final = apply_action(loaded, story, loaded["expected_player_id"], handover)
        assert final["feedback"].startswith("오누가")
        assert final["turn_index"] == 6
        assert "배달을 마쳤어" not in present(final, story)["prompt"]
