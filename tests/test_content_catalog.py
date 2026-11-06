"""Current story invariants and exact historical story/choice regression coverage."""

import copy
import hashlib
from pathlib import Path

import pytest

from carplay.game import apply_action, apply_hint, interpret, load_pack, new_game, present
from tools.check_content import INTEREST_IDS, catalog_entries, check_pack, validate_interest_metadata
from content_release_expectations import CURRENT_VERSIONS, NEW_PACK_ID

ROOT = Path(__file__).resolve().parents[1]
PLAYERS = [{"player_id": "p1", "nickname": "민준"}, {"player_id": "p2", "nickname": "서윤"}]
NEW_PACKS = ["starlight-post-office", "undersea-lighthouse"]
INTEREST_PACKS = ["space-return-signal", "animal-footprint-garden", "little-car-workshop"]


def load(slug):
    # Detailed historical dialogue/rules below deliberately remain on their version.
    return load_pack(ROOT / f"content/legacy/0.1.0/{slug}/episode.json")


def walk(pack, actions, count=2, hints=False):
    state = new_game(PLAYERS[:count], pack)
    for action in actions:
        if hints:
            state = apply_hint(state, pack)
        state = apply_action(state, pack, state["expected_player_id"], action)
    return state


def completions(pack, count=2):
    pending = [new_game(PLAYERS[:count], pack)]
    while pending:
        state = pending.pop()
        if state["finished"]:
            yield state
        else:
            pending.extend(apply_action(state, pack, state["expected_player_id"], a["id"])
                           for a in present(state, pack)["actions"])


@pytest.mark.parametrize("entry", catalog_entries(include_legacy=True), ids=lambda e: f"{e['pack_id']}@{e['version']}")
def test_catalog_episode_finishes_every_path_with_both_player_counts(entry):
    report = check_pack(load_pack(ROOT / entry["content_asset"]), entry)
    assert report["status"] == "passed"
    for counts in report["player_counts"]:
        expected_turns = 4 if entry["pack_id"] == NEW_PACK_ID else 6
        assert entry["total_choices"] == expected_turns
        assert counts["complete_paths"] == 2 ** expected_turns
        assert counts["turn_counts"] == [expected_turns]
        assert len(counts["endings"]) == 2


def test_catalog_and_all_pack_bytes_are_bundled_exactly():
    asset_root = ROOT / "apps/vehicle/app/src/main/assets"
    assert (asset_root / "content/catalog.json").read_bytes() == (ROOT / "content/catalog.json").read_bytes()
    for entry in catalog_entries(include_legacy=True):
        assert (asset_root / entry["content_asset"]).read_bytes() == (ROOT / entry["content_asset"]).read_bytes()


def test_original_six_story_archives_remain_byte_exact():
    # Fixtures evolve with safety fixes; authored 0.1.0 rules and text do not.
    expected = {
        "dinosaurs": "ab9c8330c08dbbfbb849aad1d4542a29bd2cf3bfdfe4cb0a1dfd3acdbcacd977",
        "starlight-post-office": "26b26c625e5bc8a38020272906f048b21ed0832a04614165d99869248b4ecbea",
        "undersea-lighthouse": "98f3d8412beb4521164e1b7ec06896434ebb73fcc935babbf9bd0226bb8337d2",
        "space-return-signal": "7285597b58b530f5d07ddbbbc3e77374b481133c05fe2434ac088cfa17890dd0",
        "animal-footprint-garden": "fdda36508c927aad418a4f88975645be6b2e14d96a5c90bb150cd43b9e33ac57",
        "little-car-workshop": "969123ce420c7175c91e233dd4e53ee55bd1de7b5afec943b5601e0ce7dc238c",
    }
    for slug, digest in expected.items():
        assert hashlib.sha256((ROOT / f"content/legacy/0.1.0/{slug}/episode.json").read_bytes()).hexdigest() == digest


