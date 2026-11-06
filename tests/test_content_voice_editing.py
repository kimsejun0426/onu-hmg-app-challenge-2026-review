"""Narration delivery and bounded story choices; synthetic text, not child listening results."""
import copy
import hashlib
import json
from pathlib import Path

import pytest

from carplay.catalog import ContentCatalog
from carplay.game import apply_action, interpret, new_game, present
from content_release_expectations import EDITED_012_IDS, RETIRED_ANIMAL_WAIT_WORDS

ROOT = Path(__file__).resolve().parents[1]
CATALOG = ContentCatalog()
FROZEN = json.loads((ROOT / "tests/fixtures/published_onu_011_content.json").read_text(encoding="utf-8"))
PLAYERS = [{"player_id": "p1", "nickname": "민준"}, {"player_id": "p2", "nickname": "서윤"}]


def walk(pack, path, count):
    state = new_game(PLAYERS[:count], pack)
    for action in path:
        state = apply_action(state, pack, state["expected_player_id"], action)
    return state


def game_effects(state):
    return {key: value for key, value in state.items() if key not in {"pack_version", "feedback"}}


def spoken(state, text, count):
    name = next(p["nickname"] for p in state["players"] if p["player_id"] == state["expected_player_id"])
    return f"{name}, {text}" if count == 2 else text


@pytest.mark.parametrize("frozen", FROZEN, ids=lambda item: item["pack_id"])
def test_011_apk_original_and_metadata_are_preserved_exactly(frozen):
    assert {item["pack_id"] for item in FROZEN} == EDITED_012_IDS
    assert hashlib.sha256((ROOT / frozen["content_asset"]).read_bytes()).hexdigest() == frozen["sha256"]
    entry = next(e for e in CATALOG.legacy_entries if
                 (e["pack_id"], e["version"]) == (frozen["pack_id"], "0.6.0"))
    assert {key: value for key, value in entry.items() if key != "content_asset"} == frozen["catalog_metadata"]
    assert entry["content_asset"] == frozen["content_asset"]


@pytest.mark.parametrize("pack_id", sorted(EDITED_012_IDS))
@pytest.mark.parametrize("count", [1, 2])
def test_published_012_revision_keeps_011_game_effects_and_endings(pack_id, count):
    # This is the 0.12 narration contract. Later editions have their own semantic
    # tests, while the published 0.7 scripts remain frozen and executable here.
    current, previous = CATALOG.load(pack_id, "0.7.0"), CATALOG.load(pack_id, "0.6.0")
    pending = [(new_game(PLAYERS[:count], current), new_game(PLAYERS[:count], previous))]
    complete = 0
    while pending:
        state, old = pending.pop()
        assert game_effects(state) == game_effects(old)
        view = present(state, current)
        if state["finished"]:
            complete += 1
            assert view["prompt"] == present(old, previous)["prompt"], "Ending recall is unchanged."
            continue
        assert view["prompt"].count("?") == 1
        actions = [a["id"] for a in view["actions"]]
        assert actions == [a["id"] for a in present(old, previous)["actions"]]
        for action in actions:
            pending.append((apply_action(state, current, state["expected_player_id"], action),
                            apply_action(old, previous, old["expected_player_id"], action)))
    assert complete == 64


@pytest.mark.parametrize("count", [1, 2])
@pytest.mark.parametrize("place,feature", [("POND", "WEBBED_FEET"), ("FLOWER_GARDEN", "SIX_LEGS")])
def test_watch_requests_do_not_capture_wait_refusal_uncertainty_or_someone_elses_choice(count, place, feature):
    pack = CATALOG.current("animal-footprint-garden")
    old_pack = CATALOG.load(pack["pack_id"], "0.6.0")
    path = [place, "MOVEMENT", "PHOTO_ZOOM", "SAY_ONE", feature]
    state, old_state = walk(pack, path, count), walk(old_pack, path, count)
    before = copy.deepcopy(state)
    assert "오누가 더 지켜볼까" in present(state, pack)["prompt"]
    for text in ("더 지켜보기", "지켜보기", "더 지켜볼래", "지켜봐 줘", "지켜봐줘",
                 "더 지켜봐 줘", "더 지켜봐줘", "잠깐 더 볼래"):
        candidate = interpret(spoken(state, text, count), state, pack)
        assert candidate["action_id"] == "WAIT_A_LITTLE" and not candidate["needs_clarification"]
    for text in RETIRED_ANIMAL_WAIT_WORDS:
        assert interpret(spoken(old_state, text, count), old_state, old_pack)["action_id"] == "WAIT_A_LITTLE"
        assert interpret(spoken(state, text, count), state, pack)["action_id"] is None
    for text, intent in (("기다려 줘", "WAIT"), ("생각 중이야", "WAIT"), ("하기 싫어", "PAUSE"),
                         ("다시", "REPEAT"), ("몰라", "HINT")):
        for story, current in ((pack, state), (old_pack, old_state)):
            candidate = interpret(spoken(current, text, count), current, story)
            assert candidate["intent"] == intent and candidate["action_id"] is None
    for text in ("더", "", "지켜봐 줘 하지 마", "더 지켜보기 말고", "더 지켜봐 줘 다시",
                 "더 지켜봐 줘 그리고 기록 나누기", "어제 더 지켜봐 줘라고 했어"):
        candidate = interpret(spoken(state, text, count), state, pack)
        assert candidate["needs_clarification"] and candidate["action_id"] is None
    corrected = interpret(spoken(state, "더 지켜보기 말고 기록 나누기", count), state, pack)
    assert corrected["action_id"] == "SHARE_NOTE" and not corrected["needs_clarification"]
    if count == 2:
        assert interpret("더 지켜봐 줘", state, pack)["reason"] == "missing_player"
        assert interpret("민준, 더 지켜봐 줘", state, pack)["reason"] == "wrong_player"
        assert interpret("서윤, 민준, 더 지켜봐 줘", state, pack)["reason"] == "multiple_players"
    assert state == before
    final = apply_action(state, pack, state["expected_player_id"], "WAIT_A_LITTLE")
    assert final["turn_index"] == 6 and final["feedback"] == ""
    assert "새 움직임이 없어" in present(final, pack)["prompt"]
    assert "모르는 건 빈칸" in present(final, pack)["prompt"]


