"""Exercise a real loopback Uvicorn server and both story endings, then stop it.

Uses fictional development players and a local token; no provider is contacted.
The output report contains no authentication token or child utterance recordings.
"""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import time

import httpx

ROOT = Path(__file__).resolve().parents[1]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        port = listener.getsockname()[1]
    env = {**os.environ, "PYTHONUNBUFFERED": "1"}
    server = subprocess.Popen(
        [sys.executable, str(ROOT / "tools/run_server.py"), "--port", str(port)],
        cwd=ROOT, env=env, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT, text=True,
    )
    results = []
    try:
        origin = f"http://127.0.0.1:{port}"
        with httpx.Client(timeout=0.5, trust_env=False) as client:
            for _ in range(50):
                if server.poll() is not None:
                    raise RuntimeError("The local API process exited before readiness.")
                try:
                    response = client.get(origin + "/health/ready")
                    if response.status_code == 200 and response.json().get("mode") == "local-prototype":
                        break
                except httpx.HTTPError:
                    pass
                time.sleep(0.1)
            else:
                raise RuntimeError("Local API readiness timed out.")
        for players, strategy, ending in [(1, "success", "FOUND_TOGETHER"), (2, "assist", "TEAM_ASSIST")]:
            run = subprocess.run(
                [sys.executable, str(ROOT / "tools/play_cli.py"), "--api-url", origin,
                 "--players", str(players), "--auto", strategy],
                cwd=ROOT, text=True, capture_output=True, timeout=30,
            )
            if run.returncode or f"모험 종료: {ending}" not in run.stdout:
                raise RuntimeError(f"HTTP demo failed for {players} players/{strategy}: {run.stderr}")
            actions = sum(line.startswith("아이:") for line in run.stdout.splitlines())
            results.append({"players": players, "route": strategy, "ending": ending, "actions": actions, "passed": True})
        report = {"transport": "real_loopback_http", "provider_calls": 0, "speech": "text_callback_simulation", "runs": results}
        if args.report:
            args.report.parent.mkdir(parents=True, exist_ok=True)
            args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(report, ensure_ascii=False, indent=2))
    finally:
        server.terminate()
        try:
            server.wait(timeout=5)
        except subprocess.TimeoutExpired:
            server.kill()
            server.wait(timeout=5)
        if server.stdout:
            server.stdout.close()


if __name__ == "__main__":
    main()
