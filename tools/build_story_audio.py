#!/usr/bin/env python3
"""Build exact-text Korean story clips from existing local Supertonic presets.

No download, reference recording, voice cloning, microphone, or playback path exists.
Package/model locations are explicit CLI inputs; generated intermediates stay on D.
Run --compare first, then --voice F2 or M1. --asr-only is a separate local check.
"""

from __future__ import annotations

import argparse
import hashlib
import importlib.metadata
import json
import math
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import sys
import time
import unicodedata
import wave
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[1]
FIELDS = frozenset({"prompt", "hint", "feedback"})
MODEL_REVISION = "724fb5abbf5502583fb520898d45929e62f02c0b"


def sha(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def write_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


def collect(episode: dict) -> tuple[list[dict], list[dict]]:
    unique: dict[str, dict] = {}
    excluded = []

    def visit(value: object, path: str) -> None:
        if isinstance(value, dict):
            for key, child in value.items():
                location = f"{path}/{key}"
                if key in FIELDS and isinstance(child, str):
                    if not child.strip() or "{" in child or "}" in child:
                        excluded.append({"source": location, "text": child,
                                         "reason": "empty" if not child.strip() else "template"})
                    else:
                        digest = hashlib.sha256(child.encode("utf-8")).hexdigest()
                        row = unique.setdefault(child, {
                            "id": "dino_" + digest[:16], "text": child,
                            "text_sha256": digest, "sources": [],
                        })
                        row["sources"].append(location)
                visit(child, location)
        elif isinstance(value, list):
            for index, child in enumerate(value):
                visit(child, f"{path}/{index}")

    visit(episode["scenes"], "/scenes")
    return list(unique.values()), excluded


def prepare_local_runtime(work: Path, package: Path, model: Path) -> None:
    if work.drive.lower() != "d:":
        raise ValueError("Use an explicit D: work directory for this project.")
    work.mkdir(parents=True, exist_ok=True)
    temp = work / "tmp"
    temp.mkdir(exist_ok=True)
    os.environ.update({
        "TMP": str(temp), "TEMP": str(temp), "TMPDIR": str(temp),
        "SUPERTONIC_CACHE_DIR": str(model), "HF_HOME": str(work / "hf-unused"),
        "HF_HUB_OFFLINE": "1", "TRANSFORMERS_OFFLINE": "1",
        "HF_HUB_DISABLE_IMPLICIT_TOKEN": "1", "HF_HUB_DISABLE_TELEMETRY": "1",
        "HF_HUB_DISABLE_XET": "1", "DO_NOT_TRACK": "1",
        "PYTHONDONTWRITEBYTECODE": "1", "TOKENIZERS_PARALLELISM": "false",
    })
    sys.dont_write_bytecode = True
    sys.path.insert(0, str(package))

    def reject_network(*_args: object, **_kwargs: object) -> None:
        raise RuntimeError("Network access is disabled in this offline audio builder.")

    socket.create_connection = reject_network
    socket.socket.connect = reject_network
    socket.socket.connect_ex = reject_network


def ffmpeg_json(ffmpeg: Path, path: Path, audio_filter: str, destination: Path | None = None) -> dict:
    command = [str(ffmpeg), "-nostdin", "-hide_banner", "-y", "-i", str(path),
               "-af", audio_filter]
    if destination is None:
        command += ["-f", "null", "-"]
    else:
        command += ["-ar", "24000", "-ac", "1", "-c:a", "pcm_s16le", str(destination)]
    run = subprocess.run(command, capture_output=True, text=True, check=True)
    blocks = re.findall(r"\{[^{}]*\}", run.stderr, re.DOTALL)
    if not blocks:
        raise RuntimeError("ffmpeg did not report loudness measurements.")
    return json.loads(blocks[-1])


def wav_metrics(path: Path, allowed_rates: tuple[int, ...] = (24000,)) -> dict:
    import numpy as np

    with wave.open(str(path), "rb") as reader:
        channels, width, rate, frames = (reader.getnchannels(), reader.getsampwidth(),
                                         reader.getframerate(), reader.getnframes())
        pcm = np.frombuffer(reader.readframes(frames), dtype="<i2").astype(np.float64) / 32768
    if channels != 1 or width != 2 or rate not in allowed_rates or not len(pcm):
        raise ValueError(f"Expected a nonempty mono PCM16 WAV at one of {allowed_rates} Hz.")
    peak = float(np.max(np.abs(pcm)))
    peak_db = 20 * math.log10(peak) if peak else -999.0
    duration = round(frames / rate * 1000)
    if duration > 60000 or duration <= 0 or peak_db > -3.0:
        raise ValueError(f"Invalid clip duration/peak: {path.name}: {duration}ms, {peak_db}dBFS")
    return {"duration_ms": duration, "sample_rate": rate, "channels": channels,
            "sample_width_bytes": width, "sample_peak_dbfs": round(peak_db, 4)}


def normalize_audio(ffmpeg: Path, raw: Path, output: Path) -> dict:
    measurement = ffmpeg_json(ffmpeg, raw, "loudnorm=I=-18:TP=-3.2:LRA=20:print_format=json")
    loudness, peak = float(measurement["input_i"]), float(measurement["input_tp"])
    if not math.isfinite(loudness) or not math.isfinite(peak):
        raise ValueError("Cannot normalize silent or unmeasurable audio.")
    # Preserve linear gain; reduce the loudness target when the crest factor needs it.
    target_i = min(-18.0, loudness + (-3.25 - peak))
    target_lra = min(50.0, max(7.0, float(measurement["input_lra"]) + 0.1))
    audio_filter = (
        f"loudnorm=I={target_i:.3f}:TP=-3.2:LRA={target_lra:.2f}:"
        f"measured_I={measurement['input_i']}:measured_TP={measurement['input_tp']}:"
        f"measured_LRA={measurement['input_lra']}:measured_thresh={measurement['input_thresh']}:"
        "linear=true:print_format=json"
    )
    output.parent.mkdir(parents=True, exist_ok=True)
    normalized = ffmpeg_json(ffmpeg, raw, audio_filter, output)
    if normalized.get("normalization_type") != "linear":
        raise ValueError("Expected two-pass linear loudness normalization.")
    actual = ffmpeg_json(ffmpeg, output, "loudnorm=I=-18:TP=-3:LRA=20:print_format=json")
    if float(actual["input_tp"]) > -3.0:
        raise ValueError("Final true peak exceeds -3 dBTP.")
    return {"method": "ffmpeg two-pass linear loudnorm", "requested_lufs": -18,
            "selected_target_lufs": target_i, "target_true_peak_dbtp": -3.2,
            "final_integrated_lufs": float(actual["input_i"]),
            "final_true_peak_dbtp": float(actual["input_tp"]),
            "first_pass": measurement, "second_pass": normalized, **wav_metrics(output)}


def read_pcm(path: Path) -> tuple[object, int]:
    import numpy as np

    with wave.open(str(path), "rb") as reader:
        if reader.getnchannels() != 1 or reader.getsampwidth() != 2:
            raise ValueError("Offline mix inputs must be mono PCM16.")
        rate = reader.getframerate()
        samples = np.frombuffer(reader.readframes(reader.getnframes()), dtype="<i2")
    return samples.astype(np.float64) / 32768, rate


def write_pcm(path: Path, samples: object, rate: int) -> None:
    import numpy as np

    values = np.asarray(samples)
    if not np.all(np.isfinite(values)) or np.max(np.abs(values)) > 1:
        raise ValueError("Nonfinite or clipping audio in offline mix.")
    path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), "wb") as writer:
        writer.setnchannels(1)
        writer.setsampwidth(2)
        writer.setframerate(rate)
        writer.writeframes(np.round(values * 32767).astype("<i2").tobytes())


