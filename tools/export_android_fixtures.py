"""Export deterministic Python-oracle fixtures and the exact Android content asset.

Synthetic utterances belong only to test fixtures. No real microphone, model, token,
network service or persistent family database is used. --check never writes files.
"""

from __future__ import annotations

import argparse
import asyncio
import copy
import hashlib
import json
from pathlib import Path
import sys
import unicodedata

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "services/api"))

from carplay import game
from carplay.catalog import ContentCatalog
from carplay.contracts import Command
from carplay.service import SessionService, ServiceError
from carplay.settings import Settings
from carplay.storage import Store

CONTENT = ROOT / "content/ko-KR/dinosaurs/episode.json"
MAIN_ASSET = ROOT / "apps/vehicle/app/src/main/assets/content/ko-KR/dinosaurs/episode.json"
FIXTURES = ROOT / "apps/vehicle/app/src/androidTest/assets/fixtures"
CATALOG = ROOT / "content/catalog.json"
MAIN_ASSETS = ROOT / "apps/vehicle/app/src/main/assets"
PLAYERS = [{"player_id": "p1", "nickname": "민준"}, {"player_id": "p2", "nickname": "서윤"}]
SUCCESS = ["RIVER", "FOLLOW_TRACKS", "LEAVES", "MAGNIFIER", "TRICERATOPS", "WORKSHOP"]


def encoded(value: object) -> bytes:
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode("utf-8")


def walk(pack: dict, actions: list[str], count: int = 2) -> dict:
    state = game.new_game(PLAYERS[:count], pack)
    for action in actions:
        state = game.apply_action(state, pack, state["expected_player_id"], action)
    return state


def add_request_interpretations(pack: dict, record) -> None:
    """Keep explicit requests and refusal boundaries in Python/Kotlin parity."""
    cases = json.loads((ROOT / "tests/fixtures/authored_request_cases.json").read_text(encoding="utf-8"))["cases"]
    current = ContentCatalog(CATALOG).current(pack["pack_id"])["version"]
    published_request_versions = {"0.2.0", "0.3.0", "0.4.0", "0.5.0"} if pack["pack_id"] == "onu-little-picnic" else {"0.6.0", "0.7.0", "0.8.0", "0.9.0", "0.10.0", "0.11.0"}
    for case in cases:
        editions = set(case.get("versions", {current, *published_request_versions, case["prior_version"]})) | {current}
        if case["pack_id"] != pack["pack_id"] or pack["version"] not in editions:
            continue
        for count in (1, 2):
            state = walk(pack, case["path"], count)
            name = next(p["nickname"] for p in state["players"] if p["player_id"] == state["expected_player_id"])
            text, other = case["text"], case["other"]
            requests = {
                "exact": f"{name}, {text}",
                "joined": f"{name}, {text.replace(' 줘', '줘')}",
                "other": f"{name}, {other}",
                "unnamed": text,
                "competing": f"{name}, {text} 그리고 {other}",
                "negative": f"{name}, {text} 하지 마",
                "unfinished-correction": f"{name}, {text} 말고",
                "repeat-suffix": f"{name}, {text} 다시",
                "pause-suffix": f"{name}, {text} 그만",
                "duplicate": f"{name}, {text} {text}",
                "quotation": f"{name}, 어제 {text}라고 했어",
                "correction": f"{name}, {text} 말고 {other}",
                "repeat-control": "다시",
                "pause-control": "그만",
                "wait-control": "기다려 줘",
            }
            if count == 2:
                other_name = next(p["nickname"] for p in state["players"] if p["nickname"] != name)
                requests.update({
                    "wrong-owner": f"{other_name}, {text}",
                    "mixed-names": f"{name}, {other_name}, {text}",
                    "changed-owner": f"{name}, {text} 말고 {other_name}, {other}",
                })
            for name, text in requests.items():
                record(f"request-{case['id']}-{count}-{name}", state, text)


def add_dinosaur_question_interpretations(
    pack: dict, record, fixture_name: str = "dinosaur_question_answers.json",
    label: str = "question-answer",
) -> None:
    """Independent question-authored examples, including the unmodified prior edition."""
    fixture = json.loads((ROOT / "tests/fixtures" / fixture_name).read_text(encoding="utf-8"))
    if pack["pack_id"] != fixture["pack_id"] or pack["version"] not in {
            fixture["prior_version"], fixture["current_version"],
            ContentCatalog(CATALOG).current(pack["pack_id"])["version"]}:
        return
    for case in fixture["cases"]:
        for count in (1, 2):
            state = walk(pack, case["path"], count)
            name = next(p["nickname"] for p in state["players"] if p["player_id"] == state["expected_player_id"])
            text, other = case["text"], case["other"]
            forms = {
                "named": f"{name}, {text}", "unnamed": text,
                "address-first": f"오누야 {name}, {text}", "address-after-name": f"{name}, 오누야 {text}",
                "negative": f"{name}, {case['negative']}", "both": f"{name}, {case['both']}",
                "duplicate": f"{name}, {text} {text}", "reported": f"{name}, 어제 {text}라고 했어",
                "unfinished": f"{name}, {text} 말고", "correction": f"{name}, {text} 말고 {other}",
                "addressed-correction": f"{name}, 오누야 {text} 말고 {other}",
                "quoted-address": f'{name}, 오누야 "{text}"',
            }
            if count == 2:
                other_name = next(p["nickname"] for p in state["players"] if p["player_id"] != state["expected_player_id"])
                forms.update({"wrong-owner": f"{other_name}, {text}",
                              "two-owners": f"{name}, {other_name}, {text}",
                              "owner-in-correction": f"{name}, {text} 말고 {other_name}, {other}"})
            for suffix, value in forms.items():
                record(f"{label}-{count}-{case['id']}-{suffix}", state, value)


