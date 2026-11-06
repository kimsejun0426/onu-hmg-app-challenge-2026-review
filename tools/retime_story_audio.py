#!/usr/bin/env python3
"""Retime pinned, authored WAV narration locally with pitch-preserving FFmpeg atempo.

All narration input samples are supplied to the filter. A temporary quiet tail lets
its final analysis window flush. Only that quiet padding is removed afterwards;
no output sample above -65 dBFS is discarded and the original quiet tail duration
is retained. This is a tempo edit, not new speech or an accent correction.
"""
from __future__ import annotations
import argparse
import array
from datetime import datetime, timezone
import hashlib
import json
import math
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
import wave

ROOT = Path(__file__).resolve().parents[1]
THRESHOLD = 32768 * 10 ** (-65 / 20)

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def save(path, value):
    with path.open('x', encoding='utf-8', newline='\n') as handle:
        json.dump(value, handle, ensure_ascii=False, indent=2)
        handle.write('\n')

def read_wave(path):
    with wave.open(str(path), 'rb') as reader:
        fmt = (reader.getframerate(), reader.getnchannels(), reader.getsampwidth(), reader.getcomptype())
        raw = reader.readframes(reader.getnframes())
    if fmt != (24000, 1, 2, 'NONE'):
        raise ValueError(f'Expected 24 kHz mono PCM16 narration: {path.name}: {fmt}')
    samples = array.array('h', raw)
    if sys.byteorder != 'little': samples.byteswap()
    if not samples: raise ValueError('Empty narration')
    return samples

def write_wave(path, samples):
    raw = array.array('h', samples)
    if sys.byteorder != 'little': raw.byteswap()
    with wave.open(str(path), 'wb') as writer:
        writer.setnchannels(1)
        writer.setsampwidth(2)
        writer.setframerate(24000)
        writer.writeframes(raw.tobytes())

