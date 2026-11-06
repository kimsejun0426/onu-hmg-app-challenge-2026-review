"""Deterministic, copy-on-write game rules. No model, network, clock, or database IO.

Only ``load_pack`` reads a file. The service owns authentication, lifecycle,
versions, command receipts, capture/turn IDs and playback acknowledgements.
"""

from __future__ import annotations

import copy
import json
import re
import unicodedata
from pathlib import Path
from string import Formatter


class GameError(ValueError):
    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code
        self.message = message


CONTROL_ALIASES = {
    "PAUSE": {"그만", "그만해", "그만해줘", "그만할래", "그만하자", "이제 그만", "멈춰", "멈춰줘", "조용히", "조용히 해줘", "쉬자", "쉬고 싶어", "쉴래", "졸려", "잠깐", "잠깐만", "일시정지", "하기 싫어", "안 할래", "안 하고 싶어", "그만 하고 싶어", "싫어", "싫어요", "오누야 그만"},
    "RESUME": {"계속", "계속해", "계속하자", "이어하기", "이어서 하자", "다시 하자"},
    "END": {"끝내기", "게임 종료", "놀이 종료", "종료"},
    "REPEAT": {"다시", "그거 다시", "다시 말해줘", "다시 들려줘", "못 들었어", "다시 듣기"},
    "HINT": {"힌트", "도와줘", "모르겠어", "힌트 줘", "잘 모르겠어", "몰라", "잘 몰라", "모르겠다", "모르겠어요"},
    "WAIT": {"생각 중", "생각중", "생각 중이야", "생각중이야", "기다려", "기다려줘", "잠시만 기다려"},
}
REPAIR_ALIASES = {"그 말 안 했어", "잘못 들었어", "다시 말할게"}
# These newly recognized requests used to be valid nicknames. Existing families
# may still start a game with those names; new profile entry rejects them in UI.
_RECOVERY_ALIASES = {"하기싫어", "안할래", "안하고싶어", "그만하고싶어", "싫어", "싫어요", "오누야그만",
                     "몰라", "잘몰라", "모르겠다", "모르겠어요"}
_CORRECTION = re.compile(r"(?:아니고|아니라|아니\b|말고)")
_ONU_ADDRESS = re.compile(r"^오누야(?=$|[\s,:：.!?])[\s,:：.!?]*")
_REPEATED_ONU_ADDRESS = re.compile(r"(?<!\w)오누야(?=$|[\s,:：.!?])")
_ONU_QUOTED_DELIMITERS = frozenset("\"'`“”‘’「」『』«»‹›()[]{}〈〉《》")
_TEMPLATE_FIELDS = {"nickname", "role", "destination_label", "total_score", "hint_remaining"}
_ROLES = ("탐험가", "연구원")


def _norm(value: str) -> str:
    value = unicodedata.normalize("NFKC", value).casefold()
    value = re.sub(r"[^\w\s]", " ", value, flags=re.UNICODE)
    return " ".join(value.split())


def _fail(code: str, message: str) -> None:
    raise GameError(code, message)


def _action_aliases(action: dict) -> set[str]:
    return {_norm(x) for x in [action["id"], action["label"], *action.get("aliases", [])]}


