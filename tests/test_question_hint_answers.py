"""Published 0.27 question/hint revisions retain exact-edition and boundary contracts."""

import asyncio
import copy
import hashlib
import json
from pathlib import Path

import pytest

from carplay.catalog import ContentCatalog
from carplay.game import GameError, apply_action, interpret, new_game, present
from tools.export_android_fixtures import ServiceTrace

ROOT = Path(__file__).resolve().parents[1]
FIXTURE_PATH = ROOT / "tests/fixtures/question_hint_answers.json"
FIXTURE = json.loads(FIXTURE_PATH.read_text(encoding="utf-8"))
CASES = FIXTURE["cases"]
EDITIONS = {e["pack_id"]: e for e in FIXTURE["editions"]}
CATALOG = ContentCatalog()
PLAYERS = [{"player_id": "p1", "nickname": "민준"}, {"player_id": "p2", "nickname": "서윤"}]


def revised_pack(pack_id):
    # The fixture documents the 0.27 release, not whichever edition is newest.
    return CATALOG.load(pack_id, EDITIONS[pack_id]["current_version"])


def walk(pack, case, count, players=None):
    state = new_game(copy.deepcopy(players or PLAYERS[:count]), pack)
    for action in case["path"]:
        state = apply_action(state, pack, state["expected_player_id"], action)
    assert state["scene_id"] == case["scene"]
    return state


def named(state, text):
    name = next(p["nickname"] for p in state["players"] if p["player_id"] == state["expected_player_id"])
    return f"{name}, {text}"


def accepted(state, pack, text, action):
    candidate = interpret(text, state, pack)
    assert candidate == {"intent": "ACTION", "declared_player_id": state["expected_player_id"],
                         "action_id": action, "needs_clarification": False, "reason": None}, (text, candidate)
    return apply_action(state, pack, candidate["declared_player_id"], candidate["action_id"])


@pytest.mark.parametrize("case", CASES, ids=lambda c: c["id"])
@pytest.mark.parametrize("count", [1, 2])
def test_question_authored_answers_commit_only_in_new_edition(case, count):
    pack = revised_pack(case["pack_id"])
    state = walk(pack, case, count)
    before = copy.deepcopy(state)
    assert case["evidence"] in present(state, pack)[case["evidence_field"]]
    assert pack["scenes"][case["scene"]]["prompt"] == case["source_prompt"]
    assert pack["scenes"][case["scene"]]["hint"] == case["source_hint"]
    forms = [named(state, case["text"]), named(state, "오누야 " + case["text"]),
             "오누야 " + named(state, case["text"])]
    if count == 1:
        forms += [case["text"], "오누야 " + case["text"]]
    for text in forms:
        assert accepted(state, pack, text, case["action"]) == apply_action(
            state, pack, state["expected_player_id"], case["action"])
    old = CATALOG.load(case["pack_id"], EDITIONS[case["pack_id"]]["prior_version"])
    previous = walk(old, case, count)
    refused = interpret(named(previous, case["text"]), previous, old)
    assert refused["action_id"] is None and refused["needs_clarification"]
    assert refused["reason"] == case["prior_reason"]
    assert state == before


