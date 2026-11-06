"""Versioned content survives process restart without silently changing an old adventure."""

import asyncio
import copy
import json
import uuid

import httpx
import pytest

from carplay import game
from carplay.catalog import ContentCatalog, DEFAULT_CATALOG
from carplay.main import create_app
from carplay.settings import Settings
from carplay.storage import Store, encode

TOKEN = "catalog-test-family-token-12345"
PLAYERS = [{"player_id": "p1", "nickname": "민준"}, {"player_id": "p2", "nickname": "서윤"}]
CATALOG = ContentCatalog()


def test_lookup_requires_exact_id_and_version_and_keeps_current_default():
    for entry in CATALOG.current_entries:
        current = CATALOG.current(entry["pack_id"])
        assert current["version"] == entry["version"]
        assert CATALOG.current(entry["pack_id"]) is current
        with pytest.raises(KeyError):
            CATALOG.load(entry["pack_id"], "0.0.99")
    for entry in CATALOG.legacy_entries:
        old = CATALOG.load(entry["pack_id"], entry["version"])
        assert old["version"] == entry["version"]
        assert old["pack_id"] == CATALOG.current(entry["pack_id"])["pack_id"]
        assert old["version"] != CATALOG.current(entry["pack_id"])["version"]
    with pytest.raises(KeyError):
        CATALOG.load("not-installed", "0.1.0")


def write_catalog(tmp_path, document):
    root = tmp_path / "content"
    root.mkdir(exist_ok=True)
    for entry in CATALOG.all_entries:
        target = tmp_path / entry["content_asset"]
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(CATALOG.source(entry).read_bytes())
    path = root / "catalog.json"
    path.write_text(json.dumps(document), encoding="utf-8")
    return path


@pytest.mark.parametrize("case", ["duplicate-current", "duplicate-version", "escape", "archive-as-current", "unknown-default"])
def test_catalog_rejects_ambiguous_or_unbundled_version_registration(tmp_path, case):
    document = json.loads(DEFAULT_CATALOG.read_text(encoding="utf-8"))
    if case == "duplicate-current":
        document["packs"].append(copy.deepcopy(document["packs"][0]))
    elif case == "duplicate-version":
        document["legacy_packs"].append(copy.deepcopy(document["legacy_packs"][0]))
    elif case == "escape":
        document["packs"][0]["content_asset"] = "content/../outside.json"
    elif case == "archive-as-current":
        document["packs"][0] = copy.deepcopy(document["legacy_packs"][0])
    else:
        document["default_pack_id"] = "not-installed"
    with pytest.raises(ValueError):
        ContentCatalog(write_catalog(tmp_path, document))


def test_catalog_rejects_file_identity_mismatch_instead_of_loading_wrong_version(tmp_path):
    document = json.loads(DEFAULT_CATALOG.read_text(encoding="utf-8"))
    entry = document["legacy_packs"][0]
    entry["version"] = "0.0.99"
    catalog = ContentCatalog(write_catalog(tmp_path, document))
    with pytest.raises(ValueError, match="identity"):
        catalog.load(entry["pack_id"], "0.0.99")


def envelope(state, kind, payload=None):
    return {"command_id": str(uuid.uuid4()), "expected_version": state["version"],
            "turn_id": state["turn_id"], "turn_epoch": state["turn_epoch"],
            "type": kind, "payload": payload or {}}


async def send(client, state, kind, payload=None):
    response = await client.post(f"/v1/sessions/{state['session_id']}/commands", json=envelope(state, kind, payload))
    assert response.status_code == 200, response.text
    return response.json()["snapshot"]


async def listen(client, state):
    return await send(client, state, "playback.completed", {"playback_id": state["speech_plan"]["playback_id"]})


async def choose(client, state, text):
    return await send(client, state, "action.submit", {"text": text, "is_final": True,
        "capture_id": str(uuid.uuid4()), "listen_window_id": state["listen_window_id"],
        "locale": "ko-KR", "input_source": "mock"})


