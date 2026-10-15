# AI Live-Stream Auto Clipper (standalone)

[中文 README](./README.md) · 操作手册见 [`使用手册.md`](./使用手册.md)

A single-machine, self-hosted tool that automates live-stream content production (inspired by aiqiepian.cn):
**monitor rooms → auto-record on live start (ffmpeg) → analyze audio/video/danmaku for highlight clips → auto-render (cut + cover) → rule-based review (auto-pass / manual / reject) → publish to Bilibili.**

## Stack
- **backend/** — Spring Boot 3.2.5 + MyBatis-Plus + H2 (dual engine: `real` ffmpeg/ASR + `mock`)
- **frontend/** — Vue 3 + Vite console (9 pages, dark theme, hash routing)
- **mock-server/** — single-file Node mock backend (full demo without a JDK; same API contract)
- **scripts/asr.py** — faster-whisper ASR bridge (subtitles + speech timeline)
- **media/** — recordings / clips / subtitles / covers / works output (git-ignored)

## Pipeline (real engine)
Requires JDK 17 and `ffmpeg`; Maven / Node.js are only needed to build. `env.bat` auto-detects the JDK, Maven, ffmpeg and Ollama folders, so the scripts work on any machine without editing — override with `AIQIPIAN_JDK` / `AIQIPIAN_MVN` / `AIQIPIAN_FFMPEG` if your layout is unusual.

Everyday use (Windows): double-click `build-package.bat` once to produce the single embedded jar, then `launch-app.bat` (the desktop icon) to start Ollama + backend + the app window on `http://localhost:8090`. `stop.bat` shuts it down gracefully. Rebuild the frontend through `build-package.bat` — a bare `mvn package` does **not** rebuild the UI and silently ships the previous bundle.

```bash
cd backend
mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=8090 --engine.mode=real"
cd frontend
VITE_API_TARGET=http://localhost:8090 npm install && npm run dev   # http://localhost:5173
```

The H2 database path is relative (`./data/aiqiepian.mv.db`), so the working directory picks the database. The scripts run from `backend/`; launching the jar from the repo root opens a different, empty database.

Usage:
1. Settings → Publish → **Scan to log in**: a QR code appears, confirm it in the Bilibili mobile app and the cookies are stored locally and the account bound (no more copying SESSDATA / bili_jct / DedeUserID by hand; the tool re-checks the session every 30 min and tries to refresh it). Then set the post category `tid` and save.
2. Publish Center → "Verify Cookie" (shows your Bilibili nickname when valid).
3. Rooms → add a room (paste a live-room URL, enable monitoring).
4. Keep "Auto run" on: live start auto-records → auto-analyzes into clips → high-score clips auto-render → threshold review → approved works upload to Bilibili and the post status is read back.

### Capabilities (verified end-to-end)
- Monitor / record / analyze / render: validated against real Bilibili live rooms.
- Upload: biliup-compatible `upos/bup` chunked protocol, real submissions confirmed.
- Speech-aware selection: rooms with sparse/no danmaku use Whisper VAD speech windows + scene cuts to pick segments.
- Auto highlight reel: each finished recording auto-produces one 5–20 min highlight cut.
- ASR subtitles: offline faster-whisper burns dialogue subtitles into rendered clips.
- Platform coverage: Bilibili (monitor + record + danmaku + publish), Douyu (monitor + record + danmaku via a local Python sidecar), Huya (monitor + record, re-connecting stream; no danmaku). Anything else — Douyin included — is treated as offline.

## Setting up on another machine
- **Required**: JDK 17, ffmpeg (must be reachable — `env.bat` puts it on PATH, Java calls it by name). Maven + Node.js only when building.
- **Recommended**: a Python 3.12 venv with `faster-whisper` (rendered subtitles; CPU int8 is enough, no CUDA) and `websocket-client` (Douyu danmaku; the same venv is reused), plus `ollama` with `qwen2.5:3b` (AI title/description polish). Missing any of these degrades the feature instead of breaking the pipeline.
- **Data**: cookies never leave the machine — the config export deliberately excludes them, so scan the QR code again on the new box. Rooms and settings move via Settings → Export / Import JSON; `backend/media/` and `backend/data/` are local files, copy them if you want the recordings too.


## Mock demo (no JDK)
```bash
node mock-server/server.mjs            # listens on 8080 (or PORT=8081)
cd frontend && VITE_API_TARGET=http://localhost:8081 npm run dev
```
All six stages run automatically at an accelerated pace — useful for demos and frontend development.

## Packaging & desktop app
- `build-package.bat` — builds the frontend, embeds `dist` into backend static, produces an executable jar (single process serving UI + API).
- `launch-app.bat` / desktop icon — starts the jar hidden and opens an Edge app window.
- CI: `.github/workflows/build-jar.yml` builds the jar on every push to `main` and uploads it as an artifact.

## Development conventions
- The API contract is authoritative in `直播自动切片工具-单机版详细需求文档.md` §6 + `mock-server/server.mjs`; keep mock / Spring / frontend in sync.
- `mock` vs `real` is switched by the `--engine.mode` launch argument — no business-code or frontend changes.
- Page transitions are pure CSS (do not switch back to Vue `<Transition>`; rAF freezes in hidden tabs and hangs the router).

## Known limits
- Publishing targets Bilibili only (matching the reference product).
- A crash mid-`UPLOADING` re-uploads on restart (idempotency pending).
