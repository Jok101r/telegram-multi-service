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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
            sender.sendText(chatId, "Пожалуйста, отправьте корректную ссылку на видео.");
            return;
        }

        sender.sendText(chatId, "🔍 Получаю информацию о видео...");

        try {
            String json = objectMapper.writeValueAsString(Map.of("url", url));
            RequestBody body = RequestBody.create(json, MediaType.parse("application/json"));
            Request request = new Request.Builder()
                    .url(videoDlUrl + "/info")
                    .post(body)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    String errorMsg = "Не удалось получить информацию о видео.";
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

                JsonNode info = objectMapper.readTree(response.body().string());
                String title = info.get("title").asText();
                JsonNode formats = info.get("formats");

                if (formats == null || formats.isEmpty()) {
                    sender.sendText(chatId, "Нет доступных форматов для скачивания.");
                    sessionService.setState(chatId, UserState.IDLE);
                    return;
                }

                // Store URL in session for the quality callback
                sessionService.setVideoUrl(chatId, url);

                // Build quality selection keyboard
                List<InlineKeyboardRow> rows = new ArrayList<>();
                for (JsonNode fmt : formats) {
                    String formatId = fmt.get("format_id").asText();
                    String label = fmt.get("label").asText();
                    boolean fits = fmt.has("fits_telegram") && fmt.get("fits_telegram").asBoolean(true);

                    if (!fits) {
                        label += " (не влезет)";
                    }

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

                String text = "🎬 " + title + "\n\nВыберите качество:";
                sender.sendWithKeyboard(chatId, text, keyboard);
                sessionService.setState(chatId, UserState.AWAITING_VIDEO_QUALITY);
            }
        } catch (Exception e) {
            e.printStackTrace();
            sender.sendText(chatId, "Ошибка при получении информации о видео, попробуйте ещё раз.");
            sessionService.setState(chatId, UserState.IDLE);
        }
    }
}
