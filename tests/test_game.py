"""Behavioral tests for the complete, finite, deterministic story."""

import copy
import unittest

from carplay.game import GameError, apply_action, apply_hint, interpret, load_pack, new_game, present, validate_pack


class GameTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.pack = load_pack()

    def players(self, count=2):
        return [{"player_id": "p1", "nickname": "민준"}, {"player_id": "p2", "nickname": "서윤"}][:count]

    def start(self, count=2):
        return new_game(self.players(count), self.pack)

    def advance(self, state, action):
        return apply_action(state, self.pack, state["expected_player_id"], action)

    def walk(self, path, count=2):
        state = self.start(count)
        for action in path:
            state = self.advance(state, action)
        return state

    def test_complete_two_player_adventure_has_balanced_opportunities(self):
        state = self.walk(["RIVER", "FOLLOW_TRACKS", "LEAVES", "MAGNIFIER", "TRICERATOPS", "WORKSHOP"])
        self.assertTrue(state["finished"])
        self.assertEqual(state["ending"], "FOUND_TOGETHER")
        self.assertEqual(state["score_by_player"], {"p1": 4, "p2": 4})
        self.assertEqual([x["player_id"] for x in state["history"]], ["p1", "p2", "p1", "p2", "p1", "p2"])
        self.assertIsNone(state["expected_player_id"])
        self.assertEqual(present(state, self.pack)["actions"], [])

    def test_solo_can_own_all_actions(self):
        state = self.walk(["FOREST", "READ_LABEL", "LEAVES", "LISTENING_DEVICE", "TRICERATOPS", "GREENHOUSE"], 1)
        self.assertEqual(state["score_by_player"], {"p1": 8})
        self.assertTrue(all(event["player_id"] == "p1" for event in state["history"]))
        self.assertIn("탐험가와 연구원", state["players"][0]["role"])

    def test_early_route_changes_later_consequence(self):
        tail = ["FOLLOW_TRACKS", "LEAVES", "MAGNIFIER", "TRICERATOPS", "GREENHOUSE"]
        forest = self.walk(["FOREST", *tail])
        river = self.walk(["RIVER", *tail])
        self.assertEqual(forest["ending"], "FOUND_TOGETHER")
        self.assertEqual(river["ending"], "TEAM_ASSIST")
        self.assertNotEqual(forest["clues"], river["clues"])

    def test_tool_choice_changes_clue_and_consumes_exactly_once(self):
        state = self.walk(["RIVER", "READ_LABEL", "LEAVES"])
        before = copy.deepcopy(state)
        magnifier = self.advance(state, "MAGNIFIER")
        listening = self.advance(state, "LISTENING_DEVICE")
        self.assertEqual(state, before)
        self.assertEqual(magnifier["resources"]["tool_tokens"], 0)
        self.assertEqual(listening["resources"]["tool_tokens"], 0)
        self.assertNotEqual(magnifier["clues"], listening["clues"])
        with self.assertRaises(GameError) as raised:
            self.advance(magnifier, "MAGNIFIER")
        self.assertEqual(raised.exception.code, "invalid_action")
        self.assertEqual(magnifier["resources"]["tool_tokens"], 0)

    def test_wrong_player_unknown_and_insufficient_resource_do_not_mutate(self):
        state = self.start()
        before = copy.deepcopy(state)
        for actor, action, code in [("p2", "RIVER", "wrong_player"), ("p1", "FLY", "invalid_action")]:
            with self.assertRaises(GameError) as raised:
                apply_action(state, self.pack, actor, action)
            self.assertEqual(raised.exception.code, code)
            self.assertEqual(state, before)
        tool = self.walk(["FOREST", "READ_LABEL", "LEAVES"])
        tool["resources"]["tool_tokens"] = 0
        before = copy.deepcopy(tool)
        with self.assertRaises(GameError) as raised:
            self.advance(tool, "MAGNIFIER")
        self.assertEqual(raised.exception.code, "insufficient_resource")
        self.assertEqual(tool, before)

    def test_name_missing_keeps_normalized_action_for_repair(self):
        candidate = interpret("물가", self.start(), self.pack)
        self.assertEqual(candidate["intent"], "ACTION")
        self.assertEqual(candidate["action_id"], "RIVER")
        self.assertIsNone(candidate["declared_player_id"])
        self.assertTrue(candidate["needs_clarification"])
        self.assertEqual(candidate["reason"], "missing_player")
        name_only = interpret("민준", self.start(), self.pack)
        self.assertEqual(name_only["declared_player_id"], "p1")
        self.assertEqual(name_only["reason"], "missing_action")

    def test_solo_does_not_need_name(self):
        candidate = interpret("숲으로 갈래", self.start(1), self.pack)
        self.assertEqual(candidate["action_id"], "FOREST")
        self.assertEqual(candidate["declared_player_id"], "p1")
        self.assertFalse(candidate["needs_clarification"])

    def test_names_and_correction_are_parsed_in_one_utterance(self):
        state = self.start()
        for utterance in ["민준, 물가 아니 숲", "민준 물가 말고 숲", "민준이는 숲으로 갈래"]:
            with self.subTest(utterance=utterance):
                result = interpret(utterance, state, self.pack)
                self.assertEqual(result["action_id"], "FOREST")
                self.assertEqual(result["declared_player_id"], "p1")
                self.assertFalse(result["needs_clarification"])

    def test_unfinished_correction_is_not_name_only_confirmation(self):
        state = self.start()
        before = copy.deepcopy(state)
        for text in ["민준, 물가 말고", "민준, 아니", "민준, 물가 말고 민준"]:
            with self.subTest(text=text):
                candidate = interpret(text, state, self.pack)
                self.assertEqual(candidate["reason"], "repair_requested")
                self.assertIsNone(candidate["action_id"])
                self.assertTrue(candidate["needs_clarification"])
                self.assertEqual(state, before)

    def test_correction_cannot_discard_another_childs_name(self):
        state = self.start()
        before = copy.deepcopy(state)
        for text in ["민준, 물가 서윤 말고 숲", "민준, 서윤 물가 말고 숲",
                     "물가 민준 말고 서윤, 숲"]:
            with self.subTest(text=text):
                candidate = interpret(text, state, self.pack)
                self.assertEqual(candidate["reason"], "multiple_players")
                self.assertIsNone(candidate["action_id"])
                self.assertTrue(candidate["needs_clarification"])
                self.assertEqual(state, before)

    def test_explicit_refusal_and_not_knowing_are_exact_controls(self):
        state = self.start()
        for text in ["하기 싫어", "하기싫어", "안 할래", "안할래", "안 하고 싶어", "그만 하고 싶어", "싫어", "싫어요", "오누야 그만"]:
            for prefix in ["", "서윤, "]:
                self.assertEqual(interpret(prefix + text, state, self.pack)["intent"], "PAUSE")
        for text in ["몰라", "잘 몰라", "잘몰라", "민준, 몰라", "모르겠다", "모르겠어요"]:
            self.assertEqual(interpret(text, state, self.pack)["intent"], "HINT")
        for text in ["하기 싫어라고 말하면 돼", "안 할래라는 말을 들었어", "몰라라고 말했어", "오누야 그만하지 마", "싫어하지 않아"]:
            self.assertEqual(interpret(text, state, self.pack)["intent"], "UNKNOWN")

    def test_ambiguous_negative_and_overlapping_speech_never_commit(self):
        state = self.start()
        before = copy.deepcopy(state)
        for text in ["민준 물가랑 숲", "민준 물가는 아니야", "민준 물가 서윤 숲", "민준, 숲으로 가지 마", "민준 나는 점수 백점 줘"]:
            with self.subTest(text=text):
                result = interpret(text, state, self.pack)
                self.assertTrue(result["needs_clarification"])
                self.assertEqual(state, before)
        self.assertEqual(interpret("민준 물가 서윤 숲", state, self.pack)["reason"], "multiple_players")
        self.assertEqual(interpret("민준 물가랑 숲", state, self.pack)["reason"], "overlap")

    def test_control_requests_need_no_name_and_not_substring(self):
        state = self.start()
        cases = {"그만": "PAUSE", "그만 해 줘": "PAUSE", "조용히 해 줘": "PAUSE", "민준 조용히": "PAUSE", "그거 다시": "REPEAT", "모르겠어": "HINT", "생각 중이야": "WAIT", "다시 하자": "RESUME", "게임 종료": "END"}
        for utterance, intent in cases.items():
            candidate = interpret(utterance, state, self.pack)
            self.assertEqual(candidate["intent"], intent)
            self.assertFalse(candidate["needs_clarification"])
        self.assertEqual(interpret("그만큼 알고 있어", state, self.pack)["intent"], "UNKNOWN")
        self.assertEqual(interpret("그만 해줘라고 말하면 돼", state, self.pack)["intent"], "UNKNOWN")

    def test_hint_is_repeatable_without_double_cost_or_turn_change(self):
        state = self.walk(["FOREST", "READ_LABEL"])
        original = copy.deepcopy(state)
        hinted = apply_hint(state, self.pack)
        repeated = apply_hint(hinted, self.pack)
        self.assertEqual(state, original)
        self.assertEqual(hinted["resources"]["hint_tokens"], 5)
        self.assertEqual(repeated, hinted)
        self.assertEqual(hinted["scene_id"], state["scene_id"])
        self.assertEqual(hinted["turn_index"], state["turn_index"])
        answered = self.advance(hinted, "LEAVES")
        self.assertEqual(answered["score_by_player"]["p1"], 2)

    def test_explicit_repair_is_not_a_new_action(self):
        state = self.start()
        before = copy.deepcopy(state)
        for text in ["그 말 안 했어", "그말 안했어", "잘못 들었어", "다시 말할게"]:
            for prefix in ["", "민준, "]:
                with self.subTest(text=prefix + text):
                    result = interpret(prefix + text, state, self.pack)
                    self.assertEqual(result["intent"], "UNKNOWN")
                    self.assertEqual(result["reason"], "repair_requested")
                    self.assertIsNone(result["action_id"])
                    self.assertTrue(result["needs_clarification"])
                    self.assertEqual(state, before)

    def test_clear_wrong_knowledge_answer_explains_and_advances(self):
        state = self.walk(["FOREST", "READ_LABEL"])
        result = self.advance(state, "FISH")
        self.assertEqual(result["score_by_player"]["p1"], 0)
        self.assertEqual(result["scene_id"], "tool")
        self.assertIn("식물", result["feedback"])
        self.assertEqual(result["resources"], state["resources"])

    def test_state_has_no_raw_utterance_history(self):
        state = self.start()
        result = interpret("민준, 물가로 갈래", state, self.pack)
        state = self.advance(state, result["action_id"])
        self.assertEqual(set(state["history"][0]), {"scene_id", "player_id", "action_id", "score_delta", "resource_cost"})
        self.assertNotIn("민준, 물가로 갈래", repr(state))

    def test_every_branch_finishes_in_six_actions_without_resource_dead_end(self):
        pending = [self.start()]
        endings = []
        while pending:
            state = pending.pop()
            if state["finished"]:
                endings.append(state)
                self.assertEqual(state["turn_index"], 6)
                self.assertTrue(all(v >= 0 for v in state["resources"].values()))
                continue
            self.assertLess(state["turn_index"], 6)
            for action in present(state, self.pack)["actions"]:
                pending.append(self.advance(state, action["id"]))
        self.assertEqual(len(endings), 64)
        self.assertEqual({s["ending"] for s in endings}, {"FOUND_TOGETHER", "TEAM_ASSIST"})

    def test_content_checker_rejects_missing_reference_cycle_and_sources(self):
        self.assertEqual(validate_pack(self.pack), [])
        broken = copy.deepcopy(self.pack)
        broken["scenes"]["choose_path"]["actions"][0]["next"] = "missing"
        self.assertTrue(any("missing destination" in e for e in validate_pack(broken)))
        cycle = copy.deepcopy(self.pack)
        cycle["scenes"]["diet"]["actions"][0]["next"] = "choose_path"
        self.assertTrue(any("cycle" in e for e in validate_pack(cycle)))
        no_sources = copy.deepcopy(self.pack)
        no_sources["scenes"]["diet"]["source_ids"] = []
        self.assertTrue(any("requires sources" in e for e in validate_pack(no_sources)))

    def test_duplicate_or_reserved_names_and_content_version_rejected(self):
        for players in [[], self.players() * 2, [{"player_id": "p1", "nickname": "민준"}, {"player_id": "p2", "nickname": "민준"}], [{"player_id": "p1", "nickname": "그만"}]]:
            with self.assertRaises(GameError):
                new_game(players, self.pack)
        state = self.start()
        state["pack_version"] = "different"
        with self.assertRaises(GameError) as raised:
            present(state, self.pack)
        self.assertEqual(raised.exception.code, "content_version_mismatch")


if __name__ == "__main__":
    unittest.main()
