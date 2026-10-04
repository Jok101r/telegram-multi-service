package com.example.voicebot.callback;

import com.example.voicebot.session.UserSessionService;
import com.example.voicebot.session.UserState;
import com.example.voicebot.util.TelegramSender;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;

@Component
public class VideoDownloadCallback implements CallbackHandler {

    private final TelegramSender sender;
    private final UserSessionService sessionService;

    public VideoDownloadCallback(TelegramSender sender, UserSessionService sessionService) {
        this.sender = sender;
        this.sessionService = sessionService;
    }

    @Override
    public String callbackData() {
        return "video_download";
    }

    @Override
    public void handle(CallbackQuery callbackQuery) {
        Long chatId = callbackQuery.getMessage().getChatId();
        sessionService.setState(chatId, UserState.AWAITING_VIDEO_URL);
        sender.answerCallback(callbackQuery.getId());
        sender.sendText(chatId, """
                📹 Отправьте ссылку на видео. Поддерживаемые сервисы:

                YouTube, TikTok, Instagram, Facebook, X (Twitter), VK, Vimeo, Dailymotion, Twitch, Reddit, Pinterest, Rutube, OK, Bilibili, SoundCloud и 1000+ других.""");
    }
}
