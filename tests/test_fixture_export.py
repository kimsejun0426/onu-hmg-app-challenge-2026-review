"""Exporter coverage checks run in memory and never regenerate committed assets."""

import json
from pathlib import Path

import asyncio

import pytest

from carplay.catalog import ContentCatalog
from tests.content_release_expectations import NEW_PACK_ID
from tools.export_android_fixtures import catalog_game_fixtures, game_fixtures, service_fixtures

CATALOG = ContentCatalog()


@pytest.mark.parametrize("entry", CATALOG.legacy_entries, ids=lambda e: e["pack_id"])
def test_compact_legacy_fixture_preserves_endings_both_players_and_all_scene_operations(entry):
    pack = CATALOG.load(entry["pack_id"], entry["version"])
    fixture = catalog_game_fixtures(pack, CATALOG.source(entry), representative_paths=True)
    endings = {scene["ending_id"] for scene in pack["scenes"].values() if scene["kind"] == "ending"}
    assert len(fixture["paths"]) == 2 * len(endings) == 4
    assert {(len(path["players"]), path["final_state"]["ending"]) for path in fixture["paths"]} == {
        (count, ending) for count in (1, 2) for ending in endings}
    assert fixture["coverage"]["enumerated_paths"] == (32 if entry["pack_id"] == NEW_PACK_ID else 128)
    assert fixture["coverage"]["paths"] == "one_per_player_count_and_ending"
    assert fixture["pack_version"] == entry["version"]
    for count in (1, 2):
        assert {case["state"]["scene_id"] for case in fixture["operations"]
                if len(case["state"]["players"]) == count} == set(pack["scenes"])
    assert any(case.get("expected_error", {}).get("code") == "wrong_player" for case in fixture["operations"])
    assert any(case["kind"] == "apply_hint" and "expected_state" in case for case in fixture["operations"])


def test_current_oracle_includes_abandoned_corrections_multiple_owners_and_quiet_controls():
    fixture = game_fixtures(CATALOG.current())
    assert len(fixture["paths"]) == 128
    assert fixture["pack_version"] == CATALOG.current()["version"]
    cases = {case["text"]: case["expected_candidate"] for case in fixture["interpretations"]
             if len(case["players"]) == 2 and not case["state"]["finished"]}
    for text in ("민준, 물가 말고", "민준, 아니"):
        assert cases[text]["reason"] == "repair_requested"
    for text in ("민준, 물가 서윤 말고 숲", "민준, 서윤 물가 말고 숲"):
        assert cases[text]["reason"] == "multiple_players"
    for text in ("하기 싫어", "안 할래", "안 하고 싶어", "그만 하고 싶어"):
        assert cases[text]["intent"] == "PAUSE"
    assert cases["몰라"]["intent"] == cases["잘 몰라"]["intent"] == "HINT"


