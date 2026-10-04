package com.example.voicebot.callback;

import com.example.voicebot.session.UserSessionService;
import com.example.voicebot.session.UserState;
import com.example.voicebot.util.TelegramSender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;

import java.util.Map;

@Component
public class VideoQualityCallback implements CallbackHandler {

    private static final String PREFIX = "vdl:";

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final TelegramSender sender;
    private final UserSessionService sessionService;
    private final String videoDlUrl;

    public VideoQualityCallback(
            @Qualifier("videoDlHttpClient") OkHttpClient httpClient,
            ObjectMapper objectMapper,
            TelegramSender sender,
            UserSessionService sessionService,
            @Value("${bot.video-dl-url}") String videoDlUrl) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.sender = sender;
        this.sessionService = sessionService;
        this.videoDlUrl = videoDlUrl;
    }

    @Override
    public String callbackData() {
        return PREFIX;
    }

    @Override
    public void handle(CallbackQuery callbackQuery) {
        Long chatId = callbackQuery.getMessage().getChatId();
        String formatId = callbackQuery.getData().substring(PREFIX.length());
        String videoUrl = sessionService.getVideoUrl(chatId);

        sender.answerCallback(callbackQuery.getId());

        if (videoUrl == null || videoUrl.isBlank()) {
            sender.sendText(chatId, "Сессия истекла. Отправьте ссылку заново.");
            sessionService.setState(chatId, UserState.IDLE);
            return;
        }

        sender.sendText(chatId, "⏳ Скачиваю видео, это может занять несколько минут...");

        try {
            // Step 1: Call /download — returns JSON with file metadata + download link
            String json = objectMapper.writeValueAsString(Map.of(
                    "url", videoUrl,
                    "format_id", formatId
            ));
            RequestBody body = RequestBody.create(json, MediaType.parse("application/json"));
            Request request = new Request.Builder()
                    .url(videoDlUrl + "/download")
                    .post(body)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    String errorMsg = "Ошибка при скачивании видео.";
                    if (response.body() != null) {
                        try {
                            var errorJson = objectMapper.readTree(response.body().string());
                            if (errorJson.has("detail")) {
                                errorMsg = errorJson.get("detail").asText();
                            }
                        } catch (Exception ignored) {}
                    }
                    sender.sendText(chatId, errorMsg);
                    sessionService.setState(chatId, UserState.IDLE);
                    return;
                }

                JsonNode result = objectMapper.readTree(response.body().string());
                boolean fitsTelegram = result.get("fits_telegram").asBoolean();
                String filename = result.get("filename").asText();
                String downloadUrl = result.get("download_url").asText();
                boolean isAudio = result.get("is_audio").asBoolean();
                long filesize = result.get("filesize").asLong();
                int expiresIn = result.get("expires_in_seconds").asInt();

                if (fitsTelegram) {
                    // Step 2a: File is small enough — fetch from internal URL and send via Telegram
                    String internalUrl = videoDlUrl + result.get("internal_url").asText();
                    Request fileRequest = new Request.Builder().url(internalUrl).get().build();
                    try (Response fileResponse = httpClient.newCall(fileRequest).execute()) {
                        if (!fileResponse.isSuccessful() || fileResponse.body() == null) {
                            sender.sendText(chatId, "Ошибка при получении файла.");
                            sessionService.setState(chatId, UserState.IDLE);
                            return;
                        }
                        byte[] fileBytes = fileResponse.body().bytes();
                        if (isAudio) {
                            sender.sendDocument(chatId, fileBytes, filename, "🎵 Аудио");
                        } else {
                            sender.sendVideo(chatId, fileBytes, filename, "📹 Видео");
                        }
                    }
                } else {
                    // Step 2b: File too large for Telegram — send download link
                    long sizeMb = filesize / (1024 * 1024);
                    int expiresMin = expiresIn / 60;
                    String linkMessage = String.format(
                            "📦 Файл слишком большой для Telegram (%d МБ).\n\n"
                            + "📎 Ссылка для скачивания:\n%s\n\n"
                            + "⏱ Ссылка действительна %d мин.",
                            sizeMb, downloadUrl, expiresMin
                    );
                    sender.sendText(chatId, linkMessage);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            sender.sendText(chatId, "Ошибка при скачивании видео, попробуйте ещё раз.");
        }

        sessionService.setState(chatId, UserState.IDLE);
    }
}
