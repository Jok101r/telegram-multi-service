package com.example.voicebot.handler;

import com.example.voicebot.session.UserSessionService;
import com.example.voicebot.session.UserState;
import com.example.voicebot.util.TelegramSender;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.message.Message;

import java.io.IOException;
import java.util.Map;

@Component
public class ImageGenHandler implements MessageHandler {

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final TelegramSender sender;
    private final UserSessionService sessionService;
    private final String imageGenUrl;

    public ImageGenHandler(
            @Qualifier("imageGenHttpClient") OkHttpClient httpClient,
            ObjectMapper objectMapper,
            TelegramSender sender,
            UserSessionService sessionService,
            @Value("${bot.image-gen-url}") String imageGenUrl) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.sender = sender;
        this.sessionService = sessionService;
        this.imageGenUrl = imageGenUrl;
    }

    @Override
    public boolean canHandle(Message message) {
        return message.hasText()
                && sessionService.getState(message.getChatId()) == UserState.AWAITING_IMAGE_PROMPT;
    }

    @Override
    public void handle(Message message) {
        Long chatId = message.getChatId();
        String prompt = message.getText();

        sender.sendText(chatId, "⏳ Генерирую картинку, это может занять несколько минут...");

        try {
            byte[] imageBytes = generateImage(prompt);
            sender.sendPhoto(chatId, imageBytes, prompt);
        } catch (Exception e) {
            e.printStackTrace();
            sender.sendText(chatId, "Ошибка при генерации картинки, попробуйте ещё раз.");
        }
    }

    private byte[] generateImage(String prompt) throws IOException {
        String json = objectMapper.writeValueAsString(Map.of("prompt", prompt));
        RequestBody body = RequestBody.create(json, MediaType.parse("application/json"));
        Request request = new Request.Builder().url(imageGenUrl).post(body).build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("image-gen-service вернул ошибку: " + response.code());
            }
            return response.body().bytes();
        }
    }
}
