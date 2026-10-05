import os
import time
import uuid
import logging
import threading
import tempfile
from pathlib import Path

import requests
import yt_dlp
from fastapi import FastAPI, HTTPException
from fastapi.responses import FileResponse
from pydantic import BaseModel

app = FastAPI()
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

RATE_LIMIT_DELAY = int(os.getenv("RATE_LIMIT_DELAY", "5"))
PROXY_URL = os.getenv("PROXY_URL", "").strip() or None
COOKIES_FILE = os.getenv("COOKIES_FILE", "").strip() or None
POT_PROVIDER_URL = os.getenv("POT_PROVIDER_URL", "").strip() or None
PUBLIC_URL = os.getenv("PUBLIC_URL", "http://localhost:8002").rstrip("/")
FILE_TTL_SECONDS = int(os.getenv("FILE_TTL_SECONDS", "600"))  # 10 minutes

TELEGRAM_MAX_SIZE = 48 * 1024 * 1024  # 48 MB (buffer for multipart overhead)

STORAGE_TO_API = "https://storage.to/api/upload"

DOWNLOAD_DIR = Path(tempfile.gettempdir()) / "video-dl"
DOWNLOAD_DIR.mkdir(exist_ok=True)

# Registry of served files: file_id -> {"path": Path, "created": float, "filename": str}
_file_registry: dict[str, dict] = {}
_registry_lock = threading.Lock()

# Quality tiers in priority order
# Prefer H.264 (avc1) — Telegram requires it for inline video playback on mobile
QUALITY_TIERS = [
    {"id": "best",  "label": "Лучшее",  "format": "bestvideo[vcodec^=avc1][ext=mp4]+bestaudio[ext=m4a]/bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best"},
    {"id": "720p",  "label": "720p",     "format": "bestvideo[vcodec^=avc1][height<=720][ext=mp4]+bestaudio[ext=m4a]/bestvideo[height<=720][ext=mp4]+bestaudio[ext=m4a]/best[height<=720][ext=mp4]/best[height<=720]"},
    {"id": "480p",  "label": "480p",     "format": "bestvideo[vcodec^=avc1][height<=480][ext=mp4]+bestaudio[ext=m4a]/bestvideo[height<=480][ext=mp4]+bestaudio[ext=m4a]/best[height<=480][ext=mp4]/best[height<=480]"},
    {"id": "360p",  "label": "360p",     "format": "bestvideo[vcodec^=avc1][height<=360][ext=mp4]+bestaudio[ext=m4a]/bestvideo[height<=360][ext=mp4]+bestaudio[ext=m4a]/best[height<=360][ext=mp4]/best[height<=360]"},
    {"id": "audio", "label": "Только аудио", "format": "bestaudio[ext=m4a]/bestaudio"},
]


def _base_opts() -> dict:
    """Common yt-dlp options with anti-ban settings."""
    opts = {
        "quiet": True,
        "no_warnings": True,
        "socket_timeout": 30,
        "retries": 3,
        "sleep_interval": RATE_LIMIT_DELAY,
        "max_sleep_interval": RATE_LIMIT_DELAY + 3,
        "nocheckcertificate": True,
    }
    if PROXY_URL:
        opts["proxy"] = PROXY_URL
    if COOKIES_FILE and os.path.isfile(COOKIES_FILE):
        opts["cookiefile"] = COOKIES_FILE
    if POT_PROVIDER_URL:
        opts["extractor_args"] = {
            "youtube": [f"getpot_bgutil_baseurl={POT_PROVIDER_URL}"]
        }
    opts["remote_components"] = {"ejs:github"}
    return opts


def _classify_error(e: Exception) -> tuple[int, str]:
    """Map yt-dlp errors to user-friendly messages."""
    msg = str(e).lower()
    if "private" in msg or "is not available" in msg:
        return 403, "Видео приватное или недоступно."
    if "confirm you're not a bot" in msg or "confirm you" in msg:
        return 403, "YouTube требует подтверждение — нужны куки из браузера (COOKIES_FILE)."
    if "age" in msg or "sign in" in msg or "login" in msg:
        return 403, "Видео требует авторизации (возрастное ограничение или приватное)."
    if "geo" in msg or "not available in your country" in msg:
        return 451, "Видео недоступно в вашем регионе (гео-блокировка)."
    if "429" in msg or "too many" in msg or "rate" in msg:
        return 429, "Слишком много запросов — попробуйте позже."
    if "unsupported" in msg or "no video" in msg:
        return 400, "Ссылка не поддерживается или не содержит видео."
    return 500, f"Ошибка при обработке видео: {e}"


