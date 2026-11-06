"""Approved listening copy, scene-local explanation aliases, and immutable0.9 history."""

import asyncio
import copy
import hashlib
import json
from pathlib import Path
import re

import pytest

from carplay.catalog import ContentCatalog
from carplay.game import GameError, apply_action, interpret, new_game, present
from tools.export_android_fixtures import ServiceTrace

ROOT = Path(__file__).resolve().parents[1]
FIXTURE_PATH = ROOT / "tests/fixtures/dinosaur_child_script_revision.json"
F = json.loads(FIXTURE_PATH.read_text(encoding="utf-8"))
CASES = F["cases"]
CATALOG = ContentCatalog()
OLD = CATALOG.load(F["pack_id"], F["prior_version"])
NEW = CATALOG.load(F["pack_id"], F["current_version"])
PLAYERS = [{"player_id": "p1", "nickname": "민준"}, {"player_id": "p2", "nickname": "서윤"}]


def walk(pack, path, count, players=None):
    state = new_game(copy.deepcopy(players or PLAYERS[:count]), pack)
    for action in path:
        state = apply_action(state, pack, state["expected_player_id"], action)
    return state


def named(state, text):
    name = next(p["nickname"] for p in state["players"] if p["player_id"] == state["expected_player_id"])
    return f"{name}, {text}"


def accepted(state, text, action="LISTENING_DEVICE"):
    result = interpret(text, state, NEW)
    assert result == {"intent": "ACTION", "declared_player_id": state["expected_player_id"],
                      "action_id": action, "needs_clarification": False, "reason": None}, (text, result)
    return result


def render(text, state):
    flags = state["flags"]
    values = {"destination_label": {"GREENHOUSE": "온실", "WORKSHOP": "공작실"}.get(flags.get("egg_location"), "연구소")}
    for key, spec in NEW["template_labels"].items():
        values[key] = spec["values"].get(flags.get(spec["flag"]), spec["default"])
    return re.sub(r"\{([a-z_]+)\}", lambda match: values[match[1]], text)


