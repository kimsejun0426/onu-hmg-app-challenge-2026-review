"""Explicit Korean particles retain every published action and existing session edition."""

import copy
import hashlib
import json
from pathlib import Path

import pytest

from carplay.catalog import ContentCatalog
from carplay.game import _render, apply_action, new_game, present

ROOT = Path(__file__).resolve().parents[1]
CATALOG = ContentCatalog()
FROZEN = json.loads((ROOT / "tests/fixtures/published_onu_015_content.json").read_text(encoding="utf-8"))
VERSIONS = {"little-car-workshop": ("0.6.0", "0.7.0"), "onu-little-picnic": ("0.2.0", "0.3.0")}
PLAYERS = [{"player_id": "p1", "nickname": "민준"}, {"player_id": "p2", "nickname": "서윤"}]
CAR_OBJECTS = {"PLANTS": "키 큰 화분을", "BOOKS": "그림책 상자를"}
CAR_COMPANIONS = {"PLANTS": "키 큰 화분과", "BOOKS": "그림책 상자와"}
TOY_COMPANIONS = {"KITE": "종이 연과", "PINWHEEL": "바람개비와", "LEAF_BOAT": "나뭇잎 배와", "PAPER_BOAT": "종이배와"}
WRONG_PARTICLES = ("그림책 상자을", "그림책 상자과", "바람개비과", "나뭇잎 배과", "종이배과",
                   "키 큰 화분를", "키 큰 화분와", "종이 연와")


def rendered_texts(pack, state):
    """Inspect actual shown/spoken strings, including journal summaries omitted by present()."""
    view = present(state, pack)
    texts = {field: view[field] for field in ("prompt", "hint", "feedback")}
    scene = pack["scenes"][state["scene_id"]]
    for field in ("title", "ending_title", "summary"):
        if field in scene:
            texts[field] = _render(scene[field], state, pack)
    for index, action in enumerate(view["actions"]):
        texts[f"action-{index}"] = action["label"]
    return texts


@pytest.mark.parametrize("published", FROZEN["packs"], ids=lambda entry: entry["pack_id"])
def test_delivered_015_original_bytes_and_catalog_metadata_are_preserved(published):
    assert FROZEN["source_release"] == "0.15.0-demo"
    assert FROZEN["source_commit"] == "da28dfc46f7a7b7f5be63e3d6d23dc4c3610dd4b"
    assert FROZEN["source_apk_sha256"] == "62cb7510cd403e37ead1799fcaa721650a71b6f1cfac864f6ec0284638452af7"
    assert {(entry["pack_id"], entry["version"]) for entry in FROZEN["packs"]} == {
        (pack_id, versions[0]) for pack_id, versions in VERSIONS.items()
    }
    raw = (ROOT / published["content_asset"]).read_bytes()
    assert len(raw) == published["bytes"]
    assert hashlib.sha256(raw).hexdigest() == published["sha256"]
    entry = next(entry for entry in CATALOG.legacy_entries if
                 (entry["pack_id"], entry["version"]) == (published["pack_id"], published["version"]))
    assert entry["content_asset"] == published["content_asset"]
    assert {key: value for key, value in entry.items() if key != "content_asset"} == published["catalog_metadata"]
    assert CATALOG.load(published["pack_id"], published["version"]) == json.loads(raw)
    # The 0.16 particle edit remains a version-exact historical contract even
    # when a later narration revision becomes the current edition.
    current = next(entry for entry in CATALOG.all_entries if
                   (entry["pack_id"], entry["version"]) ==
                   (published["pack_id"], VERSIONS[published["pack_id"]][1]))
    assert current["version"] == VERSIONS[published["pack_id"]][1]
    assert {key: value for key, value in current.items() if key not in {"content_asset", "version"}} == {
        key: value for key, value in published["catalog_metadata"].items() if key != "version"
    }