@pytest.mark.parametrize("version", ["0.6.0", "0.7.0", "0.8.0", "0.9.0", CATALOG.current("animal-footprint-garden")["version"]])
def test_animal_watch_fixture_keeps_current_and_historical_wait_and_attribution_boundaries(version):
    entry = next(e for e in CATALOG.all_entries if
                 (e["pack_id"], e["version"]) == ("animal-footprint-garden", version))
    fixture = catalog_game_fixtures(CATALOG.load(entry["pack_id"], version), CATALOG.source(entry),
                                    representative_paths=entry in CATALOG.legacy_entries)
    cases = {case["id"]: case for case in fixture["interpretations"] if case["id"].startswith("watch-")}
    assert len(cases) == 82, "A prose-only edition must not silently lose observation/wait boundary cases"
    for count in (1, 2):
        for route in ("POND", "FLOWER_GARDEN"):
            prefix = f"watch-{count}-{route}-"
            group = {key.removeprefix(prefix): case for key, case in cases.items() if key.startswith(prefix)}
            assert len(group) == (19 if count == 1 else 22)
            for case in group.values():
                assert case["state"]["pack_version"] == version
                assert case["state"]["scene_id"] == "finish_watch"
                assert len(case["state"]["history"]) == 5
                assert len(case["players"]) == count
            # Hand-checked meaning: observing is a story choice, whereas asking
            # for time never commits the final story action.
            for key in ("request", "joined-request", "new-label"):
                candidate = group[key]["expected_candidate"]
                if version == "0.6.0":
                    assert candidate["action_id"] is None and candidate["needs_clarification"]
                else:
                    assert candidate["intent"] == "ACTION" and candidate["action_id"] == "WAIT_A_LITTLE"
                    assert not candidate["needs_clarification"]
            for key in ("old-label", "old-short", "old-action"):
                assert group[key]["expected_candidate"]["action_id"] == ("WAIT_A_LITTLE" if version == "0.6.0" else None)
            for key, intent in [("wait", "WAIT"), ("refusal", "PAUSE"), ("repeat", "REPEAT"), ("help", "HINT")]:
                candidate = group[key]["expected_candidate"]
                assert candidate["intent"] == intent and candidate["action_id"] is None
            if version != "0.6.0":
                for key in ("competing", "negative", "unfinished", "control-suffix", "quotation", "unclear", "empty"):
                    candidate = group[key]["expected_candidate"]
                    assert candidate["action_id"] is None and candidate["needs_clarification"]
                assert group["correction"]["expected_candidate"]["action_id"] == "SHARE_NOTE"
                assert group["reverse-correction"]["expected_candidate"]["action_id"] == "WAIT_A_LITTLE"
                if count == 2:
                    for key, reason in [("missing-owner", "missing_player"), ("wrong-owner", "wrong_player"),
                                        ("mixed-owners", "multiple_players")]:
                        candidate = group[key]["expected_candidate"]
                        assert candidate["reason"] == reason and candidate["needs_clarification"]
                        # Attribution can retain a recognized action as pending;
                        # mixed speakers cannot produce an actionable candidate.
                        assert candidate["action_id"] == (None if key == "mixed-owners" else "WAIT_A_LITTLE")


@pytest.mark.parametrize("version", ["0.8.0", "0.9.0"])
@pytest.mark.parametrize("pack_id,request_id,action,other", [
    ("animal-footprint-garden", "animal-note", "MOVEMENT", "REST"),
    ("little-car-workshop", "car-cargo", "PLANTS", "BOOKS"),
    ("space-return-signal", "space-checklist", "READ_CHECKLIST", "COUNT_STEPS"),
])
def test_archiving_narration_keeps_published_request_and_refusal_cases(version, pack_id, request_id, action, other):
    entry = next(e for e in CATALOG.all_entries if (e["pack_id"], e["version"]) == (pack_id, version))
    fixture = catalog_game_fixtures(CATALOG.load(pack_id, version), CATALOG.source(entry),
                                    representative_paths=entry in CATALOG.legacy_entries)
    cases = {case["id"]: case for case in fixture["interpretations"]
             if case["id"].startswith(f"request-{request_id}-")}
    assert len(cases) == 33, "Archiving a published current edition must not drop its request boundaries"
    for count in (1, 2):
        def candidate(key):
            case = cases[f"request-{request_id}-{count}-{key}"]
            assert len(case["players"]) == count and case["state"]["pack_version"] == version
            return case["expected_candidate"]

        for key, expected in [("exact", action), ("joined", action), ("other", other), ("correction", other)]:
            result = candidate(key)
            assert result["intent"] == "ACTION" and result["action_id"] == expected
            assert not result["needs_clarification"]
        for key in ("competing", "negative", "unfinished-correction", "quotation", "duplicate"):
            result = candidate(key)
            assert result["action_id"] is None and result["needs_clarification"]
        for key, intent in [("repeat-control", "REPEAT"), ("pause-control", "PAUSE"), ("wait-control", "WAIT")]:
            assert candidate(key)["intent"] == intent and candidate(key)["action_id"] is None
        if count == 2:
            assert candidate("unnamed")["reason"] == "missing_player"
            assert candidate("wrong-owner")["reason"] == "wrong_player"
            assert candidate("mixed-names")["reason"] == "multiple_players"


