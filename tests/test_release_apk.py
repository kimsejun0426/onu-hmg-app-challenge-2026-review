"""Release policy tests use synthetic XML/DEX/signature output, never private keys."""

from copy import deepcopy
from pathlib import Path
import struct
from xml.etree import ElementTree as ET
from zipfile import ZipFile

import pytest

from tools import check_release_apk as gate

PACKAGE = "dev.carplaymate.demo"
PIN = "ab" * 32


def manifest():
    return ET.fromstring(f'''<manifest xmlns:android="{gate.ANDROID}"
        package="{PACKAGE}" android:versionName="0.5.0" android:versionCode="6">
      <uses-sdk android:minSdkVersion="28" android:targetSdkVersion="35"/>
      <uses-permission android:name="android.permission.RECORD_AUDIO"/>
      <application android:allowBackup="false" android:usesCleartextTraffic="false"
        android:fullBackupContent="@0x7f120001" android:dataExtractionRules="@0x7f120002">
        <activity android:name="dev.carplaymate.demo.MainActivity" android:exported="true">
          <intent-filter><action android:name="android.intent.action.MAIN"/>
          <category android:name="android.intent.category.LAUNCHER"/></intent-filter>
        </activity>
        <provider android:name="androidx.startup.InitializationProvider" android:exported="false"/>
        <receiver android:name="androidx.profileinstaller.ProfileInstallReceiver"
          android:exported="true" android:permission="android.permission.DUMP"/>
      </application>
    </manifest>''')


def set_attribute(node, name, value):
    node.set(f"{{{gate.ANDROID}}}{name}", value)


def check_manifest(node):
    return gate.validate_manifest(node, PACKAGE, "0.5.0", 6)


def voice_manifest():
    node = manifest()
    set_attribute(node, "versionName", "0.23.0")
    set_attribute(node, "versionCode", "24")
    for name in gate.FOREGROUND_PERMISSIONS:
        set_attribute(ET.SubElement(node, "uses-permission"), "name", name)
    app = node.find("application")
    set_attribute(app, "name", ".OnuApplication")
    service = ET.SubElement(app, "service")
    set_attribute(service, "name", ".VoiceSessionService")
    set_attribute(service, "exported", "false")
    set_attribute(service, "foregroundServiceType", "microphone|mediaPlayback")
    return node


def check_voice_manifest(node):
    return gate.validate_manifest(node, PACKAGE, "0.23.0", 24)


def signature(subject="CN=Release Test Only", digest=PIN):
    return ("Verifies\nVerified using v2 scheme (APK Signature Scheme v2): true\n"
            f"Signer #1 certificate DN: {subject}\nSigner #1 certificate SHA-256 digest: {digest}\n")


def test_reviewed_manifest_and_public_signature_policy_pass():
    assert check_manifest(manifest())["package"] == PACKAGE
    assert check_manifest(manifest())["voice_session_service"] is None
    assert gate.validate_signature(signature(), PIN.upper())["certificate_sha256"] == PIN


@pytest.mark.parametrize("types", ["microphone|mediaPlayback", "mediaPlayback|microphone",
                                   "0x00000082", "130"])
def test_reviewed_voice_service_accepts_only_its_source_and_compiled_type_pair(types):
    node = voice_manifest()
    app = node.find("application")
    service = app.find("service")
    set_attribute(service, "foregroundServiceType", types)
    # Compiled names may be absolute; both spellings identify exactly the same classes.
    if types.startswith("0x"):
        set_attribute(app, "name", gate.VOICE_APPLICATION)
        set_attribute(service, "name", gate.VOICE_SERVICE)
        set_attribute(app, "process", PACKAGE)
        set_attribute(service, "process", PACKAGE)
    result = check_voice_manifest(node)
    assert result["voice_session_service"] == {
        "application": "dev.carplaymate.demo.OnuApplication",
        "service": "dev.carplaymate.demo.VoiceSessionService",
        "exported": False, "enabled": True, "process": PACKAGE,
        "foreground_service_types": ["mediaPlayback", "microphone"],
    }


def test_current_release_cannot_drop_the_whole_voice_host_configuration():
    node = manifest()
    set_attribute(node, "versionName", "0.23.0")
    set_attribute(node, "versionCode", "24")
    with pytest.raises(gate.PolicyError, match="complete reviewed permission set"):
        check_voice_manifest(node)


