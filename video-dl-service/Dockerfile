FROM python:3.11-slim

RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg curl unzip \
    && rm -rf /var/lib/apt/lists/*

# Install Deno — required by yt-dlp for YouTube JS player parsing
RUN curl -fsSL https://deno.land/install.sh | DENO_INSTALL=/usr/local sh
ENV DENO_DIR=/tmp/deno

WORKDIR /app

ENV PIP_DEFAULT_TIMEOUT=300 \
    PIP_RETRIES=5 \
    PIP_NO_CACHE_DIR=1

COPY requirements.txt .

# Lightweight packages first (fast cache layer)
RUN pip install fastapi==0.115.* uvicorn[standard]==0.32.* requests

# yt-dlp + PO Token plugin for YouTube anti-bot bypass
RUN pip install --progress-bar on yt-dlp bgutil-ytdlp-pot-provider

COPY app.py .

EXPOSE 8002
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8002"]