@pytest.mark.parametrize("pack_id,previous,current,request_id,action,other", [
    ("animal-footprint-garden", "0.9.0", "0.10.0", "animal-draw-request", "DRAW_TOGETHER", "SAY_ONE"),
    ("onu-little-picnic", "0.3.0", "0.4.0", "picnic-keep-request", "KEEP_SCENE", "FAREWELL"),
])
def test_question_request_fixtures_keep_new_acceptance_and_delivered_refusal(pack_id, previous, current, request_id, action, other):
    for version in (previous, current):
        entry = next(e for e in CATALOG.all_entries if (e["pack_id"], e["version"]) == (pack_id, version))
        fixture = catalog_game_fixtures(CATALOG.load(pack_id, version), CATALOG.source(entry),
                                        representative_paths=version == previous)
        cases = {case["id"]: case for case in fixture["interpretations"]
                 if case["id"].startswith(f"request-{request_id}-")}
        assert len(cases) == 33
        for count in (1, 2):
            def candidate(key):
                case = cases[f"request-{request_id}-{count}-{key}"]
                assert len(case["players"]) == count and case["state"]["pack_version"] == version
                assert len(case["state"]["history"]) == 3
                return case["expected_candidate"]

            for key in ("exact", "joined"):
                result = candidate(key)
                assert result["action_id"] == (action if version == current else None)
                assert result["needs_clarification"] is (version == previous)
            for key in ("competing", "negative", "unfinished-correction", "quotation", "duplicate",
                        "repeat-suffix", "pause-suffix"):
                assert candidate(key)["action_id"] is None and candidate(key)["needs_clarification"]
            for key in ("other", "correction"):
                assert candidate(key)["action_id"] == other and not candidate(key)["needs_clarification"]
            if count == 2 and version == current:
                assert candidate("unnamed")["reason"] == "missing_player"
                assert candidate("wrong-owner")["reason"] == "wrong_player"
                assert candidate("mixed-names")["reason"] == "multiple_players"


def test_service_oracle_never_reuses_an_abandoned_pending_choice():
    fixture = asyncio.run(service_fixtures(CATALOG.current()))
    scenarios = {case["id"]: case for case in fixture["scenarios"]}
    assert set(scenarios) == {
        "ending-1", "ending-2", "name-repair", "quiet-resume-duplicate", "controls-and-objection",
        "unfinished-correction-clears-pending", "help-clears-pending", "wait-preserves-pending",
        "pause-clears-pending", "recovery-budget", "recovery-helpers-budget", "reason-choice",
        "completed-feedback-resume", "ending-resume",
        "address-pending-guard", "address-quiet-resume", "address-recovery-budget", "address-only-budget",
    }
    for name in ("unfinished-correction-clears-pending", "help-clears-pending", "pause-clears-pending"):
        steps = scenarios[name]["steps"]
        name_only = next(step for step in steps if step["request"]["payload"].get("text") == "민준")
        assert name_only["expected_current_snapshot"]["game"]["history"] == []
        final = steps[-1]["expected_current_snapshot"]["game"]
        assert [choice["action_id"] for choice in final["history"]] == ["FOREST"]

    waiting = scenarios["wait-preserves-pending"]["steps"]
    for step in waiting:
        if step["request"]["payload"].get("text") == "생각 중이야":
            state = step["expected_current_snapshot"]
            assert state["presentation"]["input_kind"] == "player_name"
            assert state["game"]["history"] == []
    assert [choice["action_id"] for choice in waiting[-1]["expected_current_snapshot"]["game"]["history"]] == ["RIVER"]


def test_service_oracle_distinguishes_delivered_feedback_from_unfinished_ending():
    scenarios = {case["id"]: case for case in asyncio.run(service_fixtures(CATALOG.current()))["scenarios"]}
    for name in ("completed-feedback-resume", "ending-resume"):
        steps = scenarios[name]["steps"]
        pause_index = next(i for i, step in enumerate(steps) if step["request"]["type"] == "session.pause")
        before = steps[pause_index - 1]["expected_current_snapshot"]
        resumes = [step for step in steps if step["request"]["type"] == "session.resume"]
        resumed = resumes[0]["expected_current_snapshot"]
        assert before["game"] == resumed["game"]
        texts = [segment["text"] for segment in resumed["speech_plan"]["segments"]]
        if name == "completed-feedback-resume":
            assert before["speech_plan"] is None
            assert texts == [before["presentation"]["prompt"]]
            assert resumes[0]["expected_response"] == resumes[1]["expected_response"]
            assert len(steps[-1]["expected_current_snapshot"]["game"]["history"]) == 2
        else:
            assert texts == [segment["text"] for segment in before["speech_plan"]["segments"]]
            assert resumed["speech_plan"]["playback_id"] != before["speech_plan"]["playback_id"]
            assert resumed["status"] == "ACTIVE"
            assert steps[-1]["expected_current_snapshot"]["status"] == "ENDED"
        stale_index = next(i for i, step in enumerate(steps)
                           if step.get("expected_error", {}).get("code") == "STALE_PLAYBACK")
        assert steps[stale_index]["expected_current_snapshot"] == steps[stale_index - 1]["expected_current_snapshot"]


