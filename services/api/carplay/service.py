"""Transactional turn coordinator. All external model work happens outside SQL locks."""

from __future__ import annotations

import asyncio
import copy
import hashlib
import json
import sqlite3
import time
import uuid
from collections.abc import Callable

from . import game
from .contracts import Command, SemanticCandidate
from .semantic import DisabledResolver, SemanticResolver
from .settings import Settings
from .storage import Store, encode


def uid() -> str:
    return str(uuid.uuid4())


class ServiceError(Exception):
    def __init__(self, status: int, code: str, message: str, session_id: str | None = None):
        super().__init__(code)
        self.status, self.code, self.message, self.session_id = status, code, message, session_id


class SessionService:
    def __init__(self, store: Store, settings: Settings, pack: dict,
                 resolver: SemanticResolver | None = None, clock: Callable[[], float] = time.time,
                 pack_lookup: Callable[[str, str], dict] | None = None):
        self.store, self.settings, self.pack = store, settings, pack
        self.resolver = resolver or DisabledResolver()
        self.clock = clock
        self.pack_lookup = pack_lookup

    def _pack_for(self, state: dict) -> dict:
        """Resolve this session's immutable content without changing the new-session default."""
        identity = (state["game"].get("pack_id"), state["game"].get("pack_version"))
        if identity == (self.pack.get("pack_id"), self.pack.get("version")):
            return self.pack
        try:
            pack = self.pack_lookup(*identity) if self.pack_lookup is not None else None
        except KeyError:
            pack = None
        if pack is None or identity != (pack.get("pack_id"), pack.get("version")):
            raise ServiceError(409, "CONTENT_VERSION_UNAVAILABLE", "진행 중인 콘텐츠 버전이 필요해요.", state["session_id"])
        return pack

    def _read(self, db: sqlite3.Connection, family: str, session_id: str) -> dict:
        row = db.execute("SELECT snapshot_json FROM sessions WHERE session_id=? AND family_id=?", (session_id, family)).fetchone()
        if not row:
            raise ServiceError(404, "SESSION_NOT_FOUND", "세션을 찾을 수 없어요.")
        state = json.loads(row[0])
        # New nickname rules do not invalidate an already saved family's identity.
        self._pack_for(state)
        return state

    def _public(self, state: dict) -> dict:
        result = copy.deepcopy({k: v for k, v in state.items() if not k.startswith("_")})
        result["presentation"] = game.present(state["game"], self._pack_for(state))
        result["presentation"]["input_kind"] = "player_name" if state["status"] == "ACTIVE" and state.get("_pending_action") else "choice"
        return result

    def _persist(self, db: sqlite3.Connection, state: dict, event_type: str, payload: dict | None = None) -> None:
        state["version"] += 1
        state["last_seq"] += 1
        db.execute("UPDATE sessions SET snapshot_json=? WHERE session_id=?", (encode(state), state["session_id"]))
        db.execute("INSERT INTO session_events VALUES (?,?,?,?)", (
            state["session_id"], state["last_seq"], event_type,
            encode({"version": state["version"], "turn_id": state["turn_id"], "turn_epoch": state["turn_epoch"], **(payload or {})}),
        ))

    def _plan(self, state: dict, prefix: str | None = None, include_prompt: bool = True) -> None:
        view = game.present(state["game"], self._pack_for(state))
        if prefix is None:
            prefix = state.get("_scene_feedback")
        texts = [x for x in (prefix, view["prompt"] if include_prompt else None) if x]
        self._plan_texts(state, texts)

    def _plan_texts(self, state: dict, texts: list[str]) -> None:
        # Server-authored narration is retained until delivery; child utterances never are.
        state["_unheard_texts"] = texts
        state["speech_plan"] = {
            "playback_id": uid(), "turn_id": state["turn_id"], "turn_epoch": state["turn_epoch"],
            "segments": [{"segment_id": uid(), "text": text, "required": True} for text in texts],
        }
        state["listen_window_id"] = None
        state["phase"] = "FEEDBACK" if state["game"].get("finished") else "PROMPTING"

    def _receipt(self, db: sqlite3.Connection, state: dict, command_id: str, body_hash: str,
                 status: str = "APPLIED") -> dict:
        snapshot = self._public(state)
        db.execute("""INSERT INTO command_receipts(session_id,command_id,body_hash,status,result_json)
                      VALUES (?,?,?,?,?) ON CONFLICT(session_id,command_id)
                      DO UPDATE SET status=excluded.status,result_json=excluded.result_json""",
                   (state["session_id"], command_id, body_hash, status, encode(snapshot) if status != "PENDING" else None))
        return {"command_id": command_id, "command_status": status, "snapshot": snapshot}

    def _lookup_receipt(self, db: sqlite3.Connection, state: dict, command_id: str,
                        body_hash: str | None = None) -> dict | None:
        row = db.execute("SELECT * FROM command_receipts WHERE session_id=? AND command_id=?",
                         (state["session_id"], command_id)).fetchone()
        if not row:
            return None
        if body_hash is not None and row["body_hash"] != body_hash:
            raise ServiceError(409, "COMMAND_ID_REUSED", "같은 명령 번호에 다른 요청을 보낼 수 없어요.", state["session_id"])
        return {"command_id": command_id, "command_status": row["status"],
                "snapshot": json.loads(row["result_json"]) if row["result_json"] else self._public(state)}

    def _cancel_resolution_receipt(self, db: sqlite3.Connection, state: dict, resolution: dict | None) -> None:
        if resolution:
            db.execute("UPDATE command_receipts SET status='CANCELLED',result_json=? WHERE session_id=? AND command_id=? AND status='PENDING'",
                       (encode(self._public(state)), state["session_id"], resolution["command_id"]))

    def _pause(self, db: sqlite3.Connection, state: dict, reason: str) -> None:
        resolution = state.pop("_resolution", None)
        pending_action = state.pop("_pending_action", None)
        if pending_action and state.get("_unheard_texts"):
            # A resume must not ask for only a name after that tentative choice
            # has been discarded. Keep delivery pending, with the full choices.
            state["_unheard_texts"] = [game.present(state["game"], self._pack_for(state))["prompt"]]
        state["status"] = "PAUSED"
        state["phase"] = "PROMPTING"
        state["turn_epoch"] += 1
        state["speech_plan"] = None
        state["listen_window_id"] = None
        state["requires_explicit_resume"] = True
        if state.get("pause_reason") not in {"QUIET_REQUEST", "NO_RESPONSE"}:
            state["pause_reason"] = reason
        self._persist(db, state, "session.paused", {"reason": state["pause_reason"]})
        self._cancel_resolution_receipt(db, state, resolution)

    def _expire(self, family: str, session_id: str) -> None:
        # A committed, short transaction makes expiry survive a later stale-command 409.
        with self.store.transaction() as db:
            state = self._read(db, family, session_id)
            pending = state.get("_resolution")
            if pending and pending["deadline"] <= self.clock():
                self._pause(db, state, "MODEL_TIMEOUT")

    def create(self, family: str, players: list[dict]) -> dict:
        state = {
            "session_id": uid(), "version": 1, "last_seq": 1,
            "turn_id": uid(), "turn_epoch": 1, "status": "ACTIVE", "phase": "PROMPTING",
            "pause_reason": None, "requires_explicit_resume": False,
            "listen_window_id": None, "speech_plan": None, "playback_checkpoint": None,
            "game": game.new_game(players, self.pack), "_captures": [], "_clarifications": 0, "_recovery_requests": 0,
            "_scene_feedback": "",
        }
        self._plan(state, prefix="둘이 함께할 때는 내 이름을 먼저 말하고, 고른 말을 이어서 말해 줘." if len(players) == 2 else "")
        with self.store.transaction() as db:
            db.execute("INSERT INTO sessions VALUES (?,?,?)", (state["session_id"], family, encode(state)))
            db.execute("INSERT INTO session_events VALUES (?,?,?,?)", (
                state["session_id"], 1, "session.created", encode({"version": 1, "turn_id": state["turn_id"], "turn_epoch": 1}),
            ))
        return self._public(state)

    def snapshot(self, family: str, session_id: str) -> dict:
        self._expire(family, session_id)
        with self.store.transaction() as db:
            return self._public(self._read(db, family, session_id))

    def receipt(self, family: str, session_id: str, command_id: str) -> dict:
        self._expire(family, session_id)
        with self.store.transaction() as db:
            state = self._read(db, family, session_id)
            result = self._lookup_receipt(db, state, command_id)
            if result is None:
                raise ServiceError(404, "COMMAND_NOT_FOUND", "아직 확인된 명령 기록이 없어요.", session_id)
            return result

    def events(self, family: str, session_id: str, after_seq: int) -> dict:
        self._expire(family, session_id)
        with self.store.transaction() as db:
            state = self._read(db, family, session_id)
            rows = db.execute("SELECT seq,type,payload_json FROM session_events WHERE session_id=? AND seq>? ORDER BY seq LIMIT 200",
                              (session_id, after_seq)).fetchall()
            items = [{"seq": row["seq"], "type": row["type"], "payload": json.loads(row["payload_json"])} for row in rows]
            return {"events": items, "last_seq": state["last_seq"],
                    "next_after_seq": items[-1]["seq"] if items else after_seq}

    def _check_version(self, state: dict, command: Command) -> None:
        if command.expected_version != state["version"]:
            raise ServiceError(409, "STALE_VERSION", "진행 상태를 다시 확인해 주세요.", state["session_id"])
        if command.turn_id != state["turn_id"] or command.turn_epoch != state["turn_epoch"]:
            raise ServiceError(409, "STALE_TURN", "이미 지나간 차례의 요청이에요.", state["session_id"])

    def _clarify(self, state: dict, candidate: dict) -> bool:
        """Return True when this question's one automatic clarification is spent."""
        state["_clarifications"] += 1
        if state["_clarifications"] >= 2:
            state.pop("_pending_action", None)
            state.pop("_unheard_texts", None)
            return True
        action_id = candidate.get("action_id")
        allowed = {a["id"] for a in game.present(state["game"], self._pack_for(state))["actions"]}
        if action_id in allowed and candidate.get("declared_player_id") is None and state["_clarifications"] == 1:
            state["_pending_action"] = action_id
            self._plan(state, "무엇을 할지는 들었어. 누구의 선택인지 이름만 말해 줄래?", include_prompt=False)
            return False
        state.pop("_pending_action", None)
        # A concrete choice prompt replaces repeated free-form interrogation.
        if candidate.get("reason") == "repair_requested":
            prefix = "아직 선택하지 않았어. 원하는 선택을 다시 말해 줘."
        elif candidate.get("reason") == "address_only":
            prefix = "응, 여기 있어."
        elif candidate.get("reason") == "unconfirmed_expansion":
            prefix = "그 말은 내가 잘 이해하지 못했어. 다시 물어볼게."
        elif candidate.get("reason") == "wrong_player":
            expected = state["game"]["expected_player_id"]
            name = next(p["nickname"] for p in state["game"]["players"] if p["player_id"] == expected)
            prefix = f"지금은 {name} 차례야. 같은 기회로 다시 해 보자."
        else:
            prefix = "어느 쪽인지 내가 잘 모르겠어. 다시 물어볼게."
        self._plan(state, prefix)
        return False

    def _candidate(self, state: dict, candidate: dict) -> tuple[str, dict]:
        intent = candidate.get("intent", "UNKNOWN")
        if intent == "PAUSE":
            return "PAUSE", {}
        if intent == "END":
            return "END", {}
        if intent in {"HINT", "REPEAT", "WAIT"}:
            # Wait/repeat retain an understood choice, never an inferred owner.
            # Asking for help still returns to the complete choice question.
            if intent == "HINT":
                state.pop("_pending_action", None)
            # Help and repeat are useful, but cannot extend a question forever.
            # Do not reset the separate clarification count on these controls.
            state["_recovery_requests"] = state.get("_recovery_requests", 0) + 1
            if state["_recovery_requests"] > 2:
                state.pop("_pending_action", None)
                state.pop("_unheard_texts", None)
                return "PAUSE", {"reason": "NO_RESPONSE"}
            if intent == "HINT":
                try:
                    pack = self._pack_for(state)
                    state["game"] = game.apply_hint(state["game"], pack)
                    hint = game.present(state["game"], pack).get("hint") or "선택지를 천천히 살펴보자."
                except game.GameError:
                    hint = "사용할 힌트가 남아 있지 않아. 선택지를 다시 들려줄게."
                self._plan(state, hint)
            elif intent == "WAIT":
                prompt = ("천천히 생각해도 괜찮아. 누구의 선택인지 이름만 말해 줄래?"
                          if state.get("_pending_action") else "천천히 생각해도 괜찮아.")
                self._plan(state, prompt, include_prompt=False)
            elif state.get("_pending_action"):
                # The current question asks only for an owner, not another choice.
                self._plan(state, "무엇을 할지는 들었어. 누구의 선택인지 이름만 말해 줄래?", include_prompt=False)
            else:
                # Repeat the complete current question, not the previous choice's feedback.
                self._plan(state, prefix="")
            return "control.applied", {"intent": intent}
        if (candidate.get("reason") == "missing_action" and candidate.get("action_id") is None
                and candidate.get("declared_player_id") and state.get("_pending_action")):
            candidate = {**candidate, "intent": "ACTION", "action_id": state["_pending_action"], "needs_clarification": False}
        actor = candidate.get("declared_player_id")
        if len(state["game"]["players"]) == 1 and actor is None:
            actor = state["game"]["players"][0]["player_id"]
        if actor is not None and actor != state["game"]["expected_player_id"]:
            if self._clarify(state, {**candidate, "reason": "wrong_player"}):
                return "PAUSE", {"reason": "NO_RESPONSE"}
            return "input.clarification", {"reason": "wrong_player"}
        if intent != "ACTION" and not (candidate.get("intent") == "ACTION"):
            if self._clarify(state, candidate):
                return "PAUSE", {"reason": "NO_RESPONSE"}
            return "input.clarification", {"reason": "unknown_action"}
        if candidate.get("needs_clarification") or actor is None or not candidate.get("action_id"):
            if self._clarify(state, candidate):
                return "PAUSE", {"reason": "NO_RESPONSE"}
            return "input.clarification", {"reason": "unconfirmed_action"}
        try:
            next_game = game.apply_action(state["game"], self._pack_for(state), actor, candidate["action_id"])
        except game.GameError:
            if self._clarify(state, {"reason": "not_allowed"}):
                return "PAUSE", {"reason": "NO_RESPONSE"}
            return "input.clarification", {"reason": "not_allowed"}
        state["game"] = next_game
        state["_scene_feedback"] = next_game.get("feedback", "")
        state["turn_id"] = uid()
        state["_captures"] = []
        state["_clarifications"] = 0
        state["_recovery_requests"] = 0
        state.pop("_pending_action", None)
        self._plan(state, next_game.get("feedback"))
        return "action.applied", {"actor_id": actor, "action_id": candidate["action_id"], "scene_id": next_game["scene_id"]}

    async def command(self, family: str, session_id: str, command: Command) -> dict:
        body_hash = hashlib.sha256(encode(command.model_dump()).encode()).hexdigest()
        self._expire(family, session_id)
        work: dict | None = None
        with self.store.transaction() as db:
            state = self._read(db, family, session_id)
            duplicate = self._lookup_receipt(db, state, command.command_id, body_hash)
            if duplicate is not None:
                return duplicate
            self._check_version(state, command)
            if state["status"] == "ENDED":
                raise ServiceError(409, "SESSION_ENDED", "이 모험은 끝났어요.", session_id)
            if command.type == "session.pause":
                self._pause(db, state, command.payload["reason"])
                return self._receipt(db, state, command.command_id, body_hash)
            if command.type == "session.end":
                resolution = state.pop("_resolution", None)
                state.update(status="ENDED", speech_plan=None, listen_window_id=None, requires_explicit_resume=True)
                state["turn_epoch"] += 1
                self._persist(db, state, "session.ended")
                self._cancel_resolution_receipt(db, state, resolution)
                return self._receipt(db, state, command.command_id, body_hash)
            if command.type == "session.resume":
                if state["status"] != "PAUSED":
                    raise ServiceError(409, "NOT_PAUSED", "이미 진행 중이에요.", session_id)
                state.update(status="ACTIVE", pause_reason=None, requires_explicit_resume=False)
                state["turn_epoch"] += 1
                state["turn_id"] = uid()
                state["_captures"] = []
                state["_clarifications"] = 0
                state["_recovery_requests"] = 0
                unheard = state.get("_unheard_texts")
                if unheard:
                    texts = list(unheard)
                    if not state["game"].get("finished"):
                        # An interrupted WAIT retains only its short reassurance.
                        # Reorient before listening, including already saved pauses.
                        prompt = game.present(state["game"], self._pack_for(state))["prompt"]
                        if texts[-1] != prompt:
                            texts.append(prompt)
                    self._plan_texts(state, texts)
                else:
                    self._plan(state, prefix="")
                self._persist(db, state, "session.resumed")
                return self._receipt(db, state, command.command_id, body_hash)
            if state["status"] != "ACTIVE":
                raise ServiceError(409, "SESSION_PAUSED", "이어하기를 먼저 요청해 주세요.", session_id)
            if command.type == "playback.completed":
                plan = state.get("speech_plan")
                if state["phase"] not in {"PROMPTING", "FEEDBACK"} or not plan or plan["playback_id"] != command.payload["playback_id"]:
                    raise ServiceError(409, "STALE_PLAYBACK", "현재 안내의 재생 완료가 아니에요.", session_id)
                state["playback_checkpoint"] = {"playback_id": plan["playback_id"], "segment_ids": [x["segment_id"] for x in plan["segments"]]}
                state["speech_plan"] = None
                state.pop("_unheard_texts", None)
                if state["game"].get("finished"):
                    state.update(status="ENDED", phase="FEEDBACK", listen_window_id=None)
                else:
                    state.update(phase="LISTENING", listen_window_id=uid())
                self._persist(db, state, "playback.completed", {"playback_id": plan["playback_id"]})
                return self._receipt(db, state, command.command_id, body_hash)
            # Only action.submit remains. Local stop must use session.pause, so it never waits here.
            payload = command.payload
            if state["phase"] != "LISTENING" or payload["listen_window_id"] != state["listen_window_id"]:
                raise ServiceError(409, "INPUT_NOT_READY", "현재 답변 창을 확인해 주세요.", session_id)
            if payload["capture_id"] in state["_captures"]:
                raise ServiceError(409, "CAPTURE_REUSED", "이미 처리한 인식 결과예요.", session_id)
            state["_captures"] = (state["_captures"] + [payload["capture_id"]])[-64:]
            candidate = game.interpret(payload["text"], state["game"], self._pack_for(state))
            needs_model = (candidate.get("intent") == "UNKNOWN" and not candidate.get("action_id")
                           and candidate.get("reason") not in {"missing_player", "missing_action", "overlap", "multiple_players", "repair_requested", "unconfirmed_expansion", "address_only"}
                           and not isinstance(self.resolver, DisabledResolver))
            if not needs_model:
                event_type, event_payload = self._candidate(state, candidate)
                if event_type == "PAUSE":
                    self._pause(db, state, event_payload.get("reason", "QUIET_REQUEST"))
                elif event_type == "END":
                    state.update(status="ENDED", speech_plan=None, listen_window_id=None, requires_explicit_resume=True)
                    state["turn_epoch"] += 1
                    self._persist(db, state, "session.ended")
                else:
                    self._persist(db, state, event_type, event_payload)
                return self._receipt(db, state, command.command_id, body_hash)
            resolution = {"resolution_id": uid(), "command_id": command.command_id,
                          "version": state["version"] + 1, "turn_epoch": state["turn_epoch"],
                          "deadline": self.clock() + self.settings.resolution_lease_seconds}
            state.update(phase="RESOLVING", speech_plan=None, listen_window_id=None, _resolution=resolution)
            self._persist(db, state, "input.resolving")
            self._receipt(db, state, command.command_id, body_hash, "PENDING")
            view = game.present(state["game"], self._pack_for(state))
            work = {"resolution": resolution, "text": payload["text"], "rule_player": candidate.get("declared_player_id"),
                    "context": {"prompt": view["prompt"], "actions": [{"id": a["id"], "label": a["label"]} for a in view["actions"]],
                                "players": [{"player_id": p["player_id"], "nickname": p["nickname"]} for p in state["game"]["players"]]}}
        # No SQL transaction or lock crosses this await.
        try:
            raw = await asyncio.wait_for(self.resolver.resolve(work["text"], work["context"]), timeout=self.settings.semantic_timeout_seconds)
            candidate = SemanticCandidate.model_validate(raw).model_dump()
        except Exception:
            candidate = {"intent": "UNKNOWN", "declared_player_id": work["rule_player"], "action_id": None, "needs_clarification": True}
        finally:
            work.pop("text", None)
        with self.store.transaction() as db:
            state = self._read(db, family, session_id)
            resolution = state.get("_resolution")
            if (not resolution or resolution["resolution_id"] != work["resolution"]["resolution_id"]
                    or state["version"] != work["resolution"]["version"]
                    or state["turn_epoch"] != work["resolution"]["turn_epoch"]):
                result = self._lookup_receipt(db, state, command.command_id, body_hash)
                if result is not None:
                    return result
                raise ServiceError(409, "RESOLUTION_CANCELLED", "이전 해석 작업이 취소됐어요.", session_id)
            if resolution["deadline"] <= self.clock():
                self._pause(db, state, "MODEL_TIMEOUT")
                return self._lookup_receipt(db, state, command.command_id, body_hash)
            state.pop("_resolution", None)
            # Speaker attribution is grounded in declared names found by deterministic parsing.
            # A model's unsupported guess can never claim the expected player's turn.
            candidate["declared_player_id"] = work["rule_player"]
            if len(state["game"]["players"]) > 1 and not work["rule_player"]:
                candidate["needs_clarification"] = True
            event_type, event_payload = self._candidate(state, candidate)
            if event_type == "PAUSE":
                self._pause(db, state, event_payload.get("reason", "QUIET_REQUEST"))
            else:
                self._persist(db, state, event_type, event_payload)
            return self._receipt(db, state, command.command_id, body_hash)