def add_question_hint_interpretations(pack: dict, record) -> None:
    """Question-authored additions and rejection boundaries for both exact editions."""
    fixture = json.loads((ROOT / "tests/fixtures/question_hint_answers.json").read_text(encoding="utf-8"))
    edition = next((e for e in fixture["editions"] if e["pack_id"] == pack["pack_id"]), None)
    if edition is None or pack["version"] not in {
            edition["prior_version"], edition["current_version"],
            ContentCatalog(CATALOG).current(pack["pack_id"])["version"]}:
        return
    for case in fixture["cases"]:
        if case["pack_id"] != pack["pack_id"]:
            continue
        for count in (1, 2):
            state = walk(pack, case["path"], count)
            name = next(p["nickname"] for p in state["players"] if p["player_id"] == state["expected_player_id"])
            text, other = case["text"], case["other"]
            forms = {
                "named": f"{name}, {text}", "unnamed": text,
                "address-first": f"오누야 {name}, {text}", "address-after-name": f"{name}, 오누야 {text}",
                "negative": f"{name}, {text} 하지 마", "both": f"{name}, {text} 그리고 {other}",
                "duplicate": f"{name}, {text} {text}", "reported": f"{name}, 어제 {text}라고 했어",
                "unfinished": f"{name}, {text} 말고", "correction": f"{name}, {text} 말고 {other}",
                "addressed-correction": f"{name}, 오누야 {text} 말고 {other}",
                "quoted-address": f'{name}, 오누야 "{text}"',
                "yes": f"{name}, 응", "no": f"{name}, 아니", "either": f"{name}, 둘 다",
            }
            if count == 2:
                other_name = next(p["nickname"] for p in state["players"] if p["player_id"] != state["expected_player_id"])
                forms.update({"wrong-owner": f"{other_name}, {text}",
                              "two-owners": f"{name}, {other_name}, {text}",
                              "owner-in-correction": f"{name}, {text} 말고 {other_name}, {other}"})
            for suffix, value in forms.items():
                record(f"question-hint-{count}-{case['id']}-{suffix}", state, value)


def add_watch_interpretations(pack: dict, record) -> None:
    """Separate a story observation from the global request for more answer time."""
    if pack["pack_id"] != "animal-footprint-garden" or pack["version"] not in {
            "0.6.0", "0.7.0", "0.8.0", "0.9.0", "0.10.0", "0.11.0",
            ContentCatalog(CATALOG).current(pack["pack_id"])["version"]}:
        return
    for count in (1, 2):
        for route, feature in (("POND", "WEBBED_FEET"), ("FLOWER_GARDEN", "SIX_LEGS")):
            state = walk(pack, [route, "MOVEMENT", "PHOTO_ZOOM", "SAY_ONE", feature], count)
            owner = next(p["nickname"] for p in state["players"] if p["player_id"] == state["expected_player_id"])
            texts = {
                "request": "더 지켜봐 줘", "joined-request": "더 지켜봐줘", "new-label": "더 지켜보기",
                "old-label": "잠깐 기다리기", "old-short": "조금 더", "old-action": "기다릴래",
                "wait": "기다려 줘", "refusal": "하기 싫어", "repeat": "다시", "help": "몰라",
                "competing": "더 지켜봐 줘 그리고 기록 나누기", "negative": "더 지켜봐 줘 하지 마",
                "unfinished": "더 지켜보기 말고", "correction": "더 지켜보기 말고 기록 나누기",
                "reverse-correction": "기록 나누기 말고 더 지켜보기", "control-suffix": "더 지켜봐 줘 기다려",
                "quotation": "어제 더 지켜봐 줘라고 했어", "unclear": "더", "empty": "",
            }
            for key, text in texts.items():
                record(f"watch-{count}-{route}-{key}", state, f"{owner}, {text}" if count == 2 else text)
            if count == 2:
                record(f"watch-{count}-{route}-missing-owner", state, "더 지켜봐 줘")
                record(f"watch-{count}-{route}-wrong-owner", state, "민준, 더 지켜봐 줘")
                record(f"watch-{count}-{route}-mixed-owners", state, "서윤, 민준, 더 지켜봐 줘")


