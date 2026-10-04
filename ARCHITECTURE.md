# Architecture

## Overview

Four Docker services communicate over an internal network. The Spring Boot bot is the sole entry point — it talks to Telegram via long-polling and calls the three Python microservices over HTTP.

```
Telegram API
     │  (long-polling)
     ▼
┌─────────────────────────────────────┐
│          telegram-bot               │
│          Spring Boot 3              │
│                                     │
│  VoiceBot ──► Commands              │
│     │         /start  /clear        │
│     │                               │
│     ├──► CallbackHandlers           │
│     │    (set user state)           │
│     │                               │
│     └──► MessageHandlers            │
│          (consume user state)       │
└────────┬──────────────┬─────────────┘
         │ HTTP POST    │ HTTP POST     │ HTTP POST
         │ /transcribe  │ /generate     │ /info, /download
         ▼              ▼               ▼
  ┌─────────────┐  ┌──────────────┐  ┌──────────────────┐
  │whisper-     │  │image-gen-    │  │video-dl-service  │
  │service      │  │service       │  │                  │
  │FastAPI :8000│  │FastAPI :8001 │  │FastAPI :8002     │
  │             │  │              │  │                  │
  │faster-      │  │diffusers     │  │yt-dlp + FFmpeg   │
  │whisper      │  │SD + LCM-LoRA│  │(YT, VK, 1000+)  │
  └─────────────┘  └──────────────┘  └──────────────────┘
```

---

## telegram-bot-java

### Update routing (`VoiceBot.java`)

```
consume(Update)
  ├─ has CallbackQuery?  → find matching CallbackHandler by callback data (exact match, then prefix match)
  ├─ has text message?   → find matching Command by text (/start, /clear)
  └─ has any message?    → iterate all MessageHandlers (each self-filters by state)
```

### User state machine

```
       /start
         │
         ▼
┌──────────────┐    button "🎙"    ┌───────────────────┐
│     IDLE     │ ──────────────► │  AWAITING_AUDIO    │
│              │ ◄──────────────  └───────────────────┘
│              │  transcription        sends audio
│              │     done
│              │
│              │    button "🖼"    ┌───────────────────────────┐
│              │ ──────────────► │ AWAITING_IMAGE_PROMPT      │
│              │ ◄──────────────  └───────────────────────────┘
│              │  image generated     sends text prompt
│              │
│              │    button "📹"    ┌───────────────────┐   picks quality   ┌───────────────────────────┐
│              │ ──────────────► │ AWAITING_VIDEO_URL │ ──────────────► │ AWAITING_VIDEO_QUALITY     │
│              │ ◄──────────────  └───────────────────┘                  └───────────────────────────┘
└──────────────┘  video sent          sends URL                              selects format
```

Both handlers return the user to `IDLE` after a successful (or failed) response.

### Session service (`UserSessionService.java`)

- `ConcurrentHashMap<Long chatId, UserState>` — current state per chat
- `ConcurrentHashMap<Long chatId, List<Integer> messageIds>` — all message IDs sent by the bot, used by `/clear`
- `ConcurrentHashMap<Long chatId, String videoUrl>` — video URL for the two-step download flow

### HTTP clients (`BotConfig.java`)

| Bean | Connect timeout | Read timeout | Used by |
|---|---|---|---|
| default `OkHttpClient` | 10 s | 120 s | standard Telegram API calls, `VideoDownloadHandler` (/info) |
| `imageGenHttpClient` | 10 s | 600 s | `ImageGenHandler` (CPU SD takes 3–5 min) |
| `videoDlHttpClient` | 10 s | 300 s | `VideoQualityCallback` (/download, video can take minutes) |

---

## whisper-service

**Stack:** FastAPI, `faster-whisper`, Python 3.11, FFmpeg

**Endpoints:**

```
GET  /health       → { model, device, compute_type }
POST /transcribe   → multipart: file=<audio bytes>
                  ← { text: "...", language: "ru" }
```

**Flow:**
1. Receive audio file (any format FFmpeg supports)
2. Save to temp file
3. Run `WhisperModel.transcribe(path)` — returns segments
4. Join segment texts, return JSON

**Model loaded once** at startup; subsequent calls reuse the in-memory model.

---

## image-gen-service

**Stack:** FastAPI, `diffusers`, `torch` (CPU), Python 3.11

**Endpoints:**

```
GET  /health      → { model, device }
POST /generate    → JSON: { prompt, steps, width, height }
                 ← PNG image (binary, Content-Type: image/png)
```

**Flow:**
1. Receive prompt text
2. Auto-translate to English via `deep-translator` if needed
3. Apply hard-coded negative prompt (low quality artifacts list)
4. Run `StableDiffusionPipeline.__call__(prompt, num_inference_steps, ...)`
5. Return PNG bytes

**Optimisations:**
- LCM-LoRA adapter loaded on top of base model → 8 steps instead of 20–50
- `enable_attention_slicing()` → lower peak RAM
- Guidance scale 1.5 (LCM works best at low CFG)

