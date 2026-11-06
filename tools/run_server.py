"""Run the prototype on loopback; never prints its authentication token."""

from __future__ import annotations

import argparse
import sys

from demo_config import LOCAL, ROOT, TOKEN_FILE, local_token

sys.path.insert(0, str(ROOT / "services" / "api"))


def main() -> None:
    parser = argparse.ArgumentParser(description="Car Playmate local developer API")
    parser.add_argument("--port", type=int, default=8000)
    args = parser.parse_args()
    if not 1024 <= args.port <= 65535:
        parser.error("Use a port between 1024 and 65535.")
    import uvicorn
    from carplay.main import create_app
    from carplay.settings import Settings

    token = local_token()
    app = create_app(
        Settings(db_path=LOCAL / "carplay.db", demo_tokens={token: "family-demo-local"})
    )
    print(f"개발용 API: http://127.0.0.1:{args.port}")
    print(f"차량 개발 클라이언트에 입력할 토큰 파일: {TOKEN_FILE}")
    print("가상 인물로 시험하는 로컬 시제품입니다. 모델 전송은 꺼져 있습니다.")
    uvicorn.run(app, host="127.0.0.1", port=args.port, log_level="warning", access_log=False)


if __name__ == "__main__":
    main()