@pytest.mark.parametrize("entry", catalog_entries(), ids=lambda e: e["pack_id"])
def test_current_content_keeps_help_free_and_all_authored_choices_reachable(entry):
    pack = load_pack(ROOT / entry["content_asset"])
    assert pack["version"] == CURRENT_VERSIONS[entry["pack_id"]]
    assert pack.get("hint_score_penalty", 0) == 0
    assert pack["initial_resources"]["hint_tokens"] >= entry["total_choices"]
    for final in completions(pack):
        helped = walk(pack, [step["action_id"] for step in final["history"]], hints=True)
        assert helped["score_by_player"] == final["score_by_player"]
        assert helped["ending"] == final["ending"]
        assert helped["turn_index"] == final["turn_index"] == entry["total_choices"]


def test_post_office_never_grades_emotional_support_greeting_or_delivery_style():
    pack = load("starlight-post-office")
    final_states = list(completions(pack))
    assert len(final_states) == 64
    assert {s["ending"] for s in final_states} == {"QUIET_DELIVERY", "SHARED_READING"}
    assert {s["flags"]["teamwork"] for s in final_states} == {"WALK_TOGETHER", "CHECK_PATH"}
    assert {s["flags"]["greeting"] for s in final_states} == {"HELLO", "TAKE_YOUR_TIME"}
    assert all(s["score_by_player"] == {"p1": 3, "p2": 3} for s in final_states)
    for state in final_states:
        actions = [step["action_id"] for step in state["history"]]
        with_hints = walk(pack, actions, hints=True)
        assert with_hints["score_by_player"] == state["score_by_player"]
        assert with_hints["ending"] == state["ending"]
        assert with_hints["resources"]["hint_tokens"] == 0


@pytest.mark.parametrize("route,recipient,route_label", [
    ("MOON_BRIDGE", "달집의 작은 곰", "달다리"),
    ("WIND_GARDEN", "바람집의 토끼", "바람정원"),
])
def test_post_office_remembers_route_companionship_tool_and_personal_greeting(route, recipient, route_label):
    pack = load("starlight-post-office")
    state = walk(pack, [route, "OPEN_NOTE", "CHECK_PATH", "WIND_CHIME", "TAKE_YOUR_TIME", "READ_TOGETHER"])
    text = present(state, pack)["prompt"]
    for remembered in (recipient, route_label, "앞길을 살피며", "바람종", "천천히 열어도 괜찮아"):
        assert remembered in text
    assert state["ending"] == "SHARED_READING"


def test_undersea_route_changes_observed_pattern_and_final_destination():
    pack = load("undersea-lighthouse")
    tail = ["READ_SIGN", "KEEP_MAP", "LIGHT_PRISM"]
    coral = walk(pack, ["CORAL_ARCH", *tail])
    shell = walk(pack, ["SHELL_TUNNEL", *tail])
    assert "동그라미, 세모, 동그라미" in present(coral, pack)["hint"]
    assert "세모, 동그라미, 세모" in present(shell, pack)["hint"]
    assert coral["clues"] != shell["clues"]
    coral_end = walk(pack, ["CORAL_ARCH", *tail, "CIRCLE", "EAST_LIGHT"])
    shell_end = walk(pack, ["SHELL_TUNNEL", *tail, "TRIANGLE", "EAST_LIGHT"])
    assert coral_end["ending"] == "LIGHT_CONNECTED"
    assert shell_end["ending"] == "LIGHT_RELAY"
    assert "서쪽 등대" in present(shell_end, pack)["prompt"]


def test_undersea_pattern_mistake_explains_and_still_allows_successful_arrival():
    pack = load("undersea-lighthouse")
    prefix = ["CORAL_ARCH", "HEAR_BUOY", "REPEAT_SIGNS", "ECHO_SHELL"]
    repaired = walk(pack, [*prefix, "TRIANGLE"])
    assert repaired["flags"]["pattern_recalled"] is False
    assert "동그라미, 세모, 동그라미" in repaired["feedback"]
    assert "마지막 칸을 동그라미로 바꿨어" in repaired["feedback"]
    result = apply_action(repaired, pack, repaired["expected_player_id"], "EAST_LIGHT")
    assert result["ending"] == "LIGHT_CONNECTED"
    assert result["turn_index"] == 6