def validate_pack(pack: dict) -> list[str]:
    """Validate the bounded scene language, references, sources and finite graph."""
    errors: list[str] = []
    if not isinstance(pack, dict):
        return ["pack must be an object"]
    for field in ("pack_id", "version", "title", "fiction_notice", "start_scene"):
        if not isinstance(pack.get(field), str) or not pack[field].strip():
            errors.append(f"missing string: {field}")
    if pack.get("schema_version") != 1:
        errors.append("schema_version must be 1")
    roles = pack.get("roles", list(_ROLES))
    if not isinstance(roles, list) or len(roles) != 2 or any(not isinstance(role, str) or not role.strip() for role in roles):
        errors.append("roles must contain two nonempty strings")
    if not isinstance(pack.get("solo_role", "탐험가와 연구원"), str) or not pack.get("solo_role", "탐험가와 연구원").strip():
        errors.append("solo_role must be a nonempty string")
    if pack.get("solo_prompt_style", "role_and_turn") not in ("role_and_turn", "nickname"):
        errors.append("solo_prompt_style must be role_and_turn or nickname")
    if "max_turns" in pack and (type(pack["max_turns"]) is not int or not 1 <= pack["max_turns"] <= 64):
        errors.append("max_turns must be an integer between 1 and 64")
    labels = pack.get("template_labels", {})
    if not isinstance(labels, dict):
        errors.append("template_labels must be an object")
        labels = {}
    for name, definition in labels.items():
        if not re.fullmatch(r"[a-z_]+", name) or name in _TEMPLATE_FIELDS:
            errors.append(f"invalid template label name: {name}")
        if (not isinstance(definition, dict) or not isinstance(definition.get("flag"), str)
                or not definition.get("flag") or not isinstance(definition.get("default"), str)
                or not isinstance(definition.get("values"), dict)
                or any(not isinstance(value, str) for value in definition.get("values", {}).values())):
            errors.append(f"invalid template label: {name}")
    template_fields = _TEMPLATE_FIELDS | labels.keys()
    scenes = pack.get("scenes")
    if not isinstance(scenes, dict) or not scenes:
        return errors + ["scenes must be a nonempty object"]
    resources = pack.get("initial_resources", {})
    if not isinstance(resources, dict) or any(type(v) is not int or v < 0 for v in resources.values()):
        errors.append("initial_resources must contain nonnegative integers")
        resources = {}
    sources = pack.get("sources", {})
    if not isinstance(sources, dict):
        errors.append("sources must be an object")
        sources = {}
    for source_id, source in sources.items():
        if not isinstance(source, dict) or not str(source.get("url", "")).startswith("https://") or not source.get("checked_at"):
            errors.append(f"invalid source: {source_id}")
    graph: dict[str, set[str]] = {key: set() for key in scenes}
    endings: set[str] = set()
    for scene_id, scene in scenes.items():
        if not isinstance(scene, dict):
            errors.append(f"scene must be an object: {scene_id}")
            continue
        if scene.get("kind") not in {"choice", "knowledge", "ending"}:
            errors.append(f"unknown scene kind: {scene_id}")
        for field in ("prompt", "hint", "title", "ending_title", "summary"):
            value = scene.get(field, "")
            if not isinstance(value, str) or (field == "prompt" and not value.strip()):
                errors.append(f"invalid {field}: {scene_id}")
                continue
            try:
                used = {name for _, name, _, _ in Formatter().parse(value) if name}
                if used - template_fields:
                    errors.append(f"unknown template fields in {scene_id}: {used - template_fields}")
            except ValueError:
                errors.append(f"invalid template: {scene_id}/{field}")
        if scene.get("kind") == "knowledge" and not scene.get("source_ids"):
            errors.append(f"knowledge requires sources: {scene_id}")
        for source_id in scene.get("source_ids", []):
            if source_id not in sources:
                errors.append(f"missing source {source_id}: {scene_id}")
        actions = scene.get("actions", [])
        if not isinstance(actions, list):
            errors.append(f"actions must be a list: {scene_id}")
            continue
        if scene.get("kind") == "ending":
            endings.add(scene_id)
            if actions or not scene.get("ending_id"):
                errors.append(f"ending has actions or no ending_id: {scene_id}")
        elif not 1 <= len(actions) <= 3:
            errors.append(f"scene needs 1–3 actions: {scene_id}")
        ids: set[str] = set()
        aliases: dict[str, str] = {}
        for action in actions:
            if not isinstance(action, dict) or not action.get("id") or not isinstance(action.get("label"), str):
                errors.append(f"invalid action: {scene_id}")
                continue
            action_id = action["id"]
            if action_id in ids:
                errors.append(f"duplicate action: {scene_id}/{action_id}")
            ids.add(action_id)
            for alias in _action_aliases(action):
                if not alias:
                    errors.append(f"empty alias: {scene_id}/{action_id}")
                if alias in aliases and aliases[alias] != action_id:
                    errors.append(f"ambiguous alias {alias}: {scene_id}")
                aliases[alias] = action_id
            outcomes = action.get("outcomes", [action])
            if not isinstance(outcomes, list) or not outcomes:
                errors.append(f"empty outcomes: {scene_id}/{action_id}")
                continue
            if "outcomes" in action and ("when" in outcomes[-1] or any("when" not in x for x in outcomes[:-1])):
                errors.append(f"outcomes need a final unconditional fallback: {scene_id}/{action_id}")
            for outcome in outcomes:
                if not isinstance(outcome, dict):
                    errors.append(f"invalid outcome: {scene_id}/{action_id}")
                    continue
                target = outcome.get("next")
                if target not in scenes:
                    errors.append(f"missing destination {target}: {scene_id}/{action_id}")
                else:
                    graph[scene_id].add(target)
                if not isinstance(outcome.get("feedback", ""), str):
                    errors.append(f"invalid feedback: {scene_id}/{action_id}")
                if type(outcome.get("score", 0)) is not int or outcome.get("score", 0) < 0:
                    errors.append(f"invalid score: {scene_id}/{action_id}")
            for key, amount in action.get("cost", {}).items():
                if key not in resources or type(amount) is not int or amount < 0:
                    errors.append(f"invalid resource cost: {scene_id}/{action_id}/{key}")
    start = pack.get("start_scene")
    if start not in scenes:
        errors.append("start_scene does not exist")
        return errors
    visited: set[str] = set()
    active: set[str] = set()

    def visit(node: str) -> None:
        if node in active:
            errors.append(f"cycle without an ending: {node}")
            return
        if node in visited:
            return
        active.add(node)
        for target in graph[node]:
            visit(target)
        active.remove(node)
        visited.add(node)

    visit(start)
    for node in set(scenes) - visited:
        errors.append(f"unreachable scene: {node}")
    if not endings or not endings <= visited:
        errors.append("all endings must be reachable")
    return errors


