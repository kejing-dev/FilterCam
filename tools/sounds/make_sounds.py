#!/usr/bin/env python3
"""Builds res/raw/*.wav (mono 44.1 kHz 16-bit, peak -1.5 dBFS).

Shutters are CC0 recordings from freesound.org (sources in apps/filter-cam/build/sound-sources.md), cut to the shutter
and normalised; tick / chime / beep / delete are short synthesised UI cues. Also writes the loudness evidence: every
sound back to back at the default (mid) volume step.

    python tools/sounds/make_sounds.py <dir with the downloaded *-hq.mp3> <loudness wav out>
"""
import os
import sys

import numpy as np
import soundfile as sf

SR = 44100
HERE = os.path.dirname(os.path.abspath(__file__))
RAW = os.path.join(HERE, "..", "..", "app", "src", "main", "res", "raw")
PEAK = 10 ** (-1.5 / 20)

# file -> (freesound id, start s, length s)
SHUTTERS = {
    "shutter_mech": ("520684", 0.0, 1.0),  # Contarex mechanical SLR shutter (Tonik1105)
    "shutter_elec": ("271010", 0.0, 0.8),  # Olympus digital compact quick snap (Kodack)
    "shutter_instant": ("444682", 0.0, 2.6),  # Polaroid 650 shutter and print motor (larspriebe)
}


def load(src_dir, fid):
    path = next(os.path.join(src_dir, f) for f in os.listdir(src_dir) if f.startswith(fid + "_"))
    d, sr = sf.read(path, always_2d=True)
    d = d.mean(axis=1)
    if sr != SR:
        t = np.arange(int(len(d) * SR / sr)) / SR
        d = np.interp(t, np.arange(len(d)) / sr, d)
    return d


def cut(d, start, length):
    # start at the first transient after `start`
    s = int(start * SR)
    env = np.abs(d[s:])
    onset = s + int(np.argmax(env > env.max() * 0.08)) - int(0.005 * SR)
    seg = d[max(0, onset): max(0, onset) + int(length * SR)].copy()
    fade = int(0.08 * SR)
    seg[-fade:] *= np.linspace(1, 0, fade)
    seg[: int(0.003 * SR)] *= np.linspace(0, 1, int(0.003 * SR))
    return seg


def norm(d):
    return d / (np.abs(d).max() + 1e-9) * PEAK


def tone(freqs, dur, decay, amp=1.0):
    t = np.arange(int(dur * SR)) / SR
    s = sum(np.sin(2 * np.pi * f * t) * a for f, a in freqs)
    env = np.exp(-t / decay) * np.minimum(1, t / 0.004)
    return s * env * amp


def write(name, d):
    os.makedirs(RAW, exist_ok=True)
    sf.write(os.path.join(RAW, name + ".wav"), norm(d), SR, subtype="PCM_16")
    return norm(d)


def main():
    src, loud_out = sys.argv[1], sys.argv[2]
    out = []
    for name, (fid, start, length) in SHUTTERS.items():
        out.append(write(name, cut(load(src, fid), start, length)))
    out.append(write("tick", tone([(2400, 1.0), (4800, 0.3)], 0.06, 0.012)))
    out.append(write("chime", np.concatenate([tone([(1318, 1), (2636, 0.2)], 0.18, 0.08), tone([(1760, 1), (3520, 0.2)], 0.45, 0.18)])))
    out.append(write("beep", tone([(1000, 1.0)], 0.12, 0.09)))
    out.append(write("delete", tone([(330, 1.0), (165, 0.5)], 0.22, 0.07)))
    # loudness evidence: each cue at the default volume step (0.75), 0.4 s apart
    gap = np.zeros(int(0.4 * SR))
    seq = np.concatenate([np.concatenate([d * 0.75, gap]) for d in out] * 2)
    sf.write(loud_out, seq, SR, subtype="PCM_16")
    print("ok", len(out), "sounds,", round(len(seq) / SR, 1), "s evidence")


if __name__ == "__main__":
    main()
