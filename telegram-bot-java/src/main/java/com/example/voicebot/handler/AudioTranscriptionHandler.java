package com.example.voicebot.handler;

import com.example.voicebot.session.UserSessionService;
import com.example.voicebot.session.UserState;
import com.example.voicebot.util.TelegramSender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.GetFile;
import org.telegram.telegrambots.meta.api.objects.File;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.io.IOException;

@Component
public class AudioTranscriptionHandler implements MessageHandler {

    private final TelegramClient telegramClient;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final TelegramSender sender;
    private final UserSessionService sessionService;
    private final String botToken;
    private final String whisperUrl;

    public AudioTranscriptionHandler(
            TelegramClient telegramClient,
            OkHttpClient httpClient,
            ObjectMapper objectMapper,
            TelegramSender sender,
            UserSessionService sessionService,
            @Value("${bot.token}") String botToken,
            @Value("${bot.whisper-url}") String whisperUrl) {
        this.telegramClient = telegramClient;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.sender = sender;
        this.sessionService = sessionService;
        this.botToken = botToken;
        this.whisperUrl = whisperUrl;
    }

    @Override
    public boolean canHandle(Message message) {
        return (message.hasVoice() || message.hasAudio())
                && sessionService.getState(message.getChatId()) == UserState.AWAITING_AUDIO;
    }

    @Override
    public void handle(Message message) {
        String fileId = message.hasVoice()
                ? message.getVoice().getFileId()
                : message.getAudio().getFileId();
        try {
            byte[] audio = downloadTelegramFile(fileId);
            String text = transcribe(audio);
            sender.sendText(message.getChatId(),
                    text.isBlank() ? "Не удалось распознать речь." : text);
        } catch (Exception e) {
            e.printStackTrace();
            sender.sendText(message.getChatId(), "Ошибка при распознавании, попробуй ещё раз.");
        }
    }

    private byte[] downloadTelegramFile(String fileId) throws TelegramApiException, IOException {
        File tgFile = telegramClient.execute(new GetFile(fileId));
        String url = "https://api.telegram.org/file/bot" + botToken + "/" + tgFile.getFilePath();
        Request request = new Request.Builder().url(url).build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("Не удалось скачать файл: " + response.code());
            }
            return response.body().bytes();
        }
    }

    private String transcribe(byte[] audioBytes) throws IOException {
        RequestBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", "voice.ogg",
                        RequestBody.create(audioBytes, MediaType.parse("application/octet-stream")))
                .build();
        Request request = new Request.Builder().url(whisperUrl).post(body).build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("whisper-service вернул ошибку: " + response.code());
            }
            JsonNode json = objectMapper.readTree(response.body().string());
            return json.path("text").asText("");
        }
    }
}
