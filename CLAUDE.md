# Project Context for AI Assistants

## What this project is

A locally-running Telegram bot that offers three features via an inline keyboard menu:
- **Audio → Text** — transcribes voice messages / audio files (Faster-Whisper)
- **Text → Image** — generates images from a prompt (Stable Diffusion via Diffusers)
- **Video Download** — downloads videos from YouTube and VK in selectable quality (yt-dlp)

Everything runs in Docker Compose on a single machine. No cloud APIs, no external AI calls.

---

## Repository layout

```
telegram-multi-service/
├── CLAUDE.md                    ← this file
├── README.md                    ← user-facing setup guide
├── ARCHITECTURE.md              ← component deep-dive
├── docker-compose.yml           ← orchestrates all 4 services
├── .env                         ← TELEGRAM_BOT_TOKEN (git-ignored)
├── telegram-bot-java/           ← Spring Boot 3 long-polling bot (Java 17 source / Java 21 runtime)
│   ├── build.gradle
│   ├── Dockerfile
│   └── src/main/java/com/example/voicebot/
│       ├── VoiceBotApplication.java
│       ├── bot/
│       │   ├── VoiceBot.java                     ← main update consumer
│       │   └── BotLifecycle.java
│       ├── command/
│       │   ├── Command.java                       ← interface
│       │   ├── StartCommand.java                  ← sends main-menu keyboard
│       │   └── ClearCommand.java                  ← deletes all tracked messages
│       ├── handler/
│       │   ├── MessageHandler.java                ← interface
│       │   ├── AudioTranscriptionHandler.java     ← downloads audio → calls whisper-service
│       │   ├── ImageGenHandler.java               ← sends prompt → calls image-gen-service
│       │   └── VideoDownloadHandler.java          ← sends URL → calls video-dl-service /info → shows quality keyboard
│       ├── callback/
│       │   ├── CallbackHandler.java               ← interface
│       │   ├── AudioTranscriptionCallback.java    ← sets AWAITING_AUDIO state
│       │   ├── ImageGenCallback.java              ← sets AWAITING_IMAGE_PROMPT state
│       │   ├── VideoDownloadCallback.java         ← sets AWAITING_VIDEO_URL state
│       │   └── VideoQualityCallback.java          ← prefix "vdl:", calls video-dl-service /download
│       ├── session/
│       │   ├── UserState.java                     ← enum: IDLE / AWAITING_AUDIO / AWAITING_IMAGE_PROMPT / AWAITING_VIDEO_URL / AWAITING_VIDEO_QUALITY
│       │   └── UserSessionService.java            ← ConcurrentHashMap of chatId → state + message IDs + video URL
│       ├── config/
│       │   └── BotConfig.java                     ← Spring beans: TelegramBotsApi, three OkHttpClients
│       └── util/
│           └── TelegramSender.java                ← thin wrapper around TelegramClient
├── whisper-service/             ← FastAPI (Python 3.11), faster-whisper
│   ├── app.py
│   ├── requirements.txt
│   └── Dockerfile
├── image-gen-service/           ← FastAPI (Python 3.11), diffusers + Stable Diffusion
│   ├── app.py
│   ├── requirements.txt
│   └── Dockerfile
└── video-dl-service/            ← FastAPI (Python 3.11), yt-dlp + FFmpeg
    ├── app.py
    ├── requirements.txt
    └── Dockerfile
```

---

## Tech stack at a glance

| Layer | Technology |
|---|---|
| Telegram integration | `telegrambots-longpolling 7.11` |
| Bot framework | Spring Boot 3.3.4, Java 17 source |
| HTTP calls (Java) | OkHttp 3 |
| Speech-to-text | Faster-Whisper (default: `small` model) |
| Image generation | Diffusers `StableDiffusionPipeline` + LCM-LoRA (default: `Lykon/dreamshaper-8`, 8 steps) |
| Video download | yt-dlp (YouTube, VK, 1000+ sites) + FFmpeg |
| Python services | FastAPI + Uvicorn |
| Orchestration | Docker Compose v3.8 |

---

## Key design decisions