@pytest.mark.parametrize("case", CASES, ids=lambda c: c["id"])
def test_negation_two_options_corrections_names_and_controls_stay_bounded(case):
    pack = revised_pack(case["pack_id"])
    text, other = case["text"], case["other"]
    for count in (1, 2):
        state = walk(pack, case, count)
        before = copy.deepcopy(state)
        for body in [f"{text} 하지 마", f"{text} 그리고 {other}", f"{text} 또는 {other}",
                     f"{text} {text}", f"어제 {text}라고 했어", f"{text} 말고", f"{text} 그만",
                     f'오누야 "{text}"', f"오누야 오누야 {text}",
                     f"오누야 어제 {text}라고 했어 말고 {other}", "응", "아니", "둘 다", "아무거나"]:
            result = interpret(named(state, body), state, pack)
            assert result["action_id"] is None and result["needs_clarification"], (body, result)
        for body in [f"{text} 말고 {other}", f"오누야 {text} 말고 {other}"]:
            accepted(state, pack, named(state, body), case["other_action"])
        for command, intent in [("오누야 쉬자", "PAUSE"), ("다시", "REPEAT"),
                                ("기다려 줘", "WAIT"), ("몰라", "HINT")]:
            result = interpret(command, state, pack)
            assert result["intent"] == intent and result["action_id"] is None
        if count == 2:
            other_name = next(p["nickname"] for p in state["players"] if p["player_id"] != state["expected_player_id"])
            for body in [text, "오누야 " + text]:
                assert interpret(body, state, pack)["reason"] == "missing_player"
            for body in [f"{other_name}, {text}", f"오누야 {other_name}, {text}"]:
                assert interpret(body, state, pack)["reason"] == "wrong_player"
            for body in [f"{other_name}, {text}", f"{text} 말고 {other_name}, {other}"]:
                assert interpret(named(state, body), state, pack)["reason"] == "multiple_players"
        assert state == before


@pytest.mark.parametrize("case", CASES, ids=lambda c: c["id"])
def test_whole_registered_name_wins_and_onu_collisions_keep_old_grammar(case):
    pack = revised_pack(case["pack_id"])
    players = copy.deepcopy(PLAYERS)
    players[len(case["path"]) % 2]["nickname"] = case["text"]
    if len(case["text"]) > 12:
        with pytest.raises(GameError) as error:
            walk(pack, case, 2, players)
        assert error.value.code == "invalid_players"
    else:
        state = walk(pack, case, 2, players)
        for body in [case["text"], "오누야 " + case["text"]]:
            result = interpret(body, state, pack)
            assert result["action_id"] is None and result["needs_clarification"]
        accepted(state, pack, named(state, case["text"]), case["action"])
        assert interpret("오누야 " + named(state, case["text"]), state, pack)["reason"] == "address_only"
    for nickname in ["오누", "오누야", "오 누야"]:
        players = copy.deepcopy(PLAYERS)
        players[(len(case["path"]) + 1) % 2]["nickname"] = nickname
        state = walk(pack, case, 2, players)
        accepted(state, pack, named(state, case["text"]), case["action"])
        result = interpret(named(state, "오누야 " + case["text"]), state, pack)
        assert result["action_id"] is None and result["needs_clarification"]