def load_pack(path: str | Path | None = None) -> dict:
    if path is None:
        path = Path(__file__).resolve().parents[4] / "content/ko-KR/dinosaurs/episode.json"
    with Path(path).open(encoding="utf-8") as stream:
        pack = json.load(stream)
    errors = validate_pack(pack)
    if errors:
        _fail("invalid_content", "; ".join(errors))
    return pack


def new_game(players: list[dict], pack: dict) -> dict:
    if not isinstance(players, list) or not 1 <= len(players) <= 2:
        _fail("invalid_players", "한 명 또는 두 명의 등록된 참여자가 필요해요.")
    clean: list[dict] = []
    used_ids: set[str] = set()
    used_names: set[str] = set()
    reserved = {_norm(alias).replace(" ", "") for aliases in CONTROL_ALIASES.values() for alias in aliases} - _RECOVERY_ALIASES
    for index, player in enumerate(players):
        if not isinstance(player, dict):
            _fail("invalid_players", "참여자 정보를 확인해 주세요.")
        player_id, nickname = player.get("player_id"), player.get("nickname")
        if not isinstance(player_id, str) or not player_id.strip() or len(player_id) > 64:
            _fail("invalid_players", "참여자 ID를 확인해 주세요.")
        if not isinstance(nickname, str) or not 1 <= len(nickname.strip()) <= 12:
            _fail("invalid_players", "별명은 한 글자부터 열두 글자까지 사용해요.")
        nickname = nickname.strip()
        normalized = _norm(nickname).replace(" ", "")
        if not normalized or player_id in used_ids or normalized in used_names or normalized in reserved:
            _fail("invalid_players", "서로 구별되는 ID와 별명을 사용해 주세요.")
        used_ids.add(player_id)
        used_names.add(normalized)
        clean.append({"player_id": player_id, "nickname": nickname,
                      "role": pack.get("solo_role", "탐험가와 연구원") if len(players) == 1 else pack.get("roles", _ROLES)[index]})
    return {
        "pack_id": pack["pack_id"], "pack_version": pack["version"], "players": clean,
        "scene_id": pack["start_scene"], "expected_player_id": clean[0]["player_id"],
        "turn_index": 0, "resources": copy.deepcopy(pack["initial_resources"]),
        "score_by_player": {p["player_id"]: 0 for p in clean}, "clues": [], "flags": {},
        "hinted_scene_ids": [], "history": [], "feedback": "", "finished": False, "ending": None,
    }


