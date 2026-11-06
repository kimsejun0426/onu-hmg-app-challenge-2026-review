"""The short picnic remembers chosen play without grading personal preferences."""

from itertools import product

import pytest

from carplay.catalog import ContentCatalog
from carplay.game import apply_action, apply_hint, new_game, present
from content_release_expectations import NEW_PACK_ID

PLAYERS = [{"player_id": "p1", "nickname": "별"}, {"player_id": "p2", "nickname": "달"}]
TOYS = {"KITE": "종이 연", "PINWHEEL": "바람개비", "LEAF_BOAT": "나뭇잎 배", "PAPER_BOAT": "종이배"}
MOTIONS = {"HIGH": "하늘 가까이", "NEAR": "돗자리 곁", "WIDE": "커다란 동그라미", "SMALL": "작은 동그라미"}


@pytest.mark.parametrize("count", [1, 2])
@pytest.mark.parametrize("help_each_turn", [False, True])
@pytest.mark.parametrize("place,spoken_place,toys,motions", [
    ("HILL", "바람 언덕", ["KITE", "PINWHEEL"], ["HIGH", "NEAR"]),
    ("POND", "잔잔한 연못", ["LEAF_BOAT", "PAPER_BOAT"], ["WIDE", "SMALL"]),
])
def test_every_picnic_choice_is_retained_without_scores_or_invented_alternatives(
    count, help_each_turn, place, spoken_place, toys, motions,
):
    pack = ContentCatalog().current(NEW_PACK_ID)
    for toy, motion, goodbye in product(toys, motions, ["FAREWELL", "KEEP_SCENE"]):
        choices = [place, toy, motion, goodbye]
        state = new_game(PLAYERS[:count], pack)
        for step, choice in enumerate(choices):
            view = present(state, pack)
            assert not state["finished"]
            assert view["prompt"].count("?") == 1 and len(view["prompt"]) <= 160
            assert len(view["actions"]) == 2
            assert state["expected_player_id"] == PLAYERS[step % count]["player_id"]
            if help_each_turn:
                state = apply_hint(state, pack)
            state = apply_action(state, pack, state["expected_player_id"], choice)
            assert state["score_by_player"] == {p["player_id"]: 0 for p in PLAYERS[:count]}
        assert state["finished"] and state["turn_index"] == 4
        assert [event["action_id"] for event in state["history"]] == choices
        assert state["flags"] == {"place": place, "toy": toy, "movement": motion, "goodbye": goodbye}
        assert state["resources"]["hint_tokens"] == (0 if help_each_turn else 4)
        assert state["ending"] == ("SHORT_FAREWELL" if goodbye == "FAREWELL" else "QUIET_SCENE")
        ending = present(state, pack)["prompt"]
        assert spoken_place in ending and TOYS[toy] in ending and MOTIONS[motion] in ending
        assert all(label not in ending for key, label in TOYS.items() if key != toy)
        assert len(ending) <= 160
        if goodbye == "KEEP_SCENE":
            assert "조용히 있을게" in ending
            assert "오늘 같이 놀아서 반가웠어" not in ending
        else:
            assert "오누가 인사할게" in ending and "오늘 같이 놀아서 반가웠어" in ending