@pytest.mark.parametrize("declaration", ["permission", "host", "service", "other_service"])
def test_partial_voice_configuration_cannot_use_historical_version_exemption(declaration):
    node = manifest()
    app = node.find("application")
    if declaration == "permission":
        set_attribute(ET.SubElement(node, "uses-permission"), "name",
                      "android.permission.FOREGROUND_SERVICE_MICROPHONE")
    elif declaration == "host":
        set_attribute(app, "name", ".OnuApplication")
    else:
        service = ET.SubElement(app, "service")
        set_attribute(service, "name", ".VoiceSessionService" if declaration == "service"
                      else ".OtherPrivateService")
        set_attribute(service, "exported", "false")
        if declaration == "other_service":
            set_attribute(service, "foregroundServiceType", "microphone")
    with pytest.raises(gate.PolicyError, match="complete reviewed permission set"):
        check_manifest(node)


@pytest.mark.parametrize("missing", ["android.permission.FOREGROUND_SERVICE",
                                     "android.permission.FOREGROUND_SERVICE_MICROPHONE",
                                     "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK",
                                     "android.permission.RECORD_AUDIO"])
def test_voice_service_needs_all_permissions_not_just_the_manifest_type(missing):
    node = voice_manifest()
    node.remove(next(item for item in node if gate.attribute(item, "name") == missing))
    with pytest.raises(gate.PolicyError, match="complete reviewed permission set"):
        check_voice_manifest(node)


@pytest.mark.parametrize("mutation", ["sdk_limit", "conditional_tag", "duplicate"])
def test_voice_service_permission_presence_cannot_hide_a_conditional_grant(mutation):
    node = voice_manifest()
    permission = next(item for item in node if gate.attribute(item, "name") ==
                      "android.permission.FOREGROUND_SERVICE_MICROPHONE")
    if mutation == "sdk_limit":
        set_attribute(permission, "maxSdkVersion", "33")
    elif mutation == "conditional_tag":
        permission.tag = "uses-permission-sdk-23"
    else:
        node.append(deepcopy(permission))
    with pytest.raises(gate.PolicyError, match="unconditional and unambiguous"):
        check_voice_manifest(node)


@pytest.mark.parametrize("types", [None, "microphone", "mediaPlayback", "0x00000080",
                                   "0x00000083", "mediaPlayback|microphone|location",
                                   "mediaPlayback|microphone|microphone", "@0x7f010001"])
def test_missing_or_additional_foreground_service_types_are_rejected(types):
    node = voice_manifest()
    service = node.find("application/service")
    if types is None:
        service.attrib.pop(f"{{{gate.ANDROID}}}foregroundServiceType")
    else:
        set_attribute(service, "foregroundServiceType", types)
    with pytest.raises(gate.PolicyError, match="exactly mediaPlayback and microphone"):
        check_voice_manifest(node)


@pytest.mark.parametrize("mutation", ["exported", "implicit_export", "intent_filter"])
def test_voice_service_must_have_no_external_activation_surface(mutation):
    node = voice_manifest()
    service = node.find("application/service")
    if mutation == "exported":
        set_attribute(service, "exported", "true")
    elif mutation == "implicit_export":
        service.attrib.pop(f"{{{gate.ANDROID}}}exported")
    else:
        ET.SubElement(ET.SubElement(service, "intent-filter"), "action")
    with pytest.raises(gate.PolicyError, match="explicitly private"):
        check_voice_manifest(node)


@pytest.mark.parametrize("path,attribute,value", [
    ("application", "name", ".OtherApplication"),
    ("application", "enabled", "false"),
    ("application", "process", ":voice"),
    ("application/service", "enabled", "false"),
    ("application/service", "process", ":voice"),
    ("application/service", "process", "other.process"),
    ("application/service", "isolatedProcess", "true"),
    ("application/service", "externalService", "true"),
    ("application/service", "useAppZygote", "true"),
])
def test_voice_service_requires_the_enabled_shared_application_host(path, attribute, value):
    node = voice_manifest()
    set_attribute(node.find(path), attribute, value)
    with pytest.raises(gate.PolicyError, match="host|main process"):
        check_voice_manifest(node)


@pytest.mark.parametrize("mutation", ["missing_host", "missing_service", "wrong_service",
                                      "duplicate_service", "second_microphone_service"])
def test_another_private_service_cannot_substitute_or_share_the_reviewed_voice_capability(mutation):
    node = voice_manifest()
    app = node.find("application")
    service = app.find("service")
    if mutation == "missing_host":
        app.attrib.pop(f"{{{gate.ANDROID}}}name")
    elif mutation == "missing_service":
        app.remove(service)
    elif mutation == "wrong_service":
        set_attribute(service, "name", ".OtherPrivateService")
    else:
        other = deepcopy(service)
        app.append(other)
        if mutation == "second_microphone_service":
            set_attribute(other, "name", ".OtherPrivateService")
            set_attribute(other, "foregroundServiceType", "0x00000080")
    with pytest.raises(gate.PolicyError, match="host|Exactly one|Unreviewed foreground"):
        check_voice_manifest(node)


