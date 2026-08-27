package com.example.voicebot.bot;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.List;

@Component
public class BotLifecycle implements SmartLifecycle {

    private final VoiceBot bot;
    private final TelegramClient telegramClient;
    private final String botToken;

    private TelegramBotsLongPollingApplication app;
    private volatile boolean running;

    public BotLifecycle(VoiceBot bot, TelegramClient telegramClient, @Value("${bot.token}") String botToken) {
        this.bot = bot;
        this.telegramClient = telegramClient;
        this.botToken = botToken;
    }

    @Override
    public void start() {
        app = new TelegramBotsLongPollingApplication();
        try {
            app.registerBot(botToken, bot);
            telegramClient.execute(SetMyCommands.builder()
                    .commands(List.of(
                            BotCommand.builder().command("start").description("Начало работы").build(),
                            BotCommand.builder().command("clear").description("Очистить чат").build()
                    ))
                    .build());
            running = true;
            System.out.println("Бот запущен.");
        } catch (Exception e) {
            throw new RuntimeException("Не удалось запустить бота", e);
        }
    }

    @Override
    public void stop() {
        try {
            if (app != null) app.close();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            running = false;
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
