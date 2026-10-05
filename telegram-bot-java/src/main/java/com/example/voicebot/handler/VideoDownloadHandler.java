package com.example.voicebot.handler;

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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

@Component
public class VideoDownloadHandler implements MessageHandler {

    private static final Pattern URL_PATTERN = Pattern.compile(
            "https?://.+\\..+/.+"
    );

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final TelegramSender sender;
    private final UserSessionService sessionService;
    private final String videoDlUrl;

    public VideoDownloadHandler(
            OkHttpClient httpClient,
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
    public boolean canHandle(Message message) {
        return message.hasText()
                && sessionService.getState(message.getChatId()) == UserState.AWAITING_VIDEO_URL;
    }

    @Override
    public void handle(Message message) {
        Long chatId = message.getChatId();
        String url = message.getText().trim();

        if (!URL_PATTERN.matcher(url).matches()) {
            sender.sendText(chatId, "❌ Пожалуйста, отправьте корректную ссылку на видео.");
            return;
        }

        Integer statusMsgId = sender.sendTextAndGetId(chatId, "🔍 Получаю информацию о видео...");

        AtomicBoolean done = new AtomicBoolean(false);
        Thread progressThread = startProgressThread(chatId, statusMsgId, done);

        try {
            String json = objectMapper.writeValueAsString(Map.of("url", url));
            RequestBody body = RequestBody.create(json, MediaType.parse("application/json"));
            Request request = new Request.Builder()
                    .url(videoDlUrl + "/info")
                    .post(body)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                stopProgress(done, progressThread);

                if (!response.isSuccessful() || response.body() == null) {
                    String errorMsg = "❌ Не удалось получить информацию о видео.";
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

                JsonNode info = objectMapper.readTree(response.body().string());
                String title = info.get("title").asText();
                JsonNode formats = info.get("formats");

                if (formats == null || formats.isEmpty()) {
                    editOrSend(chatId, statusMsgId, "❌ Нет доступных форматов для скачивания.");
                    sessionService.setState(chatId, UserState.IDLE);
                    return;
                }

                sessionService.setVideoUrl(chatId, url);

                List<InlineKeyboardRow> rows = new ArrayList<>();
                for (JsonNode fmt : formats) {
                    String formatId = fmt.get("format_id").asText();
                    String label = fmt.get("label").asText();

                    rows.add(new InlineKeyboardRow(
                            InlineKeyboardButton.builder()
                                    .text(label)
                                    .callbackData("vdl:" + formatId)
                                    .build()
                    ));
                }

                InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                        .keyboard(rows)
                        .build();

                if (statusMsgId != null) {
                    sender.deleteMessage(chatId, statusMsgId);
                }

                String text = "🎬 " + title + "\n\nВыберите качество:";
                sender.sendWithKeyboard(chatId, text, keyboard);
                sessionService.setState(chatId, UserState.AWAITING_VIDEO_QUALITY);
            }
        } catch (SocketTimeoutException e) {
            stopProgress(done, progressThread);
            editOrSend(chatId, statusMsgId,
                    "❌ Превышено время ожидания. Сервис не отвечает — попробуйте позже.");
            sessionService.setState(chatId, UserState.IDLE);
        } catch (Exception e) {
            stopProgress(done, progressThread);
            e.printStackTrace();
            editOrSend(chatId, statusMsgId,
                    "❌ Не удалось получить информацию о видео. Проверьте ссылку и попробуйте ещё раз.");
            sessionService.setState(chatId, UserState.IDLE);
        }
    }

    private Thread startProgressThread(Long chatId, Integer msgId, AtomicBoolean done) {
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
                sender.editText(chatId, msgId,
                        "🔍 Получаю информацию о видео... (" + timeStr + ")");
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
