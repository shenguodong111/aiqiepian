#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""ASR bridge: transcribe an audio/video file to SRT using faster-whisper.

Invoked by the Java backend via ProcessBuilder. All logging is ASCII to avoid
Windows GBK console errors; the SRT itself is written as UTF-8 (what ffmpeg's
subtitles filter expects).

Audio is decoded in-process (wave + numpy) and handed to transcribe as a float32
sample array, which bypasses PyAV entirely (faster-whisper's av.open path is
incompatible with the installed av build). Non-16k-mono input is normalized to
16k mono via ffmpeg first.
"""
import argparse
import subprocess
import sys
import tempfile
import wave


def fmt_ts(t: float) -> str:
    ms = int(round((t % 1.0) * 1000))
    s = int(t)
    h, m, sec = s // 3600, (s % 3600) // 60, s % 60
    if ms >= 1000:
        ms -= 1000
        sec += 1
        if sec >= 60:
            sec -= 60
            m += 1
            if m >= 60:
                m -= 60
                h += 1
    return f"{h:02d}:{m:02d}:{sec:02d},{ms:03d}"


def to_16k_mono_wav(src: str, dst: str) -> bool:
    try:
        subprocess.run(
            ["ffmpeg", "-hide_banner", "-loglevel", "error", "-i", src,
             "-vn", "-ac", "1", "-ar", "16000", "-y", dst],
            check=True, timeout=180,
        )
        return True
    except Exception as e:  # noqa: BLE001
        print(f"ERROR ffmpeg normalize: {e!r}")
        return False


def read_wav(path: str):
    """Return float32 mono samples normalized to [-1,1]; raise if not 16k mono PCM16."""
    import numpy as np
    with wave.open(path, "rb") as w:
        if w.getnchannels() != 1 or w.getsampwidth() != 2 or w.getframerate() != 16000:
            raise ValueError(
                f"expected 16k mono pcm16, got ch={w.getnchannels()} "
                f"width={w.getsampwidth()} rate={w.getframerate()}")
        raw = w.readframes(w.getnframes())
    data = np.frombuffer(raw, dtype=np.int16).astype(np.float32) / 32768.0
    return data


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", required=True, help="audio/video path")
    ap.add_argument("--output", help="output .srt path (optional if --timeline-out set)")
    ap.add_argument("--timeline-out", dest="timeline_out",
                    help="optional path: write merged speech windows as 'start<TAB>end' lines (seconds)")
    ap.add_argument("--gap", type=float, default=2.0,
                    help="merge speech segments separated by less than this many seconds (default 2.0)")
    ap.add_argument("--model", default="small")
    ap.add_argument("--language", default="zh")
    ap.add_argument("--device", default="cpu")
    ap.add_argument("--compute", default="int8")
    ap.add_argument("--prompt", default=None,
                    help="whisper initial_prompt; defaults to a simplified-Chinese cue for language=zh")
    args = ap.parse_args()

    if not args.output and not args.timeline_out:
        print("ERROR need --output and/or --timeline-out")
        return 1

    try:
        from faster_whisper import WhisperModel
    except Exception as e:  # noqa: BLE001
        print(f"ERROR import faster_whisper: {e!r}")
        return 2

    tmp = None
    try:
        wav_path = args.input
        try:
            samples = read_wav(wav_path)
        except Exception:
            # 输入非 16k mono wav（例如 mp4 或立体声）：归一化后再读
            tmp = tempfile.NamedTemporaryFile(suffix=".wav", delete=False).name
            if not to_16k_mono_wav(args.input, tmp):
                return 3
            samples = read_wav(tmp)
    except Exception as e:  # noqa: BLE001
        print(f"ERROR decode audio: {e!r}")
        return 3
    finally:
        if tmp:
            try:
                import os
                os.remove(tmp)
            except Exception:
                pass

    try:
        model = WhisperModel(args.model, device=args.device, compute_type=args.compute)
        # 中文不设提示时 whisper 常吐繁体，烧进成片里很怪；可用 --prompt 覆盖
        prompt = args.prompt or ("以下是简体中文的普通话直播解说。" if args.language == "zh" else None)
        segments, info = model.transcribe(
            samples,
            language=(args.language or None),
            beam_size=1,
            vad_filter=True,
            initial_prompt=prompt,
        )
    except Exception as e:  # noqa: BLE001
        print(f"ERROR transcribe: {e!r}")
        return 4

    lines = []
    idx = 0
    segs = []  # (start, end) of non-empty speech segments, for timeline merging
    for seg in segments:
        text = (seg.text or "").strip()
        if not text:
            continue
        segs.append((float(seg.start), float(seg.end)))
        if args.output:
            idx += 1
            lines.append(str(idx))
            lines.append(f"{fmt_ts(seg.start)} --> {fmt_ts(seg.end)}")
            lines.append(text)
            lines.append("")

    if args.output:
        with open(args.output, "w", encoding="utf-8", newline="\n") as f:
            f.write("\n".join(lines))

    if args.timeline_out:
        merged = []
        for s, e in sorted(segs):
            if merged and s - merged[-1][1] <= args.gap:
                merged[-1][1] = max(merged[-1][1], e)
            else:
                merged.append([s, e])
        with open(args.timeline_out, "w", encoding="utf-8", newline="\n") as f:
            for s, e in merged:
                f.write(f"{s:.2f}\t{e:.2f}\n")
        print(f"OK speech_windows={len(merged)}")

    print(f"OK segments={idx} lang={getattr(info, 'language', args.language)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
