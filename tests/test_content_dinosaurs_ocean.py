"""Authored choice recall and preserved rules for the current dinosaur/ocean stories.

These assertions check what a listener can recover from the spoken scene. They
do not establish enjoyment, age suitability or performance with real children.
"""

from pathlib import Path

import pytest

from carplay.game import apply_action, load_pack, new_game, present
from carplay.catalog import ContentCatalog


ROOT = Path(__file__).resolve().parents[1]
PLAYERS = [{"player_id": "p1", "nickname": "하루"}, {"player_id": "p2", "nickname": "보리"}]


def load(slug, legacy=False):
    if legacy:
        return load_pack(ROOT / "content/legacy/0.3.0" / slug / "episode.json")
    # The 0.12 prose edit remains independently testable after later editions.
    pack_id = "dinosaur-lab-missing-egg" if slug == "dinosaurs" else slug
    return ContentCatalog().load(pack_id, "0.7.0")


def walk(pack, actions, count=1):
    state = new_game(PLAYERS[:count], pack)
    for action_id in actions:
        state = apply_action(state, pack, state["expected_player_id"], action_id)
    return state


@pytest.mark.parametrize("count", [1, 2])
@pytest.mark.parametrize("route,destination,destination_id,other_id", [
    ("RIVER", "공작실", "WORKSHOP", "GREENHOUSE"),
    ("FOREST", "온실", "GREENHOUSE", "WORKSHOP"),
])
@pytest.mark.parametrize("evidence,spoken_evidence", [
    ("FOLLOW_TRACKS", "바퀴 자국"), ("READ_LABEL", "상자 표지"),
])
@pytest.mark.parametrize("tool,spoken_tool,unused_tool", [
    ("MAGNIFIER", "돋보기", "듣기 장치"),
    ("LISTENING_DEVICE", "듣기 장치", "돋보기"),
])
def test_dinosaur_questions_and_both_endings_recall_the_actual_choices(
    count, route, destination, destination_id, other_id, evidence, spoken_evidence,
    tool, spoken_tool, unused_tool,
):
    pack = load("dinosaurs")
    prefix = [route, evidence, "LEAVES", tool]
    tool_scene = walk(pack, prefix, count)
    identity = present(tool_scene, pack)
    assert spoken_tool in identity["prompt"]
    assert unused_tool not in identity["prompt"]
    assert "뿔이 세 개" in identity["prompt"]
    assert "뿔이 세 개" in identity["hint"]

    deduction_state = walk(pack, [*prefix, "TRICERATOPS"], count)
    deduction = present(deduction_state, pack)
    # The destination is a cue before the two options, not a memory-only test.
    assert f"{spoken_evidence}에서 찾은 건 {destination} 표시" in deduction["prompt"]
    # The selected clue and its destination survive copy edits; do not pin the
    # old ungrammatical particle ("상자 표지은") as expected behavior.
    assert spoken_evidence in deduction["hint"] and destination in deduction["hint"]
    assert ("온실" if destination == "공작실" else "공작실") not in deduction["hint"]
    assert "표지은" not in deduction["hint"]
    for selected, ending in [(destination_id, "FOUND_TOGETHER"), (other_id, "TEAM_ASSIST")]:
        final = apply_action(deduction_state, pack, deduction_state["expected_player_id"], selected)
        text = present(final, pack)["prompt"]
        assert final["ending"] == ending
        assert final["turn_index"] == 6
        assert spoken_tool in text and unused_tool not in text
        assert "전시대" in text and "오누" in text
        if ending == "TEAM_ASSIST":
            assert destination in text


@pytest.mark.parametrize("route,beacon,pattern", [
    ("CORAL_ARCH", "동쪽 등대", "동그라미, 세모, 동그라미"),
    ("SHELL_TUNNEL", "서쪽 등대", "세모, 동그라미, 세모"),
])
@pytest.mark.parametrize("observation,observation_text", [
    ("READ_SIGN", "표지에서 본 모양"), ("HEAR_BUOY", "부표에게 들은 모양"),
])
@pytest.mark.parametrize("teamwork,teamwork_text", [
    ("KEEP_MAP", "펼친 지도를 따라와서"), ("REPEAT_SIGNS", "표지의 약속을 되짚으며"),
])
def test_ocean_observation_and_companionship_change_the_following_scene(
    route, beacon, pattern, observation, observation_text, teamwork, teamwork_text,
):
    pack = load("undersea-lighthouse")
    observed = walk(pack, [route, observation])
    recall = present(observed, pack)
    assert observation_text in recall["prompt"]
    assert pattern in recall["prompt"]
    assert beacon in recall["hint"]
    together = apply_action(observed, pack, observed["expected_player_id"], teamwork)
    next_prompt = present(together, pack)["prompt"]
    assert teamwork_text in next_prompt
    assert "신호판 앞에 왔어" in next_prompt
    assert "빛거울" in next_prompt and "메아리 조개" in next_prompt


