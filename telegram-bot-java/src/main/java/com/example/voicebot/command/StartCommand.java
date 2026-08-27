package com.example.voicebot.command;

import com.example.voicebot.session.UserSessionService;
import com.example.voicebot.session.UserState;
import com.example.voicebot.util.TelegramSender;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

@Component
public class StartCommand implements Command {

    private final TelegramSender sender;
    private final UserSessionService sessionService;

    public StartCommand(TelegramSender sender, UserSessionService sessionService) {
        this.sender = sender;
        this.sessionService = sessionService;
    }

    @Override
    public String trigger() {
        return "/start";
    }

    @Override
    public void execute(Message message) {
        sessionService.setState(message.getChatId(), UserState.IDLE);
        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(
                        InlineKeyboardButton.builder()
                                .text("🎙 Аудио в текст")
                                .callbackData("audio_transcription")
                                .build()
                ))
                .keyboardRow(new InlineKeyboardRow(
                        InlineKeyboardButton.builder()
                                .text("🖼 Генерация картинки по промту")
                                .callbackData("image_generation")
                                .build()
                ))
                .build();

        sender.sendWithKeyboard(message.getChatId(), """
                Привет! Я помогу тебе с задачами на основе ИИ.

                Выбери, что хочешь сделать:""", keyboard);
    }
}