def _scene(state: dict, pack: dict) -> dict:
    if state.get("pack_id") != pack.get("pack_id") or state.get("pack_version") != pack.get("version"):
        _fail("content_version_mismatch", "저장된 모험과 같은 버전의 콘텐츠가 필요해요.")
    try:
        return pack["scenes"][state["scene_id"]]
    except KeyError:
        _fail("invalid_state", "현재 장면을 찾을 수 없어요.")


def _render(text: str, state: dict, pack: dict) -> str:
    player = next((p for p in state["players"] if p["player_id"] == state.get("expected_player_id")), state["players"][0])
    values = dict(
        nickname=player["nickname"], role=player["role"],
        destination_label={"GREENHOUSE": "온실", "WORKSHOP": "공작실"}.get(state["flags"].get("egg_location"), "연구소"),
        total_score=sum(state["score_by_player"].values()),
        hint_remaining=state["resources"].get("hint_tokens", 0),
    )
    for name, definition in pack.get("template_labels", {}).items():
        values[name] = definition["values"].get(state["flags"].get(definition["flag"]), definition["default"])
    return text.format(**values)


def present(state: dict, pack: dict) -> dict:
    scene = _scene(state, pack)
    prompt = _render(scene["prompt"], state, pack)
    if not state["finished"]:
        player = next(p for p in state["players"] if p["player_id"] == state["expected_player_id"])
        # Preserve the shipped 0.2.0 voice, while new editions declare their style.
        solo_style = pack.get("solo_prompt_style", "nickname" if pack["version"] == "0.2.0" else "role_and_turn")
        if solo_style == "nickname" and len(state["players"]) == 1:
            prompt = f"{player['nickname']}, {prompt}"
        else:
            prompt = f"{player['nickname']} {player['role']} 차례야. {prompt}"
    return {
        "scene_id": state["scene_id"], "prompt": prompt,
        "actions": [{"id": a["id"], "label": a["label"], "aliases": list(a.get("aliases", []))} for a in scene.get("actions", [])],
        "expected_player_id": state["expected_player_id"], "finished": state["finished"],
        "hint": _render(scene.get("hint", ""), state, pack), "feedback": state.get("feedback", ""),
        "ending": state.get("ending"), "roles": {p["player_id"]: p["role"] for p in state["players"]},
    }


def _strip_player(text: str, players: list[dict]) -> tuple[str | None, str]:
    for player in sorted(players, key=lambda p: len(p["nickname"]), reverse=True):
        name = unicodedata.normalize("NFKC", player["nickname"]).casefold()
        pattern = r"^" + re.escape(name) + r"(?:(?:이가|이는|이|가|은|는))?(?=$|[\s,:：.!?])\s*[,：:]?\s*"
        matched = re.match(pattern, text, flags=re.IGNORECASE)
        if matched:
            return player["player_id"], text[matched.end():].strip()
    return None, text


def _mentioned_players(text: str, players: list[dict]) -> set[str]:
    found = set()
    for player in players:
        name = unicodedata.normalize("NFKC", player["nickname"]).casefold()
        pattern = r"(?<!\w)" + re.escape(name) + r"(?:(?:이가|이는|이|가|은|는))?(?=$|[\s,:：.!?])"
        if re.search(pattern, text):
            found.add(player["player_id"])
    return found


