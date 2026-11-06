"""Render bounded Onu cues from licensed field recordings, entirely offline.

Requires NumPy and ffmpeg. Inputs are hash-pinned in the source manifest.
A new output directory is required; this tool never overwrites application assets.
"""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
from pathlib import Path
import subprocess
import wave

import numpy as np

RATE = 48000
VOICE_RATE = 24000
ROOT = Path(__file__).resolve().parents[1]


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def run(ffmpeg, args, data=None):
    result = subprocess.run([str(ffmpeg), "-hide_banner", "-loglevel", "error", *args],
                            input=data, capture_output=True, check=True)
    return result.stdout


def decode(ffmpeg, path, rate=RATE, filters=None):
    args = ["-i", str(path)]
    if filters:
        args += ["-af", filters]
    raw = run(ffmpeg, [*args, "-ac", "1", "-ar", str(rate), "-f", "f64le", "-"])
    return np.frombuffer(raw, dtype="<f8").copy()


def fade(signal, attack=.05, release=.12, rate=RATE):
    result = signal.copy()
    for seconds, reverse in ((attack, False), (release, True)):
        count = min(len(result) // 2, round(seconds * rate))
        if not count:
            continue
        env = np.sin(np.linspace(0, np.pi / 2, count)) ** 2
        if reverse:
            result[-count:] *= env[::-1]
        else:
            result[:count] *= env
    return result


def rms(signal):
    return float(np.sqrt(np.mean(signal ** 2)))


def db(value):
    return float(20 * np.log10(max(float(value), 1e-12)))


def level(signal, target_rms, ceiling):
    gain = min(10 ** (target_rms / 20) / max(rms(signal), 1e-12),
               10 ** (ceiling / 20) / max(float(np.max(np.abs(signal))), 1e-12))
    return signal * gain


def slice_at(signal, start, seconds, speed=1.0):
    part = signal[round(start * RATE):round((start + seconds) * RATE)]
    if len(part) != round(seconds * RATE):
        raise ValueError("Source interval outside recording")
    if speed == 1.0:
        return part.copy()
    # Intentional linked pitch/time varispeed on animal layers only.
    positions = np.arange(0, len(part) - 1, speed)
    return np.interp(positions, np.arange(len(part)), part)


def add(dst, part, start):
    index = round(start * RATE)
    if index < 0 or index + len(part) > len(dst):
        raise ValueError("Layer would be cut off")
    dst[index:index + len(part)] += part


def save_pcm(path, signal, rate):
    if not np.isfinite(signal).all() or np.max(np.abs(signal)) >= 1:
        raise ValueError("Invalid or clipped output")
    pcm = np.clip(np.round(signal * 32768), -32768, 32767).astype("<i2")
    with wave.open(str(path), "wb") as handle:
        handle.setnchannels(1)
        handle.setsampwidth(2)
        handle.setframerate(rate)
        handle.writeframes(pcm.tobytes())


def save_float(ffmpeg, path, signal):
    run(ffmpeg, ["-f", "f64le", "-ar", str(RATE), "-ac", "1", "-i", "-",
                 "-c:a", "pcm_f32le", str(path)], signal.astype("<f8").tobytes())


def measure(ffmpeg, path):
    result = subprocess.run([str(ffmpeg), "-hide_banner", "-i", str(path), "-af",
                             "loudnorm=I=-24:TP=-3:LRA=20:print_format=json", "-f", "null", "-"],
                            capture_output=True, text=True, check=True)
    data = json.loads(result.stderr[result.stderr.rfind("{"):result.stderr.rfind("}") + 1])
    with wave.open(str(path), "rb") as handle:
        rate, count = handle.getframerate(), handle.getnframes()
        if handle.getnchannels() != 1 or handle.getsampwidth() != 2:
            raise ValueError("App output must be mono PCM16")
        samples = np.frombuffer(handle.readframes(count), dtype="<i2").astype(float) / 32768
    return {"sha256": sha(path), "sample_rate": rate, "channels": 1,
            "duration_ms": round(count / rate * 1000), "frames": count,
            "rms_dbfs": round(db(rms(samples)), 3), "peak_dbfs": round(db(np.max(np.abs(samples))), 3),
            "integrated_lufs": float(data["input_i"]), "true_peak_dbtp": float(data["input_tp"]),
            "clipped_samples": int(np.sum(np.abs(samples) >= 32767 / 32768)),
            "endpoint_max_abs": float(max(abs(samples[0]), abs(samples[-1])))}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--ffmpeg", type=Path, required=True)
    args = parser.parse_args()
    out, ffmpeg = args.output, args.ffmpeg
    out.mkdir(parents=True, exist_ok=False)
    for sub in ("assets", "masters", "comparison"):
        (out / sub).mkdir()
    metadata = json.loads((ROOT / "docs/audio/onu-0.35.0-sources.json").read_text(encoding="utf-8"))
    sources = {}
    for source in metadata["sources"]:
        path = ROOT / source["file"]
        if sha(path) != source["sha256"]:
            raise ValueError("Source hash changed: " + source["id"])
        sources[source["id"]] = path
    old_manifest = json.loads((args.baseline / "manifest.json").read_text(encoding="utf-8"))
    manifest = copy.deepcopy(old_manifest)
    for entry in manifest["clips"] + manifest["cues"]:
        source = args.baseline / Path(entry["asset"]).name
        if sha(source) != entry["sha256"]:
            raise ValueError("Baseline hash changed")
        (out / "assets" / source.name).write_bytes(source.read_bytes())

    forest = decode(ffmpeg, sources["forest"], filters="highpass=f=180,lowpass=f=11500")
    stream_filter = ("highpass=f=150,lowpass=f=11000,"
                     "acompressor=threshold=0.025:ratio=3:attack=1:release=100:knee=4,"
                     "alimiter=limit=0.12:attack=5:release=80:level=0:latency=1")
    stream = decode(ffmpeg, sources["stream"], filters=stream_filter)
    pigeon = decode(ffmpeg, sources["pigeon"], filters="highpass=f=115,lowpass=f=3900")
    pig = decode(ffmpeg, sources["pig"], filters="highpass=f=160,lowpass=f=2900")
    forest_cue = level(fade(slice_at(forest, 14.15, 2.4), .18, .34), -28.5, -14)
    stream_cue = level(fade(slice_at(stream, 6.4, 2.4), .18, .36), -29, -10)
    dinosaur = level(fade(slice_at(forest, 14.15, 2.85), .25, .45), -46, -27)
    body = level(fade(slice_at(pigeon, 2.45, 1.35, .9), .10, .18), -25, -11)
    breath = level(fade(slice_at(pig, 2.5, .75, .86), .12, .19), -32, -18)
    reply = level(fade(slice_at(pigeon, 4.0, .48, 1.05), .08, .14), -27.5, -13)
    add(dinosaur, body, .35)
    add(dinosaur, breath, .42)
    add(dinosaur, reply, 2.04)
    dinosaur = level(fade(dinosaur, .12, .24), -24, -10)
    cues = {"cue_dino_arrival": dinosaur, "cue_forest": forest_cue, "cue_stream": stream_cue}
    report = {"schema_version": 1, "version": "0.35.0", "source_manifest_sha256":
              sha(ROOT / "docs/audio/onu-0.35.0-sources.json"), "builder_sha256": sha(Path(__file__)),
              "baseline_manifest_sha256": sha(args.baseline / "manifest.json"), "cues": [], "mixes": [],
              "human_listening_verified": False, "real_dinosaur_reconstruction": False,
              "generative_audio_model_used": False, "child_or_vehicle_evaluation": False,
              "voice_model_or_script_changed": False, "runtime_loop": False}
    for cue_id, signal in cues.items():
        path = out / "assets" / (cue_id + ".wav")
        save_float(ffmpeg, out / "masters" / (cue_id + ".wav"), signal)
        save_pcm(path, signal, RATE)
        stats = measure(ffmpeg, path)
        if stats["true_peak_dbtp"] > -3 or stats["duration_ms"] > 3000 or stats["clipped_samples"]:
            raise ValueError("Cue output limit exceeded")
        entry = next(row for row in manifest["cues"] if row["id"] == cue_id)
        entry.update({key: stats[key] for key in ("sha256", "duration_ms")})
        report["cues"].append({"id": cue_id, **stats})
    for clip_id, kind, source_start, end_ms in (
            ("dino_e92826c9a6ad84c9", "forest", 16.55, 2120),
            ("dino_350bcbfbd123f73b", "stream", 8.8, 1820)):
        dry_path = ROOT / "docs/audio/sources/onu-0.35.0" / (clip_id + ".wav")
        dry = decode(ffmpeg, dry_path, VOICE_RATE)
        texture = decode(ffmpeg, sources[kind], VOICE_RATE,
                         f"atrim=start={source_start}:duration=4,asetpts=PTS-STARTPTS,highpass=f=200,lowpass=f=8500")
        start, end = round(.12 * VOICE_RATE), round(end_ms / 1000 * VOICE_RATE)
        bed = fade(texture[:end - start], .30, .35, VOICE_RATE)
        active = dry[start:end][np.abs(dry[start:end]) > 10 ** (-40 / 20)]
        target = rms(active) * 10 ** (-27 / 20)
        bed *= target / max(rms(bed), 1e-12)
        wet = dry.copy()
        wet[start:end] += bed
        path = out / "assets" / (clip_id + ".wav")
        save_pcm(path, wet, VOICE_RATE)
        stats = measure(ffmpeg, path)
        if stats["true_peak_dbtp"] > -3 or stats["clipped_samples"]:
            raise ValueError("Voice mix lacks peak headroom")
        entry = next(row for row in manifest["clips"] if row["id"] == clip_id)
        if entry["duration_ms"] != stats["duration_ms"]:
            raise ValueError("Mix changed narration duration")
        entry["sha256"] = stats["sha256"]
        report["mixes"].append({"id": clip_id, "kind": kind, "dry_sha256": sha(dry_path),
                               "source_offset_seconds": source_start, "start_ms": 120, "end_ms": end_ms,
                               "relative_active_speech_rms_db": -27, "fade_ms": [300, 350],
                               "unchanged_dry_frame_count": len(wet) == len(dry), **stats})
    write_json(out / "assets/manifest.json", manifest)
    report["manifest_sha256"] = sha(out / "assets/manifest.json")
    # Compare cues with a common integrated loudness, using gain only.
    report["comparisons"] = []
    for cue_id in cues:
        paths = [args.baseline / (cue_id + ".wav"), out / "assets" / (cue_id + ".wav")]
        stats = [measure(ffmpeg, path) for path in paths]
        target = min(-24, *(s["integrated_lufs"] - 4 - s["true_peak_dbtp"] for s in stats))
        pair = {"id": cue_id, "target_integrated_lufs": target, "files": []}
        for name, path, stat in zip(("before", "after"), paths, stats, strict=True):
            signal = decode(ffmpeg, path) * 10 ** ((target - stat["integrated_lufs"]) / 20)
            dest = out / "comparison" / f"{cue_id}_{name}.wav"
            save_pcm(dest, signal, RATE)
            checked = measure(ffmpeg, dest)
            if abs(checked["integrated_lufs"] - target) > .2 or checked["true_peak_dbtp"] > -3:
                raise ValueError("Comparison loudness check failed")
            pair["files"].append({"version": name, **checked})
        report["comparisons"].append(pair)
    for kind, clip_id in (("forest", "dino_e92826c9a6ad84c9"), ("stream", "dino_350bcbfbd123f73b")):
        for label, folder in (("before", args.baseline), ("after", out / "assets")):
            cue = decode(ffmpeg, folder / f"cue_{kind}.wav")
            voice = decode(ffmpeg, folder / (clip_id + ".wav"))
            combined = np.concatenate((cue, voice))
            save_pcm(out / "comparison" / f"scene_{kind}_{label}.wav", combined, RATE)
    report["scene_comparison"] = "Offline cue then same narration at app gains; not an app playback recording."
    report["preserved_voice_files"] = sum((out / "assets" / Path(c["asset"]).name).read_bytes() ==
                                           (args.baseline / Path(c["asset"]).name).read_bytes()
                                           for c in manifest["clips"])
    report["discovery_preserved"] = sha(out / "assets/cue_discovery.wav") == sha(args.baseline / "cue_discovery.wav")
    report["recipe"] = {"forest_cue_source_start": 14.15, "stream_cue_source_start": 6.4,
                        "dinosaur_layers": [{"source": "pigeon", "start": 2.45, "duration": 1.35, "speed": .9, "at": .35},
                                            {"source": "pig", "start": 2.5, "duration": .75, "speed": .86, "at": .42},
                                            {"source": "pigeon", "start": 4., "duration": .48, "speed": 1.05, "at": 2.04}],
                        "dinosaur_forest_bed_rms_dbfs": -46, "stream_cue_transient_control": stream_filter}
    if report["preserved_voice_files"] != 22 or not report["discovery_preserved"]:
        raise ValueError("Unrelated assets changed")
    write_json(out / "provenance.json", report)
    print(json.dumps({"status": "rendered", "output": str(out), "cue_count": 3, "remixed_voices": 2,
                      "preserved_voices": 22, "manifest_sha256": report["manifest_sha256"]}))


if __name__ == "__main__":
    main()
