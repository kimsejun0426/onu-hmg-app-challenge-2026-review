"""A new first edition does not invent history or replace published editions."""

import hashlib
import json
from pathlib import Path

import pytest

from carplay.catalog import ContentCatalog
from carplay.game import new_game, present
from content_release_expectations import (
    CURRENT_VERSIONS, LEGACY_IDENTITIES, NEW_PACK_ID, PUBLISHED_040, PUBLISHED_040_METADATA,
    PUBLISHED_CONTEXT_070,
)

ROOT = Path(__file__).resolve().parents[1]
CATALOG = ContentCatalog()


def test_current_and_historical_identity_sets_are_complete_and_disjoint():
    current = [(entry["pack_id"], entry["version"]) for entry in CATALOG.current_entries]
    legacy = [(entry["pack_id"], entry["version"]) for entry in CATALOG.legacy_entries]
    assert len(current) == len(set(current)) == len(CURRENT_VERSIONS)
    assert dict(current) == CURRENT_VERSIONS
    assert len(legacy) == len(set(legacy)) == len(LEGACY_IDENTITIES)
    assert set(legacy) == LEGACY_IDENTITIES
    assert set(current).isdisjoint(legacy)
    assert CATALOG.default_pack_id == "dinosaur-lab-missing-egg"


@pytest.mark.parametrize("slug,digest", PUBLISHED_040.items())
def test_published_040_bytes_and_archived_catalog_identity_are_preserved(slug, digest):
    source = ROOT / f"content/legacy/0.4.0/{slug}/episode.json"
    assert hashlib.sha256(source.read_bytes()).hexdigest() == digest
    pack = json.loads(source.read_text(encoding="utf-8"))
    entry = next(e for e in CATALOG.legacy_entries if (e["pack_id"], e["version"]) ==
                 (pack["pack_id"], "0.4.0"))
    assert CATALOG.source(entry) == source.resolve()
    assert entry["title"] == pack["title"]
    metadata = {key: value for key, value in entry.items() if key != "content_asset"}
    stable = json.dumps(metadata, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()
    assert hashlib.sha256(stable).hexdigest() == PUBLISHED_040_METADATA[pack["pack_id"]]


def test_picnic_keeps_all_published_editions_without_fabricated_history():
    pack = CATALOG.current(NEW_PACK_ID)
    assert pack["version"] == CURRENT_VERSIONS[NEW_PACK_ID]
    assert pack["max_turns"] == 4
    assert pack["solo_prompt_style"] == "nickname"
    assert [(e["pack_id"], e["version"]) for e in CATALOG.legacy_entries
            if e["pack_id"] == NEW_PACK_ID] == [(NEW_PACK_ID, "0.1.0"), (NEW_PACK_ID, "0.2.0"), (NEW_PACK_ID, "0.3.0"), (NEW_PACK_ID, "0.4.0"), (NEW_PACK_ID, "0.5.0")]
    for preserved_version in ("0.1.0", "0.2.0", "0.3.0", "0.4.0", "0.5.0"):
        assert CATALOG.load(NEW_PACK_ID, preserved_version)["max_turns"] == 4
    for absent_version in ("0.0.0", "0.7.0", "0.8.0"):
        with pytest.raises(KeyError):
            CATALOG.load(NEW_PACK_ID, absent_version)
    players = [{"player_id": "p1", "nickname": "별"}]
    assert present(new_game(players, pack), pack)["prompt"].startswith("별, ")


@pytest.mark.parametrize("pack_id", PUBLISHED_CONTEXT_070)
def test_context_revision_archives_exact_070_bytes_and_only_repoints_catalog_path(pack_id):
    byte_count, digest, metadata_digest = PUBLISHED_CONTEXT_070[pack_id]
    source = ROOT / f"content/legacy/0.7.0/{pack_id}/episode.json"
    raw = source.read_bytes()
    assert len(raw) == byte_count
    assert hashlib.sha256(raw).hexdigest() == digest
    entry = next(e for e in CATALOG.legacy_entries if
                 (e["pack_id"], e["version"]) == (pack_id, "0.7.0"))
    assert CATALOG.source(entry) == source.resolve()
    metadata = {key: value for key, value in entry.items() if key != "content_asset"}
    stable = json.dumps(metadata, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()
    assert hashlib.sha256(stable).hexdigest() == metadata_digest
    assert CATALOG.load(pack_id, "0.7.0") == json.loads(raw)
    # This check records the 0.8 rewrite; later prose revisions have their own contract.
    current = next(e for e in CATALOG.all_entries if
                   (e["pack_id"], e["version"]) == (pack_id, "0.8.0"))
    assert current["version"] == CATALOG.load(pack_id, "0.8.0")["version"] == "0.8.0"
    assert current["content_asset"] == f"content/legacy/0.8.0/{pack_id}/episode.json"
    assert {k: v for k, v in current.items() if k not in {"content_asset", "version"}} == {
        k: v for k, v in metadata.items() if k != "version"
    }