def test_service_oracle_covers_bounded_recovery_and_reason_choice():
    scenarios = {case["id"]: case for case in asyncio.run(service_fixtures(CATALOG.current()))["scenarios"]}
    for name in ["recovery-budget", "recovery-helpers-budget"]:
        states = [step["expected_current_snapshot"] for step in scenarios[name]["steps"]]
        paused = next(state for state in states if state["status"] == "PAUSED")
        assert paused["pause_reason"] == "NO_RESPONSE"
        assert paused["speech_plan"] is None and paused["listen_window_id"] is None
        assert paused["requires_explicit_resume"]
        assert len(states[-1]["game"]["history"]) == 1
    last = scenarios["reason-choice"]["steps"][-1]["expected_current_snapshot"]
    assert [choice["action_id"] for choice in last["game"]["history"]] == ["FOREST", "READ_LABEL"]


@pytest.mark.parametrize("entry", CATALOG.current_entries, ids=lambda e: e["pack_id"])
def test_each_current_story_has_reason_and_refusal_parity_cases(entry):
    fixture = catalog_game_fixtures(CATALOG.load(entry["pack_id"], entry["version"]), CATALOG.source(entry))
    reasons = [case for case in fixture["interpretations"] if case["id"].startswith("short-reason-")]
    assert reasons
    assert all(not case["expected_candidate"]["needs_clarification"] for case in reasons)
    refused = [case for case in fixture["interpretations"] if case["id"].startswith(("reason-other-option-", "reason-negation-"))]
    assert refused
    assert all(case["expected_candidate"]["needs_clarification"] for case in refused)


@pytest.mark.parametrize("entry", [*CATALOG.current_entries, *CATALOG.legacy_entries],
                         ids=lambda entry: f"{entry['pack_id']}@{entry['version']}")
def test_every_edition_exports_vocative_choices_and_pending_refusal_with_hand_checked_meaning(entry):
    pack = CATALOG.load(entry["pack_id"], entry["version"])
    fixture = catalog_game_fixtures(pack, CATALOG.source(entry), representative_paths=True)
    cases = {case["id"]: case["expected_candidate"] for case in fixture["interpretations"]
             if case["id"].startswith("onu-address-")}
    assert len(cases) == 110
    action = pack["scenes"][pack["start_scene"]]["actions"][0]["id"]
    for count in (1, 2):
        for suffix in ("named", "prefix", "correction", "wide-boundary"):
            actual = cases[f"onu-address-{count}-{suffix}"]
            assert actual["intent"] == "ACTION" and actual["action_id"] == action
            assert actual["declared_player_id"] == "p1" and not actual["needs_clarification"]
        for suffix in ("bare", "named-bare", "name-only", "repeated-name"):
            actual = cases[f"onu-address-{count}-{suffix}"]
            assert actual["reason"] == "address_only" and actual["action_id"] is None
        for suffix in ("duplicate", "duplicate-correction", "reported-correction", "unknown", "quoted-correction", "quoted-name",
                       *(f"quoted-choice-{index}" for index in range(14))):
            actual = cases[f"onu-address-{count}-{suffix}"]
            assert actual["reason"] == "unconfirmed_expansion" and actual["action_id"] is None


