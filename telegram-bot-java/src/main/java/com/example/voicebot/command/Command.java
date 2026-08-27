package com.example.voicebot.command;

import org.telegram.telegrambots.meta.api.objects.message.Message;

public interface Command {

    String trigger();

    void execute(Message message);
}
