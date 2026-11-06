"""A saved session uses its authored edition through interleaved requests and async work."""

import asyncio
import copy
import unittest
import uuid

from carplay import game
from carplay.catalog import ContentCatalog
from carplay.contracts import Command
from carplay.service import ServiceError, SessionService
from carplay.settings import Settings
from carplay.storage import Store


class ServiceVersionTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.store = Store(":memory:")
        self.catalog = ContentCatalog()
        self.current = self.catalog.current()
        self.legacy = self.catalog.load(self.current["pack_id"], "0.1.0")
        self.settings = Settings()
        self.old_service = SessionService(self.store, self.settings, self.legacy)
        self.service = SessionService(self.store, self.settings, self.current, pack_lookup=self.catalog.load)
        self.players = [{"player_id": "p1", "nickname": "별"}]

    def tearDown(self):
        self.store.close()

    def request(self, state, kind, payload):
        return Command.model_validate({
            "command_id": str(uuid.uuid4()), "expected_version": state["version"],
            "turn_id": state["turn_id"], "turn_epoch": state["turn_epoch"], "type": kind, "payload": payload,
        })

    async def send(self, state, kind, payload):
        return (await self.service.command("family", state["session_id"], self.request(state, kind, payload)))["snapshot"]

    async def listen(self, state):
        return await self.send(state, "playback.completed", {"playback_id": state["speech_plan"]["playback_id"]})

    def action(self, state, text):
        return self.request(state, "action.submit", {
            "text": text, "is_final": True, "capture_id": str(uuid.uuid4()),
            "listen_window_id": state["listen_window_id"], "locale": "ko-KR", "input_source": "mock",
        })

    async def test_saved_and_new_sessions_keep_prompts_hint_cost_and_receipts(self):
        old = self.old_service.create("family", self.players)
        new = self.service.create("family", self.players)
        self.assertEqual(old["game"]["pack_version"], "0.1.0")
        self.assertEqual(new["game"]["pack_version"], self.current["version"])
        self.assertTrue(old["presentation"]["prompt"].startswith("별 탐험가와 연구원 차례야. "))
        self.assertTrue(new["presentation"]["prompt"].startswith("별, "))
        for state, pack in [(old, self.legacy), (new, self.current), (old, self.legacy)]:
            snapshot = self.service.snapshot("family", state["session_id"])
            self.assertEqual(snapshot["presentation"], {**game.present(state["game"], pack), "input_kind": "choice"})
        self.assertIs(self.service.pack, self.current)

        for state, pack, remaining in [(old, self.legacy, 1), (new, self.current, 5)]:
            ready = await self.listen(state)
            command = self.action(ready, "몰라")
            result = await self.service.command("family", ready["session_id"], command)
            hinted = result["snapshot"]
            self.assertEqual(hinted["game"], game.apply_hint(ready["game"], pack))
            self.assertEqual(hinted["game"]["resources"]["hint_tokens"], remaining)
            self.assertEqual(self.service.receipt("family", state["session_id"], command.command_id), result)
            ready = await self.listen(hinted)
            chosen = (await self.service.command("family", ready["session_id"], self.action(ready, "물가")))["snapshot"]
            self.assertEqual(chosen["game"], game.apply_action(hinted["game"], pack, "p1", "RIVER"))
            paused = await self.send(chosen, "session.pause", {"reason": "QUIET_REQUEST"})
            resumed = await self.send(paused, "session.resume", {})
            self.assertEqual(resumed["game"], chosen["game"])
            self.assertEqual([s["text"] for s in resumed["speech_plan"]["segments"]],
                             [s["text"] for s in chosen["speech_plan"]["segments"]])
            self.assertIs(self.service.pack, self.current)

    async def test_async_legacy_resolution_and_current_request_do_not_swap_content(self):
        started, release = asyncio.Event(), asyncio.Event()

        class Resolver:
            async def resolve(self, text, context):
                started.set()
                await release.wait()
                return {"intent": "ACTION", "declared_player_id": "p1", "action_id": "RIVER", "needs_clarification": False}

        self.service.resolver = Resolver()
        old = await self.listen(self.old_service.create("family", self.players))
        command = self.action(old, "별, 작은 흔적을 살펴볼래")
        pending = asyncio.create_task(self.service.command("family", old["session_id"], command))
        try:
            await asyncio.wait_for(started.wait(), 1)
            new = self.service.create("family", self.players)
            self.assertEqual(new["game"]["pack_version"], self.current["version"])
            self.assertEqual(new["presentation"], {**game.present(new["game"], self.current), "input_kind": "choice"})
            release.set()
            resolved = (await asyncio.wait_for(pending, 1))["snapshot"]
            self.assertEqual(resolved["game"], game.apply_action(old["game"], self.legacy, "p1", "RIVER"))
            self.assertEqual(resolved["presentation"], {**game.present(resolved["game"], self.legacy), "input_kind": "choice"})
            self.assertIs(self.service.pack, self.current)
        finally:
            release.set()
            if not pending.done():
                pending.cancel()
            await asyncio.gather(pending, return_exceptions=True)

    async def test_unknown_or_wrong_lookup_version_preserves_saved_record(self):
        old = self.old_service.create("family", self.players)
        before = self.store.connection.execute("SELECT snapshot_json FROM sessions").fetchone()[0]
        for lookup in [None, lambda *_: self.current, lambda *_: {}]:
            with self.subTest(lookup=lookup):
                service = SessionService(self.store, self.settings, self.current, pack_lookup=lookup)
                with self.assertRaises(ServiceError) as error:
                    service.snapshot("family", old["session_id"])
                self.assertEqual(error.exception.code, "CONTENT_VERSION_UNAVAILABLE")
                self.assertEqual(self.store.connection.execute("SELECT snapshot_json FROM sessions").fetchone()[0], before)
                self.assertIs(service.pack, self.current)

        def missing(*_):
            raise KeyError("unavailable")

        self.service.pack_lookup = missing
        with self.assertRaises(ServiceError) as error:
            self.service.snapshot("family", old["session_id"])
        self.assertEqual(error.exception.code, "CONTENT_VERSION_UNAVAILABLE")
        self.assertEqual(self.store.connection.execute("SELECT snapshot_json FROM sessions").fetchone()[0], before)

    async def test_historical_control_like_nickname_can_load_and_start_a_new_edition(self):
        players = [{"player_id": "p1", "nickname": "몰라"}]
        old = self.old_service.create("family", players)
        self.assertEqual(self.service.snapshot("family", old["session_id"])["game"], old["game"])
        for created in [old, self.service.create("family", players)]:
            ready = await self.listen(created)
            result = (await self.service.command("family", ready["session_id"], self.action(ready, "몰라, 물가")))["snapshot"]
            self.assertEqual(result["game"]["history"][0]["player_id"], "p1")
            self.assertEqual(result["game"]["history"][0]["action_id"], "RIVER")

    def test_legacy_hint_penalty_remains_one_and_current_help_has_no_penalty(self):
        for pack, earned in [(self.legacy, 1), (self.current, 2)]:
            state = game.new_game(self.players, pack)
            for action in ["FOREST", "READ_LABEL"]:
                state = game.apply_action(state, pack, "p1", action)
            before = copy.deepcopy(state)
            hinted = game.apply_hint(state, pack)
            self.assertEqual(state, before)
            answered = game.apply_action(hinted, pack, "p1", "LEAVES")
            self.assertEqual(answered["history"][-1]["score_delta"], earned)


if __name__ == "__main__":
    unittest.main()