def load_delivery(args: argparse.Namespace, rows: list[dict]) -> dict[str, dict]:
    if not args.delivery_plan:
        return {}
    plan = json.loads(args.delivery_plan.read_text(encoding="utf-8"))
    if plan.get("schema_version") != 1 or plan.get("voice") != args.voice:
        raise ValueError("Delivery plan version or voice differs from this build.")
    if (not args.baseline_manifest or
            sha(args.baseline_manifest) != plan.get("baseline_manifest_sha256")):
        raise ValueError("The pinned baseline manifest differs from the delivery plan.")
    entries = {entry["id"]: entry for entry in plan["clips"]}
    if len(entries) != len(plan["clips"]):
        raise ValueError("Duplicate delivery IDs.")
    for row in rows:
        entry = entries.get(row["id"])
        if not entry or entry["text_sha256"] != row["text_sha256"]:
            raise ValueError("Missing or stale delivery entry.")
        if entry["mode"] == "preserve_baseline":
            continue
        if entry["mode"] != "render" or not entry.get("segments"):
            raise ValueError("Invalid delivery mode.")
        if " ".join(part["text"] for part in entry["segments"]) != row["text"]:
            raise ValueError("Authored delivery must reconstruct the exact original text.")
        for index, part in enumerate(entry["segments"]):
            if not .96 <= part["speed"] <= 1.04 or not 0 <= part["pause_after_ms"] <= 500:
                raise ValueError("Delivery speed or pause is outside the subtle production range.")
            if index == len(entry["segments"]) - 1 and part["pause_after_ms"] != 0:
                raise ValueError("Do not add a trailing pause after the utterance.")
    return entries


