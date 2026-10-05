#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
COOKIES_FILE="$SCRIPT_DIR/cookies.txt"
BROWSER="${1:-chrome}"

if ! command -v yt-dlp &>/dev/null; then
    echo "Installing yt-dlp via brew..."
    brew install yt-dlp
fi

echo "Extracting YouTube cookies from $BROWSER..."
yt-dlp --cookies-from-browser "$BROWSER" \
       --cookies "$COOKIES_FILE" \
       --skip-download \
       --no-warnings \
       -o /dev/null \
       "https://www.youtube.com/watch?v=dQw4w9WgXcQ" 2>/dev/null

if [ -f "$COOKIES_FILE" ] && [ -s "$COOKIES_FILE" ]; then
    echo "Cookies saved to $COOKIES_FILE ($(wc -l < "$COOKIES_FILE") lines)"
    if docker compose ps --status running 2>/dev/null | grep -q video-dl-service; then
        echo "Restarting video-dl-service..."
        cd "$SCRIPT_DIR" && docker compose restart video-dl-service 2>/dev/null
    fi
else
    echo "ERROR: Failed to extract cookies"
    exit 1
fi