**Model loaded once** at startup into CPU memory (~4 GB).

---

## video-dl-service

**Stack:** FastAPI, `yt-dlp`, FFmpeg, Python 3.11

**Endpoints:**

```
GET  /health                → { status, rate_limit_delay, proxy, cookies, public_url }
POST /info                  → JSON: { url }
                           ← { title, duration, thumbnail, formats: [{ format_id, label, filesize_approx, fits_telegram }] }
POST /download              → JSON: { url, format_id }
                           ← JSON: { file_id, filename, filesize, fits_telegram, download_url, internal_url, is_audio, expires_in_seconds }
GET  /files/{file_id}/{name} → serves the downloaded file (temporary, expires after FILE_TTL_SECONDS)
```

**Flow (/info):**
1. Extract video metadata with `yt-dlp.extract_info(url, download=False)`
2. Probe each quality tier (best, 720p, 480p, 360p, audio) to estimate file sizes
3. Label formats > 50 MB with "📎 ссылка" (will be sent as a download link)
4. Return structured format list

**Flow (/download):**
1. Download video in the requested quality tier
2. FFmpeg merges video+audio streams if needed
3. Store file in temp directory, register in file registry with TTL
4. Return JSON with metadata + public download URL (no size limit)
5. Background thread cleans up expired files every 60 seconds

**Flow (file serving):**
- `GET /files/{id}/{name}` serves files from the registry
- Files auto-expire after `FILE_TTL_SECONDS` (default: 10 minutes)
- Port 8002 is exposed to the host so users can access download links

**Anti-ban features:**
- `RATE_LIMIT_DELAY` — seconds between requests (default: 5)
- `PROXY_URL` — optional SOCKS5/HTTP proxy
- `COOKIES_FILE` — optional cookies.txt for authenticated access
- Automatic retry (3 attempts)
- Classifies errors into user-friendly Russian messages (private, age-restricted, geo-blocked, rate-limited)

**Quality tiers:** best, 720p, 480p, 360p, audio-only. Each tier uses a format selector string that falls back to the closest available format.

---

## Docker Compose networking

All services share the default bridge network created by Compose. Service names are DNS hostnames:

| From | To | URL |
|---|---|---|
| telegram-bot | whisper-service | `http://whisper-service:8000/transcribe` |
| telegram-bot | image-gen-service | `http://image-gen-service:8001/generate` |
| telegram-bot | video-dl-service | `http://video-dl-service:8002/info`, `/download` |

No ports are exposed to the host by default.

### Volumes (model cache)

| Volume | Mounted at | Content |
|---|---|---|
| `whisper-cache` | `/root/.cache/huggingface` | Faster-Whisper model (~250 MB) |
| `image-gen-cache` | `/root/.cache/huggingface` | SD base model + LoRA (~4 GB) |
| `video-dl-cache` | `/root/.cache` | yt-dlp cache |

Models are downloaded on first startup; subsequent restarts reuse the cache.

---

## Sequence diagrams

### Audio transcription

```
User          telegram-bot          whisper-service
 │                 │                       │
 │──voice msg──►  │                       │
 │                 │──download audio──►   │ (Telegram file API)
 │                 │◄──audio bytes───────  │
 │                 │                       │
 │                 │──POST /transcribe──►  │
 │                 │◄──{ text, lang }───   │
 │                 │                       │
 │◄──text reply──  │                       │
```

### Image generation

```
User          telegram-bot          image-gen-service
 │                 │                       │
 │──text prompt──► │                       │
 │                 │──POST /generate────►  │
 │                 │   (waits up to 620s)  │
 │                 │◄──PNG bytes─────────  │
 │                 │                       │
 │◄──photo reply── │                       │
```

### Video download (two-step, small file ≤ 50 MB)

```
User          telegram-bot          video-dl-service
 │                 │                       │
 │──video URL───► │                       │
 │                 │──POST /info────────►  │
 │                 │◄──{ formats[] }─────  │
 │◄──quality kbd── │                       │
 │                 │                       │
 │──clicks 480p──► │                       │
 │                 │──POST /download────►  │
 │                 │◄──{ download_url }──  │
 │                 │──GET /files/id/name─► │
 │                 │◄──video bytes────────  │
 │◄──video file── │                       │
```

### Video download (two-step, large file > 50 MB)

```
User          telegram-bot          video-dl-service          User's browser
 │                 │                       │                       │
 │──video URL───► │                       │                       │
 │                 │──POST /info────────►  │                       │
 │                 │◄──{ formats[] }─────  │                       │
 │◄──quality kbd── │                       │                       │
 │                 │                       │                       │
 │──clicks best──► │                       │                       │
 │                 │──POST /download────►  │                       │
 │                 │◄──{ download_url }──  │                       │
 │◄──📎 link────── │                       │                       │
 │                 │                       │                       │
 │─────────────────┼────opens link─────────┼──GET /files/id/name─► │
 │                 │                       │◄──video stream────────│
```
