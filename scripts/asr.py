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

Cue layout is rebuilt from *word* timestamps rather than taken from whisper's own
segment boundaries: whisper segments are tuned for translation (up to ~44s, whole
paragraphs), which lands on screen as one long line that libass then wraps at
random. Here every cue is cut at punctuation and capped to the width of the
actual video, so a line never breaks in the middle of a phrase.
"""
import argparse
import re
import subprocess
import sys
import tempfile
import wave

# 断行优先级：这些标点跟在上一行末尾
BREAK_AFTER = "。！？!?…；;，,、："
STRONG_END = "。！？!?…；;"
# whisper 在直播 BGM / 静音段上反复吐出来的口水句，出现即整条丢弃
JUNK_PATTERNS = (
    "字幕由", "字幕制作", "谢谢大家观看", "谢谢观看", "感谢观看", "请不吝点赞",
    "订阅转发打赏", "明镜与点点栏目", "dotbydot", "by v.i.p", "本视频由",
)


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


CJK = "\u4e00-\u9fff"


def normalize_punct(text: str) -> str:
    """中文夹着的半角标点换成全角：whisper 常吐 "大家好,欢迎"，烧进画面就是半角逗号，很难看。"""
    t = text
    t = re.sub(rf"(?<=[{CJK}]),(?![0-9])", "，", t)
    t = re.sub(rf",(?=[{CJK}])", "，", t)
    t = re.sub(rf"(?<=[{CJK}])\.(?![0-9])", "。", t)
    t = re.sub(rf"(?<=[{CJK}])\?", "？", t)
    t = re.sub(rf"(?<=[{CJK}])!", "！", t)
    t = re.sub(rf"(?<=[{CJK}]);", "；", t)
    t = re.sub(rf"(?<=[{CJK}]):", "：", t)
    t = re.sub(r"([，。！？；：])\1+", r"\1", t)          # 连续同种标点留一个
    return t.strip(" \t")


def clean(text: str) -> str:
    """去掉替换符（乱码的直接来源）和 whisper 的音效标记。

    英文词之间的空格要留着（whisper 把空格放在词首），否则 " the team" 会被拼成 "theteam"；
    中文词之间本来没有空格，不受影响。
    """
    if not text:
        return ""
    t = text.replace("\ufffd", "")
    t = re.sub(r"\[[^\]]{0,12}(音乐|掌声|笑声|欢呼|旁白|画外音|音效|BGM|music)[^\]]{0,12}\]", "", t, flags=re.I)
    t = re.sub(r"[♪♩𝄞♫♬]+", "", t)
    lead = " " if t[:1] in (" ", "\t", "\n") else ""
    return lead + normalize_punct(t.strip())


def core_len(text: str) -> int:
    """只数汉字和字母数字：标点不算，用来判断这条是不是"其实没说话"。"""
    return len(re.findall(r"[\u4e00-\u9fffA-Za-z0-9]", text))


def is_junk(text: str) -> bool:
    if core_len(text) < 2:
        return True
    low = text.lower().replace(" ", "")
    if any(p in low for p in JUNK_PATTERNS):
        return True
    # 同一个字被复读（"啊啊啊啊啊"）：whisper 在噪声上的典型产物
    uniq = set(re.findall(r"[\u4e00-\u9fff]", text))
    return len(uniq) == 1 and core_len(text) >= 4


def _merge_stray_punct(lines):
    """标点不该独占一行：把"只有标点"的行并回上一行末尾。"""
    out = []
    for ln in lines:
        if out and not re.search(r"[\u4e00-\u9fffA-Za-z0-9]", ln):
            out[-1] = out[-1] + ln
        else:
            out.append(ln)
    return [l for l in out if l]


def _greedy(text, max_chars, bounds=()):
    """兜底折行（文本长到两行装不下时才用）：在词边界上尽量填满一行。"""
    lines = []
    t = text.strip()
    while len(t) > max_chars:
        lo = max(2, max_chars // 2)
        pool = [b for b in bounds if lo <= b <= max_chars] or list(range(lo, max_chars + 1))
        cut = -1
        for i in sorted(pool, reverse=True):     # 行尾落在标点上的词边界最自然
            if i < len(t) and t[i - 1] in BREAK_AFTER:
                cut = i
                break
        if cut < 0:
            sp = t.rfind(" ", lo, max_chars + 1)
            cut = sp + 1 if sp > 0 else min(max(pool), max_chars)
        if cut <= 0 or cut >= len(t):
            cut = max_chars
        lines.append(t[:cut].strip())
        t = t[cut:].strip()
    if t:
        lines.append(t)
    return _merge_stray_punct(lines)


def wrap_lines(text: str, max_chars: int, bounds=(), max_lines: int = 2):
    """把一条字幕折成最多两行，断点只落在词边界上。

    两行装得下时**不按顺序填满第一行**，而是在接近中点的词边界处断开：先填满第一行会把
    "，"之后的半句整条挤到第二行，读起来眼睛要跳；在词中间切（"我们辅 / 助"）更糟。
    """
    t = text.strip()
    if not t:
        return []
    if len(t) <= max_chars:
        return [t]
    cands = sorted({b for b in bounds if 0 < b < len(t)})
    if max_lines >= 2 and len(t) <= max_chars * max_lines:
        mid = len(t) / 2.0
        feasible = [b for b in cands if b <= max_chars and len(t) - b <= max_chars]
        if not feasible:   # 词边界凑不出两侧都不超标的两行，退回任意位置
            feasible = [i for i in range(1, len(t)) if i <= max_chars and len(t) - i <= max_chars]
        if feasible:
            punct = [i for i in feasible if t[i - 1] in BREAK_AFTER]
            pool = punct or feasible
            cut = min(pool, key=lambda i: (abs(i - mid), i))
            return _merge_stray_punct([t[:cut].strip(), t[cut:].strip()])
    return _greedy(t, max_chars, cands)


def assemble(parts):
    """把逐词片段拼成一条字幕文本，并返回每个词结束处的下标（折行只允许切在这些位置上）。"""
    bounds = []
    cur = ""
    for p in parts:
        if not p:
            continue
        cur = re.sub(r"\s+(?=[，。！？、；：,.!?;:])", "", cur + p)   # 中文标点前不留空格
        cur = normalize_punct(cur)                                    # 半角标点换成全角
        bounds.append(len(cur))
    return cur.strip(), bounds


def build_cues(words, max_chars, max_lines=2, min_dur=0.7, tail=0.25):
    """把逐词时间轴重切成字幕条。

    words: [(start, end, text)]，已按时间升序。
    断条规则：凑满两行就断；已经说满一行且刚过一个停顿点（逗号/句号）也断，
    这样一句话不会被劈成两条，一条也不会挤成三行。
    """
    cues = []
    cur = []

    def flush():
        if not cur:
            return
        text, bounds = assemble(w[2] for w in cur)
        if not is_junk(text):
            cues.append({"start": cur[0][0], "end": cur[-1][1],
                         "lines": wrap_lines(text, max_chars, bounds, max_lines)})
        cur.clear()

    for w in words:
        add = len(w[2].strip())
        used = sum(len(x[2].strip()) for x in cur)
        if cur and used + add > max_chars * max_lines:
            flush()
            used = 0
        cur.append(w)
        used += add
        stripped = w[2].strip()
        last = stripped[-1] if stripped else ""
        # 说满一行后遇到任何停顿就换条；整句说完（。！？）半行也换条
        if used >= max_chars and last in BREAK_AFTER:
            flush()
        elif used * 2 >= max_chars and last in STRONG_END:
            flush()
    flush()

    # 时长修整：给每条留出读完的时间，但不许压到下一条头上
    for i, c in enumerate(cues):
        nxt = cues[i + 1]["start"] if i + 1 < len(cues) else None
        c["end"] = min(c["end"] + tail, nxt - 0.05) if nxt is not None else c["end"] + tail
        if c["end"] - c["start"] < min_dur:
            c["end"] = c["start"] + min_dur
    return cues


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
    ap.add_argument("--hotwords", default=None,
                    help="domain words (anchor / room / game terms) to bias the decoder")
    ap.add_argument("--width", type=int, default=0, help="video width px (0 = unknown)")
    ap.add_argument("--height", type=int, default=0, help="video height px (0 = unknown)")
    ap.add_argument("--max-chars", dest="max_chars", type=int, default=0,
                    help="hard cap of characters per subtitle line (0 = derive from --width/--height)")
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

    # 每行字数：按"大字"档的字高（约为画面高的 6.6%，即 force_style FontSize=19 / PlayResY 288）
    # 算一行能塞下几个全角字，再钳进可读区间——竖屏画面窄，行必须跟着变短
    if args.max_chars > 0:
        max_chars = args.max_chars
    elif args.width > 0 and args.height > 0:
        glyph = max(1.0, args.height * 0.066)
        max_chars = int(min(20, max(8, (args.width * 0.88) / glyph)))
    else:
        max_chars = 16

    try:
        model = WhisperModel(args.model, device=args.device, compute_type=args.compute)
        # 中文不设提示时 whisper 常吐繁体，烧进成片里很怪；可用 --prompt 覆盖
        prompt = args.prompt or ("以下是简体中文的普通话直播解说，请逐字转写并正确断句。"
                                 if args.language == "zh" else None)
        segments, info = model.transcribe(
            samples,
            language=(args.language or None),
            beam_size=5,
            # 不照抄上一句：直播里一句话听错，condition_on_previous_text 会让后面整段跟着错下去
            condition_on_previous_text=False,
            word_timestamps=True,
            vad_filter=True,
            vad_parameters=dict(min_silence_duration_ms=400),
            initial_prompt=prompt,
            hotwords=args.hotwords or None,
            # 低于这些置信度的段落直接丢：宁可少一条字幕，也不要烧一行乱码或口水话
            no_speech_threshold=0.55,
            log_prob_threshold=-1.0,
            compression_ratio_threshold=2.3,
        )
    except Exception as e:  # noqa: BLE001
        print(f"ERROR transcribe: {e!r}")
        return 4

    words = []
    dropped = 0
    for seg in segments:
        # 整段置信度太差就丢掉（whisper 的 avg_logprob 是几何平均对数概率）
        if getattr(seg, "avg_logprob", 0.0) < -1.35 or getattr(seg, "no_speech_prob", 0.0) > 0.8:
            dropped += 1
            continue
        for w in (seg.words or []):
            wt = clean(w.word or "")
            if not wt:
                continue
            s, e = float(w.start), float(w.end)
            if e <= s:
                e = s + 0.2
            words.append((s, e, wt))
    words.sort(key=lambda x: x[0])

    cues = build_cues(words, max_chars)

    if args.output:
        out_lines = []
        for i, c in enumerate(cues, start=1):
            out_lines.append(str(i))
            out_lines.append(f"{fmt_ts(c['start'])} --> {fmt_ts(c['end'])}")
            out_lines.append("\n".join(c["lines"]))
            out_lines.append("")
        with open(args.output, "w", encoding="utf-8", newline="\n") as f:
            f.write("\n".join(out_lines))

    if args.timeline_out:
        merged = []
        for c in cues:
            s, e = c["start"], c["end"]
            if merged and s - merged[-1][1] <= args.gap:
                merged[-1][1] = max(merged[-1][1], e)
            else:
                merged.append([s, e])
        with open(args.timeline_out, "w", encoding="utf-8", newline="\n") as f:
            for s, e in merged:
                f.write(f"{s:.2f}\t{e:.2f}\n")
        print(f"OK speech_windows={len(merged)}")

    print(f"OK cues={len(cues)} words={len(words)} dropped_segments={dropped} "
          f"max_chars={max_chars} lang={getattr(info, 'language', args.language)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
