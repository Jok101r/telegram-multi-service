#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
COOKIES_FILE="$SCRIPT_DIR/cookies.txt"
BROWSER="${1:-chrome}"

if ! command -v yt-dlp &>/dev/null; then
    echo "yt-dlp not found, installing..."
    if [[ "$(uname)" == "Darwin" ]]; then
        if command -v brew &>/dev/null; then
            brew install yt-dlp
        else
            echo "ERROR: brew not found. Install Homebrew first: https://brew.sh"
            exit 1
        fi
    else
        if command -v pipx &>/dev/null; then
            pipx install yt-dlp
        elif command -v pip3 &>/dev/null; then
            pip3 install --user yt-dlp
        elif command -v apt-get &>/dev/null; then
            sudo apt-get update && sudo apt-get install -y pipx && pipx install yt-dlp
        else
            echo "ERROR: Cannot install yt-dlp. Install it manually: pip3 install --user yt-dlp"
            exit 1
        fi
    fi
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
