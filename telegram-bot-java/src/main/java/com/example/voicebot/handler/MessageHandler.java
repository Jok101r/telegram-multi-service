package com.example.voicebot.handler;

import org.telegram.telegrambots.meta.api.objects.message.Message;

public interface MessageHandler {

    boolean canHandle(Message message);

    void handle(Message message);
}