def render_delivery(tts: object, style: object, row: dict, entry: dict, seed: int,
                    args: argparse.Namespace, raw: Path) -> list[dict]:
    """Synthesize exact sentences and set bounded gaps without changing their words."""
    import numpy as np

    assembled = []
    segments = []
    cursor = 0
    rate = tts.sample_rate
    quiet_threshold = 10 ** (-65 / 20)
    for index, part in enumerate(entry["segments"]):
        segment_seed = seed + index * 1009
        np.random.seed(segment_seed)
        generated, _duration = tts.synthesize(
            part["text"], voice_style=style, total_steps=args.steps, speed=part["speed"],
            lang="ko", silence_duration=0.0,
        )
        signal = np.asarray(generated).reshape(-1)
        active = np.flatnonzero(np.abs(signal) > quiet_threshold)
        if not len(active):
            raise ValueError("Silent delivery segment.")
        # Retain margins around all samples above -65 dBFS; only quiet edges are removed.
        head_ms = 120 if index == 0 else 40
        tail_ms = 160 if index == len(entry["segments"]) - 1 else 40
        begin = max(0, int(active[0]) - round(rate * head_ms / 1000))
        end = min(len(signal), int(active[-1]) + 1 + round(rate * tail_ms / 1000))
        clipped = signal[begin:end]
        actual_head = int(active[0]) - begin
        actual_tail = end - int(active[-1]) - 1
        if index:
            previous = segments[-1]
            desired_gap = round(previous["pause_after_ms"] * rate / 1000)
            gap_frames = max(0, desired_gap - previous["trailing_margin_frames"] - actual_head)
            assembled.append(np.zeros(gap_frames, dtype=clipped.dtype))
            cursor += gap_frames
            previous["actual_join_quiet_ms"] = round(
                (gap_frames + previous["trailing_margin_frames"] + actual_head) / rate * 1000, 3)
        record = {**part, "seed": segment_seed, "start_frame": cursor,
                  "end_frame": cursor + len(clipped), "sample_rate": rate,
                  "raw_frames": len(signal), "removed_leading_frames": begin,
                  "removed_trailing_frames": len(signal) - end,
                  "leading_margin_frames": actual_head, "trailing_margin_frames": actual_tail,
                  "quiet_trim_threshold_dbfs": -65}
        segments.append(record)
        assembled.append(clipped)
        cursor += len(clipped)
    tts.save_audio(np.concatenate(assembled)[None, :], str(raw))
    return segments


