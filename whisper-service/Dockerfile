FROM python:3.11-slim

RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Global pip settings: timeout and retries
ENV PIP_DEFAULT_TIMEOUT=300 \
    PIP_RETRIES=5 \
    PIP_NO_CACHE_DIR=1

COPY requirements.txt .

# Lightweight packages first (fast cache layer)
RUN pip install fastapi==0.115.* uvicorn[standard]==0.32.* python-multipart==0.0.* requests

# faster-whisper separately (ctranslate2 ~300 MB) — its own cache layer
RUN pip install --progress-bar on faster-whisper==1.0.*

COPY app.py .

ENV WHISPER_MODEL=small
EXPOSE 8000
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8000"]
