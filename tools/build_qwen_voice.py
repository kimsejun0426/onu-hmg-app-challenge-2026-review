#!/usr/bin/env python3
"""Reproduce pinned Onu B2 authored clips with local Qwen models and a self-generated reference.

Requires an explicit existing model, plan, reference, ffmpeg and cue directory. No download,
API key, microphone or human recording capture. Writes only to a new output directory.
The model, reference rights and selected seed recipe must be supplied by the caller.
Audio/text screening and app tests remain separate from synthesis and human listening.
"""

from __future__ import annotations
import argparse, datetime, gc, hashlib, importlib.metadata, json, math, os, shutil, socket, subprocess, time
from pathlib import Path


def digest(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def read(path):
    return json.loads(Path(path).read_text(encoding="utf-8-sig"))


def write(path, value):
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    tmp.replace(path)


def refuse_network(*args, **kwargs):
    raise RuntimeError("This authored-audio build is offline.")


def loudness(ffmpeg, path):
    result = subprocess.run(
        [
            str(ffmpeg),
            "-nostdin",
            "-v",
            "info",
            "-i",
            str(path),
            "-af",
            "loudnorm=I=-23:TP=-3.2:LRA=20:print_format=json",
            "-f",
            "null",
            "-",
        ],
        capture_output=True,
        text=True,
        check=True,
    )
    return json.JSONDecoder().raw_decode(result.stderr[result.stderr.rfind("{") :])[0]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("model-dir", "plan", "reference", "output", "ffmpeg", "cue-source"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument(
        "--execute", action="store_true", help="Explicitly run the local GPU synthesis."
    )
    args = parser.parse_args()
    if not args.execute:
        parser.error("Pass --execute to run local synthesis.")
    plan = read(args.plan)
    clips = plan["clips"]
    assert clips and len(clips) <= 256
    assert len({row["id"] for row in clips}) == len(clips) == len({row["text"] for row in clips})
    assert digest(args.reference) == plan["reference"]["sha256"]
    assert read(args.model_dir / "download-manifest.json")["revision"] == plan["model"]["revision"]
    assert args.output.is_absolute(), "Provide an explicit absolute output directory."
    assert not args.output.exists(), (
        "Output must be a new directory; previous attempts are preserved."
    )
    args.output.mkdir(parents=True)
    raw_dir = args.output / "raw"
    raw_dir.mkdir()
    assets = args.output / "assets"
    assets.mkdir()
    cache = args.output / "runtime-cache"
    cache.mkdir()
    for key in (
        "TEMP",
        "TMP",
        "TMPDIR",
        "HF_HOME",
        "TORCH_HOME",
        "XDG_CACHE_HOME",
        "NUMBA_CACHE_DIR",
        "CUDA_CACHE_PATH",
        "PYTHONPYCACHEPREFIX",
    ):
        os.environ[key] = str(cache)
    os.environ.update(
        HF_HUB_OFFLINE="1",
        TRANSFORMERS_OFFLINE="1",
        HF_HUB_DISABLE_TELEMETRY="1",
        HF_HUB_DISABLE_IMPLICIT_TOKEN="1",
    )
    socket.create_connection = refuse_network
    import numpy as np
    import soundfile as sf
    import torch
    from transformers import set_seed
    from qwen_tts import Qwen3TTSModel

    assert importlib.metadata.version("qwen-tts") == plan["model"]["package"].split("==")[1]
    assert torch.cuda.is_available()
    torch.set_num_threads(6)
    report = {
        "schema_version": 1,
        "status": "running",
        "created_utc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "plan_sha256": digest(args.plan),
        "model": plan["model"],
        "reference": plan["reference"],
        "torch": torch.__version__,
        "device": torch.cuda.get_device_name(0),
        "clips": [],
        "human_listening_verified": False,
        "automatic_transcript_check_performed": False,
    }
    write(args.output / "provenance.json", report)
    start = time.perf_counter()
    model = None
    try:
        api = plan["api"]
        assert api["dtype"] == "bfloat16" and api["attention"] == "sdpa"
        model = Qwen3TTSModel.from_pretrained(
            str(args.model_dir),
            device_map="cuda:0",
            dtype=torch.bfloat16,
            attn_implementation="sdpa",
            local_files_only=True,
        )
        original = model.model.talker.generate
        ends = []

        def audit(*positional, **keywords):
            result = original(*positional, **keywords)
            seq = result.sequences.detach().cpu()
            eos = int(keywords["eos_token_id"])
            ends.append(
                {
                    "tokens": int(seq.shape[-1]),
                    "normal_end": bool(seq.shape[0] == 1 and int(seq[0, -1]) == eos),
                }
            )
            return result

        model.model.talker.generate = audit
        ref, sr = sf.read(args.reference, dtype="float32")
        assert sr == 24000
        prompt = model.create_voice_clone_prompt(
            ref_audio=(ref, sr),
            ref_text=plan["reference"]["text"],
            x_vector_only_mode=api["x_vector_only_mode"],
        )
        manifest = {"schema_version": 1, "clips": [], "cues": plan.get("cues", [])}
        for index, row in enumerate(clips):
            assert hashlib.sha256(row["text"].encode("utf-8")).hexdigest() == row["text_sha256"]
            name = Path(row["asset"]).name
            assert row["asset"] == "story_audio/" + name and name == row["id"] + ".wav"
            set_seed(row["seed"])
            t = time.perf_counter()
            before = len(ends)
            waves, sr = model.generate_voice_clone(
                text=row["text"],
                language=api["language"],
                voice_clone_prompt=prompt,
                non_streaming_mode=api["non_streaming_mode"],
                max_new_tokens=api["max_new_tokens"],
            )
            y = np.asarray(waves[0])
            assert sr == 24000 and y.ndim == 1 and np.isfinite(y).all()
            raw = raw_dir / name
            sf.write(raw, y, sr, subtype="FLOAT")
            assert (
                len(ends) == before + 1
                and ends[-1]["normal_end"]
                and 0.4 < len(y) / sr < 25
                and np.max(np.abs(y)) < 1
            )
            measured = loudness(args.ffmpeg, raw)
            level = float(measured["input_i"])
            peak = float(measured["input_tp"])
            assert math.isfinite(level) and math.isfinite(peak)
            target = plan["processing"]["static_gain_target_lufs"]
            cap = plan["processing"]["true_peak_cap_dbtp"]
            gain = min(target - level, cap - peak)
            output = assets / name
            subprocess.run(
                [
                    str(args.ffmpeg),
                    "-nostdin",
                    "-v",
                    "error",
                    "-n",
                    "-i",
                    str(raw),
                    "-af",
                    f"volume={gain:.8f}dB",
                    "-c:a",
                    "pcm_s16le",
                    str(output),
                ],
                check=True,
            )
            z, rate = sf.read(output)
            actual = loudness(args.ffmpeg, output)
            assert (
                rate == sr
                and len(z) == len(y)
                and np.max(np.abs(z)) < 1
                and float(actual["input_tp"]) <= -3.0
            )
            entry = {key: row[key] for key in ("id", "text", "text_sha256", "asset")}
            entry.update(sha256=digest(output), duration_ms=round(len(z) / rate * 1000))
            if row.get("cue_before"):
                entry["cue_before"] = row["cue_before"]
            manifest["clips"].append(entry)
            report["clips"].append(
                {
                    **entry,
                    "seed": row["seed"],
                    "raw_sha256": digest(raw),
                    "frames": len(y),
                    "sample_rate": sr,
                    "integrated_lufs": float(actual["input_i"]),
                    "true_peak_dbtp": float(actual["input_tp"]),
                    "static_gain_db": gain,
                    "termination": ends[-1],
                    "seconds": time.perf_counter() - t,
                    "matches_prior_audio_sha256": entry["sha256"]
                    == row.get("expected_audio_sha256"),
                }
            )
            write(args.output / "provenance.json", report)
            print(
                json.dumps(
                    {"completed": index + 1, "total": len(clips), "id": row["id"]},
                    ensure_ascii=False,
                ),
                flush=True,
            )
        for cue in manifest["cues"]:
            source = args.cue_source / Path(cue["asset"]).name
            assert digest(source) == cue["sha256"]
            shutil.copy2(source, assets / source.name)
        write(assets / "manifest.json", manifest)
        report["status"] = "synthesized_not_listening_approved"
        report["manifest_sha256"] = digest(assets / "manifest.json")
    except Exception as error:
        report["status"] = "failed"
        report["error"] = type(error).__name__
        raise
    finally:
        if model is not None:
            del model
            gc.collect()
            torch.cuda.empty_cache()
        report["elapsed_seconds"] = time.perf_counter() - start
        write(args.output / "provenance.json", report)


if __name__ == "__main__":
    main()