@pytest.mark.parametrize("slug,prefix,tools", [
    ("starlight-post-office", ["MOON_BRIDGE", "LOOK_STAMP", "WALK_TOGETHER"], ["STAR_LANTERN", "WIND_CHIME"]),
    ("undersea-lighthouse", ["CORAL_ARCH", "READ_SIGN", "KEEP_MAP"], ["LIGHT_PRISM", "ECHO_SHELL"]),
])
def test_tool_choice_changes_evidence_and_spends_one_resource(slug, prefix, tools):
    pack = load(slug)
    before = walk(pack, prefix)
    unchanged = copy.deepcopy(before)
    variants = [apply_action(before, pack, before["expected_player_id"], tool) for tool in tools]
    assert before == unchanged
    assert all(s["resources"]["tool_tokens"] == 0 for s in variants)
    assert variants[0]["clues"] != variants[1]["clues"]
    assert variants[0]["feedback"] != variants[1]["feedback"]


@pytest.mark.parametrize("slug", NEW_PACKS + INTEREST_PACKS)
def test_new_packs_keep_name_attribution_and_owner_independent_pause(slug):
    pack = load(slug)
    state = new_game(PLAYERS, pack)
    before = copy.deepcopy(state)
    label = present(state, pack)["actions"][0]["label"]
    assert interpret(label, state, pack)["reason"] == "missing_player"
    assert interpret(f"서윤, {label}", state, pack)["reason"] == "wrong_player"
    assert interpret("서윤, 쉬고 싶어", state, pack)["intent"] == "PAUSE"
    assert state == before
    assert [p["role"] for p in state["players"]] == pack["roles"]
    assert new_game(PLAYERS[:1], pack)["players"][0]["role"] == pack["solo_role"]


@pytest.mark.parametrize("slug", NEW_PACKS + ["little-car-workshop"])
def test_new_story_speech_is_bounded_and_does_not_claim_real_science(slug):
    pack = load(slug)
    assert pack["sources"] == {}
    assert "상상" in pack["fiction_notice"]
    assert all(scene["kind"] != "knowledge" for scene in pack["scenes"].values())
    for state in completions(pack):
        assert len(present(state, pack)["prompt"]) <= 160
    for scene in pack["scenes"].values():
        for action in scene["actions"]:
            for outcome in action.get("outcomes", [action]):
                assert len(outcome.get("feedback", "")) <= 110


def named_star_tool_scene():
    pack = load("starlight-post-office")
    players = [{"player_id": "p1", "nickname": "달"}, {"player_id": "p2", "nickname": "별"}]
    state = new_game(players, pack)
    for action in ("MOON_BRIDGE", "LOOK_STAMP", "WALK_TOGETHER"):
        state = apply_action(state, pack, state["expected_player_id"], action)
    assert state["expected_player_id"] == "p2"
    return pack, state


def test_exact_choice_alias_may_include_the_declared_childs_nickname():
    pack, state = named_star_tool_scene()
    before = copy.deepcopy(state)
    for text in ("별, 별 등", "별, 별등", "별, 바람종 말고 별 등", "별, 바람종 말고 별등"):
        result = interpret(text, state, pack)
        assert result == {"intent": "ACTION", "declared_player_id": "p2", "action_id": "STAR_LANTERN",
                          "needs_clarification": False, "reason": None}
    assert state == before


def test_corrected_action_containing_a_name_does_not_invent_an_owner():
    pack, state = named_star_tool_scene()
    result = interpret("바람종 말고 별 등", state, pack)
    assert result == {"intent": "ACTION", "declared_player_id": None, "action_id": "STAR_LANTERN",
                      "needs_clarification": True, "reason": "missing_player"}


def test_nickname_choice_collision_still_rejects_multiple_owners_and_wrong_turn():
    pack, state = named_star_tool_scene()
    before = copy.deepcopy(state)
    for text in ("별, 달, 별 등", "별, 바람종 말고 달, 별 등"):
        result = interpret(text, state, pack)
        assert result["needs_clarification"] is True
        assert result["reason"] == "multiple_players"
    wrong_turn = interpret("달, 별 등", state, pack)
    assert wrong_turn["needs_clarification"] is True
    assert wrong_turn["reason"] == "wrong_player"
    assert state == before