def mix_underlay(args: argparse.Namespace, entry: dict, segments: list[dict],
                 dry: Path, output: Path) -> dict:
    import numpy as np

    spec = entry["underlay"]
    if not args.ambience_dir or not segments:
        raise ValueError("Underlay needs local authored ambience and sentence timing.")
    source = (args.ambience_dir / spec["asset"]).resolve()
    if not source.is_relative_to(args.ambience_dir) or source.suffix != ".wav":
        raise ValueError("Invalid local ambience path.")
    ambience_manifest = json.loads((args.ambience_dir / "manifest.json").read_text(encoding="utf-8"))
    source_entry = next(item for item in ambience_manifest["cues"]
                        if Path(item["asset"]).name == source.name)
    if sha(source) != source_entry["sha256"]:
        raise ValueError("Ambience source hash differs.")
    voice, rate = read_pcm(dry)
    texture, texture_rate = read_pcm(source)
    if rate != 24000 or texture_rate != rate or not -36 <= spec["relative_rms_db"] <= -24:
        raise ValueError("Invalid ambience format or mix level.")
    first_end = round(segments[0]["end_frame"] / segments[0]["sample_rate"] * rate)
    start = round(.12 * rate)
    count = min(round(spec["max_duration_ms"] * rate / 1000), len(texture),
                first_end - start, len(voice) - start)
    if count <= 0:
        raise ValueError("No observation interval available for ambience.")
    audible_voice = voice[start:start + count]
    speech_active = audible_voice[np.abs(audible_voice) > 10 ** (-40 / 20)]
    if not len(speech_active):
        raise ValueError("No voiced interval for relative mixing.")
    speech_rms = float(np.sqrt(np.mean(speech_active ** 2)))
    bed = texture[:count].copy()
    fade = min(count // 2, round(spec["fade_ms"] * rate / 1000))
    bed[:fade] *= np.sin(np.linspace(0, np.pi / 2, fade)) ** 2
    bed[-fade:] *= np.cos(np.linspace(0, np.pi / 2, fade)) ** 2
    bed_rms = float(np.sqrt(np.mean(bed ** 2)))
    if bed_rms <= 0:
        raise ValueError("Silent ambience source.")
    bed *= speech_rms * 10 ** (spec["relative_rms_db"] / 20) / bed_rms
    mixed = voice.copy()
    mixed[start:start + count] += bed
    gain = min(1.0, 10 ** (-3.25 / 20) / max(float(np.max(np.abs(mixed))), 1e-12))
    mixed *= gain
    write_pcm(output, mixed, rate)
    measured = ffmpeg_json(args.ffmpeg, output, "loudnorm=I=-18:TP=-3:LRA=20:print_format=json")
    if float(measured["input_tp"]) > -3:
        raise ValueError("Offline mix exceeds the true-peak ceiling.")
    return {"source_asset": source.name, "source_sha256": sha(source),
            "source_manifest_sha256": sha(args.ambience_dir / "manifest.json"),
            "start_ms": round(start / rate * 1000), "end_ms": round((start + count) / rate * 1000),
            "first_sentence_end_ms": round(first_end / rate * 1000),
            "relative_active_speech_rms_db": spec["relative_rms_db"],
            "ambience_rms_dbfs": round(20 * math.log10(float(np.sqrt(np.mean(bed ** 2)))), 3),
            "fade_ms": spec["fade_ms"], "global_peak_gain": gain,
            "no_loop": True, "same_frame_count_as_dry": len(mixed) == len(voice),
            "ambience_fully_ends_before_second_sentence": start + count <= first_end,
            "masking_perceptually_verified": False,
            "final_integrated_lufs": float(measured["input_i"]),
            "final_true_peak_dbtp": float(measured["input_tp"]), **wav_metrics(output)}


def synthesize(args: argparse.Namespace, rows: list[dict], excluded: list[dict], episode: dict) -> None:
    prepare_local_runtime(args.work_dir, args.package_dir, args.model_dir)
    import numpy as np
    from supertonic import TTS

    tts = TTS(model="supertonic-3", model_dir=args.model_dir, auto_download=False,
              intra_op_num_threads=4, inter_op_num_threads=1)
    voices = ["F2", "M1"] if args.compare else [args.voice]
    if args.compare:
        rows = [row for row in rows if any(source in {
            "/scenes/choose_path/prompt", "/scenes/diet/prompt",
        } for source in row["sources"])]
    delivery = load_delivery(args, rows)
    if args.compare and delivery:
        raise ValueError("Preset comparison and authored delivery are separate builds.")
    baseline = {}
    if args.baseline_manifest:
        baseline = {clip["id"]: clip for clip in json.loads(
            args.baseline_manifest.read_text(encoding="utf-8"))["clips"]}
    manifest = {"schema_version": 1, "clips": [], "cues": []}
    records = []
    for voice in voices:
        style = tts.get_voice_style(voice)
        for row in rows:
            started = time.monotonic()
            seed = args.seed + int(row["text_sha256"][:6], 16)
            raw_dir = args.work_dir / "raw" / voice
            raw_dir.mkdir(parents=True, exist_ok=True)
            raw = raw_dir / (row["id"] + ".wav")
            output = ((args.work_dir / "comparison" / voice) if args.compare else args.output_dir)
            output /= row["id"] + ".wav"
            entry = delivery.get(row["id"])
            delivery_record = {}
            raw_hash = None
            if entry and entry["mode"] == "preserve_baseline":
                original = baseline.get(row["id"])
                if not original or original["text_sha256"] != row["text_sha256"]:
                    raise ValueError("No exact baseline for preserved clip.")
                source = args.baseline_manifest.parent / Path(original["asset"]).name
                if sha(source) != original["sha256"]:
                    raise ValueError("Baseline clip hash differs.")
                output.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(source, output)
                measured = ffmpeg_json(args.ffmpeg, output,
                                       "loudnorm=I=-18:TP=-3:LRA=20:print_format=json")
                metrics = {**wav_metrics(output), "method": "preserved baseline bytes",
                           "final_integrated_lufs": float(measured["input_i"]),
                           "final_true_peak_dbtp": float(measured["input_tp"])}
                if metrics["final_true_peak_dbtp"] > -3:
                    raise ValueError("Preserved clip exceeds the true-peak ceiling.")
                delivery_record = {"mode": "preserve_baseline", "role": entry["role"],
                                   "reason": entry["reason"], "source_sha256": sha(source)}
                settings = {"inherited_baseline": True}
            else:
                if entry:
                    segment_records = render_delivery(tts, style, row, entry, seed, args, raw)
                    delivery_record = {"mode": "render", "role": entry["role"],
                                       "reason": entry["reason"], "segments": segment_records}
                    settings = {"steps": args.steps, "sentence_speeds": [
                        part["speed"] for part in entry["segments"]],
                        "silence_duration_seconds": 0.0,
                        "emotion_conditioning": False}
                else:
                    np.random.seed(seed)
                    generated, _duration = tts.synthesize(
                        row["text"], voice_style=style, total_steps=args.steps, speed=args.speed,
                        lang="ko", silence_duration=0.24,
                    )
                    tts.save_audio(generated, str(raw))
                    settings = {"steps": args.steps, "speed": args.speed,
                                "silence_duration_seconds": 0.24}
                raw_hash = sha(raw)
                use_underlay = entry and entry.get("underlay") and args.include_underlay
                dry = args.work_dir / "dry" / output.name if use_underlay else output
                metrics = normalize_audio(args.ffmpeg, raw, dry)
                if use_underlay:
                    mix = mix_underlay(args, entry, segment_records, dry, output)
                    delivery_record["underlay"] = {**mix, "dry_file": str(dry),
                                                  "dry_sha256": sha(dry)}
                    metrics = {**metrics, **{key: mix[key] for key in (
                        "final_integrated_lufs", "final_true_peak_dbtp", "duration_ms",
                        "sample_rate", "channels", "sample_width_bytes", "sample_peak_dbfs")}}
            digest = sha(output)
            record = {**row, "voice": voice, "seed": seed, "raw_sha256": raw_hash,
                      "synthesis_settings": settings, "delivery": delivery_record,
                      "file": str(output), "sha256": digest, **metrics,
                      "generation_seconds": round(time.monotonic() - started, 3)}
            records.append(record)
            manifest["clips"].append({key: record[key] for key in
                                      ("id", "text", "text_sha256", "sha256", "duration_ms")})
            manifest["clips"][-1]["asset"] = "story_audio/" + output.name
            print(json.dumps({"voice": voice, "id": row["id"], "index": len(records),
                              "duration_ms": metrics["duration_ms"],
                              "elapsed_seconds": record["generation_seconds"]}), flush=True)
    if sha(args.episode) != args.source_hash:
        raise RuntimeError("Episode changed during generation; do not publish stale clips.")
    report = {
        "schema_version": 1, "created_at": datetime.now(timezone.utc).isoformat(),
        "purpose": "Local preset synthesis of exact authored story text; no recordings or cloning",
        "episode": str(args.episode.relative_to(ROOT)), "episode_sha256": args.source_hash,
        "pack_id": episode["pack_id"], "content_version": episode["version"],
        "model": "Supertone/supertonic-3", "model_revision": MODEL_REVISION,
        "python_package": "supertonic " + importlib.metadata.version("supertonic"),
        "model_license": "OpenRAIL-M", "code_license": "MIT",
        "license_source": f"https://huggingface.co/Supertone/supertonic-3/blob/{MODEL_REVISION}/LICENSE",
        "model_files": [{"path": str(p.relative_to(args.model_dir)), "sha256": sha(p)}
                        for p in sorted(args.model_dir.rglob("*"))
                        if p.is_file() and (p.suffix == ".onnx" or p.name == "LICENSE"
                                           or p.parent.name == "voice_styles")],
        "script_sha256": sha(Path(__file__)), "ffmpeg": subprocess.check_output(
            [str(args.ffmpeg), "-version"], text=True).splitlines()[0],
        "settings": {"voices": voices, "language": "ko", "steps": args.steps,
                     "speed": args.speed, "silence_duration_seconds": 0.24,
                     "seed_base": args.seed, "auto_download": False, "network_disabled": True},
        "voice_selection": "Provisional preset comparison; human listening not performed",
        "subjective_listening_verified": False, "child_voice_used": False,
        "model_weights_bundled": False, "excluded_fallback": excluded, "clips": records,
    }
    if delivery:
        report["delivery_plan"] = {"path": str(args.delivery_plan.relative_to(ROOT)),
                                   "sha256": sha(args.delivery_plan)}
        report["settings"].update({"speed": "per_sentence_authored", "silence_duration_seconds": 0,
                                    "emotion_conditioning": False})
        report["voice_selection"] = (
            "Same provisional F2 as 0.33; authored speed, sentence gaps and optional offline "
            "ambience only. No emotional voice model claim; human listening still required.")
    if args.compare:
        write_json(args.work_dir / "voice-comparison.json", report)
    else:
        if args.cue_manifest:
            cue_source = json.loads(args.cue_manifest.read_text(encoding="utf-8-sig"))
            for cue in cue_source["cues"]:
                name = Path(cue["asset"]).name
                original = args.cue_manifest.parent / name
                if cue["asset"] != "story_audio/" + name or sha(original) != cue["sha256"]:
                    raise ValueError("Invalid cue source path/hash.")
                metrics = wav_metrics(original, allowed_rates=(24000, 48000))
                if metrics["duration_ms"] > 3000 or metrics["duration_ms"] != cue["duration_ms"]:
                    raise ValueError("Invalid cue duration.")
                shutil.copyfile(original, args.output_dir / name)
                manifest["cues"].append({key: cue[key] for key in
                                        ("id", "asset", "sha256", "duration_ms")})
            cue_for_source = {
                "/scenes/choose_path/prompt": "cue_dino_arrival",
                "/scenes/choose_path/actions/0/feedback": "cue_stream",
                ("/scenes/choose_path/actions/1/feedback" if any(
                    "/scenes/choose_path/actions/1/feedback" in record["sources"]
                    for record in records) else "/scenes/forest_evidence/prompt"): "cue_forest",
                **{f"/scenes/{scene}/actions/{action}/feedback": "cue_discovery"
                   for scene in ("river_evidence", "forest_evidence") for action in (0, 1)},
            }
            available = {cue["id"] for cue in manifest["cues"]}
            for clip, record in zip(manifest["clips"], records, strict=True):
                matched = {cue_for_source[source] for source in record["sources"]
                           if source in cue_for_source}
                if len(matched) > 1 or not matched.issubset(available):
                    raise ValueError("Ambiguous or missing authored cue mapping.")
                if matched:
                    clip["cue_before"] = matched.pop()
            report["cue_source_manifest_sha256"] = sha(args.cue_manifest)
            report["cue_binding_count"] = sum("cue_before" in clip for clip in manifest["clips"])
        write_json(args.output_dir / "manifest.json", manifest)
        report["manifest_sha256"] = sha(args.output_dir / "manifest.json")
        write_json(args.provenance, report)


def asr_check(args: argparse.Namespace) -> None:
    if not args.asr_package_dir or not args.asr_model_dir:
        raise ValueError("Local ASR package and model directories are required.")
    prepare_local_runtime(args.work_dir, args.asr_package_dir, args.asr_model_dir)
    from faster_whisper import WhisperModel

    source = args.work_dir / "voice-comparison.json" if args.compare else args.provenance
    provenance = json.loads(source.read_text(encoding="utf-8"))
    model = WhisperModel(str(args.asr_model_dir), device="cpu", compute_type="int8",
                         cpu_threads=4, num_workers=1, local_files_only=True)

    def canonical(text: str) -> str:
        return "".join(c.lower() for c in unicodedata.normalize("NFC", text) if c.isalnum())

    from difflib import SequenceMatcher
    results = []
    for clip in provenance["clips"]:
        path = Path(clip["file"])
        if sha(path) != clip["sha256"]:
            raise ValueError("Audio changed before ASR verification.")
        segments, _info = model.transcribe(
            str(path), language="ko", task="transcribe", beam_size=5, temperature=0,
            condition_on_previous_text=False, vad_filter=False, initial_prompt=None,
            hotwords=None,
        )
        transcript = " ".join(segment.text.strip() for segment in segments)
        expected, actual = canonical(clip["text"]), canonical(transcript)
        differences = [{"kind": kind, "expected": expected[i:j], "transcribed": actual[k:l]}
                       for kind, i, j, k, l in SequenceMatcher(
                           None, expected, actual, autojunk=False).get_opcodes() if kind != "equal"]
        row = {"id": clip["id"], "voice": clip["voice"], "audio_sha256": clip["sha256"],
               "expected": clip["text"], "transcript": transcript,
               "normalized_exact_match": expected == actual, "differences": differences}
        results.append(row)
        print(json.dumps(row, ensure_ascii=False), flush=True)
    report = {"schema_version": 1, "model": "Systran/faster-whisper-small",
              "language": "ko", "compute_type": "int8", "network_disabled": True,
              "initial_prompt": None, "human_listening_verified": False,
              "meaning": "Automatic text consistency evidence, not pronunciation or enjoyment QA",
              "source_provenance_sha256": sha(source), "clips": results}
    destination = (args.work_dir / "voice-comparison-asr.json" if args.compare else
                   args.provenance.with_name(args.provenance.stem + "-asr.json"))
    write_json(destination, report)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--episode", type=Path, default=ROOT / "content/ko-KR/dinosaurs/episode.json")
    parser.add_argument("--model-dir", type=Path, required=True)
    parser.add_argument("--package-dir", type=Path, required=True)
    parser.add_argument("--ffmpeg", type=Path, required=True)
    parser.add_argument("--work-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path,
                        default=ROOT / "apps/vehicle/app/src/main/assets/story_audio")
    parser.add_argument("--provenance", type=Path,
                        default=ROOT / "docs/audio/onu-0.33.0-voice-provenance.json")
    parser.add_argument("--voice", choices=["F2", "M1"], default="F2")
    parser.add_argument("--steps", type=int, default=16)
    parser.add_argument("--speed", type=float, default=1.02)
    parser.add_argument("--seed", type=int, default=330033)
    parser.add_argument("--compare", action="store_true")
    parser.add_argument("--asr-only", action="store_true")
    parser.add_argument("--asr-package-dir", type=Path)
    parser.add_argument("--asr-model-dir", type=Path)
    parser.add_argument("--cue-manifest", type=Path)
    parser.add_argument("--delivery-plan", type=Path)
    parser.add_argument("--baseline-manifest", type=Path)
    parser.add_argument("--ambience-dir", type=Path)
    parser.add_argument("--include-underlay", action="store_true")
    parser.add_argument("--ids", nargs="+", help="Generate only these IDs into a separate candidate directory")
    parser.add_argument("--exclude-ids", nargs="+", default=[],
                        help="Withhold reviewed candidates pending human listening")
    args = parser.parse_args()
    for key, value in vars(args).items():
        if isinstance(value, Path):
            setattr(args, key, value.resolve())
    if not args.output_dir.is_relative_to(ROOT) or not args.provenance.is_relative_to(ROOT):
        raise ValueError("Publish only inside the project workspace.")
    if args.asr_only:
        asr_check(args)
        return
    args.source_hash = sha(args.episode)
    episode = json.loads(args.episode.read_text(encoding="utf-8-sig"))
    rows, excluded = collect(episode)
    unknown = set(args.exclude_ids) - {row["id"] for row in rows}
    if unknown:
        raise ValueError(f"Unknown excluded IDs: {sorted(unknown)}")
    for row in rows:
        if row["id"] in args.exclude_ids:
            excluded.append({**row, "reason": "asr_review_pending_not_confirmed_mispronunciation"})
    rows = [row for row in rows if row["id"] not in args.exclude_ids]
    if args.ids:
        unknown = set(args.ids) - {row["id"] for row in rows}
        if unknown:
            raise ValueError(f"Unknown authored clip IDs: {sorted(unknown)}")
        rows = [row for row in rows if row["id"] in args.ids]
    if not rows:
        raise ValueError("No static authored story text found.")
    synthesize(args, rows, excluded, episode)


if __name__ == "__main__":
    main()
