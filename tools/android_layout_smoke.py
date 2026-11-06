"""Run installed FamilyUiTest APKs in three emulator layouts and collect fresh PNGs.

Uses a dedicated emulator only. Explicitly enables the test's family-data reset;
does not build/install APKs. Restores the original display overrides and font scale,
including on failure. Reading-mode screenshots do not verify real audio quality.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import struct
import subprocess
import time

from android_process_restore import CREATE_FLAGS, Device, RUNNER, find_adb, stop_client

ROOT = Path(__file__).resolve().parents[1]
LAYOUTS = (
    ("landscape", "1280x800", "160", "1.0"),
    ("compact", "1024x600", "160", "1.3"),
    ("portrait", "480x900", "160", "1.2"),
)
TESTS = {
    "oneChildReadsChoosesSixTimesAndSeesTheirActualEnding": "ending",
    "twoChildrenPauseAndResumeWithoutRepeatingTheFirstChoice": "quiet",
    "guardianChangesNamesAndMustConfirmDeletingTheFamily": "settings-updated",
}
COMMON_SHOTS = ("welcome", "home", "settings", "play-reading", "listening")
EXPECTED_SHOTS = {f"{test}-{shot}.png" for test, extra in TESTS.items()
                  for shot in (*COMMON_SHOTS, extra)}
CATALOG_TEST = "catalogAndJournalPreserveTheChosenWorldAndActualMemories"
EXPECTED_SHOTS |= {f"{CATALOG_TEST}-{shot}.png" for shot in (
    "welcome", "home", "settings", "library", "starlight-detail", "starlight-play",
    "starlight-ending", "journal", "memory-detail", "undersea-detail", "undersea-play",
)}
EXPECTED_SHOTS |= {f"{test}-interests.png" for test in (*TESTS, CATALOG_TEST)}
EXPECTED_SHOTS |= {"oneChildReadsChoosesSixTimesAndSeesTheirActualEnding-ending-feedback.png"}
EXPECTED_SHOTS |= {f"editedInterestsChangeTheRecommendationAndSurviveActivityRecreation-{shot}.png" for shot in (
    "welcome", "interests", "home-matched-dinosaurs", "interests-edited", "home-matched-ocean", "home-restored-interests",
)}
EXPECTED_SHOTS |= {f"twoChildrenKeepIndependentInterestsAndCancelOrDeleteExplicitly-{shot}.png" for shot in (
    "welcome", "interests", "interests-second-child", "settings-independent-interests", "interests-deleted",
)}
EXPECTED_SHOTS |= {f"helpAndRepeatAreDirectChoicesAndNeverTakeTheChildsTurn-{shot}.png" for shot in (
    "welcome", "interests", "home", "settings", "play-reading", "listening", "help",
)}
EXPECTED_SHOTS |= {f"picnicChoicesBecomeOneMemoryAndFeedbackStaysOptional-{shot}.png" for shot in (
    "welcome", "interests", "home", "settings", "listening", "picnic-opening", "picnic-ending", "picnic-memory",
)}
FIRST_MODE_TEST = "firstReadingChoiceIsQuietAndNotAskedAgainAfterActivityRecreation"
EXPECTED_SHOTS |= {f"{test}-interaction-choice.png" for test in (*TESTS, CATALOG_TEST, FIRST_MODE_TEST,
    "helpAndRepeatAreDirectChoicesAndNeverTakeTheChildsTurn",
    "picnicChoicesBecomeOneMemoryAndFeedbackStaysOptional")}
EXPECTED_SHOTS |= {f"{FIRST_MODE_TEST}-{shot}.png" for shot in ("welcome", "interests", "home")}
TEST_COUNT = 9


def display_state(device: Device) -> dict:
    state = {}
    for kind, pattern in (("size", r"\d+x\d+"), ("density", r"\d+")):
        output = device.run("shell", "wm", kind).stdout
        physical = re.search(rf"Physical {kind}:\s*({pattern})", output)
        override = re.search(rf"Override {kind}:\s*({pattern})", output)
        if physical is None:
            raise RuntimeError(f"Could not read the emulator's physical {kind}.")
        state[kind] = {"physical": physical.group(1), "override": override.group(1) if override else None}
    font = device.run("shell", "settings", "get", "system", "font_scale").stdout.strip()
    if font != "null" and not re.fullmatch(r"\d+(?:\.\d+)?", font):
        raise RuntimeError("The original font scale could not be read safely.")
    state["font_scale"] = font
    return state


def next_device_second(device: Device) -> int:
    # A strict new timestamp avoids mistaking old screenshots for this run's output.
    first = int(device.run("shell", "date", "+%s").stdout.strip())
    deadline = time.monotonic() + 3
    while time.monotonic() < deadline:
        current = int(device.run("shell", "date", "+%s").stdout.strip())
        if current > first:
            return current
        time.sleep(0.1)
    raise RuntimeError("The emulator clock did not advance before the screenshot run.")


def collect_pngs(device: Device, directory: Path, minimum_time: int, *, voice: bool = False) -> list[dict]:
    folder = "voice-ui-screenshots" if voice else "test-screenshots"
    expected = {"listening.png", "two-child-examples.png", "recovery.png", "recovery-settings.png", "output-preparing.png", "output-started.png", "recovery-input-unavailable.png", "recovery-input-ready.png", "shared-recommendation.png", "new-adventure-preparing.png", "new-adventure-failed.png", "saved-participants-home.png", "saved-participants-detail.png"} if voice else EXPECTED_SHOTS
    remote = f"/sdcard/Android/data/{device.app_id}/files/{folder}"
    names = device.run("shell", "ls", "-1", remote).stdout.splitlines()
    names = sorted(name.strip() for name in names if re.fullmatch(r"[A-Za-z0-9_.-]+\.png", name.strip()))
    if not expected <= set(names):
        raise RuntimeError("The installed UI tests did not create all required family, catalog and journal screenshots.")
    stamps = device.run("shell", "stat", "-c", "%n:%Y", *(f"{remote}/{name}" for name in names), timeout=10).stdout
    modified = {line.rsplit("/", 1)[-1].rsplit(":", 1)[0]: int(line.rsplit(":", 1)[1])
                for line in stamps.splitlines() if ":" in line}
    fresh = sorted(name for name in names if modified.get(name, 0) >= minimum_time)
    if not expected <= set(fresh):
        raise RuntimeError("Some required PNGs are stale; an old screenshot cannot stand in for this layout.")
    directory.mkdir(parents=True, exist_ok=True)
    records = []
    for name in fresh:
        target = directory / name
        device.run("pull", f"{remote}/{name}", str(target), timeout=15)
        with target.open("rb") as stream:
            header = stream.read(24)
        if len(header) != 24 or header[:8] != b"\x89PNG\r\n\x1a\n" or header[12:16] != b"IHDR":
            raise RuntimeError(f"Invalid PNG: {name}")
        width, height = struct.unpack(">II", header[16:24])
        if not width or not height:
            raise RuntimeError(f"Empty PNG dimensions: {name}")
        records.append({"file": str(target), "width": width, "height": height,
                        "bytes": target.stat().st_size, "device_modified_epoch": modified[name]})
    return records


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="Dedicated emulator-NNNN serial.")
    parser.add_argument("--app-id", default="dev.carplaymate.demo.demo", help="Installed app package; its .test APK must also be installed.")
    parser.add_argument("--voice-ui", action="store_true",
                        help="Render voice-state fixtures instead; does not open or verify a microphone.")
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-\d+", args.serial):
        parser.error("Only dedicated emulator-NNNN serials are permitted.")
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+", args.app_id):
        parser.error("--app-id must be a valid Android package identifier.")

    local = ROOT / ".local"
    local.mkdir(exist_ok=True)
    prefix = "voice-ui" if args.voice_ui else "ui"
    count = 12 if args.voice_ui else TEST_COUNT
    test_class = "VoiceAdventureUiTest" if args.voice_ui else "FamilyUiTest"
    report_file = local / f"{prefix}-layout-report.json"
    report = {"status": "running", "serial": args.serial, "app_id": args.app_id,
              "reading_mode": not args.voice_ui, "voice_state_render_fixtures": args.voice_ui,
              "microphone_recognition_verified": False, "layouts": [], "display_restored": False}
    device = None
    original = None
    changed = False
    process = None

    def save_report() -> None:
        temporary = report_file.with_suffix(".json.tmp")
        temporary.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        temporary.replace(report_file)

    save_report()
    try:
        device = Device(find_adb(), args.serial, args.app_id)
        if device.run("get-state").stdout.strip() != "device" or device.run("shell", "getprop", "ro.kernel.qemu").stdout.strip() != "1":
            raise RuntimeError("A ready Android emulator is required.")
        for package in (args.app_id, f"{args.app_id}.test"):
            if not device.run("shell", "pm", "path", package).stdout.startswith("package:"):
                raise RuntimeError(f"Required APK is not installed: {package}")
        installed = device.run("shell", "pm", "path", args.app_id).stdout.strip().removeprefix("package:")
        if not re.fullmatch(r"/data/app/[A-Za-z0-9/_=~.\-]+\.apk", installed):
            raise RuntimeError("Expected one installed base APK on the dedicated emulator.")
        apk_sha = device.run("shell", "sha256sum", installed).stdout.split()[0]
        if not re.fullmatch(r"[0-9a-f]{64}", apk_sha):
            raise RuntimeError("Could not identify the APK used for these layouts.")
        report["apk_sha256"] = apk_sha
        original = display_state(device)
        report["original_display"] = original
        save_report()
        for name, size, density, font in LAYOUTS:
            changed = True
            device.run("shell", "wm", "size", size)
            device.run("shell", "wm", "density", density)
            device.run("shell", "settings", "put", "system", "font_scale", font)
            applied = display_state(device)
            if ((applied["size"]["override"] or applied["size"]["physical"]) != size
                    or (applied["density"]["override"] or applied["density"]["physical"]) != density
                    or float(applied["font_scale"]) != float(font)):
                raise RuntimeError(f"The requested {name} display settings were not applied.")
            earliest = next_device_second(device)
            log_path = local / f"{prefix}-layout-{name}.log"
            result = {"name": name, "status": "running", "requested": {"size": size, "density": density, "font_scale": font},
                      "applied": applied, "log": str(log_path)}
            report["layouts"].append(result)
            save_report()
            with log_path.open("w", encoding="utf-8") as log:
                process = subprocess.Popen(
                    [*device.prefix, "shell", "am", "instrument", "-w", "-r", "-e", "familyUiTests", "true",
                     "-e", "class", f"dev.carplaymate.demo.{test_class}", f"{args.app_id}.test/{RUNNER}"],
                    stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT, creationflags=CREATE_FLAGS,
                )
                process.wait(timeout=180)
            output = log_path.read_text(encoding="utf-8", errors="replace")
            ok = re.search(rf"(?m)^OK\s*\(\s*{count}\s+tests\s*\)\s*$", output) is not None
            bad = re.search(r"FAILURES!!!|INSTRUMENTATION_FAILED|process\s+crashed|INSTRUMENTATION_STATUS_CODE:\s*-[234]", output, re.IGNORECASE) is not None
            result.update(exit_code=process.returncode, ok_required_tests=ok, skipped_or_failed=bad)
            if process.returncode or not ok or bad:
                raise RuntimeError(f"{name}: all {count} UI tests must execute and pass; inspect its log.")
            shots = "voice-screenshots" if args.voice_ui else "family-screenshots"
            result["pngs"] = collect_pngs(device, local / shots / name, earliest, voice=args.voice_ui)
            result.update(status="passed", tests=count, png_count=len(result["pngs"]))
            save_report()
        report["status"] = "passed"
    except BaseException as error:
        report["status"] = "failed"
        report["error"] = str(error) or type(error).__name__
        if report["layouts"] and report["layouts"][-1]["status"] == "running":
            report["layouts"][-1]["status"] = "failed"
        raise
    finally:
        if device is not None and process is not None and (process.poll() is None or report["status"] != "passed"):
            try:
                device.run("shell", "am", "force-stop", args.app_id)
            except (OSError, RuntimeError, subprocess.SubprocessError) as error:
                report["cleanup_error"] = str(error)
        try:
            stop_client(process)
        except (OSError, subprocess.SubprocessError) as error:
            report["cleanup_error"] = str(error)
            report["status"] = "failed"
        if changed and original is not None and device is not None:
            errors = []
            commands = [
                ("shell", "wm", "size", original["size"]["override"] or "reset"),
                ("shell", "wm", "density", original["density"]["override"] or "reset"),
                ("shell", "settings", "delete", "system", "font_scale") if original["font_scale"] == "null"
                else ("shell", "settings", "put", "system", "font_scale", original["font_scale"]),
            ]
            for command in commands:
                try:
                    device.run(*command)
                except (OSError, RuntimeError, subprocess.SubprocessError) as error:
                    errors.append(str(error))
            try:
                report["restored_display"] = display_state(device)
                report["display_restored"] = not errors and report["restored_display"] == original
            except (OSError, RuntimeError, subprocess.SubprocessError) as error:
                errors.append(str(error))
            if errors or not report["display_restored"]:
                report["restore_errors"] = errors or ["Restored display does not match its original settings."]
                report["status"] = "failed"
        save_report()
    print(json.dumps(report, indent=2))
    if report["status"] != "passed":
        raise RuntimeError("Display restoration or cleanup failed; inspect .local/ui-layout-report.json.")


if __name__ == "__main__":
    try:
        main()
    except (OSError, RuntimeError, ValueError, subprocess.SubprocessError) as error:
        raise SystemExit(f"Layout smoke failed: {error}\nSee .local/ui-layout-report.json and its logs.") from None