@pytest.mark.parametrize("entry", CATALOG.all_entries, ids=lambda e: f"{e['pack_id']}@{e['version']}")
def test_saved_edition_reopens_and_resumes_with_its_original_content_and_rules(tmp_path, entry):
    async def scenario():
        settings = Settings(db_path=tmp_path / "versions.sqlite3", demo_tokens={TOKEN: "family"})
        old_pack = CATALOG.load(entry["pack_id"], entry["version"])
        old_app = create_app(settings, pack=old_pack)
        headers = {"Authorization": f"Bearer {TOKEN}"}
        async with old_app.router.lifespan_context(old_app):
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=old_app), base_url="http://test", headers=headers) as client:
                response = await client.post("/v1/sessions", json={"players": PLAYERS})
                assert response.status_code == 201
                state = await listen(client, response.json())
                action = state["presentation"]["actions"][0]["id"]
                state = await choose(client, state, f"민준, {action}")
                state = await send(client, state, "session.pause", {"reason": "QUIET_REQUEST"})
                saved = copy.deepcopy(state)

        # A new API instance has the current default pack and resolves persisted old packs.
        app = create_app(settings)
        async with app.router.lifespan_context(app):
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test", headers=headers) as client:
                url = f"/v1/sessions/{saved['session_id']}"
                restored = await client.get(f"{url}/snapshot")
                assert restored.status_code == 200, restored.text
                assert restored.json() == saved
                assert saved["speech_plan"] is None and saved["status"] == "PAUSED"
                request = envelope(saved, "session.resume")
                first = await client.post(f"{url}/commands", json=request)
                retried = await client.post(f"{url}/commands", json=request)
                assert first.status_code == retried.status_code == 200
                assert first.json() == retried.json()
                resumed = first.json()["snapshot"]
                assert resumed["game"] == saved["game"]
                assert resumed["turn_id"] != saved["turn_id"]
                assert resumed["presentation"] == {**game.present(saved["game"], old_pack), "input_kind": "choice"}
                ready = await listen(client, resumed)
                hinted = await choose(client, ready, "힌트")
                expected_hint = game.apply_hint(ready["game"], old_pack)
                assert hinted["game"] == expected_hint
                ready = await listen(client, hinted)
                next_action = ready["presentation"]["actions"][0]["id"]
                owner = next(p["nickname"] for p in ready["game"]["players"]
                             if p["player_id"] == ready["game"]["expected_player_id"])
                changed = await choose(client, ready, f"{owner}, {next_action}")
                assert changed["game"] == game.apply_action(expected_hint, old_pack,
                    expected_hint["expected_player_id"], next_action)
                fresh = await client.post("/v1/sessions", json={"players": PLAYERS[:1]})
                assert fresh.status_code == 201
                assert fresh.json()["game"]["pack_id"] == CATALOG.default_pack_id
                assert fresh.json()["game"]["pack_version"] == CATALOG.current()["version"]

    asyncio.run(scenario())


def test_unknown_persisted_content_version_is_rejected_without_overwriting_state(tmp_path):
    async def scenario():
        settings = Settings(db_path=tmp_path / "unknown.sqlite3", demo_tokens={TOKEN: "family"})
        app = create_app(settings)
        headers = {"Authorization": f"Bearer {TOKEN}"}
        async with app.router.lifespan_context(app):
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test", headers=headers) as client:
                created = await client.post("/v1/sessions", json={"players": PLAYERS})
                assert created.status_code == 201
                state = created.json()
                store: Store = app.state.service.store
                with store.transaction() as db:
                    raw = json.loads(db.execute("SELECT snapshot_json FROM sessions WHERE session_id=?", (state["session_id"],)).fetchone()[0])
                    raw["game"]["pack_version"] = "0.0.99"
                    preserved = encode(raw)
                    db.execute("UPDATE sessions SET snapshot_json=? WHERE session_id=?", (preserved, state["session_id"]))
                url = f"/v1/sessions/{state['session_id']}"
                for response in (await client.get(f"{url}/snapshot"),
                                 await client.post(f"{url}/commands", json=envelope(state, "session.resume"))):
                    assert response.status_code == 409
                    assert response.json()["error"]["code"] == "CONTENT_VERSION_UNAVAILABLE"
                with store.transaction() as db:
                    assert db.execute("SELECT snapshot_json FROM sessions WHERE session_id=?", (state["session_id"],)).fetchone()[0] == preserved
                    assert db.execute("SELECT COUNT(*) FROM command_receipts").fetchone()[0] == 0
                    assert db.execute("SELECT COUNT(*) FROM session_events").fetchone()[0] == 1

    asyncio.run(scenario())
