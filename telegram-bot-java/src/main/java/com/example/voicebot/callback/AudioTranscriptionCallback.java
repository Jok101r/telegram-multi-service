package com.example.voicebot.callback;

import com.example.voicebot.session.UserSessionService;
import com.example.voicebot.session.UserState;
import com.example.voicebot.util.TelegramSender;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;

@Component
public class AudioTranscriptionCallback implements CallbackHandler {

    private final TelegramSender sender;
    private final UserSessionService sessionService;

    public AudioTranscriptionCallback(TelegramSender sender, UserSessionService sessionService) {
        this.sender = sender;
        this.sessionService = sessionService;
    }

    @Override
    public String callbackData() {
        return "audio_transcription";
    }

    @Override
    public void handle(CallbackQuery callbackQuery) {
        Long chatId = callbackQuery.getMessage().getChatId();
        sessionService.setState(chatId, UserState.AWAITING_AUDIO);
        sender.answerCallback(callbackQuery.getId());
        sender.sendText(chatId, "🎙 Отправьте голосовое сообщение или аудиофайл — я переведу его в текст.");
    }
}
