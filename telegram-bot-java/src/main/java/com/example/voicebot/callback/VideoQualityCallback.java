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

import java.net.SocketTimeoutException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

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

        Integer statusMsgId = sender.sendTextAndGetId(chatId,
                "⏳ Скачиваю видео...\n💡 Чем больше размер, тем дольше ожидание. Большие файлы могут загружаться 5-15 минут.");

        AtomicBoolean done = new AtomicBoolean(false);
        Thread progressThread = startProgressThread(chatId, statusMsgId, done, "Скачиваю видео");

        try {
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
                stopProgress(done, progressThread);

                if (!response.isSuccessful() || response.body() == null) {
                    String errorMsg = "❌ Ошибка при скачивании видео.";
                    if (response.body() != null) {
                        try {
                            var errorJson = objectMapper.readTree(response.body().string());
                            if (errorJson.has("detail")) {
                                errorMsg = "❌ " + errorJson.get("detail").asText();
                            }
                        } catch (Exception ignored) {}
                    }
                    editOrSend(chatId, statusMsgId, errorMsg);
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
                    editOrSend(chatId, statusMsgId, "📤 Отправляю файл в Telegram...");

                    String internalUrl = videoDlUrl + result.get("internal_url").asText();
                    Request fileRequest = new Request.Builder().url(internalUrl).get().build();
                    try (Response fileResponse = httpClient.newCall(fileRequest).execute()) {
                        if (!fileResponse.isSuccessful() || fileResponse.body() == null) {
                            editOrSend(chatId, statusMsgId, "❌ Не удалось получить скачанный файл.");
                            sessionService.setState(chatId, UserState.IDLE);
                            return;
                        }
                        byte[] fileBytes = fileResponse.body().bytes();
                        if (isAudio) {
                            sender.sendDocument(chatId, fileBytes, filename, "🎵 Аудио");
                        } else {
                            sender.sendVideo(chatId, fileBytes, filename, "📹 Видео");
                        }
                        if (statusMsgId != null) {
                            sender.deleteMessage(chatId, statusMsgId);
                        }
                    }
                } else {
                    long sizeMb = filesize / (1024 * 1024);
                    boolean isLocal = downloadUrl.contains(videoDlUrl.replace("http://", "").split("/")[0]);
                    String linkMessage;
                    if (isLocal) {
                        int expiresMin = expiresIn / 60;
                        linkMessage = String.format(
                                "✅ Видео скачано (%d МБ).\n"
                                + "Файл слишком большой для отправки в Telegram.\n\n"
                                + "📎 Ссылка для скачивания:\n%s\n\n"
                                + "⏱ Ссылка действительна %d мин.",
                                sizeMb, downloadUrl, expiresMin
                        );
                    } else {
                        linkMessage = String.format(
                                "✅ Видео скачано (%d МБ).\n\n"
                                + "📎 Ссылка для скачивания:\n%s",
                                sizeMb, downloadUrl
                        );
                    }
                    editOrSend(chatId, statusMsgId, linkMessage);
                }
            }
        } catch (SocketTimeoutException e) {
            stopProgress(done, progressThread);
            editOrSend(chatId, statusMsgId,
                    "❌ Превышено время ожидания скачивания. Видео слишком большое или сервер не отвечает. Попробуйте выбрать более низкое качество.");
        } catch (Exception e) {
            stopProgress(done, progressThread);
            e.printStackTrace();
            editOrSend(chatId, statusMsgId,
                    "❌ Ошибка при скачивании видео. Попробуйте ещё раз или выберите другое качество.");
        }

        sessionService.setState(chatId, UserState.IDLE);
    }

    private Thread startProgressThread(Long chatId, Integer msgId, AtomicBoolean done, String action) {
        Thread thread = new Thread(() -> {
            int elapsed = 0;
            while (!done.get()) {
                try {
                    Thread.sleep(10_000);
                } catch (InterruptedException e) {
                    break;
                }
                if (done.get()) break;
                elapsed += 10;
                String timeStr = formatElapsed(elapsed);
                sender.editText(chatId, msgId, "⏳ " + action + "... (" + timeStr + ")");
            }
        });
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private void stopProgress(AtomicBoolean done, Thread thread) {
        done.set(true);
        thread.interrupt();
    }

    private void editOrSend(Long chatId, Integer msgId, String text) {
        if (msgId != null) {
            sender.editText(chatId, msgId, text);
        } else {
            sender.sendText(chatId, text);
        }
    }

    private String formatElapsed(int seconds) {
        if (seconds < 60) return seconds + " сек";
        int min = seconds / 60;
        int sec = seconds % 60;
        if (sec == 0) return min + " мин";
        return min + " мин " + sec + " сек";
    }
}