def _upload_to_filehost(file_path: Path, filename: str) -> str | None:
    """Upload file to storage.to (up to 25 GB, no auth) and return download URL."""
    try:
        file_size = file_path.stat().st_size
        ext = file_path.suffix.lstrip(".")
        content_type = "audio/mp4" if ext == "m4a" else "video/mp4"

        init = requests.post(f"{STORAGE_TO_API}/init",
            json={"filename": filename, "size": file_size, "content_type": content_type},
            timeout=30).json()
        if not init.get("success"):
            logger.warning(f"storage.to init failed: {init}")
            return None

        r2_key = init["r2_key"]
        upload_type = init.get("type", "single")

        if upload_type == "single":
            with open(file_path, "rb") as f:
                resp = requests.put(init["upload_url"], data=f,
                    headers={"Content-Type": content_type}, timeout=1800)
            resp.raise_for_status()
        else:
            upload_id = init["upload_id"]
            part_size = init["part_size"]
            part_urls = init["initial_urls"]
            parts = []

            with open(file_path, "rb") as f:
                part_num = 1
                while True:
                    chunk = f.read(part_size)
                    if not chunk:
                        break
                    url = part_urls.get(str(part_num))
                    if not url:
                        more = requests.post(f"{STORAGE_TO_API}/get-urls",
                            json={"upload_id": upload_id, "parts": [part_num]},
                            timeout=30).json()
                        url = more.get("urls", {}).get(str(part_num))
                    etag = None
                    for attempt in range(3):
                        try:
                            resp = requests.put(url, data=chunk, timeout=600)
                            resp.raise_for_status()
                            etag = resp.headers.get("ETag", "").strip('"')
                            break
                        except Exception as e:
                            logger.warning(f"storage.to: part {part_num} attempt {attempt+1} failed: {e}")
                            if attempt == 2:
                                raise
                            time.sleep(2)
                    parts.append({"partNumber": part_num, "etag": etag})
                    logger.info(f"storage.to: uploaded part {part_num}/{init['total_parts']}")
                    part_num += 1

            requests.post(f"{STORAGE_TO_API}/complete-multipart",
                json={"upload_id": upload_id, "parts": parts},
                timeout=30).raise_for_status()

        confirm = requests.post(f"{STORAGE_TO_API}/confirm",
            json={"filename": filename, "size": file_size,
                  "content_type": content_type, "r2_key": r2_key},
            timeout=30).json()
        if confirm.get("success"):
            url = confirm["file"]["url"]
            logger.info(f"Uploaded to storage.to: {url}")
            return url

        logger.warning(f"storage.to confirm failed: {confirm}")
        return None
    except Exception as e:
        logger.warning(f"storage.to upload failed: {e}")
        return None


def _cleanup_expired_files():
    """Remove files older than FILE_TTL_SECONDS. Runs in a background thread."""
    while True:
        time.sleep(60)
        now = time.time()
        expired = []
        with _registry_lock:
            for file_id, entry in list(_file_registry.items()):
                if now - entry["created"] > FILE_TTL_SECONDS:
                    expired.append(file_id)
                    entry["path"].unlink(missing_ok=True)
                    del _file_registry[file_id]
        if expired:
            logger.info(f"Cleaned up {len(expired)} expired file(s)")


# Start cleanup thread
_cleanup_thread = threading.Thread(target=_cleanup_expired_files, daemon=True)
_cleanup_thread.start()


class InfoRequest(BaseModel):
    url: str


class DownloadRequest(BaseModel):
    url: str
    format_id: str  # one of: best, 720p, 480p, 360p, audio


@app.get("/health")
def health():
    return {
        "status": "ok",
        "rate_limit_delay": RATE_LIMIT_DELAY,
        "proxy": bool(PROXY_URL),
        "cookies": bool(COOKIES_FILE),
        "public_url": PUBLIC_URL,
        "file_ttl_seconds": FILE_TTL_SECONDS,
    }