def _mentioned_actions(text: str, actions: list[dict]) -> set[str]:
    """Recognize clear competing option mentions only to refuse commitment.

    This does not select an action by substring. Partial noun mentions may only
    cause a clarification, never a score/resource change.
    """
    found = set()
    for action in actions:
        for alias in _action_aliases(action):
            pattern = r"(?<!\w)" + re.escape(alias) + r"(?=$|\s|랑|와|과|하고|이나|또는|으로|로|에|을|를|이야|일까)"
            if re.search(pattern, text):
                found.add(action["id"])
                break
    return found


def _candidate(intent: str = "UNKNOWN", player_id: str | None = None, action_id: str | None = None, *, clarify: bool = True, reason: str | None = None) -> dict:
    return {"intent": intent, "declared_player_id": player_id, "action_id": action_id, "needs_clarification": clarify, "reason": reason}


def _choice_with_reason(text: str, actions: list[dict], players: list[dict]) -> list[dict]:
    """A complete authored choice followed by one short positive reason.

    This is a deliberately small grammar, not substring intent selection. The
    reason is only tolerated here; it is never returned, stored or profiled.
    """
    reason = re.compile(
        r"(?:(?:나|나는|내가) |왜냐하면 (?:(?:나|나는|내가) )?)"
        r"(?:[가-힣]{1,12} ){1,3}"
        r"(?:좋아|좋아해|좋아하거든|좋거든|궁금해|궁금하거든|보고 싶어|재미있어|재미있거든|예뻐|멋져)"
    )
    if len(_mentioned_actions(text, actions)) != 1:
        return []
    matches = []
    for action in actions:
        for alias in _action_aliases(action):
            if not text.startswith(alias + " "):
                continue
            tail = text[len(alias) + 1:]
            if not reason.fullmatch(tail):
                continue
            compact_tail = tail.replace(" ", "")
            if (re.search(r"(?:^| )(?:안|못)(?: |$)", tail)
                    or any(other["id"] != action["id"] and any(
                        alias.replace(" ", "") in compact_tail for alias in _action_aliases(other)
                    ) for other in actions)
                    or any(word in compact_tail for word in (
                        "아니", "말고", "않", "싫", "그만", "취소", "멈", "쉬", "다시",
                        "힌트", "도와", "몰라", "모르", "기다", "계속", "종료", "끝내",
                    ))
                    or any(_norm(p["nickname"]).replace(" ", "") in compact_tail for p in players)):
                continue
            matches.append(action)
            break
    return matches