1. **Long-polling** — bot uses `LongPollingSingleThreadUpdateConsumer`; no webhook, no public IP required.
2. **Three OkHttpClient beans** — `imageGenHttpClient` (620 s) for CPU image generation, `videoDlHttpClient` (320 s) for video downloads, default (120 s) for everything else.
3. **UserSessionService** — `ConcurrentHashMap<Long, UserState>` tracks per-chat state; additional maps track bot-sent message IDs (for `/clear`) and video URLs (for the two-step download flow).
4. **State machine** — `IDLE → AWAITING_AUDIO | AWAITING_IMAGE_PROMPT | AWAITING_VIDEO_URL → AWAITING_VIDEO_QUALITY → IDLE`. Handlers only act when the user is in the correct state.
5. **Prefix callback routing** — `VoiceBot` first tries exact match for callback data, then prefix match (used by `VideoQualityCallback` with `"vdl:"` prefix for dynamic format IDs).
6. **Auto-translate** — `image-gen-service` uses `deep-translator` to convert non-English prompts to English before passing to SD.
7. **Anti-ban** — `video-dl-service` supports rate limiting (`RATE_LIMIT_DELAY`), optional proxy (`PROXY_URL`), and optional cookies (`COOKIES_FILE`) to avoid YouTube/VK bans.
8. **Large file handling** — files ≤ 50 MB are sent directly via Telegram; larger files are served as temporary download links (`GET /files/{id}/{name}`) valid for 10 minutes (configurable via `FILE_TTL_SECONDS`). Port 8002 is exposed to the host for this purpose.
9. **No other external ports** — whisper-service and image-gen-service communicate only over the internal Docker network.

---

## Build & run

```bash
# 1. Build the Spring Boot JAR (required before docker build)
cd telegram-bot-java && ./gradlew bootJar --no-daemon && cd ..

# 2. Start everything (first run downloads ~4.5 GB of models)
docker compose up -d --build

# 3. Rebuild only the bot after Java changes
docker compose build telegram-bot && docker compose restart telegram-bot
```

---

## Environment variables

| Variable | Where | Purpose |
|---|---|---|
| `TELEGRAM_BOT_TOKEN` | `.env` | Bot token from @BotFather |
| `WHISPER_MODEL` | docker-compose | `tiny`/`base`/`small`/`medium`/`large-v3` |
| `WHISPER_DEVICE` | docker-compose | `cpu` or `cuda` |
| `WHISPER_COMPUTE_TYPE` | docker-compose | `int8` (CPU) / `float16` (GPU) |
| `IMAGE_MODEL` | docker-compose | Any SD 1.5-compatible HuggingFace model ID |
| `IMAGE_LORA` | docker-compose | LCM-LoRA model ID for fast inference |
| `IMAGE_STEPS` | docker-compose | Denoising steps (8 default, 20 for quality) |
| `IMAGE_GUIDANCE_SCALE` | docker-compose | CFG scale (1.5 for LCM, 7–9 for standard) |
| `IMAGE_WIDTH/HEIGHT` | docker-compose | Output resolution (512×512 default) |
| `RATE_LIMIT_DELAY` | docker-compose | Seconds between yt-dlp requests (default 5, anti-ban) |
| `PROXY_URL` | docker-compose | Optional SOCKS5/HTTP proxy for video downloads |
| `COOKIES_FILE` | docker-compose | Optional path to cookies.txt (for auth content) |
| `PUBLIC_URL` | docker-compose | Public base URL for download links (default `http://localhost:8002`) |
| `FILE_TTL_SECONDS` | docker-compose | How long download links stay valid (default 600 = 10 min) |

---

## Service endpoints

| Service | Port | Endpoints |
|---|---|---|
| whisper-service | 8000 | `GET /health`, `POST /transcribe` (multipart file) |
| image-gen-service | 8001 | `GET /health`, `POST /generate` (JSON: prompt, steps, width, height) |
| video-dl-service | 8002 (exposed) | `GET /health`, `POST /info`, `POST /download` → JSON, `GET /files/{id}/{name}` → file |
| telegram-bot | 8080 (internal) | none — long-polling only |

---

## Error messages (Russian)

The bot sends Russian error messages:
- Transcription failure: `"Ошибка при распознавании, попробуй ещё раз."`
- Image gen failure: `"Ошибка при генерации картинки, попробуйте ещё раз."`
- Video download failure: `"Ошибка при скачивании видео, попробуйте ещё раз."` (+ specific messages for private/geo-blocked/rate-limited videos)
- Delete errors are silently ignored (message may already be gone).

---

## Adding a new feature (checklist)

1. Add a new `UserState` value if the feature needs a waiting state.
2. Create a `CallbackHandler` (sets the new state, sends a prompt to the user).
3. Create a `MessageHandler` (checks state, calls external service, returns to IDLE).
4. Register the callback data string in `StartCommand` keyboard.
5. If the external service needs a long timeout, add a new `OkHttpClient` bean in `BotConfig`.
