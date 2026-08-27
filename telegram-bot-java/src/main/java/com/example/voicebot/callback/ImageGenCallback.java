package com.example.voicebot.callback;

import com.example.voicebot.session.UserSessionService;
import com.example.voicebot.session.UserState;
import com.example.voicebot.util.TelegramSender;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;

@Component
public class ImageGenCallback implements CallbackHandler {

    private final TelegramSender sender;
    private final UserSessionService sessionService;

    public ImageGenCallback(TelegramSender sender, UserSessionService sessionService) {
        this.sender = sender;
        this.sessionService = sessionService;
    }

    @Override
    public String callbackData() {
        return "image_generation";
    }

    @Override
    public void handle(CallbackQuery callbackQuery) {
        Long chatId = callbackQuery.getMessage().getChatId();
        sessionService.setState(chatId, UserState.AWAITING_IMAGE_PROMPT);
        sender.answerCallback(callbackQuery.getId());
        sender.sendText(chatId,
                "🖼 Введите описание картинки — я её сгенерирую.\n\n"
                        + "Пример: футуристический город ночью, неоновые огни, кинематографично");
    }
}