def interpret(text: str, state: dict, pack: dict) -> dict:
    """Conservative aliases; never turns an unknown phrase into a wrong answer."""
    scene = _scene(state, pack)
    if not isinstance(text, str) or not text.strip():
        return _candidate(reason="no_input")
    if len(text) > 1000:
        return _candidate(reason="input_too_long")
    text = unicodedata.normalize("NFKC", text).casefold().strip()
    player_id, body = _strip_player(text, state["players"])
    # Full registered names win before vocative removal, including "오누야 숲".
    # A colliding short nickname disables this grammar for the whole session.
    addressed = False
    if not any(_norm(p["nickname"]).replace(" ", "") in {"오누", "오누야"} for p in state["players"]):
        address = _ONU_ADDRESS.match(body)
        if address:
            addressed = True
            body = body[address.end():]
            if player_id is None:
                player_id, body = _strip_player(body, state["players"])
            repeated_player, remaining = _strip_player(body, state["players"])
            if any(mark in body for mark in _ONU_QUOTED_DELIMITERS) or _REPEATED_ONU_ADDRESS.search(body):
                return _candidate(player_id=player_id, reason="unconfirmed_expansion")
            if not _norm(body) or (repeated_player is not None and not _norm(remaining)):
                # Never turn a bare address/name into confirmation of a pending choice.
                return _candidate(player_id=player_id, reason="address_only")
    action_aliases = {alias for action in scene.get("actions", []) for alias in _action_aliases(action)}
    greeting = _norm(body)
    if (addressed and not state["finished"] and greeting not in action_aliases
            and greeting in {"안녕", "뭐해", "뭐 해", "같이놀자", "같이 놀자"}):
        # Exact addressed greetings use the existing one-clarification budget.
        # Check before correction: no negated/reported suffix becomes a greeting.
        return _candidate(player_id=player_id, reason="address_only")
    corrected_action = False
    # A later, clearly stated correction replaces the earlier candidate, not the owner.
    corrected = _CORRECTION.split(body)
    if addressed and any(_norm(discarded) not in action_aliases for discarded in corrected[:-1]):
        # Quoted/reported or negated speech cannot disappear into an actionable suffix.
        return _candidate(player_id=player_id, reason="unconfirmed_expansion")
    if len(corrected) > 1 and re.search(r" (?:나|나는|내가|왜냐하면) ", _norm(body)):
        return _candidate(player_id=player_id, reason="unconfirmed_expansion")
    if len(corrected) > 1:
        body = corrected[-1].strip(" ,.!?؛:")
        # "별 등" is a complete choice, not the speaker "별" followed by "등".
        # Preserve an existing owner, or require one later when none was declared.
        corrected_action = _norm(body) in action_aliases
        if not corrected_action:
            corrected_player, body = _strip_player(body, state["players"])
            if corrected_player and player_id and corrected_player != player_id:
                return _candidate(reason="multiple_players")
            player_id = corrected_player or player_id
    normalized = _norm(body)
    # Controls are intentionally owner-independent and exact, never substring matches.
    for intent, aliases in CONTROL_ALIASES.items():
        if normalized.replace(" ", "") in {_norm(a).replace(" ", "") for a in aliases}:
            return _candidate(intent, player_id, clarify=False)
    if normalized.replace(" ", "") in {_norm(a).replace(" ", "") for a in REPAIR_ALIASES}:
        return _candidate(player_id=player_id, reason="repair_requested")
    if len(corrected) > 1 and not normalized:
        # "민준, 물가 말고" retracts a choice; it is not a name-only confirmation.
        return _candidate(player_id=player_id, reason="repair_requested")
    for discarded in corrected[:-1]:
        # A discarded whole choice may contain a nickname ("별 등"). Other
        # speaker declarations cannot disappear merely because an action follows.
        if (_norm(discarded) not in action_aliases
                and _mentioned_players(discarded, state["players"]) - {player_id}):
            return _candidate(player_id=player_id, reason="multiple_players")
    actions = scene.get("actions", [])
    matches = [a for a in actions if normalized in _action_aliases(a)]
    # A whole known action can contain a nickname, e.g. "별, 별 등". This exception
    # requires an already-declared owner; it never guesses an owner from the action.
    exact_owned_action = player_id is not None and len(matches) == 1 and not state["finished"]
    if not exact_owned_action and not corrected_action and len(_mentioned_players(text, state["players"])) > 1:
        return _candidate(player_id=player_id, reason="multiple_players")
    if state["finished"]:
        return _candidate(player_id=player_id, reason="game_finished")
    if not normalized:
        return _candidate(player_id=player_id, reason="missing_action")
    _, second_body = _strip_player(body, state["players"])
    if not exact_owned_action and not corrected_action and second_body != body:
        return _candidate(player_id=player_id, reason="multiple_players")
    if not matches and len(corrected) == 1:
        matches = _choice_with_reason(normalized, actions, state["players"])
    if len(matches) != 1:
        if len(_mentioned_actions(normalized, actions)) > 1:
            return _candidate(player_id=player_id, reason="overlap")
        if addressed or any(normalized.startswith(alias + " ") for a in actions for alias in _action_aliases(a)):
            # An unsupported extension must not be guessed by an optional model.
            return _candidate(player_id=player_id, reason="unconfirmed_expansion")
        return _candidate(player_id=player_id, reason="ambiguous_action" if len(matches) > 1 else "unknown_action")
    action_id = matches[0]["id"]
    if len(state["players"]) > 1 and player_id is None:
        return _candidate("ACTION", action_id=action_id, reason="missing_player")
    effective_player = player_id or state["expected_player_id"]
    if effective_player != state["expected_player_id"]:
        return _candidate("ACTION", player_id, action_id, reason="wrong_player")
    return _candidate("ACTION", effective_player, action_id, clarify=False)


