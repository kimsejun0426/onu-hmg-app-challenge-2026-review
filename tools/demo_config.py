"""Local, adult-developer demo configuration. No secrets are checked in."""

from __future__ import annotations

import os
from pathlib import Path
import secrets


ROOT = Path(__file__).resolve().parents[1]
LOCAL = ROOT / ".local"
TOKEN_FILE = LOCAL / "demo-token"


def local_token() -> str:
    LOCAL.mkdir(mode=0o700, parents=True, exist_ok=True)
    if not TOKEN_FILE.exists():
        token = secrets.token_urlsafe(32)
        try:
            fd = os.open(TOKEN_FILE, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(fd, "w", encoding="utf-8") as stream:
                stream.write(token + "\n")
        except FileExistsError:
            pass
    token = TOKEN_FILE.read_text(encoding="utf-8").strip()
    if len(token) < 16:
        raise ValueError("The local demo token is invalid. Replace the private token file.")
    return token
