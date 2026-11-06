"""Coordinate the real AAOS UXR instrumentation handshake on a dedicated emulator.

Requires matching installed app/test APKs; never builds, installs, disables UXR, or
uses run-as. The actual vehicle service is exercised; speech output remains fake.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import queue
import re
import shutil
import subprocess
import threading
import time
from uuid import UUID

ROOT = Path(__file__).resolve().parents[1]
RUNNER = "androidx.test.runner.AndroidJUnitRunner"
TEST_CLASS = "dev.carplaymate.demo.AaosVehicleAccessTest"
FLAGS = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0
STAGES = (
    "connecting",
    "ready-for-restriction",
    "restricted-and-quiet",
    "unrestricted-still-quiet",
    "complete",
)
EXPECTED = {
    "connecting": ("UNKNOWN", True, 0),
    "ready-for-restriction": ("UNRESTRICTED", False, 1),
    "restricted-and-quiet": ("RESTRICTED", True, 1),
    "unrestricted-still-quiet": ("UNRESTRICTED", True, 1),
    "complete": ("UNRESTRICTED", False, 2),
}


def sha256(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def find_adb() -> str:
    name = "adb.exe" if os.name == "nt" else "adb"
    for variable in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
        if value := os.environ.get(variable):
            candidate = Path(value) / "platform-tools" / name
            if candidate.is_file():
                return str(candidate)
    if installed := shutil.which(name):
        return installed
    raise RuntimeError("Set ANDROID_SDK_ROOT/ANDROID_HOME or add adb to PATH.")


class Device:
    def __init__(self, adb: str, serial: str):
        self.prefix = [adb, "-s", serial]
        self.deadline: float | None = None

    def run(self, *args: str, timeout: float = 10) -> str:
        if self.deadline is not None:
            remaining = self.deadline - time.monotonic()
            if remaining <= 0:
                raise RuntimeError("The actual AAOS handshake exceeded its deadline.")
            timeout = min(timeout, remaining)
        result = subprocess.run(
            [*self.prefix, *args], stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
            encoding="utf-8", errors="replace", timeout=timeout, creationflags=FLAGS,
        )
        # Do not copy arbitrary device stderr (or app data) into the evidence report.
        if result.returncode:
            raise RuntimeError(f"adb {args[0]} failed (exit {result.returncode}).")
        return result.stdout.strip()

    def car(self, *args: str) -> str:
        result = self.run("shell", "cmd", "car_service", *args)
        if re.search(r"unknown command|exception|permission denial|error:", result, re.I):
            raise RuntimeError(f"car_service {args[0]} was rejected.")
        return result

    def wait_restriction(self, restricted: bool) -> str:
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            value = self.car("get-current-ux-restrictions")
            # Observed AAOS 15 shell prints active restriction names, not the API's DO bit.
            # The instrumentation independently verifies isRequiresDistractionOptimization.
            lines = [line.strip() for line in value.splitlines() if line.strip()]
            if len(lines) >= 2 and lines[0] == "Current Restrictions:":
                baseline = lines[1:] == ["baseline"]
                if baseline != restricted:
                    return value
            match = re.search(r"\bDO:\s*(true|false)\b", value)
            if match and (match[1] == "true") == restricted:
                return value
            time.sleep(0.1)
        raise RuntimeError("The actual platform restriction did not reach the requested test state.")

    def installed_hash(self, package: str, user: int) -> str:
        lines = self.run("shell", "pm", "path", "--user", str(user), package).splitlines()
        if len(lines) != 1 or not lines[0].startswith("package:/data/app/"):
            raise RuntimeError(f"Expected one installed base APK for {package} in the active user.")
        path = lines[0].removeprefix("package:")
        if not re.fullmatch(r"/data/app/[A-Za-z0-9_./+=~-]+\.apk", path):
            raise RuntimeError("Unexpected installed APK path.")
        digest = self.run("shell", "sha256sum", path).split()[0]
        if not re.fullmatch(r"[a-fA-F0-9]{64}", digest):
            raise RuntimeError("Could not establish the installed APK digest.")
        return digest.lower()


def marker_path(status_path: str, stage: str, app_id: str, user: int) -> str:
    expected = f"/storage/emulated/{user}/Android/data/{app_id}/files/aaos-ux/status.json"
    if status_path != expected:
        raise RuntimeError("Test marker path did not match this app and foreground user.")
    return str(PurePosixPath(status_path).with_name(f"{stage}.json"))


def validate_marker(value: object, stage: str, run_id: str) -> dict:
    if not isinstance(value, dict):
        raise RuntimeError("Test marker is not a JSON object.")
    if value.get("run_id") != run_id or value.get("stage") != stage:
        raise RuntimeError("Stale or mismatched test marker.")
    actual = (value.get("vehicle_access"), value.get("quiet"), value.get("fake_playback_count"))
    if actual != EXPECTED[stage] or type(actual[1]) is not bool or type(actual[2]) is not int:
        raise RuntimeError(f"Incorrect provider/audio state at {stage}.")
    if value.get("actual_uxr_provider") is not True or value.get("actual_audio") is not False:
        raise RuntimeError("Test marker does not identify the actual service/fake audio boundary.")
    # Whitelist fields: no family data, transcripts, or arbitrary additional payload is retained.
    return {
        "run_id": run_id, "stage": stage, "vehicle_access": actual[0],
        "quiet": actual[1], "fake_playback_count": actual[2],
        "actual_uxr_provider": True, "actual_audio": False,
    }


def stop_client(process: subprocess.Popen | None) -> None:
    if process is None or process.poll() is not None:
        return
    process.terminate()
    try:
        process.wait(timeout=3)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=3)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--app-id", default="dev.carplaymate.demo.demo")
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--apk-sha256", required=True)
    parser.add_argument("--test-apk", required=True, type=Path)
    parser.add_argument("--timeout", default=90, type=int, help="Overall handshake timeout, maximum 90 seconds.")
    parser.add_argument("--output", type=Path, default=ROOT / ".local/aaos-ux-verification.json")
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-\d+", args.serial):
        parser.error("Only a dedicated emulator-NNNN is allowed.")
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+", args.app_id):
        parser.error("Invalid app package identifier.")
    if not re.fullmatch(r"[a-fA-F0-9]{64}", args.apk_sha256):
        parser.error("--apk-sha256 must be the exact final APK digest.")
    if not 1 <= args.timeout <= 90:
        parser.error("--timeout must be between 1 and 90 seconds.")

    started = time.monotonic()
    report = {
        "status": "running", "serial": args.serial, "app_id": args.app_id,
        "actual_aaos_service": False, "actual_audio": False,
        "speech": "fake_output_callbacks", "system_uxr_disabled": False,
        "stages": [], "vehicle_commands": [], "platform_uxr": [],
        "park_restored": False, "tests_passed": 0,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)

    def save() -> None:
        report["duration_seconds"] = round(time.monotonic() - started, 3)
        temporary = args.output.with_suffix(".json.tmp")
        temporary.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        temporary.replace(args.output)

    device: Device | None = None
    process: subprocess.Popen | None = None
    user: int | None = None
    vehicle_touched = False
    save()
    try:
        local_hash = sha256(args.apk)
        if local_hash != args.apk_sha256.lower():
            raise RuntimeError("The supplied app APK does not match its expected digest.")
        test_hash = sha256(args.test_apk)
        report["apk_sha256"] = local_hash
        report["test_apk_sha256"] = test_hash
        device = Device(find_adb(), args.serial)
        if device.run("get-state") != "device":
            raise RuntimeError("The selected emulator is not ready.")
        if device.run("shell", "getprop", "ro.kernel.qemu") != "1":
            raise RuntimeError("The target did not identify itself as an emulator.")
        if "feature:android.hardware.type.automotive" not in device.run("shell", "pm", "list", "features").splitlines():
            raise RuntimeError("The target does not expose FEATURE_AUTOMOTIVE.")
        current_user = device.run("shell", "am", "get-current-user")
        if not re.fullmatch(r"\d+", current_user):
            raise RuntimeError("Could not establish the foreground Android user.")
        user = int(current_user)
        report["android_user"] = user
        shell_uid = device.run("shell", "id", "-u")
        report["adb_shell_uid"] = int(shell_uid)
        # Observed AAOS 15 FUSE denies even root across foreground user 10. On this
        # dedicated userdebug emulator only, root may read the same whitelisted file
        # via its verified /data/media/<user> backing path. No app run-as is used.
        backing_markers = shell_uid == "0" and user != 0
        report["marker_access"] = "root_user_backing_path" if backing_markers else "app_reported_path"
        report["android_release"] = device.run("shell", "getprop", "ro.build.version.release")
        report["android_sdk"] = device.run("shell", "getprop", "ro.build.version.sdk")
        for package, expected in ((args.app_id, local_hash), (args.app_id + ".test", test_hash)):
            if device.installed_hash(package, user) != expected:
                raise RuntimeError(f"The installed APK does not match the supplied final file: {package}.")
        report["installed_hashes_match"] = True

        def driving(mode: str, stage: str) -> None:
            device.car("emulate-driving-state", mode)
            report["vehicle_commands"].append({"stage": stage, "mode": mode})
            report["platform_uxr"].append({
                "stage": stage,
                "value": device.wait_restriction(mode == "drive")[:1000],
            })
            save()

        vehicle_touched = True
        driving("park", "initial-park")
        command = [
            *device.prefix, "shell", "am", "instrument", "--user", str(user), "-w", "-r",
            "-e", "aaosUxTests", "true", "-e", "class", TEST_CLASS,
            f"{args.app_id}.test/{RUNNER}",
        ]
        process = subprocess.Popen(
            command, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, encoding="utf-8", errors="replace", bufsize=1, creationflags=FLAGS,
        )
        events: queue.Queue[str | None] = queue.Queue()

        def read_output() -> None:
            assert process is not None and process.stdout is not None
            try:
                for line in process.stdout:
                    events.put(line.rstrip("\r\n"))
            finally:
                events.put(None)

        reader = threading.Thread(target=read_output, name="aaos-instrument-output", daemon=True)
        reader.start()
        deadline = time.monotonic() + args.timeout
        device.deadline = deadline
        bundle: dict[str, str] = {}
        run_id: str | None = None
        seen: list[str] = []
        passed = 0
        junit_ok = False
        instrumentation_complete = False
        while time.monotonic() < deadline:
            try:
                line = events.get(timeout=min(0.2, max(0.001, deadline - time.monotonic())))
            except queue.Empty:
                continue
            if line is None:
                break
            if match := re.fullmatch(r"INSTRUMENTATION_STATUS: ([A-Za-z0-9_]+)=(.*)", line):
                bundle[match[1]] = match[2]
            elif match := re.fullmatch(r"INSTRUMENTATION_STATUS_CODE: (-?\d+)", line):
                code = int(match[1])
                if code < 0:
                    raise RuntimeError(f"Instrumentation failed or skipped (status {code}).")
                if code == 0:
                    passed += 1
                if "aaos_ux_stage" in bundle:
                    stage = bundle["aaos_ux_stage"]
                    if len(seen) >= len(STAGES) or stage != STAGES[len(seen)]:
                        raise RuntimeError("AAOS handshake stages arrived out of order.")
                    marker_run = str(UUID(bundle["aaos_ux_run_id"]))
                    if run_id is None:
                        run_id = marker_run
                        report["run_id"] = run_id
                    if marker_run != run_id:
                        raise RuntimeError("AAOS handshake mixed different test runs.")
                    path = marker_path(bundle["aaos_ux_status_path"], stage, args.app_id, user)
                    if backing_markers:
                        path = path.replace(f"/storage/emulated/{user}/", f"/data/media/{user}/", 1)
                    value = json.loads(device.run("shell", "cat", path))
                    report["stages"].append(validate_marker(value, stage, run_id))
                    if stage == "ready-for-restriction":
                        report["actual_aaos_service"] = True
                    seen.append(stage)
                    save()
                    if stage == "ready-for-restriction":
                        driving("drive", stage)
                    elif stage == "restricted-and-quiet":
                        driving("park", stage)
                bundle = {}
            elif re.fullmatch(r"OK \(1 test\)", line.strip()):
                junit_ok = True
            elif line == "INSTRUMENTATION_CODE: -1":
                instrumentation_complete = True
            elif line.startswith("INSTRUMENTATION_FAILED") or "FAILURES!!!" in line:
                raise RuntimeError("The actual AAOS instrumentation failed.")

        if time.monotonic() >= deadline:
            raise RuntimeError("The actual AAOS handshake exceeded its deadline.")
        if process.wait(timeout=3) != 0 or not junit_ok or not instrumentation_complete or passed != 1:
            raise RuntimeError("Expected one completed, passing, non-skipped instrumentation test.")
        if tuple(seen) != STAGES:
            raise RuntimeError("The passing instrumentation did not provide every required actual-state marker.")
        report["tests_passed"] = 1
        report["status"] = "passed"
    except Exception as error:
        report["status"] = "failed"
        # All raised validation messages are controlled; OS/JSON exceptions may contain app data.
        report["error"] = str(error) if isinstance(error, RuntimeError) else type(error).__name__
    finally:
        stop_client(process)
        if device is not None and vehicle_touched:
            device.deadline = None
            try:
                device.car("emulate-driving-state", "park")
                report["vehicle_commands"].append({"stage": "finally", "mode": "park"})
                report["platform_uxr"].append({
                    "stage": "finally", "value": device.wait_restriction(False)[:1000],
                })
                report["park_restored"] = True
            except Exception:
                report["status"] = "failed"
                report["cleanup_error"] = "Could not verify final park command. Restore dedicated emulator manually."
            if process is not None and report["status"] != "passed" and user is not None:
                try:
                    device.run("shell", "am", "force-stop", "--user", str(user), args.app_id)
                    report["failed_instrumentation_stopped"] = True
                except Exception:
                    report["failed_instrumentation_stopped"] = False
        save()
    print(json.dumps(report, ensure_ascii=False))
    return 0 if report["status"] == "passed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
