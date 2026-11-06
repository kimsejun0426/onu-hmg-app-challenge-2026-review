"""The 0.9 stories connect choices without changing shipped game or voice rules.

Synthetic narration checks, not evidence of child listening comprehension.
The archive hashes were read from the delivered 0.17 application's D: checkout.
"""

import copy
import hashlib
import json
from itertools import product
from pathlib import Path

import pytest

from carplay.catalog import ContentCatalog
from carplay.game import apply_action, interpret, load_pack, new_game, present

ROOT = Path(__file__).resolve().parents[1]
ANIMAL = "animal-footprint-garden"
CAR = "little-car-workshop"
SPACE = "space-return-signal"
PACKS = (ANIMAL, CAR, SPACE)
PLAYERS = [{"player_id": "p1", "nickname": "민준"}, {"player_id": "p2", "nickname": "서윤"}]
PUBLISHED = {
    ANIMAL: (20105, "c1c09e0ed505ee3135e7555271548c3b7ac4235bec7b01cfefc5b5605e890705"),
    CAR: (19323, "f14aa5ba5910a4e943d7e58526a0a4ce8407bde4f10c2aa0b12e0dc7eb5d2f2b"),
    SPACE: (18237, "a463aa211b5a7225788de6ce9a164608a057be01a674b8a64de73ffc556ad1fc"),
}
CATALOG = ContentCatalog()
PUBLISHED_RELEASE = json.loads(
    (ROOT / "tests/fixtures/published_onu_017_content.json").read_text(encoding="utf-8")
)


def edition(pack_id, version):
    current = load_pack(ROOT / "content/ko-KR" / pack_id / "episode.json")
    if current["version"] == version:
        return current
    archived = load_pack(ROOT / "content/legacy" / version / pack_id / "episode.json")
    assert archived["version"] == version
    return archived


def walk(pack, actions, count):
    state = new_game(PLAYERS[:count], pack)
    for action in actions:
        state = apply_action(state, pack, state["expected_player_id"], action)
    return state


def prefix(state):
    player = next(p for p in state["players"] if p["player_id"] == state["expected_player_id"])
    if len(state["players"]) == 1:
        return f"{player['nickname']}, "
    return f"{player['nickname']} {player['role']} 차례야. "


def utterance(state, body):
    player = next(p for p in state["players"] if p["player_id"] == state["expected_player_id"])
    return f"{player['nickname']}, {body}" if len(state["players"]) == 2 else body


def effects(state):
    return {key: value for key, value in state.items() if key not in {"pack_version", "feedback"}}


def changed_paths(old, new, path=()):
    if isinstance(old, dict) and isinstance(new, dict):
        paths = set()
        for key in old.keys() | new.keys():
            if key not in old or key not in new:
                paths.add((*path, key))
            else:
                paths.update(changed_paths(old[key], new[key], (*path, key)))
        return paths
    if isinstance(old, list) and isinstance(new, list) and len(old) == len(new):
        return set().union(*(changed_paths(a, b, (*path, i))
                             for i, (a, b) in enumerate(zip(old, new, strict=True))))
    return set() if old == new else {path}


@pytest.mark.parametrize("pack_id", PACKS)
def test_delivered_017_original_bytes_remain_exact(pack_id):
    source = ROOT / "content/legacy/0.8.0" / pack_id / "episode.json"
    raw = source.read_bytes()
    size, digest = PUBLISHED[pack_id]
    assert len(raw) == size and hashlib.sha256(raw).hexdigest() == digest
    assert load_pack(source)["version"] == "0.8.0"
    assert edition(pack_id, "0.9.0")["version"] == "0.9.0"
    frozen = next(entry for entry in PUBLISHED_RELEASE["packs"] if entry["pack_id"] == pack_id)
    assert (frozen["bytes"], frozen["sha256"]) == (size, digest)
    archived = next(entry for entry in CATALOG.legacy_entries
                    if (entry["pack_id"], entry["version"]) == (pack_id, "0.8.0"))
    assert archived["content_asset"] == frozen["content_asset"]
    assert CATALOG.source(archived) == source.resolve()
    assert {key: value for key, value in archived.items() if key != "content_asset"} == frozen["catalog_metadata"]
    assert CATALOG.load(pack_id, "0.8.0") == json.loads(raw)
    # Keep the 0.18 prose revision as a version-exact historical contract.
    current = next(entry for entry in CATALOG.all_entries
                   if (entry["pack_id"], entry["version"]) == (pack_id, "0.9.0"))
    assert current["version"] == CATALOG.load(pack_id, "0.9.0")["version"] == "0.9.0"
    folder = "legacy/0.9.0"
    assert current["content_asset"] == f"content/{folder}/{pack_id}/episode.json"
    assert {key: value for key, value in current.items() if key not in {"version", "content_asset"}} == {
        key: value for key, value in frozen["catalog_metadata"].items() if key != "version"
    }


