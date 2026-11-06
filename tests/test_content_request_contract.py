"""Authored request words and story continuity, using synthetic inputs only.

Frozen identities were read from the delivered D: 0.18 files before editing.
The unchanged interpreter still accepts its existing explicit correction and
bare-quote normalization; this revision adds no general language inference.
"""

import copy
import hashlib
import json
from pathlib import Path

import pytest

from carplay.catalog import ContentCatalog
from carplay.game import apply_action, interpret, new_game, present

ROOT = Path(__file__).resolve().parents[1]
CATALOG = ContentCatalog()
PLAYERS = [{"player_id": "p1", "nickname": "민준"}, {"player_id": "p2", "nickname": "서윤"}]
ANIMAL, PICNIC, OCEAN = "animal-footprint-garden", "onu-little-picnic", "undersea-lighthouse"
VERSIONS = {ANIMAL: ("0.9.0", "0.10.0"), PICNIC: ("0.3.0", "0.4.0"), OCEAN: ("0.7.0", "0.8.0")}
PUBLISHED = {
    ANIMAL: (20329, "f0df4e5675c00d6fc16e3e7862c4fe405e5089a0c73b1f72d9b1048db564a032",
             "37e45b8fee4f4115de2177a9a7ef67b6a1c4dfd4c7b3d423a8e3a7f88a350250"),
    PICNIC: (14349, "ac19c817166e409aea23a89f40cec6cd34e6bb1577492c673037534785433fe3",
             "6117524d810d96893ab1ae86bbfc0d97f4b661538ab2428eb91c74b96ca5fc29"),
    OCEAN: (19011, "e2bb8a5e545290c61ddbc90a34220a5e4e67b8f1e9c9507533427c09e0ed4ac2",
            "2057ea01e005bf29ab0c8ef1f9a91146186f3b5012b6c34a25168a7597a3e936"),
}
REQUESTS = {
    ANIMAL: ["이어 그려 줘", "이어 그려줘", "이어그려줘",
             "이어서 그려 줘", "이어서 그려줘", "이어서그려줘"],
    PICNIC: ["풍경 간직해 줘", "풍경 간직해줘", "풍경간직해줘",
             "풍경을 간직해 줘", "풍경을 간직해줘", "풍경을간직해줘",
             "풍경 조용히 간직해 줘", "풍경 조용히 간직해줘", "풍경조용히간직해줘",
             "풍경을 조용히 간직해 줘", "풍경을 조용히 간직해줘", "풍경을조용히간직해줘"],
}
PATHS = {ANIMAL: ["POND", "MOVEMENT", "PHOTO_ZOOM"], PICNIC: ["HILL", "KITE", "HIGH"]}
SCENES = {ANIMAL: "compare_notes", PICNIC: "choose_goodbye"}
ACTIONS = {ANIMAL: "DRAW_TOGETHER", PICNIC: "KEEP_SCENE"}
OTHERS = {ANIMAL: ("문장으로 남겨 줘", "SAY_ONE"), PICNIC: ("짧은 인사를 남겨 줘", "FAREWELL")}


def walk(pack, path, count):
    state = new_game(PLAYERS[:count], pack)
    for action in path:
        state = apply_action(state, pack, state["expected_player_id"], action)
    return state


def named(state, body):
    player = next(p for p in state["players"] if p["player_id"] == state["expected_player_id"])
    return f"{player['nickname']}, {body}"


@pytest.mark.parametrize("pack_id", REQUESTS)
@pytest.mark.parametrize("count", [1, 2])
def test_requests_follow_the_spoken_question(pack_id, count):
    pack = CATALOG.current(pack_id)
    state = walk(pack, PATHS[pack_id], count)
    assert state["scene_id"] == SCENES[pack_id]
    prompt = present(state, pack)["prompt"]
    assert ("이어서 그릴까?" if pack_id == ANIMAL else "풍경을 조용히 간직할까?") in prompt
    before = copy.deepcopy(state)
    for request in REQUESTS[pack_id]:
        forms = [named(state, request), named(state, "오누야 " + request), "오누야 " + named(state, request)]
        if count == 1:
            forms.append(request)
        for text in forms:
            result = interpret(text, state, pack)
            assert result["intent"] == "ACTION", (text, result)
            assert result["action_id"] == ACTIONS[pack_id] and not result["needs_clarification"]
            assert result["declared_player_id"] == state["expected_player_id"]
    assert state == before