@pytest.mark.parametrize("pack_id", VERSIONS)
def test_only_the_three_particle_locations_and_flat_labels_change(pack_id):
    previous_version, current_version = VERSIONS[pack_id]
    current = copy.deepcopy(CATALOG.load(pack_id, current_version))
    previous = copy.deepcopy(CATALOG.load(pack_id, previous_version))
    assert current["version"] == current_version
    labels = {"cargo_object", "cargo_with"} if pack_id == "little-car-workshop" else {"toy_with"}
    assert set(current["template_labels"]) - set(previous["template_labels"]) == labels
    for name in labels:
        label = current["template_labels"].pop(name)
        assert label["flag"] == ("delivery" if pack_id == "little-car-workshop" else "toy")
        for text in [label["default"], *label["values"].values()]:
            assert "{" not in text and "}" not in text
    locations = [("choose_vehicle", "prompt"), ("ending_relay", "prompt")] if pack_id == "little-car-workshop" else [
        ("ending_farewell", "summary")
    ]
    for scene, field in locations:
        current["scenes"][scene][field] = previous["scenes"][scene][field]
    current["version"] = previous_version
    # This includes every alias, feedback, source, score, resource, role, flag and branch.
    assert current == previous


@pytest.mark.parametrize("pack_id", VERSIONS)
@pytest.mark.parametrize("count", [1, 2])
def test_every_rendered_path_has_the_selected_nouns_particles_and_unchanged_game_effects(pack_id, count):
    current = CATALOG.load(pack_id, VERSIONS[pack_id][1])
    previous = CATALOG.load(pack_id, VERSIONS[pack_id][0])
    pending = [(new_game(PLAYERS[:count], current), new_game(PLAYERS[:count], previous))]
    completed = 0
    covered_objects, covered_companions = set(), set()
    while pending:
        state, old = pending.pop()
        effects = copy.deepcopy(state)
        effects["pack_version"] = old["pack_version"]
        assert effects == old
        before = copy.deepcopy(state)
        rendered, old_rendered = rendered_texts(current, state), rendered_texts(previous, old)
        for text in rendered.values():
            assert "{" not in text and "}" not in text
            for wrong in WRONG_PARTICLES:
                assert wrong not in text, (pack_id, state["scene_id"], text)
        scene = state["scene_id"]
        allowed_change = None
        if pack_id == "little-car-workshop":
            delivery = state["flags"].get("delivery")
            if scene == "choose_vehicle":
                allowed_change = "prompt"
                assert f"{CAR_OBJECTS[delivery]} 실을 차" in rendered["prompt"]
                covered_objects.add(delivery)
                if delivery == "BOOKS":
                    assert "그림책 상자을" in old_rendered["prompt"]
            elif scene == "ending_relay":
                allowed_change = "prompt"
                assert rendered["prompt"].startswith(f"{CAR_COMPANIONS[delivery]} 함께 ")
                covered_companions.add(delivery)
                if delivery == "BOOKS":
                    assert "그림책 상자과" in old_rendered["prompt"]
        elif scene == "ending_farewell":
            allowed_change = "summary"
            toy = state["flags"]["toy"]
            assert f"{TOY_COMPANIONS[toy]} 놀고" in rendered["summary"]
            covered_companions.add(toy)
            if toy != "KITE":
                original_noun = {"PINWHEEL": "바람개비", "LEAF_BOAT": "나뭇잎 배", "PAPER_BOAT": "종이배"}[toy]
                assert f"{original_noun}과 놀고" in old_rendered["summary"]
        assert {key: value for key, value in rendered.items() if key != allowed_change} == {
            key: value for key, value in old_rendered.items() if key != allowed_change
        }
        assert state == before
        if state["finished"]:
            completed += 1
            assert state["turn_index"] == (6 if pack_id == "little-car-workshop" else 4)
            continue
        choices = present(state, current)["actions"]
        assert choices == present(old, previous)["actions"]
        for action in choices:
            pending.append((apply_action(state, current, state["expected_player_id"], action["id"]),
                            apply_action(old, previous, old["expected_player_id"], action["id"])))
    if pack_id == "little-car-workshop":
        assert completed == 64
        assert covered_objects == covered_companions == {"PLANTS", "BOOKS"}
    else:
        assert completed == 16
        assert covered_companions == set(TOY_COMPANIONS)
