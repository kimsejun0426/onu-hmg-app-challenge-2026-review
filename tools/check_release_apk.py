"""Fail closed on an APK intended for a market upload; never sign, install or publish it.

Requires an independently selected package/version/certificate pin and the R8 map
from this build. Packaging checks are not vehicle certification, consent review or
proof of actual speech-service behavior. apksigner performs signature verification.
Official tooling: https://developer.android.com/tools/apksigner
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys
from xml.etree import ElementTree as ET
from zipfile import ZipFile

if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from tools import check_android_backup as backup
from tools import check_pleos_sdk_logs as sdk_logs

ANDROID = backup.ANDROID
PolicyError = backup.PolicyError
require = backup.require
DEVELOPER_UI = "dev.carplaymate.demo.DeveloperScreenKt"
VOICE_APPLICATION = "dev.carplaymate.demo.OnuApplication"
VOICE_SERVICE = "dev.carplaymate.demo.VoiceSessionService"
VOICE_SERVICE_SINCE = (0, 23, 0)
LETTER_SERVICE = "dev.carplaymate.demo.LetterSessionService"
LETTER_SERVICE_SINCE = (0, 52, 0)
LETTER_NOTIFICATION_PERMISSION = "android.permission.POST_NOTIFICATIONS"
LETTER_MEDIA_BROWSER_SERVICE = "dev.carplaymate.demo.LetterMediaBrowserService"
LETTER_MEDIA_BROWSER_SINCE = (0, 54, 0)
LETTER_MEDIA_BROWSER_PERMISSION = "android.permission.MEDIA_CONTENT_CONTROL"
LETTER_MEDIA_BROWSER_ACTION = "android.media.browse.MediaBrowserService"
FOREGROUND_PERMISSIONS = frozenset({
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_MICROPHONE",
    "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK",
})
ALLOWED_PERMISSIONS = frozenset({
    "android.permission.INTERNET", "android.permission.RECORD_AUDIO",
    "pleos.car.permission.STT_SERVICE", "pleos.car.permission.TTS_SERVICE",
}) | FOREGROUND_PERMISSIONS


def attribute(node: ET.Element, name: str, default: str | None = None) -> str | None:
    return node.get(f"{{{ANDROID}}}{name}", default)


def fingerprint(value: str) -> str:
    normalized = value.replace(":", "").lower()
    require(re.fullmatch(r"[0-9a-f]{64}", normalized) is not None,
            "Expected certificate pin must be a SHA-256 fingerprint")
    return normalized


def component_name(name: str | None, package: str) -> str | None:
    if not name:
        return None
    if name.startswith("."):
        return package + name
    return name if "." in name else package + "." + name


def reviewed_voice_types(value: str | None) -> bool:
    # aapt2 renders the compiled ServiceInfo bitmask; source/synthetic XML uses flag names.
    # MEDIA_PLAYBACK=0x2 and MICROPHONE=0x80. No extra or unresolved type is accepted.
    if value is None:
        return False
    if re.fullmatch(r"0[xX][0-9a-fA-F]+|[0-9]+", value):
        return int(value, 16 if value.lower().startswith("0x") else 10) == 0x82
    parts = value.split("|")
    return len(parts) == 2 and set(parts) == {"mediaPlayback", "microphone"}


def validate_voice_service(manifest: ET.Element, app: ET.Element, permissions: set,
                           package: str, version_name: str) -> dict | None:
    services = app.findall("service")
    voice_services = [service for service in services
                      if component_name(attribute(service, "name"), package) == VOICE_SERVICE]
    host = component_name(attribute(app, "name"), package)
    declared = bool(permissions & FOREGROUND_PERMISSIONS or voice_services or
                    host == VOICE_APPLICATION or
                    any(attribute(service, "foregroundServiceType") is not None
                        for service in services))
    # Keep historical pre-service APKs reproducible, while a current release cannot pass
    # by removing the entire host/service/permission group.
    if not declared and tuple(map(int, version_name.split("."))) < VOICE_SERVICE_SINCE:
        return None
    required = FOREGROUND_PERMISSIONS | {"android.permission.RECORD_AUDIO"}
    if tuple(map(int, version_name.split("."))) >= LETTER_SERVICE_SINCE:
        required = required | {LETTER_NOTIFICATION_PERMISSION}
    require(required <= permissions, "Voice service requires the complete reviewed permission set")
    for name in required:
        declarations = [item for item in manifest if item.tag.startswith("uses-permission")
                        and attribute(item, "name") == name]
        require(len(declarations) == 1 and declarations[0].tag == "uses-permission" and
                attribute(declarations[0], "maxSdkVersion") is None,
                "Voice service permissions must be unconditional and unambiguous")
    require(host == VOICE_APPLICATION, "Voice service requires the reviewed OnuApplication host")
    require(attribute(app, "enabled", "true") == "true" and
            attribute(app, "process", package) == package,
            "Voice application must be enabled in the main process")
    require(len(voice_services) == 1, "Exactly one reviewed VoiceSessionService is required")
    service = voice_services[0]
    require(attribute(service, "exported") == "false" and not service.findall("intent-filter"),
            "VoiceSessionService must be explicitly private without intent filters")
    require(attribute(service, "enabled", "true") == "true" and
            attribute(service, "process", package) == package and
            attribute(service, "isolatedProcess", "false") == "false" and
            attribute(service, "externalService", "false") == "false" and
            attribute(service, "useAppZygote", "false") == "false",
            "VoiceSessionService must be enabled in the application main process")
    require(reviewed_voice_types(attribute(service, "foregroundServiceType")),
            "VoiceSessionService requires exactly mediaPlayback and microphone types")
    letters = [item for item in services
               if component_name(attribute(item, "name"), package) == LETTER_SERVICE]
    current_letter = tuple(map(int, version_name.split("."))) >= LETTER_SERVICE_SINCE
    require(len(letters) == (1 if current_letter else 0),
            "Exactly one reviewed LetterSessionService is required from 0.52.0")
    letter = letters[0] if letters else None
    if letter is not None:
        require(attribute(letter, "exported") == "false" and not letter.findall("intent-filter"),
                "LetterSessionService must be explicitly private without intent filters")
        require(attribute(letter, "enabled", "true") == "true" and
                attribute(letter, "process", package) == package and
                attribute(letter, "isolatedProcess", "false") == "false" and
                attribute(letter, "externalService", "false") == "false" and
                attribute(letter, "useAppZygote", "false") == "false",
                "LetterSessionService must be enabled in the application main process")
        require(reviewed_voice_types(attribute(letter, "foregroundServiceType")),
                "LetterSessionService requires exactly mediaPlayback and microphone types")
    require(all(other is service or other is letter or attribute(other, "foregroundServiceType") is None
                for other in services), "Unreviewed foreground service")
    report = {"application": host, "service": VOICE_SERVICE, "exported": False,
              "enabled": True, "process": package,
              "foreground_service_types": ["mediaPlayback", "microphone"]}
    if letter is not None:
        report["letter_service"] = {"service": LETTER_SERVICE, "exported": False,
                                    "enabled": True, "process": package,
                                    "foreground_service_types": ["mediaPlayback", "microphone"]}
    return report


def validate_letter_media_browser(app: ET.Element, package: str, version_name: str) -> dict | None:
    services = [service for service in app.findall("service")
                if component_name(attribute(service, "name"), package) == LETTER_MEDIA_BROWSER_SERVICE]
    current = tuple(map(int, version_name.split("."))) >= LETTER_MEDIA_BROWSER_SINCE
    require(len(services) == (1 if current else 0),
            "Exactly one reviewed LetterMediaBrowserService is required from 0.54.0 only")
    if not services:
        return None
    service = services[0]
    require(attribute(service, "exported") == "true" and
            attribute(service, "permission") == LETTER_MEDIA_BROWSER_PERMISSION,
            "LetterMediaBrowserService must be explicitly exported with MEDIA_CONTENT_CONTROL")
    # Enabling this probe changes Pleos launcher classification. Release APKs keep
    # the reviewed declaration inert; a demo-only probe is not a release exemption.
    require(attribute(service, "enabled") == "false",
            "LetterMediaBrowserService must be explicitly disabled in release APKs")
    require(attribute(service, "process", package) == package and
            attribute(service, "isolatedProcess", "false") == "false" and
            attribute(service, "externalService", "false") == "false" and
            attribute(service, "useAppZygote", "false") == "false",
            "LetterMediaBrowserService must use the application main process")
    require(attribute(service, "foregroundServiceType") is None,
            "LetterMediaBrowserService must not declare a foreground service type")
    # This is one platform-protected legacy browse surface, not a general exemption
    # for services with a known name, permission, or an intent containing this action.
    filters = service.findall("intent-filter")
    require(len(filters) == 1 and list(service) == filters and not filters[0].attrib and
            len(filters[0]) == 1 and filters[0][0].tag == "action" and
            filters[0][0].attrib == {f"{{{ANDROID}}}name": LETTER_MEDIA_BROWSER_ACTION} and
            not list(filters[0][0]),
            "LetterMediaBrowserService requires exactly the legacy MediaBrowserService action")
    return {"service": LETTER_MEDIA_BROWSER_SERVICE, "exported": True,
            "permission": LETTER_MEDIA_BROWSER_PERMISSION, "enabled": False, "process": package,
            "intent_actions": [LETTER_MEDIA_BROWSER_ACTION], "foreground_service_types": []}


def validate_manifest(manifest: ET.Element, package: str, version_name: str,
                      version_code: int) -> dict:
    require(manifest.get("package") == package, "APK package does not match release policy")
    # The historical base application ID itself ends in '.demo'. Compare the actual
    # development variant IDs, while the independently supplied package pins identity.
    require(package not in {"dev.carplaymate.demo.demo", "dev.carplaymate.demo.debug"}
            and not package.endswith(".test"), "Development package is forbidden")
    require(re.fullmatch(r"\d+\.\d+\.\d+", version_name) is not None,
            "Release version must have three numeric components and no development suffix")
    require(attribute(manifest, "versionName") == version_name, "APK versionName mismatch")
    require(version_code > 0 and attribute(manifest, "versionCode") == str(version_code),
            "APK versionCode mismatch")
    require(manifest.find("instrumentation") is None, "Instrumentation APK is forbidden")
    sdk = manifest.find("uses-sdk")
    require(sdk is not None and attribute(sdk, "minSdkVersion") == "28" and
            attribute(sdk, "targetSdkVersion") == "35", "Unexpected supported SDK contract")
    app = manifest.find("application")
    require(app is not None, "Application is absent")
    for flag in ("debuggable", "testOnly", "vmSafeMode"):
        require(attribute(app, flag, "false") == "false", f"Release {flag} must be false")
    require(attribute(app, "usesCleartextTraffic") == "false", "Cleartext traffic must be disabled")
    require(all(attribute(item, "shell", "false") == "false"
                for item in app.findall("profileable")), "Shell profiling is forbidden")
    permissions = {attribute(item, "name") for item in manifest
                   if item.tag.startswith("uses-permission")}
    own_permission = package + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
    allowed = ALLOWED_PERMISSIONS | {own_permission}
    if tuple(map(int, version_name.split("."))) >= LETTER_SERVICE_SINCE:
        allowed = allowed | {LETTER_NOTIFICATION_PERMISSION}
    require(permissions <= allowed,
            "APK requests permissions outside the reviewed release allowlist")
    for permission in manifest.findall("permission"):
        require(attribute(permission, "name") == own_permission and
                attribute(permission, "protectionLevel") in {"signature", "0x00000002", "2"},
                "Unexpected or unprotected application permission")
    media_browser = validate_letter_media_browser(app, package, version_name)
    voice_service = validate_voice_service(manifest, app, permissions, package, version_name)
    launchers = []
    for component in app:
        if component.tag not in {"activity", "activity-alias", "provider", "receiver", "service"}:
            continue
        name = attribute(component, "name", "")
        require(not component.findall("intent-filter") or attribute(component, "exported") is not None,
                "Intent-filter component must explicitly declare its exported state")
        exported = attribute(component, "exported", "false")
        require(exported in {"true", "false"}, "Unresolved component export setting")
        if exported == "false":
            continue
        is_launcher = component.tag == "activity" and any(
            any(attribute(action, "name") == "android.intent.action.MAIN"
                for action in intent.findall("action")) and
            any(attribute(category, "name") == "android.intent.category.LAUNCHER"
                for category in intent.findall("category"))
            for intent in component.findall("intent-filter"))
        if is_launcher and name == "dev.carplaymate.demo.MainActivity":
            launchers.append(name)
        elif (media_browser is not None and component.tag == "service" and
              component_name(name, package) == LETTER_MEDIA_BROWSER_SERVICE):
            # The complete declaration was checked above; no other export is allowed.
            continue
        else:
            require(component.tag == "receiver" and
                    name == "androidx.profileinstaller.ProfileInstallReceiver" and
                    attribute(component, "permission") == "android.permission.DUMP",
                    "Unreviewed exported component")
    require(len(launchers) == 1, "Exactly one reviewed launcher is required")
    return {"package": package, "version_name": version_name, "version_code": version_code,
            "permissions": sorted(permissions), "debuggable": False, "test_only": False,
            "voice_session_service": voice_service, "letter_media_browser_service": media_browser}


def validate_network_rules(tree: ET.Element) -> None:
    require(tree.tag == "network-security-config", "Unexpected network policy root")
    for node in tree.iter():
        require(node.get("cleartextTrafficPermitted", "false") == "false",
                "A network resource permits cleartext traffic")
        require(node.tag != "debug-overrides", "Development trust overrides are forbidden")
        if node.tag == "certificates":
            require(node.get("src") == "system", "Non-system trust anchors require a policy review")


def validate_signature(output: str, expected_pin: str) -> dict:
    pin = fingerprint(expected_pin)
    require(re.search(r"^Verifies\s*$", output, re.MULTILINE) is not None,
            "apksigner did not verify the APK")
    require(re.search(r"^Verified using v[23](?:\.\d+)? scheme .*: true\s*$", output,
                      re.MULTILINE) is not None, "APK must verify with a modern signing scheme")
    digests = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)\s*$",
                         output, re.MULTILINE)
    require(len(digests) == 1 and fingerprint(digests[0]) == pin,
            "APK signer does not match the single approved certificate")
    subjects = re.findall(r"^Signer #\d+ certificate DN: (.+)$", output, re.MULTILINE)
    require(len(subjects) == 1 and "android debug" not in subjects[0].lower(),
            "Android development certificate is forbidden")
    return {"verified": True, "certificate_sha256": pin, "development_certificate": False}


def validate_archive_names(names: list[str]) -> None:
    require(len(names) == len(set(names)), "Duplicate ZIP entries are forbidden")
    for name in names:
        path = PurePosixPath(name)
        require(not path.is_absolute() and ".." not in path.parts and "\\" not in name,
                "Unsafe APK archive path")
        lower = name.lower()
        parts = set(PurePosixPath(lower).parts)
        require(not parts & {"fixtures", "androidtest", "testdata", ".local", ".git"},
                "Test fixtures or local development data are packaged")
        require(not lower.endswith((".jks", ".keystore", ".p12", ".pfx", ".pk8", ".key")) and
                path.name.lower() not in {"local.properties", "demo-token", ".env"} and
                not path.name.lower().startswith(".env."),
                "A credential/configuration file is packaged")


def release_descriptors(mapping: Path) -> set[str]:
    require(mapping.is_file() and mapping.stat().st_size > 0, "R8 mapping is required")
    names = {DEVELOPER_UI}
    for line in mapping.read_text(encoding="utf-8").splitlines():
        match = re.fullmatch(r"(\S+) -> (\S+):", line)
        if match and (match[1] == DEVELOPER_UI or
                      match[1].startswith(DEVELOPER_UI + "$")):
            names.update((match[1], match[2]))
    return sdk_logs.descriptors(mapping) | {"L" + name.replace(".", "/") + ";" for name in names}


def mapping_identity(mapping: Path) -> str:
    text = mapping.read_text(encoding="utf-8")
    require(re.search(r"^# compiler: R8\s*$", text, re.MULTILINE) is not None,
            "A full R8 mapping from this build is required")
    identities = re.findall(r"^# pg_map_id: ([0-9a-z]+)\s*$", text, re.MULTILINE)
    require(len(identities) == 1, "R8 mapping identity is absent or ambiguous")
    return identities[0]


def validate_dex_mapping(data: bytes, identity: str) -> None:
    # R8 embeds this public marker in every output DEX. Dependency CF markers
    # may also remain, so bind only the final DEX compiler marker to the map.
    markers = [json.loads(value) for value in re.findall(rb'~~R8(\{[^\x00]+?\})', data)]
    final = [marker for marker in markers if marker.get("backend") == "dex"]
    require(bool(final) and all(marker.get("compilation-mode") == "release" and
                               marker.get("pg-map-id") == identity for marker in final),
            "APK DEX is not bound to the supplied release R8 mapping")


def verify_signature(apk: Path, apksigner: Path, pin: str) -> dict:
    # Only public certificate data is requested; no keystore or passwords are consumed.
    result = subprocess.run([str(apksigner), "verify", "--verbose", "--print-certs", str(apk)],
                            capture_output=True, text=True, encoding="utf-8", errors="replace",
                            timeout=60, check=False)
    require(result.returncode == 0, "apksigner rejected the APK signature")
    return validate_signature(result.stdout, pin)


def check_apk(apk: Path, mapping: Path, aapt2: Path, apksigner: Path, *, package: str,
              version_name: str, version_code: int, certificate_sha256: str) -> dict:
    signature = verify_signature(apk, apksigner, certificate_sha256)
    manifest = backup.parse_xmltree(backup.aapt_dump(aapt2, apk, "xmltree", "AndroidManifest.xml"))
    manifest_result = validate_manifest(manifest, package, version_name, version_code)
    backup_result = backup.check_apk(apk, aapt2)
    app = manifest.find("application")
    network_ref = attribute(app, "networkSecurityConfig")
    network_paths = []
    if network_ref:
        table = backup.aapt_dump(aapt2, apk, "resources")
        network_paths = backup.resource_xml_paths(table, network_ref)
        for path in network_paths:
            validate_network_rules(backup.parse_xmltree(backup.aapt_dump(aapt2, apk, "xmltree", path)))
    prohibited = release_descriptors(mapping)
    map_id = mapping_identity(mapping)
    with ZipFile(apk) as archive:
        names = archive.namelist()
        validate_archive_names(names)
        dex_files = sorted(name for name in names if re.fullmatch(r"classes\d*\.dex", name))
        require(bool(dex_files), "APK contains no DEX")
        for name in dex_files:
            data = archive.read(name)
            validate_dex_mapping(data, map_id)
            references = sdk_logs.dex_references(data, prohibited)
            require(not references["type_references"] and not references["method_references"],
                    "SDK payload logger or development UI remains in APK DEX")
    return {"status": "passed", "apk_sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
            "mapping_sha256": hashlib.sha256(mapping.read_bytes()).hexdigest(), "r8_map_id": map_id,
            "manifest": manifest_result, "signature": signature, "backup": backup_result,
            "network_xml_paths": network_paths, "dex_checked": dex_files,
            "sdk_payload_loggers_absent": True, "development_ui_absent": True,
            "test_fixtures_and_credential_files_absent": True,
            "scope": "APK packaging and signature policy only; not market approval or runtime vehicle/speech validation."}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    for arg in ("apk", "mapping", "aapt2", "apksigner"):
        parser.add_argument("--" + arg, required=True, type=Path)
    parser.add_argument("--expected-package", required=True)
    parser.add_argument("--expected-version-name", required=True)
    parser.add_argument("--expected-version-code", required=True, type=int)
    parser.add_argument("--expected-cert-sha256", required=True)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    try:
        report = check_apk(args.apk, args.mapping, args.aapt2, args.apksigner,
                           package=args.expected_package, version_name=args.expected_version_name,
                           version_code=args.expected_version_code,
                           certificate_sha256=args.expected_cert_sha256)
    except (PolicyError, OSError, ValueError, subprocess.SubprocessError) as error:
        # Never dump arbitrary tool stdout/stderr or credential material.
        report = {"status": "failed", "reason": str(error) if isinstance(error, PolicyError)
                  else "Artifact/tool input could not be verified"}
    rendered = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    if args.report:
        args.report.write_text(rendered, encoding="utf-8")
    print(rendered, end="")
    if report["status"] != "passed":
        raise SystemExit(1)


if __name__ == "__main__":
    main()