def add_onu_address_interpretations(pack: dict, record) -> None:
    """Exercise the same bounded vocative against actual current and preserved choices."""
    actions = pack["scenes"][pack["start_scene"]]["actions"]
    first, other = actions[0]["label"], actions[1]["label"]
    for count in (1, 2):
        state = walk(pack, [], count)
        texts = {
            "named": f"민준, 오누야 {first}", "prefix": f"오누야 민준, {first}",
            "unnamed": f"오누야 {first}", "bare": "오누야", "named-bare": "민준, 오누야",
            "name-only": "오누야 민준", "repeated-name": "민준, 오누야 민준",
            "joined": f"오누야{first}", "joined-help": "오누야도와줘", "semicolon": f"오누야; {first}",
            "duplicate": f"오누야 오누야 민준, {first}",
            "duplicate-correction": f"민준, 오누야 {other} 말고 오누야 {first}",
            "correction": f"민준, 오누야 {other} 말고 {first}",
            "reported-correction": f"민준, 오누야 어제 {other}라고 했어 말고 {first}",
            "unfinished": f"민준, 오누야 {first} 말고", "negative": f"민준, 오누야 {first} 하지 마",
            "overlap": f"민준, 오누야 {first} 그리고 {other}", "unknown": "오누야 새 이야기 만들어 줘",
            "short-reason": f"민준, 오누야 {first} 나 멋진 이야기가 궁금해",
            "wide-boundary": f"오누야\u3000민준：{first}！",
            "pause": "오누야 그만", "resume": "오누야 계속", "end": "오누야 게임 종료",
            "repeat": "오누야 다시", "hint": "오누야 도와줘", "wait": "오누야 기다려",
            "quoted-correction": f'오누야 "{other} 말고 {first}"',
            "quoted-name": f'오누야 "민준", {first}',
        }
        delimiters = [(mark, mark) for mark in ('"', "'", "`")] + list(zip("“‘「『«‹([{〈《", "”’」』»›)]}〉》", strict=True))
        texts.update({f"quoted-choice-{index}": f"민준, 오누야 {left}{first}{right}"
                      for index, (left, right) in enumerate(delimiters)})
        if count == 2:
            texts.update({"wrong-owner": f"서윤, 오누야 {first}",
                          "mixed-owners": f"민준, 오누야 서윤 {first}"})
        for name, text in texts.items():
            record(f"onu-address-{count}-{name}", state, text)
    for index, nickname in enumerate(("오누", "오누야", "오누야숲", "오누야 숲", "오 누", "오 누야")):
        players = [{"player_id": "p1", "nickname": nickname}, PLAYERS[1]]
        state = game.new_game(players, pack)
        for name, text in {"exact-name": f"{nickname}, {first}", "name-only": nickname,
                           "other-speaker": f"서윤, 오누야 {first}",
                           "address-first": f"오누야 {nickname}, {first}"}.items():
            record(f"onu-address-collision-{index}-{name}", state, text)


def game_fixtures(pack: dict) -> dict:
    paths, representative = [], {}
    for count in (1, 2):
        initial = game.new_game(PLAYERS[:count], pack)

        def visit(state: dict, actions: list[str], steps: list[dict]) -> None:
            representative.setdefault((count, state["scene_id"]), state)
            if state["finished"]:
                paths.append({"id": f"players-{count}-{'-'.join(actions)}", "players": PLAYERS[:count],
                              "actions": actions, "initial_state": initial,
                              "initial_presentation": game.present(initial, pack),
                              "steps": steps, "final_state": state})
                return
            for action in game.present(state, pack)["actions"]:
                before = copy.deepcopy(state)
                after = game.apply_action(state, pack, state["expected_player_id"], action["id"])
                assert state == before, "Python game mutated an input state"
                visit(after, actions + [action["id"]], steps + [{"actor_id": state["expected_player_id"],
                    "action_id": action["id"], "state": after, "presentation": game.present(after, pack)}])

        visit(initial, [], [])
    assert len(paths) == 128 and all(len(path["actions"]) == 6 for path in paths)
    interpretations = []

    def interpret(case_id: str, state: dict, text: str) -> None:
        before = copy.deepcopy(state)
        interpretations.append({"id": case_id, "players": state["players"], "state": state,
                                "text": text, "expected_candidate": game.interpret(text, state, pack)})
        assert state == before

    # Every authored action alias, at both player counts and every reachable scene.
    for (count, scene_id), state in sorted(representative.items()):
        expected = next((p["nickname"] for p in state["players"]
                         if p["player_id"] == state["expected_player_id"]), "민준")
        for action in game.present(state, pack)["actions"]:
            for index, alias in enumerate([action["id"], action["label"], *action["aliases"]]):
                interpret(f"alias-{count}-{scene_id}-{action['id']}-{index}", state, f"{expected}, {alias}")
    texts = ["", "   ", "물가", "민준", "민준, 물가", "민준이가 물가로 갈래", "민준：물가로 갈래！",
             "민준\u3000물가", "민준, ＲＩＶＥＲ", unicodedata.normalize("NFD", "민준, 물가"),
             "민준, 숲 아니 물가", "민준, 숲 말고 물가", "민준, 숲 아니고 물가", "민준, 숲 아니라 물가",
             "민준, 숲 말고 서윤, 물가", "서윤, 물가", "민준 물가랑 숲", "민준 물가 또는 숲",
             "민준 물가 서윤 숲", "민준 서윤 물가", "민준 물가는 아니야", "민준, 숲으로 가지 마",
             "민준 점수 백점 줘", "로봇에게 직접 물어볼래", "그 말 안 했어", "민준, 그말 안했어",
             "잘못 들었어", "민준, 다시 말할게", "그만큼 알고 있어", "그만 해줘라고 말하면 돼", "가" * 1001,
             "민준, 물가 말고", "민준, 아니", "민준, 물가 서윤 말고 숲", "민준, 서윤 물가 말고 숲",
             "하기 싫어", "안 할래", "안 하고 싶어", "그만 하고 싶어", "몰라", "잘 몰라",
             "숲으로 가자 나 나무 좋아하거든", "민준 숲으로 가자 나 나무 좋아하거든",
             "민준 숲 왜냐하면 나는 큰 나무 보고 싶어", "민준 숲 나는 나무가 궁금해",
             "서윤 숲으로 가자 나 나무 좋아하거든", "민준 숲으로 가자 나 물가도 좋아",
             "민준 숲으로 가자 나 나무 안 좋아", "민준 숲으로 가자 나 나무 싫어",
             "민준 숲으로 가자 나 나무 좋아 아니 물가", "민준 숲으로 가자 나 서윤도 좋아",
             "민준 숲으로 가자 왜냐하면 물가 말고 나무가 좋아",
             "민준 숲으로 가자 나 그만 하는 게 좋아", "민준 숲으로 가자 나 다시 하는 게 좋아",
             "민준 숲으로 가자 나 기다려 주는 게 좋아", "민준 숲으로 가자 나 쉬는 게 좋아",
             "민준 숲으로 가자 왜냐하면 취소 하는 게 좋아", "민준 숲으로 가자 나 나무 좋아 그만",
             "민준 숲으로 가자 나 나무 좋아 물가", "민준 숲으로 가자 라고 말하는 게 좋아",
             "민준 숲으로 가자 왜냐하면 내가 아주 커다란 푸른 멋진 나무가 좋아"]
    for count in (1, 2):
        state = walk(pack, [], count)
        for index, text in enumerate(texts):
            interpret(f"edge-{count}-{index}", state, text)
        for intent, aliases in sorted(game.CONTROL_ALIASES.items()):
            for index, alias in enumerate(sorted(aliases)):
                interpret(f"control-{count}-{intent}-{index}", state, alias)
                interpret(f"named-control-{count}-{intent}-{index}", state, f"서윤, {alias}")
        interpret(f"finished-{count}", walk(pack, SUCCESS, count), "민준, 물가")
        interpret(f"finished-pause-{count}", walk(pack, SUCCESS, count), "그만")

    add_request_interpretations(pack, interpret)
    add_dinosaur_question_interpretations(pack, interpret)
    add_dinosaur_question_interpretations(pack, interpret, "short_preference_answers.json", "preference-answer")
    add_question_hint_interpretations(pack, interpret)
    add_watch_interpretations(pack, interpret)
    add_onu_address_interpretations(pack, interpret)
    operations = []

    def operation(case_id: str, kind: str, state: dict, **arguments: str) -> dict:
        before = copy.deepcopy(state)
        item = {"id": case_id, "kind": kind, "state": state, "arguments": arguments}
        try:
            after = (game.apply_hint(state, pack) if kind == "apply_hint" else
                     game.apply_action(state, pack, arguments["actor_id"], arguments["action_id"]))
            item.update(expected_state=after, expected_presentation=game.present(after, pack))
        except game.GameError as error:
            item["expected_error"] = {"code": error.code, "message": error.message}
            after = state
        assert state == before, "Rejected operation changed its input"
        operations.append(item)
        return after

    for count in (1, 2):
        for (players_count, scene_id), state in sorted(representative.items()):
            if count != players_count:
                continue
            operation(f"invalid-action-{count}-{scene_id}", "apply_action", state,
                      actor_id=state["expected_player_id"] or "p1", action_id="FLY")
            hinted = operation(f"hint-{count}-{scene_id}", "apply_hint", state)
            if not state["finished"]:
                operation(f"repeat-hint-{count}-{scene_id}", "apply_hint", hinted)
                first = game.present(state, pack)["actions"][0]["id"]
                operation(f"hint-score-{count}-{scene_id}", "apply_action", hinted,
                          actor_id=state["expected_player_id"], action_id=first)
                operation(f"wrong-actor-{count}-{scene_id}", "apply_action", state,
                          actor_id="other", action_id=first)
        exhausted = walk(pack, ["RIVER", "FOLLOW_TRACKS"], count)
        exhausted["resources"]["hint_tokens"] = 0
        operation(f"no-hints-{count}", "apply_hint", exhausted)
        no_tools = walk(pack, ["RIVER", "FOLLOW_TRACKS", "LEAVES"], count)
        no_tools["resources"]["tool_tokens"] = 0
        operation(f"no-tools-{count}", "apply_action", no_tools,
                  actor_id=no_tools["expected_player_id"], action_id="MAGNIFIER")
    return {"schema_version": 1, "pack_id": pack["pack_id"], "pack_version": pack["version"],
            "source_sha256": hashlib.sha256(CONTENT.read_bytes()).hexdigest(),
            "synthetic_inputs_only": True, "paths": paths, "interpretations": interpretations,
            "operations": operations}