def metrics(samples):
    active = [i for i, value in enumerate(samples) if abs(value) > THRESHOLD]
    if not active: raise ValueError('Narration is silent')
    rms = math.sqrt(sum(float(x) * x for x in samples) / len(samples))
    peak = max(abs(x) for x in samples)
    return dict(frames=len(samples), duration_ms=round(len(samples) / 24),
                duration_seconds=len(samples) / 24000, sample_rate=24000,
                channels=1, sample_width_bytes=2,
                rms_dbfs=20 * math.log10(rms / 32768),
                sample_peak_dbfs=20 * math.log10(peak / 32768),
                clipped_samples=sum(abs(x) >= 32767 for x in samples),
                active_first_frame=active[0], active_last_frame=active[-1],
                leading_quiet_frames=active[0],
                trailing_quiet_frames=len(samples) - 1 - active[-1],
                endpoint_max_abs=max(abs(samples[0]), abs(samples[-1])))

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-dir', type=Path, required=True)
    parser.add_argument('--baseline-source', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--ffmpeg', type=Path, required=True)
    parser.add_argument('--tempo', type=float, default=.96)
    args = parser.parse_args()
    source, baseline, out, ffmpeg = [p.resolve() for p in (args.source_dir, args.baseline_source, args.out, args.ffmpeg)]
    if not .95 <= args.tempo <= .98: raise ValueError('Expected subtle 2–5 percent tempo reduction')
    if not source.is_relative_to(ROOT) or not baseline.is_relative_to(ROOT): raise ValueError('Use project inputs')
    if out.drive.lower() != 'd:' or not out.is_relative_to(ROOT / '.local') or out.exists(): raise ValueError('Use a new D project .local output directory')
    if not ffmpeg.is_file(): raise ValueError('Existing FFmpeg binary is required; no download')
    frozen = json.loads(baseline.read_text(encoding='utf-8-sig'))
    manifest_path = source / 'manifest.json'
    manifest = json.loads(manifest_path.read_text(encoding='utf-8-sig'))
    clips, cues = manifest['clips'], manifest['cues']
    if len(clips) != 48 or len(cues) != 4: raise ValueError('Unexpected pinned asset inventory')
    expected_files = {'manifest.json', *(Path(x['asset']).name for x in clips + cues)}
    if {p.name for p in source.iterdir() if p.is_file()} != expected_files: raise ValueError('Audio inventory differs')
    if len({x['id'] for x in clips}) != 48: raise ValueError('Duplicate narration ID')
    original_records = []
    for name in sorted(expected_files):
        p = source / name
        relative = p.relative_to(ROOT).as_posix()
        digest = sha(p)
        if frozen.get(relative) != digest: raise ValueError('Source differs from pinned build: ' + relative)
        original_records.append(dict(path=str(p), name=name, sha256=digest, bytes=p.stat().st_size))
    for row in clips:
        if hashlib.sha256(row['text'].encode('utf-8')).hexdigest() != row['text_sha256']: raise ValueError('Text identity mismatch')
        if sha(source / Path(row['asset']).name) != row['sha256']: raise ValueError('Manifest WAV hash mismatch')
    out.mkdir(parents=True)
    for name in ('assets', 'source', 'raw', 'logs', 'tmp'): (out / name).mkdir()
    env = dict(os.environ, TEMP=str(out/'tmp'), TMP=str(out/'tmp'), TMPDIR=str(out/'tmp'))
    for row in original_records: shutil.copyfile(Path(row['path']), out/'source'/row['name'])
    report = dict(schema_version=1, status='running', created_utc=datetime.now(timezone.utc).isoformat(),
                  operation='offline_pitch_preserving_atempo', tempo=args.tempo,
                  ffmpeg_path=str(ffmpeg), ffmpeg_sha256=sha(ffmpeg),
                  ffmpeg_version=subprocess.check_output([str(ffmpeg), '-version'], text=True, env=env).splitlines()[0],
                  script_sha256=sha(Path(__file__)), baseline_source=dict(path=str(baseline), sha256=sha(baseline)),
                  original_manifest_sha256=sha(manifest_path), source_assets=original_records,
                  new_voice_generated=False, accent_corrected=False, human_listening_verified=False,
                  extra_normalization=False, input_tail_padding_ms=200, quiet_threshold_dbfs=-65,
                  clips=[], cues=[])
    started = time.monotonic()
    try:
        for clip in clips:
            name = Path(clip['asset']).name
            original = read_wave(out/'source'/name)
            before = metrics(original)
            raw = out/'raw'/name
            command = [str(ffmpeg), '-hide_banner', '-nostdin', '-loglevel', 'error', '-n', '-i', str(out/'source'/name),
                       '-af', f'apad=pad_len=4800,atempo={args.tempo:.6f}', '-ac', '1', '-ar', '24000', '-c:a', 'pcm_s16le', str(raw)]
            run = subprocess.run(command, env=env, text=True, capture_output=True, timeout=45)
            (out/'logs'/(clip['id']+'.log')).write_text(run.stdout+run.stderr, encoding='utf-8')
            if run.returncode != 0: raise RuntimeError('FFmpeg failed: '+clip['id'])
            expanded = read_wave(raw)
            raw_metrics = metrics(expanded)
            desired_frames = round(len(original) / args.tempo)
            preserve_tail_until = raw_metrics['active_last_frame'] + 1 + before['trailing_quiet_frames']
            final_frames = max(desired_frames, preserve_tail_until)
            if final_frames > len(expanded): raise ValueError('Filter did not retain enough end padding')
            removed = expanded[final_frames:]
            if any(abs(x) > THRESHOLD for x in removed): raise ValueError('Would remove audible output')
            rendered = expanded[:final_frames]
            output = out/'assets'/name
            write_wave(output, rendered)
            after = metrics(read_wave(output))
            if after['duration_seconds'] >= 60 or after['clipped_samples'] or after['sample_peak_dbfs'] > -2:
                raise ValueError('Invalid length or peak: '+clip['id'])
            if after['trailing_quiet_frames'] < before['trailing_quiet_frames']:
                raise ValueError('Original ending margin was shortened')
            if after['leading_quiet_frames'] + 24 < before['leading_quiet_frames'] * .9:
                raise ValueError('Leading quiet margin was materially shortened')
            if abs(after['rms_dbfs'] - before['rms_dbfs']) > .6:
                raise ValueError('Unexpected loudness change')
            if not 1.03 <= after['frames']/before['frames'] <= 1.065:
                raise ValueError('Unexpected tempo duration')
            clip['duration_ms'] = after['duration_ms']
            clip['sha256'] = sha(output)
            row = dict(id=clip['id'], text=clip['text'], text_sha256=clip['text_sha256'],
                       source=str(out/'source'/name), output=str(output), source_sha256=sha(out/'source'/name),
                       output_sha256=sha(output), before=before, after=after,
                       supplied_all_input_frames=True, discarded_above_threshold_samples=0,
                       removed_padding_frames=len(removed), final_frames=final_frames,
                       raw_output_frames=len(expanded), cue_before=clip.get('cue_before'))
            report['clips'].append(row)
        for cue in cues:
            name = Path(cue['asset']).name
            shutil.copyfile(out/'source'/name, out/'assets'/name)
            digest = sha(out/'assets'/name)
            if digest != cue['sha256']: raise ValueError('Cue changed')
            report['cues'].append(dict(id=cue['id'], sha256=digest, unchanged=True))
        save(out/'assets'/'manifest.json', manifest)
        for row in original_records:
            if sha(Path(row['path'])) != row['sha256'] or sha(out/'source'/row['name']) != row['sha256']:
                raise ValueError('Pinned source or backup changed during work')
        report.update(status='candidate_verified', candidate_manifest_sha256=sha(out/'assets'/'manifest.json'),
                      narration_count=48, cue_count=4, source_assets_unchanged=True,
                      text_ids_and_cue_bindings_preserved=True, elapsed_seconds=round(time.monotonic()-started,3),
                      limits=['Tempo edit preserves pitch algorithmically; accent and prosody are inherited.',
                              'Signal and file checks are not human listening or pronunciation validation.',
                              'No app assets changed; integration and Android playback verification are separate.'])
    except Exception as error:
        report.update(status='failed', error=type(error).__name__+': '+str(error))
        save(out/'report.json', report)
        raise
    save(out/'report.json', report)
    print(json.dumps({k:report[k] for k in ('status','narration_count','cue_count','elapsed_seconds','candidate_manifest_sha256')}))

if __name__ == '__main__': main()