@pytest.mark.parametrize("pack_id", PACKS)
def test_only_the_approved_narration_and_one_flat_label_change(pack_id):
    old, new = edition(pack_id, "0.8.0"), edition(pack_id, "0.9.0")
    allowed = {("version",)}
    if pack_id == ANIMAL:
        allowed.add(("template_labels", "first_record_location"))
        allowed.update(("scenes", scene, "actions", index, "feedback")
                       for scene in ("pond_watch", "garden_watch", "compare_notes")
                       for index in (0, 1))
        allowed.update(("scenes", "compare_notes", field) for field in ("prompt", "hint"))
        label = new["template_labels"]["first_record_location"]
        assert label["flag"] == "observation" and set(label["values"]) == {"MOVEMENT", "REST"}
        assert all("{" not in text and "}" not in text
                   for text in [label["default"], *label["values"].values()])
    elif pack_id == CAR:
        allowed.update(("scenes", scene, "prompt") for scene in ("choose_vehicle", "check_vehicle"))
    else:
        allowed.add(("scenes", "return_order", "prompt"))
    assert changed_paths(old, new) == allowed


@pytest.mark.parametrize("pack_id", PACKS)
@pytest.mark.parametrize("count", [1, 2])
def test_all_paths_preserve_effects_names_roles_endings_and_authored_answers(pack_id, count):
    old, new = edition(pack_id, "0.8.0"), edition(pack_id, "0.9.0")
    pending = [(new_game(PLAYERS[:count], old), new_game(PLAYERS[:count], new))]
    completed = 0
    while pending:
        previous, state = pending.pop()
        assert effects(previous) == effects(state)
        before, view = present(previous, old), present(state, new)
        assert before["actions"] == view["actions"]
        assert "{" not in view["prompt"] and "}" not in view["prompt"]
        if state["finished"]:
            completed += 1
            assert before["prompt"] == view["prompt"]
            assert state["turn_index"] == 6
            continue
        assert view["prompt"].startswith(prefix(state)) and view["prompt"].count("?") == 1
        for action in view["actions"]:
            # Labels are also recognition aliases; unchanged JSON alias arrays alone
            # would not prove compatibility after a display-label edit.
            for answer in [action["label"], *action["aliases"]]:
                text = utterance(state, answer)
                assert interpret(text, state, new) == interpret(text, previous, old)
            pending.append((apply_action(previous, old, previous["expected_player_id"], action["id"]),
                            apply_action(state, new, state["expected_player_id"], action["id"])))
    assert completed == 64


@pytest.mark.parametrize("count", [1, 2])
@pytest.mark.parametrize("place,observation,tool,addition", list(product(
    ["POND", "FLOWER_GARDEN"], ["MOVEMENT", "REST"],
    ["PHOTO_ZOOM", "GUIDE_BOOK"], ["SAY_ONE", "DRAW_TOGETHER"],
)))
def test_animal_keeps_the_first_record_and_adds_beside_it(count, place, observation, tool, addition):
    pack = edition(ANIMAL, "0.9.0")
    state = walk(pack, [place], count)
    assert [a["label"] for a in present(state, pack)["actions"]] == ["움직임 적기", "머문 자리 그리기"]
    state = apply_action(state, pack, state["expected_player_id"], observation)
    assert ("적었어" if observation == "MOVEMENT" else "그렸어") in state["feedback"]
    assert "오누가" in state["feedback"]
    original_note = state["flags"]["record_note"]
    original_clues = list(state["clues"])
    state = apply_action(state, pack, state["expected_player_id"], tool)
    question = present(state, pack)["prompt"]
    location = "아까 적은 메모 옆에" if observation == "MOVEMENT" else "아까 그린 그림 옆에"
    assert question == prefix(state) + location + " 오누가 문장으로 남길까, 이어서 그릴까?"
    assert "처음 기록은 그대로야" in present(state, pack)["hint"]
    state = apply_action(state, pack, state["expected_player_id"], addition)
    assert state["flags"]["record_note"] == original_note
    assert all(clue in state["clues"] for clue in original_clues)
    expected_scene = {
        ("POND", "MOVEMENT"): "오리가 지나간 뒤 잔물결이 남았어.",
        ("POND", "REST"): "오리가 돌 옆에 멈춰 있어.",
        ("FLOWER_GARDEN", "MOVEMENT"): "나비가 하얀 꽃에서 노란 꽃으로 날아갔어.",
        ("FLOWER_GARDEN", "REST"): "나비가 노란 꽃에 앉아 있어.",
    }[place, observation]
    expected_addition = "오누가 옆에 문장을 더 남겼어." if addition == "SAY_ONE" else "오누가 옆에 그림을 이어 그렸어."
    # Feedback finishes before the next child is called; no child is claimed to
    # have produced a recording, drawing, or explanation.
    assert state["feedback"] == expected_addition
    assert present(state, pack)["prompt"] == prefix(state) + expected_scene + " 몸 설명도 붙이자. 물갈퀴일까, 여섯 다리일까?"