@pytest.mark.parametrize("flag", ["debuggable", "testOnly", "vmSafeMode", "usesCleartextTraffic"])
def test_development_manifest_flags_fail(flag):
    node = manifest()
    set_attribute(node.find("application"), flag, "true")
    with pytest.raises(gate.PolicyError):
        check_manifest(node)


@pytest.mark.parametrize("name,value", [("versionName", "0.4.1"), ("versionCode", "5")])
def test_stale_apk_cannot_satisfy_new_release_policy(name, value):
    node = manifest()
    set_attribute(node, name, value)
    with pytest.raises(gate.PolicyError, match="mismatch"):
        check_manifest(node)


def test_development_package_is_rejected_even_if_caller_requests_it():
    node = manifest()
    node.set("package", PACKAGE + ".demo")
    with pytest.raises(gate.PolicyError, match="Development package"):
        gate.validate_manifest(node, PACKAGE + ".demo", "0.5.0", 6)


def test_unreviewed_permission_and_exported_component_are_rejected():
    node = manifest()
    added = ET.SubElement(node, "uses-permission-sdk-23")
    set_attribute(added, "name", "android.permission.READ_CONTACTS")
    with pytest.raises(gate.PolicyError, match="permissions"):
        check_manifest(node)
    node.remove(added)
    set_attribute(node.find("application/provider"), "exported", "true")
    with pytest.raises(gate.PolicyError, match="exported"):
        check_manifest(node)


def test_profileinstaller_must_retain_its_system_permission():
    node = manifest()
    node.find("application/receiver").attrib.pop(f"{{{gate.ANDROID}}}permission")
    with pytest.raises(gate.PolicyError, match="exported"):
        check_manifest(node)


def test_uninstallable_implicit_export_of_filtered_component_is_rejected():
    node = manifest()
    component = ET.SubElement(node.find("application"), "receiver")
    ET.SubElement(component, "intent-filter")
    with pytest.raises(gate.PolicyError, match="explicitly declare"):
        check_manifest(node)


def test_instrumentation_and_shell_profiling_are_not_release_capabilities():
    node = manifest()
    ET.SubElement(node, "instrumentation")
    with pytest.raises(gate.PolicyError, match="Instrumentation"):
        check_manifest(node)
    node.remove(node.find("instrumentation"))
    set_attribute(ET.SubElement(node.find("application"), "profileable"), "shell", "true")
    with pytest.raises(gate.PolicyError, match="profiling"):
        check_manifest(node)


@pytest.mark.parametrize("text,reason", [
    (signature("CN=Android Debug,O=Android,C=US"), "development certificate"),
    (signature(digest="cd" * 32), "approved certificate"),
    (signature().replace(": true", ": false"), "modern signing"),
    (signature().replace("Verifies\n", ""), "did not verify"),
    (signature() + f"Signer #2 certificate SHA-256 digest: {PIN}\n", "approved certificate"),
])
def test_unapproved_signature_cannot_pass(text, reason):
    with pytest.raises(gate.PolicyError, match=reason):
        gate.validate_signature(text, PIN)


@pytest.mark.parametrize("xml", [
    '<network-security-config><domain-config cleartextTrafficPermitted="true"><domain>localhost</domain></domain-config></network-security-config>',
    '<network-security-config><debug-overrides/></network-security-config>',
    '<network-security-config><base-config><trust-anchors><certificates src="user"/></trust-anchors></base-config></network-security-config>',
])
def test_compiled_network_policy_cannot_reintroduce_development_exceptions(xml):
    with pytest.raises(gate.PolicyError):
        gate.validate_network_rules(ET.fromstring(xml))


@pytest.mark.parametrize("name", ["assets/fixtures/game.json", "assets/.env", "assets/private.p12",
                                 "assets/demo-token", "../secret", "assets\\fixtures\\game.json"])
def test_fixture_secret_and_unsafe_archive_paths_fail(name):
    with pytest.raises(gate.PolicyError):
        gate.validate_archive_names(["classes.dex", name])


def test_duplicate_archive_entries_fail_closed():
    with pytest.raises(gate.PolicyError, match="Duplicate"):
        gate.validate_archive_names(["classes.dex", "classes.dex"])


