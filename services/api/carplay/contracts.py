"""Transport contracts. User text is transient and never an event payload."""

from __future__ import annotations

import unicodedata
from typing import Annotated, Any, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

Identifier = Annotated[str, Field(min_length=1, max_length=96, pattern=r"^[A-Za-z0-9_-]+$")]


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)


class Player(StrictModel):
    player_id: Annotated[str, Field(min_length=1, max_length=64, pattern=r"^[A-Za-z0-9_-]+$")]
    nickname: Annotated[str, Field(min_length=1, max_length=12)]

    @field_validator("nickname")
    @classmethod
    def clean_nickname(cls, value: str) -> str:
        value = " ".join(unicodedata.normalize("NFC", value).split())
        if not value or any(unicodedata.category(c).startswith("C") for c in value):
            raise ValueError("A nickname must be printable and nonempty.")
        return value


class CreateSession(StrictModel):
    players: Annotated[list[Player], Field(min_length=1, max_length=2)]

    @model_validator(mode="after")
    def unique_players(self) -> "CreateSession":
        if len({p.player_id for p in self.players}) != len(self.players):
            raise ValueError("Player IDs must be different.")
        if len({p.nickname.casefold() for p in self.players}) != len(self.players):
            raise ValueError("Player nicknames must be different.")
        return self


class ActionPayload(StrictModel):
    text: Annotated[str, Field(min_length=1, max_length=512)]
    is_final: Literal[True]
    capture_id: Identifier
    listen_window_id: Identifier
    locale: Literal["ko-KR"] = "ko-KR"
    input_source: Literal["mock", "pleos_stt", "android_on_device", "touch"] = "mock"


class PlaybackPayload(StrictModel):
    playback_id: Identifier


class PausePayload(StrictModel):
    reason: Literal["USER", "QUIET_REQUEST", "NO_RESPONSE", "AUDIO_FOCUS", "NETWORK",
                    "MIC_PERMISSION", "VEHICLE_POLICY", "SUSPEND"] = "QUIET_REQUEST"


class EmptyPayload(StrictModel):
    pass


class Command(StrictModel):
    command_id: Identifier
    expected_version: Annotated[int, Field(ge=1)]
    turn_id: Identifier
    turn_epoch: Annotated[int, Field(ge=1)]
    type: Literal["playback.completed", "action.submit", "session.pause", "session.resume", "session.end"]
    payload: dict[str, Any]

    @model_validator(mode="after")
    def validate_payload(self) -> "Command":
        schema = {
            "playback.completed": PlaybackPayload,
            "action.submit": ActionPayload,
            "session.pause": PausePayload,
            "session.resume": EmptyPayload,
            "session.end": EmptyPayload,
        }[self.type]
        self.payload = schema.model_validate(self.payload).model_dump()
        return self


class SemanticCandidate(StrictModel):
    """A model cannot issue controls, score changes, tools, URLs or DB commands."""

    intent: Literal["ACTION", "UNKNOWN"]
    declared_player_id: Identifier | None
    action_id: Identifier | None
    needs_clarification: bool

