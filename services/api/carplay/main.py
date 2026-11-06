"""Local-development HTTP API. No DB is opened merely by importing this module."""

from __future__ import annotations

import hmac
import time
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, Header, Query, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from . import game
from .catalog import ContentCatalog
from .contracts import Command, CreateSession
from .semantic import DisabledResolver, LocalQwenResolver, SemanticResolver
from .service import ServiceError, SessionService
from .settings import Settings
from .storage import Store


def create_app(settings: Settings | None = None, semantic_resolver: SemanticResolver | None = None,
               pack: dict | None = None, clock=time.time, catalog: ContentCatalog | None = None) -> FastAPI:
    settings = settings or Settings.from_env()

    @asynccontextmanager
    async def lifespan(application: FastAPI):
        yield
        if application.state.service is not None:
            application.state.service.store.close()
            application.state.service = None

    app = FastAPI(title="Car Playmate prototype API", version="0.1.0", lifespan=lifespan,
                  description="Local adult-development prototype. Pleos approval, production parent auth and PostgreSQL are not implemented.")
    app.state.service = None
    app.state.settings = settings

    def service() -> SessionService:
        # Called synchronously from async routes: no await between check and assignment.
        if app.state.service is None:
            resolver = semantic_resolver or (LocalQwenResolver(settings) if settings.semantic_endpoint else DisabledResolver())
            installed = catalog or (ContentCatalog() if pack is None else None)
            initial_pack = pack if pack is not None else installed.current()
            app.state.service = SessionService(Store(settings.db_path), settings, initial_pack, resolver, clock,
                                              pack_lookup=installed.load if installed is not None else None)
        return app.state.service

    async def family(authorization: str | None = Header(default=None)) -> str:
        if not authorization or not authorization.startswith("Bearer "):
            raise ServiceError(401, "AUTH_REQUIRED", "개발용 기기 인증이 필요해요.")
        candidate = authorization[7:]
        for token, family_id in settings.demo_tokens.items():
            if hmac.compare_digest(candidate.encode(), token.encode()):
                return family_id
        raise ServiceError(401, "AUTH_INVALID", "기기 인증을 확인해 주세요.")

    @app.exception_handler(ServiceError)
    async def service_error_handler(request: Request, exc: ServiceError):
        error = {"code": exc.code, "message": exc.message}
        if exc.session_id:
            error["snapshot_url"] = f"/v1/sessions/{exc.session_id}/snapshot"
        return JSONResponse(status_code=exc.status, content={"error": error}, headers={"Cache-Control": "no-store"})

    @app.exception_handler(RequestValidationError)
    async def validation_error_handler(request: Request, exc: RequestValidationError):
        # FastAPI's default validation response echoes `input`, including a child's transcript.
        return JSONResponse(status_code=422, content={"error": {"code": "INVALID_REQUEST", "message": "요청 형식을 확인해 주세요."}},
                            headers={"Cache-Control": "no-store"})

    @app.exception_handler(game.GameError)
    async def game_error_handler(request: Request, exc: game.GameError):
        # Do not echo invalid nicknames/utterances from engine validation.
        return JSONResponse(status_code=422, content={"error": {"code": "INVALID_GAME_INPUT", "message": "참여자 또는 게임 입력을 확인해 주세요."}},
                            headers={"Cache-Control": "no-store"})

    @app.middleware("http")
    async def no_store(request: Request, call_next):
        response = await call_next(request)
        response.headers["Cache-Control"] = "no-store"
        return response

    @app.get("/health/live")
    async def live():
        return {"status": "ok", "mode": "local-prototype"}

    @app.get("/health/ready")
    async def ready():
        configured = bool(settings.demo_tokens)
        dependencies_ready = False
        if configured:
            try:
                with service().store.transaction() as db:
                    db.execute("SELECT 1").fetchone()
                dependencies_ready = True
            except Exception:
                pass
        return JSONResponse(status_code=200 if configured and dependencies_ready else 503, content={
            "ready": configured and dependencies_ready, "auth_configured": configured, "mode": "local-prototype",
            "semantic": "configured-unverified" if settings.semantic_endpoint else "disabled",
            "speech": "client-adapter-required", "storage": "sqlite",
        })

    @app.post("/v1/sessions", status_code=201)
    async def create_session(body: CreateSession, family_id: str = Depends(family)):
        return service().create(family_id, [p.model_dump() for p in body.players])

    @app.get("/v1/sessions/{session_id}/snapshot")
    async def snapshot(session_id: str, family_id: str = Depends(family)):
        return service().snapshot(family_id, session_id)

    @app.get("/v1/sessions/{session_id}/commands/{command_id}")
    async def receipt(session_id: str, command_id: str, family_id: str = Depends(family)):
        return service().receipt(family_id, session_id, command_id)

    @app.get("/v1/sessions/{session_id}/events")
    async def events(session_id: str, after_seq: int = Query(default=0, ge=0), family_id: str = Depends(family)):
        return service().events(family_id, session_id, after_seq)

    @app.post("/v1/sessions/{session_id}/commands")
    async def command(session_id: str, body: Command, family_id: str = Depends(family)):
        return await service().command(family_id, session_id, body)

    return app


app = create_app()
