package com.example.voicebot.util;

import com.example.voicebot.session.UserSessionService;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.send.SendVideo;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.io.ByteArrayInputStream;

@Component
public class TelegramSender {

    private final TelegramClient telegramClient;
    private final UserSessionService sessionService;

    public TelegramSender(TelegramClient telegramClient, UserSessionService sessionService) {
        this.telegramClient = telegramClient;
        this.sessionService = sessionService;
    }

    public void sendText(Long chatId, String text) {
        try {
            Message sent = telegramClient.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text(text)
                    .build());
            sessionService.trackMessage(chatId, sent.getMessageId());
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    public Integer sendTextAndGetId(Long chatId, String text) {
        try {
            Message sent = telegramClient.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text(text)
                    .build());
            sessionService.trackMessage(chatId, sent.getMessageId());
            return sent.getMessageId();
        } catch (TelegramApiException e) {
            e.printStackTrace();
            return null;
        }
    }

    public void editText(Long chatId, Integer messageId, String text) {
        try {
            telegramClient.execute(EditMessageText.builder()
                    .chatId(chatId)
                    .messageId(messageId)
                    .text(text)
                    .build());
        } catch (TelegramApiException e) {
            // ignore — message may have been deleted
        }
    }

    public void sendWithKeyboard(Long chatId, String text, InlineKeyboardMarkup keyboard) {
        try {
            Message sent = telegramClient.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text(text)
                    .replyMarkup(keyboard)
                    .build());
            sessionService.trackMessage(chatId, sent.getMessageId());
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    public void deleteMessage(Long chatId, Integer messageId) {
        try {
            telegramClient.execute(DeleteMessage.builder()
                    .chatId(chatId)
                    .messageId(messageId)
                    .build());
        } catch (TelegramApiException e) {
            // message already deleted or unavailable — ignore
        }
    }

    public void sendPhoto(Long chatId, byte[] imageBytes, String caption) {
        try {
            InputFile photo = new InputFile(new ByteArrayInputStream(imageBytes), "image.png");
            // Telegram limits photo captions to 1024 characters
            String safeCaption = caption.length() > 1024 ? caption.substring(0, 1021) + "..." : caption;
            Message sent = telegramClient.execute(SendPhoto.builder()
                    .chatId(chatId)
                    .photo(photo)
                    .caption(safeCaption)
                    .build());
            sessionService.trackMessage(chatId, sent.getMessageId());
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    public void sendVideo(Long chatId, byte[] videoBytes, String filename, String caption) {
        String safeCaption = caption.length() > 1024 ? caption.substring(0, 1021) + "..." : caption;
        try {
            InputFile video = new InputFile(new ByteArrayInputStream(videoBytes), filename);
            Message sent = telegramClient.execute(SendVideo.builder()
                    .chatId(chatId)
                    .video(video)
                    .caption(safeCaption)
                    .supportsStreaming(true)
                    .build());
            sessionService.trackMessage(chatId, sent.getMessageId());
        } catch (TelegramApiException e) {
            // Fallback: send as document if video format is not supported by Telegram
            sendDocument(chatId, videoBytes, filename, safeCaption);
        }
    }

    public void sendDocument(Long chatId, byte[] docBytes, String filename, String caption) {
        try {
            InputFile doc = new InputFile(new ByteArrayInputStream(docBytes), filename);
            String safeCaption = caption.length() > 1024 ? caption.substring(0, 1021) + "..." : caption;
            Message sent = telegramClient.execute(SendDocument.builder()
                    .chatId(chatId)
                    .document(doc)
                    .caption(safeCaption)
                    .build());
            sessionService.trackMessage(chatId, sent.getMessageId());
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    public void answerCallback(String callbackQueryId) {
        try {
            telegramClient.execute(AnswerCallbackQuery.builder()
                    .callbackQueryId(callbackQueryId)
                    .build());
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }
}
