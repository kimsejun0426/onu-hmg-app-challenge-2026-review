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


@pytest.mark.parametrize("types", ["microphone|mediaPlayback", "0x00000082"])
def test_sound_letter_service_has_its_own_private_reviewed_owner(types):
    node = voice_manifest()
    set_attribute(node, "versionName", "0.52.0")
    set_attribute(node, "versionCode", "55")
    set_attribute(ET.SubElement(node, "uses-permission"), "name", gate.LETTER_NOTIFICATION_PERMISSION)
    service = deepcopy(node.find("application/service"))
    set_attribute(service, "name", gate.LETTER_SERVICE)
    set_attribute(service, "foregroundServiceType", types)
    node.find("application").append(service)
    result = gate.validate_manifest(node, PACKAGE, "0.52.0", 55)
    assert result["voice_session_service"]["letter_service"]["exported"] is False


@pytest.mark.parametrize("mutation", ["missing", "duplicate", "exported", "implicit_export",
                                      "intent_filter", "different_process", "isolated", "disabled",
                                      "external", "zygote", "missing_type", "extra_type", "third_service"])
def test_sound_letter_service_cannot_widen_activation_or_lose_required_owner(mutation):
    node = voice_manifest()
    set_attribute(node, "versionName", "0.52.0")
    set_attribute(node, "versionCode", "55")
    set_attribute(ET.SubElement(node, "uses-permission"), "name", gate.LETTER_NOTIFICATION_PERMISSION)
    app = node.find("application")
    service = deepcopy(app.find("service"))
    set_attribute(service, "name", gate.LETTER_SERVICE)
    app.append(service)
    if mutation == "missing":
        app.remove(service)
    elif mutation == "duplicate":
        app.append(deepcopy(service))
    elif mutation == "implicit_export":
        service.attrib.pop(f"{{{gate.ANDROID}}}exported")
    elif mutation == "intent_filter":
        ET.SubElement(service, "intent-filter")
    elif mutation == "missing_type":
        service.attrib.pop(f"{{{gate.ANDROID}}}foregroundServiceType")
    elif mutation == "third_service":
        other = deepcopy(service)
        set_attribute(other, "name", ".UnreviewedThirdService")
        app.append(other)
    else:
        name, value = {
            "exported": ("exported", "true"), "different_process": ("process", ":letter"),
            "isolated": ("isolatedProcess", "true"), "disabled": ("enabled", "false"),
            "external": ("externalService", "true"), "zygote": ("useAppZygote", "true"),
            "extra_type": ("foregroundServiceType", "microphone|mediaPlayback|location"),
        }[mutation]
        set_attribute(service, name, value)
    with pytest.raises(gate.PolicyError, match="LetterSessionService|Unreviewed foreground"):
        gate.validate_manifest(node, PACKAGE, "0.52.0", 55)


@pytest.mark.parametrize("mutation", ["missing", "duplicate", "conditional", "sdk_limit"])
def test_letter_stop_control_requires_an_unambiguous_notification_permission(mutation):
    node = voice_manifest()
    set_attribute(node, "versionName", "0.52.0")
    set_attribute(node, "versionCode", "55")
    app = node.find("application")
    letter = deepcopy(app.find("service"))
    set_attribute(letter, "name", gate.LETTER_SERVICE)
    app.append(letter)
    permission = ET.SubElement(node, "uses-permission")
    set_attribute(permission, "name", gate.LETTER_NOTIFICATION_PERMISSION)
    if mutation == "missing":
        node.remove(permission)
    elif mutation == "duplicate":
        node.append(deepcopy(permission))
    elif mutation == "conditional":
        permission.tag = "uses-permission-sdk-23"
    else:
        set_attribute(permission, "maxSdkVersion", "32")
    with pytest.raises(gate.PolicyError, match="permission"):
        gate.validate_manifest(node, PACKAGE, "0.52.0", 55)


def test_new_letter_permission_does_not_broaden_historical_release_allowlist():
    node = voice_manifest()
    set_attribute(ET.SubElement(node, "uses-permission"), "name", gate.LETTER_NOTIFICATION_PERMISSION)
    with pytest.raises(gate.PolicyError, match="allowlist"):
        check_voice_manifest(node)


def letter_media_manifest(version="0.54.0", code=57, *, browser=True):
    node = voice_manifest()
    set_attribute(node, "versionName", version)
    set_attribute(node, "versionCode", str(code))
    set_attribute(ET.SubElement(node, "uses-permission"), "name", gate.LETTER_NOTIFICATION_PERMISSION)
    app = node.find("application")
    letter = deepcopy(app.find("service"))
    set_attribute(letter, "name", gate.LETTER_SERVICE)
    app.append(letter)
    if browser:
        service = ET.SubElement(app, "service")
        set_attribute(service, "name", gate.LETTER_MEDIA_BROWSER_SERVICE)
        set_attribute(service, "exported", "true")
        set_attribute(service, "enabled", "false")
        set_attribute(service, "permission", gate.LETTER_MEDIA_BROWSER_PERMISSION)
        action = ET.SubElement(ET.SubElement(service, "intent-filter"), "action")
        set_attribute(action, "name", gate.LETTER_MEDIA_BROWSER_ACTION)
    return node


