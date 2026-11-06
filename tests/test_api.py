"""HTTP-level behavioral checks using one event loop (no threaded TestClient portal)."""

import asyncio
import copy
import json
from pathlib import Path
import tempfile
import unittest
import uuid

import httpx

from carplay import game
from carplay.catalog import ContentCatalog
from carplay.main import create_app
from carplay.settings import Settings


TOKEN = "test-family-one-token-12345"
TOKEN_OTHER = "test-family-two-token-12345"


class ApiTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.db_path = Path(self.tmp.name) / "game.sqlite3"
        self.clock_value = [100.0]
        self.app = create_app(Settings(db_path=self.db_path, demo_tokens={TOKEN: "family-1", TOKEN_OTHER: "family-2"}),
                              clock=lambda: self.clock_value[0])
        self.lifespan = self.app.router.lifespan_context(self.app)
        await self.lifespan.__aenter__()
        self.client = httpx.AsyncClient(transport=httpx.ASGITransport(app=self.app), base_url="http://test",
                                       headers={"Authorization": f"Bearer {TOKEN}"})

    async def asyncTearDown(self):
        await self.client.aclose()
        await self.lifespan.__aexit__(None, None, None)
        self.tmp.cleanup()

    async def create(self, count=2):
        players = [{"player_id": "p1", "nickname": "민준"}, {"player_id": "p2", "nickname": "서윤"}][:count]
        response = await self.client.post("/v1/sessions", json={"players": players})
        self.assertEqual(response.status_code, 201, response.text)
        return response.json()

    def envelope(self, snapshot, kind, payload, command_id=None):
        return {"command_id": command_id or str(uuid.uuid4()), "expected_version": snapshot["version"],
                "turn_id": snapshot["turn_id"], "turn_epoch": snapshot["turn_epoch"], "type": kind, "payload": payload}

    async def send(self, snapshot, kind, payload=None, command_id=None):
        request = self.envelope(snapshot, kind, payload or {}, command_id)
        response = await self.client.post(f"/v1/sessions/{snapshot['session_id']}/commands", json=request)
        self.assertEqual(response.status_code, 200, response.text)
        return response.json()["snapshot"]

    async def listen(self, snapshot):
        return await self.send(snapshot, "playback.completed", {"playback_id": snapshot["speech_plan"]["playback_id"]})

    def action_payload(self, snapshot, text):
        return {"text": text, "is_final": True, "capture_id": str(uuid.uuid4()),
                "listen_window_id": snapshot["listen_window_id"], "locale": "ko-KR", "input_source": "mock"}

    async def act(self, snapshot, text):
        return await self.send(snapshot, "action.submit", self.action_payload(snapshot, text))

    async def snapshot(self, snapshot):
        response = await self.client.get(f"/v1/sessions/{snapshot['session_id']}/snapshot")
        self.assertEqual(response.status_code, 200, response.text)
        return response.json()

    async def walk(self, path, count=2):
        state = await self.create(count)
        for action_id in path:
            state = await self.listen(state)
            expected = state["game"]["expected_player_id"]
            name = next(p["nickname"] for p in state["game"]["players"] if p["player_id"] == expected)
            label = next(a["label"] for a in state["presentation"]["actions"] if a["id"] == action_id)
            state = await self.act(state, f"{name}, {label}")
        return state

    async def test_both_endings_complete_only_after_final_playback(self):
        for destination, ending in [("WORKSHOP", "FOUND_TOGETHER"), ("GREENHOUSE", "TEAM_ASSIST")]:
            state = await self.walk(["RIVER", "FOLLOW_TRACKS", "LEAVES", "MAGNIFIER", "TRICERATOPS", destination])
            self.assertEqual(state["game"]["ending"], ending)
            self.assertEqual(state["status"], "ACTIVE")
            self.assertEqual(state["phase"], "FEEDBACK")
            state = await self.listen(state)
            self.assertEqual(state["status"], "ENDED")
            self.assertEqual(len(state["game"]["history"]), 6)

    async def test_new_two_player_intro_is_delivered_once_without_changing_story_prompt(self):
        intro = "둘이 함께할 때는 내 이름을 먼저 말하고, 고른 말을 이어서 말해 줘."
        for count in (1, 2):
            with self.subTest(count=count):
                initial = await self.create(count)
                prompt = initial["presentation"]["prompt"]
                texts = ([intro] if count == 2 else []) + [prompt]
                self.assertNotIn(intro, prompt)
                self.assertEqual([s["text"] for s in initial["speech_plan"]["segments"]], texts)
                paused = await self.send(initial, "session.pause", {"reason": "QUIET_REQUEST"})
                restored = await self.send(paused, "session.resume")
                self.assertEqual([s["text"] for s in restored["speech_plan"]["segments"]], texts)
                ready = await self.listen(restored)
                paused = await self.send(ready, "session.pause", {"reason": "QUIET_REQUEST"})
                resumed = await self.send(paused, "session.resume")
                self.assertEqual([s["text"] for s in resumed["speech_plan"]["segments"]], [prompt])
                repeated = await self.act(await self.listen(resumed), "다시")
                self.assertEqual([s["text"] for s in repeated["speech_plan"]["segments"]], [prompt])
                chosen = await self.act(await self.listen(repeated), "민준, 물가")
                self.assertTrue(all(intro not in s["text"] for s in chosen["speech_plan"]["segments"]))
                self.assertEqual(len(chosen["game"]["history"]), 1)

    async def test_resume_after_completed_feedback_reads_only_current_question(self):
        delivered = await self.walk(["RIVER"], count=1)
        old_playback = delivered["speech_plan"]["playback_id"]
        self.assertEqual(len(delivered["speech_plan"]["segments"]), 2)
        ready = await self.listen(delivered)
        paused = await self.send(ready, "session.pause", {"reason": "QUIET_REQUEST"})
        request = self.envelope(paused, "session.resume", {})
        url = f"/v1/sessions/{paused['session_id']}/commands"
        response = await self.client.post(url, json=request)
        self.assertEqual(response.status_code, 200, response.text)
        resumed = response.json()["snapshot"]
        self.assertEqual([s["text"] for s in resumed["speech_plan"]["segments"]],
                         [ready["presentation"]["prompt"]])
        self.assertEqual(resumed["game"], ready["game"])
        self.assertNotEqual(resumed["speech_plan"]["playback_id"], old_playback)
        self.assertEqual((await self.client.post(url, json=request)).json(), response.json())
        stale = await self.client.post(url, json=self.envelope(
            resumed, "playback.completed", {"playback_id": old_playback}))
        self.assertEqual(stale.status_code, 409)
        self.assertEqual(stale.json()["error"]["code"], "STALE_PLAYBACK")
        self.assertEqual(await self.snapshot(resumed), resumed)
        self.assertEqual((await self.listen(resumed))["phase"], "LISTENING")

    async def test_resume_preserves_undelivered_feedback_and_ending(self):
        paths = [["RIVER"], ["RIVER", "FOLLOW_TRACKS", "LEAVES", "MAGNIFIER", "TRICERATOPS", "WORKSHOP"]]
        for path in paths:
            with self.subTest(path=path):
                pending = await self.walk(path)
                texts = [s["text"] for s in pending["speech_plan"]["segments"]]
                paused = await self.send(pending, "session.pause", {"reason": "QUIET_REQUEST"})
                resumed = await self.send(paused, "session.resume")
                self.assertEqual([s["text"] for s in resumed["speech_plan"]["segments"]], texts)
                self.assertNotEqual(resumed["speech_plan"]["playback_id"], pending["speech_plan"]["playback_id"])
                self.assertEqual(resumed["game"], pending["game"])
                self.assertEqual(resumed["status"], "ACTIVE")
                completed = await self.listen(resumed)
                self.assertEqual(completed["status"], "ENDED" if len(path) == 6 else "ACTIVE")
                self.assertEqual(len(completed["game"]["history"]), len(path))

    async def test_resume_after_name_confirmation_does_not_revive_choice_or_old_feedback(self):
        for delivered_name_prompt in (False, True):
            with self.subTest(delivered_name_prompt=delivered_name_prompt):
                ready = await self.listen(await self.walk(["RIVER"]))
                pending = await self.act(ready, "바퀴 자국")
                self.assertEqual(pending["presentation"]["input_kind"], "player_name")
                if delivered_name_prompt:
                    pending = await self.listen(pending)
                paused = await self.send(pending, "session.pause", {"reason": "QUIET_REQUEST"})
                resumed = await self.send(paused, "session.resume")
                self.assertEqual(resumed["presentation"]["input_kind"], "choice")
                self.assertEqual([s["text"] for s in resumed["speech_plan"]["segments"]],
                                 [ready["presentation"]["prompt"]])
                self.assertEqual(resumed["game"], ready["game"])
                name_only = await self.act(await self.listen(resumed), "서윤")
                self.assertEqual(name_only["game"], ready["game"])
                chosen = await self.act(await self.listen(name_only), "서윤, 바퀴 자국")
                self.assertEqual([h["action_id"] for h in chosen["game"]["history"]],
                                 ["RIVER", "FOLLOW_TRACKS"])

    async def test_resume_after_recovery_budget_reads_question_without_previous_result(self):
        ready = await self.listen(await self.walk(["RIVER"], count=1))
        first = await self.act(ready, "딸기 케이크")
        paused = await self.act(await self.listen(first), "딸기 케이크")
        self.assertEqual(paused["pause_reason"], "NO_RESPONSE")
        resumed = await self.send(paused, "session.resume")
        self.assertEqual([s["text"] for s in resumed["speech_plan"]["segments"]],
                         [ready["presentation"]["prompt"]])
        self.assertEqual(resumed["game"], ready["game"])
        chosen = await self.act(await self.listen(resumed), "바퀴 자국")
        self.assertEqual([h["action_id"] for h in chosen["game"]["history"]], ["RIVER", "FOLLOW_TRACKS"])

    async def test_interrupted_wait_resume_restores_question_once_without_applying_action(self):
        for count, path, answer in [(1, [], "물가"), (2, ["RIVER"], "서윤, 바퀴 자국")]:
            with self.subTest(count=count, path=path):
                ready = await self.listen(await self.walk(path, count=count))
                waiting = await self.act(ready, "기다려")
                wait_text = "천천히 생각해도 괜찮아."
                prompt = ready["presentation"]["prompt"]
                self.assertEqual([s["text"] for s in waiting["speech_plan"]["segments"]], [wait_text])
                self.assertEqual(waiting["game"], ready["game"])
                self.assertIsNone(waiting["listen_window_id"])
                # Interrupt the WAIT, then interrupt its recovery again before delivery.
                # Neither interruption may apply a choice or append the same question twice.
                for _ in range(2):
                    previous_playback = waiting["speech_plan"]["playback_id"]
                    paused = await self.send(waiting, "session.pause", {"reason": "QUIET_REQUEST"})
                    self.assertIsNone(paused["speech_plan"])
                    self.assertIsNone(paused["listen_window_id"])
                    waiting = await self.send(paused, "session.resume")
                    self.assertEqual([s["text"] for s in waiting["speech_plan"]["segments"]], [wait_text, prompt])
                    self.assertNotEqual(waiting["speech_plan"]["playback_id"], previous_playback)
                    self.assertEqual(waiting["game"], ready["game"])
                    self.assertIsNone(waiting["listen_window_id"])
                listening = await self.listen(waiting)
                self.assertEqual(listening["phase"], "LISTENING")
                self.assertNotEqual(listening["listen_window_id"], ready["listen_window_id"])
                chosen = await self.act(listening, answer)
                self.assertEqual([h["action_id"] for h in chosen["game"]["history"]],
                                 path + (["RIVER"] if not path else ["FOLLOW_TRACKS"]))

    async def test_delivered_wait_stays_short_and_does_not_replay_on_resume(self):
        ready = await self.listen(await self.create(1))
        waiting = await self.act(ready, "기다려")
        self.assertEqual([s["text"] for s in waiting["speech_plan"]["segments"]], ["천천히 생각해도 괜찮아."])
        listening = await self.listen(waiting)
        self.assertEqual(listening["phase"], "LISTENING")
        self.assertEqual(listening["game"], ready["game"])
        paused = await self.send(listening, "session.pause", {"reason": "QUIET_REQUEST"})
        resumed = await self.send(paused, "session.resume")
        self.assertEqual([s["text"] for s in resumed["speech_plan"]["segments"]], [ready["presentation"]["prompt"]])

    async def test_persisted_wait_resume_uses_saved_edition_without_new_state_fields(self):
        ready = await self.listen(await self.create(1))
        paused = await self.send(ready, "session.pause", {"reason": "QUIET_REQUEST"})
        legacy = ContentCatalog().load(paused["game"]["pack_id"], "0.1.0")
        saved_game = game.new_game([{"player_id": "p1", "nickname": "민준"}], legacy)
        saved_prompt = game.present(saved_game, legacy)["prompt"]
        self.assertNotEqual(saved_prompt, ready["presentation"]["prompt"])
        # Seed the existing paused WAIT representation directly, without calling the
        # current WAIT handler or adding a new flag, then reopen the persistent store.
        with self.app.state.service.store.transaction() as db:
            state = json.loads(db.execute("SELECT snapshot_json FROM sessions WHERE session_id=?",
                                          (paused["session_id"],)).fetchone()[0])
            state.update(game=saved_game, _unheard_texts=["천천히 생각해도 괜찮아."], _recovery_requests=1)
            self.assertNotIn("_pending_action", state)
            db.execute("UPDATE sessions SET snapshot_json=? WHERE session_id=?",
                       (json.dumps(state, ensure_ascii=False), paused["session_id"]))
        self.app.state.service.store.close()
        self.app.state.service = None
        restored = await self.snapshot(paused)
        self.assertEqual(restored["status"], "PAUSED")
        self.assertIsNone(restored["speech_plan"])
        self.assertIsNone(restored["listen_window_id"])
        resumed = await self.send(restored, "session.resume")
        self.assertEqual([s["text"] for s in resumed["speech_plan"]["segments"]],
                         ["천천히 생각해도 괜찮아.", saved_prompt])
        self.assertEqual(resumed["game"], saved_game)
        self.assertEqual((await self.listen(resumed))["phase"], "LISTENING")

    async def test_duplicate_action_and_playback_do_not_repeat_effects(self):
        state = await self.create()
        playback = self.envelope(state, "playback.completed", {"playback_id": state["speech_plan"]["playback_id"]})
        url = f"/v1/sessions/{state['session_id']}/commands"
        first = await self.client.post(url, json=playback)
        again = await self.client.post(url, json=playback)
        self.assertEqual(first.json(), again.json())
        ready = first.json()["snapshot"]
        action = self.envelope(ready, "action.submit", self.action_payload(ready, "민준, 물가"))
        response = await self.client.post(url, json=action)
        retried = await self.client.post(url, json=action)
        self.assertEqual(response.json(), retried.json())
        after = response.json()["snapshot"]
        self.assertNotEqual(ready["turn_id"], after["turn_id"])
        self.assertEqual(len(after["game"]["history"]), 1)
        receipt = await self.client.get(f"{url}/{action['command_id']}")
        self.assertEqual(receipt.json(), response.json())
        events = (await self.client.get(f"/v1/sessions/{state['session_id']}/events")).json()
        self.assertEqual(sum(e["type"] == "action.applied" for e in events["events"]), 1)
        self.assertEqual([e["seq"] for e in events["events"]], list(range(1, after["last_seq"] + 1)))
        changed = copy.deepcopy(action)
        changed["payload"]["text"] = "민준, 숲"
        conflict = await self.client.post(url, json=changed)
        self.assertEqual(conflict.status_code, 409)
        self.assertEqual(conflict.json()["error"]["code"], "COMMAND_ID_REUSED")

    async def test_name_only_repair_preserves_action_without_penalty(self):
        state = await self.listen(await self.create())
        self.assertEqual(state["presentation"]["input_kind"], "choice")
        original_game = state["game"]
        uncertain = await self.act(state, "물가")
        self.assertEqual(uncertain["game"], original_game)
        self.assertEqual(len(uncertain["speech_plan"]["segments"]), 1)
        self.assertIn("이름만", uncertain["speech_plan"]["segments"][0]["text"])
        self.assertEqual(uncertain["presentation"]["input_kind"], "player_name")
        ready = await self.listen(uncertain)
        self.assertEqual(ready["presentation"]["input_kind"], "player_name")
        repaired = await self.act(ready, "민준")
        self.assertEqual(repaired["presentation"]["input_kind"], "choice")
        self.assertEqual(repaired["game"]["history"][0]["action_id"], "RIVER")
        self.assertEqual(repaired["game"]["expected_player_id"], "p2")

    async def test_wait_preserves_name_confirmation_and_duplicate_does_not_spend_budget(self):
        state = await self.listen(await self.create())
        pending = await self.act(state, "물가")
        for _ in range(2):
            ready = await self.listen(pending)
            request = self.envelope(ready, "action.submit", self.action_payload(ready, "기다려"))
            url = f"/v1/sessions/{ready['session_id']}/commands"
            response = await self.client.post(url, json=request)
            self.assertEqual(response.status_code, 200)
            pending = response.json()["snapshot"]
            self.assertEqual(response.json(), (await self.client.post(url, json=request)).json())
            self.assertEqual(pending["presentation"]["input_kind"], "player_name")
            self.assertEqual(pending["game"], state["game"])
            self.assertEqual([s["text"] for s in pending["speech_plan"]["segments"]],
                             ["천천히 생각해도 괜찮아. 누구의 선택인지 이름만 말해 줄래?"])
        ready = await self.listen(pending)
        self.assertNotEqual(ready["listen_window_id"], state["listen_window_id"])
        chosen = await self.act(ready, "민준")
        self.assertEqual([h["action_id"] for h in chosen["game"]["history"]], ["RIVER"])
        self.assertEqual(chosen["game"]["expected_player_id"], "p2")

    async def test_wait_budget_still_discards_pending_before_explicit_resume(self):
        state = await self.listen(await self.create())
        pending = await self.act(state, "물가")
        for _ in range(3):
            pending = await self.act(await self.listen(pending), "기다려")
        self.assertEqual(pending["status"], "PAUSED")
        self.assertEqual(pending["pause_reason"], "NO_RESPONSE")
        self.assertEqual(pending["presentation"]["input_kind"], "choice")
        self.assertIsNone(pending["speech_plan"])
        self.assertIsNone(pending["listen_window_id"])
        resumed = await self.send(pending, "session.resume")
        name_only = await self.act(await self.listen(resumed), "민준")
        self.assertEqual(name_only["game"], state["game"])
        self.assertEqual(name_only["presentation"]["input_kind"], "choice")

    async def test_android_on_device_input_requires_declared_player_before_applying(self):
        state = await self.listen(await self.create())
        payload = self.action_payload(state, "물가")
        payload["input_source"] = "android_on_device"
        uncertain = await self.send(state, "action.submit", payload)
        self.assertEqual(uncertain["game"], state["game"])
        self.assertIn("이름만", uncertain["speech_plan"]["segments"][0]["text"])

        ready = await self.listen(uncertain)
        payload = self.action_payload(ready, "민준")
        payload["input_source"] = "android_on_device"
        repaired = await self.send(ready, "action.submit", payload)
        self.assertEqual(len(repaired["game"]["history"]), 1)
        self.assertEqual(repaired["game"]["history"][0]["action_id"], "RIVER")
        self.assertEqual(repaired["game"]["history"][0]["player_id"], "p1")
        self.assertEqual(repaired["game"]["expected_player_id"], "p2")
        self.assertEqual(repaired["game"]["resources"], state["game"]["resources"])

    async def test_wrong_player_and_unknown_do_not_spend_resources(self):
        state = await self.listen(await self.create())
        for index, text in enumerate(["서윤, 물가", "민준, 우주선을 꺼내"]):
            original = copy.deepcopy(state["game"])
            state = await self.act(state, text)
            self.assertEqual(state["game"], original)
            if index == 0:
                state = await self.listen(state)
        self.assertEqual(state["status"], "PAUSED")
        self.assertEqual(state["pause_reason"], "NO_RESPONSE")
        self.assertIsNone(state["listen_window_id"])
        self.assertIsNone(state["speech_plan"])

    async def test_touch_choice_requires_the_current_declared_player(self):
        state = await self.listen(await self.create())
        payload = self.action_payload(state, "서윤, 물가")
        payload["input_source"] = "touch"
        clarified = await self.send(state, "action.submit", payload)
        self.assertEqual(clarified["game"], state["game"])
        ready = await self.listen(clarified)
        payload = self.action_payload(ready, "민준, 물가")
        payload["input_source"] = "touch"
        chosen = await self.send(ready, "action.submit", payload)
        self.assertEqual(chosen["game"]["history"][0]["player_id"], "p1")
        self.assertEqual(chosen["game"]["history"][0]["action_id"], "RIVER")

    async def test_named_objection_does_not_confirm_pending_action(self):
        state = await self.listen(await self.create())
        uncertain = await self.act(state, "물가")
        objection = await self.act(await self.listen(uncertain), "민준, 그 말 안 했어")
        self.assertEqual(objection["game"], state["game"])
        self.assertEqual(objection["game"]["history"], [])

    async def test_unfinished_correction_discards_pending_without_advancing_or_duplicate_effect(self):
        for correction in ["민준, 물가 말고", "민준, 아니"]:
            with self.subTest(correction=correction):
                state = await self.listen(await self.create())
                uncertain = await self.act(state, "물가")
                ready = await self.listen(uncertain)
                request = self.envelope(ready, "action.submit", self.action_payload(ready, correction))
                url = f"/v1/sessions/{ready['session_id']}/commands"
                response = await self.client.post(url, json=request)
                self.assertEqual(response.status_code, 200)
                repaired = response.json()["snapshot"]
                self.assertEqual(repaired["game"], state["game"])
                self.assertEqual(repaired["turn_id"], state["turn_id"])
                self.assertEqual(repaired["turn_epoch"], state["turn_epoch"] + 1)
                self.assertEqual(repaired["status"], "PAUSED")
                self.assertEqual(repaired["pause_reason"], "NO_RESPONSE")
                self.assertIsNone(repaired["speech_plan"])
                self.assertIsNone(repaired["listen_window_id"])
                self.assertEqual(response.json(), (await self.client.post(url, json=request)).json())
                stale = await self.client.post(url, json=self.envelope(
                    ready, "action.submit", self.action_payload(ready, "민준, 물가")))
                self.assertEqual(stale.status_code, 409)
                self.assertEqual(stale.json()["error"]["code"], "STALE_VERSION")
                resumed = await self.send(repaired, "session.resume")
                name_only = await self.act(await self.listen(resumed), "민준")
                self.assertEqual(name_only["game"], state["game"])
                chosen = await self.act(await self.listen(name_only), "민준, 물가 말고 숲")
                self.assertEqual([h["action_id"] for h in chosen["game"]["history"]], ["FOREST"])

    async def test_mixed_owner_corrections_bypass_the_model_and_discard_pending(self):
        class MustNotRun:
            called = False

            async def resolve(self, text, context):
                self.called = True
                raise AssertionError("Ambiguous speaker repair must not reach the model")

        resolver = MustNotRun()
        for text in ["민준, 물가 서윤 말고 숲", "민준, 서윤 물가 말고 숲", "민준, 물가 말고"]:
            state = await self.listen(await self.create())
            self.app.state.service.resolver = resolver
            uncertain = await self.act(state, "물가")
            repaired = await self.act(await self.listen(uncertain), text)
            self.assertEqual(repaired["game"], state["game"])
            self.assertEqual(repaired["status"], "PAUSED")
            self.assertEqual(repaired["pause_reason"], "NO_RESPONSE")
            resumed = await self.send(repaired, "session.resume")
            name_only = await self.act(await self.listen(resumed), "민준")
            self.assertEqual(name_only["game"], state["game"])
        self.assertFalse(resolver.called)

    async def test_refusal_clears_pending_closes_input_and_needs_explicit_resume(self):
        state = await self.listen(await self.create())
        uncertain = await self.act(state, "물가")
        ready = await self.listen(uncertain)
        payload = self.action_payload(ready, "서윤, 안 할래")
        request = self.envelope(ready, "action.submit", payload)
        url = f"/v1/sessions/{ready['session_id']}/commands"
        response = await self.client.post(url, json=request)
        self.assertEqual(response.status_code, 200)
        paused = response.json()["snapshot"]
        self.assertEqual(paused["status"], "PAUSED")
        self.assertEqual(paused["pause_reason"], "QUIET_REQUEST")
        self.assertEqual(paused["game"], state["game"])
        self.assertEqual(paused["turn_id"], state["turn_id"])
        self.assertEqual(paused["turn_epoch"], ready["turn_epoch"] + 1)
        self.assertIsNone(paused["speech_plan"])
        self.assertIsNone(paused["listen_window_id"])
        self.assertTrue(paused["requires_explicit_resume"])
        self.assertEqual(response.json(), (await self.client.post(url, json=request)).json())
        self.assertEqual((await self.snapshot(paused))["status"], "PAUSED")
        late = await self.client.post(url, json=self.envelope(paused, "action.submit", self.action_payload(ready, "민준")))
        self.assertEqual(late.status_code, 409)
        self.assertEqual(late.json()["error"]["code"], "SESSION_PAUSED")
        resumed = await self.send(paused, "session.resume")
        late_playback = await self.client.post(url, json=self.envelope(resumed, "playback.completed", {
            "playback_id": uncertain["speech_plan"]["playback_id"]}))
        self.assertEqual(late_playback.status_code, 409)
        self.assertEqual(late_playback.json()["error"]["code"], "STALE_PLAYBACK")
        name_only = await self.act(await self.listen(resumed), "민준")
        self.assertEqual(name_only["game"], state["game"])

    async def test_repeat_during_name_confirmation_keeps_question_choice_and_receipt(self):
        for path, choice, name, action in [([], "물가", "민준", "RIVER"),
                                           (["RIVER"], "바퀴 자국", "서윤", "FOLLOW_TRACKS")]:
            with self.subTest(path=path):
                initial = await self.listen(await self.walk(path))
                pending = await self.act(initial, choice)
                question = [s["text"] for s in pending["speech_plan"]["segments"]]
                ready = await self.listen(pending)
                request = self.envelope(ready, "action.submit", self.action_payload(ready, "다시 말해줘"))
                url = f"/v1/sessions/{ready['session_id']}/commands"
                response = await self.client.post(url, json=request)
                self.assertEqual(response.status_code, 200, response.text)
                repeated = response.json()["snapshot"]
                self.assertEqual(repeated["presentation"]["input_kind"], "player_name")
                self.assertEqual([s["text"] for s in repeated["speech_plan"]["segments"]], question)
                self.assertEqual(repeated["game"], initial["game"])
                self.assertEqual(repeated["turn_id"], initial["turn_id"])
                self.assertEqual(repeated["turn_epoch"], initial["turn_epoch"])
                self.assertIsNone(repeated["listen_window_id"])
                self.assertNotEqual(repeated["speech_plan"]["playback_id"], pending["speech_plan"]["playback_id"])
                self.assertEqual((await self.client.post(url, json=request)).json(), response.json())
                chosen = await self.act(await self.listen(repeated), name)
                self.assertEqual([h["action_id"] for h in chosen["game"]["history"]], path + [action])
                self.assertEqual((await self.client.post(url, json=request)).json(), response.json())
                self.assertEqual(await self.snapshot(chosen), chosen)

    async def test_name_repeat_and_wait_share_budget_without_confirming_an_owner(self):
        for controls in [("다시 말해줘", "기다려"), ("기다려", "다시 말해줘"),
                         ("기다려", "다시 말해줘", "다시 말해줘")]:
            with self.subTest(controls=controls):
                initial = await self.listen(await self.create())
                pending = await self.act(initial, "물가")
                for index, control in enumerate(controls):
                    pending = await self.act(await self.listen(pending), control)
                    self.assertEqual(pending["game"], initial["game"])
                    if index < 2:
                        self.assertEqual(pending["presentation"]["input_kind"], "player_name")
                        self.assertIn("이름만", pending["speech_plan"]["segments"][0]["text"])
                if len(controls) == 2:
                    chosen = await self.act(await self.listen(pending), "민준")
                    self.assertEqual([h["action_id"] for h in chosen["game"]["history"]], ["RIVER"])
                else:
                    self.assertEqual(pending["status"], "PAUSED")
                    self.assertEqual(pending["pause_reason"], "NO_RESPONSE")
                    self.assertEqual(pending["presentation"]["input_kind"], "choice")
                    self.assertIsNone(pending["speech_plan"])
                    self.assertIsNone(pending["listen_window_id"])
                    resumed = await self.send(pending, "session.resume")
                    self.assertEqual([s["text"] for s in resumed["speech_plan"]["segments"]],
                                     [initial["presentation"]["prompt"]])
                    name_only = await self.act(await self.listen(resumed), "민준")
                    self.assertEqual(name_only["game"], initial["game"])

    async def test_hint_or_stop_after_name_repeat_still_discards_pending_choice(self):
        for control in ("모르겠어", "쉬자"):
            with self.subTest(control=control):
                initial = await self.listen(await self.create())
                pending = await self.act(initial, "물가")
                repeated = await self.act(await self.listen(pending), "다시 말해줘")
                self.assertEqual(repeated["presentation"]["input_kind"], "player_name")
                changed = await self.act(await self.listen(repeated), control)
                self.assertEqual(changed["presentation"]["input_kind"], "choice")
                if control == "쉬자":
                    self.assertEqual(changed["status"], "PAUSED")
                    self.assertEqual(changed["pause_reason"], "QUIET_REQUEST")
                    changed = await self.send(changed, "session.resume")
                self.assertEqual(changed["speech_plan"]["segments"][-1]["text"], initial["presentation"]["prompt"])
                name_only = await self.act(await self.listen(changed), "민준")
                self.assertEqual(name_only["game"]["history"], [])
                self.assertEqual(name_only["game"]["expected_player_id"], "p1")

    async def test_not_knowing_offers_a_hint_without_advancing_and_charges_once(self):
        state = await self.listen(await self.create())
        uncertain = await self.act(state, "물가")
        hinted = await self.act(await self.listen(uncertain), "몰라")
        self.assertEqual(hinted["presentation"]["input_kind"], "choice")
        for key in ["history", "score_by_player", "turn_index", "expected_player_id", "scene_id"]:
            self.assertEqual(hinted["game"][key], state["game"][key])
        self.assertEqual(hinted["turn_id"], state["turn_id"])
        self.assertEqual(hinted["game"]["resources"]["hint_tokens"], state["game"]["resources"]["hint_tokens"] - 1)
        repeated = await self.act(await self.listen(hinted), "잘 몰라")
        self.assertEqual(repeated["game"], hinted["game"])
        name_only = await self.act(await self.listen(repeated), "민준")
        self.assertEqual(name_only["game"]["history"], [])

    async def test_pause_during_name_prompt_resumes_full_choices_not_a_discarded_confirmation(self):
        state = await self.listen(await self.create())
        pending = await self.act(state, "물가")
        self.assertIn("이름만", pending["speech_plan"]["segments"][0]["text"])
        paused = await self.send(pending, "session.pause", {"reason": "QUIET_REQUEST"})
        self.assertEqual(paused["presentation"]["input_kind"], "choice")
        resumed = await self.send(paused, "session.resume")
        self.assertEqual(resumed["presentation"]["input_kind"], "choice")
        self.assertEqual([s["text"] for s in resumed["speech_plan"]["segments"]], [state["presentation"]["prompt"]])
        name_only = await self.act(await self.listen(resumed), "민준")
        self.assertEqual(name_only["game"], state["game"])

    async def test_explicit_repair_does_not_reach_model(self):
        class MustNotRun:
            called = False
            async def resolve(self, text, context):
                self.called = True
                return {"intent": "ACTION", "action_id": "RIVER", "declared_player_id": "p1", "needs_clarification": False}
        state = await self.listen(await self.create())
        resolver = MustNotRun()
        self.app.state.service.resolver = resolver
        result = await self.act(state, "민준, 그 말 안 했어")
        self.assertFalse(resolver.called)
        self.assertEqual(result["game"], state["game"])

    async def test_current_window_and_actual_playback_completion_required(self):
        state = await self.create()
        forged = self.action_payload({**state, "listen_window_id": "invented-window"}, "민준, 물가")
        rejected = await self.client.post(f"/v1/sessions/{state['session_id']}/commands",
                                          json=self.envelope(state, "action.submit", forged))
        self.assertEqual(rejected.status_code, 409)
        ready = await self.listen(state)
        wrong_window = self.action_payload(ready, "민준, 물가")
        wrong_window["listen_window_id"] = "old-window"
        rejected = await self.client.post(f"/v1/sessions/{state['session_id']}/commands",
                                          json=self.envelope(ready, "action.submit", wrong_window))
        self.assertEqual(rejected.status_code, 409)
        self.assertEqual((await self.snapshot(ready))["game"]["history"], [])

    async def test_pause_resume_restores_unheard_result_with_new_plan(self):
        state = await self.act(await self.listen(await self.create()), "민준, 물가")
        before = copy.deepcopy(state)
        paused = await self.send(state, "session.pause", {"reason": "QUIET_REQUEST"})
        self.assertIsNone(paused["speech_plan"])
        self.assertTrue(paused["requires_explicit_resume"])
        for _ in range(2):
            self.assertEqual((await self.snapshot(paused))["status"], "PAUSED")
        resumed = await self.send(paused, "session.resume")
        self.assertEqual(resumed["game"], before["game"])
        self.assertEqual([x["text"] for x in resumed["speech_plan"]["segments"]], [x["text"] for x in before["speech_plan"]["segments"]])
        self.assertNotEqual(resumed["speech_plan"]["playback_id"], before["speech_plan"]["playback_id"])
        self.assertGreater(resumed["turn_epoch"], before["turn_epoch"])
        stale = self.envelope(resumed, "playback.completed", {"playback_id": before["speech_plan"]["playback_id"]})
        response = await self.client.post(f"/v1/sessions/{state['session_id']}/commands", json=stale)
        self.assertEqual(response.status_code, 409)

    async def test_quiet_reason_survives_network_pause(self):
        paused = await self.send(await self.create(), "session.pause", {"reason": "QUIET_REQUEST"})
        paused = await self.send(paused, "session.pause", {"reason": "NETWORK"})
        self.assertEqual(paused["pause_reason"], "QUIET_REQUEST")
        self.assertIsNone(paused["speech_plan"])

    async def test_family_isolation_includes_receipts_and_events(self):
        state = await self.create()
        headers = {"Authorization": f"Bearer {TOKEN_OTHER}"}
        base = f"/v1/sessions/{state['session_id']}"
        for path in [f"{base}/snapshot", f"{base}/events", f"{base}/commands/missing"]:
            response = await self.client.get(path, headers=headers)
            self.assertEqual(response.status_code, 404)
        response = await self.client.post(f"{base}/commands", headers=headers,
                                          json=self.envelope(state, "session.pause", {"reason": "QUIET_REQUEST"}))
        self.assertEqual(response.status_code, 404)
        self.assertEqual((await self.snapshot(state))["status"], "ACTIVE")

    async def test_validation_and_persistence_do_not_echo_raw_text(self):
        state = await self.listen(await self.create())
        private = "민준, 오늘 비밀암호는 abc-unique-78329야"
        unknown = await self.act(state, private)
        self.assertNotIn(private, json.dumps(unknown, ensure_ascii=False))
        bad = self.envelope(await self.listen(unknown), "action.submit", self.action_payload(state, private))
        bad["payload"]["unexpected"] = private
        response = await self.client.post(f"/v1/sessions/{state['session_id']}/commands", json=bad)
        self.assertEqual(response.status_code, 422)
        self.assertNotIn(private, response.text)
        with self.app.state.service.store.transaction() as db:
            dump = "\n".join(db.iterdump())
        self.assertNotIn(private, dump)
        self.assertNotIn("abc-unique-78329", dump)

    async def test_invalid_registered_name_returns_sanitized_422(self):
        for name in ["그만", "가" * 13]:
            response = await self.client.post("/v1/sessions", json={"players": [{"player_id": "p1", "nickname": name}]})
            self.assertEqual(response.status_code, 422)
            self.assertNotIn(name, response.text)

    async def test_auth_is_required_and_unconfigured_app_fails_closed(self):
        response = await self.client.get("/v1/sessions/missing/snapshot", headers={"Authorization": "Bearer wrong-token"})
        self.assertEqual(response.status_code, 401)
        app = create_app(Settings(db_path=":memory:"))
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
            self.assertEqual((await client.get("/health/ready")).status_code, 503)
            response = await client.post("/v1/sessions", json={"players": [{"player_id": "p1", "nickname": "민준"}]})
            self.assertEqual(response.status_code, 401)
            self.assertIsNone(app.state.service)

    async def test_hint_repeated_in_same_scene_spends_once(self):
        state = await self.listen(await self.create(1))
        first = await self.act(state, "힌트")
        second = await self.act(await self.listen(first), "힌트")
        self.assertEqual(first["game"]["resources"], second["game"]["resources"])
        self.assertEqual(second["game"]["history"], [])

    async def test_repeat_after_choice_keeps_current_question_without_previous_feedback(self):
        chosen = await self.act(await self.listen(await self.create()), "민준, 물가")
        feedback = chosen["game"]["feedback"]
        prompt = chosen["presentation"]["prompt"]
        self.assertTrue(feedback)
        self.assertEqual([s["text"] for s in chosen["speech_plan"]["segments"]], [feedback, prompt])
        ready = await self.listen(chosen)
        request = self.envelope(ready, "action.submit", self.action_payload(ready, "다시"))
        url = f"/v1/sessions/{ready['session_id']}/commands"
        response = await self.client.post(url, json=request)
        self.assertEqual(response.status_code, 200)
        repeated = response.json()["snapshot"]
        self.assertEqual([s["text"] for s in repeated["speech_plan"]["segments"]], [prompt])
        self.assertEqual(repeated["game"], ready["game"])
        self.assertEqual(repeated["turn_id"], ready["turn_id"])
        self.assertEqual(repeated["turn_epoch"], ready["turn_epoch"])
        self.assertEqual(response.json(), (await self.client.post(url, json=request)).json())

    async def test_unsupported_reason_gets_honest_repair_without_retaining_or_applying_it(self):
        cases = [
            ("민준 숲으로 가자 나 지우개가 빨간색이라서",
             "그 말은 내가 잘 이해하지 못했어. 다시 물어볼게.", "지우개"),
            ("민준 우주선을 꺼내",
             "어느 쪽인지 내가 잘 모르겠어. 다시 물어볼게.", "우주선"),
        ]
        for text, prefix, marker in cases:
            with self.subTest(text=text):
                state = await self.listen(await self.create())
                repaired = await self.act(state, text)
                self.assertEqual(repaired["game"], state["game"])
                self.assertEqual(repaired["turn_id"], state["turn_id"])
                self.assertEqual(repaired["turn_epoch"], state["turn_epoch"])
                self.assertEqual(repaired["presentation"]["input_kind"], "choice")
                self.assertEqual([s["text"] for s in repaired["speech_plan"]["segments"]], [
                    prefix, state["presentation"]["prompt"],
                ])
                db = self.app.state.service.store.connection
                for table, column in [("sessions", "snapshot_json"), ("session_events", "payload_json"),
                                      ("command_receipts", "result_json")]:
                    rows = db.execute(f"SELECT {column} FROM {table}").fetchall()
                    self.assertNotIn(marker, repr([row[0] for row in rows]))
                chosen = await self.act(await self.listen(repaired), "민준 숲")
                self.assertEqual([h["action_id"] for h in chosen["game"]["history"]], ["FOREST"])

    async def test_database_restart_preserves_quiet(self):
        state = await self.send(await self.create(), "session.pause", {"reason": "QUIET_REQUEST"})
        app2 = create_app(Settings(db_path=self.db_path, demo_tokens={TOKEN: "family-1"}))
        async with app2.router.lifespan_context(app2):
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app2), base_url="http://test",
                                        headers={"Authorization": f"Bearer {TOKEN}"}) as client:
                response = await client.get(f"/v1/sessions/{state['session_id']}/snapshot")
                self.assertEqual(response.status_code, 200)
                recovered = response.json()
                self.assertEqual(recovered["status"], "PAUSED")
                self.assertIsNone(recovered["speech_plan"])

    async def test_recovery_controls_share_finite_budget_and_duplicates_are_read_only(self):
        state = await self.listen(await self.create(1))
        state = await self.act(state, "도와줘")
        state = await self.act(await self.listen(state), "다시")
        ready = await self.listen(state)
        request = self.envelope(ready, "action.submit", self.action_payload(ready, "기다려"))
        url = f"/v1/sessions/{ready['session_id']}/commands"
        response = await self.client.post(url, json=request)
        self.assertEqual(response.status_code, 200)
        paused = response.json()["snapshot"]
        self.assertEqual(paused["status"], "PAUSED")
        self.assertEqual(paused["pause_reason"], "NO_RESPONSE")
        self.assertEqual(paused["game"], ready["game"])
        self.assertIsNone(paused["speech_plan"])
        self.assertIsNone(paused["listen_window_id"])
        self.assertTrue(paused["requires_explicit_resume"])
        self.assertEqual(response.json(), (await self.client.post(url, json=request)).json())
        self.assertEqual(paused, await self.snapshot(paused))

    async def test_help_does_not_reset_errors_but_explicit_resume_and_choice_do(self):
        state = await self.listen(await self.create(1))
        failed = await self.act(state, "무슨 소리인지 잘 모르겠는데")
        helped = await self.act(await self.listen(failed), "도와줘")
        repeated = await self.act(await self.listen(helped), "다시")
        paused = await self.act(await self.listen(repeated), "무슨 소리인지 잘 모르겠는데")
        self.assertEqual(paused["status"], "PAUSED")
        self.assertEqual(paused["game"]["history"], [])
        resumed = await self.send(paused, "session.resume")
        self.assertEqual([segment["text"] for segment in resumed["speech_plan"]["segments"]],
                         [resumed["presentation"]["prompt"]])
        failed_again = await self.act(await self.listen(resumed), "아직 잘 모르겠는데")
        self.assertEqual(failed_again["status"], "ACTIVE")
        chosen = await self.act(await self.listen(failed_again), "숲")
        for text in ["도와줘", "다시", "어떻게 하는 걸까"]:
            chosen = await self.act(await self.listen(chosen), text)
            self.assertEqual(chosen["status"], "ACTIVE")
        final = await self.act(await self.listen(chosen), "상자 표지")
        self.assertEqual(len(final["game"]["history"]), 2)

    async def test_unconfirmed_extended_choice_is_not_sent_to_optional_model(self):
        class MustNotRun:
            called = False

            async def resolve(self, text, context):
                self.called = True
                raise AssertionError("Unsupported extended choice must remain unconfirmed")

        resolver = MustNotRun()
        for text in ["민준 숲으로 가자 나 나무 안 좋아", "민준 숲으로 가자 나 나무 좋아 아니 물가"]:
            state = await self.listen(await self.create())
            self.app.state.service.resolver = resolver
            result = await self.act(state, text)
            self.assertEqual(result["game"], state["game"])
        self.assertFalse(resolver.called)

    async def test_short_reason_never_enters_saved_records(self):
        state = await self.listen(await self.create(1))
        text = "숲으로 가자 나 소나무가 좋아하거든"
        result = await self.act(state, text)
        self.assertEqual(result["game"]["history"][0]["action_id"], "FOREST")
        db = self.app.state.service.store.connection
        for table, column in [("sessions", "snapshot_json"), ("session_events", "payload_json"),
                              ("command_receipts", "result_json")]:
            rows = db.execute(f"SELECT {column} FROM {table}").fetchall()
            self.assertNotIn("소나무", repr([row[0] for row in rows]))

    async def test_second_optional_model_rejection_also_closes_input(self):
        class Unknown:
            async def resolve(self, text, context):
                return {"intent": "UNKNOWN", "action_id": None, "declared_player_id": None,
                        "needs_clarification": True}

        state = await self.listen(await self.create(1))
        self.app.state.service.resolver = Unknown()
        first = await self.act(state, "오늘 다른 얘기 해볼까")
        paused = await self.act(await self.listen(first), "더 다른 얘기 해볼까")
        self.assertEqual(paused["status"], "PAUSED")
        self.assertEqual(paused["pause_reason"], "NO_RESPONSE")
        self.assertIsNone(paused["speech_plan"])
        self.assertIsNone(paused["listen_window_id"])
        self.assertEqual(paused["game"], state["game"])
        self.assertEqual(paused, await self.snapshot(paused))

    async def test_model_timeout_is_recoverable_without_state_change(self):
        class Slow:
            async def resolve(self, text, context):
                await asyncio.sleep(1)
        await self._model_case(Slow(), timeout=0.02)

    async def test_malformed_and_invented_model_outputs_cannot_apply(self):
        class Malformed:
            async def resolve(self, text, context):
                return {"intent": "ACTION", "action_id": "RIVER", "declared_player_id": "p1", "needs_clarification": False, "score": 999}
        class Invented:
            async def resolve(self, text, context):
                return {"intent": "ACTION", "action_id": "FLY", "declared_player_id": "p1", "needs_clarification": False}
        await self._model_case(Malformed())
        await self._model_case(Invented())

    async def test_valid_model_candidate_still_uses_engine_rules(self):
        class Valid:
            async def resolve(self, text, context):
                return {"intent": "ACTION", "action_id": "RIVER", "declared_player_id": "p1", "needs_clarification": False}
        state = await self.listen(await self.create())
        self.app.state.service.resolver = Valid()
        result = await self.act(state, "민준, 물이 흐르는 쪽으로 조사하러 가자")
        self.assertEqual(len(result["game"]["history"]), 1)
        self.assertEqual(result["game"]["history"][0]["action_id"], "RIVER")
        self.assertEqual(result["game"]["history"][0]["player_id"], "p1")
        self.assertEqual(result["game"]["expected_player_id"], "p2")

    async def test_model_cannot_guess_two_player_ownership(self):
        class Guess:
            async def resolve(self, text, context):
                return {"intent": "ACTION", "action_id": "RIVER", "declared_player_id": "p1", "needs_clarification": False}
        state = await self.listen(await self.create())
        self.app.state.service.resolver = Guess()
        result = await self.act(state, "물이 흐르는 쪽으로 조사하러 가자")
        self.assertEqual(result["game"], state["game"])
        self.assertIn("이름만", result["speech_plan"]["segments"][0]["text"])

    async def _model_case(self, resolver, timeout=1.0):
        # This test substitutes only the model boundary, not the engine or database.
        state = await self.listen(await self.create())
        self.app.state.service.resolver = resolver
        self.app.state.service.settings = Settings(db_path=self.db_path, demo_tokens={TOKEN: "family-1"},
                                                   semantic_timeout_seconds=timeout, resolution_lease_seconds=3)
        before = copy.deepcopy(state["game"])
        response = await self.act(state, "민준, 투명하게 확대하는 연구 장비를 꺼내 줘")
        self.assertEqual(response["phase"], "PROMPTING")
        self.assertEqual(response["game"], before)

    async def test_pause_cancels_in_flight_model_and_late_result(self):
        await self._blocked_model_case(expire=False)

    async def test_expired_lease_cannot_leave_resolving_forever(self):
        await self._blocked_model_case(expire=True)

    async def _blocked_model_case(self, expire):
        started, finish = asyncio.Event(), asyncio.Event()
        class Blocked:
            async def resolve(self, text, context):
                started.set()
                await finish.wait()
                return {"intent": "ACTION", "action_id": "RIVER", "declared_player_id": "p1", "needs_clarification": False}
        state = await self.listen(await self.create())
        self.app.state.service.resolver = Blocked()
        request = self.envelope(state, "action.submit", self.action_payload(state, "민준, 물이 흐르는 쪽으로 조사하러 가자"))
        url = f"/v1/sessions/{state['session_id']}/commands"
        task = asyncio.create_task(self.client.post(url, json=request))
        await asyncio.wait_for(started.wait(), timeout=1)
        during = await self.snapshot(state)
        self.assertEqual(during["phase"], "RESOLVING")
        duplicate = await self.client.post(url, json=request)
        self.assertEqual(duplicate.json()["command_status"], "PENDING")
        if expire:
            self.clock_value[0] += 10
            paused = await self.snapshot(state)
            self.assertEqual(paused["pause_reason"], "MODEL_TIMEOUT")
        else:
            paused = await self.send(during, "session.pause", {"reason": "QUIET_REQUEST"})
        self.assertEqual(paused["status"], "PAUSED")
        finish.set()
        result = await asyncio.wait_for(task, timeout=2)
        self.assertEqual(result.status_code, 200)
        self.assertEqual(result.json()["command_status"], "CANCELLED")
        after = await self.snapshot(state)
        self.assertEqual(after["game"], state["game"])
        self.assertIsNone(after["speech_plan"])
        self.assertEqual(after["status"], "PAUSED")


if __name__ == "__main__":
    unittest.main()