def catalog_game_fixtures(pack: dict, source: Path, representative_paths: bool = False) -> dict:
    """Full paths for a catalog episode, without dinosaur-specific actions.

    Historical assets can retain only one path per ending/player count. Their
    interpretation and operation cases still cover every reachable scene.
    """
    paths, representative = [], {}
    for count in (1, 2):
        initial = game.new_game(PLAYERS[:count], pack)

        def visit(state: dict, actions: list[str], steps: list[dict]) -> None:
            representative.setdefault((count, state["scene_id"]), state)
            if state["finished"]:
                paths.append({"id": f"players-{count}-{'-'.join(actions)}", "players": PLAYERS[:count],
                              "actions": actions, "initial_state": initial,
                              "initial_presentation": game.present(initial, pack),
                              "steps": steps, "final_state": state})
                return
            for action in game.present(state, pack)["actions"]:
                before = copy.deepcopy(state)
                after = game.apply_action(state, pack, state["expected_player_id"], action["id"])
                assert state == before, "Python game mutated an input state"
                visit(after, actions + [action["id"]], steps + [{"actor_id": state["expected_player_id"],
                    "action_id": action["id"], "state": after, "presentation": game.present(after, pack)}])

        visit(initial, [], [])
    assert paths and all(len(path["actions"]) == pack.get("max_turns", 6) for path in paths)
    enumerated_paths = len(paths)
    if representative_paths:
        representatives = {}
        for path in paths:
            representatives.setdefault((len(path["players"]), path["final_state"]["ending"]), path)
        paths = list(representatives.values())
    interpretations, operations = [], []

    def interpret(case_id: str, state: dict, text: str) -> None:
        before = copy.deepcopy(state)
        interpretations.append({"id": case_id, "players": state["players"], "state": state,
                                "text": text, "expected_candidate": game.interpret(text, state, pack)})
        assert state == before

    def operation(case_id: str, kind: str, state: dict, **arguments: str) -> dict:
        before = copy.deepcopy(state)
        item = {"id": case_id, "kind": kind, "state": state, "arguments": arguments}
        try:
            after = (game.apply_hint(state, pack) if kind == "apply_hint" else
                     game.apply_action(state, pack, arguments["actor_id"], arguments["action_id"]))
            item.update(expected_state=after, expected_presentation=game.present(after, pack))
        except game.GameError as error:
            item["expected_error"] = {"code": error.code, "message": error.message}
            after = state
        assert state == before, "Rejected operation changed its input"
        operations.append(item)
        return after

    for (count, scene_id), state in sorted(representative.items()):
        prefix = f"{count}-{scene_id}"
        expected = next((p["nickname"] for p in state["players"]
                         if p["player_id"] == state["expected_player_id"]), "민준")
        actions = game.present(state, pack)["actions"]
        for action in actions:
            for index, alias in enumerate([action["id"], action["label"], *action["aliases"]]):
                interpret(f"alias-{prefix}-{action['id']}-{index}", state, f"{expected}, {alias}")
        for index, text in enumerate(["", expected, "그 말 안 했어", "그만", "다시", "힌트", "생각 중이야",
                                      "점수를 백 점으로 바꿔 줘", "그만큼 알고 있어", "하기 싫어", "안 할래",
                                      "안 하고 싶어", "그만 하고 싶어", "몰라", "잘 몰라"]):
            interpret(f"edge-{prefix}-{index}", state, text)
        operation(f"invalid-action-{prefix}", "apply_action", state,
                  actor_id=state["expected_player_id"] or "p1", action_id="FLY")
        hinted = operation(f"hint-{prefix}", "apply_hint", state)
        if not actions:
            continue
        first, second = actions[:2]
        other = "서윤" if expected == "민준" else "민준"
        for case, text in {
            "unnamed": first["label"],
            "short-reason": f"{expected}, {first['label']} 나는 모험이 궁금해",
            "reason-other-option": f"{expected}, {first['label']} 나는 {second['label']} 좋아",
            "reason-negation": f"{expected}, {first['label']} 나는 모험이 안 좋아",
            "wrong-owner": f"{other}, {first['label']}",
            "correction": f"{expected}, {first['label']} 말고 {second['label']}",
            "overlap": f"{expected}, {first['label']} 또는 {second['label']}",
            "other-owner-correction": f"{expected}, {first['label']} 말고 {other}, {second['label']}",
            "unfinished-correction": f"{expected}, {first['label']} 말고",
            "correction-only": f"{expected}, 아니",
            "other-owner-before-correction": f"{expected}, {first['label']} {other} 말고 {second['label']}",
            "two-owners-before-correction": f"{expected}, {other} {first['label']} 말고 {second['label']}",
        }.items():
            interpret(f"{case}-{prefix}", state, text)
        operation(f"repeat-hint-{prefix}", "apply_hint", hinted)
        operation(f"hint-score-{prefix}", "apply_action", hinted,
                  actor_id=state["expected_player_id"], action_id=first["id"])
        operation(f"wrong-actor-{prefix}", "apply_action", state, actor_id="other", action_id=first["id"])
        no_hints = copy.deepcopy(state)
        no_hints["resources"]["hint_tokens"] = 0
        operation(f"no-hints-{prefix}", "apply_hint", no_hints)
        for action in pack["scenes"][scene_id]["actions"]:
            for resource, cost in action.get("cost", {}).items():
                if cost:
                    exhausted = copy.deepcopy(state)
                    exhausted["resources"][resource] = 0
                    operation(f"no-resource-{prefix}-{action['id']}-{resource}", "apply_action", exhausted,
                              actor_id=state["expected_player_id"], action_id=action["id"])
    add_request_interpretations(pack, interpret)
    add_dinosaur_question_interpretations(pack, interpret)
    add_dinosaur_question_interpretations(pack, interpret, "short_preference_answers.json", "preference-answer")
    add_question_hint_interpretations(pack, interpret)
    add_watch_interpretations(pack, interpret)
    add_onu_address_interpretations(pack, interpret)
    if pack["pack_id"] == "starlight-post-office":
        named_players = [{"player_id": "p1", "nickname": "달"}, {"player_id": "p2", "nickname": "별"}]
        named_state = game.new_game(named_players, pack)
        for choice in ("MOON_BRIDGE", "LOOK_STAMP", "WALK_TOGETHER"):
            named_state = game.apply_action(named_state, pack, named_state["expected_player_id"], choice)
        for index, text in enumerate(("별, 별 등", "별, 별등", "별, 바람종 말고 별 등",
                                      "별, 바람종 말고 별등", "바람종 말고 별 등",
                                      "별, 바람종 말고 달, 별 등", "달, 바람종 말고 별 등")):
            interpret(f"nickname-choice-correction-{index}", named_state, text)
    return {"schema_version": 1, "pack_id": pack["pack_id"], "pack_version": pack["version"],
            "source_sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
            "synthetic_inputs_only": True, "paths": paths, "interpretations": interpretations,
            "operations": operations, "coverage": {
                "paths": "one_per_player_count_and_ending" if representative_paths else "exhaustive",
                "enumerated_paths": enumerated_paths,
                "interpretations_and_operations": "every_reachable_scene"}}