def check_letter_media_manifest(node):
    return gate.validate_manifest(node, PACKAGE, gate.attribute(node, "versionName"),
                                  int(gate.attribute(node, "versionCode")))


def letter_media_service(node):
    return next(service for service in node.findall("application/service")
                if gate.component_name(gate.attribute(service, "name"), PACKAGE) ==
                gate.LETTER_MEDIA_BROWSER_SERVICE)


@pytest.mark.parametrize("name", [".LetterMediaBrowserService", "LetterMediaBrowserService",
                                  gate.LETTER_MEDIA_BROWSER_SERVICE])
def test_reviewed_letter_browser_stays_disabled_and_separate_from_the_private_fgs(name):
    node = letter_media_manifest()
    service = letter_media_service(node)
    set_attribute(service, "name", name)
    set_attribute(service, "enabled", "false")
    set_attribute(service, "process", PACKAGE)
    set_attribute(service, "isolatedProcess", "false")
    set_attribute(service, "externalService", "false")
    set_attribute(service, "useAppZygote", "false")
    result = check_letter_media_manifest(node)
    assert result["letter_media_browser_service"] == {
        "service": gate.LETTER_MEDIA_BROWSER_SERVICE, "exported": True,
        "permission": "android.permission.MEDIA_CONTENT_CONTROL", "enabled": False,
        "process": PACKAGE, "intent_actions": ["android.media.browse.MediaBrowserService"],
        "foreground_service_types": [],
    }
    assert result["voice_session_service"]["letter_service"]["exported"] is False
    assert result["voice_session_service"]["letter_service"]["foreground_service_types"] == [
        "mediaPlayback", "microphone"]
    assert gate.LETTER_MEDIA_BROWSER_PERMISSION not in result["permissions"]
    launcher = node.find("application/activity")
    assert gate.attribute(launcher, "name") == "dev.carplaymate.demo.MainActivity"
    assert [gate.attribute(item, "name") for item in launcher.findall("intent-filter/category")] == [
        "android.intent.category.LAUNCHER"]


@pytest.mark.parametrize("version,code", [("0.52.0", 55), ("0.53.0", 56), ("0.53.99", 56)])
def test_historical_sound_letter_apks_still_pass_without_a_browser(version, code):
    result = check_letter_media_manifest(letter_media_manifest(version, code, browser=False))
    assert result["letter_media_browser_service"] is None


@pytest.mark.parametrize("version", ["0.54.0", "0.54.1", "0.55.0", "1.0.0"])
def test_current_apk_cannot_omit_the_entire_browser_projection(version):
    with pytest.raises(gate.PolicyError, match="Exactly one reviewed LetterMediaBrowserService"):
        check_letter_media_manifest(letter_media_manifest(version, browser=False))


def test_new_browser_exception_does_not_apply_to_historical_versions():
    with pytest.raises(gate.PolicyError, match="from 0.54.0 only"):
        check_letter_media_manifest(letter_media_manifest("0.53.0", 56))


@pytest.mark.parametrize("mutation", ["missing", "renamed", "duplicate", "other_component"])
def test_browser_identity_and_unique_service_are_required(mutation):
    node = letter_media_manifest()
    app = node.find("application")
    service = letter_media_service(node)
    if mutation == "missing":
        app.remove(service)
    elif mutation == "renamed":
        set_attribute(service, "name", ".ArbitraryMediaBrowserService")
    elif mutation == "duplicate":
        other = deepcopy(service)
        set_attribute(other, "name", ".LetterMediaBrowserService")
        app.append(other)
    else:
        service.tag = "receiver"
    with pytest.raises(gate.PolicyError, match="Exactly one reviewed LetterMediaBrowserService"):
        check_letter_media_manifest(node)