def minimal_dex(types):
    """Only tables consumed by the verifier; this is not an installable DEX fixture."""
    data = bytearray(112 + 8 * len(types))
    data[:8] = b"dex\n035\0"
    struct.pack_into("<I", data, 40, 0x12345678)
    struct.pack_into("<II", data, 56, len(types), 112)
    struct.pack_into("<II", data, 64, len(types), 112 + 4 * len(types))
    for i, value in enumerate(types):
        encoded = value.encode("ascii")
        assert len(encoded) < 128
        struct.pack_into("<I", data, 112 + 4 * i, len(data))
        struct.pack_into("<I", data, 112 + 4 * len(types) + 4 * i, i)
        data.extend(bytes([len(encoded)]) + encoded + b"\0")
    data.extend(b'~~R8{"backend":"dex","compilation-mode":"release","pg-map-id":"123abcd"}\0')
    return bytes(data)


@pytest.fixture
def packaged(tmp_path, monkeypatch):
    apk = tmp_path / "synthetic.apk"
    mapping = tmp_path / "mapping.txt"
    mapping.write_text("# compiler: R8\n# pg_map_id: 123abcd\n"
                       "ai.pleos.playground.stt.utils.Log -> a.b:\n"
                       "dev.carplaymate.demo.DeveloperScreenKt -> c.d:\n", encoding="utf-8")
    trees = {"AndroidManifest.xml": manifest()}
    for name in ("backup_rules", "data_extraction_rules"):
        trees[f"res/{name}.xml"] = ET.parse(
            gate.backup.ROOT / f"apps/vehicle/app/src/main/res/xml/{name}.xml").getroot()
    table = """resource 0x7f120001 xml/backup_rules
    () (file) res/backup_rules.xml type=XML
resource 0x7f120002 xml/data_extraction_rules
    () (file) res/data_extraction_rules.xml type=XML
resource 0x7f120003 xml/network_security_config
    () (file) res/network.xml type=XML
    (v35) (file) res/network35.xml type=XML
"""
    monkeypatch.setattr(gate, "verify_signature", lambda *args: gate.validate_signature(signature(), PIN))
    monkeypatch.setattr(gate.backup, "aapt_dump", lambda a, b, kind, file=None: table if kind == "resources" else file)
    monkeypatch.setattr(gate.backup, "parse_xmltree", lambda file: deepcopy(trees[file]))

    def write(types=()):
        with ZipFile(apk, "w") as archive:
            archive.writestr("classes.dex", minimal_dex(types))
            archive.writestr("assets/content/catalog.json", "{}")

    def check():
        return gate.check_apk(apk, mapping, Path("aapt2"), Path("apksigner"), package=PACKAGE,
                              version_name="0.5.0", version_code=6, certificate_sha256=PIN)

    write()
    return write, check, trees


def test_complete_packaging_pipeline_checks_manifest_bound_rules_and_all_dex(packaged):
    _, check, _ = packaged
    result = check()
    assert result["status"] == "passed"
    assert result["backup"]["runtime_backup_or_device_transfer_tested"] is False
    assert result["development_ui_absent"] is True
    assert result["dex_checked"] == ["classes.dex"]


@pytest.mark.parametrize("descriptor", ["La/b;", "Lc/d;",
                                         "Lai/pleos/playground/tts/utils/Log;"])
def test_obfuscated_sdk_logger_and_developer_ui_are_rejected(packaged, descriptor):
    write, check, _ = packaged
    write([descriptor])
    with pytest.raises(gate.PolicyError, match="logger or development UI"):
        check()


def test_single_leaky_network_configuration_fails_complete_pipeline(packaged):
    _, check, trees = packaged
    set_attribute(trees["AndroidManifest.xml"].find("application"), "networkSecurityConfig", "@0x7f120003")
    trees["res/network.xml"] = ET.fromstring('<network-security-config><base-config cleartextTrafficPermitted="false"/></network-security-config>')
    trees["res/network35.xml"] = ET.fromstring('<network-security-config><base-config cleartextTrafficPermitted="true"/></network-security-config>')
    with pytest.raises(gate.PolicyError, match="cleartext"):
        check()


def test_wrong_build_mapping_cannot_hide_obfuscated_logger_names():
    with pytest.raises(gate.PolicyError, match="bound"):
        gate.validate_dex_mapping(minimal_dex(["La/b;"]), "different")


def test_dex_without_release_compiler_marker_is_rejected():
    with pytest.raises(gate.PolicyError, match="bound"):
        gate.validate_dex_mapping(b"DEX without R8 release marker", "123abcd")
