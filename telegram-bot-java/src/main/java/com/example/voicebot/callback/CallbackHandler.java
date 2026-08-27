package com.example.voicebot.callback;

import org.telegram.telegrambots.meta.api.objects.CallbackQuery;

public interface CallbackHandler {
    String callbackData();
    void handle(CallbackQuery callbackQuery);
}
