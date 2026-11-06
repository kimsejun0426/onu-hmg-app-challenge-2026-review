"""Verify source policy and, optionally, the actual APK's Android backup exclusions.

No build, install, device access or backup is performed. APK checking follows the
manifest's compiled resource IDs, including all resource configurations and renamed
XML paths. A passing result proves packaging, not OEM backup/transfer behavior.

Usage: python tools/check_android_backup.py [--apk app-demo.apk --aapt2 PATH]
Official policy/schema: https://developer.android.com/identity/data/autobackup
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
from xml.etree import ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ANDROID = "http://schemas.android.com/apk/res/android"
DOMAINS = frozenset({"root", "file", "database", "sharedpref", "external",
                     "device_root", "device_file", "device_database", "device_sharedpref"})
REFERENCES = {"fullBackupContent": "full-backup-content",
              "dataExtractionRules": "data-extraction-rules"}


class PolicyError(ValueError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise PolicyError(message)


def validate_exclusions(node: ET.Element, label: str) -> None:
    require(all(child.tag == "exclude" for child in node),
            f"{label}: only explicit exclusions are permitted")
    require(len(node) == len(DOMAINS), f"{label}: expected all nine app-data domains")
    require({child.get("domain") for child in node} == DOMAINS,
            f"{label}: an app-data domain is missing or duplicated")
    require(all(child.attrib == {"domain": child.get("domain"), "path": "."}
                and not len(child) for child in node),
            f"{label}: exclusions must cover each entire domain with path='.'")


def validate_rules(node: ET.Element, expected_root: str) -> None:
    require(node.tag == expected_root, f"Expected {expected_root}, found {node.tag}")
    if expected_root == "full-backup-content":
        validate_exclusions(node, expected_root)
    else:
        require(len(node) == 2 and {child.tag for child in node} ==
                {"cloud-backup", "device-transfer"},
                "Both cloud-backup and device-transfer must have explicit rules")
        for child in node:
            validate_exclusions(child, child.tag)


def manifest_references(manifest: ET.Element) -> dict[str, str]:
    require(manifest.tag == "manifest", "Expected Android manifest")
    application = manifest.find("application")
    require(application is not None, "Manifest has no application")
    require(application.get(f"{{{ANDROID}}}allowBackup") == "false",
            "Application must retain allowBackup=false")
    references = {}
    for attribute in REFERENCES:
        value = application.get(f"{{{ANDROID}}}{attribute}")
        require(value is not None and value.startswith("@"),
                f"Application must reference {attribute}")
        references[attribute] = value
    return references


def check_source(root: Path) -> dict:
    main = root / "apps/vehicle/app/src/main"
    refs = manifest_references(ET.parse(main / "AndroidManifest.xml").getroot())
    files = {}
    for attribute, ref in refs.items():
        require(re.fullmatch(r"@xml/[a-z0-9_]+", ref) is not None,
                f"Unsupported source resource reference: {ref}")
        path = main / "res/xml" / (ref.removeprefix("@xml/") + ".xml")
        validate_rules(ET.parse(path).getroot(), REFERENCES[attribute])
        files[attribute] = str(path.relative_to(root))
    return {"status": "passed", "rule_files": files, "excluded_domains": sorted(DOMAINS)}


def parse_xmltree(text: str) -> ET.Element:
    """Read aapt2's tree without relying on original (possibly shortened) APK paths."""
    stack: list[tuple[int, ET.Element]] = []
    root = None
    for line in text.splitlines():
        element = re.match(r"^(\s*)E: ([\w-]+) \(line=\d+\)", line)
        if element:
            indent, tag = len(element[1]), element[2]
            while stack and stack[-1][0] >= indent:
                stack.pop()
            node = ET.Element(tag)
            if stack:
                stack[-1][1].append(node)
            else:
                require(root is None, "Multiple roots in aapt2 XML output")
                root = node
            stack.append((indent, node))
            continue
        attribute = re.match(r"^\s*A: (.+?)(?:\(0x[0-9a-fA-F]+\))?=(.*)$", line)
        if attribute:
            require(bool(stack), "Attribute outside an XML element")
            key, value = attribute[1], attribute[2].strip()
            if key.startswith(ANDROID + ":"):
                key = f"{{{ANDROID}}}" + key.removeprefix(ANDROID + ":")
            if value.startswith('"'):
                value, _ = json.JSONDecoder().raw_decode(value)
            stack[-1][1].set(key, value)
    require(root is not None, "No XML root in aapt2 output")
    return root


def resource_xml_paths(table: str, reference: str) -> list[str]:
    require(re.fullmatch(r"@0x[0-9a-fA-F]{8}", reference) is not None,
            f"Expected compiled resource ID, found {reference}")
    target = reference[1:].lower()
    active = False
    paths = []
    for line in table.splitlines():
        entry = re.match(r"^\s*resource (0x[0-9a-fA-F]{8}) (\S+)", line)
        if entry:
            active = entry[1].lower() == target
            if active:
                require(entry[2].startswith("xml/"), f"{reference} does not reference XML")
            continue
        if active and line.strip():
            file = re.match(r"^\s*\([^)]*\) \(file\) (\S+) type=XML\s*$", line)
            require(file is not None,
                    f"Unsupported or non-file XML configuration for {reference}: {line.strip()}")
            paths.append(file[1])
    require(bool(paths), f"No packaged XML files for {reference}")
    return sorted(set(paths))


def aapt_dump(aapt2: Path, apk: Path, kind: str, file: str | None = None) -> str:
    command = [str(aapt2), "dump", kind, str(apk)]
    if file is not None:
        command += ["--file", file]
    result = subprocess.run(command, capture_output=True, text=True, encoding="utf-8",
                            errors="strict", timeout=40, check=True)
    return result.stdout


def check_apk(apk: Path, aapt2: Path) -> dict:
    manifest = parse_xmltree(aapt_dump(aapt2, apk, "xmltree", "AndroidManifest.xml"))
    refs = manifest_references(manifest)
    table = aapt_dump(aapt2, apk, "resources")
    checked = {}
    for attribute, ref in refs.items():
        paths = resource_xml_paths(table, ref)
        for path in paths:
            tree = parse_xmltree(aapt_dump(aapt2, apk, "xmltree", path))
            validate_rules(tree, REFERENCES[attribute])
        checked[attribute] = {"resource_id": ref, "apk_paths": paths}
    sdk = manifest.find("uses-sdk")
    return {"status": "passed", "apk": str(apk),
            "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(), "rule_resources": checked,
            "min_sdk": sdk.get(f"{{{ANDROID}}}minSdkVersion") if sdk is not None else None,
            "target_sdk": sdk.get(f"{{{ANDROID}}}targetSdkVersion") if sdk is not None else None,
            "runtime_backup_or_device_transfer_tested": False}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-root", type=Path, default=ROOT)
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--aapt2", type=Path)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    if bool(args.apk) != bool(args.aapt2):
        parser.error("--apk and --aapt2 must be supplied together")
    report = {"source": check_source(args.source_root),
              "apk": check_apk(args.apk, args.aapt2) if args.apk else {"status": "not_checked"},
              "scope": "Android cloud backup and device-to-device transfer packaging; not OEM runtime verification."}
    rendered = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    if args.report:
        args.report.write_text(rendered, encoding="utf-8")
    print(rendered, end="")


if __name__ == "__main__":
    main()
