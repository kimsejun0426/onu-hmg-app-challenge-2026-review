"""Synthetic answers only: small positive-reason grammar and refusal boundaries."""

import copy

import pytest

from carplay.game import apply_action, interpret, load_pack, new_game


PACK = load_pack()
PLAYERS = [{"player_id": "p1", "nickname": "민준"}, {"player_id": "p2", "nickname": "서윤"}]


@pytest.mark.parametrize("text", [
    "숲으로 가자, 나 나무 좋아하거든",
    "숲으로 갈래 왜냐하면 나무가 좋아",
    "숲 나는 나무가 궁금해",
    "숲 왜냐하면 나는 큰 나무 보고 싶어",
    "숲으로 가자! 내가 나무 좋아해.",
])
@pytest.mark.parametrize("count", [1, 2])
def test_explicit_choice_and_short_reason_changes_only_choice(text, count):
    state = new_game(PLAYERS[:count], PACK)
    before = copy.deepcopy(state)
    utterance = f"민준, {text}" if count == 2 else text
    candidate = interpret(utterance, state, PACK)
    assert candidate == {"intent": "ACTION", "declared_player_id": "p1", "action_id": "FOREST",
                         "needs_clarification": False, "reason": None}
    assert state == before
    chosen = apply_action(state, PACK, candidate["declared_player_id"], candidate["action_id"])
    assert chosen == apply_action(state, PACK, "p1", "FOREST")
    assert utterance not in repr(chosen)
    assert "나무" not in repr(chosen["history"])


@pytest.mark.parametrize("text", [
    "숲으로 가자 나 나무 안 좋아",
    "숲으로 가자 나 나무 싫어",
    "숲으로 가자 나 나무 좋아 아니 물가",
    "숲으로 가자 왜냐하면 물가 말고 나무가 좋아",
    "숲으로 가자 나 물가도 좋아",
    "숲으로 가자 나 서윤도 좋아",
    "숲으로 가자 나 서윤이 나무 좋아하거든",
    "숲으로 가자 나 그만 하는 게 좋아",
    "숲으로 가자 나 다시 하는 게 좋아",
    "숲으로 가자 나 기다려 주는 게 좋아",
    "숲으로 가자 나 쉬는 게 좋아",
    "숲으로 가자 왜냐하면 취소 하는 게 좋아",
    "숲으로 가자 나 나무 좋아 그만",
    "숲으로 가자 나 나무 좋아 물가",
    "숲으로 가자 라고 말하는 게 좋아",
    "숲으로 가자 왜냐하면 내가 아주 커다란 푸른 멋진 나무가 좋아",
])
def test_negation_other_options_names_controls_and_extra_clauses_never_commit(text):
    state = new_game(PLAYERS, PACK)
    before = copy.deepcopy(state)
    candidate = interpret(f"민준, {text}", state, PACK)
    assert candidate["needs_clarification"]
    assert candidate["action_id"] is None
    assert state == before


def test_reason_does_not_guess_speaker_or_override_turn():
    state = new_game(PLAYERS, PACK)
    missing = interpret("숲으로 가자 나 나무 좋아하거든", state, PACK)
    assert missing["reason"] == "missing_player"
    assert missing["action_id"] == "FOREST"
    assert missing["needs_clarification"]
    wrong = interpret("서윤 숲으로 가자 나 나무 좋아하거든", state, PACK)
    assert wrong["reason"] == "wrong_player"
    assert wrong["needs_clarification"]


def test_existing_exact_correction_and_pause_still_work():
    state = new_game(PLAYERS, PACK)
    assert not interpret("민준 물가 말고 숲", state, PACK)["needs_clarification"]
    assert interpret("서윤 그만", state, PACK)["intent"] == "PAUSE"
