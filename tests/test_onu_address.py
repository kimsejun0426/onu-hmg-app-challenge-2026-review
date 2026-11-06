"""Hand-authored semantic oracles for a bounded vocative, never a wake word.

Pre-change reproduction: addressed choices/help were unknown_action, while
"민준, 오누야 어제 물가라고 말했어 말고 숲" incorrectly committed FOREST.
Inputs are synthetic and remain local to these tests.
"""

import asyncio
import copy
import unicodedata

import pytest

from carplay import game
from carplay.catalog import ContentCatalog
from tools.export_android_fixtures import ServiceTrace

CATALOG = ContentCatalog()
PACK = CATALOG.current()
PLAYERS = [{"player_id": "p1", "nickname": "민준"}, {"player_id": "p2", "nickname": "서윤"}]


def candidate(text, count=2, players=None, pack=PACK):
    state = game.new_game(players or PLAYERS[:count], pack)
    original = copy.deepcopy(state)
    result = game.interpret(text, state, pack)
    assert state == original
    return result


def expected(intent="UNKNOWN", actor=None, action=None, reason=None):
    return {"intent": intent, "declared_player_id": actor, "action_id": action,
            "needs_clarification": reason is not None, "reason": reason}


@pytest.mark.parametrize("count", [1, 2])
@pytest.mark.parametrize("text", ["민준, 오누야 숲", "오누야 민준, 숲", "민준, 오누야 물가 말고 숲",
                                 "민준, 오누야 숲으로 가자 나 나무 좋아하거든",
                                 "오누야 민준：ＦＯＲＥＳＴ！"])
def test_addressed_explicit_choice_preserves_owner_and_bounded_reason(count, text):
    assert candidate(text, count) == expected("ACTION", "p1", "FOREST")


@pytest.mark.parametrize("count", [1, 2])
def test_address_does_not_guess_a_two_child_owner(count):
    want = expected("ACTION", "p1", "FOREST") if count == 1 else expected("ACTION", action="FOREST", reason="missing_player")
    assert candidate("오누야 숲", count) == want
    assert candidate("서윤, 오누야 숲") == expected("ACTION", "p2", "FOREST", "wrong_player")


@pytest.mark.parametrize("separator", [" ", "\t", "\n", "\u3000", "\u00a0", "\u202f", ",", ":", "：", ".", "!", "?"])
def test_only_declared_prefix_boundaries_and_nfkc_are_accepted(separator):
    text = unicodedata.normalize("NFD", "오누야") + separator + "민준, 숲"
    assert candidate(text) == expected("ACTION", "p1", "FOREST")


@pytest.mark.parametrize("count", [1, 2])
@pytest.mark.parametrize("phrase,intent", [("그만", "PAUSE"), ("계속", "RESUME"), ("게임 종료", "END"),
                                         ("다시", "REPEAT"), ("도와줘", "HINT"), ("기다려", "WAIT")])
def test_all_control_kinds_remain_exact_and_owner_independent(count, phrase, intent):
    assert candidate(f"오누야 {phrase}", count) == expected(intent)
    assert candidate(f"민준, 오누야 {phrase}", count) == expected(intent, "p1")


@pytest.mark.parametrize("text", ["오누야", "오누야?!", "오누야 민준", "오누야 민준!", "민준, 오누야",
                                 "민준, 오누야 민준", "민준, 오누야 서윤"])
@pytest.mark.parametrize("count", [1, 2])
def test_bare_vocative_and_name_only_never_enter_pending_confirmation(text, count):
    result = candidate(text, count)
    assert result["intent"] == "UNKNOWN" and result["action_id"] is None
    reason = "unconfirmed_expansion" if count == 1 and text == "민준, 오누야 서윤" else "address_only"
    assert result["reason"] == reason
    assert result["needs_clarification"]