@pytest.mark.parametrize("count", [1, 2])
@pytest.mark.parametrize("cargo,vehicle", list(product(["PLANTS", "BOOKS"], ["OPEN_CART", "COVERED_VAN"])))
def test_the_selected_delivery_car_is_duri_without_a_transformation(count, cargo, vehicle):
    pack = edition(CAR, "0.9.0")
    initial = new_game(PLAYERS[:count], pack)
    assert "꼬마 자동차 두리가 왔어" in present(initial, pack)["prompt"]
    state = walk(pack, [cargo, "READ_ORDER"], count)
    cargo_object = "키 큰 화분을" if cargo == "PLANTS" else "그림책 상자를"
    assert present(state, pack)["prompt"] == prefix(state) + (
        f"{cargo_object} 실을 두리의 모습을 정하자. 위가 트인 초록 짐차를 고를까, 덮개 있는 파란 밴을 고를까?"
    )
    state = apply_action(state, pack, state["expected_player_id"], vehicle)
    identity = "지붕 없는 초록 짐차" if vehicle == "OPEN_CART" else "덮개 있는 파란 밴"
    assert present(state, pack)["prompt"] == prefix(state) + (
        f"{identity} 두리의 짐칸을 보자. 옆면 고리를 점검등으로 볼까, 점검 거울로 볼까?"
    )
    assert "변신" not in state["feedback"] + present(state, pack)["prompt"]
    assert state["flags"]["vehicle"] == vehicle


@pytest.mark.parametrize("count", [1, 2])
@pytest.mark.parametrize("preparation", ["READ_CHECKLIST", "COUNT_STEPS"])
def test_return_question_does_not_repeat_the_completed_preparation(count, preparation):
    old, new = edition(SPACE, "0.8.0"), edition(SPACE, "0.9.0")
    path = ["MOON", "PANORAMA", "PHOTO", preparation]
    previous, state = walk(old, path, count), walk(new, path, count)
    assert state["feedback"] == previous["feedback"]
    assert state["feedback"].index("함께 탔어") < state["feedback"].index("문 닫기") < state["feedback"].index("출발 신호")
    assert "순서를 짚었어" in present(previous, old)["prompt"]
    assert present(state, new)["prompt"] == prefix(state) + "문이 아직 열려 있네. 문 닫기부터 할까, 출발 신호부터 보낼까?"
    assert len(present(state, new)["prompt"]) < len(present(previous, old)["prompt"])
    for answer, action in [("문 닫기부터", "CLOSE_DOOR"), ("출발 신호부터", "SEND_SIGNAL")]:
        assert interpret(utterance(state, answer), state, new)["action_id"] == action
        outcome = apply_action(state, new, state["expected_player_id"], action)
        assert "문을 닫고 출발 신호" in outcome["feedback"] or "문부터 닫고 신호" in outcome["feedback"]


@pytest.mark.parametrize("count", [1, 2])
@pytest.mark.parametrize("pack_id,path,choice,other", [
    (ANIMAL, ["POND", "REST", "GUIDE_BOOK"], "문장으로 남길래", "이어서 그릴래"),
    (CAR, ["BOOKS", "READ_ORDER"], "초록 짐차를 고를래", "파란 밴을 고를래"),
    (SPACE, ["MOON", "PANORAMA", "PHOTO", "READ_CHECKLIST"], "문 닫기부터", "출발 신호부터"),
])
def test_reworded_questions_keep_names_corrections_controls_and_refusal_boundaries(count, pack_id, path, choice, other):
    old, new = edition(pack_id, "0.8.0"), edition(pack_id, "0.9.0")
    previous, state = walk(old, path, count), walk(new, path, count)
    unchanged = copy.deepcopy(state)
    for body in (choice, other, f"{choice} 말고 {other}"):
        for text in (utterance(state, body), "오누야 " + utterance(state, body)):
            result = interpret(text, state, new)
            assert result == interpret(text, previous, old)
            assert result["intent"] == "ACTION" and not result["needs_clarification"]
    for body, intent in [("쉬자", "PAUSE"), ("기다려 줘", "WAIT"), ("다시", "REPEAT"),
                         ("도와줘", "HINT"), ("끝내기", "END"), ("계속", "RESUME")]:
        text = utterance(state, body)
        result = interpret(text, state, new)
        assert result == interpret(text, previous, old)
        assert result["intent"] == intent and result["action_id"] is None
    for body in (f"{choice} 그리고 {other}", f"{choice} 하지 마", f"{choice} 말고",
                 f"어제 {choice}라고 했어", f"{choice} {choice}"):
        text = utterance(state, body)
        result = interpret(text, state, new)
        assert result == interpret(text, previous, old)
        assert result["needs_clarification"] and result["action_id"] is None
    assert interpret("오누야", state, new)["reason"] == "address_only"
    assert interpret("오누야 " + utterance(state, f'"{choice}"'), state, new)["action_id"] is None
    if count == 2:
        assert interpret(choice, state, new)["reason"] == "missing_player"
        wrong = next(p["nickname"] for p in PLAYERS if p["player_id"] != state["expected_player_id"])
        assert interpret(f"{wrong}, {choice}", state, new)["reason"] == "wrong_player"
    assert state == unchanged
