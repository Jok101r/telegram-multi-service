package com.example.voicebot.command;

import com.example.voicebot.session.UserSessionService;
import com.example.voicebot.session.UserState;
import com.example.voicebot.util.TelegramSender;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.message.Message;

@Component
public class ClearCommand implements Command {

    private final TelegramSender sender;
    private final UserSessionService sessionService;

    public ClearCommand(TelegramSender sender, UserSessionService sessionService) {
        this.sender = sender;
        this.sessionService = sessionService;
    }

    @Override
    public String trigger() {
        return "/clear";
    }

    @Override
    public void execute(Message message) {
        Long chatId = message.getChatId();
        sessionService.setState(chatId, UserState.IDLE);
        sessionService.popMessageIds(chatId)
                .forEach(id -> sender.deleteMessage(chatId, id));
    }
}
