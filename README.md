# Telegram AI Bot

A locally-running Telegram bot with AI features, powered by Docker Compose.

## Features

| Button | What it does |
|--------|-------------|
| 🎙 Audio to text | Transcribes voice messages and audio files (Faster-Whisper) |
| 🖼 Generate image from prompt | Generates an image from a text description (Stable Diffusion v1.5) |
| 📹 Download video | Downloads videos from YouTube and VK in selectable quality (yt-dlp) |
| /clear | Deletes all messages in the current chat |

## Architecture

```
telegram-bot-java/       — Spring Boot bot (Java 21)
whisper-service/         — FastAPI transcription service (faster-whisper)
image-gen-service/       — FastAPI image generation service (diffusers + SD v1.5)
video-dl-service/        — FastAPI video download service (yt-dlp + FFmpeg)
docker-compose.yml       — orchestrates all four services
```

All services communicate over an internal Docker network. The bot does not expose any external ports.

## Requirements

- **Docker Desktop** (or Docker Engine + Compose v2)
- **Java 21 + Gradle** — required only for building the JAR (or use a multi-stage Docker build)
- **~6 GB RAM** for image-gen-service on first startup (model ~4 GB + torch ~1 GB)
- **~5 GB disk space** for Docker images and model cache

## Quick Start

### 1. Get a bot token

1. Open [@BotFather](https://t.me/BotFather) in Telegram
2. Send `/newbot` and follow the instructions
3. Copy the token you receive, e.g. `123456789:AAF...`

### 2. Configure environment variables

```bash
cp .env
```

Open `.env` and paste your token:

```env
TELEGRAM_BOT_TOKEN=123456789:AAF...
```

### 3. Build the JAR

```bash
cd telegram-bot-java
./gradlew bootJar --no-daemon
cd ..
```

### 4. Start all services

```bash
docker compose up -d --build
```

**First startup will take longer** — Docker will pull base images and download models:
- `whisper-service`: Whisper small model (~250 MB) is downloaded on first startup
- `image-gen-service`: Stable Diffusion v1.5 model (~4 GB) is downloaded on first startup

You can monitor progress with:

```bash
docker compose logs -f image-gen-service
```

When `Model loaded successfully.` appears in the logs, the service is ready.

### 5. Check status

```bash
docker compose ps
```

All four services should show status `Up`.

## Configuration

### .env

```env
TELEGRAM_BOT_TOKEN=<token from @BotFather>
```

### docker-compose.yml — service parameters

**whisper-service:**
| Variable | Default | Options |
|---|---|---|
| `WHISPER_MODEL` | `small` | `tiny`, `base`, `small`, `medium`, `large-v3` |
| `WHISPER_DEVICE` | `cpu` | `cpu`, `cuda` |
| `WHISPER_COMPUTE_TYPE` | `int8` | `int8` (CPU), `float16` (GPU) |

**image-gen-service:**
| Variable | Default | Note |
|---|---|---|
| `IMAGE_MODEL` | `runwayml/stable-diffusion-v1-5` | Any SD 1.5-compatible model from HuggingFace |
| `IMAGE_STEPS` | `20` | Lower = faster but lower quality |
| `IMAGE_WIDTH` | `512` | |
| `IMAGE_HEIGHT` | `512` | |

Alternative models (replace `IMAGE_MODEL`):
- `Lykon/dreamshaper-8` — better overall quality
- `SG161222/Realistic_Vision_V5.1` — photorealism
- `prompthero/openjourney-v4` — artistic style (midjourney-like)

**video-dl-service:**
| Variable | Default | Note |
|---|---|---|
| `RATE_LIMIT_DELAY` | `5` | Seconds between requests (anti-ban) |
| `PROXY_URL` | _(empty)_ | Optional: `socks5://host:port` or `http://host:port` |
| `COOKIES_FILE` | _(empty)_ | Optional: path to cookies.txt for authenticated content |

Files up to **50 MB** are sent directly in Telegram. Larger files are served as a temporary download link (valid for 10 minutes by default).

To use download links on a remote server, update `PUBLIC_URL` to your server's address (e.g., `http://your-server:8002`).

## Updating the bot after code changes

```bash
cd telegram-bot-java
./gradlew bootJar --no-daemon
cd ..
docker compose build telegram-bot
docker stop telegram-audio-transcription-telegram-bot-1
docker rm telegram-audio-transcription-telegram-bot-1
docker compose up -d --no-build
```

## Useful commands

```bash
# Stream bot logs in real time
docker compose logs -f telegram-bot

# Stream logs from all services
docker compose logs -f

# Stop all services
docker compose down

# Stop and delete model cache (frees ~5 GB)
docker compose down -v

# Restart the bot without rebuilding
docker compose restart telegram-bot
```

## Performance

Image generation on CPU takes **3–5 minutes** for 512×512 at 20 steps.  
If you have a GPU, add the following to the `image-gen-service` section in `docker-compose.yml`:

```yaml
    environment:
      - IMAGE_MODEL=runwayml/stable-diffusion-v1-5
      - IMAGE_STEPS=20
      - WHISPER_DEVICE=cuda          # for whisper-service
    deploy:
      resources:
        reservations:
          devices:
            - driver: nvidia
              count: all
              capabilities: [gpu]
```

Also set `WHISPER_DEVICE=cuda` and `WHISPER_COMPUTE_TYPE=float16` in whisper-service.