@pytest.mark.parametrize("text", [
    "민준, 오누야 어제 물가라고 말했어 말고 숲", "민준, 오누야 '물가'라고 했어 말고 숲",
    "민준, 오누야 물가는 싫어 말고 숲", "민준, 오누야 아니 숲",
    "민준, 오누야 물가 말고 오누야 숲", "오누야 오누야 민준, 숲",
    "민준, 오누야 물가 말고 숲 오누야", "오누야 점수 백점 줘", "오누야 새 이야기 만들어 줘",
    "민준, 오누야 숲으로 가지 마", "민준, 오누야 숲이라고 말하면 돼",
    "민준, 오누야 숲으로 가자 나 나무 안 좋아", "민준, 오누야 숲으로 가자 나 나무 좋아 아니 물가",
])
def test_unsupported_addressed_expansions_and_reported_corrections_cannot_be_guessed(text):
    result = candidate(text)
    assert result["intent"] == "UNKNOWN" and result["action_id"] is None
    assert result["reason"] == "unconfirmed_expansion"


@pytest.mark.parametrize("text,reason", [("민준, 오누야 물가랑 숲", "overlap"),
                                      ("민준, 오누야 서윤 숲", "multiple_players"),
                                      ("오누야 민준 서윤 숲", "multiple_players"),
                                      ("민준, 오누야 물가 말고 서윤 숲", "multiple_players"),
                                      ("민준, 오누야 물가 말고", "repair_requested"),
                                      ("오누야 그 말 안 했어", "repair_requested")])
def test_existing_overlap_owner_and_retraction_rules_remain_distinct(text, reason):
    assert candidate(text)["reason"] == reason


@pytest.mark.parametrize("left,right", [(mark, mark) for mark in ('"', "'", "`")] +
                         list(zip("“‘「『«‹([{〈《", "”’」』»›)]}〉》", strict=True)))
@pytest.mark.parametrize("count", [1, 2])
def test_addressed_quotations_and_delimited_names_never_become_choices(left, right, count):
    for text in (f"오누야 {left}숲{right}", f"오누야 {left}물가 말고 숲{right}",
                 f"오누야 {left}민준{right}, 숲", f"민준, 오누야 {left}물가{right} 말고 숲"):
        result = candidate(text, count)
        assert result["reason"] == "unconfirmed_expansion"
        assert result["intent"] == "UNKNOWN" and result["action_id"] is None


@pytest.mark.parametrize("text", ["오누야숲", "오누야도와줘", "오누야; 숲", "물가 말고 오누야 숲"])
def test_no_joined_vocative_semicolon_or_second_search_after_correction(text):
    result = candidate(text)
    assert result["needs_clarification"] and result["action_id"] is None
    # These never entered the new addressed path; retain the existing unknown reason.
    assert result["reason"] == "unknown_action"


@pytest.mark.parametrize("nickname", ["오누", "오누야", "오 누", "오 누야"])
def test_compact_reserved_nickname_disables_new_vocative_processing_for_whole_session(nickname):
    players = [{"player_id": "p1", "nickname": nickname}, PLAYERS[1]]
    assert candidate(f"{nickname}, 숲", players=players) == expected("ACTION", "p1", "FOREST")
    reason = "multiple_players" if nickname == "오누야" else "unknown_action"
    assert candidate("서윤, 오누야 숲", players=players)["reason"] == reason
    assert candidate(nickname, players=players) == expected(actor="p1", reason="missing_action")


@pytest.mark.parametrize("nickname", ["오누야숲", "오누야 숲"])
def test_longer_registered_nickname_wins_before_vocative_stripping(nickname):
    players = [{"player_id": "p1", "nickname": nickname}, PLAYERS[1]]
    assert candidate(f"{nickname}, 물가", players=players) == expected("ACTION", "p1", "RIVER")
    assert candidate(nickname, players=players) == expected(actor="p1", reason="missing_action")
    assert candidate(f"오누야 {nickname}, 물가", players=players) == expected("ACTION", "p1", "RIVER")