def test_interest_tags_describe_executable_content_instead_of_title_keywords():
    entries = catalog_entries()
    expected = {
        "dinosaur-lab-missing-egg": ("dinosaurs", "explore"),
        "starlight-post-office": ("stories", "imagine"),
        "undersea-lighthouse": ("ocean", "solve"),
        "space-return-signal": ("space", "solve"),
        "animal-footprint-garden": ("animals", "observe"),
        "little-car-workshop": ("vehicles", "solve"),
        NEW_PACK_ID: ("stories", "imagine"),
    }
    assert {e["pack_id"]: (e["interest_tags"][0], e["play_style"]) for e in entries} == expected
    assert {tag for e in entries for tag in e["interest_tags"]} == INTEREST_IDS
    assert all(len(e["interest_tags"]) == 1 for e in entries)
    # A fantasy star-letter is not evidence that a child will learn astronomy.
    post = next(e for e in entries if e["pack_id"] == "starlight-post-office")
    assert "space" not in post["interest_tags"]


@pytest.mark.parametrize("tags,style", [
    ([], "observe"), ("animals", "observe"), (["animals", "animals"], "observe"),
    (["unknown"], "observe"), ([None], "observe"), (["animals"], "learn"), (["animals"], []),
])
def test_catalog_rejects_unusable_recommendation_evidence(tags, style):
    with pytest.raises(ValueError):
        validate_interest_metadata({"pack_id": "invalid", "interest_tags": tags, "play_style": style})


@pytest.mark.parametrize("slug", INTEREST_PACKS)
def test_interest_stories_keep_help_and_preferences_equal_on_every_path(slug):
    pack = load(slug)
    for state in completions(pack):
        actions = [step["action_id"] for step in state["history"]]
        helped = walk(pack, actions, hints=True)
        assert helped["score_by_player"] == state["score_by_player"] == {"p1": 3, "p2": 3}
        assert helped["ending"] == state["ending"]
        assert helped["resources"] == {"tool_tokens": 0, "hint_tokens": 0}
        assert len(present(state, pack)["prompt"]) <= 160
    for scene in pack["scenes"].values():
        for action in scene["actions"]:
            for outcome in action.get("outcomes", [action]):
                assert len(outcome.get("feedback", "")) <= 110


def test_space_observation_changes_facts_record_and_ending_memory():
    pack = load("space-return-signal")
    moon = walk(pack, ["MOON", "PANORAMA"])
    mars = walk(pack, ["MARS", "CLOSE_CAMERA"])
    assert "충돌구" in moon["feedback"] and "산화철" not in moon["feedback"]
    assert "산화철" in mars["feedback"] and "충돌구" not in mars["feedback"]
    assert moon["clues"] != mars["clues"]
    result = walk(pack, ["MARS", "CLOSE_CAMERA", "NOTE", "COUNT_STEPS", "CLOSE_DOOR", "TELL_STORY"])
    text = present(result, pack)["prompt"]
    for remembered in ("화성", "산화철", "관찰 노트", "순서를 세며", "확대 카메라"):
        assert remembered in text
    assert result["ending"] == "MISSION_STORY"
    assert "노트을" not in text and "카메라으로" not in text


def test_space_premature_signal_is_corrected_before_return_and_sharing_is_still_available():
    pack = load("space-return-signal")
    state = walk(pack, ["MOON", "PANORAMA", "PHOTO", "READ_CHECKLIST", "SEND_SIGNAL"])
    assert state["flags"]["order_recalled"] is False
    assert "문을 닫고 신호를 보내" in state["feedback"]
    assert {a["id"] for a in present(state, pack)["actions"]} == {"MAKE_GALLERY", "TELL_STORY"}
    assert "열린 문" in state["feedback"]


