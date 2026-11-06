"""Run Android integration tests against a disposable, loopback-only API.

Requires an already running dedicated emulator and the project's Python venv.
Game-flow input/playback are simulated. --speech-output separately exercises actual
offline TTS callbacks; it does not verify microphone recognition or acoustic quality.
"""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import tempfile
import threading
import time
import xml.etree.ElementTree as ET

import httpx
import uvicorn

from carplay.main import create_app
from carplay.settings import Settings

ROOT = Path(__file__).resolve().parents[1]
VEHICLE = ROOT / "apps/vehicle"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="Dedicated emulator serial, e.g. emulator-5580")
    parser.add_argument("--build-type", choices=("debug", "demo"), default="demo",
                        help="Test the optimized, installable demo by default.")
    parser.add_argument("--family-ui", action="store_true",
                        help="Exercise family screens, resetting this test app's local data on the dedicated emulator.")
    parser.add_argument("--speech-output", action="store_true",
                        help="Require an installed offline Korean TTS engine and test its real callbacks.")
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"):
        parser.error("Use a dedicated emulator; physical devices are not selected by this tool.")
    local = ROOT / ".local"
    local.mkdir(exist_ok=True)
    started = time.time()
    report_path = local / "android-smoke.json"
    report_path.write_text(json.dumps({"status": "running", "serial": args.serial}) + "\n", encoding="utf-8")
    token = secrets.token_urlsafe(32)
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        port = listener.getsockname()[1]
    with tempfile.TemporaryDirectory(prefix="android-api-", dir=local) as directory:
        app = create_app(Settings(db_path=Path(directory) / "test.sqlite3",
                                  demo_tokens={token: "android-test-family"}, semantic_endpoint=None))
        server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=port,
                                              access_log=False, log_level="warning"))
        # A managed thread avoids Windows venv launcher processes outliving terminate().
        server_thread = threading.Thread(target=server.run, name="android-smoke-api", daemon=True)
        server_thread.start()
        try:
            with httpx.Client(timeout=1, trust_env=False) as client:
                for _ in range(100):
                    if not server_thread.is_alive():
                        raise RuntimeError("The disposable API exited before readiness.")
                    try:
                        if client.get(f"http://127.0.0.1:{port}/health/ready").status_code == 200:
                            break
                    except httpx.HTTPError:
                        pass
                    time.sleep(0.1)
                else:
                    raise RuntimeError("Disposable API readiness timed out.")
            wrapper = VEHICLE / ("gradlew.bat" if os.name == "nt" else "gradlew")
            required_classes = {"dev.carplaymate.demo.VehicleFlowTest": 3,
                                "dev.carplaymate.demo.LocalGameParityTest": 3,
                                "dev.carplaymate.demo.LocalGameClientTest": 9,
                                "dev.carplaymate.demo.AddressedVoiceTest": 6,
                                "dev.carplaymate.demo.AddressedQuietRequestTest": 1,
                                "dev.carplaymate.demo.CompanionGreetingTest": 6,
                                "dev.carplaymate.demo.DinosaurAnswerRevisionTest": 5,
                                "dev.carplaymate.demo.QuestionHintAnswerRevisionTest": 6,
                                "dev.carplaymate.demo.LocalSpeechRepairTest": 11,
                                "dev.carplaymate.demo.LocalResumeNarrationTest": 8,
                                "dev.carplaymate.demo.ContentNarrationRevisionTest": 5,
                                "dev.carplaymate.demo.SpaceStoryRevisionTest": 4,
                                "dev.carplaymate.demo.AdventureLibraryTest": 10,
                                "dev.carplaymate.demo.AdventureParityTest": 4,
                                "dev.carplaymate.demo.PleosSpeechAdaptersTest": 21,
                                "dev.carplaymate.demo.PlaybackPresenceTest": 4,
                                "dev.carplaymate.demo.ViewModelRecoveryTest": 11,
                                "dev.carplaymate.demo.StabilityRecoveryTest": 5,
                                "dev.carplaymate.demo.SpeechAdapterRecoveryTest": 8,
                                "dev.carplaymate.demo.SpeechCaptureDeadlineTest": 10,
                                "dev.carplaymate.demo.RecoveryGuidanceTest": 7,
                                "dev.carplaymate.demo.InteractionModeTest": 12,
                                "dev.carplaymate.demo.VehicleAccessTest": 5,
                                "dev.carplaymate.demo.VoiceSessionHostPolicyInstrumentedTest": 6,
                                "dev.carplaymate.demo.StorageResilienceTest": 7,
                                "dev.carplaymate.demo.StorageOverviewTest": 9,
                                "dev.carplaymate.demo.ProfileEncryptionTest": 6,
                                "dev.carplaymate.demo.PrivateRecordCipherTest": 13,
                                "dev.carplaymate.demo.RecordStoreMigrationTest": 17,
                                "dev.carplaymate.demo.RecordStoreIntegrityTest": 4,
                                "dev.carplaymate.demo.RecordStoreInterruptedFileTest": 4,
                                "dev.carplaymate.demo.SharedKeyBootstrapTest": 3,
                                "dev.carplaymate.demo.FamilyInterestsTest": 4}
            if args.family_ui:
                required_classes["dev.carplaymate.demo.FamilyUiTest"] = 9
                required_classes["dev.carplaymate.demo.VoiceAdventureUiTest"] = 12
                required_classes["dev.carplaymate.demo.PermissionReturnUiTest"] = 6
                required_classes["dev.carplaymate.demo.ActivityPauseTest"] = 1
                required_classes["dev.carplaymate.demo.VoiceForegroundServiceTest"] = 7
                required_classes["dev.carplaymate.demo.StabilityUiTest"] = 1
                required_classes["dev.carplaymate.demo.SystemRecoveryUiTest"] = 3
            if args.speech_output:
                required_classes["dev.carplaymate.demo.SpeechOutputTest"] = 1
            command = [str(wrapper), f":app:connected{args.build_type.title()}AndroidTest", "--no-daemon", "--console=plain",
                       f"-PtestBuildType={args.build_type}",
                       f"-Pandroid.testInstrumentationRunnerArguments.apiUrl=http://10.0.2.2:{port}",
                       f"-Pandroid.testInstrumentationRunnerArguments.apiToken={token}",
                       "-Pandroid.testInstrumentationRunnerArguments.class=" + ",".join(required_classes)]
            if args.family_ui:
                command.append("-Pandroid.testInstrumentationRunnerArguments.familyUiTests=true")
                command.append("-Pandroid.testInstrumentationRunnerArguments.systemRecoveryUiTests=true")
            if args.speech_output:
                command.append("-Pandroid.testInstrumentationRunnerArguments.speechOutputTests=true")
            # Never print the command: it contains an ephemeral authentication token.
            result = subprocess.run(
                command, cwd=VEHICLE, env={**os.environ, "ANDROID_SERIAL": args.serial},
                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                # The preceding 208-test run took 1,190 seconds before report overhead.
                # Allow the expanded suite and build/report overhead; per-test guards stay unchanged.
                encoding="utf-8", errors="replace", timeout=1800,
            )
            output = result.stdout.replace(token, "[redacted]")
            (local / "android-smoke.log").write_text(output, encoding="utf-8")
            print(output)
            if result.returncode:
                raise RuntimeError("Android integration tests failed; see .local/android-smoke.log.")
            suites = [ET.parse(path).getroot() for path in
                      (VEHICLE / "app/build/outputs/androidTest-results/connected").rglob("TEST-*.xml")
                      if path.stat().st_mtime >= started - 2]
            cases = [case for suite in suites for case in suite.iter("testcase")
                     if case.get("classname") in required_classes]
            by_class = {name: sum(case.get("classname") == name for case in cases) for name in required_classes}
            executed = {case.get("name") for case in cases}
            required = {"onePlayerFindsEggAndFinishesAfterSimulatedPlayback",
                        "twoPlayersReachTeamAssistanceEnding",
                        "clearedViewModelRestoresQuietlyAndResumesWithoutRepeatingAction"}
            totals = {"tests": len(cases), "failures": sum(case.find("failure") is not None for case in cases),
                      "errors": sum(case.find("error") is not None for case in cases),
                      "skipped": sum(case.find("skipped") is not None for case in cases)}
            if (not required <= executed or any(by_class[name] < count for name, count in required_classes.items())
                    or any(totals[key] for key in ("failures", "errors", "skipped"))):
                raise RuntimeError("Required Android game, recovery and requested UI/voice tests did not all pass.")
            report = {"status": "passed", "serial": args.serial, "transport": "android_emulator_to_loopback_api",
                      "build_type": args.build_type,
                      "game_flow_speech": "mock_input_and_playback",
                      "actual_tts_callbacks": args.speech_output, "microphone_recognition_verified": False,
                      "family_ui": args.family_ui, "by_class": by_class, "provider_calls": 0, **totals}
            report_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
            print(json.dumps(report, indent=2))
        except BaseException:
            report_path.write_text(json.dumps({"status": "failed", "serial": args.serial}) + "\n", encoding="utf-8")
            raise
        finally:
            server.should_exit = True
            server_thread.join(timeout=10)
            if server_thread.is_alive():
                raise RuntimeError("Disposable API did not stop within ten seconds.")


if __name__ == "__main__":
    try:
        main()
    except subprocess.TimeoutExpired:
        # TimeoutExpired.__str__ includes the command and therefore the token.
        raise SystemExit("Android integration tests timed out; the temporary API was stopped.") from None