class CanonicalIds:
    """Per scenario, sorted object keys/depth first; lists retain their order.

    Each identity namespace is numbered on first appearance, e.g. turn_1.
    The same raw ID always receives the same symbol, including in old receipts.
    """
    FIELDS = {"session_id": "session", "turn_id": "turn", "playback_id": "playback",
              "segment_id": "segment", "segment_ids": "segment", "listen_window_id": "window",
              "command_id": "command", "capture_id": "capture"}

    def __init__(self):
        self.identities: dict[str, dict[str, str]] = {}

    def normalize(self, value, field=None):
        if isinstance(value, dict):
            return {key: self.normalize(value[key], key) for key in sorted(value)}
        if isinstance(value, list):
            return [self.normalize(item, field) for item in value]
        if isinstance(value, str) and field in self.FIELDS:
            namespace = self.FIELDS[field]
            known = self.identities.setdefault(namespace, {})
            return known.setdefault(value, f"{namespace}_{len(known) + 1}")
        return value


class ServiceTrace:
    def __init__(self, pack: dict, count: int, case_id: str):
        self.store = Store(":memory:")
        self.service = SessionService(self.store, Settings(db_path=":memory:"), pack, clock=lambda: 100.0)
        self.family = "fixture-family"
        self.state = self.service.create(self.family, PLAYERS[:count])
        self.ids = CanonicalIds()
        self.result = {"id": case_id, "players": PLAYERS[:count],
                       "initial_snapshot": self.ids.normalize(self.state), "steps": []}
        self.counter = 0

    async def send(self, kind: str, payload=None, request=None) -> dict:
        self.counter += 1
        request = request or {"command_id": f"fixture-command-{self.counter}",
            "expected_version": self.state["version"], "turn_id": self.state["turn_id"],
            "turn_epoch": self.state["turn_epoch"], "type": kind, "payload": payload or {}}
        step = {"request": self.ids.normalize(request)}
        try:
            response = await self.service.command(self.family, self.state["session_id"], Command.model_validate(request))
            step["expected_response"] = self.ids.normalize(response)
        except ServiceError as error:
            step["expected_error"] = {"status": error.status, "code": error.code, "message": error.message}
        self.state = self.service.snapshot(self.family, self.state["session_id"])
        step["expected_current_snapshot"] = self.ids.normalize(self.state)
        self.result["steps"].append(step)
        return request

    async def playback(self):
        return await self.send("playback.completed", {"playback_id": self.state["speech_plan"]["playback_id"]})

    async def text(self, text: str):
        return await self.send("action.submit", {"text": text, "is_final": True,
            "capture_id": f"fixture-capture-{self.counter + 1}", "listen_window_id": self.state["listen_window_id"],
            "locale": "ko-KR", "input_source": "mock"})

    def close(self):
        self.store.connection.close()


