"""Backup policy regressions, including the optimized APK resource binding boundary."""

from copy import deepcopy
import json
from pathlib import Path
from xml.etree import ElementTree as ET

import pytest

from tools import check_android_backup as policy


def rules(name):
    return ET.parse(policy.ROOT / f"apps/vehicle/app/src/main/res/xml/{name}.xml").getroot()


def xmltree(node, depth=0):
    """Synthetic aapt2 output; no APK, build or Android device is involved."""
    lines = [" " * depth + f"E: {node.tag} (line=1)"]
    for key, value in node.attrib.items():
        key = key.replace("{" + policy.ANDROID + "}", policy.ANDROID + ":")
        encoded = value if value.startswith("@") or value in {"true", "false"} else json.dumps(value)
        lines.append(" " * (depth + 2) + f"A: {key}={encoded}")
    for child in node:
        lines += xmltree(child, depth + 2).splitlines()
    return "\n".join(lines)


@pytest.fixture
def packaged(tmp_path, monkeypatch):
    manifest = ET.fromstring(f'''<manifest xmlns:android="{policy.ANDROID}">
        <uses-sdk android:minSdkVersion="28" android:targetSdkVersion="35" />
        <application android:allowBackup="false"
          android:fullBackupContent="@0x7f120001"
          android:dataExtractionRules="@0x7f120002" />
        </manifest>''')
    table = """Package name=dev.carplaymate.demo.demo id=7f
    resource 0x7f120001 xml/backup_rules
        () (file) res/A1.xml type=XML
    resource 0x7f120002 xml/data_extraction_rules
        () (file) res/B2.xml type=XML
        (v35) (file) res/C3.xml type=XML
"""
    trees = {"AndroidManifest.xml": manifest, "res/A1.xml": rules("backup_rules"),
             "res/B2.xml": rules("data_extraction_rules"),
             "res/C3.xml": deepcopy(rules("data_extraction_rules"))}
    calls = []

    def dump(aapt2, apk, kind, file=None):
        calls.append((kind, file))
        return table if kind == "resources" else xmltree(trees[file])

    monkeypatch.setattr(policy, "aapt_dump", dump)
    apk = tmp_path / "synthetic.apk"
    apk.write_bytes(b"synthetic test only, not an Android APK")
    return apk, trees, calls


def test_source_excludes_all_backup_domains_for_supported_android_versions():
    result = policy.check_source(policy.ROOT)
    assert result["status"] == "passed"
    assert len(result["excluded_domains"]) == 9


def test_missing_device_transfer_section_is_rejected():
    node = rules("data_extraction_rules")
    node.remove(node.find("device-transfer"))
    with pytest.raises(policy.PolicyError, match="Both cloud-backup and device-transfer"):
        policy.validate_rules(node, "data-extraction-rules")


def test_named_family_preferences_exclusion_does_not_cover_checkpoints_or_future_preferences():
    node = rules("data_extraction_rules")
    node.find("device-transfer/exclude[@domain='sharedpref']").set("path", "family_v1.xml")
    with pytest.raises(policy.PolicyError, match="entire domain"):
        policy.validate_rules(node, "data-extraction-rules")


@pytest.mark.parametrize("domain", ["device_sharedpref", "device_root", "external"])
def test_less_visible_storage_domains_cannot_be_omitted(domain):
    node = rules("backup_rules")
    node.remove(node.find(f"exclude[@domain='{domain}']"))
    with pytest.raises(policy.PolicyError, match="all nine"):
        policy.validate_rules(node, "full-backup-content")


def test_include_rules_are_not_accepted_as_an_all_excluded_policy():
    node = rules("data_extraction_rules")
    ET.SubElement(node.find("cloud-backup"), "include", domain="sharedpref", path=".")
    with pytest.raises(policy.PolicyError, match="only explicit exclusions"):
        policy.validate_rules(node, "data-extraction-rules")


def test_apk_follows_manifest_ids_and_checks_renamed_xml_in_every_configuration(packaged):
    apk, _, calls = packaged
    result = policy.check_apk(apk, Path("aapt2"))
    assert result["rule_resources"]["dataExtractionRules"]["apk_paths"] == ["res/B2.xml", "res/C3.xml"]
    assert ("xmltree", "res/C3.xml") in calls
    assert result["runtime_backup_or_device_transfer_tested"] is False


def test_a_single_leaky_apk_resource_configuration_fails_the_check(packaged):
    apk, trees, _ = packaged
    variant = trees["res/C3.xml"]
    variant.remove(variant.find("device-transfer"))
    with pytest.raises(policy.PolicyError, match="Both cloud-backup and device-transfer"):
        policy.check_apk(apk, Path("aapt2"))


def test_unreferenced_good_rules_cannot_hide_a_wrong_manifest_binding(packaged):
    apk, trees, _ = packaged
    trees["AndroidManifest.xml"].find("application").set(
        f"{{{policy.ANDROID}}}dataExtractionRules", "@0x7f120001")
    with pytest.raises(policy.PolicyError, match="Expected data-extraction-rules"):
        policy.check_apk(apk, Path("aapt2"))


def test_apk_allow_backup_override_is_rejected(packaged):
    apk, trees, _ = packaged
    trees["AndroidManifest.xml"].find("application").set(f"{{{policy.ANDROID}}}allowBackup", "true")
    with pytest.raises(policy.PolicyError, match="allowBackup=false"):
        policy.check_apk(apk, Path("aapt2"))


def test_compiled_attribute_id_and_raw_value_suffix_are_parsed():
    manifest = policy.parse_xmltree('''N: android=http://schemas.android.com/apk/res/android (line=2)
  E: manifest (line=2)
    E: application (line=4)
      A: http://schemas.android.com/apk/res/android:allowBackup(0x01010280)=false
      A: http://schemas.android.com/apk/res/android:fullBackupContent(0x010104eb)=@0x7f120001
      A: http://schemas.android.com/apk/res/android:dataExtractionRules(0x0101063e)=@0x7f120002
      A: http://schemas.android.com/apk/res/android:label(0x01010001)="Test" (Raw: "Test")
''')
    assert policy.manifest_references(manifest)["dataExtractionRules"] == "@0x7f120002"


def test_resource_alias_or_unexpected_dump_format_fails_closed():
    with pytest.raises(policy.PolicyError, match="Unsupported or non-file"):
        policy.resource_xml_paths("""resource 0x7f120001 xml/backup_rules
    () @0x7f120002
""", "@0x7f120001")