def test_exact_original_bytes_metadata_and_only_approved_changes():
    raw = (ROOT / F["archive_asset"]).read_bytes()
    assert len(raw) == F["published_source_bytes"]
    assert hashlib.sha256(raw).hexdigest() == F["published_source_sha256"]
    archived = next(e for e in CATALOG.legacy_entries if (e["pack_id"], e["version"]) == (F["pack_id"], "0.9.0"))
    metadata = {k: v for k, v in archived.items() if k != "content_asset"}
    encoded = json.dumps(metadata, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()
    assert hashlib.sha256(encoded).hexdigest() == F["published_metadata_sha256"]
    current = next(e for e in CATALOG.all_entries
                   if (e["pack_id"], e["version"]) == (F["pack_id"], F["current_version"]))
    assert current["version"] == "0.10.0"
    assert {k: v for k, v in current.items() if k not in {"content_asset", "version"}} == {
        k: v for k, v in metadata.items() if k != "version"}
    assert hashlib.sha256(CATALOG.source(current).read_bytes()).hexdigest() == F["approved_source_sha256"]
    restored = copy.deepcopy(NEW)
    assert len(F["changed_fields"]) == 11 and len(CASES) == 7
    for field in F["changed_fields"]:
        parts = field["path"].strip("/").split("/")
        parent = restored
        for token in parts[:-1]:
            parent = parent[int(token)] if isinstance(parent, list) else parent[token]
        key = int(parts[-1]) if isinstance(parent, list) else parts[-1]
        assert parent[key] == field["after"]
        parent[key] = field["before"]
    template = F["added_template"]
    assert restored["template_labels"].pop(template["name"]) == template["value"]
    action = next(a for a in restored["scenes"]["tool"]["actions"] if a["id"] == "LISTENING_DEVICE")
    old_action = next(a for a in OLD["scenes"]["tool"]["actions"] if a["id"] == "LISTENING_DEVICE")
    assert action["aliases"] == old_action["aliases"] + [c["text"] for c in CASES]
    action["aliases"] = old_action["aliases"]
    restored["version"] = "0.9.0"
    assert restored == OLD
    assert (ROOT / "apps/vehicle/app/src/androidTest/assets/fixtures/dinosaur-child-script-revision.json").read_bytes() == FIXTURE_PATH.read_bytes()


@pytest.mark.parametrize("case", CASES, ids=lambda c: c["id"])
@pytest.mark.parametrize("count", [1, 2])
def test_exact_explanation_answers_and_both_address_positions(case, count):
    state = walk(NEW, case["path"], count)
    before = copy.deepcopy(state)
    assert case["question"] in present(state, NEW)["prompt"]
    forms = [named(state, case["text"]), named(state, "오누야 " + case["text"]), "오누야 " + named(state, case["text"])]
    if count == 1:
        forms += [case["text"], "오누야 " + case["text"]]
    for text in forms:
        accepted(state, text)
    previous = walk(OLD, case["path"], count)
    rejected = interpret(named(previous, case["text"]), previous, OLD)
    assert rejected["action_id"] is None and rejected["needs_clarification"]
    assert state == before


@pytest.mark.parametrize("case", CASES, ids=lambda c: c["id"])
def test_negative_both_correction_controls_and_two_children(case):
    text, other = case["text"], case["other"]
    for count in (1, 2):
        state = walk(NEW, case["path"], count)
        before = copy.deepcopy(state)
        for body in [case["negative"], f"{text} 하지 마", f"{text} 그리고 {other}", f"{text} 또는 {other}",
                     f"{text} {text}", f"어제 {text}라고 했어", f"{text} 말고", f"{text} 그만",
                     f'오누야 "{text}"', f"오누야 오누야 {text}",
                     f"오누야 어제 {text}라고 했어 말고 {other}", "응", "아니", "둘 다"]:
            result = interpret(named(state, body), state, NEW)
            assert result["action_id"] is None and result["needs_clarification"], (body, result)
        for body in [f"{text} 말고 {other}", f"오누야 {text} 말고 {other}"]:
            accepted(state, named(state, body), case["other_action"])
        for body in [f"{other} 말고 {text}", f"오누야 {other} 말고 {text}"]:
            accepted(state, named(state, body))
        for body, intent in [("그만", "PAUSE"), ("오누야 쉬자", "PAUSE"), ("다시", "REPEAT"),
                             ("기다려 줘", "WAIT"), ("몰라", "HINT")]:
            result = interpret(body, state, NEW)
            assert result["intent"] == intent and result["action_id"] is None
        if count == 2:
            wrong = next(p["nickname"] for p in state["players"] if p["player_id"] != state["expected_player_id"])
            assert interpret(text, state, NEW)["reason"] == "missing_player"
            assert interpret(f"{wrong}, {text}", state, NEW)["reason"] == "wrong_player"
            assert interpret(named(state, f"{wrong}, {text}"), state, NEW)["reason"] == "multiple_players"
            assert interpret(named(state, f"{text} 말고 {wrong}, {other}"), state, NEW)["reason"] == "multiple_players"
        assert state == before


@pytest.mark.parametrize("case", CASES, ids=lambda c: c["id"])
def test_whole_registered_names_keep_priority(case):
    players = copy.deepcopy(PLAYERS)
    players[1]["nickname"] = case["text"]
    if len(case["text"]) > 12:
        with pytest.raises(GameError) as error:
            walk(NEW, case["path"], 2, players)
        assert error.value.code == "invalid_players"
    else:
        state = walk(NEW, case["path"], 2, players)
        for body in [case["text"], "오누야 " + case["text"]]:
            result = interpret(body, state, NEW)
            assert result["action_id"] is None and result["needs_clarification"]
        accepted(state, named(state, case["text"]))
        assert interpret("오누야 " + named(state, case["text"]), state, NEW)["reason"] == "address_only"
    for name in ["오누", "오누야", "오 누야"]:
        state = walk(NEW, case["path"], 2, [{"player_id": "p1", "nickname": name}, PLAYERS[1]])
        accepted(state, named(state, case["text"]))
        assert interpret(named(state, "오누야 " + case["text"]), state, NEW)["needs_clarification"]


@pytest.mark.parametrize("count", [1, 2])
def test_all_paths_keep_states_scores_resources_aliases_and_clear_ending_agency(count):
    pending = [(new_game(PLAYERS[:count], OLD), new_game(PLAYERS[:count], NEW))]
    endings = {"FOUND_TOGETHER": 0, "TEAM_ASSIST": 0}
    texts = set()
    while pending:
        old, state = pending.pop()
        assert {k: v for k, v in old.items() if k not in {"pack_version", "feedback"}} == {
            k: v for k, v in state.items() if k not in {"pack_version", "feedback"}}
        view = present(state, NEW)
        scene = NEW["scenes"][state["scene_id"]]
        for field in ("prompt", "hint"):
            line = render(scene.get(field, ""), state)
            if line:
                texts.add(line)
                assert view[field].endswith(line)
        if state["feedback"]:
            texts.add(state["feedback"])
        if state["finished"]:
            endings[state["ending"]] += 1
            assert state["turn_index"] == 6
            assert "설명합니다" not in view["prompt"]
            if state["ending"] == "TEAM_ASSIST":
                assert "연구소 팀이" in view["prompt"] and "알 모형을 찾았대." in view["prompt"]
                assert "우리가 찾은 로봇이" in view["prompt"] and "우리 함께 찾았네." not in view["prompt"]
            else:
                assert "처음엔 " in view["prompt"] and "에서 바퀴 자국을 봤어." in view["prompt"]
                assert "에서 찾은 로봇" not in view["prompt"] and view["prompt"].endswith("우리 함께 찾았네.")
            if state["flags"]["tool_used"] == "LISTENING_DEVICE":
                assert "듣기 장치로 공룡 그림 설명을 들었지." in view["prompt"]
            else:
                assert "돋보기로 공룡 그림을 크게 봤지." in view["prompt"]
            continue
        assert len(view["actions"]) == 2 and view["prompt"].count("?") == 1 and len(view["prompt"]) <= 160
        for action in present(old, OLD)["actions"]:
            for text in [action["label"], *action["aliases"]]:
                assert interpret(named(old, text), old, OLD) == interpret(named(state, text), state, NEW)
            pending.append((apply_action(old, OLD, old["expected_player_id"], action["id"]),
                            apply_action(state, NEW, state["expected_player_id"], action["id"])))
    assert endings == {"FOUND_TOGETHER": 32, "TEAM_ASSIST": 32}
    assert len(texts) == 44


@pytest.mark.parametrize("count", [1, 2])
@pytest.mark.parametrize("case", CASES, ids=lambda c: c["id"])
def test_service_commits_once_and_returns_original_receipt(case, count):
    async def run():
        trace = ServiceTrace(NEW, count, "child-script-answer")
        try:
            for action in case["path"]:
                await trace.playback()
                await trace.text(named(trace.state["game"], action))
            await trace.playback()
            request = await trace.text(named(trace.state["game"], case["text"]))
            response = copy.deepcopy(trace.result["steps"][-1]["expected_response"])
            assert trace.state["game"]["history"][-1]["action_id"] == "LISTENING_DEVICE"
            assert len(trace.state["game"]["history"]) == 4
            await trace.send("action.submit", request=request)
            assert trace.result["steps"][-1]["expected_response"] == response
            assert len(trace.state["game"]["history"]) == 4
        finally:
            trace.close()
    asyncio.run(run())
