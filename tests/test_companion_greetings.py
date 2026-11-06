"""Bounded, authored greetings inside an existing answer window, never ambient chat."""

import asyncio
import copy
import json
from pathlib import Path

import pytest

from carplay import game
from carplay.catalog import ContentCatalog
from tools.export_android_fixtures import ServiceTrace

PACK = ContentCatalog().current()
FIXTURE = json.loads((Path(__file__).parent / "fixtures/companion-greetings.json").read_text(encoding="utf-8"))


@pytest.mark.parametrize("case", FIXTURE["cases"], ids=lambda case: case["id"])
def test_hand_authored_greeting_boundary(case):
    pack = copy.deepcopy(PACK)
    if "action_alias" in case:
        action = next(a for a in pack["scenes"][pack["start_scene"]]["actions"] if a["id"] == "RIVER")
        action.setdefault("aliases", []).append(case["action_alias"])
    state = game.new_game(case["players"], pack)
    before = copy.deepcopy(state)
    assert game.interpret(case["text"], state, pack) == case["expected_candidate"]
    assert state == before


class RejectResolver:
    calls = 0

    async def resolve(self, text, context):
        self.calls += 1
        raise AssertionError("A fixed greeting must never reach a model")


def texts(state):
    return [segment["text"] for segment in state["speech_plan"]["segments"]]


def assert_quiet(state):
    assert state["status"] == "PAUSED" and state["pause_reason"] == "NO_RESPONSE"
    assert state["speech_plan"] is None and state["listen_window_id"] is None
    assert state["requires_explicit_resume"]


@pytest.mark.parametrize("count", [1, 2])
def test_one_greeting_returns_current_question_then_existing_quiet_budget(count):
    async def run():
        trace = ServiceTrace(PACK, count, "companion-greeting-budget")
        resolver = RejectResolver()
        trace.service.resolver = resolver
        try:
            await trace.playback()
            before = copy.deepcopy(trace.state["game"])
            turn = trace.state["turn_id"]
            request = await trace.text("오누야 안녕")
            first_response = copy.deepcopy(trace.result["steps"][-1]["expected_response"])
            assert trace.state["status"] == "ACTIVE"
            assert texts(trace.state) == ["응, 여기 있어.", trace.state["presentation"]["prompt"]]
            assert trace.state["game"] == before and trace.state["turn_id"] == turn
            assert trace.state["listen_window_id"] is None
            await trace.send("action.submit", request=request)
            assert trace.result["steps"][-1]["expected_response"] == first_response
            await trace.playback()
            await trace.text("오누야 같이 놀자")
            assert_quiet(trace.state)
            assert trace.state["game"] == before and resolver.calls == 0
            # A late final result cannot make a paused session listen again.
            await trace.send("action.submit", {"text": "오누야 뭐해", "is_final": True,
                              "capture_id": "late-greeting", "listen_window_id": "closed-window"})
            assert trace.result["steps"][-1]["expected_error"]["code"] == "SESSION_PAUSED"
            await trace.send("session.resume")
            await trace.playback()
            await trace.text("민준 물가")
            assert [item["action_id"] for item in trace.state["game"]["history"]] == ["RIVER"]
        finally:
            trace.close()
    asyncio.run(run())


@pytest.mark.parametrize("prefix", ["물가", "서윤 물가"])
def test_greeting_never_confirms_pending_or_bypasses_wrong_player(prefix):
    async def run():
        trace = ServiceTrace(PACK, 2, "companion-greeting-pending")
        try:
            await trace.playback()
            await trace.text(prefix)
            if prefix == "물가":
                assert trace.state["presentation"]["input_kind"] == "player_name"
            else:
                assert texts(trace.state)[0] == "지금은 민준 차례야. 같은 기회로 다시 해 보자."
            await trace.playback()
            await trace.text("오누야 민준 안녕")
            assert_quiet(trace.state)
            assert trace.state["game"]["history"] == []
            await trace.send("session.resume")
            await trace.playback()
            await trace.text("민준")
            assert trace.state["game"]["history"] == []
        finally:
            trace.close()
    asyncio.run(run())


def test_wrong_player_greeting_keeps_turn_and_does_not_give_extra_budget():
    async def run():
        trace = ServiceTrace(PACK, 2, "companion-greeting-owner")
        try:
            await trace.playback()
            await trace.text("서윤 오누야 안녕")
            assert texts(trace.state)[0] == "지금은 민준 차례야. 같은 기회로 다시 해 보자."
            assert trace.state["game"]["history"] == []
            await trace.playback()
            await trace.text("민준 오누야 뭐해")
            assert_quiet(trace.state)
        finally:
            trace.close()
    asyncio.run(run())


def test_greeting_does_not_reset_shared_help_wait_repeat_budget():
    async def run():
        trace = ServiceTrace(PACK, 1, "companion-greeting-helpers")
        try:
            for phrase in ("도와줘", "오누야 뭐해", "기다려"):
                await trace.playback()
                await trace.text(phrase)
                assert trace.state["status"] == "ACTIVE"
            await trace.playback()
            await trace.text("다시 말해줘")
            assert_quiet(trace.state)
            assert trace.state["game"]["history"] == []
        finally:
            trace.close()
    asyncio.run(run())


def test_a_greeting_before_playback_completion_is_not_a_wake_word():
    async def run():
        trace = ServiceTrace(PACK, 1, "companion-greeting-closed-window")
        try:
            before = copy.deepcopy(trace.state)
            await trace.send("action.submit", {"text": "오누야 안녕", "is_final": True,
                              "capture_id": "early-greeting", "listen_window_id": "not-opened"})
            assert trace.result["steps"][-1]["expected_error"]["code"] == "INPUT_NOT_READY"
            assert trace.state == before
        finally:
            trace.close()
    asyncio.run(run())


@pytest.mark.parametrize("nickname", ["안녕", "오누야 안녕"])
def test_registered_full_name_still_confirms_its_original_pending_choice(nickname):
    async def run():
        trace = ServiceTrace(PACK, 2, "companion-greeting-nickname")
        try:
            trace.state = trace.service.create(trace.family, [
                {"player_id": "p1", "nickname": nickname},
                {"player_id": "p2", "nickname": "서윤"}])
            await trace.playback()
            await trace.text("물가")
            assert trace.state["presentation"]["input_kind"] == "player_name"
            await trace.playback()
            await trace.text(nickname)
            assert [(item["player_id"], item["action_id"]) for item in trace.state["game"]["history"]] == [("p1", "RIVER")]
        finally:
            trace.close()
    asyncio.run(run())