@pytest.mark.parametrize("count", [1, 2])
@pytest.mark.parametrize("route,beacon,pattern,shape,other_shape,destination,relay", [
    ("CORAL_ARCH", "동쪽 등대", "동그라미, 세모, 동그라미", "CIRCLE", "TRIANGLE", "EAST_LIGHT", "WEST_LIGHT"),
    ("SHELL_TUNNEL", "서쪽 등대", "세모, 동그라미, 세모", "TRIANGLE", "CIRCLE", "WEST_LIGHT", "EAST_LIGHT"),
])
@pytest.mark.parametrize("tool,spoken_tool,unused_tool", [
    ("LIGHT_PRISM", "빛거울", "메아리 조개"),
    ("ECHO_SHELL", "메아리 조개", "빛거울"),
])
def test_ocean_pattern_cues_and_both_endings_keep_tool_and_destination(
    count, route, beacon, pattern, shape, other_shape, destination, relay,
    tool, spoken_tool, unused_tool,
):
    pack = load("undersea-lighthouse")
    before_pattern = walk(pack, [route, "HEAR_BUOY", "KEEP_MAP", tool], count)
    scene = present(before_pattern, pack)
    assert pattern in scene["prompt"] and pattern in scene["hint"]
    assert spoken_tool in scene["prompt"] and unused_tool not in scene["prompt"]
    for selected_shape, recalled in [(shape, True), (other_shape, False)]:
        ready = apply_action(before_pattern, pack, before_pattern["expected_player_id"], selected_shape)
        assert ready["flags"]["pattern_recalled"] is recalled
        direction = present(ready, pack)
        assert f"기다리는 곳은 {beacon}" in direction["prompt"]
        assert beacon in direction["hint"]
        if not recalled:
            # Explain the observable change and recovery, rather than blame.
            assert "깜빡여" in ready["feedback"] and "바꾸자" in ready["feedback"]
        for selected, ending in [(destination, "LIGHT_CONNECTED"), (relay, "LIGHT_RELAY")]:
            final = apply_action(ready, pack, ready["expected_player_id"], selected)
            text = present(final, pack)["prompt"]
            assert final["ending"] == ending
            assert final["turn_index"] == 6
            assert spoken_tool in text and unused_tool not in text
            assert beacon in text and "도착" in text and "오누" in text and "인사" in text


@pytest.mark.parametrize("slug", ["dinosaurs", "undersea-lighthouse"])
@pytest.mark.parametrize("count", [1, 2])
def test_rewrite_preserves_all_paths_rules_sources_and_voice_action_names(slug, count):
    current, old = load(slug), load(slug, legacy=True)
    assert current["version"] == "0.7.0"
    assert current["sources"] == old["sources"]
    assert current["initial_resources"] == old["initial_resources"]
    assert current["hint_score_penalty"] == old["hint_score_penalty"] == 0
    assert current["scenes"].keys() == old["scenes"].keys()
    pending = [(new_game(PLAYERS[:count], current), new_game(PLAYERS[:count], old))]
    completed = 0
    while pending:
        new_state, old_state = pending.pop()
        for field in ["scene_id", "ending", "turn_index", "score_by_player", "resources", "clues", "history"]:
            assert new_state[field] == old_state[field]
        assert new_state["flags"] == old_state["flags"]
        rendered = present(new_state, current)
        old_actions = present(old_state, old)["actions"]
        assert [a["id"] for a in rendered["actions"]] == [a["id"] for a in old_actions]
        for action, old_action in zip(rendered["actions"], old_actions, strict=True):
            assert set([old_action["label"], *old_action["aliases"]]) <= set([action["label"], *action["aliases"]])
        for field in ["prompt", "hint", "feedback"]:
            assert "{" not in rendered[field] and "}" not in rendered[field]
        assert len(rendered["prompt"]) <= 160
        if new_state["finished"]:
            completed += 1
            assert new_state["turn_index"] == 6
        else:
            for choice in rendered["actions"]:
                pending.append((
                    apply_action(new_state, current, new_state["expected_player_id"], choice["id"]),
                    apply_action(old_state, old, old_state["expected_player_id"], choice["id"]),
                ))
    assert completed == 64
