"""Text simulation of the real HTTP contracts; does not record or synthesize speech.

Default: same-loop HTTPX ASGI transport + persistent SQLite. --api-url connects to
the same local server used by the Android developer client. Display completion
simulates a playback callback; it is never evidence of actual speaker playback.
"""

from __future__ import annotations

import argparse
import asyncio
from contextlib import AsyncExitStack
import json
from pathlib import Path
import sys
from uuid import uuid4

from demo_config import LOCAL, ROOT, TOKEN_FILE, local_token

sys.path.insert(0, str(ROOT / "services" / "api"))


class Demo:
    def __init__(self, client, token: str):
        self.client = client
        self.headers = {"Authorization": f"Bearer {token}"}
        self.snapshot: dict = {}

    async def request(self, method: str, path: str, body: dict | None = None) -> dict:
        kwargs = {"headers": self.headers}
        if body is not None:
            kwargs["json"] = body
        response = await self.client.request(method, path, **kwargs)
        if response.status_code >= 400:
            try:
                error = response.json().get("error", {})
            except ValueError:
                error = {}
            raise RuntimeError(f"HTTP {response.status_code}: {error.get('code', 'request_failed')}")
        return response.json()

    async def refresh(self) -> dict:
        self.snapshot = await self.request("GET", f"/v1/sessions/{self.snapshot['session_id']}/snapshot")
        return self.snapshot

    async def command(self, kind: str, payload: dict | None = None) -> dict:
        current = self.snapshot
        body = {
            "command_id": str(uuid4()),
            "expected_version": current["version"],
            "turn_id": current["turn_id"],
            "turn_epoch": current["turn_epoch"],
            "type": kind,
            "payload": payload or {},
        }
        result = await self.request("POST", f"/v1/sessions/{current['session_id']}/commands", body)
        snapshot = result.get("snapshot")
        if snapshot and snapshot["version"] >= self.snapshot["version"]:
            self.snapshot = snapshot
        if result.get("command_status") == "PENDING":
            await self.refresh()
        return result

    async def display_prompt(self) -> None:
        plan = self.snapshot.get("speech_plan")
        if not plan:
            return
        for segment in plan.get("segments", []):
            print(f"\n진행자: {segment['text']}")
        if self.snapshot["status"] == "ACTIVE" and self.snapshot["phase"] in {"PROMPTING", "FEEDBACK"}:
            await self.command("playback.completed", {"playback_id": plan["playback_id"]})


async def main() -> None:
    parser = argparse.ArgumentParser(description="공룡 모험 개발용 텍스트 플레이")
    parser.add_argument("--api-url", help="선택: 실행 중인 로컬 API, 예: http://127.0.0.1:8000")
    parser.add_argument("--token-file", type=Path, default=TOKEN_FILE)
    parser.add_argument("--players", type=int, choices=[1, 2], default=2)
    parser.add_argument("--auto", choices=["first", "last", "success", "assist"], help="개발용 자동 진행: first/last 또는 두 결말 success/assist")
    parser.add_argument("--resume", action="store_true", help="이전 CLI 세션을 명시적으로 이어하기")
    args = parser.parse_args()
    token = args.token_file.read_text(encoding="utf-8").strip() if args.api_url else local_token()
    session_file = LOCAL / ("remote-session.json" if args.api_url else "cli-session.json")
    LOCAL.mkdir(parents=True, exist_ok=True)
    print("개발용 텍스트 시뮬레이션 · 실제 마이크·차량 음성 재생 시험이 아닙니다.")
    async with AsyncExitStack() as stack:
        if args.api_url:
            import httpx
            client = await stack.enter_async_context(httpx.AsyncClient(base_url=args.api_url, timeout=10, trust_env=False))
        else:
            import httpx
            from carplay.main import create_app
            from carplay.settings import Settings
            app = create_app(Settings(db_path=LOCAL / "cli.db", demo_tokens={token: "family-demo-local"}))
            await stack.enter_async_context(app.router.lifespan_context(app))
            client = await stack.enter_async_context(httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://demo"))
        demo = Demo(client, token)
        if args.resume:
            if not session_file.exists():
                parser.error("이어갈 CLI 세션이 없습니다. --resume 없이 시작하세요.")
            session_id = json.loads(session_file.read_text(encoding="utf-8"))["session_id"]
            demo.snapshot = await demo.request("GET", f"/v1/sessions/{session_id}/snapshot")
            if demo.snapshot["status"] == "PAUSED":
                await demo.command("session.resume")
        else:
            players = [{"player_id": "p1", "nickname": "민준"}]
            if args.players == 2:
                players.append({"player_id": "p2", "nickname": "서윤"})
            demo.snapshot = await demo.request("POST", "/v1/sessions", {"players": players})
            session_file.write_text(json.dumps({"session_id": demo.snapshot["session_id"]}), encoding="utf-8")
        for _ in range(30):
            await demo.display_prompt()
            snap = demo.snapshot
            if snap["status"] == "ENDED":
                game = snap["game"]
                print("\n모험 종료:", game.get("ending"))
                print("점수:", json.dumps(game.get("score_by_player", {}), ensure_ascii=False))
                return
            if snap["status"] == "PAUSED":
                print("\n조용히 쉬는 중. 다음 실행에서 --resume으로 이어갈 수 있어요.")
                return
            if snap["phase"] != "LISTENING":
                raise RuntimeError(f"Unexpected phase: {snap['phase']}")
            presentation = snap.get("presentation", {})
            actions = presentation.get("actions", [])
            if args.auto:
                if not actions:
                    raise RuntimeError("No available actions for automatic demonstration.")
                action = actions[-1 if args.auto == "last" else 0]
                if args.auto in {"success", "assist"} and snap["game"]["scene_id"] == "deduction":
                    destination = snap["game"]["flags"]["egg_location"]
                    action = next(a for a in actions if (a["id"] == destination) == (args.auto == "success"))
                actor = presentation.get("expected_player_id") or snap["game"].get("expected_player_id")
                nickname = next(p["nickname"] for p in snap["game"]["players"] if p["player_id"] == actor)
                text = f"{nickname}, {action['label']}"
                print(f"아이: {text}")
            else:
                print("선택:", " / ".join(a["label"] for a in actions))
                print("다시 / 힌트 / 생각 중 / 그만 · 두 명이면 ‘민준, 선택’처럼 말하기")
                try:
                    text = input("아이 > ").strip()
                except (EOFError, KeyboardInterrupt):
                    text = "그만"
            if text in {"그만", "조용히", "/quit", "/pause"}:
                await demo.command("session.pause", {"reason": "QUIET_REQUEST"})
                print("진행을 보존하고 중단했습니다.")
                return
            await demo.command("action.submit", {
                "text": text,
                "is_final": True,
                "capture_id": str(uuid4()),
                "listen_window_id": snap["listen_window_id"],
                "locale": "ko-KR",
                "input_source": "mock",
            })
        raise RuntimeError("Demo step limit exceeded; inspect the content graph.")


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except (RuntimeError, OSError, ValueError) as error:
        print(f"개발용 데모 오류: {error}", file=sys.stderr)
        raise SystemExit(1)