@pytest.mark.parametrize("pack_id", EDITIONS)
def test_exact_archives_metadata_and_only_declared_aliases_change(pack_id):
    edition = EDITIONS[pack_id]
    raw = (ROOT / edition["archive_asset"]).read_bytes()
    assert len(raw) == edition["published_source_bytes"]
    assert hashlib.sha256(raw).hexdigest() == edition["published_source_sha256"]
    archived = next(e for e in CATALOG.legacy_entries if (e["pack_id"], e["version"]) ==
                    (pack_id, edition["prior_version"]))
    meta = {k: v for k, v in archived.items() if k != "content_asset"}
    encoded = json.dumps(meta, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()
    assert hashlib.sha256(encoded).hexdigest() == edition["published_metadata_sha256"]
    current = next(e for e in CATALOG.all_entries if (e["pack_id"], e["version"]) ==
                   (pack_id, edition["current_version"]))
    assert current["version"] == edition["current_version"]
    assert {k: v for k, v in current.items() if k not in {"version", "content_asset"}} == {
        k: v for k, v in meta.items() if k != "version"}
    previous = json.loads(raw)
    new = copy.deepcopy(revised_pack(pack_id))
    for sid, scene in new["scenes"].items():
        for action in scene.get("actions", []):
            original = next(a for a in previous["scenes"][sid]["actions"] if a["id"] == action["id"])
            additions = [c["text"] for c in CASES if (c["pack_id"], c["scene"], c["action"]) ==
                         (pack_id, sid, action["id"])]
            assert action["aliases"] == original["aliases"] + additions
            action["aliases"] = original["aliases"]
    new["version"] = edition["prior_version"]
    assert new == previous, "Questions, hints, feedback, flags, scores and endings must be unchanged"


@pytest.mark.parametrize("pack_id", EDITIONS)
@pytest.mark.parametrize("count", [1, 2])
def test_all_paths_and_prior_aliases_preserve_results(pack_id, count):
    old = CATALOG.load(pack_id, EDITIONS[pack_id]["prior_version"])
    new = revised_pack(pack_id)
    pending = [(new_game(PLAYERS[:count], old), new_game(PLAYERS[:count], new))]
    checked_scenes = set()
    completed = 0
    while pending:
        previous, state = pending.pop()
        assert {k: v for k, v in previous.items() if k != "pack_version"} == {
            k: v for k, v in state.items() if k != "pack_version"}
        for key in ["prompt", "hint", "feedback"]:
            assert present(previous, old)[key] == present(state, new)[key]
        if state["finished"]:
            completed += 1
            continue
        for action in present(previous, old)["actions"]:
            if state["scene_id"] not in checked_scenes:
                for alias in [action["id"], action["label"], *action["aliases"]]:
                    assert interpret(named(state, alias), state, new) == interpret(named(previous, alias), previous, old)
            pending.append((apply_action(previous, old, previous["expected_player_id"], action["id"]),
                            apply_action(state, new, state["expected_player_id"], action["id"])))
        checked_scenes.add(state["scene_id"])
    assert completed == 2 ** new["max_turns"]


@pytest.mark.parametrize("pack_id", EDITIONS)
def test_service_new_answer_receipt_name_confirmation_and_quiet_budget(pack_id):
    async def run():
        case = next(c for c in CASES if c["pack_id"] == pack_id)
        pack = CATALOG.current(pack_id)
        trace = ServiceTrace(pack, 2, "question-hint-answer")
        try:
            for action in case["path"]:
                await trace.playback()
                await trace.text(named(trace.state["game"], action))
            await trace.playback()
            before = copy.deepcopy(trace.state["game"])
            await trace.text(case["text"])
            assert trace.state["presentation"]["input_kind"] == "player_name"
            assert trace.state["game"] == before
            await trace.playback()
            request = await trace.text(named(before, "").rstrip(", "))
            response = copy.deepcopy(trace.result["steps"][-1]["expected_response"])
            history = trace.state["game"]["history"]
            assert len(history) == len(case["path"]) + 1 and history[-1]["action_id"] == case["action"]
            await trace.send("action.submit", request=request)
            assert trace.result["steps"][-1]["expected_response"] == response
            assert len(trace.state["game"]["history"]) == len(history)
            await trace.playback()
            after = copy.deepcopy(trace.state["game"])
            for invalid in ["응", "둘 다"]:
                await trace.text(named(after, invalid))
                if trace.state["status"] == "ACTIVE":
                    await trace.playback()
            assert trace.state["status"] == "PAUSED" and trace.state["pause_reason"] == "NO_RESPONSE"
            assert trace.state["speech_plan"] is None and trace.state["listen_window_id"] is None
            assert trace.state["requires_explicit_resume"] and trace.state["game"] == after
        finally:
            trace.close()
    asyncio.run(run())


def test_independent_fixture_is_bundled_exactly_and_027_dinosaur_edition_is_preserved():
    assert len(CASES) == 42 and len(EDITIONS) == 6
    assert (ROOT / "apps/vehicle/app/src/androidTest/assets/fixtures/question-hint-answers.json").read_bytes() == FIXTURE_PATH.read_bytes()
    assert CATALOG.load("dinosaur-lab-missing-egg", "0.8.0")["version"] == "0.8.0"
