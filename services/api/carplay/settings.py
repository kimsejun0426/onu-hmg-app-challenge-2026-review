"""Explicit demo configuration; no implicit or embedded accepted credentials."""

from __future__ import annotations

import json
import os
from dataclasses import dataclass, field
from pathlib import Path
from urllib.parse import urlparse


@dataclass(frozen=True)
class Settings:
    db_path: str | Path = Path(".local/carplay.sqlite3")
    demo_tokens: dict[str, str] = field(default_factory=dict)
    semantic_endpoint: str | None = None
    semantic_model: str = "Qwen/Qwen3-4B-Instruct-2507"
    semantic_api_key: str | None = None
    semantic_timeout_seconds: float = 2.0
    resolution_lease_seconds: float = 6.0

    def __post_init__(self) -> None:
        if any(not isinstance(k, str) or len(k) < 16 or not isinstance(v, str) or not v
               for k, v in self.demo_tokens.items()):
            raise ValueError("Demo tokens must have at least 16 characters and map to family IDs.")
        if self.semantic_timeout_seconds <= 0 or self.resolution_lease_seconds <= self.semantic_timeout_seconds:
            raise ValueError("The resolution lease must exceed the positive model timeout.")
        if self.semantic_endpoint:
            u = urlparse(self.semantic_endpoint)
            if u.scheme not in {"http", "https"} or u.hostname not in {"localhost", "127.0.0.1", "::1"}:
                raise ValueError("This prototype only permits a loopback semantic endpoint.")
            if u.username or u.password or u.query or u.fragment:
                raise ValueError("Use semantic_api_key, not credentials in the model URL.")

    @classmethod
    def from_env(cls) -> "Settings":
        tokens = json.loads(os.environ.get("CARPLAY_DEMO_TOKENS", "{}"))
        if not isinstance(tokens, dict):
            raise ValueError("CARPLAY_DEMO_TOKENS must be a JSON object mapping tokens to families.")
        return cls(
            db_path=os.environ.get("CARPLAY_DB_PATH", ".local/carplay.sqlite3"),
            demo_tokens=tokens,
            semantic_endpoint=os.environ.get("CARPLAY_SEMANTIC_ENDPOINT") or None,
            semantic_model=os.environ.get("CARPLAY_SEMANTIC_MODEL", "Qwen/Qwen3-4B-Instruct-2507"),
            semantic_api_key=os.environ.get("CARPLAY_SEMANTIC_API_KEY") or None,
            semantic_timeout_seconds=float(os.environ.get("CARPLAY_SEMANTIC_TIMEOUT_SECONDS", "2")),
            resolution_lease_seconds=float(os.environ.get("CARPLAY_RESOLUTION_LEASE_SECONDS", "6")),
        )