def _outcome(action: dict, state: dict) -> dict:
    for candidate in action.get("outcomes", [action]):
        if all(state["flags"].get(k) == v for k, v in candidate.get("when", {}).items()):
            return candidate
    _fail("invalid_content", "행동의 결과가 정의되지 않았어요.")


def apply_action(state: dict, pack: dict, actor_id: str, action_id: str) -> dict:
    scene = _scene(state, pack)
    if state["finished"]:
        _fail("game_finished", "이 모험은 이미 끝났어요.")
    if actor_id != state["expected_player_id"]:
        _fail("wrong_player", "현재 차례인 참여자가 행동해 주세요.")
    action = next((a for a in scene.get("actions", []) if a["id"] == action_id), None)
    if action is None:
        _fail("invalid_action", "이 장면에서 할 수 있는 행동을 골라 주세요.")
    for resource, amount in action.get("cost", {}).items():
        if state["resources"].get(resource, 0) < amount:
            _fail("insufficient_resource", "그 도구를 사용할 자원이 부족해요.")
    chosen = _outcome(action, state)
    result = copy.deepcopy(state)
    for resource, amount in action.get("cost", {}).items():
        result["resources"][resource] -= amount
    for effect in (action, chosen) if chosen is not action else (action,):
        result["flags"].update(effect.get("set_flags", {}))
        result["clues"] = list(dict.fromkeys([*result["clues"], *effect.get("add_clues", [])]))
    score = chosen.get("score", 0)
    if state["scene_id"] in state["hinted_scene_ids"]:
        score = max(0, score - pack.get("hint_score_penalty", 1))
    result["score_by_player"][actor_id] += score
    result["feedback"] = chosen.get("feedback", "")
    result["history"].append({"scene_id": state["scene_id"], "player_id": actor_id, "action_id": action_id, "score_delta": score, "resource_cost": copy.deepcopy(action.get("cost", {}))})
    result["scene_id"] = chosen["next"]
    result["turn_index"] += 1
    next_scene = pack["scenes"][result["scene_id"]]
    result["finished"] = next_scene["kind"] == "ending"
    result["ending"] = next_scene.get("ending_id")
    result["expected_player_id"] = None if result["finished"] else result["players"][result["turn_index"] % len(result["players"])]["player_id"]
    return result


def apply_hint(state: dict, pack: dict) -> dict:
    scene = _scene(state, pack)
    if state["finished"]:
        _fail("game_finished", "이 모험은 이미 끝났어요.")
    if not scene.get("hint"):
        _fail("hint_unavailable", "이 장면에는 추가 힌트가 없어요.")
    result = copy.deepcopy(state)
    if state["scene_id"] not in state["hinted_scene_ids"]:
        if state["resources"].get("hint_tokens", 0) < 1:
            _fail("insufficient_resource", "준비된 힌트를 모두 사용했어요. 선택지를 다시 들을 수 있어요.")
        result["resources"]["hint_tokens"] -= 1
        result["hinted_scene_ids"].append(state["scene_id"])
    result["feedback"] = "힌트야. " + _render(scene["hint"], result, pack)
    return result


__all__ = ["GameError", "load_pack", "validate_pack", "new_game", "present", "interpret", "apply_action", "apply_hint"]