async def service_fixtures(pack: dict) -> dict:
    cases = []
    for count in (1, 2):
        trace = ServiceTrace(pack, count, f"ending-{count}")
        try:
            intro = "둘이 함께할 때는 내 이름을 먼저 말하고, 고른 말을 이어서 말해 줘."
            prompt = trace.state["presentation"]["prompt"]
            texts = ([intro] if count == 2 else []) + [prompt]
            assert [s["text"] for s in trace.state["speech_plan"]["segments"]] == texts
            await trace.send("session.pause", {"reason": "QUIET_REQUEST"})
            await trace.send("session.resume")
            assert [s["text"] for s in trace.state["speech_plan"]["segments"]] == texts
            await trace.playback()
            await trace.send("session.pause", {"reason": "QUIET_REQUEST"})
            await trace.send("session.resume")
            assert [s["text"] for s in trace.state["speech_plan"]["segments"]] == [prompt]
            actions = SUCCESS if count == 1 else SUCCESS[:-1] + ["GREENHOUSE"]
            for action in actions:
                await trace.playback()
                name = next(p["nickname"] for p in trace.state["game"]["players"]
                            if p["player_id"] == trace.state["game"]["expected_player_id"])
                await trace.text(f"{name}, {action}")
            await trace.playback()
            cases.append(trace.result)
        finally:
            trace.close()
    for case_id in ("name-repair", "quiet-resume-duplicate", "controls-and-objection",
                    "unfinished-correction-clears-pending", "help-clears-pending",
                    "wait-preserves-pending", "pause-clears-pending", "recovery-budget",
                    "recovery-helpers-budget", "reason-choice", "completed-feedback-resume", "ending-resume",
                    "address-pending-guard", "address-quiet-resume", "address-recovery-budget", "address-only-budget"):
        trace = ServiceTrace(pack, 2, case_id)
        try:
            await trace.playback()
            if case_id == "name-repair":
                await trace.text("물가")
                await trace.playback()
                await trace.text("민준")
                await trace.playback()
                await trace.text("민준, 바퀴 자국")
                await trace.playback()
                await trace.text("서윤, 바퀴 자국")
            elif case_id == "quiet-resume-duplicate":
                original = await trace.text("민준, 물가")
                await trace.send("action.submit", request=original)
                changed = copy.deepcopy(original)
                changed["payload"]["text"] = "민준, 숲"
                await trace.send("action.submit", request=changed)
                old_playback = trace.state["speech_plan"]["playback_id"]
                await trace.send("session.pause", {"reason": "QUIET_REQUEST"})
                await trace.send("session.pause", {"reason": "NETWORK"})
                await trace.send("session.resume")
                await trace.send("playback.completed", {"playback_id": old_playback})
                await trace.playback()
                await trace.text("그만")
            elif case_id == "completed-feedback-resume":
                await trace.text("민준, 물가")
                old_playback = trace.state["speech_plan"]["playback_id"]
                assert len(trace.state["speech_plan"]["segments"]) == 2
                await trace.playback()
                original_game = copy.deepcopy(trace.state["game"])
                prompt = trace.state["presentation"]["prompt"]
                await trace.send("session.pause", {"reason": "QUIET_REQUEST"})
                request = await trace.send("session.resume")
                assert [s["text"] for s in trace.state["speech_plan"]["segments"]] == [prompt]
                assert trace.state["game"] == original_game
                assert trace.state["speech_plan"]["playback_id"] != old_playback
                await trace.send("session.resume", request=request)
                await trace.send("playback.completed", {"playback_id": old_playback})
                assert trace.result["steps"][-1]["expected_error"]["code"] == "STALE_PLAYBACK"
                await trace.playback()
                await trace.text("서윤, 바퀴 자국")
                assert len(trace.state["game"]["history"]) == 2
            elif case_id == "ending-resume":
                for index, action_id in enumerate(SUCCESS):
                    if index:
                        await trace.playback()
                    actor = trace.state["game"]["expected_player_id"]
                    name = next(p["nickname"] for p in trace.state["game"]["players"] if p["player_id"] == actor)
                    await trace.text(f"{name}, {action_id}")
                original_game = copy.deepcopy(trace.state["game"])
                texts = [s["text"] for s in trace.state["speech_plan"]["segments"]]
                old_playback = trace.state["speech_plan"]["playback_id"]
                await trace.send("session.pause", {"reason": "QUIET_REQUEST"})
                await trace.send("session.resume")
                assert [s["text"] for s in trace.state["speech_plan"]["segments"]] == texts
                assert trace.state["speech_plan"]["playback_id"] != old_playback
                assert trace.state["game"] == original_game
                assert trace.state["status"] == "ACTIVE"
                await trace.send("playback.completed", {"playback_id": old_playback})
                assert trace.result["steps"][-1]["expected_error"]["code"] == "STALE_PLAYBACK"
                await trace.playback()
                assert trace.state["status"] == "ENDED"
            elif case_id == "controls-and-objection":
                for text in ("물가", "민준, 그 말 안 했어", "서윤, 숲", "민준 물가랑 숲", "민준, 숲 말고 물가"):
                    await trace.text(text)
                    if trace.state["status"] == "PAUSED":
                        assert trace.state["pause_reason"] == "NO_RESPONSE"
                        await trace.send("session.resume")
                    await trace.playback()
                for text in ("힌트", "힌트", "다시", "생각 중이야"):
                    await trace.text(text)
                    if trace.state["status"] == "PAUSED":
                        assert trace.state["pause_reason"] == "NO_RESPONSE"
                        await trace.send("session.resume")
                    await trace.playback()
                await trace.text("게임 종료")
            elif case_id in {"recovery-budget", "recovery-helpers-budget"}:
                texts = ("민준 잘 모르겠는데", "도와줘", "다시", "서윤 숲") if case_id == "recovery-budget" else ("도와줘", "다시", "기다려")
                for index, text in enumerate(texts):
                    request = await trace.text(text)
                    if index < len(texts) - 1:
                        assert trace.state["status"] == "ACTIVE"
                        await trace.playback()
                assert trace.state["status"] == "PAUSED"
                assert trace.state["pause_reason"] == "NO_RESPONSE"
                assert not trace.state["game"]["history"]
                await trace.send("action.submit", request=request)
                await trace.send("session.resume")
                await trace.playback()
                await trace.text("민준 숲으로 가자 나 나무 좋아하거든")
                await trace.playback()
                await trace.text("서윤 잘 모르겠는데")
                assert trace.state["status"] == "ACTIVE"
                await trace.playback()
                await trace.text("도와줘")
                assert trace.state["status"] == "ACTIVE"
            elif case_id == "wait-preserves-pending":
                await trace.text("물가")
                for _ in range(2):
                    await trace.playback()
                    request = await trace.text("생각 중이야")
                    assert trace.state["presentation"]["input_kind"] == "player_name"
                    assert not trace.state["game"]["history"]
                    await trace.send("action.submit", request=request)
                await trace.playback()
                await trace.text("민준")
                assert [item["action_id"] for item in trace.state["game"]["history"]] == ["RIVER"]
            elif case_id == "reason-choice":
                await trace.text("숲으로 가자 나 나무 좋아하거든")
                await trace.playback()
                await trace.text("민준")
                assert trace.state["game"]["history"][0]["action_id"] == "FOREST"
                await trace.playback()
                await trace.text("서윤 상자 표지 나는 이야기가 궁금해")
                assert len(trace.state["game"]["history"]) == 2
            elif case_id == "address-pending-guard":
                await trace.text("물가")
                await trace.playback()
                await trace.text("오누야 민준")
                assert trace.state["status"] == "PAUSED" and trace.state["pause_reason"] == "NO_RESPONSE"
                assert not trace.state["game"]["history"]
                await trace.send("session.resume")
                await trace.playback()
                await trace.text("민준, 오누야 숲")
                assert [item["action_id"] for item in trace.state["game"]["history"]] == ["FOREST"]
            elif case_id == "address-quiet-resume":
                await trace.text("민준, 오누야 그만")
                assert trace.state["status"] == "PAUSED" and trace.state["pause_reason"] == "QUIET_REQUEST"
                assert trace.state["speech_plan"] is None and trace.state["listen_window_id"] is None
                await trace.send("session.resume")
                await trace.playback()
                original = await trace.text("오누야 민준, 숲")
                await trace.send("action.submit", request=original)
                assert [item["action_id"] for item in trace.state["game"]["history"]] == ["FOREST"]
            elif case_id == "address-recovery-budget":
                for index, text in enumerate(("오누야 도와줘", "오누야 다시", "오누야 기다려")):
                    await trace.text(text)
                    if index < 2:
                        assert trace.state["status"] == "ACTIVE"
                        await trace.playback()
                assert trace.state["status"] == "PAUSED" and trace.state["pause_reason"] == "NO_RESPONSE"
                assert not trace.state["game"]["history"]
            elif case_id == "address-only-budget":
                await trace.text("오누야")
                assert trace.state["status"] == "ACTIVE"
                assert [segment["text"] for segment in trace.state["speech_plan"]["segments"]] == [
                    "응, 여기 있어.", trace.state["presentation"]["prompt"]]
                await trace.playback()
                await trace.text("민준, 오누야")
                assert trace.state["status"] == "PAUSED" and trace.state["pause_reason"] == "NO_RESPONSE"
                assert not trace.state["game"]["history"]
            else:
                await trace.text("물가")
                await trace.playback()
                control = {"unfinished-correction-clears-pending": "민준, 물가 말고",
                           "help-clears-pending": "몰라",
                           "pause-clears-pending": "하기 싫어"}[case_id]
                await trace.text(control)
                if trace.state["status"] == "PAUSED":
                    await trace.send("session.resume")
                await trace.playback()
                await trace.text("민준")
                assert not trace.state["game"]["history"], "Abandoned pending choice was applied"
                if trace.state["status"] == "PAUSED":
                    assert trace.state["pause_reason"] == "NO_RESPONSE"
                    await trace.send("session.resume")
                await trace.playback()
                await trace.text("민준, 숲")
                assert [item["action_id"] for item in trace.state["game"]["history"]] == ["FOREST"]
            cases.append(trace.result)
        finally:
            trace.close()
    return {"schema_version": 1, "pack_id": pack["pack_id"], "pack_version": pack["version"],
            "synthetic_inputs_only": True, "id_normalization": {
                "scope": "per scenario; initial_snapshot, then each request, expected_response, expected_current_snapshot",
                "traversal": "object keys sorted ascending; depth first; list order preserved",
                "symbols": "namespace_1, namespace_2, ... on first raw identity appearance; preserve equality",
                "fields": CanonicalIds.FIELDS}, "scenarios": cases}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Fail if committed assets differ; do not write.")
    args = parser.parse_args()
    pack = game.load_pack(CONTENT)
    game_data = game_fixtures(pack)
    service_data = asyncio.run(service_fixtures(pack))
    outputs = {MAIN_ASSET: CONTENT.read_bytes(), FIXTURES / "game-parity.json": encoded(game_data),
               FIXTURES / "service-parity.json": encoded(service_data)}
    outputs[MAIN_ASSETS / "content/catalog.json"] = CATALOG.read_bytes()
    outputs[FIXTURES / "dinosaur-question-answers.json"] = (ROOT / "tests/fixtures/dinosaur_question_answers.json").read_bytes()
    outputs[FIXTURES / "question-hint-answers.json"] = (ROOT / "tests/fixtures/question_hint_answers.json").read_bytes()
    outputs[FIXTURES / "companion-greetings.json"] = (ROOT / "tests/fixtures/companion-greetings.json").read_bytes()
    catalog = ContentCatalog(CATALOG)
    index = {"schema_version": 1, "synthetic_inputs_only": True, "packs": [], "legacy_packs": []}
    for historical, entries in ((False, catalog.current_entries), (True, catalog.legacy_entries)):
        for entry in entries:
            source = catalog.source(entry)
            current = catalog.load(entry["pack_id"], entry["version"])
            outputs[MAIN_ASSETS / entry["content_asset"]] = source.read_bytes()
            if historical:
                fixture_asset = f"fixtures/legacy/{current['version']}/{current['pack_id']}/game-parity.json"
                data = catalog_game_fixtures(current, source, representative_paths=True)
            else:
                fixture_asset = ("fixtures/game-parity.json" if current["pack_id"] == pack["pack_id"] else
                                 f"fixtures/{current['pack_id']}/game-parity.json")
                data = game_data if current["pack_id"] == pack["pack_id"] else catalog_game_fixtures(current, source)
            outputs[FIXTURES.parent / fixture_asset] = encoded(data)
            index["legacy_packs" if historical else "packs"].append({
                "pack_id": current["pack_id"], "pack_version": current["version"],
                "content_asset": entry["content_asset"], "fixture_asset": fixture_asset,
                "paths": len(data["paths"]), "interpretations": len(data["interpretations"]),
                "operations": len(data["operations"]), "source_sha256": data["source_sha256"],
                "path_coverage": "representative_per_player_and_ending" if historical else "exhaustive"})
    outputs[FIXTURES / "catalog-parity.json"] = encoded(index)
    changed = []
    for path, data in outputs.items():
        if not path.exists() or path.read_bytes() != data:
            changed.append(str(path.relative_to(ROOT)))
            if not args.check:
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(data)
    print(json.dumps({"mode": "check" if args.check else "export", "changed": changed,
                      "paths": len(game_data["paths"]), "interpretations": len(game_data["interpretations"]),
                      "operations": len(game_data["operations"]), "service_scenarios": len(service_data["scenarios"]),
                      "catalog_totals": {key: sum(item[key] for item in index["packs"])
                                         for key in ("paths", "interpretations", "operations")},
                      "legacy_totals": {key: sum(item[key] for item in index["legacy_packs"])
                                        for key in ("paths", "interpretations", "operations")},
                      "catalog_packs": index["packs"], "legacy_packs": index["legacy_packs"]}, indent=2))
    if args.check and changed:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
