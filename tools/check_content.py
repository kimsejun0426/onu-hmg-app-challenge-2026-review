#!/usr/bin/env python3
"""Validate the story catalog and exhaust every path at both player counts."""

import argparse
import copy
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "services/api"))

from carplay.game import GameError, apply_action, interpret, load_pack, new_game, present  # noqa: E402
from carplay.catalog import ContentCatalog, DEFAULT_CATALOG  # noqa: E402

PLAYERS = [{"player_id": "review-1", "nickname": "민준"}, {"player_id": "review-2", "nickname": "서윤"}]
INTEREST_IDS = frozenset({"dinosaurs", "space", "animals", "vehicles", "ocean", "stories"})
PLAY_STYLES = frozenset({"explore", "observe", "imagine", "solve"})


def validate_interest_metadata(entry: dict) -> None:
    """Reject missing or invented recommendation evidence at authoring time."""
    tags = entry.get("interest_tags")
    if (not isinstance(tags, list) or not tags
            or any(not isinstance(tag, str) or tag not in INTEREST_IDS for tag in tags)
            or len(set(tags)) != len(tags)):
        raise ValueError(f"Invalid interest_tags: {entry.get('pack_id')}")
    if not isinstance(entry.get("play_style"), str) or entry["play_style"] not in PLAY_STYLES:
        raise ValueError(f"Invalid play_style: {entry.get('pack_id')}")


def catalog_entries(include_legacy: bool = False, catalog_path: Path = DEFAULT_CATALOG) -> list[dict]:
    catalog = ContentCatalog(catalog_path)
    for entry in catalog.all_entries:
        validate_interest_metadata(entry)
        for field in ("title", "subtitle", "description", "art_key", "duration_label"):
            if not isinstance(entry.get(field), str) or not entry[field].strip():
                raise ValueError(f"Missing catalog text: {entry['pack_id']}/{field}")
        if type(entry.get("estimated_minutes")) is not int or entry["estimated_minutes"] < 1:
            raise ValueError("estimated_minutes must be a positive target, not measured duration")
        if type(entry.get("total_choices")) is not int or entry["total_choices"] < 1:
            raise ValueError("total_choices must be a positive integer")
    listed = {catalog.source(entry) for entry in catalog.all_entries}
    discovered = {p.resolve() for p in catalog.content_root.rglob("episode.json")}
    if listed != discovered:
        raise ValueError("Catalog and authored current/historical episodes differ")
    return catalog.all_entries if include_legacy else catalog.current_entries


def check_pack(pack: dict, entry: dict | None = None) -> dict:
    if entry and (pack["pack_id"], pack["version"], pack["title"]) != (
        entry["pack_id"], entry["version"], entry["title"]
    ):
        raise ValueError("Catalog identity does not match its episode")
    summaries = []
    for count in (1, 2):
        pending = [new_game(PLAYERS[:count], pack)]
        finished = []
        expanded = 0
        seen_scenes = set()
        max_prompt_characters = 0
        while pending:
            state = pending.pop()
            expanded += 1
            if expanded > 10000:
                raise GameError("content_too_large", "콘텐츠 검사 경로 한도를 초과했습니다.")
            shown = present(state, pack)
            max_prompt_characters = max(max_prompt_characters, len(shown["prompt"]))
            seen_scenes.add(state["scene_id"])
            if any("{" in shown[field] or "}" in shown[field] for field in ("prompt", "hint", "feedback")):
                raise ValueError(f"Unresolved story template: {state['scene_id']}")
            if any(value < 0 for value in state["resources"].values()):
                raise ValueError("A story path consumed an unavailable resource")
            if state["finished"]:
                finished.append(state)
                continue
            if not shown["actions"]:
                raise GameError("content_dead_end", f"행동 없는 미완료 장면: {state['scene_id']}")
            expected = next(p for p in state["players"] if p["player_id"] == state["expected_player_id"])
            for action in shown["actions"]:
                for alias in [action["id"], action["label"], *action["aliases"]]:
                    candidate = interpret(f"{expected['nickname']}, {alias}", state, pack)
                    if candidate["needs_clarification"] or candidate["action_id"] != action["id"]:
                        raise ValueError(f"Unusable authored alias: {state['scene_id']}/{alias}")
                before = copy.deepcopy(state)
                next_state = apply_action(state, pack, state["expected_player_id"], action["id"])
                if state != before:
                    raise ValueError("Game engine mutated a prior story state")
                pending.append(next_state)
        endings = sorted({state["ending"] for state in finished})
        turns = sorted({state["turn_index"] for state in finished})
        declared_endings = {s["ending_id"] for s in pack["scenes"].values() if s["kind"] == "ending"}
        if set(endings) != declared_endings or seen_scenes != set(pack["scenes"]):
            raise ValueError("Some authored scenes or endings have no executable path")
        if entry and (turns != [entry["total_choices"]] or set(endings) != set(entry["endings"])):
            raise ValueError("Catalog turns/endings do not match executable paths")
        summaries.append({"players": count, "complete_paths": len(finished), "endings": endings,
                          "turn_counts": turns, "max_rendered_prompt_characters": max_prompt_characters})
    return {"pack_id": pack["pack_id"], "version": pack["version"], "scenes": len(pack["scenes"]),
            "player_counts": summaries, "status": "passed", "human_listening_and_playtest": "pending"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("path", nargs="?", help="Check one episode; otherwise check the complete catalog.")
    args = parser.parse_args()
    if args.path:
        result = check_pack(load_pack(args.path))
    else:
        catalog = ContentCatalog()
        catalog_entries(include_legacy=True)
        result = {"status": "passed", "packs": [check_pack(catalog.load(entry["pack_id"], entry["version"]), entry)
                                                  for entry in catalog.current_entries],
                  "legacy_packs": [check_pack(catalog.load(entry["pack_id"], entry["version"]), entry)
                                   for entry in catalog.legacy_entries]}
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    try:
        main()
    except (GameError, ValueError, OSError) as error:
        print(str(error), file=sys.stderr)
        raise SystemExit(1)