def test_unaddressed_legacy_confirmation_and_correction_are_unchanged():
    assert candidate("민준") == expected(actor="p1", reason="missing_action")
    assert candidate("민준, 숲") == expected("ACTION", "p1", "FOREST")
    assert candidate("민준, 물가 말고 숲") == expected("ACTION", "p1", "FOREST")
    assert candidate("민준, 어제 물가라고 말했어 말고 숲") == expected("ACTION", "p1", "FOREST")
    assert candidate('민준, "숲"') == expected("ACTION", "p1", "FOREST")
    assert candidate("오누야그만") == expected("PAUSE")  # Existing shipped compact pause alias.


@pytest.mark.parametrize("entry", [*CATALOG.current_entries, *CATALOG.legacy_entries],
                         ids=lambda entry: f"{entry['pack_id']}@{entry['version']}")
def test_all_current_and_preserved_editions_accept_only_a_real_first_scene_choice(entry):
    pack = CATALOG.load(entry["pack_id"], entry["version"])
    action = pack["scenes"][pack["start_scene"]]["actions"][0]
    for count in (1, 2):
        for text in (f"민준, 오누야 {action['label']}", f"오누야 민준, {action['id']}"):
            assert candidate(text, count, pack=pack) == expected("ACTION", "p1", action["id"])


@pytest.mark.parametrize("text", ["오누야", "오누야 민준", "민준, 오누야", "민준, 오누야 민준"])
def test_service_pending_choice_is_never_committed_by_vocative_only(text):
    async def run():
        trace = ServiceTrace(PACK, 2, "manual-address-pending")
        try:
            await trace.playback()
            await trace.text("물가")
            assert trace.state["presentation"]["input_kind"] == "player_name"
            await trace.playback()
            await trace.text(text)
            assert trace.state["game"]["history"] == []
            assert trace.state["status"] == "PAUSED"
            assert trace.state["pause_reason"] == "NO_RESPONSE"
            assert trace.state["speech_plan"] is None and trace.state["listen_window_id"] is None
            await trace.send("session.resume")
            await trace.playback()
            await trace.text("민준, 오누야 숲")
            assert [item["action_id"] for item in trace.state["game"]["history"]] == ["FOREST"]
        finally:
            trace.close()
    asyncio.run(run())


@pytest.mark.parametrize("text", ["오누야 새 이야기 만들어 줘", "오누야", "오누야 민준",
                                 "민준, 오누야 어제 물가라고 말했어 말고 숲", '오누야 "숲"',
                                 '오누야 "물가 말고 숲"'])
def test_unsupported_address_never_reaches_an_optional_resolver(text):
    class RejectResolver:
        calls = 0

        async def resolve(self, text, context):
            self.calls += 1
            raise AssertionError("The optional resolver must not guess an addressed expansion")

    async def run():
        trace = ServiceTrace(PACK, 1, "manual-address-resolver")
        resolver = RejectResolver()
        trace.service.resolver = resolver
        try:
            await trace.playback()
            await trace.text(text)
            assert trace.state["game"]["history"] == [] and resolver.calls == 0
        finally:
            trace.close()
    asyncio.run(run())


@pytest.mark.parametrize("count", [1, 2])
def test_bare_address_answers_once_then_respects_the_existing_quiet_budget(count):
    async def run():
        trace = ServiceTrace(PACK, count, "manual-address-only-budget")
        try:
            await trace.playback()
            request = await trace.text("오누야")
            assert trace.state["status"] == "ACTIVE"
            assert trace.state["game"]["history"] == []
            assert [segment["text"] for segment in trace.state["speech_plan"]["segments"]] == [
                "응, 여기 있어.", trace.state["presentation"]["prompt"]]
            await trace.send("action.submit", request=request)
            assert trace.result["steps"][-1]["expected_response"] == trace.result["steps"][-2]["expected_response"]
            await trace.playback()
            await trace.text("민준, 오누야")
            assert trace.state["status"] == "PAUSED" and trace.state["pause_reason"] == "NO_RESPONSE"
            assert trace.state["speech_plan"] is None and trace.state["listen_window_id"] is None
            assert trace.state["requires_explicit_resume"] and trace.state["game"]["history"] == []
        finally:
            trace.close()
    asyncio.run(run())