@pytest.mark.parametrize("pack_id", VERSIONS)
def test_delivered_bytes_and_catalog_metadata_are_preserved(pack_id):
    previous, current = VERSIONS[pack_id]
    source = ROOT / f"content/legacy/{previous}/{pack_id}/episode.json"
    raw = source.read_bytes()
    length, digest, metadata_digest = PUBLISHED[pack_id]
    assert len(raw) == length and hashlib.sha256(raw).hexdigest() == digest
    archived = next(e for e in CATALOG.legacy_entries if (e["pack_id"], e["version"]) == (pack_id, previous))
    assert CATALOG.source(archived) == source.resolve()
    metadata = {key: value for key, value in archived.items() if key != "content_asset"}
    encoded = json.dumps(metadata, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()
    assert hashlib.sha256(encoded).hexdigest() == metadata_digest
    assert CATALOG.load(pack_id, previous) == json.loads(raw)
    # The 0.20 request revision remains a version-exact contract after archiving.
    active = next(e for e in CATALOG.all_entries if (e["pack_id"], e["version"]) == (pack_id, current))
    assert active["version"] == CATALOG.load(pack_id, current)["version"] == current
    assert active["content_asset"] == f"content/legacy/{current}/{pack_id}/episode.json"
    assert {key: value for key, value in active.items() if key not in {"version", "content_asset"}} == {
        key: value for key, value in metadata.items() if key != "version"
    }


@pytest.mark.parametrize("pack_id", VERSIONS)
def test_only_exact_aliases_and_two_continuity_sentences_change(pack_id):
    old = CATALOG.load(pack_id, VERSIONS[pack_id][0])
    new = copy.deepcopy(CATALOG.load(pack_id, VERSIONS[pack_id][1]))
    new["version"] = old["version"]
    if pack_id in REQUESTS:
        old_actions, new_actions = old["scenes"][SCENES[pack_id]]["actions"], new["scenes"][SCENES[pack_id]]["actions"]
        old_action = next(a for a in old_actions if a["id"] == ACTIONS[pack_id])
        new_action = next(a for a in new_actions if a["id"] == ACTIONS[pack_id])
        assert new_action["aliases"] == old_action["aliases"] + REQUESTS[pack_id]
        new_action["aliases"] = old_action["aliases"]
    scene = "ending_wait" if pack_id == ANIMAL else "choose_destination"
    if pack_id != PICNIC:
        assert new["scenes"][scene]["prompt"] != old["scenes"][scene]["prompt"]
        new["scenes"][scene]["prompt"] = old["scenes"][scene]["prompt"]
    assert new == old


@pytest.mark.parametrize("pack_id", VERSIONS)
@pytest.mark.parametrize("count", [1, 2])
def test_all_paths_keep_effects_turns_choices_and_published_alias_meanings(pack_id, count):
    # Keep the historical 0.20 prose contract pinned to its published editions.
    old = CATALOG.load(pack_id, VERSIONS[pack_id][0])
    new = CATALOG.load(pack_id, VERSIONS[pack_id][1])
    pending = [(new_game(PLAYERS[:count], old), new_game(PLAYERS[:count], new))]
    completed = 0
    while pending:
        before, state = pending.pop()
        assert {k: v for k, v in before.items() if k != "pack_version"} == {
            k: v for k, v in state.items() if k != "pack_version"
        }
        old_view, view = present(before, old), present(state, new)
        assert [(a["id"], a["label"]) for a in view["actions"]] == [(a["id"], a["label"]) for a in old_view["actions"]]
        changed_scene = (pack_id == ANIMAL and state["scene_id"] == "ending_wait") or (
            pack_id == OCEAN and state["scene_id"] == "choose_destination")
        if not changed_scene:
            assert view["prompt"] == old_view["prompt"]
        assert view["feedback"] == old_view["feedback"] and view["hint"] == old_view["hint"]
        assert "{" not in view["prompt"] and len(view["prompt"]) <= 160
        if state["finished"]:
            completed += 1
            assert state["turn_index"] == (4 if pack_id == PICNIC else 6)
            continue
        for action in old_view["actions"]:
            for text in [action["label"], *action["aliases"]]:
                assert interpret(named(state, text), state, new) == interpret(named(before, text), before, old)
            pending.append((apply_action(before, old, before["expected_player_id"], action["id"]),
                            apply_action(state, new, state["expected_player_id"], action["id"])))
    assert completed == (16 if pack_id == PICNIC else 64)


@pytest.mark.parametrize("pack_id", REQUESTS)
@pytest.mark.parametrize("count", [1, 2])
def test_new_requests_do_not_relax_refusal_attribution_or_existing_correction_grammar(pack_id, count):
    old, new = CATALOG.load(pack_id, VERSIONS[pack_id][0]), CATALOG.current(pack_id)
    previous, state = walk(old, PATHS[pack_id], count), walk(new, PATHS[pack_id], count)
    before = copy.deepcopy(state)
    other, other_id = OTHERS[pack_id]
    for request in REQUESTS[pack_id]:
        unsupported = [f"{request} 하지 마", f"{request} 그리고 {other}", f"어제 {request}라고 했어",
                       f"{request} {request}", f"{request} 말고", f'오누야 "{request}"',
                       f"오누야 어제 {request}라고 했어 말고 {other}"]
        for body in unsupported:
            for pack, current_state in [(old, previous), (new, state)]:
                result = interpret(named(current_state, body), current_state, pack)
                assert result["needs_clarification"] and result["action_id"] is None, (body, result)
        # The exact request was not recognized by the delivered edition.
        assert interpret(named(previous, request), previous, old)["action_id"] is None
        # Keep the already-supported explicit correction and quote normalization.
        corrected = interpret(named(state, request + " 말고 " + other), state, new)
        assert corrected["action_id"] == other_id and not corrected["needs_clarification"]
        quoted = interpret(named(state, f'"{request}"'), state, new)
        assert quoted["action_id"] == ACTIONS[pack_id] and not quoted["needs_clarification"]
        if count == 2:
            unnamed = interpret(request, state, new)
            assert unnamed["reason"] == "missing_player" and unnamed["needs_clarification"]
            wrong_name = next(p["nickname"] for p in state["players"] if p["player_id"] != state["expected_player_id"])
            wrong = interpret(f"{wrong_name}, {request}", state, new)
            assert wrong["reason"] == "wrong_player" and wrong["needs_clarification"]
            mixed = interpret(named(state, f"{wrong_name}, {request}"), state, new)
            assert mixed["reason"] == "multiple_players" and mixed["needs_clarification"]
    for text, intent in [("그만", "PAUSE"), ("다시", "REPEAT"), ("기다려 줘", "WAIT"), ("도와줘", "HINT")]:
        assert interpret(text, state, new) == interpret(text, previous, old)
        assert interpret(text, state, new)["intent"] == intent
    assert state == before


@pytest.mark.parametrize("count", [1, 2])
def test_waiting_ending_keeps_the_recorded_body_description(count):
    pack = CATALOG.current(ANIMAL)
    for route, feature, description in [("POND", "WEBBED_FEET", "헤엄을 돕는 물갈퀴"),
                                        ("FLOWER_GARDEN", "SIX_LEGS", "여섯 개의 다리")]:
        for observation in ("MOVEMENT", "REST"):
            for drawing in ("SAY_ONE", "DRAW_TOGETHER"):
                state = walk(pack, [route, observation, "PHOTO_ZOOM", drawing, feature, "WAIT_A_LITTLE"], count)
                prompt = present(state, pack)["prompt"]
                assert f"옆에 {description} 설명도 붙였어." in prompt
                assert "만 수첩에 남겼어" not in prompt
                assert "더 기다려도 새 움직임이 없어" in prompt
                assert state["flags"]["teamwork"] == drawing and state["flags"]["feature_matched"]


@pytest.mark.parametrize("count", [1, 2])
def test_lighthouse_question_precedes_nuris_actual_arrival(count):
    pack = CATALOG.current(OCEAN)
    for route, shape, destination, beacon in [("CORAL_ARCH", "CIRCLE", "EAST_LIGHT", "동쪽 등대"),
                                             ("SHELL_TUNNEL", "TRIANGLE", "WEST_LIGHT", "서쪽 등대")]:
        state = walk(pack, [route, "READ_SIGN", "KEEP_MAP", "ECHO_SHELL", shape], count)
        prompt = present(state, pack)["prompt"]
        assert f"누리가 돌아갈 곳은 {beacon}야." in prompt
        assert "기다리는 곳" not in prompt
        final = apply_action(state, pack, state["expected_player_id"], destination)
        assert "누리가 불빛을 따라 돌아가" in final["feedback"]
        assert f"{beacon}에 도착한 누리" in present(final, pack)["prompt"]
