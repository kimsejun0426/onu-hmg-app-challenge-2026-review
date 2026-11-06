"""Create original, brief story cues; no recordings or external model assets.

Run with NumPy installed. Writes to a NEW output folder. These are stylized
sound-design sketches, not reconstructions of dinosaur vocalizations.
Runtime cues play serially before narration; optional ambience textures are
for finite offline mixing inside a narration file, never runtime loops.
"""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import wave
import numpy as np

RATE = 24000
SEED = 3302

def envelope(n, fade_in=.10, fade_out=.20):
    y = np.ones(n)
    a, b = min(n // 2, int(RATE * fade_in)), min(n // 2, int(RATE * fade_out))
    y[:a] = np.sin(np.linspace(0, np.pi/2, a)) ** 2
    y[-b:] = np.cos(np.linspace(0, np.pi/2, b)) ** 2
    return y

def smooth_noise(seconds, seed, low=180., high=2300.):
    n = int(RATE * seconds)
    rng = np.random.default_rng(seed)
    f = np.fft.rfftfreq(n, 1/RATE)
    shape = (1 - np.exp(-(f / low) ** 4)) * np.exp(-(f / high) ** 4)
    signal = np.fft.irfft(np.fft.rfft(rng.standard_normal(n)) * shape, n)
    return signal / max(float(np.max(np.abs(signal))), 1e-8)

def add(dst, part, start):
    i = int(start * RATE)
    n = min(len(part), len(dst)-i)
    if n > 0:
        dst[i:i+n] += part[:n]

def bird(seconds=.20, start_hz=1700., end_hz=2250.):
    t = np.arange(int(RATE * seconds))/RATE
    f = np.linspace(start_hz, end_hz, len(t)) + 70*np.sin(2*np.pi*17*t)
    phase = 2*np.pi*np.cumsum(f)/RATE
    return np.sin(phase) * envelope(len(t), .04, .12) * .08

def little_dinosaur():
    t = np.arange(int(RATE * 1.25))/RATE
    f = 125 + 42*np.sin(np.pi * t / 1.25) + 4*np.sin(2*np.pi*6*t)
    phase = 2*np.pi*np.cumsum(f)/RATE
    # Soft resonant, rounded fictional call. No sub-bass, sharp roar or impulse.
    signal = sum((.64 ** h) * np.sin((h+1)*phase + .15*h) for h in range(6))
    signal += .09*smooth_noise(1.25, SEED+3, 220., 1250.)
    signal *= (.68 + .32*np.sin(np.pi*t/1.25)**2)*envelope(len(t), .22, .35)
    return signal * .19

def make_cues():
    t = np.arange(int(2.6*RATE))/RATE
    arrival = .10*smooth_noise(2.6, SEED, 450., 2400.)
    arrival *= .55+.45*np.sin(np.pi*t/2.6)**2
    add(arrival,bird(),.20)
    add(arrival,little_dinosaur(),.91)
    forest = .20*smooth_noise(1.65, SEED+1, 700., 3600.)
    ft = np.arange(len(forest))/RATE
    forest *= .25+.75*np.sin(np.pi*ft/1.65)**2
    add(forest,bird(.18,1900,1500),.38)
    water = .20*smooth_noise(1.65,SEED+2,230.,3300.)
    wt = np.arange(len(water))/RATE
    water *= .55+.15*np.sin(2*np.pi*2.7*wt)+.12*np.sin(2*np.pi*4.1*wt)
    for i, hz in enumerate([680.,930.,810.]):
        t = np.arange(int(.20*RATE))/RATE
        bubble = np.sin(2*np.pi*(hz*t-160*t*t))*np.exp(-t*22)*envelope(len(t),.02,.07)*.06
        add(water,bubble,.25+i*.36)
    discovery = np.zeros(int(.80*RATE))
    for i,hz in enumerate([523.25,659.25,783.99]):
        t=np.arange(int(.48*RATE))/RATE
        note=(np.sin(2*np.pi*hz*t)+.18*np.sin(2*np.pi*2*hz*t))*np.exp(-t*6)
        add(discovery,note*envelope(len(t),.035,.14)*.10,.10*i)
    return [
        ("cue_dino_arrival",arrival,"작은 상상 속 공룡의 인사와 숲의 공기", -12.0),
        ("cue_forest",forest,"짧은 잎사귀와 작은 새소리", -18.0),
        ("cue_stream",water,"작은 시냇물의 질감", -18.0),
        ("cue_discovery",discovery,"장면 발견을 알리는 짧은 세 음", -17.0),
    ]


def make_ambience():
    """Original quiet textures for bounded offline mixing, with no animal calls."""
    seconds = 4.0
    t = np.arange(int(seconds * RATE)) / RATE
    water = smooth_noise(seconds, 3402, 220., 2300.)
    water *= .65 + .12*np.sin(2*np.pi*2.1*t) + .08*np.sin(2*np.pi*3.7*t)
    forest = smooth_noise(seconds, 3403, 700., 2600.)
    forest *= .50 + .35*np.sin(np.pi*t/seconds)**2
    return [
        ("ambience_stream", water, "원작 잔물결 질감; 합성 대사 첫 관찰 문장 아래에서만 사용", -18.),
        ("ambience_forest", forest, "원작 부드러운 잎 마찰 질감; 새소리·포효 없음", -18.),
    ]

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output",type=Path,required=True)
    parser.add_argument("--ambience-only",action="store_true",
                        help="Write textures for finite offline mixing, not runtime loops")
    args=parser.parse_args()
    args.output.mkdir(parents=True,exist_ok=False)
    entries=[]
    for key, signal, description, peak_db in (make_ambience() if args.ambience_only else make_cues()):
        signal *= envelope(len(signal))
        peak=np.max(np.abs(signal))
        if peak: signal *= 10**(peak_db/20)/peak
        pcm=np.round(np.clip(signal,-1,1)*32767).astype("<i2")
        path=args.output/(key+".wav")
        with wave.open(str(path),"wb") as f:
            f.setnchannels(1); f.setsampwidth(2); f.setframerate(RATE); f.writeframes(pcm.tobytes())
        entries.append({"id":key,"asset":"story_audio/"+path.name,
            "sha256":hashlib.sha256(path.read_bytes()).hexdigest(),
            "duration_ms":round(len(pcm)*1000/RATE), "description":description,
            "peak_dbfs":round(float(20*np.log10(np.max(np.abs(pcm.astype(float)))/32768)),3),
            "rms_dbfs":round(float(20*np.log10(np.sqrt(np.mean((pcm.astype(float)/32768)**2)))),3)})
    report={"schema_version":1,"sample_rate":RATE,"format":"mono PCM16 WAV",
        "seed":3402 if args.ambience_only else SEED,
        "source":"Original procedural synthesis from this script; no sampled recordings.",
        "kind":"offline_ambience_sources" if args.ambience_only else "scene_cues",
        "script_sha256":hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        "purpose":"Production candidate: bounded scene accents, no loop or required clue conveyed solely by sound.",
        "subjective_listening_verified":False,
        "not_claimed":["real dinosaur vocalization","child preference evidence","physical vehicle loudness validation"],
        "cues":entries}
    (args.output/"manifest.json").write_text(json.dumps(report,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    print(json.dumps({"cues":len(entries),"duration_ms":sum(x["duration_ms"] for x in entries),
        "output":str(args.output)},ensure_ascii=False))

if __name__=="__main__": main()