@pytest.mark.parametrize("tool", ["MAGNIFIER", "LISTENING_DEVICE"])
@pytest.mark.parametrize("count", [1, 2])
def test_dinosaur_clue_is_read_once_and_remains_in_the_standalone_question(tool, count):
    pack = CATALOG.current("dinosaur-lab-missing-egg")
    state = walk(pack, ["RIVER", "FOLLOW_TRACKS", "LEAVES", tool], count)
    prompt = present(state, pack)["prompt"]
    assert (state["feedback"] + prompt).count("뿔이 세 개") == 1
    assert "뿔이 세 개" in prompt
    assert "트리케라톱스" in prompt and "스테고사우루스" in prompt


@pytest.mark.parametrize("route,beacon,pattern", [
    ("CORAL_ARCH", "동쪽 등대", "동그라미, 세모, 동그라미"),
    ("SHELL_TUNNEL", "서쪽 등대", "세모, 동그라미, 세모"),
])
@pytest.mark.parametrize("observation", ["READ_SIGN", "HEAR_BUOY"])
@pytest.mark.parametrize("tool", ["LIGHT_PRISM", "ECHO_SHELL"])
@pytest.mark.parametrize("count", [1, 2])
def test_ocean_directions_survive_and_each_full_pattern_is_spoken_once(route, beacon, pattern, observation, tool, count):
    pack = CATALOG.current("undersea-lighthouse")
    observed = walk(pack, [route, observation], count)
    prompt = present(observed, pack)["prompt"]
    assert beacon in observed["feedback"]
    assert (observed["feedback"] + prompt).count(pattern) == 1 and pattern in prompt
    equipped = walk(pack, [route, observation, "KEEP_MAP", tool], count)
    prompt = present(equipped, pack)["prompt"]
    assert (equipped["feedback"] + prompt).count(pattern) == 1 and pattern in prompt
    assert "동그라미를 넣을까, 세모를 넣을까?" in prompt


@pytest.mark.parametrize("route,greeting", [
    ("MOON_BRIDGE", "HELLO"), ("MOON_BRIDGE", "TAKE_YOUR_TIME"),
    ("WIND_GARDEN", "HELLO"), ("WIND_GARDEN", "TAKE_YOUR_TIME"),
])
def test_letter_opening_precedes_one_reading_and_preserves_the_chosen_greeting(route, greeting):
    pack = CATALOG.current("starlight-post-office")
    state = walk(pack, [route, "LOOK_STAMP", "WALK_TOGETHER", "STAR_LANTERN", greeting, "READ_TOGETHER"], 2)
    ending = present(state, pack)["prompt"]
    assert state["feedback"] == "모모가 친구 곁에서 별편지를 펼쳤어."
    assert (state["feedback"] + ending).count("편지를 읽어 줬어") == 1
    assert ("만나서 반가워" if greeting == "HELLO" else "천천히 열어도 괜찮아") in ending


@pytest.mark.parametrize("mission", ["MOON", "MARS"])
@pytest.mark.parametrize("record", ["PHOTO", "NOTE"])
@pytest.mark.parametrize("sharing", ["MAKE_GALLERY", "TELL_STORY"])
def test_space_final_feedback_prepares_without_repeating_the_completed_sharing(mission, record, sharing):
    pack = CATALOG.current("space-return-signal")
    state = walk(pack, [mission, "PANORAMA", record, "COUNT_STEPS", "CLOSE_DOOR", sharing], 2)
    ending = present(state, pack)["prompt"]
    assert "전시를 열었어" not in state["feedback"] and "들려줬어" not in state["feedback"]
    assert ("전시를 열었어" if sharing == "MAKE_GALLERY" else "들려줬어") in ending
    assert ("사진에는" if record == "PHOTO" else "노트에는") in ending
    assert ("달" if mission == "MOON" else "화성") in ending