def test_address_service_fixtures_preserve_quiet_pending_and_separate_helper_budget():
    scenarios = {case["id"]: case for case in asyncio.run(service_fixtures(CATALOG.current()))["scenarios"]}
    for name in ("address-pending-guard", "address-quiet-resume", "address-recovery-budget", "address-only-budget"):
        snapshots = [step["expected_current_snapshot"] for step in scenarios[name]["steps"]]
        paused = next(state for state in snapshots if state["status"] == "PAUSED")
        assert paused["game"]["history"] == []
        assert paused["speech_plan"] is None and paused["listen_window_id"] is None
        assert paused["requires_explicit_resume"]
        assert paused["pause_reason"] == ("QUIET_REQUEST" if name == "address-quiet-resume" else "NO_RESPONSE")
        if name in {"address-pending-guard", "address-quiet-resume"}:
            assert [choice["action_id"] for choice in snapshots[-1]["game"]["history"]] == ["FOREST"]
    only = scenarios["address-only-budget"]["steps"]
    response = next(step["expected_current_snapshot"] for step in only if step["request"]["payload"].get("text") == "오누야")
    assert [segment["text"] for segment in response["speech_plan"]["segments"]] == [
        "응, 여기 있어.", response["presentation"]["prompt"]]


@pytest.mark.parametrize("version", ["0.7.0", "0.8.0"])
def test_question_authored_dinosaur_answers_and_refusals_are_in_both_edition_oracles(version):
    pack = CATALOG.load("dinosaur-lab-missing-egg", version)
    source = CATALOG.source(next(e for e in CATALOG.all_entries if
                                (e["pack_id"], e["version"]) == (pack["pack_id"], version)))
    fixture = game_fixtures(pack) if version == "0.8.0" else catalog_game_fixtures(pack, source, representative_paths=True)
    cases = {c["id"]: c for c in fixture["interpretations"] if c["id"].startswith("question-answer-")}
    assert len(cases) == 8 * (12 + 15)
    for count in (1, 2):
        for case_id, action in [("find-river", "RIVER"), ("river-label", "READ_LABEL"),
                                ("forest-follow", "FOLLOW_TRACKS"), ("uncertain-fish", "FISH"),
                                ("use-listening-device", "LISTENING_DEVICE")]:
            prefix = f"question-answer-{count}-{case_id}-"
            candidate = cases[prefix + "named"]["expected_candidate"]
            assert candidate["action_id"] == (action if version == "0.8.0" else None)
            for suffix in ("negative", "both", "duplicate", "reported", "unfinished", "quoted-address"):
                candidate = cases[prefix + suffix]["expected_candidate"]
                assert candidate["action_id"] is None and candidate["needs_clarification"]


QUESTION_HINT_FIXTURE = json.loads((Path(__file__).parent / "fixtures/question_hint_answers.json").read_text(encoding="utf-8"))


@pytest.mark.parametrize("edition", QUESTION_HINT_FIXTURE["editions"], ids=lambda e: e["pack_id"])
@pytest.mark.parametrize("historical", [False, True])
def test_question_hint_oracles_keep_new_answers_and_exact_previous_refusals(edition, historical):
    version = edition["prior_version" if historical else "current_version"]
    entry = next(e for e in CATALOG.all_entries if (e["pack_id"], e["version"]) == (edition["pack_id"], version))
    fixture = catalog_game_fixtures(CATALOG.load(edition["pack_id"], version), CATALOG.source(entry),
                                    representative_paths=historical)
    cases = {c["id"]: c["expected_candidate"] for c in fixture["interpretations"] if c["id"].startswith("question-hint-")}
    questions = [c for c in QUESTION_HINT_FIXTURE["cases"] if c["pack_id"] == edition["pack_id"]]
    assert len(cases) == len(questions) * (15 + 18)
    for count in (1, 2):
        for question in questions:
            prefix = f"question-hint-{count}-{question['id']}-"
            for suffix in ["named", "address-first", "address-after-name"]:
                candidate = cases[prefix + suffix]
                assert candidate["action_id"] == (None if historical else question["action"])
                assert candidate["needs_clarification"] == historical
            for suffix in ["negative", "both", "duplicate", "reported", "unfinished", "quoted-address", "yes", "no", "either"]:
                candidate = cases[prefix + suffix]
                assert candidate["action_id"] is None and candidate["needs_clarification"]
            if not historical:
                assert cases[prefix + "addressed-correction"]["action_id"] == question["other_action"]