def test_animal_habitat_changes_body_evidence_and_mismatch_is_explained():
    pack = load("animal-footprint-garden")
    pond = walk(pack, ["POND", "MOVEMENT", "PHOTO_ZOOM"])
    garden = walk(pack, ["FLOWER_GARDEN", "MOVEMENT", "PHOTO_ZOOM"])
    assert "물갈퀴" in pond["feedback"]
    assert "여섯 다리" in garden["feedback"] and "가려질 수도" in garden["feedback"]
    assert pond["clues"] != garden["clues"]
    repaired = walk(pack, ["POND", "REST", "GUIDE_BOOK", "SAY_ONE", "SIX_LEGS"])
    assert repaired["flags"]["feature_matched"] is False
    assert "물갈퀴 설명을 붙였어" in repaired["feedback"]
    assert repaired["scene_id"] == "finish_watch"


def test_animal_wait_does_not_invent_a_sighting_or_an_emotional_reason():
    pack = load("animal-footprint-garden")
    prefix = ["FLOWER_GARDEN", "REST"]
    resting = walk(pack, prefix)
    assert "얼마나 머물지는 아직 몰라" in resting["feedback"]
    result = walk(pack, [*prefix, "GUIDE_BOOK", "DRAW_TOGETHER", "SIX_LEGS", "WAIT_A_LITTLE"])
    assert "새 움직임은 보이지 않았지만" in result["feedback"]
    assert result["ending"] == "PATIENT_OBSERVATION"
    text = present(result, pack)["prompt"]
    for remembered in ("꽃밭", "나비", "잠시 머문 모습", "해설 수첩", "여섯 개의 다리", "서로의 그림"):
        assert remembered in text


def test_vehicle_order_changes_cargo_adaptation_and_loading_puzzle():
    pack = load("little-car-workshop")
    plants = walk(pack, ["PLANTS", "READ_ORDER", "OPEN_CART"])
    books = walk(pack, ["BOOKS", "READ_ORDER", "OPEN_CART"])
    assert plants["flags"]["vehicle_adapted"] is False
    assert books["flags"]["vehicle_adapted"] is True
    assert "상자용 비 덮개" in books["feedback"]
    assert plants["clues"] != books["clues"]
    plant_load = walk(pack, ["PLANTS", "READ_ORDER", "OPEN_CART", "CHECK_LIGHT", "MOON_FIRST"])
    book_load = walk(pack, ["BOOKS", "READ_ORDER", "COVERED_VAN", "CHECK_LIGHT", "MOON_FIRST"])
    assert plant_load["flags"]["load_order_recalled"] is True
    assert book_load["flags"]["load_order_recalled"] is False
    assert "달은 문 가까이" in book_load["feedback"]
    assert plant_load["scene_id"] == book_load["scene_id"] == "handover"


def test_vehicle_ending_reports_preparation_and_handover_not_an_unperformed_delivery():
    pack = load("little-car-workshop")
    state = walk(pack, ["BOOKS", "LOOK_CARGO", "OPEN_CART", "CHECK_MIRROR", "STAR_FIRST", "PASS_CHECKLIST"])
    assert state["ending"] == "DELIVERY_HANDOVER"
    text = present(state, pack)["prompt"]
    for remembered in ("초록 짐차", "그림책 상자", "점검 거울", "달 상자", "작은 도서관", "배달은 친구들이 맡아"):
        assert remembered in text
    assert "배달을 마쳤어" not in text and "도착했어" not in text and "거울으로" not in text


@pytest.mark.parametrize("slug,source_ids", [
    ("space-return-signal", {"nasa_moon_craters", "nasa_mars_color"}),
    ("animal-footprint-garden", {"nps_duck_feet", "smithsonian_insect_legs"}),
])
def test_new_fact_bearing_stories_reference_checked_primary_sources(slug, source_ids):
    pack = load(slug)
    assert set(pack["sources"]) == source_ids
    referenced = {source for scene in pack["scenes"].values() for source in scene.get("source_ids", [])}
    assert referenced == source_ids
    for source in pack["sources"].values():
        assert source["checked_at"] == "2026-10-26"
        assert source["claims_used"]
    assert "상상" in pack["fiction_notice"]
