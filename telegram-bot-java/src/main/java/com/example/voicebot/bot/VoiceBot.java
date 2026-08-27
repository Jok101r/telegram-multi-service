package com.example.voicebot.bot;

import com.example.voicebot.callback.CallbackHandler;
import com.example.voicebot.command.Command;
import com.example.voicebot.handler.MessageHandler;
import com.example.voicebot.session.UserSessionService;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class VoiceBot implements LongPollingSingleThreadUpdateConsumer {

    private final Map<String, Command> commands;
    private final List<MessageHandler> messageHandlers;
    private final Map<String, CallbackHandler> callbackHandlers;
    private final UserSessionService sessionService;

    public VoiceBot(List<Command> commands,
                    List<MessageHandler> messageHandlers,
                    List<CallbackHandler> callbackHandlers,
                    UserSessionService sessionService) {
        this.commands = commands.stream()
                .collect(Collectors.toMap(Command::trigger, c -> c));
        this.messageHandlers = messageHandlers;
        this.callbackHandlers = callbackHandlers.stream()
                .collect(Collectors.toMap(CallbackHandler::callbackData, c -> c));
        this.sessionService = sessionService;
    }

    @Override
    public void consume(Update update) {
        if (update.hasCallbackQuery()) {
            CallbackQuery callback = update.getCallbackQuery();
            CallbackHandler handler = callbackHandlers.get(callback.getData());
            if (handler != null) handler.handle(callback);
            return;
        }

        if (!update.hasMessage()) return;
        Message message = update.getMessage();

        // track all incoming messages so they can be deleted via /clear
        sessionService.trackMessage(message.getChatId(), message.getMessageId());

        if (message.hasText()) {
            Command cmd = commands.get(message.getText());
            if (cmd != null) {
                cmd.execute(message);
                return;
            }
            // not a command — pass to handlers (e.g. ImageGenHandler)
        }

        messageHandlers.stream()
                .filter(h -> h.canHandle(message))
                .findFirst()
                .ifPresent(h -> h.handle(message));
    }
}
