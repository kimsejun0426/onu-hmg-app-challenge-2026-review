"""Verify recovery after a real OS force-stop on a dedicated Android emulator.

Requires installed app and matching test APKs. Does not build, install, or use run-as.
The explicitly enabled fixture resets that app's local family/game data. Playback is
advanced in reading mode; this is a process/persistence test, not a voice quality test.
"""

from __future__ import annotations

import argparse
from contextlib import ExitStack
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time
from typing import TextIO
from uuid import UUID

ROOT = Path(__file__).resolve().parents[1]
TEST_CLASS = "dev.carplaymate.demo.ProcessRestoreTest"
RUNNER = "androidx.test.runner.AndroidJUnitRunner"
CREATE_FLAGS = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0


def find_adb() -> str:
    name = "adb.exe" if os.name == "nt" else "adb"
    for key in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
        sdk = os.environ.get(key)
        if sdk:
            candidate = Path(sdk) / "platform-tools" / name
            if candidate.is_file():
                return str(candidate)
    installed = shutil.which(name)
    if installed:
        return installed
    raise RuntimeError("adb was not found. Set ANDROID_SDK_ROOT/ANDROID_HOME or add adb to PATH.")


class Device:
    def __init__(self, adb: str, serial: str, app_id: str):
        self.prefix = [adb, "-s", serial]
        self.app_id = app_id
        self.marker = f"/sdcard/Android/data/{app_id}/files/process-ready.txt"

    def run(self, *arguments: str, timeout: float = 5, allow_failure: bool = False) -> subprocess.CompletedProcess:
        result = subprocess.run(
            [*self.prefix, *arguments], stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
            encoding="utf-8", errors="replace", timeout=timeout, creationflags=CREATE_FLAGS,
        )
        if result.returncode and not allow_failure:
            detail = (result.stderr or result.stdout).strip()[:600]
            raise RuntimeError(f"adb {arguments[0]} failed ({result.returncode}): {detail}")
        return result

    def marker_exists(self) -> bool:
        result = self.run("shell", "test", "-f", self.marker, allow_failure=True)
        if result.returncode == 0:
            return True
        if result.returncode == 1 and not result.stdout.strip() and not result.stderr.strip():
            return False
        raise RuntimeError("Could not inspect the external process-ready marker.")

    def read_marker(self) -> str | None:
        if not self.marker_exists():
            return None
        value = self.run("shell", "cat", self.marker).stdout.strip()
        if not value:  # The seed may be between creating and fsyncing the short marker.
            return None
        try:
            return str(UUID(value))
        except ValueError as error:
            raise RuntimeError("The process-ready marker is not a valid fixture run ID.") from error

    def pids(self) -> list[int]:
        result = self.run("shell", "pidof", self.app_id, timeout=3, allow_failure=True)
        value = result.stdout.strip()
        if result.returncode == 1 and not value and not result.stderr.strip():
            return []
        if result.returncode or (value and not re.fullmatch(r"\d+(?:\s+\d+)*", value)):
            raise RuntimeError("Could not establish the target app's OS PID.")
        return [int(pid) for pid in value.split()]

    def instrument(self, method: str, log: TextIO) -> subprocess.Popen:
        return subprocess.Popen(
            [*self.prefix, "shell", "am", "instrument", "-w", "-r",
             "-e", "processDeathTests", "true", "-e", "class", f"{TEST_CLASS}#{method}",
             f"{self.app_id}.test/{RUNNER}"],
            stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT,
            creationflags=CREATE_FLAGS,
        )


