"""Version-exact authored content lookup; historical sessions never use a newer pack."""

from __future__ import annotations

import json
from pathlib import Path, PurePosixPath

from . import game

DEFAULT_CATALOG = Path(__file__).resolve().parents[3] / "content/catalog.json"


class ContentCatalog:
    def __init__(self, path: str | Path = DEFAULT_CATALOG):
        self.path = Path(path).resolve()
        self.content_root = self.path.parent
        self.project_root = self.content_root.parent
        document = json.loads(self.path.read_text(encoding="utf-8"))
        if (not isinstance(document, dict) or document.get("schema_version") != 1
                or not isinstance(document.get("packs"), list) or not document["packs"]
                or not isinstance(document.get("legacy_packs", []), list)):
            raise ValueError("Invalid content catalog schema")
        self.current_entries = document["packs"]
        self.legacy_entries = document.get("legacy_packs", [])
        self.default_pack_id = document.get("default_pack_id")
        self._entries: dict[tuple[str, str], dict] = {}
        self._packs: dict[tuple[str, str], dict] = {}
        current_ids: set[str] = set()
        for historical, entries in ((False, self.current_entries), (True, self.legacy_entries)):
            for entry in entries:
                if not isinstance(entry, dict) or any(
                    not isinstance(entry.get(key), str) or not entry[key].strip()
                    for key in ("pack_id", "version", "content_asset")
                ):
                    raise ValueError("Catalog entries need pack_id, version and content_asset")
                identity = (entry["pack_id"], entry["version"])
                if identity in self._entries or (not historical and entry["pack_id"] in current_ids):
                    raise ValueError("Duplicate catalog identity")
                source = self.source(entry)
                archive = self.content_root / "legacy"
                if historical != source.is_relative_to(archive):
                    raise ValueError("Current and historical content paths must be separate")
                self._entries[identity] = entry
                if not historical:
                    current_ids.add(entry["pack_id"])
        if self.default_pack_id not in current_ids:
            raise ValueError("Catalog default must be a current installed pack")

    @property
    def all_entries(self) -> list[dict]:
        return [*self.current_entries, *self.legacy_entries]

    def source(self, entry: dict) -> Path:
        asset = entry["content_asset"]
        relative = PurePosixPath(asset)
        if ("\\" in asset or relative.is_absolute() or ".." in relative.parts
                or not relative.parts or relative.parts[0] != "content"):
            raise ValueError("Catalog asset must be a relative content/ path")
        source = (self.project_root / asset).resolve()
        if not source.is_relative_to(self.content_root) or not source.is_file():
            raise ValueError("Catalog asset is not bundled inside content/")
        return source

    def load(self, pack_id: str, version: str) -> dict:
        """Unknown versions raise KeyError; no latest-version or filename fallback."""
        identity = (pack_id, version)
        if identity not in self._packs:
            entry = self._entries[identity]
            pack = game.load_pack(self.source(entry))
            if (pack["pack_id"], pack["version"]) != identity:
                raise ValueError("Catalog identity differs from its content")
            self._packs[identity] = pack
        return self._packs[identity]

    def current(self, pack_id: str | None = None) -> dict:
        selected = pack_id or self.default_pack_id
        for entry in self.current_entries:
            if entry["pack_id"] == selected:
                return self.load(entry["pack_id"], entry["version"])
        raise KeyError(selected)