@pytest.mark.parametrize("name,value,reason", [
    ("exported", None, "explicitly exported"),
    ("exported", "false", "explicitly exported"),
    ("exported", "@0x7f010001", "explicitly exported"),
    ("permission", None, "MEDIA_CONTENT_CONTROL"),
    ("permission", "", "MEDIA_CONTENT_CONTROL"),
    ("permission", "android.permission.INTERNET", "MEDIA_CONTENT_CONTROL"),
    ("permission", "android.permission.BIND_MEDIA_BROWSER_SERVICE", "MEDIA_CONTENT_CONTROL"),
    ("permission", PACKAGE + ".MEDIA_BROWSER", "MEDIA_CONTENT_CONTROL"),
    ("enabled", None, "explicitly disabled"),
    ("enabled", "true", "explicitly disabled"),
    ("enabled", "@0x7f010001", "explicitly disabled"),
    ("process", ":media", "main process"),
    ("process", "other.package", "main process"),
    ("isolatedProcess", "true", "main process"),
    ("externalService", "true", "main process"),
    ("useAppZygote", "true", "main process"),
    ("foregroundServiceType", "mediaPlayback", "foreground service type"),
    ("foregroundServiceType", "microphone|mediaPlayback", "foreground service type"),
    ("foregroundServiceType", "0x00000000", "foreground service type"),
])
def test_browser_export_does_not_weaken_permission_process_or_fgs_policy(name, value, reason):
    node = letter_media_manifest()
    service = letter_media_service(node)
    if value is None:
        service.attrib.pop(f"{{{gate.ANDROID}}}{name}")
    else:
        set_attribute(service, name, value)
    with pytest.raises(gate.PolicyError, match=reason):
        check_letter_media_manifest(node)


@pytest.mark.parametrize("mutation", ["missing_filter", "second_filter", "filter_priority",
                                      "missing_action", "other_action", "second_action",
                                      "duplicate_action", "category", "data", "metadata",
                                      "action_attribute", "action_child"])
def test_browser_filter_is_exactly_one_legacy_action_without_extra_activation_paths(mutation):
    node = letter_media_manifest()
    service = letter_media_service(node)
    intent = service.find("intent-filter")
    action = intent.find("action")
    if mutation == "missing_filter":
        service.remove(intent)
    elif mutation == "second_filter":
        service.append(deepcopy(intent))
    elif mutation == "filter_priority":
        set_attribute(intent, "priority", "1")
    elif mutation == "missing_action":
        intent.remove(action)
    elif mutation == "other_action":
        set_attribute(action, "name", "androidx.media3.session.MediaLibraryService")
    elif mutation == "second_action":
        set_attribute(ET.SubElement(intent, "action"), "name", "android.intent.action.MEDIA_BUTTON")
    elif mutation == "duplicate_action":
        intent.append(deepcopy(action))
    elif mutation == "category":
        set_attribute(ET.SubElement(intent, "category"), "name", "android.intent.category.DEFAULT")
    elif mutation == "data":
        set_attribute(ET.SubElement(intent, "data"), "scheme", "https")
    elif mutation == "metadata":
        set_attribute(ET.SubElement(service, "meta-data"), "name", "unreviewed.media.path")
    elif mutation == "action_attribute":
        set_attribute(action, "other", "unreviewed")
    else:
        ET.SubElement(action, "category")
    with pytest.raises(gate.PolicyError, match="exactly the legacy MediaBrowserService action"):
        check_letter_media_manifest(node)


@pytest.mark.parametrize("tag,name", [("service", ".ArbitraryMediaBrowserService"),
                                      ("receiver", gate.LETTER_MEDIA_BROWSER_SERVICE),
                                      ("provider", gate.LETTER_MEDIA_BROWSER_SERVICE),
                                      ("activity", gate.LETTER_MEDIA_BROWSER_SERVICE)])
def test_reviewed_browser_cannot_whitelist_other_exported_components(tag, name):
    node = letter_media_manifest()
    other = deepcopy(letter_media_service(node))
    other.tag = tag
    set_attribute(other, "name", name)
    node.find("application").append(other)
    with pytest.raises(gate.PolicyError, match="Unreviewed exported component"):
        check_letter_media_manifest(node)


@pytest.mark.parametrize("permission", ["android.permission.READ_CONTACTS",
                                        "android.permission.ACCESS_FINE_LOCATION",
                                        gate.LETTER_MEDIA_BROWSER_PERMISSION])
def test_browser_does_not_add_requested_permissions(permission):
    node = letter_media_manifest()
    set_attribute(ET.SubElement(node, "uses-permission"), "name", permission)
    with pytest.raises(gate.PolicyError, match="allowlist"):
        check_letter_media_manifest(node)


@pytest.mark.parametrize("permission", [PACKAGE + ".MEDIA_BROWSER", gate.LETTER_MEDIA_BROWSER_PERMISSION])
def test_browser_does_not_authorize_custom_or_shadowed_platform_permissions(permission):
    node = letter_media_manifest()
    declaration = ET.SubElement(node, "permission")
    set_attribute(declaration, "name", permission)
    set_attribute(declaration, "protectionLevel", "signature")
    with pytest.raises(gate.PolicyError, match="Unexpected or unprotected application permission"):
        check_letter_media_manifest(node)


def test_browser_is_not_a_substitute_for_the_existing_launcher():
    node = letter_media_manifest()
    app = node.find("application")
    app.remove(app.find("activity"))
    with pytest.raises(gate.PolicyError, match="Exactly one reviewed launcher"):
        check_letter_media_manifest(node)
