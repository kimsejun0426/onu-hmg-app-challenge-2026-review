"""Published narration survives new content editions and keeps the same solo voice."""

import copy
import hashlib
from pathlib import Path

import pytest

from carplay.catalog import ContentCatalog
from carplay.game import new_game, present, validate_pack
from content_release_expectations import PUBLISHED_IDS

ROOT = Path(__file__).resolve().parents[1]
CATALOG = ContentCatalog()
PLAYERS = [{"player_id": "p1", "nickname": "별"}, {"player_id": "p2", "nickname": "달"}]
PUBLISHED = {
    "dinosaurs": "d7132a107302d8b97d7f9a84696de8ff828b74cf17a6c82d59a07395e45d45d1",
    "starlight-post-office": "274430e9c97f76301209031cc7caca62399393627da476544427faeeea998ac6",
    "undersea-lighthouse": "ebe2f6a4ee8c6c75c4714f4a2c8997c890e27bd84da0068e3bbcc2000bd9729a",
    "space-return-signal": "74940525c9c6b0ff3fa1ec8dbbde3250b20d145235747fd057ef69da9234be74",
    "animal-footprint-garden": "ae564240af3b36466ca921df0c2d7a330f4731a5f35b6843d66cbca2968c7b32",
    "little-car-workshop": "51f046cd8d751b8638c6e54fcfc03c2da0a3605562547edf29275c99f174eb2a",
}
PUBLISHED_030 = {
    "dinosaurs": "fe008f7e7574ec9708b3bf17f99d6b2d41f2cf7ab86d18f03f9f99ba0f7eeedc",
    "starlight-post-office": "4395180acf8b51866271ddb5fcb50d75c491120957eb02f14fd01acb491a0dea",
    "undersea-lighthouse": "11026dcf5de957bf846d2f5305f71ebedaa18602fd0d551af2720ce42369c2cd",
    "space-return-signal": "e5b6594ea0983149632349ad67149f66b371f402346d193e6d4c8e6569f9d37b",
    "animal-footprint-garden": "663cb51957a63337257fecc9ae8ea5085e07893bb98efe205c84de7b2a8b45e0",
    "little-car-workshop": "e560a0ce0571e84f880d44675322dcb9001eee1b281a5f706aa823d6a542178c",
}


@pytest.mark.parametrize("slug,digest", PUBLISHED.items())
def test_published_020_content_bytes_are_preserved(slug, digest):
    source = ROOT / f"content/legacy/0.2.0/{slug}/episode.json"
    assert hashlib.sha256(source.read_bytes()).hexdigest() == digest


@pytest.mark.parametrize("slug,digest", PUBLISHED_030.items())
def test_published_030_content_bytes_are_preserved(slug, digest):
    source = ROOT / f"content/legacy/0.3.0/{slug}/episode.json"
    assert hashlib.sha256(source.read_bytes()).hexdigest() == digest


@pytest.mark.parametrize("entry", CATALOG.all_entries, ids=lambda item: f"{item['pack_id']}@{item['version']}")
def test_all_editions_keep_their_intended_solo_voice_and_two_child_turn(entry):
    pack = CATALOG.load(entry["pack_id"], entry["version"])
    single = new_game(PLAYERS[:1], pack)
    heard = present(single, pack)["prompt"]
    if entry["pack_id"] in PUBLISHED_IDS and entry["version"] == "0.1.0":
        assert heard.startswith(f"별 {single['players'][0]['role']} 차례야. ")
    elif entry["pack_id"] == "dinosaur-lab-missing-egg" and entry["version"] == "0.12.0":
        assert heard == pack["scenes"][single["scene_id"]]["prompt"]
    else:
        assert heard.startswith("별, ")
    paired = new_game(PLAYERS, pack)
    assert present(paired, pack)["prompt"].startswith(f"별 {paired['players'][0]['role']} 차례야. ")


def test_new_edition_voice_is_content_owned_instead_of_a_version_number_exception():
    pack = copy.deepcopy(CATALOG.current())
    pack["version"] = "9.0.0"
    assert validate_pack(pack) == []
    single = new_game(PLAYERS[:1], pack)
    assert present(single, pack)["prompt"] == pack["scenes"][single["scene_id"]]["prompt"]
    pack["solo_prompt_style"] = "nickname"
    assert present(single, pack)["prompt"].startswith("별, ")
    pack["solo_prompt_style"] = "role_and_turn"
    assert "차례야. " in present(new_game(PLAYERS[:1], pack), pack)["prompt"]


@pytest.mark.parametrize("invalid", ["unknown", None, {}, []])
def test_unrecognized_solo_narration_policy_is_rejected(invalid):
    pack = copy.deepcopy(CATALOG.current())
    pack["solo_prompt_style"] = invalid
    assert "solo_prompt_style must be role_and_turn, nickname or body_only" in validate_pack(pack)