def stop_client(process: subprocess.Popen | None) -> None:
    if process is None or process.poll() is not None:
        return
    process.terminate()
    try:
        process.wait(timeout=3)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=3)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="Dedicated emulator serial, e.g. emulator-5580")
    parser.add_argument("--app-id", default="dev.carplaymate.demo.debug", help="Already installed app package ID.")
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-\d+", args.serial):
        parser.error("Only a dedicated emulator-NNNN serial is permitted; physical devices are not selected.")
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+", args.app_id):
        parser.error("--app-id must be a valid Android package identifier.")

    local = ROOT / ".local"
    local.mkdir(exist_ok=True)
    report_path = local / "process-restore.json"
    seed_log_path = local / "process-restore-seed.log"
    verify_log_path = local / "process-restore-verify.log"
    started = time.monotonic()
    report = {
        "status": "running", "serial": args.serial, "app_id": args.app_id,
        "actual_force_stop": False, "seed_killed": False,
        "speech": "explicit_reading_mode_manual_playback_completion",
        "seed": {"status": "not_started", "counted_as_passed_test": False},
        "verify": {"status": "not_started", "tests": 0},
        "pid_verification": {"seed_pid": None, "after_force_stop": None, "verify_pid": None},
        "logs": {"seed": str(seed_log_path), "verify": str(verify_log_path)},
    }

    def save_report() -> None:
        report["duration_seconds"] = round(time.monotonic() - started, 3)
        temporary = report_path.with_suffix(".json.tmp")
        temporary.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        temporary.replace(report_path)

    device: Device | None = None
    seed: subprocess.Popen | None = None
    verify: subprocess.Popen | None = None
    save_report()
    try:
        device = Device(find_adb(), args.serial, args.app_id)
        if device.run("get-state").stdout.strip() != "device":
            raise RuntimeError("The dedicated emulator is not ready.")
        if device.run("shell", "getprop", "ro.kernel.qemu").stdout.strip() != "1":
            raise RuntimeError("The selected serial did not identify itself as an emulator.")
        for package in (args.app_id, f"{args.app_id}.test"):
            if not device.run("shell", "pm", "path", package).stdout.startswith("package:"):
                raise RuntimeError(f"The required APK is not installed: {package}")
        if device.marker_exists():
            raise RuntimeError("An existing process-ready.txt marker was found; inspect the unfinished run before retrying.")

        with ExitStack() as files:
            seed_log = files.enter_context(seed_log_path.open("w", encoding="utf-8"))
            verify_log = files.enter_context(verify_log_path.open("w", encoding="utf-8"))
            seed = device.instrument("seedActiveAdventure", seed_log)
            report["seed"]["status"] = "waiting_for_ready"
            save_report()
            deadline = time.monotonic() + 30
            run_id = None
            while time.monotonic() < deadline:
                if seed.poll() is not None:
                    raise RuntimeError("The seed exited before readiness; see process-restore-seed.log.")
                run_id = device.read_marker()
                if run_id is not None:
                    break
                time.sleep(0.2)
            if run_id is None:
                raise RuntimeError("The seed did not publish its marker within 30 seconds.")
            seed_pids = device.pids()
            if len(seed_pids) != 1 or seed.poll() is not None:
                raise RuntimeError("Exactly one live app process is required before the deliberate force-stop.")
            seed_pid = seed_pids[0]
            report["run_id"] = run_id
            report["pid_verification"]["seed_pid"] = seed_pid
            report["seed"]["status"] = "active_feedback_ready"
            save_report()

            device.run("shell", "am", "force-stop", args.app_id)
            report["actual_force_stop"] = True
            deadline = time.monotonic() + 5
            remaining = device.pids()
            while remaining and time.monotonic() < deadline:
                time.sleep(0.15)
                remaining = device.pids()
            report["pid_verification"]["after_force_stop"] = remaining
            if remaining:
                raise RuntimeError("The app PID survived the OS force-stop.")
            seed.wait(timeout=10)
            seed_log.flush()
            seed_output = seed_log_path.read_text(encoding="utf-8", errors="replace")
            expected_crash = re.search(r"process\s+crashed", seed_output, re.IGNORECASE) is not None
            seed_passed = re.search(r"OK\s*\(\s*1\s+test\s*\)", seed_output) is not None
            report["seed"].update(exit_code=seed.returncode, expected_process_crash=expected_crash)
            if not expected_crash or seed_passed:
                raise RuntimeError("The seed did not report the expected process crash; it must never count as a passed test.")
            if device.read_marker() != run_id:
                raise RuntimeError("The seed marker did not survive; a normal cleanup may have run.")
            report["seed_killed"] = True
            report["seed"]["status"] = "killed_after_committed_action"
            save_report()

            verify = device.instrument("verifyRestoredAdventure", verify_log)
            report["verify"]["status"] = "running_in_new_process"
            save_report()
            deadline = time.monotonic() + 90
            verify_pid = None
            while verify.poll() is None:
                if time.monotonic() >= deadline:
                    raise RuntimeError("Recovery verification exceeded 90 seconds.")
                if verify_pid is None:
                    new_pids = device.pids()
                    if len(new_pids) > 1:
                        raise RuntimeError("Recovery verification unexpectedly has multiple app PIDs.")
                    if new_pids:
                        verify_pid = new_pids[0]
                        report["pid_verification"]["verify_pid"] = verify_pid
                time.sleep(0.2)
            verify_log.flush()
            output = verify_log_path.read_text(encoding="utf-8", errors="replace")
            passed = re.search(r"(?m)^OK\s*\(\s*1\s+test\s*\)\s*$", output) is not None
            skipped_or_failed = re.search(
                r"FAILURES!!!|INSTRUMENTATION_FAILED|process\s+crashed|INSTRUMENTATION_STATUS_CODE:\s*-[234]",
                output, re.IGNORECASE,
            ) is not None
            report["verify"].update(exit_code=verify.returncode, ok_one_test=passed,
                                     skipped_or_failed=skipped_or_failed)
            if verify.returncode or not passed or skipped_or_failed:
                raise RuntimeError("Recovery verification did not execute and pass exactly one test; see process-restore-verify.log.")
            if verify_pid is None or verify_pid == seed_pid:
                raise RuntimeError("A distinct verification-process PID was not observed.")
            if device.marker_exists():
                raise RuntimeError("The successful verification did not remove its readiness marker.")
            report["pid_verification"]["distinct_processes"] = True
            report["verify"].update(status="passed", tests=1)
            report["status"] = "passed"
    except BaseException as error:
        report["status"] = "failed"
        report["error"] = str(error) or type(error).__name__
        raise
    finally:
        running = any(process is not None and process.poll() is None for process in (seed, verify))
        began = seed is not None or verify is not None
        if device is not None and (running or (began and report["status"] != "passed")):
            try:
                device.run("shell", "am", "force-stop", args.app_id)
                report["cleanup_force_stop"] = True
            except (OSError, RuntimeError, subprocess.SubprocessError) as error:
                report["cleanup_error"] = str(error)
        for process in (seed, verify):
            try:
                stop_client(process)
            except (OSError, subprocess.SubprocessError) as error:
                report["cleanup_error"] = str(error)
                report["status"] = "failed"
        save_report()
    print(json.dumps(report, indent=2))
    if report["status"] != "passed":
        raise RuntimeError("Process-restore cleanup failed; inspect .local/process-restore.json.")


if __name__ == "__main__":
    try:
        main()
    except (OSError, RuntimeError, subprocess.SubprocessError) as error:
        raise SystemExit(f"Process restore failed: {error}\nSee .local/process-restore.json and its logs.") from None