@app.post("/info")
def get_info(req: InfoRequest):
    """Extract video metadata and available quality tiers."""
    opts = _base_opts()
    opts["skip_download"] = True

    try:
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(req.url, download=False)
    except Exception as e:
        code, msg = _classify_error(e)
        raise HTTPException(status_code=code, detail=msg)

    if info is None:
        raise HTTPException(status_code=400, detail="Не удалось получить информацию о видео.")

    title = info.get("title", "Видео")
    duration = info.get("duration")
    thumbnail = info.get("thumbnail")

    # Estimate file sizes per tier by probing formats
    formats_out = []
    for tier in QUALITY_TIERS:
        probe_opts = _base_opts()
        probe_opts["skip_download"] = True
        probe_opts["format"] = tier["format"]

        try:
            with yt_dlp.YoutubeDL(probe_opts) as ydl:
                probe_info = ydl.extract_info(req.url, download=False)
                if probe_info is None:
                    continue
        except Exception:
            continue

        # Get estimated file size
        filesize = probe_info.get("filesize") or probe_info.get("filesize_approx")

        # If still no size, try to estimate from requested_formats
        if not filesize and probe_info.get("requested_formats"):
            total = 0
            for f in probe_info["requested_formats"]:
                s = f.get("filesize") or f.get("filesize_approx") or 0
                total += s
            if total > 0:
                filesize = total

        # If still no size but we have duration and tbr, estimate
        if not filesize and duration:
            tbr = probe_info.get("tbr")
            if not tbr and probe_info.get("requested_formats"):
                tbr = sum(f.get("tbr", 0) for f in probe_info["requested_formats"])
            if tbr:
                filesize = int(tbr * 1000 / 8 * duration)

        # Get resolution
        height = probe_info.get("height")
        if not height and probe_info.get("requested_formats"):
            heights = [f.get("height", 0) for f in probe_info["requested_formats"]]
            height = max(heights) if heights else None

        # Build label
        label = tier["label"]
        if height and tier["id"] != "audio":
            label = f"{height}p"
        if filesize:
            size_mb = filesize / (1024 * 1024)
            label += f" (~{size_mb:.0f} МБ)"
            if filesize > TELEGRAM_MAX_SIZE:
                label += " 📎 ссылка"

        fits_telegram = (filesize or 0) <= TELEGRAM_MAX_SIZE if filesize else True

        formats_out.append({
            "format_id": tier["id"],
            "label": label,
            "filesize_approx": filesize,
            "fits_telegram": fits_telegram,
        })

    if not formats_out:
        raise HTTPException(status_code=400, detail="Не удалось определить доступные форматы.")

    return {
        "title": title,
        "duration": duration,
        "thumbnail": thumbnail,
        "formats": formats_out,
    }


@app.post("/download")
def download_video(req: DownloadRequest):
    """Download video, store it, and return metadata with download link."""
    tier = next((t for t in QUALITY_TIERS if t["id"] == req.format_id), None)
    if tier is None:
        raise HTTPException(status_code=400, detail=f"Неизвестный формат: {req.format_id}")

    file_id = uuid.uuid4().hex
    ext = "m4a" if req.format_id == "audio" else "mp4"

    opts = _base_opts()
    opts["format"] = tier["format"]
    opts["outtmpl"] = str(DOWNLOAD_DIR / f"{file_id}.%(ext)s")
    opts["merge_output_format"] = ext
    opts["postprocessor_args"] = {"merger": ["-movflags", "+faststart"]}

    if req.format_id == "audio":
        opts["postprocessors"] = [{
            "key": "FFmpegExtractAudio",
            "preferredcodec": "m4a",
        }]

    try:
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(req.url, download=True)
    except Exception as e:
        for f in DOWNLOAD_DIR.glob(f"{file_id}.*"):
            f.unlink(missing_ok=True)
        code, msg = _classify_error(e)
        raise HTTPException(status_code=code, detail=msg)

    # Find the actual output file
    actual_file = None
    for f in DOWNLOAD_DIR.glob(f"{file_id}.*"):
        actual_file = f
        break

    if actual_file is None or not actual_file.exists():
        raise HTTPException(status_code=500, detail="Не удалось скачать видео.")

    file_size = actual_file.stat().st_size
    title = (info or {}).get("title", "video")
    safe_title = "".join(c if c.isalnum() or c in " -_" else "_" for c in title)[:60]
    download_name = f"{safe_title}.{actual_file.suffix.lstrip('.')}"
    fits_telegram = file_size <= TELEGRAM_MAX_SIZE

    # Register the file for local serving
    with _registry_lock:
        _file_registry[file_id] = {
            "path": actual_file,
            "created": time.time(),
            "filename": download_name,
            "media_type": "audio/mp4" if req.format_id == "audio" else "video/mp4",
        }

    # local_url = f"{PUBLIC_URL}/files/{file_id}/{download_name}"
    download_url = None

    if not fits_telegram:
        logger.info(f"File {file_size / 1024 / 1024:.0f} MB exceeds Telegram limit, uploading to storage.to...")
        hosted_url = _upload_to_filehost(actual_file, download_name)
        if hosted_url:
            download_url = hosted_url
        else:
            actual_file.unlink(missing_ok=True)
            raise HTTPException(status_code=502,
                detail="Не удалось загрузить файл на файлообменник. Попробуйте ещё раз или выберите более низкое качество.")

    return {
        "file_id": file_id,
        "filename": download_name,
        "filesize": file_size,
        "fits_telegram": fits_telegram,
        "download_url": download_url,
        "internal_url": f"/files/{file_id}/{download_name}",
        "is_audio": req.format_id == "audio",
        "expires_in_seconds": FILE_TTL_SECONDS,
    }


@app.get("/files/{file_id}/{filename}")
def serve_file(file_id: str, filename: str):
    """Serve a previously downloaded file. Files expire after FILE_TTL_SECONDS."""
    with _registry_lock:
        entry = _file_registry.get(file_id)

    if entry is None or not entry["path"].exists():
        raise HTTPException(status_code=404, detail="Файл не найден или срок хранения истёк.")

    return FileResponse(
        path=str(entry["path"]),
        media_type=entry["media_type"],
        filename=entry["filename"],
    )


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8002)
