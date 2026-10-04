package com.example.voicebot.session;

public enum UserState {
    IDLE,
    AWAITING_AUDIO,
    AWAITING_IMAGE_PROMPT,
    AWAITING_VIDEO_URL,
    AWAITING_VIDEO_QUALITY
}
