package com.example.voicebot.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.concurrent.TimeUnit;

@Configuration
public class BotConfig {

    @Bean
    public TelegramClient telegramClient(@Value("${bot.token}") String botToken) {
        return new OkHttpTelegramClient(botToken);
    }

    @Primary
    @Bean
    public OkHttpClient okHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    // Separate client for image-gen-service: CPU generation takes 3–5 minutes
    @Bean("imageGenHttpClient")
    public OkHttpClient imageGenHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(600, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .callTimeout(620, TimeUnit.SECONDS)
                .build();
    }

    // Separate client for video-dl-service: downloads can take several minutes
    @Bean("videoDlHttpClient")
    public OkHttpClient videoDlHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(300, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .callTimeout(320, TimeUnit.SECONDS)
                .build();
    }

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}
